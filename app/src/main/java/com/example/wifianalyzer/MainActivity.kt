package com.example.wifianalyzer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

// =====================================================================
// VERİ MODELLERİ
// =====================================================================

data class WifiNetworkInfo(
    val ssid: String,
    val bssid: String,
    val rssi: Int,      // Sinyal gücü (dBm)
    val channel: Int,
    val band: String
)

data class ChannelRecommendation(
    val best24GhzChannel: Int,
    val best24GhzScore: Int,
    val best5GhzChannel: Int,
    val best5GhzScore: Int
)

data class SpeedTestResult(
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val error: String? = null
)

// =====================================================================
// ANA AKTİVİTE
// =====================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiAnalyzerScreen()
                }
            }
        }
    }
}

// =====================================================================
// ARAYÜZ (JETPACK COMPOSE)
// =====================================================================

@Composable
fun WifiAnalyzerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wifiManager = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    var networks by remember { mutableStateOf<List<WifiNetworkInfo>>(emptyList()) }
    var statusText by remember { mutableStateOf("Taramaya hazır") }
    var recommendation by remember { mutableStateOf<ChannelRecommendation?>(null) }

    var speed by remember { mutableStateOf(SpeedTestResult()) }
    var isTestingSpeed by remember { mutableStateOf(false) }
    var speedStage by remember { mutableStateOf("Henüz ölçülmedi") }

    // Tarama sonuçlarını okuyup state'e yazar
    @SuppressLint("MissingPermission")
    fun updateNetworksFromScan(fromCache: Boolean) {
        try {
            val results = wifiManager.scanResults
            if (results.isEmpty() && fromCache) {
                statusText = "Tarama limitine takıldı, 1-2 dakika sonra tekrar deneyin."
                return
            }
            networks = results.map { parseScanResult(it) }
                .distinctBy { "${it.bssid}_${it.channel}" }
                .sortedByDescending { it.rssi }
            recommendation = analyzeWifiChannels(networks)
            statusText = if (fromCache) {
                "Limit nedeniyle önbellek gösteriliyor (${networks.size} ağ)"
            } else {
                "${networks.size} ağ bulundu"
            }
        } catch (e: SecurityException) {
            statusText = "Konum/Wi-Fi izni eksik."
        }
    }

    // İzin isteme başlatıcısı
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            statusText = "İzinler verildi, taranıyor..."
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } else {
            statusText = "Wi-Fi ağlarını görmek için konum izni gerekli!"
        }
    }

    // Tarama sonuçlarını dinleyen BroadcastReceiver
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                updateNetworksFromScan(fromCache = false)
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun startWifiScan() {
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            statusText = "Ağlar taranıyor..."
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (!started) updateNetworksFromScan(fromCache = true)
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    // Hız testi: Ping -> İndirme -> Yükleme
    fun startSpeedTest() {
        isTestingSpeed = true
        speed = SpeedTestResult()
        scope.launch {
            speedStage = "Ping ölçülüyor..."
            val ping = runPingTest()
            speed = speed.copy(
                pingMs = ping.getOrNull()?.first,
                jitterMs = ping.getOrNull()?.second
            )

            speedStage = "İndirme ölçülüyor (10 MB)..."
            val down = runDownloadTest()
            speed = speed.copy(downloadMbps = down.getOrNull())

            speedStage = "Yükleme ölçülüyor (5 MB)..."
            val up = runUploadTest()
            speed = speed.copy(uploadMbps = up.getOrNull())

            val firstError = listOf(ping.exceptionOrNull(), down.exceptionOrNull(), up.exceptionOrNull())
                .firstNotNullOfOrNull { it }
            speed = speed.copy(error = firstError?.localizedMessage)
            speedStage = if (firstError == null) "Test tamamlandı" else "Test kısmen başarısız"
            isTestingSpeed = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Android Wi-Fi Analizörü",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. Hız testi kartı
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("İnternet Hız Testi", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(Modifier.weight(1f), "Ping", speed.pingMs?.let { fmt(it, 0) }, "ms")
                        MetricTile(Modifier.weight(1f), "Jitter", speed.jitterMs?.let { fmt(it, 1) }, "ms")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(Modifier.weight(1f), "İndirme", speed.downloadMbps?.let { fmt(it, 1) }, "Mbps")
                        MetricTile(Modifier.weight(1f), "Yükleme", speed.uploadMbps?.let { fmt(it, 1) }, "Mbps")
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(speedStage, style = MaterialTheme.typography.bodySmall)
                    speed.error?.let {
                        Text(
                            "Hata: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(enabled = !isTestingSpeed, onClick = { startSpeedTest() }) {
                        Text(if (isTestingSpeed) "Test Ediliyor..." else "Hız Testi Başlat")
                    }
                }
            }
        }

        // 2. En boş kanal önerisi
        recommendation?.let { rec ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Önerilen En Boş Kanallar",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("• 2.4 GHz Bandı: Kanal ${rec.best24GhzChannel} (Parazit Yükü: ${rec.best24GhzScore})")
                        Text("• 5 GHz Bandı: Kanal ${rec.best5GhzChannel} (Parazit Yükü: ${rec.best5GhzScore})")
                    }
                }
            }
        }

        // 3. Tarama butonu + durum
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { startWifiScan() }) { Text("Ağları Tara") }
            }
        }

        // 4. Ağ listesi (renkli sinyal çubuklu)
        items(networks, key = { "${it.bssid}_${it.channel}" }) { net ->
            NetworkCard(net)
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun MetricTile(modifier: Modifier, label: String, value: String?, unit: String) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value ?: "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NetworkCard(net: WifiNetworkInfo) {
    val color = signalColor(net.rssi)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(net.ssid, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${net.band} • Kanal ${net.channel} • ${net.bssid}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${net.rssi} dBm", fontWeight = FontWeight.Bold, color = color)
                    Text(
                        signalLabel(net.rssi),
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            SignalBar(rssi = net.rssi)
        }
    }
}

// -100 dBm (boş) ... -40 dBm (dolu) aralığında renkli, animasyonlu çubuk
@Composable
fun SignalBar(rssi: Int) {
    val target = ((rssi + 100) / 60f).coerceIn(0.05f, 1f)
    val fraction by animateFloatAsState(targetValue = target, label = "signalFraction")
    val color = signalColor(rssi)
    val shape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.65f), color)))
        )
    }
}

fun signalColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF2E7D32) // Güçlü  (yeşil)
    rssi >= -75 -> Color(0xFFF9A825) // Orta    (sarı)
    else -> Color(0xFFC62828)        // Zayıf   (kırmızı)
}

fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Güçlü"
    rssi >= -75 -> "Orta"
    else -> "Zayıf"
}

fun fmt(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

// =====================================================================
// YARDIMCI FONKSİYONLAR VE ALGORİTMALAR
// =====================================================================

// Frekansı (MHz) kanal numarasına ve banda dönüştürür
fun parseScanResult(result: ScanResult): WifiNetworkInfo {
    val freq = result.frequency
    val (channel, band) = when {
        freq in 2412..2484 -> {
            val ch = if (freq == 2484) 14 else (freq - 2412) / 5 + 1
            Pair(ch, "2.4 GHz")
        }
        freq in 5170..5895 -> Pair((freq - 5000) / 5, "5 GHz")
        freq in 5955..7115 -> Pair((freq - 5950) / 5, "6 GHz")
        else -> Pair(-1, "Bilinmiyor")
    }

    @Suppress("DEPRECATION")
    val rawSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        result.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
    } else {
        result.SSID ?: ""
    }
    val ssidName = if (rawSsid.isEmpty() || rawSsid == "<unknown ssid>") "Gizli Ağ" else rawSsid

    return WifiNetworkInfo(
        ssid = ssidName,
        bssid = result.BSSID ?: "",
        rssi = result.level,
        channel = channel,
        band = band
    )
}

// 2.4 GHz ve 5 GHz kanallarının parazit yoğunluğunu hesaplayıp en boş kanalı önerir
fun analyzeWifiChannels(networks: List<WifiNetworkInfo>): ChannelRecommendation {
    fun interferenceWeight(rssi: Int): Int = (rssi + 100).coerceIn(1, 100)

    // 2.4 GHz: birbiriyle çakışmayan 1, 6, 11 kanalları
    val candidate24 = listOf(1, 6, 11)
    val nets24 = networks.filter { it.band == "2.4 GHz" && it.channel in 1..14 }
    val scores24 = candidate24.associateWith { targetCh ->
        var total = 0
        for (net in nets24) {
            val distance = abs(net.channel - targetCh)
            if (distance <= 4) {
                val overlap = (5 - distance) / 5.0
                total += (interferenceWeight(net.rssi) * overlap).toInt()
            }
        }
        total
    }
    val best24 = scores24.minByOrNull { it.value }?.toPair() ?: (1 to 0)

    // 5 GHz: DFS dışı güvenli 36, 40, 44, 48 kanalları
    val candidate5 = listOf(36, 40, 44, 48)
    val nets5 = networks.filter { it.band == "5 GHz" }
    val scores5 = candidate5.associateWith { targetCh ->
        nets5.filter { it.channel == targetCh }.sumOf { interferenceWeight(it.rssi) }
    }
    val best5 = scores5.minByOrNull { it.value }?.toPair() ?: (36 to 0)

    return ChannelRecommendation(
        best24GhzChannel = best24.first,
        best24GhzScore = best24.second,
        best5GhzChannel = best5.first,
        best5GhzScore = best5.second
    )
}

// =====================================================================
// HIZ TESTİ (Cloudflare uç noktaları)
// =====================================================================

private const val PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
private const val UP_URL = "https://speed.cloudflare.com/__up"
private const val UPLOAD_BYTES = 5_000_000

private fun openConnection(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        useCaches = false
    }

// Ping: 1 ısınma + 6 ölçüm. Ortalama gecikme ve jitter (ardışık farkların ortalaması) döner.
suspend fun runPingTest(): Result<Pair<Double, Double>> = withContext(Dispatchers.IO) {
    runCatching {
        fun once(): Double {
            val conn = openConnection(PING_URL)
            val start = System.nanoTime()
            conn.responseCode
            conn.inputStream.use { it.readBytes() }
            return (System.nanoTime() - start) / 1_000_000.0
        }

        once() // TLS el sıkışmasını ısıtma turunda harca
        val samples = List(6) { once() }
        val avg = samples.average()
        val jitter = samples.zipWithNext { a, b -> abs(a - b) }.average()
        avg to jitter
    }
}

// İndirme: 10 MB indirip Mbps hesaplar
suspend fun runDownloadTest(): Result<Double> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = openConnection(DOWN_URL)
        val start = System.nanoTime()
        var total = 0L
        conn.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
            }
        }
        conn.disconnect()
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        (total * 8.0) / 1_000_000.0 / seconds
    }
}

// Yükleme: 5 MB rastgele veriyi POST edip Mbps hesaplar
suspend fun runUploadTest(): Result<Double> = withContext(Dispatchers.IO) {
    runCatching {
        val chunk = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
        val conn = openConnection(UP_URL).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(UPLOAD_BYTES)
        }
        conn.connect() // bağlantı kurulumu süreye dahil olmasın
        val start = System.nanoTime()
        conn.outputStream.use { out ->
            var sent = 0
            while (sent < UPLOAD_BYTES) {
                val n = minOf(chunk.size, UPLOAD_BYTES - sent)
                out.write(chunk, 0, n)
                sent += n
            }
            out.flush()
        }
        val code = conn.responseCode
        conn.inputStream.use { it.readBytes() }
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        conn.disconnect()
        if (code !in 200..299) error("Sunucu yanıtı: HTTP $code")
        (UPLOAD_BYTES * 8.0) / 1_000_000.0 / seconds
    }
}
