package com.spymag.portfoliowidget.data

import android.util.Log
import com.spymag.portfoliowidget.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class Trading212Client(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "Trading212Client"
        private const val BASE_URL = "https://live.trading212.com"
    }

    /**
     * Fetches the total portfolio value from Trading212 by summing
     * quantity × currentPrice (already in account currency).
     */
    suspend fun fetchTrading212TotalValue(): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.TRADING212_API_KEY

        // 1) Verify authentication
        val infoReq = Request.Builder()
            .url("$BASE_URL/api/v0/equity/account/info")
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
            .url("$BASE_URL/api/v0/equity/portfolio")
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

        "€%.2f".format(total)
    }
}
