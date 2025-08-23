package com.spymag.portfoliowidget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

suspend fun fetchBitvavoHoldings(): List<Holding> = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.BITVAVO_API_KEY
    val apiSecret = BuildConfig.BITVAVO_API_SECRET
    val timestamp = System.currentTimeMillis().toString()
    val method = "GET"
    val requestPath = "/v2/balance"
    val signature = sign(apiSecret, timestamp, method, requestPath)
    val req = Request.Builder()
        .url("https://api.bitvavo.com$requestPath")
        .addHeader("Bitvavo-Access-Key", apiKey)
        .addHeader("Bitvavo-Access-Timestamp", timestamp)
        .addHeader("Bitvavo-Access-Signature", signature)
        .addHeader("Bitvavo-Access-Window", "60000")
        .get()
        .build()
    val client = OkHttpClient()
    val balancesJson = client.newCall(req).execute().use { it.body?.string().orEmpty() }
    val balances = JSONArray(balancesJson)
    val holdings = mutableListOf<Holding>()
    for (i in 0 until balances.length()) {
        val obj = balances.getJSONObject(i)
        val amount = obj.optDouble("available", 0.0) + obj.optDouble("inOrder", 0.0)
        if (amount <= 0) continue
        val symbol = obj.getString("symbol")
        val value = if (symbol.equals("EUR", true)) {
            amount
        } else {
            val market = "$symbol-EUR"
            val priceReq = Request.Builder()
                .url("https://api.bitvavo.com/v2/ticker/price?market=$market")
                .get()
                .build()
            val priceJson = client.newCall(priceReq).execute().use { it.body?.string().orEmpty() }
            val priceObj = JSONObject(priceJson)
            val price = priceObj.optDouble("price", 0.0)
            amount * price
        }
        holdings += Holding(symbol, value)
    }
    holdings
}

private fun sign(secret: String, timestamp: String, method: String, path: String): String {
    val message = timestamp + method + path
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    val hash = mac.doFinal(message.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}

