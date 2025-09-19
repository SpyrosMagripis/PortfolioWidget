package com.spymag.portfoliowidget

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Currency
import java.util.Locale

private const val TAG = "Trading212Api"

fun fetchTrading212TotalValue(): String {
    val portfolio = fetchTrading212Portfolio()
    val total = portfolio.holdings.sumOf { it.value }
    return formatAccountValue(total, portfolio.accountCurrency)
}

fun fetchTrading212Holdings(): List<Holding> {
    return fetchTrading212Portfolio().holdings
}

private data class Trading212Portfolio(
    val accountCurrency: String,
    val holdings: List<Holding>
)

private fun fetchTrading212Portfolio(): Trading212Portfolio {
    val apiKey = BuildConfig.TRADING212_API_KEY
    val baseUrl = "https://live.trading212.com"
    val client = OkHttpClient()

    val accountCurrency = fetchAccountCurrency(client, baseUrl, apiKey)
    val positions = fetchPortfolioPositions(client, baseUrl, apiKey)
    val fxProvider = FxRateProvider(client, accountCurrency)

    val holdings = mutableListOf<Holding>()
    for (i in 0 until positions.length()) {
        val position = positions.getJSONObject(i)
        val ticker = extractTicker(position)
        val value = computePositionValueInAccountCurrency(position, accountCurrency, fxProvider, ticker)
        holdings += Holding(ticker, value)
    }

    return Trading212Portfolio(accountCurrency, holdings)
}

private fun fetchAccountCurrency(client: OkHttpClient, baseUrl: String, apiKey: String): String {
    val infoReq = Request.Builder()
        .url("$baseUrl/api/v0/equity/account/info")
        .addHeader("Authorization", apiKey)
        .get()
        .build()

    val infoJson = client.newCall(infoReq).execute().use { resp ->
        if (!resp.isSuccessful) {
            throw Exception("Trading212 auth failed: ${resp.code}")
        }
        resp.body?.string().orEmpty()
    }

    if (infoJson.isBlank()) {
        return "EUR"
    }

    return try {
        val info = JSONObject(infoJson)
        val directFields = listOf(
            info.optStringOrNull("accountCurrencyCode"),
            info.optStringOrNull("currencyCode"),
            info.optStringOrNull("accountCurrency"),
            info.optStringOrNull("currency")
        )
        for (field in directFields) {
            if (!field.isNullOrBlank()) {
                return field.uppercase(Locale.US)
            }
        }

        info.optJSONObject("accountCurrency")?.let { nested ->
            parseCurrency(nested)?.let { return it.uppercase(Locale.US) }
        }

        parseCurrency(info)?.let { return it.uppercase(Locale.US) }

        "EUR"
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse Trading212 account info response", e)
        "EUR"
    }
}

private fun fetchPortfolioPositions(client: OkHttpClient, baseUrl: String, apiKey: String): JSONArray {
    val portReq = Request.Builder()
        .url("$baseUrl/api/v0/equity/portfolio")
        .addHeader("Authorization", apiKey)
        .get()
        .build()

    val portJson = client.newCall(portReq).execute().use { resp ->
        if (!resp.isSuccessful) {
            throw Exception("Trading212 portfolio fetch failed: ${resp.code}")
        }
        resp.body?.string().orEmpty()
    }
    Log.d(TAG, "Trading212 portfolio response: $portJson")
    return JSONArray(portJson)
}

private fun extractTicker(position: JSONObject): String {
    return position.optStringOrNull("ticker")
        ?: position.optStringOrNull("symbol")
        ?: position.optStringOrNull("code")
        ?: position.optStringOrNull("name")
        ?: "Unknown"
}

private fun computePositionValueInAccountCurrency(
    position: JSONObject,
    accountCurrency: String,
    fxProvider: FxRateProvider,
    ticker: String
): Double {
    position.optParsedDouble("valueInAccountCurrency", "currentValueInAccountCurrency")?.let { return it }

    position.optJSONObject("valueInAccountCurrency")?.let { nested ->
        parseDouble(nested)?.let { return it }
    }

    val accountCurrencyUpper = accountCurrency.uppercase(Locale.US)
    val instrumentCurrency = (
        position.optCurrency("valueCurrencyCode", "currencyCode", "currency")
            ?: position.optJSONObject("currentPrice")?.let { parseCurrency(it) }
            ?: position.optJSONObject("price")?.let { parseCurrency(it) }
            ?: accountCurrencyUpper
        ).uppercase(Locale.US)

    val instrumentValue = position.optParsedDouble("value", "currentValue", "marketValue", "valuation")
        ?: run {
            val quantity = position.optParsedDouble("quantity") ?: 0.0
            val price = position.optParsedDouble("currentPrice")
                ?: position.optParsedDouble("price")
                ?: 0.0
            quantity * price
        }

    if (instrumentCurrency == accountCurrencyUpper) {
        return instrumentValue
    }

    return fxProvider.convert(instrumentValue, instrumentCurrency, ticker)
}

private fun formatAccountValue(amount: Double, currencyCode: String): String {
    val normalizedCode = currencyCode.uppercase(Locale.US)
    val symbol = try {
        Currency.getInstance(normalizedCode).symbol
    } catch (e: IllegalArgumentException) {
        normalizedCode
    }
    val prefix = if (symbol.all { it.isLetter() }) "$symbol " else symbol
    return String.format(Locale.getDefault(), "%s%.2f", prefix, amount)
}

private class FxRateProvider(
    private val client: OkHttpClient,
    accountCurrency: String
) {
    private val targetCurrency = accountCurrency.uppercase(Locale.US)
    private val cache = mutableMapOf<String, Double>()

    fun convert(amount: Double, fromCurrency: String, ticker: String): Double {
        if (amount == 0.0) {
            return 0.0
        }
        val source = fromCurrency.uppercase(Locale.US)
        if (source == targetCurrency || source.isBlank()) {
            return amount
        }

        val rate = cache[source]?.also {
            Log.i(TAG, "Using cached FX rate $source->$targetCurrency = $it")
        } ?: fetchRate(source).also { fetchedRate ->
            cache[source] = fetchedRate
        }

        val converted = amount * rate
        if (source == "USD" && targetCurrency == "EUR") {
            val formattedAmount = String.format(Locale.US, "%.2f", amount)
            val formattedConverted = String.format(Locale.US, "%.2f", converted)
            val formattedRate = String.format(Locale.US, "%.6f", rate)
            Log.i(
                TAG,
                "Converted $ticker: $formattedAmount USD -> $formattedConverted EUR at rate $formattedRate"
            )
        }
        return converted
    }

    private fun fetchRate(fromCurrency: String): Double {
        val rate = fetchFromExchangeRateHost(fromCurrency)
            ?: throw IOException("Unable to fetch FX rate from $fromCurrency to $targetCurrency")
        Log.i(TAG, "FX rate retrieval successful: $fromCurrency->$targetCurrency = $rate")
        return rate
    }

    private fun fetchFromExchangeRateHost(fromCurrency: String): Double? {
        val url = "https://api.exchangerate.host/convert?from=$fromCurrency&to=$targetCurrency"
        Log.i(TAG, "Requesting FX rate $fromCurrency->$targetCurrency from ExchangeRateHost")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "FX rate retrieval failed: ExchangeRateHost responded ${resp.code}")
                    return null
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Log.w(TAG, "FX rate retrieval failed: ExchangeRateHost returned empty body")
                    return null
                }

                val json = JSONObject(body)
                val result = json.optDouble("result", Double.NaN)
                if (!result.isNaN() && result > 0) {
                    Log.i(TAG, "ExchangeRateHost result for $fromCurrency->$targetCurrency: $result")
                    return result
                }

                val infoRate = json.optJSONObject("info")?.optDouble("rate")?.takeIf { !it.isNaN() && it > 0 }
                if (infoRate != null) {
                    Log.i(TAG, "ExchangeRateHost info rate for $fromCurrency->$targetCurrency: $infoRate")
                } else {
                    Log.w(
                        TAG,
                        "FX rate retrieval failed: ExchangeRateHost response missing rate for $fromCurrency->$targetCurrency"
                    )
                }
                infoRate
            }
        } catch (e: IOException) {
            Log.w(TAG, "FX rate retrieval failed: ExchangeRateHost request error", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "FX rate retrieval failed: unable to parse ExchangeRateHost response", e)
            null
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    val value = optString(key, "")
    return value.takeIf { it.isNotBlank() }
}

private fun JSONObject.optParsedDouble(vararg keys: String): Double? {
    for (key in keys) {
        if (has(key)) {
            parseDouble(opt(key))?.let { return it }
        }
    }
    return null
}

private fun JSONObject.optCurrency(vararg keys: String): String? {
    for (key in keys) {
        if (has(key)) {
            parseCurrency(opt(key))?.let { return it }
        }
    }
    return null
}

private fun parseDouble(value: Any?): Double? {
    return when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        is JSONObject -> {
            val candidateKeys = listOf(
                "value",
                "amount",
                "price",
                "mid",
                "midPrice",
                "ask",
                "askPrice",
                "bid",
                "bidPrice",
                "last",
                "lastPrice",
                "close",
                "closePrice"
            )
            for (key in candidateKeys) {
                parseDouble(value.opt(key))?.let { return it }
            }
            null
        }
        is JSONArray -> {
            if (value.length() > 0) parseDouble(value.opt(0)) else null
        }
        else -> null
    }
}

private fun parseCurrency(value: Any?): String? {
    return when (value) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> {
            val candidateKeys = listOf("currencyCode", "code", "currency")
            for (key in candidateKeys) {
                parseCurrency(value.opt(key))?.let { return it }
            }
            null
        }
        else -> null
    }
}

