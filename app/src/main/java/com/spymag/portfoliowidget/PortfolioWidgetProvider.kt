package com.spymag.portfoliowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PortfolioWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "PortfolioWidget"
        private const val ACTION_UPDATE = "com.spymag.PORTFOLIO_UPDATE"
        private const val UPDATE_INTERVAL = 60_000L  // 60 seconds
        private const val CREDENTIALS_URL = "https://example.com/credentials"
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleNextUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.cancel(pendingIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE || intent.action == ACTION_UPDATE) {
            Thread {
                // Optional: test public endpoint to verify network
                //testPublicEndpoint(context)

                val creds = fetchCredentials()

                val bitvavoTotal = if (creds != null &&
                    !creds.bitvavoKey.isNullOrBlank() &&
                    !creds.bitvavoSecret.isNullOrBlank()
                ) {
                    try {
                        fetchBitvavoTotalValue(creds.bitvavoKey!!, creds.bitvavoSecret!!)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching Bitvavo total value", e)
                        "–"
                    }
                } else {
                    Log.w(TAG, "Missing Bitvavo credentials")
                    "Credentials unavailable"
                }

                val trading212Total = if (creds != null &&
                    !creds.trading212Key.isNullOrBlank()
                ) {
                    fetchTrading212PortfolioValue(creds.trading212Key!!)
                } else {
                    Log.w(TAG, "Missing Trading212 credentials")
                    "Credentials unavailable"
                }

                Log.d(TAG, "Fetched Bitvavo total value: $bitvavoTotal")
                Log.d(TAG, "Fetched Trading212 total value: $trading212Total")

                // Update widget with the total values
                updateWidgetTotals(context, bitvavoTotal, trading212Total)
                scheduleNextUpdate(context)
            }.start()
        }
    }

    private fun updateWidgetTotals(context: Context, bitvavoValue: String, trading212Value: String) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        for (appWidgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_portfolio)
            val display = "Bitvavo total: $bitvavoValue\nTrading212 total: $trading212Value"
            rv.setTextViewText(R.id.tvValue1, display)
            // Make the TextView clickable to trigger an update
            rv.setOnClickPendingIntent(
                R.id.tvValue1,
                pendingIntent(context)
            )
            mgr.updateAppWidget(appWidgetId, rv)
        }
    }

    private fun scheduleNextUpdate(context: Context) {
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !mgr.canScheduleExactAlarms()) {
            mgr.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + UPDATE_INTERVAL,
                pendingIntent(context)
            )
        } else {
            mgr.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + UPDATE_INTERVAL,
                pendingIntent(context)
            )
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
            action = ACTION_UPDATE
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Test a public Bitvavo endpoint to verify networking (optional).
     */
    private fun testPublicEndpoint(context: Context) {
        Thread {
            try {
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url("https://api.bitvavo.com/v2/markets")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    val json = JSONArray(resp.body!!.string())
                    val firstQuote = if (json.length() > 0) json.getJSONObject(0).getString("quote") else "none"
                    Log.d(TAG, "Public test - first market quote: $firstQuote")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Public test failed", e)
            }
        }.start()
    }

    /**
     * Compute HMAC SHA256 signature for Bitvavo auth.
     */
    private fun sign(
        secret: String,
        timestamp: String,
        method: String,
        path: String,
        body: String = ""
    ): String {
        val payload = timestamp + method.uppercase() + path + body
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        val hmacBytes = mac.doFinal(payload.toByteArray())
        return hmacBytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Fetches the total portfolio value by summing available and inOrder balances,
     * converting each to EUR using public price endpoints.
     */
    private fun fetchBitvavoTotalValue(apiKey: String, apiSecret: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val method = "GET"
        val requestPath = "/v2/balance"

        // 1) Authenticated request for balances
        val signature = sign(apiSecret, timestamp, method, requestPath)
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://api.bitvavo.com$requestPath")
            .addHeader("Bitvavo-Access-Key", apiKey)
            .addHeader("Bitvavo-Access-Timestamp", timestamp)
            .addHeader("Bitvavo-Access-Signature", signature)
            .addHeader("Bitvavo-Access-Window", "60000")
            .get()
            .build()

        val balancesJson = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        Log.d(TAG, "Bitvavo balances response: $balancesJson")
        val balances = JSONArray(balancesJson)

        // 2) Sum total value in EUR
        var total = 0.0
        val publicClient = OkHttpClient()
        for (i in 0 until balances.length()) {
            val obj = balances.getJSONObject(i)
            val available = obj.optDouble("available", 0.0)
            val inOrder = obj.optDouble("inOrder", 0.0)
            val amount = available + inOrder
            if (amount <= 0) continue

            val symbol = obj.getString("symbol")
            if (symbol.equals("EUR", ignoreCase = true)) {
                total += amount
            } else {
                val market = "$symbol-EUR"
                try {
                    val priceReq = Request.Builder()
                        .url("https://api.bitvavo.com/v2/ticker/price?market=$market")
                        .get()
                        .build()
                    publicClient.newCall(priceReq).execute().use { pr ->
                        val priceJson = pr.body?.string().orEmpty()
                        Log.d(TAG, "Ticker $market response: $priceJson")
                        val priceObj = JSONObject(priceJson)
                        val price = priceObj.optDouble("price", 0.0)
                        total += amount * price
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching price for $market", e)
                }
            }
        }
        return "€%.2f".format(total)
    }

    private data class Credentials(
        val bitvavoKey: String?,
        val bitvavoSecret: String?,
        val trading212Key: String?
    )

    private fun fetchCredentials(): Credentials? {
        return try {
            val client = OkHttpClient()
            val req = Request.Builder()
                .url(CREDENTIALS_URL)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Credential fetch failed: ${'$'}{resp.code}")
                    return null
                }
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val bitvavoKey = obj.optString("bitvavoApiKey")
                val bitvavoSecret = obj.optString("bitvavoApiSecret")
                val trading212Key = obj.optString("trading212ApiKey")
                if (bitvavoKey.isBlank() && bitvavoSecret.isBlank() && trading212Key.isBlank()) {
                    Log.e(TAG, "Credentials missing or invalid")
                    null
                } else {
                    Credentials(bitvavoKey, bitvavoSecret, trading212Key)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Credential fetch error", e)
            null
        }
    }

    private fun fetchTrading212PortfolioValue(apiKey: String): String {
        return try {
            val client = OkHttpClient()
            val req = Request.Builder()
                .url("https://example.com/trading212/portfolio")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Trading212 fetch failed: ${'$'}{resp.code}")
                    return "–"
                }
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val total = obj.optDouble("totalValue", 0.0)
                "€%.2f".format(total)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Trading212 value", e)
            "–"
        }
    }
}
