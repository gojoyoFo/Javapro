package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*





import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunningAppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val memoryKb: Long,
    val isSystem: Boolean
)

private suspend fun detectRunningAppsRoot(context: Context): List<RunningAppInfo> {
    val pm = context.packageManager
    val result = mutableListOf<RunningAppInfo>()
    return withContext(Dispatchers.IO) {
        try {
            val output = TweakExecutor.executeWithOutput("ps -A -o NAME,RSS 2>/dev/null || ps -ef 2>/dev/null") ?: ""
            val pkgMemMap = mutableMapOf<String, Long>()
            output.lines().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val name = parts[0]
                    val rss = parts.last().toLongOrNull() ?: 0L
                    if (name.contains(".") && !name.startsWith("/")) {
                        pkgMemMap[name] = (pkgMemMap[name] ?: 0L) + rss
                    }
                }
            }
            pkgMemMap.forEach { (pkg, memKb) ->
                if (pkg == context.packageName) return@forEach
                if (memKb < 500L) return@forEach
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    result.add(RunningAppInfo(name, pkg, icon, memKb, isSystem))
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        result.sortedByDescending { it.memoryKb }
    }
}

private suspend fun detectRunningAppsUsageStats(context: Context): List<RunningAppInfo> {
    val pm = context.packageManager
    val result = mutableListOf<RunningAppInfo>()
    return withContext(Dispatchers.IO) {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 1000L * 60 * 15
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            stats?.filter { it.lastTimeUsed >= start }?.forEach { stat ->
                val pkg = stat.packageName ?: return@forEach
                if (pkg == context.packageName) return@forEach
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    result.add(RunningAppInfo(name, pkg, icon, 0L, isSystem))
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        result.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(navController: NavController, lang: String, onShowAd: (onGranted: () -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRooted = remember { TweakExecutor.checkRoot() }

    var runningApps by remember { mutableStateOf<List<RunningAppInfo>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var totalRam by remember { mutableStateOf(0L) }
    var availRam by remember { mutableStateOf(0L) }
    var detectionMethod by remember { mutableStateOf("") }

    var showRunningSection by remember { mutableStateOf(false) }
    var showForceStopWarning by remember { mutableStateOf<RunningAppInfo?>(null) }

    val protectedPackages = remember {
        setOf(
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings",
            "android",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.launcher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.realme.launcher",
            "com.transsion.launcher",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.android.wifi",
            "com.android.server.telecom",
            "com.android.keyguard",
            "com.android.shell",
            context.packageName
        )
    }

    var showDexOptSection by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<RunningAppInfo>>(emptyList()) }
    var isLoadingInstalled by remember { mutableStateOf(false) }

    var isDexOptRunning by remember { mutableStateOf(false) }
    var dexOptProgress by remember { mutableStateOf(0f) }
    var dexOptCurrentApp by remember { mutableStateOf("") }
    var dexOptDone by remember { mutableStateOf(false) }
    var dexOptCancelled by remember { mutableStateOf(false) }
    var showDexOptAllWarning by remember { mutableStateOf(false) }
    var showDexOptSingleWarning by remember { mutableStateOf<String?>(null) }
    var showCancelWarning by remember { mutableStateOf(false) }

    fun refreshRam() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                totalRam = mi.totalMem
                availRam = mi.availMem
            }
        }
    }

    fun loadRunningApps() {
        scope.launch {
            isLoadingApps = true
            refreshRam()
            if (isRooted) {
                val apps = detectRunningAppsRoot(context)
                if (apps.isNotEmpty()) {
                    runningApps = apps
                    detectionMethod = "Root (ps)"
                } else {
                    runningApps = detectRunningAppsUsageStats(context)
                    detectionMethod = "UsageStats"
                }
            } else {
                runningApps = detectRunningAppsUsageStats(context)
                detectionMethod = "UsageStats"
            }
            isLoadingApps = false
        }
    }

    fun loadInstalledApps() {
        scope.launch {
            isLoadingInstalled = true
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val result = pm.getInstalledPackages(PackageManager.GET_META_DATA).mapNotNull { pkg ->
                    try {
                        val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                        if (pkg.packageName == context.packageName) return@mapNotNull null
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = try { pm.getApplicationIcon(pkg.packageName) } catch (e: Exception) { null }
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        RunningAppInfo(name, pkg.packageName, icon, 0L, isSystem)
                    } catch (e: Exception) { null }
                }.sortedBy { it.name.lowercase() }
                installedApps = result
            }
            isLoadingInstalled = false
        }
    }

    LaunchedEffect(Unit) { loadRunningApps() }
    LaunchedEffect(Unit) { while (true) { delay(3000); refreshRam() } }

    val usedRam = totalRam - availRam
    val usedPct = if (totalRam > 0) usedRam.toFloat() / totalRam else 0f
    val ramColor = when {
        usedPct >= 0.85f -> MaterialTheme.colorScheme.error
        usedPct >= 0.65f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    if (showDexOptAllWarning) {
        DexOptWarningDialog(lang = lang,
            title = context.getString(R.string.memory_dexopt_all),
            body = context.getString(R.string.memory_dexopt_all),
            onConfirm = {
                showDexOptAllWarning = false
                onShowAd {
                    isDexOptRunning = true; dexOptDone = false; dexOptCancelled = false
                    scope.launch {
                        val apps = installedApps.toList()
                        apps.forEachIndexed { i, app ->
                            if (dexOptCancelled) return@forEachIndexed
                            dexOptCurrentApp = app.name
                            dexOptProgress = i.toFloat() / apps.size
                            withContext(Dispatchers.IO) { TweakExecutor.execute("cmd package compile -m speed-profile -f ${app.packageName}") }
                            delay(80)
                        }
                        if (!dexOptCancelled) { dexOptProgress = 1f; dexOptDone = true }
                        isDexOptRunning = false
                    }
                }
            }, onDismiss = { showDexOptAllWarning = false })
    }

    showDexOptSingleWarning?.let { pkg ->
        val appName = installedApps.find { it.packageName == pkg }?.name ?: pkg
        DexOptWarningDialog(lang = lang, title = "DexOpt $appName",
            body = context.getString(R.string.memory_dexopt_app_confirm, appName),
            onConfirm = {
                showDexOptSingleWarning = null
                onShowAd {
                    isDexOptRunning = true; dexOptDone = false; dexOptCancelled = false; dexOptProgress = 0f
                    scope.launch {
                        dexOptCurrentApp = appName
                        withContext(Dispatchers.IO) { TweakExecutor.execute("cmd package compile -m speed-profile -f $pkg") }
                        if (!dexOptCancelled) { dexOptProgress = 1f; dexOptDone = true }
                        isDexOptRunning = false
                    }
                }
            }, onDismiss = { showDexOptSingleWarning = null })
    }

    if (showCancelWarning) {
        AlertDialog(onDismissRequest = { showCancelWarning = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.appdetail_dexopt_cancel_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.appdetail_dexopt_irreversible), fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = { dexOptCancelled = true; showCancelWarning = false; isDexOptRunning = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.appdetail_dexopt_cancel_action))
                }
            },
            dismissButton = { TextButton(onClick = { showCancelWarning = false }) { Text(stringResource(R.string.action_continue)) } })
    }

    showForceStopWarning?.let { app ->
        AlertDialog(
            onDismissRequest = { showForceStopWarning = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.memory_protected_app), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.memory_dexopt_app_confirm, app.name),
                    fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = app
                        showForceStopWarning = null
                        scope.launch {
                            withContext(Dispatchers.IO) { TweakExecutor.execute("am force-stop ${target.packageName}") }
                            Toast.makeText(context, context.getString(R.string.gameboost_app_stopped, target.name), Toast.LENGTH_SHORT).show()
                            delay(800)
                            loadRunningApps()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(stringResource(R.string.appdetail_force_stop_anyway)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showForceStopWarning = null }, shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory_management), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = ramColor.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, ramColor.copy(alpha = 0.4f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(ramColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Memory, null, tint = ramColor, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.memory_ram_usage), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${String.format("%.1f", usedRam / 1024f / 1024f / 1024f)} GB / ${String.format("%.1f", totalRam / 1024f / 1024f / 1024f)} GB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${(usedPct * 100).toInt()}%", fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = ramColor)
                        }
                        LinearProgressIndicator(progress = { usedPct }, modifier = Modifier.fillMaxWidth().height(7.dp), color = ramColor, trackColor = ramColor.copy(alpha = 0.15f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(stringResource(R.string.memory_available) + ": " + String.format("%.1f", availRam / 1024f / 1024f / 1024f) + " GB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (detectionMethod.isNotBlank()) Text(stringResource(R.string.gameboost_detection) + ": $detectionMethod", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                            }
                            TextButton(onClick = { loadRunningApps() }, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_refresh), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.memory_running_apps),
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                                )
                                Text(
                                    if (isLoadingApps) stringResource(R.string.status_detecting)
                                    else if (runningApps.isEmpty()) stringResource(R.string.status_unknown)
                                    else stringResource(R.string.memory_apps_detected, runningApps.size),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!isRooted) {
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                                    Text(stringResource(R.string.status_limited), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            IconButton(onClick = {
                                if (!isLoadingApps) showRunningSection = !showRunningSection
                            }) {
                                Icon(
                                    if (showRunningSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    null
                                )
                            }
                        }

                        AnimatedVisibility(visible = showRunningSection) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isLoadingApps) {
                                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                            Text(stringResource(R.string.status_detecting_processes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                } else if (runningApps.isEmpty()) {
                                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                            Text(stringResource(R.string.memory_no_apps_detected), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                            if (!isRooted) Text(stringResource(R.string.gameboost_more_accurate_root), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                    }
                                } else {
                                    runningApps.forEach { app ->
                                        val isProtected = protectedPackages.any { app.packageName.startsWith(it) }
                                        RunningAppCard(
                                            app = app,
                                            lang = lang,
                                            isRooted = isRooted,
                                            isProtected = isProtected,
                                            onForceStop = {
                                                if (isProtected) {
                                                    showForceStopWarning = app
                                                } else {
                                                    scope.launch {
                                                        withContext(Dispatchers.IO) { TweakExecutor.execute("am force-stop ${app.packageName}") }
                                                        Toast.makeText(context, context.getString(R.string.gameboost_app_stopped, app.name), Toast.LENGTH_SHORT).show()
                                                        delay(800)
                                                        loadRunningApps()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = { loadRunningApps() },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_refresh), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("DexOpt", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(stringResource(R.string.memory_recompile_apps), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                showDexOptSection = !showDexOptSection
                                if (showDexOptSection && installedApps.isEmpty()) loadInstalledApps()
                            }) { Icon(if (showDexOptSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null) }
                        }

                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Row(Modifier.padding(10.dp)) {
                                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.memory_dexopt_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }

                        AnimatedVisibility(visible = showDexOptSection) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (!isRooted) {
                                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                        Text(stringResource(R.string.memory_dexopt_warning), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp))
                                    }
                                }

                                if (isDexOptRunning || dexOptDone) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (dexOptDone) stringResource(R.string.action_done)
                                                else stringResource(R.string.memory_compiling_app, dexOptCurrentApp),
                                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                                color = if (dexOptDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                            )
                                            Text("${(dexOptProgress * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                        }
                                        LinearProgressIndicator(progress = { dexOptProgress }, modifier = Modifier.fillMaxWidth().height(6.dp), color = if (dexOptDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                                        if (isDexOptRunning) Text(stringResource(R.string.memory_dexopt_do_not_close), fontSize = 10.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                if (isDexOptRunning) {
                                    OutlinedButton(onClick = { showCancelWarning = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                                        Icon(Icons.Default.Cancel, null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.appdetail_dexopt_cancel_action))
                                    }
                                } else {
                                    Button(onClick = { showDexOptAllWarning = true; dexOptDone = false }, enabled = isRooted, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                                        Icon(Icons.Default.AllInclusive, null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.memory_dexopt_all))
                                    }
                                }

                                if (!isDexOptRunning) {
                                    Text(stringResource(R.string.debug_per_app, installedApps.size), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (isLoadingInstalled) {
                                        Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                                Text(stringResource(R.string.status_loading_app_list), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    } else if (installedApps.isEmpty()) {
                                        OutlinedButton(onClick = { loadInstalledApps() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.debug_load_app_list))
                                        }
                                    } else {
                                        installedApps.forEach { app ->
                                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                                val iconBitmap = remember(app.packageName) { try { app.icon?.toBitmap(48, 48)?.asImageBitmap() } catch (e: Exception) { null } }
                                                if (iconBitmap != null) {
                                                    Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                                                } else {
                                                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                                        Text(app.name.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                                    }
                                                }
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(app.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(app.packageName, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                OutlinedButton(onClick = { showDexOptSingleWarning = app.packageName }, enabled = isRooted && !isDexOptRunning, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                                    Text("DexOpt", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RunningAppCard(app: RunningAppInfo, lang: String, isRooted: Boolean, isProtected: Boolean, onForceStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isProtected) MaterialTheme.colorScheme.surfaceContainerHigh
                             else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(
            0.5.dp,
            if (isProtected) MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val iconBitmap = remember(app.packageName) { try { app.icon?.toBitmap(64, 64)?.asImageBitmap() } catch (e: Exception) { null } }
            if (iconBitmap != null) {
                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(app.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app.memoryKb > 0) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("${String.format("%.1f", app.memoryKb / 1024f)} MB", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (app.isSystem) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text("System", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (isProtected) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Default.Lock, null, modifier = Modifier.size(9.dp), tint = MaterialTheme.colorScheme.error)
                                Text(stringResource(R.string.status_protected), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (isRooted) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onForceStop,
                    shape = RoundedCornerShape(10.dp),
                    colors = if (isProtected)
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    else
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, if (isProtected) MaterialTheme.colorScheme.error.copy(alpha = 0.25f) else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(if (isProtected) Icons.Default.Warning else Icons.Default.Stop, null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_stop), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DexOptWarningDialog(lang: String, title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(28.dp)) },
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = { Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp) },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text(stringResource(R.string.action_proceed))
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text(stringResource(R.string.action_cancel)) } }
    )
}
