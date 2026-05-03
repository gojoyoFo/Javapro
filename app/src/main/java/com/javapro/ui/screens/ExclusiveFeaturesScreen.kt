package com.javapro.ui.screens

import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.utils.ColorPreset
import com.javapro.utils.ExclusiveExecutor
import com.javapro.utils.PreferenceManager
import com.javapro.utils.PremiumManager
import com.javapro.utils.TweakExecutor
import com.javapro.utils.TweakProfile
import com.javapro.utils.ShizukuManager
import com.javapro.ui.components.getNavBarStyle
import com.javapro.ui.components.setNavBarStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ExWhite = Color(0xFFFFFFFF)

private const val PREFS_MAIN       = "javapro_settings"
private const val PREFS_BOOST      = "GameBoostPrefs"
private const val PREFS_APPOPS     = "AppOpsPrefs"
private const val PREFS_APPPROFILE = "AppProfilePrefs"
private const val KEY_AUTO_TWEAK   = "auto_tweak_enabled"
private const val KEY_BACKUP_URI   = "backup_uri_persisted"
private const val BACKUP_FILENAME  = "javapro_backup.json"

private data class BuiltInPreset(
    val nameId : String,
    val nameEn : String,
    val red    : Float,
    val green  : Float,
    val blue   : Float,
    val sat    : Float,
    val icon   : ImageVector,
    val tint   : Color
)

private val BUILTIN_PRESETS = listOf(
    BuiltInPreset("Gaming",         "Gaming",    950f,  980f,  1060f, 1160f, Icons.Default.SportsEsports, Color(0xFF42A5F5)),
    BuiltInPreset("Sinema",         "Cinema",    1060f, 1000f,  840f, 1100f, Icons.Default.Movie,         Color(0xFFEF5350)),
    BuiltInPreset("Natural",        "Natural",   1000f, 1000f, 1000f, 1000f, Icons.Default.Park,          Color(0xFF66BB6A)),
    BuiltInPreset("Perawatan Mata", "Eye Care",  1050f,  975f,  740f,  940f, Icons.Default.RemoveRedEye,  Color(0xFFFFCA28))
)

private val NET_PRESETS = listOf(
    Triple("Gaming",    "Gaming",    listOf("echo 1 > /proc/sys/net/ipv4/tcp_low_latency", "echo 1 > /proc/sys/net/ipv4/tcp_fastopen", "settings put global wifi_sleep_policy 2", "settings put global wifi_scan_throttle_enabled 0")),
    Triple("Streaming", "Streaming", listOf("echo 4194304 > /proc/sys/net/core/rmem_max", "echo 4194304 > /proc/sys/net/core/wmem_max", "settings put global wifi_sleep_policy 0")),
    Triple("Download",  "Download",  listOf("echo 16777216 > /proc/sys/net/core/rmem_max", "echo 16777216 > /proc/sys/net/core/wmem_max", "echo bbr > /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null")),
    Triple("Hemat",     "Saver",     listOf("settings put global mobile_data_always_on 0", "settings put global wifi_scan_throttle_enabled 1", "echo 0 > /proc/sys/net/ipv4/tcp_low_latency"))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExclusiveFeaturesScreen(navController: NavController, prefManager: PreferenceManager, lang: String) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val isId      = lang == "id"
    val isPremium = remember { PremiumManager.isPremium(context) }
    val isRooted  = remember { TweakExecutor.checkRoot() }
    val isShizuku = remember { ShizukuManager.isAvailable() }
    val hasAccess = isRooted || isShizuku
    val mainPrefs  = remember { context.getSharedPreferences(PREFS_MAIN,       Context.MODE_PRIVATE) }
    val boostPrefs = remember { context.getSharedPreferences(PREFS_BOOST,      Context.MODE_PRIVATE) }
    val appPrefs   = remember { context.getSharedPreferences(PREFS_APPPROFILE, Context.MODE_PRIVATE) }
    val opsPrefs   = remember { context.getSharedPreferences(PREFS_APPOPS,     Context.MODE_PRIVATE) }

    var showGateDialog by remember { mutableStateOf(!isPremium) }

    // FIX BUG: Guard mencegah double-invoke saat user tap cepat.
    // Surface onClick tidak punya debounce bawaan — sebelum recomposition
    // selesai, callback bisa terpanggil 2x sehingga popup muncul dua kali.
    var isNavigating by remember { mutableStateOf(false) }

    if (showGateDialog) {
        ExclusiveGateDialog(
            lang       = lang,
            onWatchAds = {
                if (!isNavigating) {
                    isNavigating   = true
                    showGateDialog = false
                    navController.popBackStack()
                    navController.navigate("daily_reward")
                }
            },
            onUpgrade  = {
                if (!isNavigating) {
                    isNavigating   = true
                    showGateDialog = false
                    navController.popBackStack()
                    navController.navigate("premium")
                }
            },
            onDismiss  = {
                if (!isNavigating) {
                    isNavigating   = true
                    showGateDialog = false
                    navController.popBackStack()
                }
            }
        )
        return
    }

    var presets         by remember { mutableStateOf(ExclusiveExecutor.loadPresets(mainPrefs)) }
    var profiles        by remember { mutableStateOf(ExclusiveExecutor.loadProfiles(mainPrefs)) }
    var backupSaved     by remember { mutableStateOf(false) }
    var backupError     by remember { mutableStateOf(false) }
    var isImporting     by remember { mutableStateOf(false) }
    var importOk        by remember { mutableStateOf<Boolean?>(null) }
    var autoTweak       by remember { mutableStateOf(mainPrefs.getBoolean(KEY_AUTO_TWEAK, false)) }
    var autoStatus      by remember { mutableStateOf("") }
    var savedUri        by remember { mutableStateOf(mainPrefs.getString(KEY_BACKUP_URI, null)) }
    var showAddPreset   by remember { mutableStateOf(false) }
    var useFloatingNav  by remember { mutableStateOf(getNavBarStyle(context)) }
    var presetName      by remember { mutableStateOf("") }
    var delPreset       by remember { mutableStateOf<ColorPreset?>(null) }
    var showAddProfile  by remember { mutableStateOf(false) }
    var profileName     by remember { mutableStateOf("") }
    var delProfile      by remember { mutableStateOf<TweakProfile?>(null) }
    var showNoConfig    by remember { mutableStateOf<String?>(null) }
    var netApplying     by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    var presetExpanded  by remember { mutableStateOf(false) }
    var netExpanded     by remember { mutableStateOf(false) }
    var freezeExpanded  by remember { mutableStateOf(false) }
    var freezePkgInput  by remember { mutableStateOf("") }
    var freezeResult    by remember { mutableStateOf<Boolean?>(null) }
    var freezeIsApplying by remember { mutableStateOf(false) }
    var freezeIsFrozen  by remember { mutableStateOf(false) }
    var freezePkgChecked by remember { mutableStateOf("") }
    var freezeNotFound  by remember { mutableStateOf(false) }
    var customBannerUri by remember { mutableStateOf(mainPrefs.getString(ExclusiveExecutor.KEY_BANNER_URI, null)) }

    val redVal   by prefManager.redValFlow.collectAsState()
    val greenVal by prefManager.greenValFlow.collectAsState()
    val blueVal  by prefManager.blueValFlow.collectAsState()
    val satVal   by prefManager.satValFlow.collectAsState()

    LaunchedEffect(autoTweak) {
        if (!autoTweak) { autoStatus = ""; return@LaunchedEffect }
        val gameList = withContext(Dispatchers.IO) { ExclusiveExecutor.readGameList(context) }
        if (gameList.isEmpty()) {
            autoStatus = context.getString(R.string.excl_game_txt_not_found)
            return@LaunchedEffect
        }
        autoStatus = context.getString(R.string.excl_auto_status_active, gameList.size)
        var lastPkg = ""
        while (autoTweak) {
            val fg = withContext(Dispatchers.IO) { ExclusiveExecutor.getForeground(context) }
            if (fg != null && fg in gameList && fg != lastPkg) {
                val has = withContext(Dispatchers.IO) { ExclusiveExecutor.hasBoostConfig(boostPrefs, fg) }
                if (!has) {
                    showNoConfig = fg; lastPkg = fg
                } else {
                    lastPkg    = fg
                    autoStatus = context.getString(R.string.excl_tweak_applied, fg)
                    withContext(Dispatchers.IO) { ExclusiveExecutor.applyBoostForPkg(boostPrefs, fg, isRooted) }
                }
            }
            if (fg != null && fg !in gameList) lastPkg = ""
            delay(2000L)
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        mainPrefs.edit().putString(KEY_BACKUP_URI, uri.toString()).apply()
        savedUri = uri.toString()
        scope.launch {
            val json = withContext(Dispatchers.IO) { ExclusiveExecutor.buildBackupJson(mainPrefs, boostPrefs, appPrefs, opsPrefs) }
            val ok   = withContext(Dispatchers.IO) { ExclusiveExecutor.writeBackupToUri(context, uri, json) }
            backupSaved = ok; backupError = !ok
            Toast.makeText(context, if (ok) context.getString(R.string.excl_backup_saved) else context.getString(R.string.action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun doBackup() {
        backupSaved = false; backupError = false
        val uri = savedUri
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) { ExclusiveExecutor.buildBackupJson(mainPrefs, boostPrefs, appPrefs, opsPrefs) }
                val ok   = withContext(Dispatchers.IO) { ExclusiveExecutor.writeBackupToUri(context, android.net.Uri.parse(uri), json) }
                if (ok) {
                    backupSaved = true
                    Toast.makeText(context, context.getString(R.string.excl_backup_updated), Toast.LENGTH_SHORT).show()
                } else {
                    mainPrefs.edit().remove(KEY_BACKUP_URI).apply()
                    savedUri = null
                    backupLauncher.launch(BACKUP_FILENAME)
                }
            }
        } else backupLauncher.launch(BACKUP_FILENAME)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true; importOk = null
            val json = withContext(Dispatchers.IO) { ExclusiveExecutor.readJsonFromUri(context, uri) }
            if (json.isNullOrBlank()) { isImporting = false; importOk = false; return@launch }
            val ok = withContext(Dispatchers.IO) { ExclusiveExecutor.applyBackupJson(mainPrefs, boostPrefs, appPrefs, opsPrefs, prefManager, json) }
            isImporting = false; importOk = ok
            if (ok) {
                presets   = ExclusiveExecutor.loadPresets(mainPrefs)
                profiles  = ExclusiveExecutor.loadProfiles(mainPrefs)
                autoTweak = mainPrefs.getBoolean(KEY_AUTO_TWEAK, false)
            }
            Toast.makeText(context, if (ok) context.getString(R.string.excl_import_success) else context.getString(R.string.excl_invalid_file), Toast.LENGTH_SHORT).show()
        }
    }

    val bannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        mainPrefs.edit().putString(ExclusiveExecutor.KEY_BANNER_URI, uri.toString()).apply()
        customBannerUri = uri.toString()
        Toast.makeText(context, context.getString(R.string.excl_banner_updated), Toast.LENGTH_SHORT).show()
    }

    if (showNoConfig != null) {
        AlertDialog(
            onDismissRequest = { showNoConfig = null },
            containerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape            = RoundedCornerShape(20.dp),
            icon    = { Icon(Icons.Default.Warning, null, tint = Color(0xFFFFCA28), modifier = Modifier.size(28.dp)) },
            title   = { Text(stringResource(R.string.excl_no_tweaks_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text    = { Text(stringResource(R.string.excl_no_tweaks_body, showNoConfig ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp) },
            confirmButton = { Button(onClick = { showNoConfig = null; navController.navigate("gamelist") }, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(stringResource(R.string.action_open_gamelist), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showNoConfig = null }) { Text(stringResource(R.string.action_dismiss), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        )
    }

    if (delPreset != null) {
        ExConfirmDialog(
            title        = context.getString(R.string.excl_delete_preset_title),
            body         = "\"${delPreset!!.name}\"",
            confirmLabel = context.getString(R.string.action_delete),
            isId         = isId,
            onConfirm    = { val u = presets.filter { it.name != delPreset!!.name }; presets = u; ExclusiveExecutor.savePresets(mainPrefs, u); delPreset = null },
            onDismiss    = { delPreset = null }
        )
    }

    if (showAddPreset) {
        AlertDialog(
            onDismissRequest = { showAddPreset = false; presetName = "" },
            containerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape            = RoundedCornerShape(20.dp),
            title   = { Text(stringResource(R.string.excl_save_preset_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("R:${redVal.toInt()} G:${greenVal.toInt()} B:${blueVal.toInt()} S:${satVal.toInt()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    ExTextField(presetName, stringResource(R.string.excl_preset_name_hint)) { presetName = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isBlank()) return@Button
                        val u = presets.filter { it.name != presetName.trim() } + ColorPreset(presetName.trim(), redVal, greenVal, blueVal, satVal)
                        presets = u; ExclusiveExecutor.savePresets(mainPrefs, u)
                        showAddPreset = false; presetName = ""
                    },
                    shape  = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAddPreset = false; presetName = "" }) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        )
    }

    if (delProfile != null) {
        ExConfirmDialog(
            title        = context.getString(R.string.excl_delete_profile_title),
            body         = "\"${delProfile!!.name}\"",
            confirmLabel = context.getString(R.string.action_delete),
            isId         = isId,
            onConfirm    = { val u = profiles.filter { it.name != delProfile!!.name }; profiles = u; ExclusiveExecutor.saveProfiles(mainPrefs, u); delProfile = null },
            onDismiss    = { delProfile = null }
        )
    }

    if (showAddProfile) {
        AlertDialog(
            onDismissRequest = { showAddProfile = false; profileName = "" },
            containerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape            = RoundedCornerShape(20.dp),
            title   = { Text(stringResource(R.string.excl_save_profile_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
            text    = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.excl_save_profile_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    ExTextField(profileName, stringResource(R.string.excl_profile_name_hint)) { profileName = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (profileName.isBlank()) return@Button
                        val np = TweakProfile(profileName.trim(), System.currentTimeMillis(), ExclusiveExecutor.snapPrefs(mainPrefs), ExclusiveExecutor.snapPrefs(boostPrefs), ExclusiveExecutor.snapPrefs(appPrefs), ExclusiveExecutor.snapPrefs(opsPrefs))
                        val u  = profiles.filter { it.name != np.name } + np
                        profiles = u; ExclusiveExecutor.saveProfiles(mainPrefs, u)
                        showAddProfile = false; profileName = ""
                        Toast.makeText(context, context.getString(R.string.excl_profile_saved), Toast.LENGTH_SHORT).show()
                    },
                    shape  = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAddProfile = false; profileName = "" }) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title          = { Text(stringResource(R.string.excl_title), fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface) } },
                colors         = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            ExLabel(Icons.Default.Backup, stringResource(R.string.excl_backup_import_label), MaterialTheme.colorScheme.primary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (savedUri != null) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)), RoundedCornerShape(10.dp)).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Backup, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                            Text(stringResource(R.string.excl_backup_location_set), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { mainPrefs.edit().remove(KEY_BACKUP_URI).apply(); savedUri = null }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text(stringResource(R.string.action_change), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExPillButton(stringResource(R.string.action_backup), Icons.Default.Backup, compact = true) { doBackup() }
                        ExPillButton(
                            if (isImporting) stringResource(R.string.status_importing) else stringResource(R.string.action_import),
                            if (isImporting) Icons.Default.Download else Icons.Default.Circle,
                            enabled = !isImporting, compact = true
                        ) { importLauncher.launch(arrayOf("application/json", "*/*")) }
                    }
                    AnimatedVisibility(backupSaved, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) { ExFeedback(Icons.Default.CheckCircle, stringResource(R.string.excl_backup_saved_success), Color(0xFF66BB6A)) }
                    AnimatedVisibility(backupError, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) { ExFeedback(Icons.Default.Warning, stringResource(R.string.action_save_failed), Color(0xFFEF5350)) }
                    AnimatedVisibility(importOk == true,  enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) { ExFeedback(Icons.Default.CheckCircle, stringResource(R.string.excl_import_success), Color(0xFF66BB6A)) }
                    AnimatedVisibility(importOk == false, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) { ExFeedback(Icons.Default.Warning, stringResource(R.string.excl_invalid_file), Color(0xFFEF5350)) }
                }
            }

            ExLabel(Icons.Default.Tune, stringResource(R.string.excl_profile_switcher_label), MaterialTheme.colorScheme.secondary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { profileExpanded = !profileExpanded }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExBadge("${profiles.size}", MaterialTheme.colorScheme.secondary)
                            Text(stringResource(R.string.excl_saved_profiles), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        val arrowRotation by animateFloatAsState(if (profileExpanded) 180f else 0f, label = "profileArrow")
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).then(Modifier.graphicsLayer { rotationZ = arrowRotation }))
                    }
                    AnimatedVisibility(visible = profileExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                            ExPillButton(stringResource(R.string.excl_save_current_profile), Icons.Default.Save) { showAddProfile = true }
                            if (profiles.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
                                profiles.sortedByDescending { it.createdAt }.forEach { p ->
                                    ExProfileItem(p, isId,
                                        onApply  = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { ExclusiveExecutor.restoreProfile(p, mainPrefs, boostPrefs, appPrefs, opsPrefs, prefManager) }
                                                presets = ExclusiveExecutor.loadPresets(mainPrefs)
                                                Toast.makeText(context, context.getString(R.string.excl_profile_applied, p.name), Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDelete = { delProfile = p }
                                    )
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.excl_no_profiles_yet), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            ExLabel(Icons.Default.AutoAwesome, stringResource(R.string.excl_auto_detect_label), MaterialTheme.colorScheme.tertiary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.excl_auto_detect_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.excl_auto_detect_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked         = autoTweak,
                            onCheckedChange = { autoTweak = it; mainPrefs.edit().putBoolean(KEY_AUTO_TWEAK, it).apply() },
                            colors          = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (autoStatus.isNotEmpty()) {
                        ExFeedback(Icons.Default.Info, autoStatus, MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            ExLabel(Icons.Default.Wifi, stringResource(R.string.excl_network_label), MaterialTheme.colorScheme.tertiary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { netExpanded = !netExpanded }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.excl_choose_network_preset), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        val arrowRotation by animateFloatAsState(if (netExpanded) 180f else 0f, label = "netArrow")
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).then(Modifier.graphicsLayer { rotationZ = arrowRotation }))
                    }
                    AnimatedVisibility(visible = netExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            if (netApplying) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.status_applying), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                NET_PRESETS.forEach { (labelId, labelEn, cmds) ->
                                    val needsRoot = cmds.any { it.startsWith("echo") }
                                    val canApply  = !needsRoot || isRooted
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(if (isId) labelId else labelEn, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (canApply) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (needsRoot) ExBadge("ROOT", if (isRooted) Color(0xFF4CAF50) else Color(0xFFEF5350))
                                        Surface(
                                            onClick  = {
                                                if (!canApply) return@Surface
                                                scope.launch {
                                                    netApplying = true
                                                    ExclusiveExecutor.applyNetPreset(cmds)
                                                    netApplying = false
                                                    Toast.makeText(context, context.getString(R.string.excl_net_preset_applied, labelEn), Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape    = CircleShape,
                                            color    = if (canApply) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                            enabled  = canApply,
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.PlayArrow, null, tint = if (canApply) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ExLabel(Icons.Default.Palette, stringResource(R.string.excl_color_presets_label), MaterialTheme.colorScheme.primary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { presetExpanded = !presetExpanded }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("R:${redVal.toInt()} G:${greenVal.toInt()} B:${blueVal.toInt()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        }
                        val arrowRotation by animateFloatAsState(if (presetExpanded) 180f else 0f, label = "presetArrow")
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).then(Modifier.graphicsLayer { rotationZ = arrowRotation }))
                    }
                    AnimatedVisibility(visible = presetExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                            Text(stringResource(R.string.excl_builtin_presets), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BUILTIN_PRESETS.forEach { preset ->
                                ExBuiltInPresetItem(preset, isId, isRooted,
                                    onApply = {
                                        scope.launch {
                                            prefManager.setRGB(preset.red, preset.green, preset.blue)
                                            prefManager.setSat(preset.sat)
                                            ExclusiveExecutor.applyColorPreset(preset.red, preset.green, preset.blue, preset.sat, isRooted)
                                            Toast.makeText(context, context.getString(R.string.excl_preset_applied, if (isId) preset.nameId else preset.nameEn), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onSave = {
                                        val u = presets.filter { it.name != (if (isId) preset.nameId else preset.nameEn) } + ColorPreset(if (isId) preset.nameId else preset.nameEn, preset.red, preset.green, preset.blue, preset.sat)
                                        presets = u; ExclusiveExecutor.savePresets(mainPrefs, u)
                                        Toast.makeText(context, context.getString(R.string.excl_preset_saved, if (isId) preset.nameId else preset.nameEn), Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.excl_custom_presets, presets.size), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ExPillButton(stringResource(R.string.excl_save_values), Icons.Default.Save, compact = true) { showAddPreset = true }
                            }
                            if (presets.isNotEmpty()) {
                                presets.forEach { p ->
                                    ExUserPresetItem(p, isRooted, isId,
                                        onApply = {
                                            scope.launch {
                                                prefManager.setRGB(p.red, p.green, p.blue)
                                                prefManager.setSat(p.sat)
                                                ExclusiveExecutor.applyColorPreset(p.red, p.green, p.blue, p.sat, isRooted)
                                                Toast.makeText(context, context.getString(R.string.excl_preset_applied, p.name), Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDelete = { delPreset = p }
                                    )
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.excl_no_custom_presets), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            ExLabel(Icons.Default.Image, stringResource(R.string.excl_banner_label), MaterialTheme.colorScheme.primary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.excl_banner_desc),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp
                    )
                    ExFeedback(Icons.Default.Image, stringResource(R.string.excl_banner_recommendation), MaterialTheme.colorScheme.primary)
                    if (customBannerUri != null) {
                        ExFeedback(Icons.Default.CheckCircle, stringResource(R.string.excl_banner_custom_active), Color(0xFF66BB6A))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExPillButton(stringResource(R.string.action_pick_photo), Icons.Default.Image, compact = true) {
                            bannerLauncher.launch(arrayOf("image/jpeg", "image/png", "image/*"))
                        }
                        if (customBannerUri != null) {
                            ExPillButton(stringResource(R.string.action_reset), Icons.Default.Refresh, compact = true) {
                                mainPrefs.edit().remove(ExclusiveExecutor.KEY_BANNER_URI).apply()
                                customBannerUri = null
                                Toast.makeText(context, context.getString(R.string.excl_banner_reset), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            ExLabel(Icons.Default.AcUnit, stringResource(R.string.freeze_section_label), MaterialTheme.colorScheme.secondary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { freezeExpanded = !freezeExpanded }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.freeze_title),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.freeze_desc),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isRooted) ExBadge("ROOT", Color(0xFF4CAF50))
                            if (!isRooted && isShizuku) ExBadge("SHIZUKU", MaterialTheme.colorScheme.primary)
                            if (!hasAccess) ExBadge(stringResource(R.string.freeze_no_access), Color(0xFFEF5350))
                            val arrowRotation by animateFloatAsState(if (freezeExpanded) 180f else 0f, label = "freezeArrow")
                            Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).then(Modifier.graphicsLayer { rotationZ = arrowRotation }))
                        }
                    }
                    AnimatedVisibility(visible = freezeExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                            if (!hasAccess) {
                                ExFeedback(Icons.Default.Warning, stringResource(R.string.freeze_requires_access), Color(0xFFEF5350))
                            } else {
                                ExTextField(
                                    value = freezePkgInput,
                                    label = stringResource(R.string.freeze_pkg_hint),
                                    onValueChange = {
                                        freezePkgInput = it
                                        freezeResult = null
                                        freezeNotFound = false
                                        if (it.isNotBlank() && it == freezePkgChecked) Unit
                                    }
                                )
                                if (freezePkgChecked.isNotBlank() && freezePkgChecked == freezePkgInput.trim()) {
                                    ExFeedback(
                                        if (freezeIsFrozen) Icons.Default.AcUnit else Icons.Default.CheckCircle,
                                        stringResource(if (freezeIsFrozen) R.string.freeze_status_frozen else R.string.freeze_status_active),
                                        if (freezeIsFrozen) MaterialTheme.colorScheme.primary else Color(0xFF66BB6A)
                                    )
                                }
                                if (freezeNotFound) {
                                    ExFeedback(Icons.Default.Warning, stringResource(R.string.freeze_pkg_not_found), Color(0xFFEF5350))
                                }
                                if (freezeResult == true) {
                                    ExFeedback(
                                        Icons.Default.CheckCircle,
                                        stringResource(if (freezeIsFrozen) R.string.freeze_success_frozen else R.string.freeze_success_unfrozen),
                                        Color(0xFF66BB6A)
                                    )
                                }
                                if (freezeResult == false) {
                                    ExFeedback(Icons.Default.Warning, stringResource(R.string.freeze_failed), Color(0xFFEF5350))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val pkg = freezePkgInput.trim()
                                            if (pkg.isBlank()) return@OutlinedButton
                                            freezeResult = null
                                            freezeNotFound = false
                                            val installed = ExclusiveExecutor.isPackageInstalled(context, pkg)
                                            if (!installed) {
                                                freezeNotFound = true
                                                return@OutlinedButton
                                            }
                                            freezePkgChecked = pkg
                                            freezeIsFrozen = ExclusiveExecutor.isAppFrozen(context, pkg)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.4f))
                                    ) {
                                        Icon(Icons.Default.Search, null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.freeze_btn_check), fontSize = 13.sp)
                                    }
                                    Button(
                                        onClick = {
                                            val pkg = freezePkgInput.trim()
                                            if (pkg.isBlank()) return@Button
                                            if (!ExclusiveExecutor.isPackageInstalled(context, pkg)) {
                                                freezeNotFound = true
                                                return@Button
                                            }
                                            scope.launch {
                                                freezeIsApplying = true
                                                freezeResult = null
                                                val ok = if (freezeIsFrozen) {
                                                    ExclusiveExecutor.unfreezeApp(pkg)
                                                } else {
                                                    ExclusiveExecutor.freezeApp(pkg)
                                                }
                                                if (ok) {
                                                    freezeIsFrozen = !freezeIsFrozen
                                                    freezePkgChecked = pkg
                                                }
                                                freezeResult = ok
                                                freezeIsApplying = false
                                            }
                                        },
                                        enabled = freezePkgInput.isNotBlank() && !freezeIsApplying,
                                        modifier = Modifier.weight(2f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (freezeIsFrozen) Color(0xFF66BB6A) else MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        if (freezeIsApplying) {
                                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                                        } else {
                                            Icon(if (freezeIsFrozen) Icons.Default.PlayArrow else Icons.Default.AcUnit, null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(if (freezeIsFrozen) R.string.freeze_btn_unfreeze else R.string.freeze_btn_freeze),
                                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ExLabel(Icons.Default.Navigation, stringResource(R.string.excl_navbar_label), MaterialTheme.colorScheme.primary)
            ExCard {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (useFloatingNav) "Floating Pill" else "Glow Liquid", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (useFloatingNav) stringResource(R.string.excl_navbar_floating) else stringResource(R.string.excl_navbar_indicator), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = useFloatingNav, onCheckedChange = { useFloatingNav = it; setNavBarStyle(context, it) }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
                }
            }

            ExLabel(Icons.Default.Apps, stringResource(R.string.excl_more_menu_label), MaterialTheme.colorScheme.tertiary)
            ExCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExShortcutButton(
                        icon    = Icons.Default.BatteryChargingFull,
                        title   = if (isId) "Monitor Baterai" else "Battery Monitor",
                        desc    = if (isId) "Charge limit, kesehatan & riwayat" else "Charge limit, health & history",
                        tint    = Color(0xFF66BB6A),
                        onClick = { navController.navigate("battery") }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
                    ExShortcutButton(
                        icon    = Icons.Default.Build,
                        title   = stringResource(R.string.action_open_debug),
                        desc    = stringResource(R.string.excl_debug_desc),
                        tint    = Color(0xFFEF5350),
                        badge   = if (isRooted) "ROOT" else "SHIZUKU",
                        badgeColor = if (isRooted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                        onClick = { navController.navigate("debug_tools") }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
                    ExShortcutButton(
                        icon    = Icons.Default.Videocam,
                        title   = stringResource(R.string.excl_more_screen_record),
                        desc    = if (isId) "Rekam layar perangkat" else "Record device screen",
                        tint    = MaterialTheme.colorScheme.tertiary,
                        onClick = { navController.navigate("screen_record") }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ExCard(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(20.dp)).padding(16.dp)) {
        Column(content = content)
    }
}

@Composable
private fun ExShortcutButton(
    icon: ImageVector, title: String, desc: String, tint: Color,
    badge: String? = null, badgeColor: Color = Color.Unspecified, onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(14.dp),
        color   = MaterialTheme.colorScheme.surfaceContainerHigh,
        border  = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(0.12f))
                    .border(BorderStroke(1.dp, tint.copy(0.3f)), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (badge != null) ExBadge(badge, badgeColor)
                }
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ExLabel(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 2.dp, top = 4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = tint, letterSpacing = 1.2.sp)
    }
}

@Composable
private fun ExPillButton(label: String, icon: ImageVector, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, modifier = if (!compact) Modifier.fillMaxWidth() else Modifier) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 8.dp else 13.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(0.12f), border = BorderStroke(0.8.dp, color.copy(0.4f))) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), letterSpacing = 0.5.sp)
    }
}

@Composable
private fun ExFeedback(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(0.08f))
            .border(BorderStroke(0.5.dp, color.copy(0.3f)), RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun ExTextField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine    = true,
        shape         = RoundedCornerShape(10.dp),
        modifier      = Modifier.fillMaxWidth(),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(0.5f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
            cursorColor          = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor    = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLabelColor  = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun ExConfirmDialog(title: String, body: String, confirmLabel: String, isId: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape            = RoundedCornerShape(20.dp),
        title            = { Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text             = { Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
        confirmButton    = { Button(onClick = onConfirm, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))) { Text(confirmLabel, color = Color.White, fontWeight = FontWeight.Bold) } },
        dismissButton    = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    )
}

@Composable
private fun ExProfileItem(profile: TweakProfile, isId: Boolean, onApply: () -> Unit, onDelete: () -> Unit) {
    val date = remember(profile.createdAt) { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(profile.createdAt)) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onApply,  modifier = Modifier.size(34.dp)) { Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF66BB6A),             modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Delete,    null, tint = Color(0xFFEF5350).copy(0.7f), modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun ExBuiltInPresetItem(preset: BuiltInPreset, isId: Boolean, isRooted: Boolean, onApply: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(preset.tint.copy(0.15f)).border(BorderStroke(1.dp, preset.tint.copy(0.4f)), CircleShape), contentAlignment = Alignment.Center) {
            Icon(preset.icon, null, tint = preset.tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (isId) preset.nameId else preset.nameEn, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("R:${preset.red.toInt()} G:${preset.green.toInt()} B:${preset.blue.toInt()} S:${preset.sat.toInt()}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        if (isRooted) IconButton(onClick = onApply, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.PlayArrow, null, tint = preset.tint, modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onSave, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun ExUserPresetItem(preset: ColorPreset, isRooted: Boolean, isId: Boolean, onApply: () -> Unit, onDelete: () -> Unit) {
    val c = Color(red = (preset.red / 2000f).coerceIn(0f,1f), green = (preset.green / 2000f).coerceIn(0f,1f), blue = (preset.blue / 2000f).coerceIn(0f,1f))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)), CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(preset.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("R:${preset.red.toInt()} G:${preset.green.toInt()} B:${preset.blue.toInt()} S:${preset.sat.toInt()}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        if (isRooted) IconButton(onClick = onApply,  modifier = Modifier.size(34.dp)) { Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF66BB6A),             modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350).copy(0.7f), modifier = Modifier.size(17.dp)) }
    }
}
