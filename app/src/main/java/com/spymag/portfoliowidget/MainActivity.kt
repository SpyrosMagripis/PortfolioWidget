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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.graphics.Paint
import com.spymag.portfoliowidget.ui.theme.PortfolioWidgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var bitvavoHoldings by remember { mutableStateOf<List<Holding>?>(null) }
    var tradingHoldings by remember { mutableStateOf<List<Holding>?>(null) }
    var bitvavoTotal by remember { mutableStateOf(prefs.getString(PREF_BITVAVO, null)) }
    var tradingTotal by remember { mutableStateOf(prefs.getString(PREF_TRADING212, null)) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            val fetched = fetchBitvavoHoldings()
                .filter { it.value > 1.0 }
                .sortedByDescending { it.value }
            bitvavoHoldings = fetched
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
            val trading = withContext(Dispatchers.IO) { fetchTrading212Holdings() }
            tradingHoldings = trading
            val total = trading.sumOf { it.value }
            tradingTotal = "€%.2f".format(total)
            Log.d(TAG, "Fetched Trading212 total value: $tradingTotal")
            prefs.edit()
                .putString(PREF_TRADING212, tradingTotal)
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
                        .padding(start = 16.dp, top = 27.dp, end = 16.dp, bottom = 12.dp),
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
        val data = bitvavoHoldings
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
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Treemap(
                        data = data,
                        modifier = Modifier.matchParentSize()
                    )
                    val text = bitvavoTotal ?: "…"
                    Text(
                        text = "Bitvavo: $text",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val tData = tradingHoldings
                    if (tData == null) {
                        val text = tradingTotal ?: "…"
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Trading 212: $text")
                        }
                    } else {
                        Treemap(
                            data = tData,
                            modifier = Modifier.matchParentSize()
                        )
                        val text = tradingTotal ?: "…"
                        Text(
                            text = "Trading 212: $text",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp)
                        )
                    }
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
fun Treemap(data: List<Holding>, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 28f
        }
    }
    Canvas(modifier) {
        fun drawRects(items: List<Holding>, x: Float, y: Float, w: Float, h: Float, horizontal: Boolean, index: Int) {
            if (items.isEmpty()) return
            if (items.size == 1) {
                val color = chartColors[index % chartColors.size]
                drawRect(color, Offset(x, y), Size(w, h))
                val label = "${items[0].symbol} €${"%.2f".format(items[0].value)}"
                drawContext.canvas.nativeCanvas.drawText(label, x + 8f, y + 32f, paint)
                return
            }
            val total = items.sumOf { it.value }
            val first = items.first()
            val fraction = (first.value / total).toFloat()
            if (horizontal) {
                val w1 = w * fraction
                val color = chartColors[index % chartColors.size]
                drawRect(color, Offset(x, y), Size(w1, h))
                val label = "${first.symbol} €${"%.2f".format(first.value)}"
                drawContext.canvas.nativeCanvas.drawText(label, x + 8f, y + 32f, paint)
                drawRects(items.drop(1), x + w1, y, w - w1, h, !horizontal, index + 1)
            } else {
                val h1 = h * fraction
                val color = chartColors[index % chartColors.size]
                drawRect(color, Offset(x, y), Size(w, h1))
                val label = "${first.symbol} €${"%.2f".format(first.value)}"
                drawContext.canvas.nativeCanvas.drawText(label, x + 8f, y + 32f, paint)
                drawRects(items.drop(1), x, y + h1, w, h - h1, !horizontal, index + 1)
            }
        }
        drawRects(data.sortedByDescending { it.value }, 0f, 0f, size.width, size.height, true, 0)
    }
}

