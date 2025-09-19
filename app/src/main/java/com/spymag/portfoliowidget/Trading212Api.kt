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
    val fxProvider = FxRateProvider(client, baseUrl, apiKey, accountCurrency)

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
    val instrumentCurrency = listOfNotNull(
        position.optStringOrNull("valueCurrencyCode"),
        position.optStringOrNull("currencyCode"),
        position.optStringOrNull("currency"),
        position.optStringOrNull("instrumentCurrency"),
        position.optStringOrNull("fxCurrency"),
        position.optStringOrNull("valueCurrency"),
        position.optStringOrNull("currentValueCurrency"),
        position.optValue("value")?.let { parseCurrency(it) },
        position.optValue("currentValue")?.let { parseCurrency(it) },
        position.optValue("marketValue")?.let { parseCurrency(it) },
        position.optValue("valuation")?.let { parseCurrency(it) },
        position.optValue("currentPrice")?.let { parseCurrency(it) },
        position.optValue("price")?.let { parseCurrency(it) },
        position.optValue("lastPrice")?.let { parseCurrency(it) },
        position.optValue("averagePrice")?.let { parseCurrency(it) },
        position.optValue("avgPrice")?.let { parseCurrency(it) },
        findCurrencyRecursively(position)
    ).firstOrNull()?.uppercase(Locale.US) ?: accountCurrencyUpper

    val instrumentValue = position.optParsedDouble("value", "currentValue", "marketValue", "valuation")
        ?: run {
            val quantity = position.optParsedDouble("quantity") ?: 0.0
            val price = position.optParsedDouble("currentPrice")
                ?: position.optParsedDouble("price")
                ?: 0.0
            quantity * price
        }

    if (instrumentCurrency != accountCurrencyUpper) {
        Log.i(
            TAG,
            "Preparing conversion for $ticker: ${String.format(Locale.US, "%.2f", instrumentValue)} " +
                "$instrumentCurrency -> $accountCurrencyUpper"
        )
    } else {
        Log.d(TAG, "Using account currency for $ticker: $accountCurrencyUpper")
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
    private val baseUrl: String,
    private val apiKey: String,
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
        val formattedAmount = String.format(Locale.US, "%.2f", amount)
        val formattedConverted = String.format(Locale.US, "%.2f", converted)
        val formattedRate = String.format(Locale.US, "%.6f", rate)
        Log.i(
            TAG,
            "Converted $ticker: $formattedAmount $source -> $formattedConverted $targetCurrency at rate $formattedRate"
        )
        return converted
    }

    private fun fetchRate(fromCurrency: String): Double {
        fetchFromTrading212(fromCurrency)?.let { rate ->
            Log.i(
                TAG,
                "FX rate retrieval successful via Trading212: $fromCurrency->$targetCurrency = $rate"
            )
            return rate
        }

        val rate = fetchFromExchangeRateHost(fromCurrency)
            ?: throw IOException("Unable to fetch FX rate from $fromCurrency to $targetCurrency")
        Log.i(
            TAG,
            "FX rate retrieval successful via ExchangeRateHost: $fromCurrency->$targetCurrency = $rate"
        )
        return rate
    }

    private fun fetchFromTrading212(fromCurrency: String): Double? {
        val pair = "${fromCurrency.uppercase(Locale.US)}$targetCurrency"
        val endpoints = listOf(
            "$baseUrl/api/v0/equity/fx/rate?from=$fromCurrency&to=$targetCurrency",
            "$baseUrl/api/v0/equity/fx/rates/$pair",
            "$baseUrl/api/v0/equity/prices?tickers=$pair",
            "$baseUrl/api/v0/equity/prices/$pair"
        )

        for (endpoint in endpoints) {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", apiKey)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(
                            TAG,
                            "Trading212 FX endpoint $endpoint responded with ${resp.code}"
                        )
                        return@use
                    }

                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) {
                        Log.d(TAG, "Trading212 FX endpoint $endpoint returned empty body")
                        return@use
                    }

                    val rate = parseRateResponse(body)
                    if (rate != null && rate > 0) {
                        Log.i(TAG, "Trading212 FX endpoint $endpoint yielded rate $rate")
                        return rate
                    } else {
                        Log.d(
                            TAG,
                            "Unable to parse FX rate from Trading212 endpoint $endpoint: $body"
                        )
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "Trading212 FX request failed for $endpoint", e)
            } catch (e: Exception) {
                Log.d(TAG, "Unexpected error parsing Trading212 FX response from $endpoint", e)
            }
        }

        return null
    }

    private fun parseRateResponse(body: String): Double? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        return try {
            when {
                trimmed.startsWith("{") -> parseDouble(JSONObject(trimmed))
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    var idx = 0
                    while (idx < array.length()) {
                        val candidate = array.opt(idx)
                        val rate = parseDouble(candidate)
                        if (rate != null && rate > 0) {
                            return rate
                        }
                        idx++
                    }
                    null
                }
                else -> trimmed.toDoubleOrNull()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse FX rate body: $trimmed", e)
            null
        }
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

private fun JSONObject.optValue(vararg keys: String): Any? {
    for (key in keys) {
        if (has(key)) {
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) {
                return value
            }
        }
    }
    return null
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
                "closePrice",
                "rate",
                "fxRate",
                "conversionRate",
                "exchangeRate"
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

private fun findCurrencyRecursively(value: Any?): String? {
    parseCurrency(value)?.let { return it }
    return when (value) {
        is JSONObject -> {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val nested = value.opt(key)
                if (nested != null && nested != JSONObject.NULL) {
                    findCurrencyRecursively(nested)?.let { return it }
                }
            }
            null
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val nested = value.opt(index)
                if (nested != null && nested != JSONObject.NULL) {
                    findCurrencyRecursively(nested)?.let { return it }
                }
            }
            null
        }
        else -> null
    }
}

