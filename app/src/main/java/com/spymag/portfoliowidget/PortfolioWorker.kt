package com.spymag.portfoliowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PortfolioWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "PortfolioWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val totalValue = fetchTotalPortfolioValue()
            updateWidgetTotal(applicationContext, totalValue)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating portfolio", e)
            Result.retry()
        }
    }

    private fun updateWidgetTotal(context: Context, totalValue: String) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        for (appWidgetId in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_portfolio)
            rv.setTextViewText(R.id.tvValue1, "Bitvavo total: $totalValue")
            rv.setOnClickPendingIntent(
                R.id.tvValue1,
                PortfolioWidgetProvider.pendingIntent(context)
            )
            mgr.updateAppWidget(appWidgetId, rv)
        }
    }

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

    private fun fetchTotalPortfolioValue(): String {
        val apiKey = BuildConfig.BITVAVO_API_KEY
        val apiSecret = BuildConfig.BITVAVO_API_SECRET
        val timestamp = System.currentTimeMillis().toString()
        val method = "GET"
        val requestPath = "/v2/balance"

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
}
