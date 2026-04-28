package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*





import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.utils.ExclusiveExecutor
import com.javapro.utils.PreferenceManager
import com.javapro.utils.ShizukuManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class DebugTab { LOGCAT, DMESG, PROCESS, RESOURCE, CRASH, PACKAGE, BUILD }

private fun logLineColor(line: String): Color? = when {
    line.contains("FATAL", ignoreCase = true) ||
    line.contains("ERROR", ignoreCase = true) ||
    line.contains(" E ") || line.contains("/E:")  -> Color(0xFFEF5350)
    line.contains("WARN",  ignoreCase = true) ||
    line.contains(" W ") || line.contains("/W:")  -> Color(0xFFFFCA28)
    line.contains("INFO",  ignoreCase = true) ||
    line.contains(" I ") || line.contains("/I:")  -> Color(0xFF66BB6A)
    line.contains("DEBUG", ignoreCase = true) ||
    line.contains(" D ") || line.contains("/D:")  -> Color(0xFF90CAF9)
    else                                           -> null
}

private fun saveLogToFile(context: Context, lines: List<String>, baseName: String) {
    try {
        val ts       = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "${baseName}_$ts.txt"
        val content  = if (lines.isEmpty()) "(empty log)" else lines.joinToString("\n")

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = java.io.File(dir, fileName)
        file.writeText(content)

        Toast.makeText(context, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugToolsScreen(
    navController : NavController,
    prefManager   : PreferenceManager,
    lang          : String
) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val isId       = lang == "id"
    val isDark     by prefManager.darkModeFlow.collectAsState()
    val isRooted   = remember { TweakExecutor.checkRoot() }
    val isShizuku  = remember { ShizukuManager.isAvailable() }

    val bgColor   = MaterialTheme.colorScheme.background
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val txtP      = MaterialTheme.colorScheme.onBackground
    val txtS      = MaterialTheme.colorScheme.onSurfaceVariant
    val border    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val logBg     = if (isDark) Color(0xFF0A0A0A) else Color(0xFFF5F5F5)
    val logTxt    = if (isDark) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)

    var selectedTab   by remember { mutableStateOf(DebugTab.LOGCAT) }
    var pkgFilter     by remember { mutableStateOf("") }
    var isLive        by remember { mutableStateOf(false) }
    var isLoading     by remember { mutableStateOf(false) }
    var logLines      by remember { mutableStateOf<List<String>>(emptyList()) }
    var dmesgLines    by remember { mutableStateOf<List<String>>(emptyList()) }
    var processLines  by remember { mutableStateOf<List<String>>(emptyList()) }
    var resourceInfo  by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var crashLines    by remember { mutableStateOf<List<String>>(emptyList()) }
    var packageInfo   by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var buildInfo     by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val listState = rememberLazyListState()

    suspend fun fetchCurrent() {
        isLoading = true
        when (selectedTab) {
            DebugTab.LOGCAT   -> logLines     = ExclusiveExecutor.fetchLogcat(pkgFilter.takeIf { it.isNotBlank() }, 500, isRooted)
            DebugTab.DMESG    -> dmesgLines   = ExclusiveExecutor.fetchDmesg()
            DebugTab.PROCESS  -> processLines = ExclusiveExecutor.fetchRunningProcesses(pkgFilter.takeIf { it.isNotBlank() })
            DebugTab.RESOURCE -> if (pkgFilter.isNotBlank()) resourceInfo = ExclusiveExecutor.fetchAppMemoryInfo(context, pkgFilter)
            DebugTab.CRASH    -> crashLines   = ExclusiveExecutor.fetchCrashDump(500)
            DebugTab.PACKAGE  -> if (pkgFilter.isNotBlank()) packageInfo = ExclusiveExecutor.fetchPackageInfo(context, pkgFilter)
            DebugTab.BUILD    -> if (buildInfo.isEmpty()) buildInfo = ExclusiveExecutor.fetchBuildInfo()
        }
        isLoading = false
    }

    LaunchedEffect(selectedTab, pkgFilter) { fetchCurrent() }

    LaunchedEffect(isLive, selectedTab) {
        if (!isLive) return@LaunchedEffect
        while (isActive) {
            delay(2000L)
            fetchCurrent()
            if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.debug_title),
                            fontWeight = FontWeight.ExtraBold,
                            fontStyle  = FontStyle.Italic,
                            fontSize   = 20.sp,
                            color      = txtP
                        )
                        DbBadge(if (isRooted) "ROOT" else "SHIZUKU", if (isRooted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = txtP)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val lines = when (selectedTab) {
                                DebugTab.LOGCAT   -> logLines
                                DebugTab.DMESG    -> dmesgLines
                                DebugTab.PROCESS  -> processLines
                                DebugTab.RESOURCE -> resourceInfo.entries.map { "${it.key}: ${it.value}" }
                                DebugTab.CRASH    -> crashLines
                                DebugTab.PACKAGE  -> packageInfo.entries.map { "${it.key}: ${it.value}" }
                                DebugTab.BUILD    -> buildInfo.entries.map { "${it.key}: ${it.value}" }
                            }
                            val tabLabel = when (selectedTab) {
                                DebugTab.LOGCAT   -> "logcat"
                                DebugTab.DMESG    -> "dmesg"
                                DebugTab.PROCESS  -> "process"
                                DebugTab.RESOURCE -> "resource"
                                DebugTab.CRASH    -> "crash"
                                DebugTab.PACKAGE  -> "package"
                                DebugTab.BUILD    -> "build"
                            }
                            val baseName = if (pkgFilter.isNotBlank())
                                "${pkgFilter.trim()}_$tabLabel"
                            else
                                "system_$tabLabel"
                            saveLogToFile(context, lines, baseName)
                        }
                    }) {
                        Icon(Icons.Default.Download, null, tint = txtS)
                    }
                    IconButton(onClick = { scope.launch { fetchCurrent() } }) {
                        Icon(Icons.Default.Refresh, null, tint = txtS)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Access mode info
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardColor)
                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isRooted) Icons.Default.VerifiedUser else Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint     = if (isRooted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isRooted)
                        stringResource(R.string.debug_root_status)
                    else
                        stringResource(R.string.debug_shizuku_status),
                    fontSize   = 11.sp,
                    color      = txtS,
                    lineHeight = 16.sp
                )
            }

            // Package filter
            OutlinedTextField(
                value         = pkgFilter,
                onValueChange = { pkgFilter = it },
                label         = { Text(stringResource(R.string.debug_filter_package), fontSize = 11.sp) },
                placeholder   = { Text("com.example.app", fontSize = 11.sp, color = txtS) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                shape         = RoundedCornerShape(12.dp),
                leadingIcon   = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                trailingIcon  = {
                    if (pkgFilter.isNotBlank()) {
                        IconButton(onClick = { pkgFilter = "" }) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = border,
                    focusedLabelColor    = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor  = txtS
                )
            )

            // Tab row
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                DbTabChip(
                    label    = "Logcat",
                    icon     = Icons.Default.Article,
                    selected = selectedTab == DebugTab.LOGCAT,
                    onClick  = { selectedTab = DebugTab.LOGCAT }
                )
                if (isRooted) {
                    DbTabChip(
                        label    = "dmesg",
                        icon     = Icons.Default.Memory,
                        selected = selectedTab == DebugTab.DMESG,
                        onClick  = { selectedTab = DebugTab.DMESG }
                    )
                }
                DbTabChip(
                    label    = stringResource(R.string.debug_process),
                    icon     = Icons.Default.GridView,
                    selected = selectedTab == DebugTab.PROCESS,
                    onClick  = { selectedTab = DebugTab.PROCESS }
                )
                DbTabChip(
                    label    = "Resource",
                    icon     = Icons.Default.BarChart,
                    selected = selectedTab == DebugTab.RESOURCE,
                    onClick  = { selectedTab = DebugTab.RESOURCE }
                )
                DbTabChip(
                    label    = "Crash",
                    icon     = Icons.Default.BugReport,
                    selected = selectedTab == DebugTab.CRASH,
                    onClick  = { selectedTab = DebugTab.CRASH }
                )
                DbTabChip(
                    label    = stringResource(R.string.debug_package),
                    icon     = Icons.Default.GridView,
                    selected = selectedTab == DebugTab.PACKAGE,
                    onClick  = { selectedTab = DebugTab.PACKAGE }
                )
                DbTabChip(
                    label    = "Build",
                    icon     = Icons.Default.Info,
                    selected = selectedTab == DebugTab.BUILD,
                    onClick  = { selectedTab = DebugTab.BUILD }
                )
            }

            // Live toggle row — only shown for Logcat
            if (selectedTab == DebugTab.LOGCAT) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardColor)
                        .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            stringResource(R.string.debug_live_update),
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = if (isLive) Color(0xFF66BB6A) else txtP
                        )
                        Text(
                            stringResource(R.string.debug_auto_refresh_desc),
                            fontSize = 11.sp,
                            color    = txtS
                        )
                    }
                    Switch(
                        checked         = isLive,
                        onCheckedChange = { isLive = it },
                        colors          = SwitchDefaults.colors(
                            checkedThumbColor   = Color.White,
                            checkedTrackColor   = Color(0xFF66BB6A),
                            uncheckedThumbColor = txtS,
                            uncheckedTrackColor = cardColor
                        )
                    )
                }
            }

            // Content
            if (isLoading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            } else {
                when (selectedTab) {
                    DebugTab.LOGCAT, DebugTab.DMESG, DebugTab.PROCESS -> {
                        val lines = when (selectedTab) {
                            DebugTab.LOGCAT  -> logLines
                            DebugTab.DMESG   -> dmesgLines
                            else             -> processLines
                        }
                        val errorCount = lines.count { logLineColor(it) == Color(0xFFEF5350) }
                        val warnCount  = lines.count { logLineColor(it) == Color(0xFFFFCA28) }

                        if (selectedTab == DebugTab.LOGCAT && (errorCount > 0 || warnCount > 0)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEF5350).copy(alpha = 0.08f))
                                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFEF5350).copy(alpha = 0.3f)), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Color(0xFFEF5350), modifier = Modifier.size(14.dp))
                                Text(
                                    "$errorCount error${if (errorCount != 1) "s" else ""} · $warnCount warning${if (warnCount != 1) "s" else ""}",
                                    fontSize = 11.sp, color = Color(0xFFEF5350), fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(logBg)
                                .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(14.dp))
                        ) {
                            if (lines.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.debug_no_log), fontSize = 12.sp, color = txtS)
                                }
                            } else {
                                LazyColumn(
                                    state             = listState,
                                    modifier          = Modifier.fillMaxSize().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    items(lines) { line ->
                                        val color = logLineColor(line) ?: logTxt
                                        Text(
                                            text       = line,
                                            fontSize   = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color      = color,
                                            lineHeight = 14.sp,
                                            modifier   = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text("${lines.size} lines", fontSize = 10.sp, color = txtS)
                            if (isLive && selectedTab == DebugTab.LOGCAT) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.size(6.dp).background(Color(0xFF66BB6A), CircleShape))
                                    Text(stringResource(R.string.debug_live_update_desc), fontSize = 10.sp, color = Color(0xFF66BB6A))
                                }
                            }
                        }
                    }

                    DebugTab.CRASH -> {
                        val crashColor = Color(0xFFEF5350)
                        if (crashLines.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(cardColor), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(32.dp))
                                    Text(stringResource(R.string.debug_no_crash), fontSize = 12.sp, color = txtS)
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(crashColor.copy(0.08f))
                                        .border(androidx.compose.foundation.BorderStroke(0.5.dp, crashColor.copy(0.3f)), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment     = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = crashColor, modifier = Modifier.size(14.dp))
                                    Text(
                                        stringResource(R.string.debug_crash_count, crashLines.size),
                                        fontSize = 11.sp, color = crashColor, fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp))
                                        .background(logBg)
                                        .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(14.dp))
                                ) {
                                    LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        items(crashLines) { line ->
                                            Text(
                                                text       = line,
                                                fontSize   = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color      = logLineColor(line) ?: crashColor,
                                                lineHeight = 14.sp,
                                                modifier   = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text("${crashLines.size} lines", fontSize = 10.sp, color = txtS)
                    }

                    DebugTab.PACKAGE -> {
                        if (pkgFilter.isBlank()) {
                            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(cardColor), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.GridView, null, tint = txtS, modifier = Modifier.size(32.dp))
                                    Text(
                                    stringResource(R.string.debug_filter_package),
                                        fontSize = 12.sp, color = txtS, lineHeight = 18.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else if (packageInfo.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(cardColor), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.gameboost_package_not_found), fontSize = 12.sp, color = txtS)
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp))
                                    .background(cardColor)
                                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(14.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(pkgFilter, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                HorizontalDivider(color = border, thickness = 0.5.dp)
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(packageInfo.entries.toList()) { (key, value) ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(key, fontSize = 10.sp, color = txtS, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
                                            Text(value, fontSize = 12.sp, color = txtP, fontFamily = if (key.contains("Permission") || key == "Package") FontFamily.Monospace else FontFamily.Default, lineHeight = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DebugTab.BUILD -> {
                        if (buildInfo.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(cardColor), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp))
                                    .background(cardColor)
                                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(14.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    items(buildInfo.entries.toList()) { (key, value) ->
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment     = Alignment.Top
                                            ) {
                                                Text(key, fontSize = 11.sp, color = txtS, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.45f))
                                                Text(value, fontSize = 11.sp, color = txtP, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.55f), lineHeight = 15.sp)
                                            }
                                            HorizontalDivider(color = border, thickness = 0.3.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DebugTab.RESOURCE -> {
                        if (pkgFilter.isBlank()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(cardColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Search, null, tint = txtS, modifier = Modifier.size(32.dp))
                                    Text(
                                    stringResource(R.string.debug_filter_package),
                                        fontSize   = 12.sp,
                                        color      = txtS,
                                        lineHeight = 18.sp,
                                        textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else if (resourceInfo.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(cardColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.debug_app_no_data), fontSize = 12.sp, color = txtS)
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(cardColor)
                                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, border), RoundedCornerShape(14.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(pkgFilter, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                HorizontalDivider(color = border, thickness = 0.5.dp)
                                resourceInfo.forEach { (key, value) ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment     = Alignment.CenterVertically
                                    ) {
                                        Text(key, fontSize = 12.sp, color = txtS, fontWeight = FontWeight.Medium)
                                        Text(value, fontSize = 12.sp, color = txtP, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedTab == DebugTab.LOGCAT || selectedTab == DebugTab.CRASH) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DbLegendDot(Color(0xFFEF5350), "Error/Fatal")
                    DbLegendDot(Color(0xFFFFCA28), "Warn")
                    DbLegendDot(Color(0xFF66BB6A), "Info")
                    DbLegendDot(Color(0xFF90CAF9), "Debug")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DbTabChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(50),
        color   = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        border  = if (selected)
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        else
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint     = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text       = label,
                fontSize   = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color      = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DbBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(0.12f), border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(0.4f))) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), letterSpacing = 0.5.sp)
    }
}

@Composable
private fun DbLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
