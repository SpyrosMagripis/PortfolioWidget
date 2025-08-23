package com.spymag.portfoliowidget

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

private const val TAG = "Trading212Api"

fun fetchTrading212TotalValue(): String {
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

