package com.spymag.portfoliowidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


class PortfolioWidgetProvider : AppWidgetProvider() {

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "PortfolioWidgetProvider"
        private const val ACTION_UPDATE = "com.spymag.PORTFOLIO_UPDATE"
        private const val ACTION_TOGGLE = "com.spymag.PORTFOLIO_TOGGLE"
        private const val UNIQUE_WORK_NAME = "PortfolioUpdateWork"
        private const val PREFS_NAME = "portfolio_widget_prefs"
        private const val PREF_HIDE_VALUES = "hide_values"
        private const val PREF_BITVAVO = "bitvavo_value"
        private const val PREF_TRADING212 = "trading212_value"
        private const val PREF_BITVAVO_TIME = "bitvavo_time"
        private const val PREF_TRADING212_TIME = "trading212_time"
        private const val UPDATE_INTERVAL = 15 * 60 * 1000L
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

        fun updatePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
                action = ACTION_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun togglePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            return PendingIntent.getBroadcast(
                context,
                2,
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
        when (intent.action) {
            ACTION_TOGGLE -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hidden = prefs.getBoolean(PREF_HIDE_VALUES, false)
                prefs.edit().putBoolean(PREF_HIDE_VALUES, !hidden).apply()
                refreshWidgetFromPrefs(context)
            }
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, ACTION_UPDATE -> {
                triggerUpdate(context)
            }
        }
    }

    private fun triggerUpdate(context: Context) {
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

    private fun updateWidgetTotal(context: Context, bitvavoValue: String, trading212Value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        if (bitvavoValue != "–") {
            editor.putString(PREF_BITVAVO, bitvavoValue)
            editor.putLong(PREF_BITVAVO_TIME, now)
        }
        if (trading212Value != "–") {
            editor.putString(PREF_TRADING212, trading212Value)
            editor.putLong(PREF_TRADING212_TIME, now)
        }
        editor.apply()
        refreshWidgetFromPrefs(context)
    }

    private fun refreshWidgetFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bitvavo = prefs.getString(PREF_BITVAVO, "–") ?: "–"
        val trading = prefs.getString(PREF_TRADING212, "–") ?: "–"
        val bitTime = prefs.getLong(PREF_BITVAVO_TIME, 0L)
        val tradingTime = prefs.getLong(PREF_TRADING212_TIME, 0L)

        val hidden = prefs.getBoolean(PREF_HIDE_VALUES, false)
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        for (appWidgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_portfolio)
            if (hidden) {
                rv.setTextViewText(R.id.tvValue1, "Bitvavo total: ******")
                rv.setTextViewText(R.id.tvValue2, "Trading212 total: ******")
                rv.setImageViewResource(R.id.ivToggle, R.drawable.ic_visibility_off)
            } else {
                val bitText = "Bitvavo total: $bitvavo (${formatTime(bitTime)})"
                val tradingText = "Trading212 total: $trading (${formatTime(tradingTime)})"
                rv.setTextViewText(R.id.tvValue1, bitText)
                rv.setTextViewText(R.id.tvValue2, tradingText)
                rv.setImageViewResource(R.id.ivToggle, R.drawable.ic_visibility)
            }
            rv.setOnClickPendingIntent(R.id.ivToggle, togglePendingIntent(context))
            rv.setOnClickPendingIntent(R.id.content_layout, updatePendingIntent(context))
            mgr.updateAppWidget(appWidgetId, rv)
        }
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "N/A"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
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
