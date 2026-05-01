package com.javapro.ui.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.R
import com.javapro.service.BatteryMonitorService
import com.javapro.utils.BatteryExecutor
import com.javapro.utils.BatterySnapshot
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BatGreen  = Color(0xFF66BB6A)
private val BatYellow = Color(0xFFFFCA28)
private val BatRed    = Color(0xFFEF5350)
private val BatBlue   = Color(0xFF42A5F5)
private val BatPurple = Color(0xFFAB47BC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(navController: NavController, lang: String) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val isRooted = remember { TweakExecutor.checkRoot() }

    var batteryInfo     by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var history         by remember { mutableStateOf<List<BatterySnapshot>>(emptyList()) }
    var limitEnabled    by remember { mutableStateOf(BatteryExecutor.isChargeLimitEnabled(context)) }
    var limitValue      by remember { mutableStateOf(BatteryExecutor.getChargeLimitValue(context).toFloat()) }
    var isApplyingLimit by remember { mutableStateOf(false) }
    var limitPath       by remember { mutableStateOf(BatteryExecutor.getChargeLimitPath()) }
    var schedEnabled    by remember { mutableStateOf(false) }
    var schedStart      by remember { mutableStateOf("22:00") }
    var schedStop       by remember { mutableStateOf("07:00") }
    var monitorEnabled  by remember { mutableStateOf(BatteryExecutor.isMonitorEnabled(context)) }
    var overheatThresh  by remember { mutableStateOf(BatteryExecutor.getOverheatThreshold(context)) }
    var lowThresh       by remember { mutableStateOf(BatteryExecutor.getLowBatteryThreshold(context).toFloat()) }
    var serviceRunning  by remember { mutableStateOf(BatteryMonitorService.isRunning(context)) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            batteryInfo = withContext(Dispatchers.IO) { BatteryExecutor.getBatteryInfo(context) }
            val intent  = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val temp     = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                val voltage  = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val levelPct = if (scale > 0) (level * 100 / scale) else level
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                               status == BatteryManager.BATTERY_STATUS_FULL
                val currentMa = withContext(Dispatchers.IO) { BatteryExecutor.getCurrentMa(charging) }
                val watt      = withContext(Dispatchers.IO) { BatteryExecutor.getWattage(context, charging) }
                val snap      = BatterySnapshot(System.currentTimeMillis(), levelPct, temp, voltage, charging, currentMa, watt)
                withContext(Dispatchers.IO) { BatteryExecutor.recordSnapshot(context, snap) }
            }
            history      = withContext(Dispatchers.IO) { BatteryExecutor.loadHistoryFromContext(context) }
            limitPath    = BatteryExecutor.getChargeLimitPath()
            serviceRunning = BatteryMonitorService.isRunning(context)
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(5_000L)
            refresh()
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.battery_clear_history_title)) },
            text  = { Text(stringResource(R.string.battery_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = {
                    BatteryExecutor.clearHistory(context)
                    history = emptyList()
                    showClearDialog = false
                }) { Text(stringResource(R.string.action_delete), color = BatRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.battery_monitor_title),
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle  = FontStyle.Italic,
                        fontSize   = 20.sp,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BatLabel(Icons.Default.BatteryStd, stringResource(R.string.label_battery_status), BatGreen)
            BatteryStatusCard(batteryInfo)

            if (history.size >= 2) {
                BatLabel(Icons.Default.ShowChart, stringResource(R.string.label_history), BatBlue)
                BatteryHistoryCard(history)
            }

            BatLabel(Icons.Default.HealthAndSafety, stringResource(R.string.label_health), BatYellow)
            BatteryHealthCard(batteryInfo)

            if (isRooted) {
                BatLabel(Icons.Default.ElectricBolt, stringResource(R.string.label_charge_limit), BatRed)
                ChargeLimitCard(
                    enabled    = limitEnabled,
                    value      = limitValue,
                    isApplying = isApplyingLimit,
                    limitPath  = limitPath,
                    onEnabled  = { limitEnabled = it },
                    onValue    = { limitValue = it },
                    onApply    = {
                        scope.launch {
                            isApplyingLimit = true
                            val ok = withContext(Dispatchers.IO) {
                                if (limitEnabled) BatteryExecutor.applyChargeLimit(limitValue.toInt())
                                else BatteryExecutor.removeChargeLimit()
                            }
                            BatteryExecutor.saveChargeLimitPref(context, limitEnabled, limitValue.toInt())
                            isApplyingLimit = false
                            Toast.makeText(
                                context,
                                if (ok) context.getString(R.string.charge_limit_apply_success)
                                else    context.getString(R.string.charge_limit_apply_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )

                BatLabel(Icons.Default.Schedule, stringResource(R.string.label_charge_schedule), BatBlue)
                ChargeScheduleCard(
                    enabled     = schedEnabled,
                    startTime   = schedStart,
                    stopTime    = schedStop,
                    onEnabled   = { schedEnabled = it },
                    onStartTime = { schedStart = it },
                    onStopTime  = { schedStop = it },
                    onApply     = {
                        Toast.makeText(context, context.getString(R.string.charge_schedule_saved), Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                BatCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BatRed.copy(0.08f))
                            .border(BorderStroke(0.5.dp, BatRed.copy(0.3f)), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = BatRed, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.root_required_message),
                            fontSize = 12.sp,
                            color    = BatRed
                        )
                    }
                }
            }

            BatLabel(Icons.Default.Notifications, stringResource(R.string.label_battery_notification), MaterialTheme.colorScheme.primary)
            BatteryNotifCard(
                monitorEnabled  = monitorEnabled,
                serviceRunning  = serviceRunning,
                overheatThresh  = overheatThresh,
                lowThresh       = lowThresh,
                onToggleMonitor = { enabled ->
                    monitorEnabled = enabled
                    BatteryExecutor.setMonitorEnabled(context, enabled)
                    if (enabled) {
                        BatteryMonitorService.start(context)
                        serviceRunning = true
                    } else {
                        BatteryMonitorService.stop(context)
                        serviceRunning = false
                    }
                },
                onOverheatThresh = { v ->
                    overheatThresh = v
                    BatteryExecutor.setOverheatThreshold(context, v)
                },
                onLowThresh = { v ->
                    lowThresh = v
                    BatteryExecutor.setLowBatteryThreshold(context, v.toInt())
                }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BatteryStatusCard(info: Map<String, String>) {
    BatCard {
        if (info.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return@BatCard
        }

        val level = info[stringResource(R.string.info_key_level)]?.replace("%", "")?.toIntOrNull() ?: 0
        val levelColor = when {
            level >= 60 -> BatGreen
            level >= 30 -> BatYellow
            else        -> BatRed
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(levelColor.copy(0.1f))
                    .border(BorderStroke(2.dp, levelColor.copy(0.4f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$level", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = levelColor)
                    Text("%", fontSize = 10.sp, color = levelColor.copy(0.7f), fontWeight = FontWeight.Bold)
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val primaryKeys = listOf(
                    stringResource(R.string.info_key_status),
                    stringResource(R.string.info_key_temperature),
                    stringResource(R.string.info_key_voltage),
                    stringResource(R.string.info_key_current),
                    stringResource(R.string.info_key_wattage),
                    stringResource(R.string.info_key_charger)
                )
                primaryKeys.forEach { key ->
                    info[key]?.let { value ->
                        val accent = when (key) {
                            stringResource(R.string.info_key_wattage)  -> BatPurple
                            stringResource(R.string.info_key_current)  -> BatBlue
                            else -> null
                        }
                        BatInfoRow(key, value, accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryHealthCard(info: Map<String, String>) {
    BatCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val healthKeys = listOf(
                stringResource(R.string.info_key_health),
                stringResource(R.string.info_key_technology),
                stringResource(R.string.info_key_cycle_count),
                stringResource(R.string.info_key_design_capacity),
                stringResource(R.string.info_key_current_capacity),
                stringResource(R.string.info_key_wear_level),
                stringResource(R.string.info_key_charge_type),
                stringResource(R.string.info_key_resistance),
                stringResource(R.string.info_key_input_current),
                stringResource(R.string.info_key_remaining_charge)
            )
            val available = healthKeys.filter { info.containsKey(it) }

            if (available.isEmpty()) {
                Text(
                    stringResource(R.string.health_data_unavailable),
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@BatCard
            }

            val wearKey   = stringResource(R.string.info_key_wear_level)
            val healthKey = stringResource(R.string.info_key_health)
            val goodStr   = stringResource(R.string.health_good)

            available.forEach { key ->
                info[key]?.let { value ->
                    val accent = when (key) {
                        wearKey -> {
                            val pct = value.replace("%", "").toIntOrNull() ?: 0
                            when { pct < 10 -> BatGreen; pct < 25 -> BatYellow; else -> BatRed }
                        }
                        healthKey -> if (value == goodStr) BatGreen else BatRed
                        else -> null
                    }
                    BatInfoRow(key, value, accent)
                }
            }
        }
    }
}

@Composable
private fun BatteryHistoryCard(history: List<BatterySnapshot>) {
    BatCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val recent = history.takeLast(24)

            if (recent.size >= 2) {
                BatteryLineChart(
                    snapshots = recent,
                    modifier  = Modifier.fillMaxWidth().height(120.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f), thickness = 0.5.dp)
            }

            val last = history.last()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatStatChip("${last.level}%",        stringResource(R.string.info_key_level),       BatGreen)
                BatStatChip("${last.temperature}°C", stringResource(R.string.info_key_temperature), BatYellow)
                BatStatChip("${last.voltage}mV",     stringResource(R.string.info_key_voltage),     BatBlue)
            }
            if (last.currentMa != 0 || last.watt > 0f) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (last.currentMa != 0)
                        BatStatChip("${last.currentMa}mA", stringResource(R.string.info_key_current), BatBlue)
                    if (last.watt > 0f)
                        BatStatChip("${"%.1f".format(last.watt)}W", stringResource(R.string.info_key_wattage), BatPurple)
                }
            }
        }
    }
}

@Composable
private fun BatteryLineChart(snapshots: List<BatterySnapshot>, modifier: Modifier) {
    val lineColor = BatGreen
    val fillColor = BatGreen.copy(alpha = 0.15f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant.copy(0.4f)

    Canvas(modifier) {
        if (snapshots.size < 2) return@Canvas

        val w      = size.width
        val h      = size.height
        val padH   = 16.dp.toPx()
        val padV   = 8.dp.toPx()
        val chartH = h - padV * 2
        val chartW = w - padH * 2

        val minLevel = snapshots.minOf { it.level }.coerceAtMost(90)
        val maxLevel = 100

        fun xOf(i: Int)     = padH + i.toFloat() / (snapshots.size - 1) * chartW
        fun yOf(level: Int) = padV + (1f - (level - minLevel).toFloat() / (maxLevel - minLevel)) * chartH

        drawLine(axisColor, Offset(padH, padV), Offset(padH, h - padV), strokeWidth = 1.dp.toPx())
        drawLine(axisColor, Offset(padH, h - padV), Offset(w - padH, h - padV), strokeWidth = 1.dp.toPx())

        val fillPath = Path()
        fillPath.moveTo(xOf(0), h - padV)
        snapshots.forEachIndexed { i, s -> fillPath.lineTo(xOf(i), yOf(s.level)) }
        fillPath.lineTo(xOf(snapshots.size - 1), h - padV)
        fillPath.close()
        drawPath(fillPath, fillColor)

        val linePath = Path()
        snapshots.forEachIndexed { i, s ->
            if (i == 0) linePath.moveTo(xOf(i), yOf(s.level))
            else        linePath.lineTo(xOf(i), yOf(s.level))
        }
        drawPath(linePath, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        snapshots.forEachIndexed { i, s ->
            if (s.isCharging) {
                drawCircle(BatBlue, radius = 3.dp.toPx(), center = Offset(xOf(i), yOf(s.level)))
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            BatteryExecutor.formatTimestamp(snapshots.first().timestamp),
            fontSize   = 9.sp,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            BatteryExecutor.formatTimestamp(snapshots.last().timestamp),
            fontSize   = 9.sp,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ChargeLimitCard(
    enabled: Boolean, value: Float, isApplying: Boolean,
    limitPath: String?,
    onEnabled: (Boolean) -> Unit, onValue: (Float) -> Unit, onApply: () -> Unit
) {
    BatCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.charge_limit_description),
                fontSize   = 12.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )

            if (limitPath == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BatYellow.copy(0.08f))
                        .border(BorderStroke(0.5.dp, BatYellow.copy(0.3f)), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = BatYellow, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.charge_limit_path_not_found), fontSize = 11.sp, color = BatYellow)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BatGreen.copy(0.06f))
                        .border(BorderStroke(0.5.dp, BatGreen.copy(0.25f)), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = BatGreen, modifier = Modifier.size(12.dp))
                    Text(limitPath, fontSize = 9.sp, color = BatGreen, fontFamily = FontFamily.Monospace)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f)), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.charge_limit_enable_label),
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.charge_limit_current, value.toInt()),
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked         = enabled,
                    onCheckedChange = onEnabled,
                    enabled         = limitPath != null,
                    colors          = SwitchDefaults.colors(checkedTrackColor = BatGreen)
                )
            }

            if (enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.charge_limit_slider_label), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${value.toInt()}%",
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color      = when {
                                value >= 90 -> BatRed
                                value >= 70 -> BatYellow
                                else        -> BatGreen
                            }
                        )
                    }
                    Slider(
                        value         = value,
                        onValueChange = onValue,
                        valueRange    = 50f..99f,
                        steps         = 48,
                        colors        = SliderDefaults.colors(thumbColor = BatGreen, activeTrackColor = BatGreen)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("50%", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("99%", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Button(
                onClick  = onApply,
                enabled  = limitPath != null && !isApplying,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = BatGreen)
            ) {
                if (isApplying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.ElectricBolt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.charge_limit_apply), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChargeScheduleCard(
    enabled: Boolean, startTime: String, stopTime: String,
    onEnabled: (Boolean) -> Unit, onStartTime: (String) -> Unit,
    onStopTime: (String) -> Unit, onApply: () -> Unit
) {
    BatCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.charge_schedule_description),
                fontSize   = 12.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f)), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.charge_schedule_enable_label),
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked         = enabled,
                    onCheckedChange = onEnabled,
                    colors          = SwitchDefaults.colors(checkedTrackColor = BatBlue)
                )
            }

            if (enabled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.charge_schedule_start),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value         = startTime,
                            onValueChange = onStartTime,
                            singleLine    = true,
                            placeholder   = { Text("22:00", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)) },
                            shape         = RoundedCornerShape(10.dp),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = BatBlue.copy(0.5f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(0.4f)
                            )
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.charge_schedule_stop),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value         = stopTime,
                            onValueChange = onStopTime,
                            singleLine    = true,
                            placeholder   = { Text("07:00", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)) },
                            shape         = RoundedCornerShape(10.dp),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor   = BatBlue.copy(0.5f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(0.4f)
                            )
                        )
                    }
                }

                Button(
                    onClick  = onApply,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = BatBlue)
                ) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.charge_schedule_save), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BatInfoRow(label: String, value: String, accent: Color? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color      = accent ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BatStatChip(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            shape  = RoundedCornerShape(10.dp),
            color  = color.copy(0.1f),
            border = BorderStroke(0.8.dp, color.copy(0.35f))
        ) {
            Text(
                value,
                fontSize   = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = color,
                modifier   = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontFamily = FontFamily.Monospace
            )
        }
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BatLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier              = Modifier.padding(start = 2.dp, top = 4.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            text,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.ExtraBold,
            fontStyle     = FontStyle.Italic,
            color         = tint,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun BatCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f)), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun BatteryNotifCard(
    monitorEnabled: Boolean, serviceRunning: Boolean,
    overheatThresh: Float, lowThresh: Float,
    onToggleMonitor: (Boolean) -> Unit,
    onOverheatThresh: (Float) -> Unit,
    onLowThresh: (Float) -> Unit
) {
    BatCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.notif_description),
                fontSize   = 12.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f)), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.notif_monitor_label),
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (serviceRunning) stringResource(R.string.notif_status_running)
                        else               stringResource(R.string.notif_status_inactive),
                        fontSize = 11.sp,
                        color    = if (serviceRunning) BatGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked         = monitorEnabled,
                    onCheckedChange = onToggleMonitor,
                    colors          = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
            }

            if (monitorEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f), thickness = 0.5.dp)

                Text(
                    stringResource(R.string.notif_alert_settings),
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Thermostat, null, tint = BatRed, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.notif_overheat_alert), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Text("${overheatThresh.toInt()}°C", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = BatRed)
                    }
                    Slider(
                        value         = overheatThresh,
                        onValueChange = onOverheatThresh,
                        valueRange    = 35f..60f,
                        steps         = 24,
                        colors        = SliderDefaults.colors(thumbColor = BatRed, activeTrackColor = BatRed)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("35°C", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("60°C", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.BatteryAlert, null, tint = BatYellow, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.notif_low_battery_alert), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Text("${lowThresh.toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = BatYellow)
                    }
                    Slider(
                        value         = lowThresh,
                        onValueChange = onLowThresh,
                        valueRange    = 5f..30f,
                        steps         = 24,
                        colors        = SliderDefaults.colors(thumbColor = BatYellow, activeTrackColor = BatYellow)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("5%",  fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("30%", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f), thickness = 0.5.dp)

                Text(
                    stringResource(R.string.notif_includes_title),
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )

                val notifFeatures = listOf(
                    Icons.Default.BatteryFull  to stringResource(R.string.notif_feature_level),
                    Icons.Default.Thermostat   to stringResource(R.string.notif_feature_temp),
                    Icons.Default.ElectricBolt to stringResource(R.string.notif_feature_voltage),
                    Icons.Default.Speed        to stringResource(R.string.notif_feature_current),
                    Icons.Default.FlashOn      to stringResource(R.string.notif_feature_watt),
                    Icons.Default.Timer        to stringResource(R.string.notif_feature_estimate),
                    Icons.Default.Power        to stringResource(R.string.notif_feature_charger_type),
                    Icons.Default.Warning      to stringResource(R.string.notif_feature_overheat),
                    Icons.Default.BatteryAlert to stringResource(R.string.notif_feature_low_battery),
                    Icons.Default.CheckCircle  to stringResource(R.string.notif_feature_charge_limit)
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    notifFeatures.forEach { (icon, label) ->
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
