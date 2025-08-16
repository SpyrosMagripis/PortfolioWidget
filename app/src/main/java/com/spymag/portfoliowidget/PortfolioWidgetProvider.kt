package com.spymag.portfoliowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


class PortfolioWidgetProvider : AppWidgetProvider() {

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val ACTION_UPDATE = "com.spymag.PORTFOLIO_UPDATE"
        private const val UNIQUE_WORK_NAME = "PortfolioUpdateWork"

        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
                action = ACTION_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        enqueuePeriodicWork(context)
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<PortfolioWorker>().build())
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE || intent.action == ACTION_UPDATE) {

            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<PortfolioWorker>().build())

            Thread {
                // Optional: test public endpoint to verify network
                //testPublicEndpoint(context)

                // Fetch totals from Bitvavo and Trading212
                val bitvavoValue = try {
                    fetchTotalPortfolioValue()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching Bitvavo total value", e)
                    "–"
                }

                val trading212Value = try {
                    fetchTrading212TotalValue()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching Trading212 total value", e)
                    "–"
                }

                Log.d(TAG, "Fetched Bitvavo total value: $bitvavoValue")
                Log.d(TAG, "Fetched Trading212 total value: $trading212Value")

                // Update widget with the totals
                updateWidgetTotal(context, bitvavoValue, trading212Value)
                scheduleNextUpdate(context)
            }.start()
        }
    }

    private fun updateWidgetTotal(context: Context, bitvavoValue: String, trading212Value: String) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        for (appWidgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_portfolio)
            // Display Bitvavo total
            rv.setTextViewText(R.id.tvValue1, "Bitvavo total: $bitvavoValue")
            // Display Trading212 total
            rv.setTextViewText(R.id.tvValue2, "Trading212 total: $trading212Value")
            // Make the TextViews clickable to trigger an update
            rv.setOnClickPendingIntent(R.id.tvValue1, pendingIntent(context))
            rv.setOnClickPendingIntent(R.id.tvValue2, pendingIntent(context))
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

    private fun enqueuePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<PortfolioWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }


    /**
     * Test a public Bitvavo endpoint to verify networking (optional).
     */
    private fun testPublicEndpoint(context: Context) {
        Thread {
            try {
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
    private fun fetchTotalPortfolioValue(): String {
        val apiKey = BuildConfig.BITVAVO_API_KEY
        val apiSecret = BuildConfig.BITVAVO_API_SECRET
        val timestamp = System.currentTimeMillis().toString()
        val method = "GET"
        val requestPath = "/v2/balance"

        // 1) Authenticated request for balances
        val signature = sign(apiSecret, timestamp, method, requestPath)
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
                    client.newCall(priceReq).execute().use { pr ->
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

    /**
     * Fetches the total portfolio value from Trading212 by summing
     * quantity × currentPrice (already in account currency).
     */
    private fun fetchTrading212TotalValue(): String {
        val apiKey = BuildConfig.TRADING212_API_KEY
        val baseUrl = "https://live.trading212.com"
        val client = OkHttpClient()

        // 1) Verify authentication
        val infoReq = Request.Builder()
            .url("$baseUrl/api/v0/equity/account/info")
            .addHeader("Authorization", apiKey)
            .get()
            .build()

        client.newCall(infoReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Trading212 auth failed: ${resp.code}")
            }
        }

        // 2) Fetch portfolio positions
        val portReq = Request.Builder()
            .url("$baseUrl/api/v0/equity/portfolio")
            .addHeader("Authorization", apiKey)
            .get()
            .build()

        val portJson = client.newCall(portReq).execute().use { it.body?.string().orEmpty() }
        Log.d(TAG, "Trading212 portfolio response: $portJson")
        val positions = JSONArray(portJson)

        // 3) Sum quantity × currentPrice
        var total = 0.0
        for (i in 0 until positions.length()) {
            val p = positions.getJSONObject(i)
            val quantity = p.optDouble("quantity", 0.0)
            val currentPrice = p.optDouble("currentPrice", 0.0)
            total += quantity * currentPrice
        }

        return "€%.2f".format(total)
    }
}
