package com.javapro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.javapro.R
import com.javapro.utils.BatteryExecutor
import com.javapro.utils.BatterySnapshot

class BatteryMonitorService : Service() {

    private val CHANNEL_MONITOR  = "battery_monitor"
    private val CHANNEL_ALERT    = "battery_alert"
    private val NOTIF_ID_MONITOR = 7001
    private val NOTIF_ID_ALERT   = 7002

    private val ACTION_STOP = "com.javapro.BATTERY_MONITOR_STOP"

    private var lastLevel        = -1
    private var lastCharging     = false
    private var chargingSince    = 0L
    private var dischargeSince   = 0L
    private var levelAtCharge    = 0
    private var levelAtDischarge = 0
    private var alertedOverheat  = false
    private var alertedLow       = false
    private var alertedLimit     = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)
                ACTION_STOP                   -> stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val placeholder = buildMonitorNotification(
            level      = 0,
            temp       = 0f,
            voltage    = 0,
            isCharging = false,
            charger    = "—",
            timeLabel  = "—",
            currentMa  = 0,
            watt       = 0f
        )
        startForeground(NOTIF_ID_MONITOR, placeholder)
        val current = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (current != null) handleBatteryChanged(current)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_ALERT)
        nm.cancel(NOTIF_ID_ALERT + 1)
        nm.cancel(NOTIF_ID_ALERT + 2)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleBatteryChanged(intent: Intent) {
        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

        val levelPct   = if (scale > 0) level * 100 / scale else level
        val temp       = tempRaw / 10f
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        val chargerStr = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC       -> getString(R.string.charger_ac)
            BatteryManager.BATTERY_PLUGGED_USB      -> getString(R.string.charger_usb)
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> getString(R.string.charger_wireless)
            else                                    -> ""
        }

        val now        = System.currentTimeMillis()
        val currentMa  = BatteryExecutor.getCurrentMa(isCharging)
        val watt       = BatteryExecutor.getWattage(applicationContext, isCharging)
        val inputWatt  = if (isCharging) BatteryExecutor.getInputWattage(applicationContext) else 0f

        if (lastLevel == -1) {
            lastLevel        = levelPct
            lastCharging     = isCharging
            levelAtCharge    = levelPct
            levelAtDischarge = levelPct
            chargingSince    = now
            dischargeSince   = now
        }

        if (isCharging && !lastCharging) {
            chargingSince = now; levelAtCharge = levelPct; alertedLimit = false
        }
        if (!isCharging && lastCharging) {
            dischargeSince = now; levelAtDischarge = levelPct
        }

        val snap = BatterySnapshot(now, levelPct, temp, voltage, isCharging, currentMa, watt)
        BatteryExecutor.recordSnapshot(applicationContext, snap)

        val timeLabel = estimateTime(levelPct, isCharging)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_MONITOR, buildMonitorNotification(
            levelPct, temp, voltage, isCharging, chargerStr, timeLabel, currentMa, watt, inputWatt
        ))

        checkAlerts(levelPct, temp, isCharging, nm)

        lastLevel    = levelPct
        lastCharging = isCharging
    }

    private fun estimateTime(level: Int, isCharging: Boolean): String {
        val history = BatteryExecutor.loadHistoryFromContext(applicationContext)
        if (history.size < 3) return "—"

        if (isCharging) {
            val h = history.filter { it.isCharging }.takeLast(10)
            if (h.size < 2) return "—"
            val elapsed = (h.last().timestamp - h.first().timestamp) / 1000f / 60f
            val gained  = h.last().level - h.first().level
            if (gained <= 0 || elapsed <= 0) return "—"
            val remaining = (100 - level) / (gained / elapsed)
            return if (remaining < 60)
                getString(R.string.notif_time_min_to_full, remaining.toInt())
            else
                getString(R.string.notif_time_hour_to_full, (remaining / 60).toInt(), (remaining % 60).toInt())
        } else {
            val h = history.filter { !it.isCharging }.takeLast(10)
            if (h.size < 2) return "—"
            val elapsed = (h.last().timestamp - h.first().timestamp) / 1000f / 60f
            val lost    = h.first().level - h.last().level
            if (lost <= 0 || elapsed <= 0) return "—"
            val remaining = level / (lost / elapsed)
            return if (remaining < 60)
                getString(R.string.notif_time_min_left, remaining.toInt())
            else
                getString(R.string.notif_time_hour_left, (remaining / 60).toInt(), (remaining % 60).toInt())
        }
    }

    private fun buildMonitorNotification(
        level: Int, temp: Float, voltage: Int,
        isCharging: Boolean, charger: String, timeLabel: String,
        currentMa: Int, watt: Float, inputWatt: Float = 0f
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("nav_to", "battery")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tempIcon     = when { temp >= 45f -> "🔴"; temp >= 38f -> "🟡"; else -> "🟢" }
        val chargeIcon   = if (isCharging) "⚡" else "🔋"
        val chargerLabel = if (charger.isNotBlank()) " · $charger" else ""
        val titleText    = "$chargeIcon $level%$chargerLabel · ${temp}°C $tempIcon"

        val contentText = buildString {
            if (voltage > 0) append("${voltage}mV")
            if (currentMa != 0) { if (isNotEmpty()) append(" · "); append("${currentMa}mA") }
            if (watt > 0f) { if (isNotEmpty()) append(" · "); append("↓${"%.1f".format(watt)}W") }
            if (inputWatt > 0f) { if (isNotEmpty()) append(" · "); append("↑${"%.1f".format(inputWatt)}W") }
            if (timeLabel != "—") { if (isNotEmpty()) append(" · "); append(timeLabel) }
            if (isEmpty()) append(if (isCharging) getString(R.string.notif_content_charging) else getString(R.string.notif_content_discharging))
        }

        val bigText = buildString {
            appendLine(getString(R.string.notif_big_level, level))
            appendLine(getString(R.string.notif_big_temp, temp))
            if (voltage > 0) appendLine(getString(R.string.notif_big_voltage, voltage))
            if (currentMa != 0) appendLine(getString(R.string.notif_big_current, currentMa))
            if (watt > 0f) appendLine(getString(R.string.notif_big_watt_out, watt))
            if (inputWatt > 0f) appendLine(getString(R.string.notif_big_watt_in, inputWatt))
            if (timeLabel != "—") appendLine(getString(R.string.notif_big_estimate, timeLabel))
            if (isCharging && charger.isNotBlank()) appendLine(getString(R.string.notif_big_charger, charger))
        }.trimEnd()

        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_battery_notif)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setProgress(100, level, false)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentPi)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_action_stop), stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun checkAlerts(level: Int, temp: Float, isCharging: Boolean, nm: NotificationManager) {
        val limitEnabled      = BatteryExecutor.isChargeLimitEnabled(applicationContext)
        val limitValue        = BatteryExecutor.getChargeLimitValue(applicationContext)
        val overheatThreshold = BatteryExecutor.getOverheatThreshold(applicationContext)
        val lowThreshold      = BatteryExecutor.getLowBatteryThreshold(applicationContext)

        if (temp >= overheatThreshold && !alertedOverheat) {
            alertedOverheat = true
            nm.notify(NOTIF_ID_ALERT, buildAlertNotification(
                getString(R.string.alert_overheat_title),
                getString(R.string.alert_overheat_body, temp)
            ))
        } else if (temp < overheatThreshold - 3f) {
            alertedOverheat = false
        }

        if (!isCharging && level <= lowThreshold && level != lastLevel && !alertedLow) {
            alertedLow = true
            nm.notify(NOTIF_ID_ALERT + 1, buildAlertNotification(
                getString(R.string.alert_low_title, level),
                getString(R.string.alert_low_body)
            ))
        } else if (level > lowThreshold + 5) {
            alertedLow = false
        }

        if (isCharging && limitEnabled && level >= limitValue && !alertedLimit) {
            alertedLimit = true
            nm.notify(NOTIF_ID_ALERT + 2, buildAlertNotification(
                getString(R.string.alert_limit_title, level),
                getString(R.string.alert_limit_body, limitValue)
            ))
        }
    }

    private fun buildAlertNotification(title: String, body: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 99, launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_battery_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, getString(R.string.notif_channel_monitor_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_monitor_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, getString(R.string.notif_channel_alert_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notif_channel_alert_desc)
            }
        )
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == BatteryMonitorService::class.java.name }
        }
    }
}
