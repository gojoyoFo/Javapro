package com.javapro.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*



import com.javapro.utils.ShizukuManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.FpsService
import com.javapro.R
import com.javapro.ads.AdManager
import com.javapro.utils.PreferenceManager
import com.javapro.utils.PremiumManager
import com.javapro.utils.TweakExecutor
import com.javapro.utils.TweakManager
import com.javapro.utils.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CpuClusterInfo(
    val name           : String,
    val cores          : List<Int>,
    val currentFreqMhz : Int,
    val maxFreqMhz     : Int,
    val color          : Color
)

data class CpuStatSnapshot(val idle: Long, val total: Long)

private object CpuClusterCache {
    var clusters: List<CpuClusterInfo> = emptyList()
    var lastUpdated: Long = 0L
}

suspend fun readCpuStatSnapshot(): CpuStatSnapshot {
    return try {
        val directLine = try {
            withContext(Dispatchers.IO) { File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } }
        } catch (e: Exception) { null }
        val rawOutput = if (directLine == null) TweakExecutor.executeWithOutput("cat /proc/stat") else null
        val line = directLine ?: rawOutput?.lines()?.firstOrNull { it.startsWith("cpu ") } ?: return CpuStatSnapshot(0L, 0L)
        val parts = line.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.drop(1).map { it.toLongOrNull() ?: 0L }
        CpuStatSnapshot(parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }, parts.sum())
    } catch (e: Exception) { CpuStatSnapshot(0L, 0L) }
}

fun calcCpuUsage(s1: CpuStatSnapshot, s2: CpuStatSnapshot): Float {
    val deltaTotal = s2.total - s1.total
    val deltaIdle  = s2.idle  - s1.idle
    return if (deltaTotal <= 0) 0f else ((deltaTotal - deltaIdle).toFloat() / deltaTotal * 100f).coerceIn(0f, 100f)
}

private fun readFreqDirect(path: String): Long {
    return try { File(path).readText().trim().toLongOrNull() ?: 0L } catch (_: Exception) { 0L }
}

/**
 * readFreq: coba direct File I/O dulu.
 * Kalau 0 (permission denied / core offline), fallback via shell — Root atau Shizuku.
 * scaling_cur_freq sering permission 400 (root-only) di banyak SoC, jadi fallback wajib ada.
 */
private fun readFreq(path: String): Long {
    val direct = readFreqDirect(path)
    if (direct > 0L) return direct
    return try {
        TweakExecutor.executeWithOutputSync("cat $path")?.trim()?.toLongOrNull() ?: 0L
    } catch (_: Exception) { 0L }
}

// Baca cluster dari cpufreq/policy* — cara BENAR karena policy folder
// mencerminkan cluster fisik yang sesungguhnya di SoC.
// Grouping berdasarkan cpuinfo_max_freq TIDAK akurat karena beberapa SoC
// (mis. Helio G85, Snapdragon 680) bisa punya max_freq sama di cluster berbeda,
// sehingga menghasilkan cluster palsu.
private fun readPolicyClusters(): List<List<Int>> {
    val policyDir = File("/sys/devices/system/cpu/cpufreq")
    if (!policyDir.exists()) return emptyList()
    return try {
        policyDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            ?.mapNotNull { dir ->
                val relatedFile = File(dir, "related_cpus")
                    .takeIf { it.exists() && it.canRead() }
                    ?: File(dir, "affected_cpus").takeIf { it.exists() && it.canRead() }
                    ?: return@mapNotNull null
                val cores = relatedFile.readText().trim()
                    .split("\\s+".toRegex())
                    .mapNotNull { it.toIntOrNull() }
                    .sorted()
                if (cores.isEmpty()) null else cores
            }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

suspend fun readCpuClustersSuspend(): List<CpuClusterInfo> = withContext(Dispatchers.IO) {
    try {
        val cpuCount = Runtime.getRuntime().availableProcessors()

        // ── Langkah 1: Ambil grup cluster dari policy (akurat) ──────────────
        val policyGroups = readPolicyClusters()

        // ── Langkah 2: Fallback ke grouping max_freq kalau policy tidak ada ──
        val groups: List<List<Int>> = if (policyGroups.isNotEmpty()) {
            policyGroups
        } else {
            val maxFreqs = (0 until cpuCount).map { core ->
                readFreq("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            }
            // PENTING: filter 0L (core offline / tidak terbaca) SEBELUM distinct()
            // Tanpa ini, 0L ikut masuk sebagai "grup" palsu → muncul Big Core fiktif
            val uniqueMax = maxFreqs.filter { it > 0L }.distinct().sorted()
            if (uniqueMax.isEmpty()) return@withContext emptyList()
            uniqueMax.map { maxFreq ->
                maxFreqs.mapIndexedNotNull { i, f -> if (f == maxFreq) i else null }
            }.filter { it.isNotEmpty() }  // buang grup kosong
        }

        if (groups.isEmpty()) return@withContext emptyList()

        // ── Langkah 3: Baca frekuensi tiap core ─────────────────────────────
        // Baca dari policy folder, bukan per-core — lebih reliable saat core offline
        val policyFreqs = mutableMapOf<Int, Pair<Long, Long>>() // coreFirst -> (cur, max)
        groups.forEach { cores ->
            val first = cores.first()
            val policyBase = "/sys/devices/system/cpu/cpufreq/policy$first"
            val curFreq = readFreq("$policyBase/scaling_cur_freq")
                .takeIf { it > 0L }
                ?: readFreq("$policyBase/cpuinfo_cur_freq")
                    .takeIf { it > 0L }
                ?: cores.mapNotNull { c ->
                    readFreq("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq")
                        .takeIf { it > 0L }
                }.maxOrNull() ?: 0L
            val maxFreq = readFreq("$policyBase/cpuinfo_max_freq")
                .takeIf { it > 0L }
                ?: cores.mapNotNull { c ->
                    readFreq("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq")
                        .takeIf { it > 0L }
                }.maxOrNull() ?: 0L
            policyFreqs[first] = Pair(curFreq, maxFreq)
        }

        val coreFreqs = (0 until cpuCount).map { core ->
            val cur = readFreq("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            val max = readFreq("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            Pair(cur, max)
        }

        // ── Langkah 4: Nama cluster sesuai jumlah cluster yang benar-benar ada
        // Tidak hardcode "Big Core" kalau HP hanya punya 2 cluster
        val clusterColors = listOf(
            Color(0xFF64B5F6), // Little — biru
            Color(0xFFFFD600), // Mid    — kuning
            Color(0xFFEF5350), // Big    — merah
            Color(0xFFCE93D8)  // Prime  — ungu
        )
        val total = groups.size
        val clusterNames = when (total) {
            1    -> listOf("Core")
            2    -> listOf("Little Core", "Big Core")
            3    -> listOf("Little Core", "Mid Core", "Big Core")
            else -> listOf("Little Core", "Mid Core", "Big Core", "Prime Core")
        }

        groups.mapIndexed { index, cores ->
            val policyData  = policyFreqs[cores.first()]
            val policyCur   = policyData?.first ?: 0L
            val policyMax   = policyData?.second ?: 0L

            // Fallback ke per-core kalau policy tidak ada
            val curValues = if (policyCur > 0L) listOf(policyCur)
                else cores.mapNotNull { coreFreqs.getOrNull(it)?.first?.takeIf { v -> v > 0L } }
            val maxValues = if (policyMax > 0L) listOf(policyMax)
                else cores.mapNotNull { coreFreqs.getOrNull(it)?.second?.takeIf { v -> v > 0L } }

            val avgCur  = if (curValues.isNotEmpty()) curValues.average().toLong() else 0L
            val maxFreq = maxValues.maxOrNull() ?: 0L

            CpuClusterInfo(
                name           = clusterNames.getOrElse(index) { "Cluster ${index + 1}" },
                cores          = cores,
                currentFreqMhz = (avgCur / 1000).toInt(),
                maxFreqMhz     = (maxFreq / 1000).toInt(),
                color          = clusterColors.getOrElse(index) { Color(0xFFCE93D8) }
            )
        }
    } catch (e: Exception) { emptyList() }
}


private const val AD_MIN_WATCH_SECONDS = 13

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    prefManager   : PreferenceManager,
    lang          : String,
    navController : NavController,
    onShowAd      : (slot: String, onGranted: () -> Unit) -> Unit = { _, granted -> granted() }
) {
    val context         = LocalContext.current
    val isRooted        = remember { TweakExecutor.checkRoot() }
    val info            = remember { TweakExecutor.getDeviceInfo(context) }
    val isShizukuActive = remember { com.javapro.utils.ShizukuManager.isAvailable() }
    val isPremium       = remember { PremiumManager.isPremium(context) }
    val premiumType     = remember { PremiumManager.getPremiumType(context) }
    val expiryMs        = remember { PremiumManager.getExpiryMs(context) }

    val isPerfModeActive by TweakManager.isPerformanceActive.collectAsState()
    val fpsEnabled  by prefManager.fpsEnabledFlow.collectAsState(initial = false)
    val isDark      by prefManager.darkModeFlow.collectAsState()

    var showMenu     by remember { mutableStateOf(false) }
    var cpuUsage     by remember { mutableStateOf(0f) }
    var cpuHistory   by remember { mutableStateOf(listOf<Float>()) }
    var cpuClusters  by remember { mutableStateOf(CpuClusterCache.clusters) }
    var touchedIndex by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val toastSkipWarning = stringResource(R.string.home_toast_watch_ad)

    val AD_LOAD_TIMEOUT_HOME = 8
    val AD_MIN_WATCH_HOME    = 5

    val guardedShowAd: (String, () -> Unit) -> Unit = { slot, onGranted ->
        var adCompleted = false
        var adStarted   = false

        onShowAd(slot) {
            adCompleted = true
            adStarted   = true
        }

        scope.launch {
            var loadWait = 0
            while (!adStarted && loadWait < AD_LOAD_TIMEOUT_HOME) {
                delay(1000)
                loadWait++
            }

            if (!adStarted) {
                onGranted()
                onShowAd("${slot}_preload") {}
                return@launch
            }

            var watchWait = 0
            while (!adCompleted && watchWait < AD_MIN_WATCH_HOME) {
                delay(1000)
                watchWait++
            }

            if (adCompleted) {
                onGranted()
            } else {
                Toast.makeText(context, toastSkipWarning, Toast.LENGTH_SHORT).show()
            }

            onShowAd("${slot}_preload") {}
        }
    }

    LaunchedEffect(Unit) {
        // Loop CPU usage: cepat (1 detik) — tidak ada shell, hanya baca /proc/stat
        var prev: CpuStatSnapshot? = null
        while (true) {
            val cur = readCpuStatSnapshot()
            if (prev != null) cpuUsage = calcCpuUsage(prev!!, cur)
            prev = cur
            cpuHistory = (cpuHistory + cpuUsage).takeLast(60)
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        // Pastikan Shizuku service sudah bind sebelum cluster loop mulai
        if (ShizukuManager.isAvailable()) ShizukuManager.ensureBound()
        // Tunggu sebentar agar bind sempat selesai
        delay(1500)
        // Loop cluster: terpisah, interval 2 detik — hanya baca File sysfs, tidak ada shell
        // Dipisahkan agar delay baca freq tidak menghambat update % CPU
        while (true) {
            val result = readCpuClustersSuspend()
            if (result.isNotEmpty()) {
                CpuClusterCache.clusters = result
                CpuClusterCache.lastUpdated = System.currentTimeMillis()
            }
            cpuClusters = CpuClusterCache.clusters
            delay(2000)
        }
    }

    val pulseAnim  = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue  = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "pulseAlpha"
    )

    val cpuColor     = when { cpuUsage >= 80f -> MaterialTheme.colorScheme.error; cpuUsage >= 50f -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
    val displayValue = if (touchedIndex != null && touchedIndex!! < cpuHistory.size) cpuHistory[touchedIndex!!] else cpuUsage

    val remainingDays    = if (isPremium && premiumType != "permanent") ((expiryMs - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).coerceAtLeast(0L) else 0L
    val premiumDaysColor = if (isPremium && premiumType != "permanent" && remainingDays <= 2L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary


    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("JavaPro", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (isPremium) {
                            val badgeColor = when (premiumType) {
                                "weekly"    -> Color(0xFF00ACC1)
                                "monthly"   -> Color(0xFF1E88E5)
                                "yearly"    -> Color(0xFFFF8F00)
                                "permanent" -> Color(0xFFAB47BC)
                                else        -> Color(0xFF1E88E5)
                            }
                            val badgeLabel = when (premiumType) {
                                "weekly"    -> "Plus"
                                "monthly"   -> "Plus+"
                                "yearly"    -> "Plus\u2605"
                                "permanent" -> "King"
                                else        -> "Plus"
                            }
                            Row(
                                modifier = Modifier
                                    .clickable { navController.navigate("premium") }
                                    .clip(RoundedCornerShape(50))
                                    .background(badgeColor.copy(alpha = 0.15f))
                                    .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter            = painterResource(id = R.drawable.ic_crown),
                                    contentDescription = null,
                                    tint               = badgeColor,
                                    modifier           = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text          = badgeLabel,
                                    color         = badgeColor,
                                    fontWeight    = FontWeight.Bold,
                                    fontSize      = 11.sp,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!isPremium) {
                        IconButton(onClick = { navController.navigate("premium") }, modifier = Modifier.padding(end = 2.dp)) {
                            val canvasPrimary = MaterialTheme.colorScheme.primary
                            val canvasError   = MaterialTheme.colorScheme.error
                            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.size(34.dp)) {
                                    val r = size.minDimension / 2f
                                    drawCircle(color = canvasPrimary, radius = r, style = Stroke(width = 2f))
                                    drawLine(color = canvasError, start = Offset(r * 0.35f, r * 0.35f), end = Offset(r * 1.65f, r * 1.65f), strokeWidth = 3.5f, cap = StrokeCap.Round)
                                    drawLine(color = canvasError, start = Offset(r * 1.65f, r * 0.35f), end = Offset(r * 0.35f, r * 1.65f), strokeWidth = 3.5f, cap = StrokeCap.Round)
                                }
                                Text(stringResource(R.string.nav_ads), fontSize = 7.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.width(220.dp)) {
                        DropdownMenuItem(
                            text        = { Text(stringResource(R.string.nav_settings)) },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick     = { showMenu = false; navController.navigate("settings") }
                        )
                        DropdownMenuItem(
                            text        = { Text(stringResource(R.string.nav_app_profiles)) },
                            leadingIcon = { Icon(Icons.Default.GridView, null) },
                            onClick     = { showMenu = false; navController.navigate("app_profiles") }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text        = {
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.nav_mode))
                                    Switch(
                                        checked         = isDark,
                                        onCheckedChange = null,
                                        modifier        = Modifier.height(24.dp),
                                        thumbContent    = {
                                            Icon(
                                                imageVector        = if (isDark) Icons.Default.DarkMode else Icons.Default.Circle,
                                                contentDescription = null,
                                                modifier           = Modifier.size(14.dp),
                                                tint               = MaterialTheme.colorScheme.onPrimary
                                            )
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor   = MaterialTheme.colorScheme.primary,
                                            checkedTrackColor   = MaterialTheme.colorScheme.primary.copy(0.45f),
                                            uncheckedThumbColor = MaterialTheme.colorScheme.secondary,
                                            uncheckedTrackColor = MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    )
                                }
                            },
                            leadingIcon = { Icon(if (isDark) Icons.Default.DarkMode else Icons.Default.Circle, null) },
                            onClick     = { prefManager.setDarkMode(!isDark) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text        = { Text(stringResource(R.string.nav_credits)) },
                            leadingIcon = { Icon(Icons.Default.Language, null) },
                            onClick     = { showMenu = false; navController.navigate("credits") }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, titleContentColor = MaterialTheme.colorScheme.onBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Box(modifier = Modifier.fillMaxWidth()) {
                Card(
                    shape     = RoundedCornerShape(28.dp),
                    modifier  = Modifier.fillMaxWidth().height(172.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Box {
                        val bannerPrefs    = remember { context.getSharedPreferences("javapro_settings", android.content.Context.MODE_PRIVATE) }
                        val customBannerUri = remember { bannerPrefs.getString("custom_banner_uri", null) }
                        val customBitmap   = remember(customBannerUri) {
                            if (customBannerUri != null) {
                                try {
                                    val uri  = Uri.parse(customBannerUri)
                                    val ins  = context.contentResolver.openInputStream(uri)
                                    val bmp  = BitmapFactory.decodeStream(ins)
                                    ins?.close()
                                    bmp?.asImageBitmap()
                                } catch (_: Exception) { null }
                            } else null
                        }
                        if (customBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap             = customBitmap,
                                contentDescription = null,
                                modifier           = Modifier.fillMaxSize(),
                                contentScale       = ContentScale.Crop
                            )
                        } else {
                            Image(painter = painterResource(R.drawable.banner), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.5f)), startY = 80f))
                        )
                    }
                }
                Surface(
                    modifier = Modifier.padding(10.dp).align(Alignment.TopStart),
                    shape    = RoundedCornerShape(50),
                    color    = Color(0xFF0A0C10).copy(0.85f)
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(7.dp).background(Color(0xFF39FF14).copy(pulseAlpha), CircleShape))
                        Text(stringResource(R.string.home_status_alive), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF39FF14))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(36.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(36.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {

                    Row(
                        modifier              = Modifier.padding(bottom = 14.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.home_system_health),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {

                        Column(
                            modifier            = Modifier.width(86.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier         = Modifier
                                    .size(56.dp)
                                    .background(cpuColor.copy(0.14f), CircleShape)
                                    .border(1.5.dp, cpuColor.copy(0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Memory, null, tint = cpuColor, modifier = Modifier.size(28.dp)) }

                            Text("${displayValue.toInt()}%", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = cpuColor, lineHeight = 32.sp)

                            Surface(shape = RoundedCornerShape(50), color = cpuColor.copy(0.16f)) {
                                Text(
                                    when {
                                        displayValue >= 80f -> stringResource(R.string.home_cpu_high)
                                        displayValue >= 50f -> stringResource(R.string.home_cpu_med)
                                        else                -> stringResource(R.string.home_cpu_low)
                                    },
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cpuColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text("CPU", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Tampilkan cluster hanya kalau data sudah tersedia.
                            // Tidak pakai fallback hardcode karena bisa muncul cluster
                            // yang tidak ada di HP (mis. Big Core padahal HP hanya 2 cluster)
                            if (cpuClusters.isEmpty()) {
                                Text(
                                    "Detecting…",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                cpuClusters.take(4).forEach { cluster ->
                                key(cluster.cores.first()) {   // key by core index, bukan nama — nama bisa duplikat saat list berubah
                                    val progress = if (cluster.maxFreqMhz > 0) (cluster.currentFreqMhz.toFloat() / cluster.maxFreqMhz).coerceIn(0f, 1f) else 0f
                                    val animProg by animateFloatAsState(targetValue = progress, animationSpec = tween(600), label = "cp_${cluster.cores.first()}")
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(cluster.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cluster.color)
                                            Text("(${cluster.currentFreqMhz} MHz)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(cluster.color.copy(0.14f))) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(animProg)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(50))
                                                    .background(Brush.horizontalGradient(listOf(cluster.color.copy(0.65f), cluster.color)))
                                            )
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        DeviceInfoChip(Icons.Default.PhoneAndroid, info["model"]?.take(10) ?: "—", isDark)
                        DeviceInfoChip(Icons.Default.Android,      info["android"] ?: "—",          isDark)
                        DeviceInfoChip(Icons.Default.Memory,       info["RAM"] ?: "—",              isDark)
                        DeviceInfoChip(Icons.Default.BatteryChargingFull, info["Battery"] ?: "—",   isDark)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(0.22f)), RoundedCornerShape(32.dp))
                        .clickable { navController.navigate("app_profiles") }
                ) {
                    Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier         = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(0.16f), RoundedCornerShape(14.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(0.3f)), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                        Text(stringResource(R.string.home_app_profiles_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            stringResource(R.string.home_set_perf_per_app),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary.copy(0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                }

                FpsMonitorCard(
                    modifier        = Modifier.weight(1f),
                    fpsEnabled      = fpsEnabled,
                    isRooted        = isRooted,
                    isShizukuActive = isShizukuActive,
                    prefManager     = prefManager
                )
            }

            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(32.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                StatusPill(
                    modifier = Modifier.weight(1f),
                    icon     = Icons.Default.VerifiedUser,
                    label    = stringResource(R.string.home_root_label),
                    status   = if (isRooted) stringResource(R.string.home_root_active) else stringResource(R.string.home_root_inactive),
                    isActive = isRooted,
                    isDark   = isDark
                )
                StatusPill(
                    modifier = Modifier.weight(1f),
                    icon     = Icons.Default.Speed,
                    label    = "Shizuku",
                    status   = if (isShizukuActive) stringResource(R.string.home_shizuku_running) else stringResource(R.string.home_shizuku_stopped),
                    isActive = isShizukuActive,
                    isDark   = isDark
                )
            }

            Spacer(Modifier.height(6.dp))

            ExclusiveFeaturesCard(lang = lang, isDark = isDark, navController = navController)

            Spacer(Modifier.height(6.dp))

            SupportGridSection(lang = lang, isDark = isDark, navController = navController)

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ExclusiveFeaturesCard(lang: String, isDark: Boolean, navController: NavController) {
    val context   = LocalContext.current
    val isPremium = remember { PremiumManager.isPremium(context) }
    val AD_PREFS       = "exclusive_ad_prefs"
    val KEY_AD_COUNT   = "ad_watch_count"
    val KEY_AD_UNLOCK  = "ad_unlock_until"
    val AD_REQUIRED    = 6
    val adPrefs        = remember { context.getSharedPreferences(AD_PREFS, android.content.Context.MODE_PRIVATE) }
    val adWatchCount   = remember { mutableStateOf(adPrefs.getInt(KEY_AD_COUNT, 0)) }
    val adUnlockUntil  = remember { mutableStateOf(adPrefs.getLong(KEY_AD_UNLOCK, 0L)) }
    val isAdUnlocked   = adUnlockUntil.value > System.currentTimeMillis()

    var showFreeDialog by remember { mutableStateOf(false) }

    if (showFreeDialog) {
        ExclusiveGateDialog(
            lang       = lang,
            onWatchAds = { showFreeDialog = false; navController.navigate("exclusive_features") },
            onUpgrade  = { showFreeDialog = false; navController.navigate("premium") },
            onDismiss  = { showFreeDialog = false }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier              = Modifier.padding(start = 4.dp, bottom = 2.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
            Text(
                text          = stringResource(R.string.home_exclusive_section),
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = MaterialTheme.colorScheme.secondary,
                letterSpacing = 1.sp
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.secondary.copy(0.3f)), RoundedCornerShape(20.dp))
                .clickable {
                    when {
                        isPremium    -> navController.navigate("exclusive_features")
                        isAdUnlocked -> navController.navigate("exclusive_features")
                        else         -> showFreeDialog = true
                    }
                }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier         = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(0.15f))
                        .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.secondary.copy(0.3f)), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPremium || isAdUnlocked) Icons.Default.AutoAwesome else Icons.Default.Lock,
                        contentDescription = null,
                        tint        = MaterialTheme.colorScheme.secondary,
                        modifier    = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_exclusive_title),
                        fontSize   = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        fontStyle  = androidx.compose.ui.text.font.FontStyle.Italic,
                        color      = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        stringResource(R.string.home_exclusive_subtitle),
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isPremium && !isAdUnlocked) {
                        Spacer(Modifier.height(6.dp))
                        val prog = adWatchCount.value.toFloat() / AD_REQUIRED.toFloat()
                        val animProg by animateFloatAsState(targetValue = prog.coerceIn(0f, 1f), animationSpec = tween(600), label = "cardProgress")
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.home_exclusive_ad_progress, adWatchCount.value, AD_REQUIRED),
                                fontSize = 9.sp, color = MaterialTheme.colorScheme.secondary.copy(0.8f)
                            )
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondary.copy(0.15f))) {
                                Box(
                                    Modifier.fillMaxWidth(animProg).fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.secondary.copy(0.7f), MaterialTheme.colorScheme.primary)))
                                )
                            }
                        }
                    }
                }
                if (!isPremium && !isAdUnlocked) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondary.copy(0.15f)) {
                        Text(
                            stringResource(R.string.home_exclusive_free_badge),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color      = MaterialTheme.colorScheme.secondary,
                            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.secondary.copy(0.5f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SupportGridSection(lang: String, isDark: Boolean, navController: NavController) {
    val context = LocalContext.current

    data class SupportItem(
        val icon    : ImageVector,
        val label   : String,
        val onClick : () -> Unit
    )

    val items = listOf(
        SupportItem(
            icon    = Icons.Default.Memory,
            label   = stringResource(R.string.home_support_donate),
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sociabuzz.com/javakids/tribe"))) }
        ),
        SupportItem(
            icon    = Icons.Default.Send,
            label   = "Telegram",
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Java_diks"))) }
        ),
        SupportItem(
            icon    = Icons.Default.BugReport,
            label   = stringResource(R.string.home_support_report_bug),
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Java_nih_deks"))) }
        ),
        SupportItem(
            icon    = Icons.Default.Storage,
            label   = stringResource(R.string.home_support_credits),
            onClick = { navController.navigate("credits") }
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier              = Modifier.padding(start = 4.dp, bottom = 2.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text(
                text          = stringResource(R.string.home_support_section),
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.take(2).forEach { item ->
                SupportTile(icon = item.icon, label = item.label, isDark = isDark, onClick = item.onClick, modifier = Modifier.weight(1f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.drop(2).forEach { item ->
                SupportTile(icon = item.icon, label = item.label, isDark = isDark, onClick = item.onClick, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SupportTile(
    icon     : ImageVector,
    label    : String,
    isDark   : Boolean,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(0.12f), RoundedCornerShape(12.dp))
                    .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(0.2f)), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(
                text       = label,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface,
                maxLines   = 1
            )
        }
    }
}

@Composable
private fun DeviceInfoChip(icon: ImageVector, value: String, isDark: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text = value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusPill(modifier: Modifier = Modifier, icon: ImageVector, label: String, status: String, isActive: Boolean, isDark: Boolean) {
    val textColor = if (isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    val bgColor   = if (isActive) MaterialTheme.colorScheme.tertiary.copy(0.12f) else MaterialTheme.colorScheme.surfaceVariant

    Surface(shape = RoundedCornerShape(50), color = bgColor, modifier = modifier) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
            Column {
                Text(text = label,  fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text(text = status, fontSize = 12.sp, color = textColor,     fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ClusterItem(cluster: CpuClusterInfo, isDark: Boolean) {
    val progress         = if (cluster.maxFreqMhz > 0) cluster.currentFreqMhz.toFloat() / cluster.maxFreqMhz else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), animationSpec = tween(600), label = "clusterProgress")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(36.dp).background(cluster.color.copy(0.14f), CircleShape).border(0.8.dp, cluster.color.copy(0.35f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Memory, null, tint = cluster.color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${cluster.name} · Core ${cluster.cores.first()}–${cluster.cores.last()}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cluster.color)
                Text("${cluster.currentFreqMhz} / ${cluster.maxFreqMhz} MHz", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(5.dp))
            Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(cluster.color.copy(0.13f))) {
                Box(modifier = Modifier.fillMaxWidth(animatedProgress).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(cluster.color.copy(0.65f), cluster.color))))
            }
        }
    }
}

@Composable
fun InfoItem(icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(7.dp))
        Column {
            Text(text = title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MonitorRow(label: String, value: String, icon: ImageVector, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun MonitorChip(modifier: Modifier, label: String, value: String, sub: String, color: Color) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(0.09f))
            .border(BorderStroke(0.8.dp, color.copy(0.25f)), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 9.sp, color = color.copy(0.75f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = color)
            if (sub.isNotBlank()) Text(sub, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FpsMonitorCard(
    modifier        : Modifier,
    fpsEnabled      : Boolean,
    isRooted        : Boolean,
    isShizukuActive : Boolean,
    prefManager     : PreferenceManager
) {
    val dimColor    = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(0.5f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.8.dp, borderColor), RoundedCornerShape(32.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier         = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Speed, null, tint = dimColor, modifier = Modifier.size(26.dp)) }

            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.home_fps_monitor_title),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Soon",
                        modifier  = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize  = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
                    )
                }
            }

            Text(
                stringResource(R.string.home_show_fps),
                fontSize   = 11.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f),
                lineHeight = 15.sp
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_fps_off),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f)
                )
                Switch(
                    checked         = false,
                    onCheckedChange = null,
                    enabled         = false,
                    modifier        = Modifier.height(24.dp),
                    colors          = SwitchDefaults.colors(
                        disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f),
                        disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}



