package com.javapro.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.R
import com.javapro.utils.PremiumManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpoofDevice(
    val name       : String,
    val model      : String,
    val brand      : String,
    val props      : Map<String, String>,
    val chipset    : String  = "",
    val fingerprint: String  = ""
)

data class SpoofBrand(
    val name    : String,
    val icon    : ImageVector,
    val devices : List<SpoofDevice>,
    val isSoon  : Boolean = false
)

private fun asusRogDevices(): List<SpoofDevice> = listOf(
    SpoofDevice(
        name        = "ASUS ROG Phone 9",
        model       = "ASUSAI2501",
        brand       = "asus",
        chipset     = "Snapdragon™ 8 Elite",
        fingerprint = "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
        props = mapOf(
            "ro.product.name"                    to "ASUS_ROG_9",
            "ro.product.device"                  to "ASUSAI2501",
            "ro.product.brand"                   to "asus",
            "ro.product.model"                   to "ASUSAI2501",
            "ro.soc.model.external_name"         to "Snapdragon™ 8 Elite",
            "ro.soc.manufacturer"                to "Qualcomm Technologies, Inc.",
            "ro.hardware.chipname"               to "Snapdragon™ 8 Elite",
            "ro.product.cpu.abi"                 to "arm64-v8a",
            "ro.vendor.qti.soc_model"            to "SM8750",
            "persist.sys.gaming.xmode"           to "true",
            "persist.sys.gaming.xmode.level"     to "3",
            "persist.sys.gaming.gputurbo"        to "on",
            "persist.sys.gaming.touch.acceleration" to "1",
            "persist.sys.gaming.lag_reduction"   to "enabled",
            "persist.sys.gaming.performance.boost" to "ultra",
            "ro.config.battery_capacity"         to "6000",
            "ro.battery.charging.tech"           to "HyperCharge 65W",
            "ro.display.panel.manufacturer"      to "Samsung E6 AMOLED",
            "ro.cooling.system"                  to "3D Vapor Chamber, AeroActive Cooler X",
            "persist.sys.cooling.fan_support"    to "true"
        )
    ),
    SpoofDevice(
        name        = "ASUS ROG Phone 8 Pro",
        model       = "ASUSAI2401_B",
        brand       = "asus",
        chipset     = "Snapdragon™ 8 Gen 3",
        fingerprint = "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
        props = mapOf(
            "ro.product.name"                    to "ASUS_ROG_8_PRO",
            "ro.product.device"                  to "ASUSAI2401_B",
            "ro.product.brand"                   to "asus",
            "ro.product.model"                   to "ASUSAI2401_B",
            "ro.soc.model.external_name"         to "Snapdragon™ 8 Gen 3",
            "ro.soc.manufacturer"                to "Qualcomm Technologies, Inc.",
            "ro.hardware.chipname"               to "Snapdragon™ 8 Gen 3",
            "ro.product.cpu.abi"                 to "arm64-v8a",
            "ro.vendor.qti.soc_model"            to "SM8650",
            "persist.sys.gaming.xmode"           to "true",
            "persist.sys.gaming.xmode.level"     to "2",
            "persist.sys.gaming.gputurbo"        to "on",
            "ro.config.battery_capacity"         to "5800",
            "ro.battery.charging.tech"           to "HyperCharge 65W",
            "ro.display.panel.manufacturer"      to "Samsung E6 AMOLED"
        )
    ),
    SpoofDevice(
        name        = "ASUS ROG Phone 7 Ultimate",
        model       = "ASUSAI2205_D",
        brand       = "asus",
        chipset     = "Snapdragon™ 8 Gen 2",
        fingerprint = "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
        props = mapOf(
            "ro.product.name"                to "ASUS_ROG_7_ULTIMATE",
            "ro.product.device"              to "ASUSAI2205_D",
            "ro.product.brand"               to "asus",
            "ro.product.model"               to "ASUSAI2205_D",
            "ro.soc.model.external_name"     to "Snapdragon™ 8 Gen 2",
            "ro.soc.manufacturer"            to "Qualcomm Technologies, Inc.",
            "ro.hardware.chipname"           to "Snapdragon™ 8 Gen 2",
            "ro.product.cpu.abi"             to "arm64-v8a",
            "ro.vendor.qti.soc_model"        to "SM8550",
            "persist.sys.gaming.xmode"       to "true",
            "persist.sys.gaming.xmode.level" to "3",
            "persist.sys.gaming.gputurbo"    to "on",
            "ro.config.battery_capacity"     to "6000"
        )
    )
)

private fun samsungDevices(): List<SpoofDevice> = listOf(
    SpoofDevice(
        name        = "Galaxy Z Fold5",
        model       = "SM-F9460",
        brand       = "samsung",
        chipset     = "Snapdragon 8 Gen 2",
        fingerprint = "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
        props = mapOf(
            "ro.product.brand"                     to "samsung",
            "ro.product.manufacturer"              to "samsung",
            "ro.product.model"                     to "SM-F9460",
            "ro.product.odm.model"                 to "SM-F9460",
            "ro.product.system.model"              to "SM-F9460",
            "ro.product.vendor.model"              to "SM-F9460",
            "ro.product.odm.marketname"            to "Galaxy Z Fold5",
            "ro.product.product.marketname"        to "Galaxy Z Fold5",
            "ro.product.system.marketname"         to "Galaxy Z Fold5",
            "ro.product.vendor.marketname"         to "Galaxy Z Fold5",
            "ro.product.marketname"                to "Galaxy Z Fold5",
            "ro.product.name"                      to "Galaxy Z Fold5",
            "ro.product.device"                    to "q5q",
            "ro.soc.manufacturer"                  to "Qualcomm",
            "ro.soc.model"                         to "SM8650",
            "sys.fps_unlock_allowed"               to "120"
        )
    )
)

private fun oneplusDevices(): List<SpoofDevice> = listOf(
    SpoofDevice(
        name        = "OnePlus 15",
        model       = "OP611FL1",
        brand       = "OnePlus",
        chipset     = "Snapdragon 8 Elite",
        fingerprint = "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
        props = mapOf(
            "ro.product.device"                      to "OnePlus 15",
            "ro.product.system.brand"                to "OnePlus",
            "ro.product.system.manufacturer"         to "OnePlus",
            "ro.product.product.manufacturer"        to "OnePlus",
            "ro.system.manufacturer"                 to "OnePlus",
            "ro.product.model"                       to "OP611FL1",
            "ro.product.name"                        to "OnePlus 15",
            "ro.product.brand"                       to "OnePlus",
            "ro.product.brand_for_attestation"       to "oneplus",
            "ro.product.device_for_attestation"      to "OP611FL1",
            "ro.product.manufacturer_for_attestation" to "OnePlus",
            "ro.product.model_for_attestation"       to "OnePlus 15"
        )
    )
)

private fun allBrands(): List<SpoofBrand> = listOf(
    SpoofBrand("ASUS ROG",  Icons.Filled.SportsEsports, asusRogDevices()),
    SpoofBrand("Samsung",   Icons.Filled.PhoneAndroid,  samsungDevices()),
    SpoofBrand("OnePlus",   Icons.Filled.Bolt,          oneplusDevices()),
    SpoofBrand("Xiaomi",    Icons.Filled.Memory,        emptyList(), isSoon = true),
    SpoofBrand("Google",    Icons.Filled.Android,       emptyList(), isSoon = true),
    SpoofBrand("Realme",    Icons.Filled.Speed,         emptyList(), isSoon = true),
    SpoofBrand("Vivo",      Icons.Filled.Smartphone,    emptyList(), isSoon = true),
    SpoofBrand("Oppo",      Icons.Filled.MobileScreenShare, emptyList(), isSoon = true),
)

private fun isRooted(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root"))
        val result  = process.inputStream.bufferedReader().readLine()
        result?.trim() == "root"
    } catch (_: Exception) { false }
}

private fun applySpoof(context: Context, device: SpoofDevice): Boolean {
    return try {
        val sb = StringBuilder()
        device.props.forEach { (k, v) ->
            sb.append("resetprop $k \"$v\"\n")
        }
        if (device.fingerprint.isNotEmpty()) {
            sb.append("resetprop ro.build.fingerprint \"${device.fingerprint}\"\n")
            sb.append("resetprop ro.product.build.fingerprint \"${device.fingerprint}\"\n")
            sb.append("resetprop ro.system.build.fingerprint \"${device.fingerprint}\"\n")
            sb.append("resetprop ro.vendor.build.fingerprint \"${device.fingerprint}\"\n")
        }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", sb.toString()))
        process.waitFor() == 0
    } catch (_: Exception) { false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSpoofScreen(
    navController : NavController,
    lang          : String = "en",
    onWatchAd     : (onAdStarted: () -> Unit, onAdFinished: (AdWatchResult) -> Unit) -> Unit
) {
    val context    = LocalContext.current
    val isRoot     = remember { isRooted() }
    val scope      = rememberCoroutineScope()
    val brands     = remember { allBrands() }

    var showDyworSheet    by remember { mutableStateOf(true) }
    var selectedBrand     by remember { mutableStateOf<SpoofBrand?>(null) }
    var selectedDevice    by remember { mutableStateOf<SpoofDevice?>(null) }
    var showDeviceSheet   by remember { mutableStateOf(false) }
    var isApplying        by remember { mutableStateOf(false) }
    var appliedDevice     by remember { mutableStateOf<String?>(null) }
    var showRebootDialog  by remember { mutableStateOf(false) }

    if (showDyworSheet) {
        DyworBottomSheet(onDismiss = { showDyworSheet = false })
    }

    if (showDeviceSheet && selectedDevice != null) {
        DeviceDetailSheet(
            device     = selectedDevice!!,
            isRoot     = isRoot,
            isApplying = isApplying,
            onDismiss  = { showDeviceSheet = false; selectedDevice = null },
            onApply    = { device ->
                if (!isRoot) {
                    Toast.makeText(context, context.getString(R.string.spoof_root_required), Toast.LENGTH_SHORT).show()
                    return@DeviceDetailSheet
                }
                isApplying = true
                onWatchAd(
                    {},
                    { result ->
                        if (result == AdWatchResult.COMPLETED) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { applySpoof(context, device) }
                                isApplying = false
                                if (ok) {
                                    appliedDevice   = device.name
                                    showDeviceSheet = false
                                    showRebootDialog = true
                                } else {
                                    Toast.makeText(context, context.getString(R.string.spoof_apply_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            isApplying = false
                            Toast.makeText(context, context.getString(R.string.spoof_ad_skipped), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        )
    }

    if (showRebootDialog && appliedDevice != null) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            icon = {
                Icon(
                    imageVector        = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text       = stringResource(R.string.spoof_reboot_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.spoof_reboot_body, appliedDevice!!))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootDialog = false
                        try {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                        } catch (_: Exception) {
                            Toast.makeText(context, context.getString(R.string.spoof_reboot_failed), Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.spoof_reboot_now))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRebootDialog = false },
                    shape   = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.spoof_reboot_later))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = stringResource(R.string.spoof_screen_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isRoot) {
                item {
                    RootWarningCard()
                }
            }

            if (appliedDevice != null) {
                item {
                    ActiveSpoofCard(
                        deviceName = appliedDevice!!,
                        onReset    = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        Runtime.getRuntime().exec(arrayOf("su", "-c",
                                            "resetprop --delete ro.product.model;" +
                                            "resetprop --delete ro.product.brand;" +
                                            "resetprop --delete ro.product.device;" +
                                            "resetprop --delete ro.product.name;" +
                                            "resetprop --delete ro.build.fingerprint;" +
                                            "resetprop --delete ro.soc.model.external_name;" +
                                            "resetprop --delete ro.hardware.chipname"
                                        )).waitFor()
                                    } catch (_: Exception) {}
                                }
                                appliedDevice = null
                                Toast.makeText(context, context.getString(R.string.spoof_reset_success), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            item {
                Text(
                    text       = stringResource(R.string.spoof_select_brand),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onBackground,
                    modifier   = Modifier.padding(vertical = 4.dp)
                )
            }

            items(brands) { brand ->
                BrandCard(
                    brand      = brand,
                    isSelected = selectedBrand?.name == brand.name,
                    isRoot     = isRoot,
                    onClick    = {
                        if (!brand.isSoon && isRoot) {
                            selectedBrand = if (selectedBrand?.name == brand.name) null else brand
                        } else if (!isRoot) {
                            Toast.makeText(context, context.getString(R.string.spoof_root_required), Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                AnimatedVisibility(
                    visible = selectedBrand?.name == brand.name && !brand.isSoon,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier            = Modifier.padding(start = 16.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        brand.devices.forEach { device ->
                            DeviceItem(
                                device    = device,
                                isApplied = appliedDevice == device.name,
                                onClick   = {
                                    selectedDevice  = device
                                    showDeviceSheet = true
                                }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RootWarningCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier            = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment   = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = Icons.Filled.Lock,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onErrorContainer,
                modifier           = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text       = stringResource(R.string.spoof_root_only_title),
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text  = stringResource(R.string.spoof_root_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ActiveSpoofCard(deviceName: String, onReset: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                modifier          = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.15f)),
                contentAlignment  = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint               = primary,
                    modifier           = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = stringResource(R.string.spoof_active_label),
                    style      = MaterialTheme.typography.labelSmall,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text       = deviceName,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            OutlinedButton(
                onClick = onReset,
                shape   = RoundedCornerShape(10.dp),
                border  = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    imageVector        = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.error,
                    modifier           = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = stringResource(R.string.spoof_btn_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BrandCard(
    brand      : SpoofBrand,
    isSelected : Boolean,
    isRoot     : Boolean,
    onClick    : () -> Unit
) {
    val containerColor = when {
        brand.isSoon -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        isSelected   -> MaterialTheme.colorScheme.secondaryContainer
        else         -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        brand.isSoon -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        isSelected   -> MaterialTheme.colorScheme.onSecondaryContainer
        else         -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        enabled  = !brand.isSoon && isRoot
    ) {
        Row(
            modifier              = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = brand.icon,
                contentDescription = null,
                tint               = contentColor,
                modifier           = Modifier.size(24.dp)
            )
            Text(
                text       = brand.name,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = contentColor,
                modifier   = Modifier.weight(1f)
            )
            if (brand.isSoon) {
                Text(
                    text  = stringResource(R.string.spoof_soon_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            } else if (!brand.isSoon) {
                Text(
                    text  = "${brand.devices.size} ${stringResource(R.string.spoof_devices_count)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
                Icon(
                    imageVector        = if (isSelected) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint               = contentColor,
                    modifier           = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device    : SpoofDevice,
    isApplied : Boolean,
    onClick   : () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(
            containerColor = if (isApplied)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        border   = if (isApplied) BorderStroke(1.5.dp, primary.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = device.name,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (isApplied)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                if (device.chipset.isNotEmpty()) {
                    Text(
                        text  = device.chipset,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isApplied)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isApplied) {
                Icon(
                    imageVector        = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint               = primary,
                    modifier           = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    imageVector        = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailSheet(
    device     : SpoofDevice,
    isRoot     : Boolean,
    isApplying : Boolean,
    onDismiss  : () -> Unit,
    onApply    : (SpoofDevice) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor   = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text       = device.name,
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onSurface
            )

            if (device.chipset.isNotEmpty()) {
                SpoofInfoRow(Icons.Filled.Memory, stringResource(R.string.spoof_detail_chipset), device.chipset)
            }

            SpoofInfoRow(Icons.Filled.PhoneAndroid, stringResource(R.string.spoof_detail_model), device.model)

            if (device.fingerprint.isNotEmpty()) {
                SpoofInfoRow(
                    icon  = Icons.Filled.Fingerprint,
                    label = stringResource(R.string.spoof_detail_fingerprint),
                    value = device.fingerprint,
                    maxLines = 2
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text       = stringResource(R.string.spoof_detail_props_title),
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape  = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    device.props.entries.take(8).forEach { (k, v) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text     = k.substringAfterLast("."),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text     = v,
                                style    = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color    = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (device.props.size > 8) {
                        Text(
                            text  = "+${device.props.size - 8} ${stringResource(R.string.spoof_more_props)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (!isRoot) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape  = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier              = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint        = MaterialTheme.colorScheme.onErrorContainer,
                            modifier    = Modifier.size(18.dp)
                        )
                        Text(
                            text  = stringResource(R.string.spoof_root_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick  = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.spoof_btn_cancel))
                }
                Button(
                    onClick  = { onApply(device) },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(14.dp),
                    enabled  = isRoot && !isApplying
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(
                            modifier  = Modifier.size(18.dp),
                            color     = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.spoof_btn_apply))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpoofInfoRow(
    icon     : ImageVector,
    label    : String,
    value    : String,
    maxLines : Int = 1
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.Top
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Column {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text     = value,
                style    = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DyworBottomSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle       = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.Warning,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(32.dp)
                )
            }

            Text(
                text       = stringResource(R.string.spoof_dywor_title),
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color      = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text  = stringResource(R.string.spoof_dywor_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape  = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier            = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DyworPoint(Icons.Filled.Android,       stringResource(R.string.spoof_dywor_point1))
                    DyworPoint(Icons.Filled.SportsEsports, stringResource(R.string.spoof_dywor_point2))
                    DyworPoint(Icons.Filled.Warning,       stringResource(R.string.spoof_dywor_point3))
                }
            }

            Button(
                onClick  = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text       = stringResource(R.string.spoof_dywor_btn),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DyworPoint(icon: ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier           = Modifier.size(16.dp)
        )
        Text(
            text  = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
