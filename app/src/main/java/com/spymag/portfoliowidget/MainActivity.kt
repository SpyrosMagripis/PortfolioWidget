package com.spymag.portfoliowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spymag.portfoliowidget.ui.theme.PortfolioWidgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PortfolioWidgetTheme {
                PortfolioScreen()
            }
        }
    }
}

data class Holding(val symbol: String, val value: Double)

private const val PREFS_NAME = "portfolio_widget_prefs"
private const val PREF_TRADING212 = "trading212_value"
private const val PREF_TRADING212_TIME = "trading212_time"
private const val PREF_BITVAVO = "bitvavo_value"
private const val PREF_BITVAVO_TIME = "bitvavo_time"
private const val TAG = "MainActivity"

@Composable
fun PortfolioScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var holdings by remember { mutableStateOf<List<Holding>?>(null) }
    var bitvavoTotal by remember { mutableStateOf(prefs.getString(PREF_BITVAVO, null)) }
    var tradingTotal by remember { mutableStateOf(prefs.getString(PREF_TRADING212, null)) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            val fetched = fetchBitvavoHoldings()
                .filter { it.value > 1.0 }
                .sortedByDescending { it.value }
            holdings = fetched
            val total = fetched.sumOf { it.value }
            bitvavoTotal = "€%.2f".format(total)
            Log.d(TAG, "Fetched Bitvavo total value: $bitvavoTotal")
            prefs.edit()
                .putString(PREF_BITVAVO, bitvavoTotal)
                .putLong(PREF_BITVAVO_TIME, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            if (bitvavoTotal == null) bitvavoTotal = "–"
        }
        try {
            val trading = withContext(Dispatchers.IO) { fetchTrading212TotalValue() }
            tradingTotal = trading
            Log.d(TAG, "Fetched Trading212 total value: $trading")
            prefs.edit()
                .putString(PREF_TRADING212, trading)
                .putLong(PREF_TRADING212_TIME, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            if (tradingTotal == null) tradingTotal = "–"
        }
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PortfolioWidgetProvider::class.java))
        val intent = Intent(context, PortfolioWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Portfolio Widget",
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    ) { inner ->
        val data = holdings
        if (data == null) {
            Box(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PieChart(
                        data,
                        modifier = Modifier
                            .size(200.dp)
                            .padding(top = 16.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Bitvavo total: ${bitvavoTotal ?: "\u2026"}"
                    )
                    Spacer(Modifier.height(8.dp))
                    data.forEachIndexed { index, h ->
                        val color = chartColors[index % chartColors.size]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(color)
                            )
                            Text(
                                text = "${h.symbol}: €${"%.2f".format(h.value)}",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(0.25f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val text = tradingTotal ?: "…"
                    Text("Trading 212: $text")
                }
            }
        }
    }
}

private val chartColors = listOf(
    Color(0xFFEF5350),
    Color(0xFFAB47BC),
    Color(0xFF42A5F5),
    Color(0xFF26A69A),
    Color(0xFFFFA726),
    Color(0xFF8D6E63)
)

@Composable
fun PieChart(data: List<Holding>, modifier: Modifier = Modifier) {
    val total = data.sumOf { it.value }
    Canvas(modifier) {
        var startAngle = -90f
        data.forEachIndexed { index, h ->
            val sweep = (h.value / total * 360f).toFloat()
            drawArc(
                color = chartColors[index % chartColors.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true
            )
            startAngle += sweep
        }
    }
}

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

@Preview(showBackground = true)
@Composable
fun PreviewPortfolio() {
    PortfolioWidgetTheme {
        PieChart(
            data = listOf(Holding("BTC", 50.0), Holding("ETH", 30.0), Holding("EUR", 20.0)),
            modifier = Modifier.size(200.dp)
        )
    }
}
