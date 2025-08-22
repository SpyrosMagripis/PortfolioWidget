package com.spymag.portfoliowidget.data

import android.util.Log
import com.spymag.portfoliowidget.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BitvavoClient(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "BitvavoClient"
        private const val BASE_URL = "https://api.bitvavo.com"
    }

    /**
     * Fetches the total portfolio value by summing available and inOrder balances,
     * converting each to EUR using public price endpoints.
     */
    suspend fun fetchTotalPortfolioValue(): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.BITVAVO_API_KEY
        val apiSecret = BuildConfig.BITVAVO_API_SECRET
        val timestamp = System.currentTimeMillis().toString()
        val method = "GET"
        val requestPath = "/v2/balance"

        // 1) Authenticated request for balances
        val signature = sign(apiSecret, timestamp, method, requestPath)
        val req = Request.Builder()
            .url("$BASE_URL$requestPath")
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
            val available = obj.optString("available").toDoubleOrNull() ?: 0.0
            val inOrder = obj.optString("inOrder").toDoubleOrNull() ?: 0.0
            val amount = available + inOrder
            if (amount <= 0) continue

            val symbol = obj.getString("symbol")
            if (symbol.equals("EUR", ignoreCase = true)) {
                total += amount
            } else {
                val market = "$symbol-EUR"
                try {
                    val priceReq = Request.Builder()
                        .url("$BASE_URL/v2/ticker/price?market=$market")
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
        "€%.2f".format(total)
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
}
