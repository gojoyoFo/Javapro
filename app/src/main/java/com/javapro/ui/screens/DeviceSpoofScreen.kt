package com.javapro.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.javapro.spoof.SpoofBrand
import com.javapro.spoof.SpoofDevice
import com.javapro.spoof.SpoofExecutor
import com.javapro.spoof.allBrands
import com.javapro.utils.PremiumManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSpoofScreen(
    navController : NavController,
    lang          : String = "en",
    onWatchAd     : (onAdStarted: () -> Unit, onAdFinished: (AdWatchResult) -> Unit) -> Unit
) {
    val context   = LocalContext.current
    val isPremium = remember { PremiumManager.isPremium(context) }
    val isRoot    = remember { SpoofExecutor.isRooted() }
    val scope     = rememberCoroutineScope()
    val brands    = remember { allBrands() }
    val prefs     = remember {
        context.getSharedPreferences("javapro_settings", android.content.Context.MODE_PRIVATE)
    }

    var showDyworSheet       by remember { mutableStateOf(!prefs.getBoolean("spoof_dywor_shown", false)) }
    var selectedBrand        by remember { mutableStateOf<SpoofBrand?>(null) }
    var selectedDevice       by remember { mutableStateOf<SpoofDevice?>(null) }
    var showDeviceSheet      by remember { mutableStateOf(false) }
    var isApplying           by remember { mutableStateOf(false) }
    var appliedDevice        by remember { mutableStateOf(prefs.getString("spoof_active_device", null)) }
    var pendingReboot        by remember { mutableStateOf(prefs.getBoolean("spoof_pending_reboot", false)) }
    var showRebootDialog     by remember { mutableStateOf(false) }
    var rebootDialogDeviceName by remember { mutableStateOf("") }
    var showResetRebootDialog  by remember { mutableStateOf(false) }

    fun doApply(device: SpoofDevice) {
        scope.launch {
            isApplying = true
            val ok = withContext(Dispatchers.IO) {
                SpoofExecutor.applySpoof(device)
            }
            isApplying = false
            if (ok) {
                appliedDevice      = device.name
                pendingReboot      = true
                showDeviceSheet    = false
                selectedDevice     = null
                rebootDialogDeviceName = device.name
                showRebootDialog   = true
                prefs.edit()
                    .putString("spoof_active_device", device.name)
                    .putBoolean("spoof_pending_reboot", true)
                    .apply()
            } else {
                Toast.makeText(context, context.getString(R.string.spoof_apply_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doReset() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                SpoofExecutor.removeSpoof()
            }
            if (ok) {
                appliedDevice     = null
                pendingReboot     = true
                showResetRebootDialog = true
                prefs.edit()
                    .remove("spoof_active_device")
                    .putBoolean("spoof_pending_reboot", true)
                    .apply()
            } else {
                Toast.makeText(context, context.getString(R.string.spoof_apply_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showRebootDialog) {
        RebootConfirmDialog(
            title   = stringResource(R.string.spoof_reboot_title),
            body    = stringResource(R.string.spoof_reboot_body, rebootDialogDeviceName),
            onReboot = {
                showRebootDialog = false
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.spoof_reboot_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onLater = {
                showRebootDialog = false
            }
        )
    }

    if (showResetRebootDialog) {
        RebootConfirmDialog(
            title    = stringResource(R.string.spoof_reset_reboot_title),
            body     = stringResource(R.string.spoof_reset_reboot_body),
            onReboot = {
                showResetRebootDialog = false
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.spoof_reboot_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onLater = {
                showResetRebootDialog = false
            }
        )
    }

    if (showDyworSheet) {
        DyworBottomSheet(onDismiss = {
            prefs.edit().putBoolean("spoof_dywor_shown", true).apply()
            showDyworSheet = false
        })
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
                if (isPremium) {
                    doApply(device)
                } else {
                    onWatchAd({}, { result ->
                        if (result == AdWatchResult.COMPLETED) doApply(device)
                        else {
                            isApplying = false
                            Toast.makeText(context, context.getString(R.string.spoof_ad_skipped), Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(stringResource(R.string.spoof_screen_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isRoot) item { RootWarningCard() }

            if (pendingReboot) {
                item { PendingRebootBanner() }
            }

            if (appliedDevice != null) {
                item {
                    ActiveSpoofCard(deviceName = appliedDevice!!, onReset = { doReset() })
                }
            }

            item {
                Text(
                    text       = stringResource(R.string.spoof_select_brand),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.padding(vertical = 4.dp)
                )
            }

            items(brands) { brand ->
                BrandCard(
                    brand      = brand,
                    isSelected = selectedBrand?.name == brand.name,
                    isRoot     = isRoot,
                    onClick    = {
                        if (!brand.isSoon && isRoot)
                            selectedBrand = if (selectedBrand?.name == brand.name) null else brand
                        else if (!isRoot)
                            Toast.makeText(context, context.getString(R.string.spoof_root_required), Toast.LENGTH_SHORT).show()
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
                                onClick   = { selectedDevice = device; showDeviceSheet = true }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RebootConfirmDialog(
    title    : String,
    body     : String,
    onReboot : () -> Unit,
    onLater  : () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        icon             = { Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) },
        title            = { Text(title, fontWeight = FontWeight.Bold) },
        text             = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton    = {
            Button(onClick = onReboot, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.spoof_reboot_now))
            }
        },
        dismissButton    = {
            OutlinedButton(onClick = onLater, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.spoof_reboot_later))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun PendingRebootBanner() {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
            Text(
                text  = stringResource(R.string.spoof_pending_reboot_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun RootWarningCard() {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(24.dp))
            Column {
                Text(stringResource(R.string.spoof_root_only_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.spoof_root_only_desc),  style = MaterialTheme.typography.bodySmall,  color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
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
                modifier         = Modifier.size(40.dp).clip(CircleShape).background(primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CheckCircle, null, tint = primary, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.spoof_active_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text(deviceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            OutlinedButton(
                onClick        = onReset,
                shape          = RoundedCornerShape(10.dp),
                border         = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier       = Modifier.height(34.dp)
            ) {
                Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.spoof_btn_reset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BrandCard(brand: SpoofBrand, isSelected: Boolean, isRoot: Boolean, onClick: () -> Unit) {
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
            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Icon(brand.icon, null, tint = contentColor, modifier = Modifier.size(24.dp))
            Text(brand.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = contentColor, modifier = Modifier.weight(1f))
            if (brand.isSoon) {
                Text(
                    text     = stringResource(R.string.spoof_soon_badge),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            } else {
                Text("${brand.devices.size} ${stringResource(R.string.spoof_devices_count)}", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
                Icon(if (isSelected) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = contentColor, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DeviceItem(device: SpoofDevice, isApplied: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(containerColor = if (isApplied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        border   = if (isApplied) BorderStroke(1.5.dp, primary.copy(alpha = 0.5f)) else null
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (isApplied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (device.chipset.isNotEmpty())
                    Text(device.chipset, style = MaterialTheme.typography.bodySmall, color = if (isApplied) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (isApplied) Icons.Filled.CheckCircle else Icons.Filled.ChevronRight, null, tint = if (isApplied) primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailSheet(device: SpoofDevice, isRoot: Boolean, isApplying: Boolean, onDismiss: () -> Unit, onApply: (SpoofDevice) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor   = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (device.chipset.isNotEmpty())     SpoofInfoRow(Icons.Filled.Memory,       stringResource(R.string.spoof_detail_chipset),      device.chipset)
            SpoofInfoRow(Icons.Filled.PhoneAndroid, stringResource(R.string.spoof_detail_model), device.model)
            if (device.fingerprint.isNotEmpty()) SpoofInfoRow(Icons.Filled.Fingerprint,  stringResource(R.string.spoof_detail_fingerprint),  device.fingerprint, maxLines = 2)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (!isRoot) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.spoof_root_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(R.string.spoof_btn_cancel))
                }
                Button(onClick = { onApply(device) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), enabled = isRoot && !isApplying) {
                    if (isApplying) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.spoof_btn_apply)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpoofInfoRow(icon: ImageVector, label: String, value: String, maxLines: Int = 1) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DyworBottomSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor   = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            Text(stringResource(R.string.spoof_dywor_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.spoof_dywor_body),  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DyworPoint(Icons.Filled.Android,       stringResource(R.string.spoof_dywor_point1))
                    DyworPoint(Icons.Filled.SportsEsports, stringResource(R.string.spoof_dywor_point2))
                    DyworPoint(Icons.Filled.Warning,       stringResource(R.string.spoof_dywor_point3))
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.spoof_dywor_btn), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DyworPoint(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
