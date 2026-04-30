package com.javapro.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
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

    var showDyworSheet         by remember { mutableStateOf(!prefs.getBoolean("spoof_dywor_shown", false)) }
    var selectedBrand          by remember { mutableStateOf<SpoofBrand?>(null) }
    var selectedDevice         by remember { mutableStateOf<SpoofDevice?>(null) }
    var showDeviceSheet        by remember { mutableStateOf(false) }
    var isApplying             by remember { mutableStateOf(false) }
    var appliedDevice          by remember { mutableStateOf(prefs.getString("spoof_active_device", null)) }
    var showRebootDialog       by remember { mutableStateOf(false) }
    var rebootDialogDeviceName by remember { mutableStateOf("") }
    var showResetRebootDialog  by remember { mutableStateOf(false) }

    var showPendingReboot by remember {
        val saved = prefs.getBoolean("spoof_pending_reboot", false)
        val resolved = if (saved) {
            val moduleActive = SpoofExecutor.isSpoofActive()
            if (!moduleActive) {
                prefs.edit().putBoolean("spoof_pending_reboot", false).apply()
                false
            } else true
        } else false
        mutableStateOf(resolved)
    }

    fun doApply(device: SpoofDevice) {
        scope.launch {
            isApplying = true
            val ok = withContext(Dispatchers.IO) { SpoofExecutor.applySpoof(device) }
            isApplying = false
            if (ok) {
                appliedDevice          = device.name
                showPendingReboot      = true
                showDeviceSheet        = false
                selectedDevice         = null
                rebootDialogDeviceName = device.name
                prefs.edit()
                    .putString("spoof_active_device", device.name)
                    .putBoolean("spoof_pending_reboot", true)
                    .apply()
                delay(600)
                showRebootDialog = true
            } else {
                Toast.makeText(context, context.getString(R.string.spoof_apply_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doReset() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { SpoofExecutor.removeSpoof() }
            if (ok) {
                appliedDevice     = null
                showPendingReboot = true
                prefs.edit()
                    .remove("spoof_active_device")
                    .putBoolean("spoof_pending_reboot", true)
                    .apply()
                delay(600)
                showResetRebootDialog = true
            } else {
                Toast.makeText(context, context.getString(R.string.spoof_apply_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showRebootDialog) {
        RebootConfirmDialog(
            title    = stringResource(R.string.spoof_reboot_title),
            body     = stringResource(R.string.spoof_reboot_body, rebootDialogDeviceName),
            onReboot = {
                showRebootDialog  = false
                showPendingReboot = false
                prefs.edit().putBoolean("spoof_pending_reboot", false).apply()
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) }
                catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.spoof_reboot_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onLater = { showRebootDialog = false }
        )
    }

    if (showResetRebootDialog) {
        RebootConfirmDialog(
            title    = stringResource(R.string.spoof_reset_reboot_title),
            body     = stringResource(R.string.spoof_reset_reboot_body),
            onReboot = {
                showResetRebootDialog = false
                showPendingReboot     = false
                prefs.edit().putBoolean("spoof_pending_reboot", false).apply()
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) }
                catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.spoof_reboot_failed), Toast.LENGTH_SHORT).show()
                }
            },
            onLater = { showResetRebootDialog = false }
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
                    onWatchAd({}) { result ->
                        if (result == AdWatchResult.COMPLETED) doApply(device)
                        else {
                            isApplying = false
                            Toast.makeText(context, context.getString(R.string.spoof_ad_skipped), Toast.LENGTH_SHORT).show()
                        }
                    }
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

            if (showPendingReboot) {
                item {
                    PendingRebootBanner(onDismiss = {
                        showPendingReboot = false
                        prefs.edit().putBoolean("spoof_pending_reboot", false).apply()
                    })
                }
            }

            if (appliedDevice != null) {
                item {
                    ActiveSpoofCard(
                        deviceName = appliedDevice!!,
                        onReset    = { doReset() },
                        onReboot   = {
                            rebootDialogDeviceName = appliedDevice ?: ""
                            showRebootDialog = true
                        }
                    )
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
                    enter   = expandVertically(animationSpec = tween(300)) + fadeIn(),
                    exit    = shrinkVertically(animationSpec = tween(250)) + fadeOut()
                ) {
                    Column(
                        modifier            = Modifier.padding(top = 8.dp),
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
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        },
        title = {
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        },
        confirmButton = {
            Column(
                modifier            = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onReboot, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.spoof_reboot_now), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onLater, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.spoof_reboot_later))
                }
            }
        },
        shape          = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun PendingRebootBanner(onDismiss: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.65f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label         = "alpha"
    )
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    null,
                    tint     = MaterialTheme.colorScheme.secondary.copy(alpha = pulseAlpha),
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.spoof_pending_reboot_title),
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    stringResource(R.string.spoof_pending_reboot_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    null,
                    tint     = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
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
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(stringResource(R.string.spoof_root_only_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.spoof_root_only_desc),  style = MaterialTheme.typography.bodySmall,  color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun ActiveSpoofCard(deviceName: String, onReset: () -> Unit, onReboot: () -> Unit) {
    val primary   = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Card(
        shape    = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent),
        border   = BorderStroke(1.dp, primary.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(primary.copy(alpha = 0.12f), secondary.copy(alpha = 0.06f))
                    )
                )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = primary, modifier = Modifier.size(26.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.spoof_active_label),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = primary.copy(alpha = 0.8f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            deviceName,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onSurface,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(primary.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.spoof_active_badge),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                HorizontalDivider(color = primary.copy(alpha = 0.12f))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick        = onReset,
                        modifier       = Modifier.weight(1f),
                        shape          = RoundedCornerShape(12.dp),
                        border         = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.spoof_btn_reset), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick        = onReboot,
                        modifier       = Modifier.weight(1f),
                        shape          = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.spoof_reboot_now), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandCard(brand: SpoofBrand, isSelected: Boolean, isRoot: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val arrowRotation by animateFloatAsState(
        targetValue   = if (isSelected) 180f else 0f,
        animationSpec = tween(300),
        label         = "arrow"
    )
    val containerColor = when {
        brand.isSoon -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        isSelected   -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else         -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        enabled  = !brand.isSoon && isRoot,
        border   = if (isSelected) BorderStroke(1.dp, primary.copy(alpha = 0.4f)) else null
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            val iconTint = if (brand.isSoon)
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            else if (isSelected) primary
            else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isSelected) primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(brand.icon, null, tint = iconTint, modifier = Modifier.size(21.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    brand.name,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (brand.isSoon) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                 else if (isSelected) primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!brand.isSoon) {
                    Text(
                        "${brand.devices.size} ${stringResource(R.string.spoof_devices_count)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            if (brand.isSoon) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.spoof_soon_badge),
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    tint     = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(22.dp).rotate(arrowRotation)
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(device: SpoofDevice, isApplied: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(
            containerColor = if (isApplied)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        ),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        border   = if (isApplied)
            BorderStroke(1.5.dp, primary.copy(alpha = 0.4f))
        else
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isApplied) primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isApplied) Icons.Filled.CheckCircle else Icons.Filled.PhoneAndroid,
                    null,
                    tint     = if (isApplied) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (isApplied) primary else MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                if (device.chipset.isNotEmpty()) {
                    Text(
                        device.chipset,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint     = if (isApplied) primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
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
    val primary   = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(40.dp).height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(primary.copy(alpha = 0.25f), secondary.copy(alpha = 0.15f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PhoneAndroid, null, tint = primary, modifier = Modifier.size(32.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    if (device.chipset.isNotEmpty()) {
                        Text(device.chipset, style = MaterialTheme.typography.bodySmall, color = primary)
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape  = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (device.chipset.isNotEmpty())
                        SpoofInfoRow(Icons.Filled.Memory,       stringResource(R.string.spoof_detail_chipset),     device.chipset)
                    SpoofInfoRow(Icons.Filled.PhoneAndroid,     stringResource(R.string.spoof_detail_model),       device.model)
                    if (device.fingerprint.isNotEmpty())
                        SpoofInfoRow(Icons.Filled.Fingerprint,  stringResource(R.string.spoof_detail_fingerprint), device.fingerprint, maxLines = 3)
                }
            }

            Text(
                stringResource(R.string.spoof_detail_props_title),
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape  = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val previewEntries = device.props.entries.take(8).toList()
                    previewEntries.forEachIndexed { idx, (key, value) ->
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                key.substringAfterLast("."),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                value,
                                style      = MaterialTheme.typography.labelSmall,
                                color      = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                modifier   = Modifier.weight(0.55f),
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis
                            )
                        }
                        if (idx < previewEntries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
                        }
                    }
                    if (device.props.size > 8) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "+${device.props.size - 8} ${stringResource(R.string.spoof_more_props)}",
                            style  = MaterialTheme.typography.labelSmall,
                            color  = primary.copy(alpha = 0.7f)
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
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.spoof_root_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.spoof_btn_cancel))
                }
                Button(
                    onClick  = { onApply(device) },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(16.dp),
                    enabled  = isRoot && !isApplying
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.spoof_btn_apply), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SpoofInfoRow(icon: ImageVector, label: String, value: String, maxLines: Int = 1) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DyworBottomSheet(onDismiss: () -> Unit) {
    val primary   = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(40.dp).height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = 0.3f), secondary.copy(alpha = 0.12f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Warning, null, tint = primary, modifier = Modifier.size(38.dp))
            }
            Text(stringResource(R.string.spoof_dywor_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(
                stringResource(R.string.spoof_dywor_body),
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Card(
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape    = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DyworPoint(Icons.Filled.Android,       stringResource(R.string.spoof_dywor_point1))
                    DyworPoint(Icons.Filled.SportsEsports, stringResource(R.string.spoof_dywor_point2))
                    DyworPoint(Icons.Filled.Warning,       stringResource(R.string.spoof_dywor_point3))
                }
            }
            Button(
                onClick  = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.spoof_dywor_btn), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DyworPoint(icon: ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(15.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
    }
}
