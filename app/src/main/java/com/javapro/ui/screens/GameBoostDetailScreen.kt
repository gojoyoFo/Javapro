package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*




import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.utils.PremiumManager
import com.javapro.utils.PreferenceManager
import com.javapro.utils.TweakExecutor
import com.javapro.utils.GameBoostExecutor
import com.javapro.utils.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_BOOST = "GameBoostPrefs"
private const val PREF_VERSION = "pref_version"
private const val CURRENT_PREF_VERSION = 3

private fun resetPrefsIfNeeded(context: Context) {
    val prefs = context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE)
    if (prefs.getInt(PREF_VERSION, 0) < CURRENT_PREF_VERSION) {
        prefs.edit().clear().putInt(PREF_VERSION, CURRENT_PREF_VERSION).apply()
    }
}

private fun saveBoostState(context: Context, pkg: String, active: Boolean) =
    context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE).edit().putBoolean("boost_$pkg", active).apply()
private fun getBoostState(context: Context, pkg: String) =
    context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE).getBoolean("boost_$pkg", false)
private fun savePref(context: Context, key: String, value: Boolean) =
    context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
private fun getPref(context: Context, key: String, default: Boolean) =
    context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE).getBoolean(key, default)
private fun saveStr(context: Context, key: String, value: String) =
    context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE).edit().putString(key, value).apply()
private fun getStr(context: Context, key: String, default: String) =
    context.getSharedPreferences(PREF_BOOST, Context.MODE_PRIVATE).getString(key, default) ?: default

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoostDetailScreen(
    navController: NavController,
    packageName: String,
    lang: String,
    prefManager: PreferenceManager,
    onShowAd: (onGranted: () -> Unit) -> Unit = { it() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val showAdSafe: (((() -> Unit) -> Unit)) = { onGranted ->
        onShowAd {
            onGranted()
        }
    }

    val isRooted = remember { TweakExecutor.checkRoot() }
    val isShizuku = remember { ShizukuManager.isAvailable() }
    val hasAccess = isRooted || isShizuku

    var gameInfo by remember { mutableStateOf<GameInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isBoosting by remember { mutableStateOf(false) }
    var isBoostActive by remember { mutableStateOf(false) }

    var killBgEnabled by remember { mutableStateOf(false) }
    var prioritizeEnabled by remember { mutableStateOf(false) }
    var disableAnimEnabled by remember { mutableStateOf(false) }
    var touchBoostEnabled by remember { mutableStateOf(false) }
    var gameModeEnabled by remember { mutableStateOf(false) }
    var memOptEnabled by remember { mutableStateOf(false) }

    var renderAheadEnabled by remember { mutableStateOf(false) }
    var selectedRenderAhead by remember { mutableStateOf("1") }
    var sustainedPerfEnabled by remember { mutableStateOf(false) }
    var isApplyingPerfExtra by remember { mutableStateOf(false) }
    var perfExtraExpanded by remember { mutableStateOf(false) }

    var thermalEnabled by remember { mutableStateOf(false) }
    var cpuGpuBoostEnabled by remember { mutableStateOf(false) }
    var uclampEnabled by remember { mutableStateOf(false) }
    var ioEnabled by remember { mutableStateOf(false) }
    var vmEnabled by remember { mutableStateOf(false) }
    var schedEnabled by remember { mutableStateOf(false) }
    var netEnabled by remember { mutableStateOf(false) }
    var dozeEnabled by remember { mutableStateOf(false) }
    var lmkEnabled by remember { mutableStateOf(false) }
    var wifiLatencyEnabled by remember { mutableStateOf(false) }
    var rootOptionsExpanded by remember { mutableStateOf(false) }
    var isApplyingRootOpt by remember { mutableStateOf(false) }

    var adrenoBoostLevel by remember { mutableStateOf("0") }
    var gpuIdleTimerEnabled by remember { mutableStateOf(false) }
    var irqAffinityEnabled by remember { mutableStateOf(false) }
    var extraRootExpanded by remember { mutableStateOf(false) }
    var isApplyingExtraRoot by remember { mutableStateOf(false) }

    var selectedScale by remember { mutableStateOf("disable") }
    var selectedFps by remember { mutableStateOf("60") }
    var lockFpsEnabled by remember { mutableStateOf(false) }
    var selectedDsMethod by remember { mutableStateOf("new") }
    var isApplyingDs by remember { mutableStateOf(false) }
    var isApplyingBoostOpt by remember { mutableStateOf(false) }
    var selectedDriver by remember { mutableStateOf("default") }
    var isApplyingDriver by remember { mutableStateOf(false) }



    LaunchedEffect(packageName) {
        resetPrefsIfNeeded(context)
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val icon = pm.getApplicationIcon(packageName)
                val name = pm.getApplicationLabel(appInfo).toString()
                gameInfo = GameInfo(name, packageName, icon)
            } catch (e: Exception) { }
        }
        isBoostActive = getBoostState(context, packageName)
        killBgEnabled = getPref(context, "killbg_$packageName", false)
        prioritizeEnabled = getPref(context, "prioritize_$packageName", false)
        disableAnimEnabled = getPref(context, "anim_$packageName", false)
        touchBoostEnabled = getPref(context, "touch_$packageName", false)
        gameModeEnabled = getPref(context, "gamemode_$packageName", false)
        memOptEnabled = getPref(context, "memopt_$packageName", false)
        renderAheadEnabled = getPref(context, "renderahead_$packageName", false)
        selectedRenderAhead = getStr(context, "renderaheadval_$packageName", "1")
        sustainedPerfEnabled = getPref(context, "sustained_$packageName", false)
        thermalEnabled = getPref(context, "thermal_$packageName", false)
        cpuGpuBoostEnabled = getPref(context, "cpugpu_$packageName", false)
        uclampEnabled = getPref(context, "uclamp_$packageName", false)
        ioEnabled = getPref(context, "io_$packageName", false)
        vmEnabled = getPref(context, "vm_$packageName", false)
        schedEnabled = getPref(context, "sched_$packageName", false)
        netEnabled = getPref(context, "net_$packageName", false)
        dozeEnabled = getPref(context, "doze_$packageName", false)
        lmkEnabled = getPref(context, "lmk_$packageName", false)
        wifiLatencyEnabled = getPref(context, "wifi_$packageName", false)
        adrenoBoostLevel = getStr(context, "adreno_$packageName", "0")
        gpuIdleTimerEnabled = getPref(context, "gpuidle_$packageName", false)
        irqAffinityEnabled = getPref(context, "irqaffinity_$packageName", false)
        selectedScale = getStr(context, "scale_$packageName", "disable")
        selectedFps = getStr(context, "fps_$packageName", "60")
        lockFpsEnabled = getPref(context, "lockfps_$packageName", false)
        selectedDsMethod = getStr(context, "dsmethod_$packageName", "new")
        selectedDriver = getStr(context, "driver_$packageName", "default")
        isLoading = false
    }

    fun launchGame() {
        val ok = GameBoostExecutor.launchGame(context, packageName)
        if (!ok) Toast.makeText(context, context.getString(R.string.gameboost_game_not_found), Toast.LENGTH_SHORT).show()
    }

    suspend fun applyBoost() {
        GameBoostExecutor.applyBoost(
            pkg      = packageName,
            cfg      = buildConfig(),
            isRooted = isRooted
        )
    }

    fun buildConfig() = GameBoostExecutor.BoostConfig(
        killBg        = killBgEnabled,
        prioritize    = prioritizeEnabled,
        disableAnim   = disableAnimEnabled,
        touchBoost    = touchBoostEnabled,
        gameMode      = gameModeEnabled,
        memOpt        = memOptEnabled,
        renderAhead   = renderAheadEnabled,
        renderAheadVal= selectedRenderAhead,
        sustainedPerf = sustainedPerfEnabled,
        thermal       = thermalEnabled,
        cpuGpuBoost   = cpuGpuBoostEnabled,
        uclamp        = uclampEnabled,
        io            = ioEnabled,
        vm            = vmEnabled,
        sched         = schedEnabled,
        net           = netEnabled,
        doze          = dozeEnabled,
        lmk           = lmkEnabled,
        wifiLatency   = wifiLatencyEnabled,
        adrenoBoost   = adrenoBoostLevel,
        gpuIdleTimer  = gpuIdleTimerEnabled,
        irqAffinity   = irqAffinityEnabled,
        scale         = selectedScale,
        fps           = selectedFps,
        lockFps       = lockFpsEnabled,
        dsMethod      = selectedDsMethod,
        driver        = selectedDriver
    )



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(gameInfo?.name ?: packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, null) } },
                actions = {
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            GBHeaderCard(gameInfo, packageName, isRooted, isShizuku, lang)

            if (!hasAccess) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.premium_root_shizuku_required), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        showAdSafe {
                            scope.launch {
                                isBoosting = true
                                withContext(Dispatchers.IO) { if (isBoostActive) stopBoost() else applyBoost() }
                                isBoostActive = !isBoostActive
                                saveBoostState(context, packageName, isBoostActive)
                                isBoosting = false
                                Toast.makeText(context, if (isBoostActive) context.getString(R.string.gameboost_active) else context.getString(R.string.gameboost_stopped), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = hasAccess && !isBoosting,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isBoostActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                ) {
                    if (isBoosting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else {
                        Icon(if (isBoostActive) Icons.Default.Stop else Icons.Default.Bolt, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isBoostActive) "Stop" else "Boost!", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            if (!isBoostActive) {
                                isBoosting = true
                                withContext(Dispatchers.IO) { applyBoost() }
                                isBoostActive = true
                                saveBoostState(context, packageName, true)
                                isBoosting = false
                            }
                            launchGame()
                        }
                    },
                    enabled = hasAccess && !isBoosting,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.gbdetail_play_btn), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
            }

            // ── Cloud Config Banner ──────────────────────────────────────────
            Card(
                onClick = { navController.navigate("cloud_configs/$packageName") },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(0.18f),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.gbdetail_cloud_config_title),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize   = 14.sp,
                            color      = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            stringResource(R.string.gbdetail_cloud_config_desc),
                            fontSize   = 11.sp,
                            color      = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f),
                            lineHeight = 15.sp
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            GBSectionLabel(stringResource(R.string.gbdetail_boost_options), MaterialTheme.colorScheme.primary)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(4.dp)) {
                    GBToggle(stringResource(R.string.gbdetail_kill_bg_apps), stringResource(R.string.gbdetail_kill_bg_apps_desc), killBgEnabled, hasAccess) { killBgEnabled = it; savePref(context, "killbg_$packageName", it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(stringResource(R.string.gbdetail_prioritize_game), stringResource(R.string.gbdetail_prioritize_desc), prioritizeEnabled, hasAccess) { prioritizeEnabled = it; savePref(context, "prioritize_$packageName", it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(stringResource(R.string.gbdetail_reduce_anim), stringResource(R.string.gbdetail_reduce_anim_desc), disableAnimEnabled, hasAccess) { disableAnimEnabled = it; savePref(context, "anim_$packageName", it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(stringResource(R.string.gbdetail_touch_boost), stringResource(R.string.gbdetail_touch_boost_desc), touchBoostEnabled, hasAccess) { touchBoostEnabled = it; savePref(context, "touch_$packageName", it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(stringResource(R.string.gbdetail_android_game_mode), stringResource(R.string.gbdetail_android_game_mode_desc), gameModeEnabled, hasAccess) { gameModeEnabled = it; savePref(context, "gamemode_$packageName", it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(stringResource(R.string.gbdetail_mem_opt), stringResource(R.string.gbdetail_mem_opt_desc), memOptEnabled, hasAccess) { memOptEnabled = it; savePref(context, "memopt_$packageName", it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Button(
                            onClick = {
                                showAdSafe {
                                    scope.launch {
                                        isApplyingBoostOpt = true
                                        withContext(Dispatchers.IO) {
                                            GameBoostExecutor.applyBoostOptions(packageName, buildConfig())
                                        }
                                        isApplyingBoostOpt = false
                                        Toast.makeText(context, context.getString(R.string.gbdetail_boost_applied), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = hasAccess && !isApplyingBoostOpt,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isApplyingBoostOpt) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else {
                                Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.gbdetail_apply_boost), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            GBSectionLabel(stringResource(R.string.gbdetail_perf_graphics), MaterialTheme.colorScheme.tertiary)
            GBPerfExtraSection(
                expanded = perfExtraExpanded,
                onExpandToggle = { perfExtraExpanded = !perfExtraExpanded },
                hasAccess = hasAccess,
                isShizuku = isShizuku,
                isRooted = isRooted,
                renderAheadEnabled = renderAheadEnabled,
                selectedRenderAhead = selectedRenderAhead,
                sustainedPerfEnabled = sustainedPerfEnabled,
                isApplying = isApplyingPerfExtra,
                lang = lang,
                onRenderAheadToggle = { renderAheadEnabled = it; savePref(context, "renderahead_$packageName", it) },
                onRenderAheadSelect = { selectedRenderAhead = it; saveStr(context, "renderaheadval_$packageName", it) },
                onSustainedPerfToggle = { sustainedPerfEnabled = it; savePref(context, "sustained_$packageName", it) },
                onApply = {
                    showAdSafe {
                        scope.launch {
                            isApplyingPerfExtra = true
                            withContext(Dispatchers.IO) {
                                    GameBoostExecutor.applyPerfExtra(packageName, buildConfig())
                                }
                            isApplyingPerfExtra = false
                            Toast.makeText(context, context.getString(R.string.action_applied), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            if (isRooted) {
                GBSectionLabel(stringResource(R.string.gbdetail_root_options_advanced), MaterialTheme.colorScheme.error)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { rootOptionsExpanded = !rootOptionsExpanded }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.gbdetail_root_options_label),
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    if (rootOptionsExpanded) stringResource(R.string.gbdetail_tap_collapse) else stringResource(R.string.gbdetail_tap_expand),
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                if (rootOptionsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)
                            )
                        }
                        AnimatedVisibility(visible = rootOptionsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(Modifier.padding(bottom = 4.dp)) {
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_disable_thermal), stringResource(R.string.gbdetail_disable_thermal_desc), thermalEnabled, true, warningLabel = stringResource(R.string.memory_hot)) { thermalEnabled = it; savePref(context, "thermal_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_max_cpu_gpu), stringResource(R.string.gbdetail_max_cpu_gpu_desc), cpuGpuBoostEnabled, true) { cpuGpuBoostEnabled = it; savePref(context, "cpugpu_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_cpu_uclamp), stringResource(R.string.gbdetail_cpu_uclamp_desc), uclampEnabled, true) { uclampEnabled = it; savePref(context, "uclamp_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_io_scheduler), stringResource(R.string.gbdetail_io_scheduler_desc), ioEnabled, true) { ioEnabled = it; savePref(context, "io_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.memory_vm_tweak), stringResource(R.string.memory_vm_tweak_desc), vmEnabled, true) { vmEnabled = it; savePref(context, "vm_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_sched_tweak), stringResource(R.string.gbdetail_sched_tweak_desc), schedEnabled, true) { schedEnabled = it; savePref(context, "sched_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_aggressive_lmk), stringResource(R.string.gbdetail_aggressive_lmk_desc), lmkEnabled, true) { lmkEnabled = it; savePref(context, "lmk_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_tcp_opt), stringResource(R.string.gbdetail_tcp_opt_desc), netEnabled, true) { netEnabled = it; savePref(context, "net_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_wifi_gaming), stringResource(R.string.gbdetail_wifi_gaming_desc), wifiLatencyEnabled, true) { wifiLatencyEnabled = it; savePref(context, "wifi_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                GBToggle(stringResource(R.string.gbdetail_disable_doze), stringResource(R.string.gbdetail_disable_doze_desc), dozeEnabled, true) { dozeEnabled = it; savePref(context, "doze_$packageName", it) }
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Button(
                                        onClick = {
                                            showAdSafe {
                                                scope.launch {
                                                    isApplyingRootOpt = true
                                                    withContext(Dispatchers.IO) {
                                                        GameBoostExecutor.applyPerfExtra(packageName, buildConfig())
                                                    }
                                                    isApplyingRootOpt = false
                                                    Toast.makeText(context, context.getString(R.string.gbdetail_root_options_applied), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        enabled = !isApplyingRootOpt,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        if (isApplyingRootOpt) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                                        else {
                                            Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.gbdetail_apply_root_options), fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isRooted) {
                GBSectionLabel(stringResource(R.string.gbdetail_root_extra_label), MaterialTheme.colorScheme.error)
                GBExtraRootSection(
                    expanded = extraRootExpanded,
                    onExpandToggle = { extraRootExpanded = !extraRootExpanded },
                    adrenoBoostLevel = adrenoBoostLevel,
                    gpuIdleTimerEnabled = gpuIdleTimerEnabled,
                    irqAffinityEnabled = irqAffinityEnabled,
                    isApplying = isApplyingExtraRoot,
                    lang = lang,
                    onAdrenoBoostSelect = { adrenoBoostLevel = it; saveStr(context, "adreno_$packageName", it) },
                    onGpuIdleTimer = { gpuIdleTimerEnabled = it; savePref(context, "gpuidle_$packageName", it) },
                    onIrqAffinity = { irqAffinityEnabled = it; savePref(context, "irqaffinity_$packageName", it) },
                    onApply = {
                        showAdSafe {
                            scope.launch {
                                isApplyingExtraRoot = true
                                withContext(Dispatchers.IO) {
                                    GameBoostExecutor.applyRootExtra(packageName, buildConfig())
                                }
                                isApplyingExtraRoot = false
                                Toast.makeText(context, context.getString(R.string.gbdetail_root_extra_applied), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            GBSectionLabel(stringResource(R.string.gbdetail_graphics_driver), MaterialTheme.colorScheme.secondary)
            GBGraphicsDriverSection(
                hasAccess = hasAccess,
                isRooted = isRooted,
                selectedDriver = selectedDriver,
                isApplying = isApplyingDriver,
                lang = lang,
                onDriver = { selectedDriver = it; saveStr(context, "driver_$packageName", it) },
                onShowAd = showAdSafe,
                onApply = {
                    scope.launch {
                        isApplyingDriver = true
                        withContext(Dispatchers.IO) {
                            GameBoostExecutor.applyDriver(packageName, selectedDriver)
                        }
                        isApplyingDriver = false
                        Toast.makeText(context, context.getString(R.string.gbdetail_driver_applied), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            GBSectionLabel("Downscale & FPS", MaterialTheme.colorScheme.tertiary)
            GBDownscaleSection(
                hasAccess = hasAccess, isRooted = isRooted, isShizuku = isShizuku,
                selectedScale = selectedScale, selectedFps = selectedFps, lockFpsEnabled = lockFpsEnabled,
                selectedDsMethod = selectedDsMethod,
                isApplying = isApplyingDs, lang = lang,
                onScale = { selectedScale = it; saveStr(context, "scale_$packageName", it) },
                onFps = { selectedFps = it; saveStr(context, "fps_$packageName", it) },
                onLockFps = { lockFpsEnabled = it; savePref(context, "lockfps_$packageName", it) },
                onDsMethod = { selectedDsMethod = it; saveStr(context, "dsmethod_$packageName", it) },
                onShowAd = showAdSafe,
                onApply = {
                    scope.launch {
                        isApplyingDs = true
                        withContext(Dispatchers.IO) {
                            GameBoostExecutor.applyDownscale(packageName, buildConfig())
                        }
                        isApplyingDs = false
                        Toast.makeText(context, context.getString(R.string.action_applied), Toast.LENGTH_SHORT).show()
                    }
                },
                onReset = {
                    scope.launch {
                        isApplyingDs = true
                        withContext(Dispatchers.IO) {
                            GameBoostExecutor.resetDownscale(packageName)
                        }
                        selectedScale = "disable"; saveStr(context, "scale_$packageName", "disable")
                        isApplyingDs = false
                        Toast.makeText(context, context.getString(R.string.action_reset), Toast.LENGTH_SHORT).show()
                    }
                }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GBHeaderCard(gameInfo: GameInfo?, packageName: String, isRooted: Boolean, isShizuku: Boolean, lang: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(packageName) { try { gameInfo?.icon?.toBitmap(128, 128)?.asImageBitmap() } catch (e: Exception) { null } }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(68.dp).clip(RoundedCornerShape(14.dp)))
            } else {
                Box(Modifier.size(68.dp).background(MaterialTheme.colorScheme.primary.copy(0.2f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SportsEsports, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(gameInfo?.name ?: packageName, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(packageName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isRooted) {
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFF1B5E20).copy(0.2f), border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(0.5f))) {
                            Text("Root", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    if (isShizuku) {
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFF0D47A1).copy(0.2f), border = BorderStroke(1.dp, Color(0xFF1565C0).copy(0.5f))) {
                            Text("Shizuku", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    if (!isRooted && !isShizuku) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                            Text(stringResource(R.string.status_no_access), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GBSectionLabel(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
}

@Composable
private fun GBToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, warningLabel: String? = null, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                if (warningLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(warningLabel, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
            }
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun GBPerfExtraSection(
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    hasAccess: Boolean,
    isShizuku: Boolean,
    isRooted: Boolean,
    renderAheadEnabled: Boolean,
    selectedRenderAhead: String,
    sustainedPerfEnabled: Boolean,
    isApplying: Boolean,
    lang: String,
    onRenderAheadToggle: (Boolean) -> Unit,
    onRenderAheadSelect: (String) -> Unit,
    onSustainedPerfToggle: (Boolean) -> Unit,
    onApply: () -> Unit
) {
    val renderAheadOptions = listOf("1", "2", "3", "4")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
    ) {
        Column {
            // Header dropdown — sama persis style Root Options
            Row(
                Modifier.fillMaxWidth().clickable { onExpandToggle() }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.gbdetail_perf_graphics),
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        if (expanded) stringResource(R.string.gbdetail_tap_collapse) else stringResource(R.string.gbdetail_tap_expand),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    GBToggle(
                        stringResource(R.string.gbdetail_render_ahead),
                        stringResource(R.string.gbdetail_render_ahead_desc),
                        renderAheadEnabled, hasAccess
                    ) { onRenderAheadToggle(it) }

                    AnimatedVisibility(visible = renderAheadEnabled, enter = expandVertically(), exit = shrinkVertically()) {
                        Column {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.fps_frame_count), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                renderAheadOptions.forEach { value ->
                                    val isSel = selectedRenderAhead == value
                                    Surface(
                                        modifier = Modifier.size(36.dp).clickable(enabled = hasAccess) { onRenderAheadSelect(value) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(0.25f))
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(value, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.gbdetail_sustained_perf), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                if (!isRooted && isShizuku) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF0D47A1).copy(0.15f), border = BorderStroke(0.8.dp, Color(0xFF1565C0).copy(0.4f))) {
                                        Text("Shizuku", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.gbdetail_sustained_perf_desc),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = sustainedPerfEnabled, onCheckedChange = onSustainedPerfToggle, enabled = hasAccess)
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Button(
                            onClick = onApply,
                            enabled = hasAccess && !isApplying,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            if (isApplying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiary)
                            else {
                                Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.gbdetail_apply_graphics_perf), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GBExtraRootSection(
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    adrenoBoostLevel: String,
    gpuIdleTimerEnabled: Boolean,
    irqAffinityEnabled: Boolean,
    isApplying: Boolean,
    lang: String,
    onAdrenoBoostSelect: (String) -> Unit,
    onGpuIdleTimer: (Boolean) -> Unit,
    onIrqAffinity: (Boolean) -> Unit,
    onApply: () -> Unit
) {
    val adrenoLevels = listOf("0" to "Off", "1" to "Low", "2" to "Med", "3" to "High")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onExpandToggle() }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.gbdetail_advanced_tweaks),
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (expanded) stringResource(R.string.gbdetail_tap_collapse) else stringResource(R.string.gbdetail_tap_expand),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.gbdetail_adreno_boost), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(
                            stringResource(R.string.gbdetail_adreno_boost_desc),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            adrenoLevels.forEach { (value, label) ->
                                val isSel = adrenoBoostLevel == value
                                Surface(
                                    modifier = Modifier.weight(1f).clickable { onAdrenoBoostSelect(value) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(0.25f))
                                ) {
                                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(label, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(
                        stringResource(R.string.gbdetail_aggressive_gpu_idle),
                        stringResource(R.string.gbdetail_aggressive_gpu_idle_desc),
                        gpuIdleTimerEnabled, true
                    ) { onGpuIdleTimer(it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    GBToggle(
                        stringResource(R.string.gbdetail_irq_affinity),
                        stringResource(R.string.gbdetail_irq_affinity_desc),
                        irqAffinityEnabled, true
                    ) { onIrqAffinity(it) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Button(
                            onClick = onApply,
                            enabled = !isApplying,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (isApplying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                            else {
                                Icon(Icons.Default.Bolt, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.gbdetail_apply_root_extra), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GBGraphicsDriverSection(
    hasAccess: Boolean, isRooted: Boolean,
    selectedDriver: String,
    isApplying: Boolean, lang: String,
    onDriver: (String) -> Unit,
    onApply: () -> Unit,
    onShowAd: (onGranted: () -> Unit) -> Unit = { it() }
) {
    val driverOptions = listOf("skia_vulkan" to "Skia Vulkan", "skia_gl" to "Skia GL", "opengl" to "OpenGL", "default" to stringResource(R.string.status_default))
    val driverDescriptions = mapOf(
        "skia_vulkan" to stringResource(R.string.gbdetail_max_cpu_gpu_desc),
        "skia_gl" to stringResource(R.string.gbdetail_driver_default_desc),
        "opengl" to stringResource(R.string.gbdetail_driver_legacy_desc),
        "default" to stringResource(R.string.status_default)
    )
    val driverIcons = mapOf("skia_vulkan" to Icons.Default.Bolt, "skia_gl" to Icons.Default.Speed, "opengl" to Icons.Default.Memory, "default" to Icons.Default.Settings)
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = driverOptions.find { it.first == selectedDriver }?.second ?: "Default"
    val selectedDesc = driverDescriptions[selectedDriver] ?: ""
    val selectedIcon = driverIcons[selectedDriver] ?: Icons.Default.Settings

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.gbdetail_select_driver), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(stringResource(R.string.gbdetail_gpu_renderer_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (hasAccess) expanded = !expanded }) {
                Surface(
                    modifier = Modifier.fillMaxWidth().menuAnchor().clickable(enabled = hasAccess) { expanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(selectedIcon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selectedLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(selectedDesc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(0.75f))
                        }
                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    driverOptions.forEach { (value, label) ->
                        val icon = driverIcons[value] ?: Icons.Default.Settings
                        val desc = driverDescriptions[value] ?: ""
                        val isSel = selectedDriver == value
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, modifier = Modifier.size(18.dp), tint = if (isSel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                                        Text(desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            onClick = { onDriver(value); expanded = false },
                            trailingIcon = { if (isSel) Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary) }
                        )
                    }
                }
            }
            Button(
                onClick = { onShowAd { onApply() } },
                enabled = hasAccess && !isApplying,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                if (isApplying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                else {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.gbdetail_apply_driver), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun GBDownscaleSection(
    hasAccess: Boolean, isRooted: Boolean, isShizuku: Boolean,
    selectedScale: String, selectedFps: String, lockFpsEnabled: Boolean,
    selectedDsMethod: String,
    isApplying: Boolean, lang: String,
    onScale: (String) -> Unit, onFps: (String) -> Unit, onLockFps: (Boolean) -> Unit,
    onDsMethod: (String) -> Unit,
    onApply: () -> Unit, onReset: () -> Unit, onShowAd: (onGranted: () -> Unit) -> Unit = { it() }
) {
    val scaleOptions = listOf("0.3" to "0.3x", "0.4" to "0.4x", "0.5" to "0.5x", "0.6" to "0.6x", "0.7" to "0.7x", "0.75" to "0.75x", "0.8" to "0.8x", "0.9" to "0.9x", "1.0" to "1.0x", "disable" to "Off")
    val fpsOptions = listOf("30", "60", "90", "120", "144")
    val fpsAccessible = isRooted || isShizuku
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.premium_execution_method), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val methods = listOf("new" to stringResource(R.string.status_new_android15), "legacy" to stringResource(R.string.status_legacy))
                    methods.forEach { (value, label) ->
                        val isSel = selectedDsMethod == value
                        Surface(
                            modifier = Modifier.weight(1f).clickable(enabled = hasAccess) { onDsMethod(value) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSel) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(0.2f))
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                if (isSel) {
                                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(label, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.gbdetail_resolution_scale), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                scaleOptions.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (value, label) ->
                            val isSel = selectedScale == value
                            Surface(
                                modifier = Modifier.weight(1f).clickable(enabled = hasAccess) { onScale(value) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(0.25f))
                            ) {
                                Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FPS Target", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (!isRooted && isShizuku) {
                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF0D47A1).copy(0.15f), border = BorderStroke(1.dp, Color(0xFF1565C0).copy(0.4f))) {
                        Text("Shizuku", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fpsOptions.forEach { fps ->
                    val isSel = selectedFps == fps
                    Surface(
                        modifier = Modifier.weight(1f).clickable(enabled = fpsAccessible) { onFps(fps) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(0.25f))
                    ) {
                        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(fps, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal, color = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gbdetail_lock_fps), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(stringResource(R.string.gbdetail_lock_fps_desc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = lockFpsEnabled, onCheckedChange = onLockFps, enabled = fpsAccessible)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReset, enabled = hasAccess && !isApplying, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.action_reset), fontSize = 13.sp)
                }
                Button(
                    onClick = { onShowAd { onApply() } },
                    enabled = hasAccess && !isApplying,
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isApplying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.action_apply), fontSize = 13.sp)
                }
            }
        }
    }
}
