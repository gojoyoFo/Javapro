package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*





import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun readKernelVal(path: String): String? =
    withContext(Dispatchers.IO) {
        TweakExecutor.executeWithOutput("cat $path 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }
    }

private suspend fun readKernelInt(path: String): Int? = readKernelVal(path)?.toIntOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweakScreen(lang: String, navController: NavController, onShowAd: (onGranted: () -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRooted = remember { TweakExecutor.checkRoot() }

    var batteryPct by remember { mutableStateOf(0) }
    var batteryTemp by remember { mutableStateOf(0f) }
    var batteryHealth by remember { mutableStateOf("") }
    var batteryVoltage by remember { mutableStateOf(0) }
    var batteryWatt by remember { mutableStateOf(0f) }
    var isCharging by remember { mutableStateOf(false) }

    var totalRam by remember { mutableStateOf(0L) }
    var availRam by remember { mutableStateOf(0L) }

    var swappiness by remember { mutableStateOf(60f) }
    var defaultSwappiness by remember { mutableStateOf(60) }
    var cachePressure by remember { mutableStateOf(100f) }
    var defaultCachePressure by remember { mutableStateOf(100) }
    var dirtyRatio by remember { mutableStateOf(20f) }
    var defaultDirtyRatio by remember { mutableStateOf(20) }
    var pageCluster by remember { mutableStateOf(3f) }
    var defaultPageCluster by remember { mutableStateOf(3) }
    var readahead by remember { mutableStateOf(128f) }
    var defaultReadahead by remember { mutableStateOf(128) }

    var selectedScheduler by remember { mutableStateOf("") }
    var availableSchedulers by remember { mutableStateOf(listOf<String>()) }
    var tcpCongestion by remember { mutableStateOf("cubic") }
    var availableCongestion by remember { mutableStateOf(listOf<String>()) }
    var fsyncEnabled by remember { mutableStateOf(true) }

    var isApplyingVm by remember { mutableStateOf(false) }
    var isApplyingIo by remember { mutableStateOf(false) }
    var isApplyingNet by remember { mutableStateOf(false) }
    var vmApplied by remember { mutableStateOf(false) }
    var ioApplied by remember { mutableStateOf(false) }
    var netApplied by remember { mutableStateOf(false) }

    var zramComprAlgo by remember { mutableStateOf("lz4") }
    var isApplyingZram by remember { mutableStateOf(false) }
    var zramApplied by remember { mutableStateOf(false) }

    fun readBattery(strGood: String, strOverheat: String, strDead: String, strOverVoltage: String, strUnknown: String) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (isCharging) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val currentNow = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
            val voltageV = batteryVoltage / 1000f
            val currentA = kotlin.math.abs(currentNow) / 1_000_000f
            batteryWatt = voltageV * currentA
        } else batteryWatt = 0f
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        batteryHealth = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> strGood
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> strOverheat
            BatteryManager.BATTERY_HEALTH_DEAD -> strDead
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> strOverVoltage
            else -> strUnknown
        }
    }

    val strGood        = stringResource(R.string.memory_good)
    val strOverheat    = stringResource(R.string.memory_overheat)
    val strDead        = stringResource(R.string.memory_dead)
    val strOverVoltage = stringResource(R.string.tweak_over_voltage_label)
    val strUnknown     = stringResource(R.string.status_unknown)

    LaunchedEffect(Unit) {
        readBattery(strGood, strOverheat, strDead, strOverVoltage, strUnknown)
        withContext(Dispatchers.IO) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            totalRam = mi.totalMem
            availRam = mi.availMem

            defaultSwappiness = readKernelInt("/proc/sys/vm/swappiness") ?: 60
            swappiness = defaultSwappiness.toFloat()
            defaultCachePressure = readKernelInt("/proc/sys/vm/vfs_cache_pressure") ?: 100
            cachePressure = defaultCachePressure.toFloat()
            defaultDirtyRatio = readKernelInt("/proc/sys/vm/dirty_ratio") ?: 20
            dirtyRatio = defaultDirtyRatio.toFloat()
            defaultPageCluster = readKernelInt("/proc/sys/vm/page-cluster") ?: 3
            pageCluster = defaultPageCluster.toFloat()

            val blockRaw = TweakExecutor.executeWithOutput("ls /sys/block/") ?: ""
            val blockDev = blockRaw.trim().split("\\s+".toRegex()).firstOrNull { it.startsWith("mmcblk") || it.startsWith("nvme") || it.startsWith("sda") }
            if (blockDev != null) {
                defaultReadahead = readKernelInt("/sys/block/$blockDev/queue/read_ahead_kb") ?: 128
                readahead = defaultReadahead.toFloat()
                val schedRaw = readKernelVal("/sys/block/$blockDev/queue/scheduler") ?: ""
                selectedScheduler = Regex("\\[(.+?)]").find(schedRaw)?.groupValues?.get(1) ?: ""
                availableSchedulers = schedRaw.replace("[", "").replace("]", "").trim().split(" ").filter { it.isNotBlank() }
            }

            val zramAlgoRaw = readKernelVal("/sys/block/zram0/comp_algorithm") ?: ""
            zramComprAlgo = Regex("\\[(.+?)]").find(zramAlgoRaw)?.groupValues?.get(1)?.trim() ?: "lz4"

            tcpCongestion = readKernelVal("/proc/sys/net/ipv4/tcp_congestion_control") ?: "cubic"
            availableCongestion = readKernelVal("/proc/sys/net/ipv4/tcp_available_congestion_control")
                ?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: listOf("cubic", "reno")

            fsyncEnabled = readKernelInt("/sys/module/sync/parameters/fsync_enabled") != 0
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            readBattery(strGood, strOverheat, strDead, strOverVoltage, strUnknown)
            withContext(Dispatchers.IO) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                availRam = mi.availMem
            }
        }
    }

    val batteryColor = when {
        batteryPct <= 15 -> MaterialTheme.colorScheme.error
        batteryPct <= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val batteryIcon = when {
        isCharging -> Icons.Default.BatteryChargingFull
        batteryPct <= 15 -> Icons.Default.BatteryAlert
        batteryPct <= 30 -> Icons.Default.BatteryAlert
        batteryPct <= 50 -> Icons.Default.Battery3Bar
        batteryPct <= 70 -> Icons.Default.Battery3Bar
        batteryPct <= 90 -> Icons.Default.Battery5Bar
        else -> Icons.Default.BatteryFull
    }

    val usedRam = totalRam - availRam
    val usedPct = if (totalRam > 0) usedRam.toFloat() / totalRam else 0f
    val ramColor = when {
        usedPct >= 0.85f -> MaterialTheme.colorScheme.error
        usedPct >= 0.65f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.tweak_title_advanced), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        Text(stringResource(R.string.tweak_subtitle_kernel), fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!isRooted) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.tweak_root_not_detected), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            IntrinsicSize.Min.let {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = batteryColor.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, batteryColor.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.padding(14.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(batteryIcon, null, tint = batteryColor, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tweak_section_battery), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("$batteryPct%", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = batteryColor)
                            HorizontalDivider(thickness = 0.5.dp, color = batteryColor.copy(alpha = 0.3f))
                            TKInfoRow(Icons.Default.Thermostat, "${batteryTemp}°C")
                            TKInfoRow(Icons.Default.Bolt, "${batteryVoltage}mV")
                            TKInfoRow(Icons.Default.VerifiedUser, batteryHealth)
                            if (isCharging && batteryWatt > 0f) {
                                TKInfoRow(Icons.Default.Bolt, String.format("%.1fW", batteryWatt))
                            }
                            if (isCharging) {
                                Spacer(Modifier.height(2.dp))
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(stringResource(R.string.tweak_charging_label), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { navController.navigate("memory_screen") },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = ramColor.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, ramColor.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.padding(14.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, null, tint = ramColor, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tweak_section_memory), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            val usedGb = String.format("%.1f", usedRam / 1024f / 1024f / 1024f)
                            val totalGbFloat = totalRam / 1024f / 1024f / 1024f
                            Text("$usedGb GB", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = ramColor)
                            Text(stringResource(R.string.memory_of_gb, totalGbFloat), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { usedPct }, modifier = Modifier.fillMaxWidth().height(5.dp), color = ramColor, trackColor = ramColor.copy(alpha = 0.2f))
                            HorizontalDivider(thickness = 0.5.dp, color = ramColor.copy(alpha = 0.3f))
                            TKInfoRow(Icons.Default.CheckCircle, stringResource(R.string.memory_available) + ": " + String.format("%.1f", availRam / 1024f / 1024f / 1024f) + " GB")
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.action_manage), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ramColor)
                            }
                        }
                    }
                }
            }

            TKSectionLabel(stringResource(R.string.memory_vm_kernel), Icons.Default.Memory, MaterialTheme.colorScheme.primary)

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TKSliderItem("Swappiness", stringResource(R.string.memory_swappiness_desc, defaultSwappiness), swappiness, 0f..200f, 39, isRooted) { swappiness = it }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    TKSliderItem(stringResource(R.string.tweak_cache_pressure_label), stringResource(R.string.memory_cache_pressure_desc, defaultCachePressure), cachePressure, 1f..500f, 49, isRooted) { cachePressure = it }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    TKSliderItem("Dirty Ratio", stringResource(R.string.memory_dirty_ratio_desc, defaultDirtyRatio), dirtyRatio, 1f..90f, 17, isRooted) { dirtyRatio = it }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    TKSliderItem("Page Cluster", stringResource(R.string.memory_page_cluster_desc, defaultPageCluster), pageCluster, 0f..8f, 7, isRooted) { pageCluster = it }

                    if (vmApplied) {
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(stringResource(R.string.action_applied_done), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { swappiness = defaultSwappiness.toFloat(); cachePressure = defaultCachePressure.toFloat(); dirtyRatio = defaultDirtyRatio.toFloat(); pageCluster = defaultPageCluster.toFloat(); vmApplied = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), enabled = isRooted) { Text(stringResource(R.string.action_reset)) }
                        Button(onClick = {
                            onShowAd {
                                scope.launch {
                                    isApplyingVm = true
                                    withContext(Dispatchers.IO) {
                                        TweakExecutor.execute("echo ${swappiness.toInt()} > /proc/sys/vm/swappiness")
                                        TweakExecutor.execute("echo ${cachePressure.toInt()} > /proc/sys/vm/vfs_cache_pressure")
                                        TweakExecutor.execute("echo ${dirtyRatio.toInt()} > /proc/sys/vm/dirty_ratio")
                                        TweakExecutor.execute("echo ${pageCluster.toInt()} > /proc/sys/vm/page-cluster")
                                    }
                                    isApplyingVm = false
                                    vmApplied = true
                                }
                            }
                        }, modifier = Modifier.weight(2f), shape = RoundedCornerShape(10.dp), enabled = isRooted && !isApplyingVm) {
                            if (isApplyingVm) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(stringResource(R.string.action_apply))
                        }
                    }
                }
            }

            TKSectionLabel(stringResource(R.string.tweak_section_io_storage), Icons.Default.Storage, MaterialTheme.colorScheme.secondary)

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TKSliderItem("Read-Ahead (KB)", stringResource(R.string.memory_readahead_desc, defaultReadahead), readahead, 64f..2048f, 30, isRooted) { readahead = it }

                    if (availableSchedulers.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Text("I/O Scheduler", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(stringResource(R.string.tweak_scheduler_desc, selectedScheduler), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            availableSchedulers.chunked(4).forEach { rowItems ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rowItems.forEach { sched ->
                                        val isSel = selectedScheduler == sched
                                        Surface(
                                            modifier = Modifier.weight(1f).clickable(enabled = isRooted) { selectedScheduler = sched },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                        ) {
                                            Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                                Text(sched, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    // Isi sisa slot kosong agar Row tetap rata
                                    repeat(4 - rowItems.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (ioApplied) {
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(R.string.action_applied_done), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { readahead = defaultReadahead.toFloat(); ioApplied = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), enabled = isRooted) { Text(stringResource(R.string.action_reset)) }
                        Button(onClick = {
                            onShowAd {
                                scope.launch {
                                    isApplyingIo = true
                                    withContext(Dispatchers.IO) {
                                        TweakExecutor.execute("for f in /sys/block/*/queue/read_ahead_kb; do echo ${readahead.toInt()} > \$f; done")
                                        if (selectedScheduler.isNotBlank()) TweakExecutor.execute("for f in /sys/block/*/queue/scheduler; do echo $selectedScheduler > \$f 2>/dev/null; done")
                                    }
                                    isApplyingIo = false
                                    ioApplied = true
                                }
                            }
                        }, modifier = Modifier.weight(2f), shape = RoundedCornerShape(10.dp), enabled = isRooted && !isApplyingIo) {
                            if (isApplyingIo) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(stringResource(R.string.action_apply))
                        }
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    // ZRAM Compression Algorithm
                    val zramAlgos = listOf("lz4" to "LZ4", "zstd" to "ZSTD", "lzo" to "LZO", "lzo-rle" to "LZO-RLE", "none" to "Off")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.memory_zram_algo), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(stringResource(R.string.memory_zram_algo_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            zramAlgos.forEach { (value, label) ->
                                val isSel = zramComprAlgo == value
                                Surface(
                                    modifier = Modifier.weight(1f).clickable(enabled = isRooted) { zramComprAlgo = value },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                ) {
                                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(label, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (zramApplied) {
                            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(stringResource(R.string.action_applied_done), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { zramComprAlgo = "lz4"; zramApplied = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                enabled = isRooted
                            ) { Text(stringResource(R.string.action_reset)) }
                            Button(
                                onClick = {
                                    onShowAd {
                                        scope.launch {
                                            isApplyingZram = true
                                            withContext(Dispatchers.IO) {
                                                if (zramComprAlgo != "none") {
                                                    TweakExecutor.execute("swapoff /dev/block/zram0 2>/dev/null")
                                                    TweakExecutor.execute("echo 1 > /sys/block/zram0/reset 2>/dev/null")
                                                    TweakExecutor.execute("echo $zramComprAlgo > /sys/block/zram0/comp_algorithm 2>/dev/null")
                                                    TweakExecutor.execute("echo \$(awk '/MemTotal/{printf \"%d\", \$2*1024*0.5}' /proc/meminfo) > /sys/block/zram0/disksize 2>/dev/null")
                                                    TweakExecutor.execute("mkswap /dev/block/zram0 2>/dev/null && swapon /dev/block/zram0 2>/dev/null")
                                                }
                                            }
                                            isApplyingZram = false
                                            zramApplied = true
                                        }
                                    }
                                },
                                modifier = Modifier.weight(2f),
                                shape = RoundedCornerShape(10.dp),
                                enabled = isRooted && !isApplyingZram
                            ) {
                                if (isApplyingZram) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text(stringResource(R.string.action_apply))
                            }
                        }
                    }
                }
            }

            TKSectionLabel(stringResource(R.string.tweak_network), Icons.Default.Wifi, MaterialTheme.colorScheme.tertiary)

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.tweak_tcp_congestion_label), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(stringResource(R.string.tweak_tcp_congestion_desc, tcpCongestion), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableCongestion.take(4).forEach { algo ->
                            val isSel = tcpCongestion == algo
                            Surface(modifier = Modifier.weight(1f).clickable(enabled = isRooted) { tcpCongestion = algo }, shape = RoundedCornerShape(8.dp),
                                color = if (isSel) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))) {
                                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(algo, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (netApplied) {
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text(stringResource(R.string.action_applied_done), fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { tcpCongestion = "cubic"; netApplied = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), enabled = isRooted) { Text(stringResource(R.string.action_reset)) }
                        Button(onClick = {
                            onShowAd {
                                scope.launch {
                                    withContext(Dispatchers.IO) { TweakExecutor.execute("echo $tcpCongestion > /proc/sys/net/ipv4/tcp_congestion_control") }
                                    netApplied = true
                                }
                            }
                        }, modifier = Modifier.weight(2f), shape = RoundedCornerShape(10.dp), enabled = isRooted) {
                            Text(stringResource(R.string.action_apply))
                        }
                    }
                }
            }

            TKSectionLabel(stringResource(R.string.tweak_section_miscellaneous), Icons.Default.Tune, MaterialTheme.colorScheme.primary)

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fsync", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(stringResource(R.string.memory_sync_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!fsyncEnabled) {
                            Spacer(Modifier.height(4.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(stringResource(R.string.memory_data_loss_risk), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Switch(checked = fsyncEnabled, onCheckedChange = { v ->
                        fsyncEnabled = v
                        scope.launch { withContext(Dispatchers.IO) { TweakExecutor.execute("echo ${if (v) 1 else 0} > /sys/module/sync/parameters/fsync_enabled 2>/dev/null") } }
                    }, enabled = isRooted)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TKSectionLabel(text: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun TKSliderItem(label: String, desc: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, onValueChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("${value.toInt()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${range.start.toInt()}", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
            Text("${range.endInclusive.toInt()}", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun TKInfoRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
    }
}
