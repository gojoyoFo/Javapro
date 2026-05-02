package com.javapro.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*




import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
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

private fun readFreqShell(path: String): Long {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("cat", path))
        process.inputStream.bufferedReader().readLine()?.trim()?.toLongOrNull() ?: 0L
    } catch (_: Exception) { 0L }
}

private fun readFreq(path: String): Long {
    val direct = readFreqDirect(path)
    return if (direct > 0L) direct else readFreqShell(path)
}

suspend fun readCpuClustersSuspend(): List<CpuClusterInfo> = withContext(Dispatchers.IO) {
    try {
        val cpuCount  = Runtime.getRuntime().availableProcessors()
        val coreFreqs = (0 until cpuCount).map { core ->
            val curPath = "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"
            val maxPath = "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq"
            Pair(readFreq(curPath), readFreq(maxPath))
        }
        val maxFreqs       = coreFreqs.map { it.second }
        val uniqueMaxFreqs = maxFreqs.distinct().filter { it > 0L }.sorted()

        if (uniqueMaxFreqs.isEmpty()) return@withContext emptyList()

        val clusterColors  = listOf(Color(0xFF64B5F6), Color(0xFFFFD600), Color(0xFFEF5350))
        uniqueMaxFreqs.mapIndexed { index, maxFreq ->
            val cores  = maxFreqs.mapIndexedNotNull { i, f -> if (f == maxFreq) i else null }
            val curValues = cores.map { coreFreqs[it].first }
            val avgCur = if (curValues.isNotEmpty() && curValues.any { it > 0L })
                curValues.filter { it > 0L }.average().toLong()
            else
                (maxFreq * 0.3).toLong()
            val name   = when { index == 0 -> "Little Core"; index == uniqueMaxFreqs.size - 1 -> "Big Core"; else -> "Mid Core" }
            CpuClusterInfo(name, cores, (avgCur / 1000).toInt(), (maxFreq / 1000).toInt(), clusterColors.getOrElse(index) { Color(0xFFCE93D8) })
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
    var cpuClusters  by remember { mutableStateOf(listOf<CpuClusterInfo>()) }
    var touchedIndex by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val toastSkipWarning = stringResource(R.string.home_toast_watch_ad)

    // guardedShowAd: tampilkan iklan, tunggu max timeout.
    // Kalau iklan tidak load → langsung izinkan navigasi (tidak block user).
    // Kalau iklan mulai tayang tapi user skip terlalu cepat → toast peringatan, tidak navigate.
    // Kalau iklan selesai ditonton → navigasi normal.
    // Setelah selesai/gagal → preload iklan berikutnya otomatis.
    val AD_LOAD_TIMEOUT_HOME = 8  // detik tunggu iklan load
    val AD_MIN_WATCH_HOME    = 5  // detik minimal tonton setelah iklan mulai

    val guardedShowAd: (String, () -> Unit) -> Unit = { slot, onGranted ->
        var adCompleted = false
        var adStarted   = false

        onShowAd(slot) {
            adCompleted = true
            adStarted   = true
        }

        scope.launch {
            // Tunggu iklan load
            var loadWait = 0
            while (!adStarted && loadWait < AD_LOAD_TIMEOUT_HOME) {
                delay(1000)
                loadWait++
            }

            if (!adStarted) {
                // Iklan tidak load → langsung izinkan, jangan block user
                onGranted()
                // Preload berikutnya
                onShowAd("${slot}_preload") {}
                return@launch
            }

            // Iklan mulai tayang, tunggu minimal AD_MIN_WATCH_HOME detik
            var watchWait = 0
            while (!adCompleted && watchWait < AD_MIN_WATCH_HOME) {
                delay(1000)
                watchWait++
            }

            if (adCompleted) {
                onGranted()
            } else {
                // User skip sebelum waktunya
                Toast.makeText(context, toastSkipWarning, Toast.LENGTH_SHORT).show()
            }

            // Preload iklan berikutnya segera
            onShowAd("${slot}_preload") {}
        }
    }

    LaunchedEffect(Unit) {
        var prev: CpuStatSnapshot? = null
        while (true) {
            val cur = readCpuStatSnapshot()
            if (prev != null) cpuUsage = calcCpuUsage(prev!!, cur)
            prev = cur
            cpuClusters = readCpuClustersSuspend()
            cpuHistory  = (cpuHistory + cpuUsage).takeLast(60)
            delay(1000)
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
                        Text("Alive", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF39FF14))
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
                                    when { displayValue >= 80f -> stringResource(R.string.home_cpu_high); displayValue >= 50f -> stringResource(R.string.home_cpu_med); else -> stringResource(R.string.home_cpu_low) },
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cpuColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text("CPU", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val coreDisplayList = if (cpuClusters.isNotEmpty()) cpuClusters.take(3) else listOf(
                                CpuClusterInfo("Little Core", listOf(0), 0, 1, Color(0xFF64B5F6)),
                                CpuClusterInfo("Mid Core",    listOf(4), 0, 1, Color(0xFFFFD600)),
                                CpuClusterInfo("Big Core",    listOf(7), 0, 1, Color(0xFFEF5350))
                            )
                            coreDisplayList.forEach { cluster ->
                                key(cluster.name) {
                                    val progress = if (cluster.maxFreqMhz > 0) (cluster.currentFreqMhz.toFloat() / cluster.maxFreqMhz).coerceIn(0f, 1f) else 0f
                                    val animProg by animateFloatAsState(targetValue = progress, animationSpec = tween(600), label = "cp_${cluster.name}")
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

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        DeviceInfoChip(Icons.Default.PhoneAndroid,       info["model"]?.take(10) ?: "—", isDark)
                        DeviceInfoChip(Icons.Default.Android,       info["android"] ?: "—",          isDark)
                        DeviceInfoChip(Icons.Default.Memory,        info["RAM"] ?: "—",              isDark)
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
                        Text("App Profiles", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
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
                    label    = "Root Access",
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
    // Check free-user ad-unlock state
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
                        isPremium    -> navController.navigate("exclusive_features")   // premium: langsung masuk
                        isAdUnlocked -> navController.navigate("exclusive_features")   // free, sudah unlock via iklan
                        else         -> showFreeDialog = true                          // free, belum unlock
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
                    // Show mini progress bar for free users not yet unlocked
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
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.take(2).forEach { item ->
                SupportTile(
                    icon    = item.icon,
                    label   = item.label,
                    isDark  = isDark,
                    onClick = item.onClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.drop(2).forEach { item ->
                SupportTile(
                    icon    = item.icon,
                    label   = item.label,
                    isDark  = isDark,
                    onClick = item.onClick,
                    modifier = Modifier.weight(1f)
                )
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
fun FpsModeItem(selected: Boolean, onClick: () -> Unit, title: String, subtitle: String, badge: String?, isDark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.tertiary, unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title,    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (badge != null) {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiary.copy(0.15f)) {
                Text(text = badge, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
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
    val context = LocalContext.current
    val canUse  = isRooted || isShizukuActive

    var showMethodDialog by remember { mutableStateOf(false) }

    val activeColor = MaterialTheme.colorScheme.tertiary
    val borderColor = if (fpsEnabled && canUse) activeColor.copy(0.35f) else MaterialTheme.colorScheme.outlineVariant
    val iconBg      = if (canUse) activeColor.copy(0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val iconBorder  = if (canUse) activeColor.copy(0.3f)  else MaterialTheme.colorScheme.outlineVariant
    val iconTint    = if (canUse) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor  = if (canUse) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    if (showMethodDialog) {
        FpsMethodDialog(
            isRooted        = isRooted,
            isShizukuActive = isShizukuActive,
            prefManager     = prefManager,
            onDismiss       = { showMethodDialog = false },
            onConfirm       = { method ->
                showMethodDialog = false
                prefManager.setFpsEnabled(true)
                prefManager.setFpsMethod(method)
                val intent = Intent(context, FpsService::class.java).putExtra("fps_method", method)
                context.startService(intent)
                Toast.makeText(context, "FPS Monitor On ($method)", Toast.LENGTH_SHORT).show()
            }
        )
    }

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
                    .background(iconBg, RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, iconBorder), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Speed, null, tint = iconTint, modifier = Modifier.size(26.dp)) }

            Text("FPS Monitor", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = titleColor)

            Text(
                if (!canUse) "Butuh Root atau Shizuku"
                else stringResource(R.string.home_show_fps),
                fontSize  = 11.sp,
                color     = if (canUse) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.45f),
                lineHeight = 15.sp
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (fpsEnabled && canUse) "ON" else "OFF",
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color      = if (fpsEnabled && canUse) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f)
                )
                Switch(
                    checked         = fpsEnabled,
                    onCheckedChange = { isChecked ->
                        if (isChecked && canUse) {
                            showMethodDialog = true
                        } else {
                            prefManager.setFpsEnabled(false)
                            context.stopService(Intent(context, FpsService::class.java))
                            Toast.makeText(context, "FPS Monitor Off", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled  = canUse,
                    modifier = Modifier.height(24.dp),
                    colors   = SwitchDefaults.colors(
                        checkedThumbColor   = MaterialTheme.colorScheme.onTertiary,
                        checkedTrackColor   = activeColor,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun FpsMethodDialog(
    isRooted        : Boolean,
    isShizukuActive : Boolean,
    prefManager     : PreferenceManager,
    onDismiss       : () -> Unit,
    onConfirm       : (String) -> Unit
) {
    val savedMethod  = prefManager.getFpsMethod()
    val defaultMethod = when {
        isRooted        -> "root"
        isShizukuActive -> "shizuku"
        else            -> "non_root"
    }
    var selected by remember { mutableStateOf(savedMethod ?: defaultMethod) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Text("Metode FPS Monitor", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isRooted) {
                    FpsModeItem(
                        selected  = selected == "root",
                        onClick   = { selected = "root" },
                        title     = "Root",
                        subtitle  = "Akurat, pakai API system. Butuh root.",
                        badge     = "AKURAT",
                        isDark    = false
                    )
                }
                if (isShizukuActive) {
                    FpsModeItem(
                        selected  = selected == "shizuku",
                        onClick   = { selected = "shizuku" },
                        title     = "Shizuku (TaskFpsCallback)",
                        subtitle  = "Sama akuratnya, pakai Shizuku ADB.",
                        badge     = "AKURAT",
                        isDark    = false
                    )
                }
                FpsModeItem(
                    selected  = selected == "non_root",
                    onClick   = { selected = "non_root" },
                    title     = "Non-Root",
                    subtitle  = "FPS display",
                    badge     = null,
                    isDark    = false
                )

                if (selected == "root" || selected == "shizuku") {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(0.08f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(13.dp).padding(top = 1.dp))
                            Text(
                                "Metode ini butuh permission READ_FRAME_BUFFER. " +
                                if (selected == "shizuku") "Grant via: adb shell pm grant ${android.os.Build.ID} android.permission.READ_FRAME_BUFFER"
                                else "Sudah di-grant otomatis via root.",
                                fontSize = 10.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text("Aktifkan", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
