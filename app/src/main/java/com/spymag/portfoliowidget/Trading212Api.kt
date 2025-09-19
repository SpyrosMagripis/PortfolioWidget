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

private val COUNTRY_TO_CURRENCY = mapOf(
    "US" to "USD",
    "CA" to "CAD",
    "GB" to "GBP",
    "UK" to "GBP",
    "IE" to "EUR",
    "DE" to "EUR",
    "FR" to "EUR",
    "NL" to "EUR",
    "BE" to "EUR",
    "ES" to "EUR",
    "IT" to "EUR",
    "PT" to "EUR",
    "AT" to "EUR",
    "FI" to "EUR",
    "GR" to "EUR",
    "LU" to "EUR",
    "LT" to "EUR",
    "LV" to "EUR",
    "EE" to "EUR",
    "CY" to "EUR",
    "MT" to "EUR",
    "SK" to "EUR",
    "SI" to "EUR",
    "CH" to "CHF",
    "SE" to "SEK",
    "NO" to "NOK",
    "DK" to "DKK",
    "PL" to "PLN",
    "CZ" to "CZK",
    "HU" to "HUF",
    "RO" to "RON",
    "BG" to "BGN",
    "HR" to "EUR",
    "IS" to "ISK",
    "JP" to "JPY",
    "HK" to "HKD",
    "SG" to "SGD",
    "CN" to "CNY",
    "KR" to "KRW",
    "IN" to "INR",
    "AU" to "AUD",
    "NZ" to "NZD",
    "ZA" to "ZAR",
    "BR" to "BRL",
    "MX" to "MXN",
    "TR" to "TRY",
    "IL" to "ILS",
    "AE" to "AED",
    "SA" to "SAR",
    "QA" to "QAR",
    "KW" to "KWD",
    "BH" to "BHD",
    "AR" to "ARS",
    "CL" to "CLP",
    "ID" to "IDR",
    "MY" to "MYR",
    "TH" to "THB",
    "PH" to "PHP"
)

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

private fun inferCurrencyFromTicker(ticker: String?): String? {
    if (ticker.isNullOrBlank()) {
        return null
    }

    val normalized = ticker.uppercase(Locale.US)
    val separators = charArrayOf('_', '-', '.', ' ')
    val parts = normalized.split(*separators).filter { it.isNotBlank() }

    for (part in parts) {
        COUNTRY_TO_CURRENCY[part]?.let { return it }
        if (looksLikeCurrencyCode(part)) {
            return part
        }
    }

    COUNTRY_TO_CURRENCY.entries.firstOrNull { (country, _) ->
        normalized.contains("_${country}_") ||
            normalized.endsWith("_${country}") ||
            normalized.startsWith("${country}_")
    }?.let { return it.value }

    return null
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
    val tickerCurrency = inferCurrencyFromTicker(ticker)?.uppercase(Locale.US)
    val metadataCurrency = listOfNotNull(
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
    ).firstOrNull()?.uppercase(Locale.US)

    val rawCurrency = tickerCurrency ?: metadataCurrency
    val instrumentCurrency = rawCurrency?.takeIf { looksLikeCurrencyCode(it) } ?: accountCurrencyUpper
    val detectionSource = when {
        tickerCurrency != null -> "ticker"
        metadataCurrency != null -> "position metadata"
        else -> "account currency default"
    }
    Log.d(
        TAG,
        "Detected currency for $ticker via $detectionSource: $instrumentCurrency (account $accountCurrencyUpper)"
    )

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
        return fxProvider.convert(instrumentValue, instrumentCurrency, ticker)
    }

    Log.i(
        TAG,
        "No FX conversion required for $ticker: ${String.format(Locale.US, "%.2f", instrumentValue)} $accountCurrencyUpper"
    )
    return instrumentValue
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
        if (!looksLikeCurrencyCode(source)) {
            Log.w(
                TAG,
                "Skipping FX conversion for $ticker: invalid currency code '$source'"
            )
            return amount
        }
        if (source == targetCurrency || source.isBlank()) {
            return amount
        }

        val rate = cache[source]?.also {
            Log.i(TAG, "Using cached FX rate $source->$targetCurrency = $it for $ticker")
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
        val fetchers = listOf(
            "ExchangeRateHost" to ::fetchFromExchangeRateHost,
            "Frankfurter" to ::fetchFromFrankfurter
        )

        for ((name, fetcher) in fetchers) {
            val rate = fetcher(fromCurrency)
            if (rate != null) {
                Log.i(
                    TAG,
                    "FX rate retrieval successful via $name: $fromCurrency->$targetCurrency = $rate"
                )
                return rate
            }
        }

        throw IOException("Unable to fetch FX rate from $fromCurrency to $targetCurrency")
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

    private fun fetchFromFrankfurter(fromCurrency: String): Double? {
        val url = "https://api.frankfurter.app/latest?from=$fromCurrency&to=$targetCurrency"
        Log.i(TAG, "Requesting FX rate $fromCurrency->$targetCurrency from Frankfurter")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "FX rate retrieval failed: Frankfurter responded ${resp.code}")
                    return null
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Log.w(TAG, "FX rate retrieval failed: Frankfurter returned empty body")
                    return null
                }

                val json = JSONObject(body)
                val rates = json.optJSONObject("rates")
                if (rates == null) {
                    Log.w(TAG, "FX rate retrieval failed: Frankfurter response missing rates for $fromCurrency->$targetCurrency")
                    return null
                }

                val rate = rates.optDouble(targetCurrency, Double.NaN)
                if (!rate.isNaN() && rate > 0) {
                    Log.i(TAG, "Frankfurter rate for $fromCurrency->$targetCurrency: $rate")
                    return rate
                }

                Log.w(
                    TAG,
                    "FX rate retrieval failed: Frankfurter response missing rate for $fromCurrency->$targetCurrency"
                )
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "FX rate retrieval failed: Frankfurter request error", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "FX rate retrieval failed: unable to parse Frankfurter response", e)
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
        is String -> {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val normalized = trimmed.uppercase(Locale.US)
                normalized.takeIf { looksLikeCurrencyCode(it) }
            }
        }
        is JSONObject -> {
            val candidateKeys = listOf("currencyCode", "code", "currency")
            for (key in candidateKeys) {
                parseCurrency(value.opt(key))?.let { return it }
            }
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (looksLikeCurrencyCode(key)) {
                    return key.uppercase(Locale.US)
                }
            }
            null
        }
        else -> null
    }
}

private fun looksLikeCurrencyCode(value: String): Boolean {
    if (value.isBlank()) {
        return false
    }

    val normalized = value.trim().uppercase(Locale.US)
    if (normalized.length != 3 || !normalized.all { it.isLetter() }) {
        return normalized == "GBX"
    }

    return try {
        Currency.getInstance(normalized)
        true
    } catch (e: IllegalArgumentException) {
        normalized == "GBX"
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

