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
private val CASH_KEYWORDS = listOf(
    "CASH",
    "FREEFUNDS",
    "AVAILABLEFUNDS",
    "AVAILABLECASH",
    "FREECASH",
    "TOTALCASH",
    "CASHBALANCE",
    "CASHVALUE",
    "LIQUIDFUNDS",
    "UNINVESTED"
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

private data class AccountSnapshot(
    val accountCurrency: String,
    val cashBalance: Double?
)

private fun fetchTrading212Portfolio(): Trading212Portfolio {
    val apiKey = BuildConfig.TRADING212_API_KEY
    val baseUrl = "https://live.trading212.com"
    val client = OkHttpClient()

    val accountSnapshot = fetchAccountSnapshot(client, baseUrl, apiKey)
    val accountCurrency = accountSnapshot.accountCurrency
    val positions = fetchPortfolioPositions(client, baseUrl, apiKey)
    val fxProvider = FxRateProvider(client, accountCurrency)

    val holdings = mutableListOf<Holding>()
    for (i in 0 until positions.length()) {
        val position = positions.getJSONObject(i)
        val ticker = extractTicker(position)
        val value = computePositionValueInAccountCurrency(position, accountCurrency, fxProvider, ticker)
        holdings += Holding(ticker, value)
    }

    val cashBalance = accountSnapshot.cashBalance ?: fetchCashBalance(client, baseUrl, apiKey, accountCurrency)
    if (cashBalance != null) {
        val formattedCash = String.format(Locale.US, "%.2f", cashBalance)
        Log.i(
            TAG,
            "Trading212 cash balance detected: $formattedCash ${accountCurrency.uppercase(Locale.US)}"
        )
        holdings += Holding("Cash", cashBalance)
    } else {
        Log.i(TAG, "No Trading212 cash balance found in account info or cash endpoint")
    }

    return Trading212Portfolio(accountCurrency, holdings)
}

private fun fetchAccountSnapshot(client: OkHttpClient, baseUrl: String, apiKey: String): AccountSnapshot {
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
        return AccountSnapshot("EUR", null)
    }

    return try {
        val info = JSONObject(infoJson)
        val currency = extractAccountCurrency(info) ?: "EUR"
        val cash = extractCashBalance(info)
        if (cash != null) {
            val formattedCash = String.format(Locale.US, "%.2f", cash)
            Log.i(TAG, "Trading212 account info cash balance: $formattedCash ${currency.uppercase(Locale.US)}")
        } else {
            Log.i(TAG, "No cash balance found in Trading212 account info response")
        }
        AccountSnapshot(currency, cash)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse Trading212 account info response", e)
        AccountSnapshot("EUR", null)
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

private fun fetchCashBalance(
    client: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    accountCurrency: String
): Double? {
    val request = Request.Builder()
        .url("$baseUrl/api/v0/equity/account/cash")
        .addHeader("Authorization", apiKey)
        .get()
        .build()

    Log.i(TAG, "Requesting Trading212 cash balance from cash endpoint")

    return try {
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Trading212 cash endpoint responded ${resp.code}")
                return null
            }

            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "Trading212 cash endpoint returned empty body")
                return null
            }

            val json = JSONObject(body)
            val cash = extractCashBalance(json)
            if (cash != null) {
                val formattedCash = String.format(Locale.US, "%.2f", cash)
                Log.i(TAG, "Trading212 cash endpoint balance: $formattedCash ${accountCurrency.uppercase(Locale.US)}")
            } else {
                Log.w(TAG, "Trading212 cash endpoint response missing cash balance")
            }
            cash
        }
    } catch (e: IOException) {
        Log.w(TAG, "Trading212 cash endpoint request error", e)
        null
    } catch (e: Exception) {
        Log.w(TAG, "Unable to parse Trading212 cash endpoint response", e)
        null
    }
}

private fun extractAccountCurrency(info: JSONObject): String? {
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

    val candidateContainers = listOf("account", "summary", "balances", "accountSummary", "profile")
    for (key in candidateContainers) {
        when (val value = info.opt(key)) {
            is JSONObject -> parseCurrency(value)?.let { return it.uppercase(Locale.US) }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) {
                        parseCurrency(item)?.let { return it.uppercase(Locale.US) }
                    }
                }
            }
        }
    }

    return null
}

private fun extractCashBalance(root: JSONObject): Double? {
    val queue: ArrayDeque<Any> = ArrayDeque()
    val visited = mutableSetOf<Int>()
    queue += root

    while (queue.isNotEmpty()) {
        when (val node = queue.removeFirst()) {
            is JSONObject -> {
                val identity = System.identityHashCode(node)
                if (!visited.add(identity)) {
                    continue
                }

                val typeValue = node.optStringOrNull("type")
                    ?: node.optStringOrNull("name")
                    ?: node.optStringOrNull("category")
                if (!typeValue.isNullOrBlank() && typeValue.uppercase(Locale.US).contains("CASH")) {
                    val typeCandidates = listOf("value", "amount", "balance", "cash", "available", "free")
                    for (candidateKey in typeCandidates) {
                        parseCashCandidate(node.opt(candidateKey))?.let { return it }
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    val normalizedKey = key.trim().uppercase(Locale.US)
                    if (CASH_KEYWORDS.any { normalizedKey.contains(it) }) {
                        parseCashCandidate(value)?.let { return it }
                    }

                    if (value is JSONObject || value is JSONArray) {
                        queue += value
                    }
                }
            }

            is JSONArray -> {
                val identity = System.identityHashCode(node)
                if (!visited.add(identity)) {
                    continue
                }
                for (i in 0 until node.length()) {
                    val item = node.opt(i)
                    if (item is JSONObject || item is JSONArray) {
                        queue += item
                    } else {
                        parseCashCandidate(item)?.let { return it }
                    }
                }
            }
        }
    }

    return null
}

private fun parseCashCandidate(value: Any?): Double? {
    return when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        is JSONObject -> {
            parseDouble(value)?.let { return it }
            val nestedKeys = listOf("balance", "amount", "cash", "available", "free", "total", "value")
            for (key in nestedKeys) {
                parseDouble(value.opt(key))?.let { return it }
            }
            null
        }
        is JSONArray -> {
            for (i in 0 until value.length()) {
                parseCashCandidate(value.opt(i))?.let { return it }
            }
            null
        }
        else -> null
    }
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

    val instrumentValue = position.optParsedDouble("value", "currentValue", "marketValue", "valuation")
        ?: run {
            val quantity = position.optParsedDouble("quantity") ?: 0.0
            val price = position.optParsedDouble("currentPrice")
                ?: position.optParsedDouble("price")
                ?: 0.0
            quantity * price
        }

    val accountCurrencyUpper = accountCurrency.uppercase(Locale.US)
    val tickerUpper = ticker.uppercase(Locale.US)
    val requiresUsdConversion = tickerUpper.contains("US") && accountCurrencyUpper != "USD"

    if (requiresUsdConversion) {
        Log.i(
            TAG,
            "Preparing conversion for $ticker: ${String.format(Locale.US, "%.2f", instrumentValue)} USD -> $accountCurrencyUpper"
        )
        return fxProvider.convertUsdToAccount(instrumentValue, ticker)
    }

    val reason = if (tickerUpper.contains("US")) {
        "account currency already USD"
    } else {
        "ticker has no US segment"
    }
    Log.i(
        TAG,
        "No USD conversion required for $ticker ($reason): ${String.format(Locale.US, "%.2f", instrumentValue)} $accountCurrencyUpper"
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
    private var usdToTargetRate: Double? = null

    fun convertUsdToAccount(amount: Double, ticker: String): Double {
        if (amount == 0.0 || targetCurrency == "USD") {
            if (targetCurrency == "USD") {
                Log.i(TAG, "Account currency is USD; skipping conversion for $ticker")
            }
            return amount
        }

        val rate = usdToTargetRate?.also {
            Log.i(TAG, "Using cached FX rate USD->$targetCurrency = $it for $ticker")
        } ?: fetchUsdRate().also { fetchedRate ->
            usdToTargetRate = fetchedRate
        }

        val converted = amount * rate
        val formattedAmount = String.format(Locale.US, "%.2f", amount)
        val formattedConverted = String.format(Locale.US, "%.2f", converted)
        val formattedRate = String.format(Locale.US, "%.6f", rate)
        Log.i(
            TAG,
            "Converted $ticker: $formattedAmount USD -> $formattedConverted $targetCurrency at rate $formattedRate"
        )
        return converted
    }

    private fun fetchUsdRate(): Double {
        val fetchers = listOf(
            "ER-API" to ::fetchFromOpenErApi,
            "Frankfurter" to ::fetchFromFrankfurter
        )

        for ((name, fetcher) in fetchers) {
            val rate = fetcher()
            if (rate != null) {
                Log.i(
                    TAG,
                    "FX rate retrieval successful via $name: USD->$targetCurrency = $rate"
                )
                return rate
            }
        }

        throw IOException("Unable to fetch FX rate from USD to $targetCurrency")
    }

    private fun fetchFromOpenErApi(): Double? {
        val url = "https://open.er-api.com/v6/latest/USD"
        Log.i(TAG, "Requesting FX rate USD->$targetCurrency from ER-API")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "FX rate retrieval failed: ER-API responded ${resp.code}")
                    return null
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Log.w(TAG, "FX rate retrieval failed: ER-API returned empty body")
                    return null
                }

                val json = JSONObject(body)
                val result = json.optString("result")?.uppercase(Locale.US)
                if (result != null && result != "SUCCESS") {
                    val errorType = json.optString("error-type")
                    Log.w(TAG, "FX rate retrieval failed: ER-API reported $result (${errorType.orEmpty()})")
                    return null
                }

                val rates = json.optJSONObject("rates")
                if (rates == null) {
                    Log.w(TAG, "FX rate retrieval failed: ER-API response missing rates for USD->$targetCurrency")
                    return null
                }

                val rate = rates.optDouble(targetCurrency, Double.NaN)
                if (!rate.isNaN() && rate > 0) {
                    Log.i(TAG, "ER-API rate for USD->$targetCurrency: $rate")
                    return rate
                }

                Log.w(
                    TAG,
                    "FX rate retrieval failed: ER-API response missing rate for USD->$targetCurrency"
                )
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "FX rate retrieval failed: ER-API request error", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "FX rate retrieval failed: unable to parse ER-API response", e)
            null
        }
    }

    private fun fetchFromFrankfurter(): Double? {
        val url = "https://api.frankfurter.app/latest?from=USD&to=$targetCurrency"
        Log.i(TAG, "Requesting FX rate USD->$targetCurrency from Frankfurter")
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
                    Log.w(TAG, "FX rate retrieval failed: Frankfurter response missing rates for USD->$targetCurrency")
                    return null
                }

                val rate = rates.optDouble(targetCurrency, Double.NaN)
                if (!rate.isNaN() && rate > 0) {
                    Log.i(TAG, "Frankfurter rate for USD->$targetCurrency: $rate")
                    return rate
                }

                Log.w(
                    TAG,
                    "FX rate retrieval failed: Frankfurter response missing rate for USD->$targetCurrency"
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

