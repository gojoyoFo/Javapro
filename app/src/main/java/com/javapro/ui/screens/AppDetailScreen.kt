package com.javapro.ui.screens
import com.javapro.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*





import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.ads.AdManager
import com.javapro.utils.AppInfo
import com.javapro.utils.AppProfileManager
import com.javapro.utils.ShizukuManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    navController : NavController,
    packageName   : String,
    lang          : String,
    onShowAd      : (slot: String, onGranted: () -> Unit) -> Unit = { _, granted -> granted() }
) {
    val context  = LocalContext.current
    val activity = run {
        var ctx = context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        ctx as? Activity
    }
    val scope    = rememberCoroutineScope()

    fun guardedApply(
        slot      : String = AdManager.SLOT_APPPROFILE,
        onGranted : () -> Unit
    ) {
        val act = activity ?: return
        AdManager.showInterstitialIfAllowed(act, slot, onGranted)
    }

    var isRooted             by remember { mutableStateOf(false) }
    var isShizukuAvailable   by remember { mutableStateOf(false) }
    var appInfo              by remember { mutableStateOf<AppInfo?>(null) }
    var isLoading            by remember { mutableStateOf(true) }

    var selectedDriver       by remember { mutableStateOf("default") }
    var selectedProfile      by remember { mutableStateOf("balance") }
    var preloadEnabled       by remember { mutableStateOf(false) }
    var smartCacheEnabled    by remember { mutableStateOf(false) }
    var touchResponsiveEnabled by remember { mutableStateOf(false) }
    var reduceLoggingEnabled by remember { mutableStateOf(false) }
    var notifLimiterEnabled  by remember { mutableStateOf(false) }
    var dataSaverEnabled     by remember { mutableStateOf(false) }
    var batteryOptEnabled    by remember { mutableStateOf(false) }
    var isApplyingAdvanced   by remember { mutableStateOf(false) }
    var advancedApplied      by remember { mutableStateOf(false) }

    var selectedDexOpt       by remember { mutableStateOf("speed-profile") }
    var isApplyingDexOpt     by remember { mutableStateOf(false) }

    var selectedBucket       by remember { mutableStateOf("active") }
    var isApplyingBucket     by remember { mutableStateOf(false) }

    var selectedCpuPriority  by remember { mutableStateOf("normal") }
    var isApplyingCpuPriority by remember { mutableStateOf(false) }

    var showForceStopDialog  by remember { mutableStateOf(false) }
    var showAppOpsDialog     by remember { mutableStateOf(false) }

    val featuresEnabled = isRooted || isShizukuAvailable
    val appOpsPrefs     = remember { context.getSharedPreferences("AppOpsPrefs", Context.MODE_PRIVATE) }

    LaunchedEffect(packageName) {
        isLoading = true
        withContext(Dispatchers.IO) {
            isRooted           = TweakExecutor.checkRoot()
            isShizukuAvailable = ShizukuManager.isAvailable()
            try {
                val pm              = context.packageManager
                val pi              = pm.getPackageInfo(packageName, 0)
                val icon            = pm.getApplicationIcon(packageName)
                val applicationInfo = pi.applicationInfo
                    ?: throw android.content.pm.PackageManager.NameNotFoundException("ApplicationInfo is null")
                val name = pm.getApplicationLabel(applicationInfo).toString()
                appInfo        = AppInfo(name = name, packageName = packageName, icon = icon, profile = AppProfileManager.getAppProfile(context, packageName))
                selectedProfile      = AppProfileManager.getAppProfile(context, packageName)
                selectedDriver       = AppProfileManager.getAppDriver(context, packageName)
                preloadEnabled       = AppProfileManager.getPreload(context, packageName)
                smartCacheEnabled    = AppProfileManager.getSmartCache(context, packageName)
                touchResponsiveEnabled = AppProfileManager.getTouchResponsive(context, packageName)
                reduceLoggingEnabled = AppProfileManager.getReduceLogging(context, packageName)
                notifLimiterEnabled  = AppProfileManager.getNotifLimiter(context, packageName)
                dataSaverEnabled     = AppProfileManager.getDataSaver(context, packageName)
                batteryOptEnabled    = AppProfileManager.getBatteryOptimize(context, packageName)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
        isLoading = false
    }

    if (showForceStopDialog) {
        AlertDialog(
            onDismissRequest = { showForceStopDialog = false },
            icon    = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp)) },
            title   = { Text(stringResource(R.string.appdetail_force_stop_title), fontWeight = FontWeight.Bold) },
            text    = { Text(stringResource(R.string.appdetail_force_stop_body), fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showForceStopDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) { AppProfileManager.forceStopApp(packageName) }
                            Toast.makeText(context, context.getString(R.string.appdetail_app_stopped), Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape  = RoundedCornerShape(10.dp)
                ) { Text(stringResource(R.string.appdetail_force_stop_btn), color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showForceStopDialog = false }, shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showAppOpsDialog) {
        ADAppOpsDialog(
            packageName = packageName,
            lang        = lang,
            onDismiss   = { showAppOpsDialog = false },
            onSetOp     = { op, allow ->
                appOpsPrefs.edit().putBoolean("${packageName}_$op", allow).apply()
                AppProfileManager.setAppOp(context, packageName, op, allow)
            },
            getOpState  = { op -> appOpsPrefs.getBoolean("${packageName}_$op", true) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(appInfo?.name ?: packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, null) } },
                colors         = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (appInfo == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("App not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val app = appInfo!!
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                ADHeaderSection(app)

                if (!featuresEnabled) ADAccessWarningBanner(lang)
                else ADAccessModeBadge(isRooted, isShizukuAvailable)

                ADSectionLabel("Graphics Driver", MaterialTheme.colorScheme.primary)
                ADDriverSection(selected = selectedDriver, enabled = featuresEnabled, onSelect = { driver ->
                    selectedDriver = driver
                    AppProfileManager.setAppDriver(context, packageName, driver)
                    Toast.makeText(context, "Driver: $driver", Toast.LENGTH_SHORT).show()
                })

                ADSectionLabel(stringResource(R.string.appdetail_perf_profile), MaterialTheme.colorScheme.secondary)
                ADProfileSection(selected = selectedProfile, lang = lang, enabled = featuresEnabled, onSelect = { profile ->
                    selectedProfile = profile
                    AppProfileManager.setAppProfile(context, packageName, profile)
                })

                ADSectionLabel(stringResource(R.string.appdetail_quick_actions), MaterialTheme.colorScheme.tertiary)
                ADQuickActionsSection(
                    enabled          = featuresEnabled,
                    lang             = lang,
                    onClearCache     = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AppProfileManager.clearCacheApp(packageName) }
                            Toast.makeText(context, context.getString(R.string.appdetail_cache_cleared), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTrimMemory     = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AppProfileManager.trimMemory(packageName) }
                            Toast.makeText(context, context.getString(R.string.appdetail_memory_trimmed), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onForceStop      = { showForceStopDialog = true }
                )

                ADSectionLabel("App Ops", MaterialTheme.colorScheme.tertiary)
                ADAppOpsSection(enabled = featuresEnabled, onClick = { showAppOpsDialog = true })

                ADSectionLabel(stringResource(R.string.appdetail_advanced_optimization), MaterialTheme.colorScheme.primary)
                ADAdvancedToggleSection(
                    preloadEnabled         = preloadEnabled,
                    smartCacheEnabled      = smartCacheEnabled,
                    touchResponsiveEnabled = touchResponsiveEnabled,
                    reduceLoggingEnabled   = reduceLoggingEnabled,
                    notifLimiterEnabled    = notifLimiterEnabled,
                    dataSaverEnabled       = dataSaverEnabled,
                    batteryOptEnabled      = batteryOptEnabled,
                    enabled                = featuresEnabled,
                    isApplying             = isApplyingAdvanced,
                    applied                = advancedApplied,
                    lang                   = lang,
                    onPreloadToggle        = { preloadEnabled       = it; AppProfileManager.setPreload(context, packageName, it);       advancedApplied = false },
                    onSmartCacheToggle     = { smartCacheEnabled    = it; AppProfileManager.setSmartCache(context, packageName, it);    advancedApplied = false },
                    onTouchToggle          = { touchResponsiveEnabled = it; AppProfileManager.setTouchResponsive(context, packageName, it); advancedApplied = false },
                    onReduceLoggingToggle  = { reduceLoggingEnabled = it; AppProfileManager.setReduceLogging(context, packageName, it); advancedApplied = false },
                    onNotifLimiterToggle   = { notifLimiterEnabled  = it; AppProfileManager.setNotifLimiter(context, packageName, it);  advancedApplied = false },
                    onDataSaverToggle      = { dataSaverEnabled     = it; AppProfileManager.setDataSaver(context, packageName, it);     advancedApplied = false },
                    onBatteryOptToggle     = { batteryOptEnabled    = it; AppProfileManager.setBatteryOptimize(context, packageName, it); advancedApplied = false },
                    onApply                = {
                        guardedApply {
                            scope.launch {
                                isApplyingAdvanced = true
                                advancedApplied    = false
                                withContext(Dispatchers.IO) {
                                    AppProfileManager.applyAdvancedTweaks(context, packageName, isRooted)
                                }
                                isApplyingAdvanced = false
                                advancedApplied    = true
                                Toast.makeText(context, context.getString(R.string.action_applied_dot), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )

                ADSectionLabel("DexOpt Compiler", MaterialTheme.colorScheme.secondary)
                ADDexOptSection(
                    selected   = selectedDexOpt,
                    isApplying = isApplyingDexOpt,
                    enabled    = featuresEnabled,
                    lang       = lang,
                    onSelect   = { selectedDexOpt = it },
                    onApply    = {
                        guardedApply {
                            scope.launch {
                                isApplyingDexOpt = true
                                withContext(Dispatchers.IO) { AppProfileManager.compileDexOpt(packageName, selectedDexOpt) }
                                isApplyingDexOpt = false
                                Toast.makeText(context, context.getString(R.string.appdetail_dexopt_done), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )

                ADSectionLabel(stringResource(R.string.appdetail_standby_bucket), MaterialTheme.colorScheme.tertiary)
                ADStandbyBucketSection(
                    selected   = selectedBucket,
                    isApplying = isApplyingBucket,
                    enabled    = featuresEnabled,
                    lang       = lang,
                    onSelect   = { selectedBucket = it },
                    onApply    = {
                        guardedApply {
                            scope.launch {
                                isApplyingBucket = true
                                withContext(Dispatchers.IO) { AppProfileManager.applyStandbyBucket(packageName, selectedBucket) }
                                isApplyingBucket = false
                                Toast.makeText(context, context.getString(R.string.appdetail_bucket_applied), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )

                if (isRooted) {
                    ADSectionLabel(stringResource(R.string.appdetail_cpu_priority), MaterialTheme.colorScheme.error)
                    ADCpuPrioritySection(
                        selected   = selectedCpuPriority,
                        isApplying = isApplyingCpuPriority,
                        lang       = lang,
                        onSelect   = { selectedCpuPriority = it },
                        onApply    = {
                            guardedApply {
                                scope.launch {
                                    isApplyingCpuPriority = true
                                    withContext(Dispatchers.IO) { AppProfileManager.applyCpuPriority(packageName, selectedCpuPriority) }
                                    isApplyingCpuPriority = false
                                    Toast.makeText(context, context.getString(R.string.appdetail_priority_applied), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ADHeaderSection(app: AppInfo) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val iconBitmap = remember(app.packageName) { try { app.icon.toBitmap(128, 128).asImageBitmap() } catch (e: Exception) { null } }
            if (iconBitmap != null) {
                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
            } else {
                Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Text(app.name.take(1).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp))
                Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f), maxLines = 2)
            }
        }
    }
}

@Composable
private fun ADAccessWarningBanner(lang: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.appdetail_no_root_shizuku), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun ADAccessModeBadge(isRooted: Boolean, isShizuku: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isRooted) {
            Surface(shape = RoundedCornerShape(50), color = Color(0xFF1B5E20).copy(alpha = 0.15f), border = BorderStroke(1.dp, Color(0xFF1B5E20).copy(alpha = 0.5f))) {
                Text("Root Active", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        if (isShizuku) {
            Surface(shape = RoundedCornerShape(50), color = Color(0xFF0D47A1).copy(alpha = 0.15f), border = BorderStroke(1.dp, Color(0xFF0D47A1).copy(alpha = 0.5f))) {
                Text("Shizuku Active", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun ADSectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
}

@Composable
private fun ADDriverSection(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    data class DriverItem(val key: String, val label: String, val desc: String, val isBeta: Boolean = false)
    val drivers = listOf(
        DriverItem("default",    "Default",          "System default driver"),
        DriverItem("game",       "Game Driver",      "Optimized for gaming & high performance"),
        DriverItem("angle",      "ANGLE Driver",     "OpenGL ES via Vulkan translation layer"),
        DriverItem("system",     "System Graphics",  "Stable firmware driver"),
        DriverItem("prerelease", "Pre-release Driver","Experimental, may be unstable", true)
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(8.dp)) {
            drivers.forEach { driver ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onSelect(driver.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == driver.key, onClick = null, enabled = enabled)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(driver.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(driver.desc,  fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    if (driver.isBeta) {
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.errorContainer) {
                            Text("BETA", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ADProfileSection(selected: String, lang: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val profiles = listOf(
        Triple("balance",     stringResource(R.string.appdetail_perf_balance),     MaterialTheme.colorScheme.tertiary),
        Triple("performance", stringResource(R.string.appdetail_perf_performance), MaterialTheme.colorScheme.error),
        Triple("powersave",   stringResource(R.string.appdetail_perf_powersave),  MaterialTheme.colorScheme.secondary)
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { (key, label, color) ->
                val isSelected = selected == key
                Surface(
                    modifier = Modifier.weight(1f).clickable(enabled = enabled) { onSelect(key) },
                    shape    = RoundedCornerShape(12.dp),
                    color    = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border   = BorderStroke(1.5.dp, if (isSelected) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ADQuickActionsSection(
    enabled      : Boolean,
    lang         : String,
    onClearCache : () -> Unit,
    onTrimMemory : () -> Unit,
    onForceStop  : () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ADQuickActionButton(
                icon    = Icons.Default.CleaningServices,
                label   = "Clear Cache",
                color   = MaterialTheme.colorScheme.primary,
                enabled = enabled,
                onClick = onClearCache,
                modifier = Modifier.weight(1f)
            )
            ADQuickActionButton(
                icon    = Icons.Default.Memory,
                label   = "Trim RAM",
                color   = MaterialTheme.colorScheme.secondary,
                enabled = enabled,
                onClick = onTrimMemory,
                modifier = Modifier.weight(1f)
            )
            ADQuickActionButton(
                icon    = Icons.Default.Stop,
                label   = "Force Stop",
                color   = MaterialTheme.colorScheme.error,
                enabled = enabled,
                onClick = onForceStop,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ADQuickActionButton(
    icon     : ImageVector,
    label    : String,
    color    : Color,
    enabled  : Boolean,
    onClick  : () -> Unit,
    modifier : Modifier = Modifier
) {
    Surface(
        onClick  = onClick,
        enabled  = enabled,
        modifier = modifier,
        shape    = RoundedCornerShape(12.dp),
        color    = color.copy(alpha = if (enabled) 0.12f else 0.05f),
        border   = BorderStroke(1.dp, color.copy(alpha = if (enabled) 0.4f else 0.15f))
    ) {
        Column(
            modifier            = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = if (enabled) color else color.copy(0.35f), modifier = Modifier.size(22.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) color else color.copy(0.35f))
        }
    }
}

@Composable
private fun ADAppOpsSection(enabled: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("App Ops", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("Manage app permissions & operations", fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun ADAdvancedToggleSection(
    preloadEnabled         : Boolean,
    smartCacheEnabled      : Boolean,
    touchResponsiveEnabled : Boolean,
    reduceLoggingEnabled   : Boolean,
    notifLimiterEnabled    : Boolean,
    dataSaverEnabled       : Boolean,
    batteryOptEnabled      : Boolean,
    enabled                : Boolean,
    isApplying             : Boolean,
    applied                : Boolean,
    lang                   : String,
    onPreloadToggle        : (Boolean) -> Unit,
    onSmartCacheToggle     : (Boolean) -> Unit,
    onTouchToggle          : (Boolean) -> Unit,
    onReduceLoggingToggle  : (Boolean) -> Unit,
    onNotifLimiterToggle   : (Boolean) -> Unit,
    onDataSaverToggle      : (Boolean) -> Unit,
    onBatteryOptToggle     : (Boolean) -> Unit,
    onApply                : () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(4.dp)) {
            ADToggleItem("Preload",                          stringResource(R.string.appdetail_compile_app_memory),                                                                         preloadEnabled,         enabled, onPreloadToggle)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ADToggleItem("Smart Cache Cleaner",              stringResource(R.string.appdetail_clean_cache_on_apply),                                                                          smartCacheEnabled,      enabled, onSmartCacheToggle)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ADToggleItem(stringResource(R.string.appdetail_touch_responsive_title),                                  stringResource(R.string.appdetail_touch_responsive_desc), touchResponsiveEnabled, enabled, onTouchToggle)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ADToggleItem(stringResource(R.string.appdetail_reduce_logging_title),                                    stringResource(R.string.appdetail_reduce_logging_desc),                                    reduceLoggingEnabled,   enabled, onReduceLoggingToggle)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ADToggleItem(stringResource(R.string.appdetail_notif_limiter_title),                            stringResource(R.string.appdetail_notif_limiter_desc),           notifLimiterEnabled,    enabled, onNotifLimiterToggle)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ADToggleItem(stringResource(R.string.appdetail_data_saver_title),                       stringResource(R.string.appdetail_data_saver_desc),                        dataSaverEnabled,       enabled, onDataSaverToggle)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ADToggleItem(stringResource(R.string.appdetail_battery_opt_title),                           stringResource(R.string.appdetail_battery_opt_desc), batteryOptEnabled,      enabled, onBatteryOptToggle)

            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (applied) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.appdetail_success_applied), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Button(
                    onClick  = onApply,
                    enabled  = enabled && !isApplying,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.status_applying))
                    } else {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_apply_all), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ADToggleItem(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title,    fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ADDexOptSection(
    selected   : String,
    isApplying : Boolean,
    enabled    : Boolean,
    lang       : String,
    onSelect   : (String) -> Unit,
    onApply    : () -> Unit
) {
    data class DexItem(val key: String, val label: String, val desc: String)
    val options = listOf(
        DexItem("speed-profile", "Speed Profile",      stringResource(R.string.appdetail_dex_speed_profile_desc)),
        DexItem("speed",         "Speed",              stringResource(R.string.appdetail_dex_speed_desc)),
        DexItem("quicken",       "Quicken",            stringResource(R.string.appdetail_dex_quicken_desc)),
        DexItem("verify",        "Verify Only",        stringResource(R.string.appdetail_dex_verify_desc))
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { opt ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onSelect(opt.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == opt.key, onClick = null, enabled = enabled)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(opt.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(opt.desc,  fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Button(
                    onClick  = onApply,
                    enabled  = enabled && !isApplying,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.status_compiling))
                    } else {
                        Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.appdetail_run_dexopt), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ADStandbyBucketSection(
    selected   : String,
    isApplying : Boolean,
    enabled    : Boolean,
    lang       : String,
    onSelect   : (String) -> Unit,
    onApply    : () -> Unit
) {
    data class BucketItem(val key: String, val label: String, val desc: String, val color: Color)
    val buckets = listOf(
        BucketItem("active",      "Active",      stringResource(R.string.appdetail_bucket_active_desc),          Color(0xFF66BB6A)),
        BucketItem("frequent",    "Frequent",    stringResource(R.string.appdetail_bucket_frequent_desc),                        Color(0xFF42A5F5)),
        BucketItem("working_set", "Working Set", stringResource(R.string.appdetail_bucket_working_desc),                  Color(0xFFFFCA28)),
        BucketItem("rare",        "Rare",        stringResource(R.string.appdetail_bucket_rare_desc), Color(0xFFEF5350))
    )
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            buckets.forEach { bucket ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onSelect(bucket.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == bucket.key, onClick = null, enabled = enabled)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(bucket.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (selected == bucket.key && enabled) bucket.color else MaterialTheme.colorScheme.onSurface)
                        Text(bucket.desc,  fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    if (selected == bucket.key) {
                        Surface(shape = RoundedCornerShape(50), color = bucket.color.copy(0.15f), border = BorderStroke(0.8.dp, bucket.color.copy(0.4f))) {
                            Text("Selected", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = bucket.color, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Button(
                    onClick  = onApply,
                    enabled  = enabled && !isApplying,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.status_applying))
                    } else {
                        Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.appdetail_apply_bucket), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ADCpuPrioritySection(
    selected   : String,
    isApplying : Boolean,
    lang       : String,
    onSelect   : (String) -> Unit,
    onApply    : () -> Unit
) {
    data class PriorityItem(val key: String, val label: String, val desc: String, val color: Color, val badge: String)
    val priorities = listOf(
        PriorityItem("normal",   stringResource(R.string.appdetail_priority_normal_title),   stringResource(R.string.appdetail_priority_normal_desc),         MaterialTheme.colorScheme.secondary, "nice 0"),
        PriorityItem("high",     stringResource(R.string.appdetail_priority_high_title),     stringResource(R.string.appdetail_priority_high_desc),              Color(0xFFFFCA28),                   "nice -10"),
        PriorityItem("realtime", stringResource(R.string.appdetail_priority_realtime_title),stringResource(R.string.appdetail_priority_realtime_desc), Color(0xFFEF5350),                   "nice -20")
    )

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer.copy(0.5f)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.appdetail_priority_root_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }

    Spacer(Modifier.height(6.dp))

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            priorities.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(p.key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == p.key, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (selected == p.key) p.color else MaterialTheme.colorScheme.onSurface)
                        Text(p.desc,  fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Surface(shape = RoundedCornerShape(4.dp), color = p.color.copy(0.15f), border = BorderStroke(0.8.dp, p.color.copy(0.4f))) {
                        Text(p.badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = p.color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Button(
                    onClick  = onApply,
                    enabled  = !isApplying,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.status_applying))
                    } else {
                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.appdetail_apply_priority), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ADAppOpsDialog(
    packageName : String,
    lang        : String,
    onDismiss   : () -> Unit,
    onSetOp     : (String, Boolean) -> Unit,
    getOpState  : suspend (String) -> Boolean
) {
    val opsList = listOf(
        "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG",
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE", "CAMERA", "RECORD_AUDIO", "FINE_LOCATION",
        "COARSE_LOCATION", "READ_PHONE_STATE", "CALL_PHONE", "USE_FINGERPRINT",
        "BODY_SENSORS", "POST_NOTIFICATIONS", "SCHEDULE_EXACT_ALARM",
        "ACCESS_WIFI_STATE", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "PACKAGE_USAGE_STATS"
    )

    val scope = rememberCoroutineScope()
    var opsStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(packageName) {
        val initialStates = mutableMapOf<String, Boolean>()
        for (op in opsList) { initialStates[op] = getOpState(op) }
        opsStates = initialStates
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App Ops", fontWeight = FontWeight.Bold)
                    Text(packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            if (isLoading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(opsList) { op ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(op, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked         = opsStates[op] ?: true,
                                onCheckedChange = { checked ->
                                    opsStates = opsStates.toMutableMap().also { it[op] = checked }
                                    scope.launch { onSetOp(op, checked) }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
