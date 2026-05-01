package com.javapro.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.javapro.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BatterySnapshot(
    val timestamp: Long,
    val level: Int,
    val temperature: Float,
    val voltage: Int,
    val isCharging: Boolean,
    val currentMa: Int = 0,
    val watt: Float = 0f
)

object BatteryExecutor {

    private const val KEY_HISTORY         = "battery_history_json"
    private const val KEY_LIMIT           = "charge_limit_enabled"
    private const val KEY_LIMIT_VAL       = "charge_limit_value"
    private const val KEY_MONITOR_ENABLED = "monitor_notif_enabled"
    private const val KEY_OVERHEAT_THRESH = "overheat_threshold"
    private const val KEY_LOW_THRESH      = "low_battery_threshold"
    private const val MAX_HISTORY         = 96
    private const val PREFS_BATTERY       = "BatteryPrefs"
    const val DEFAULT_OVERHEAT_THRESH     = 42f
    const val DEFAULT_LOW_THRESH          = 15

    private val SYSFS_CHARGE_LIMIT_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_control_limit",
        "/sys/class/power_supply/battery/charge_stop_level",
        "/sys/class/power_supply/battery/batt_slate_mode",
        "/sys/class/power_supply/sm5414-battery/charge_stop_level",
        "/sys/class/power_supply/bms/charge_control_limit",
        "/sys/class/power_supply/battery/constant_charge_current_max",
        "/sys/class/power_supply/battery/charge_control_limit_max",
        "/sys/class/power_supply/wireless/charge_stop_level",
        "/sys/class/power_supply/usb/charge_stop_level",
        "/sys/kernel/debug/regulator/battery/charge_stop_level",
        "/sys/devices/platform/battery/charge_stop_level"
    )

    private val SYSFS_CHARGING_ENABLED_PATHS = listOf(
        "/sys/class/power_supply/battery/charging_enabled",
        "/sys/class/power_supply/main/charging_enabled",
        "/sys/class/power_supply/battery/battery_charging_enabled"
    )

    private val SYSFS_INPUT_SUSPEND_PATHS = listOf(
        "/sys/class/power_supply/battery/input_suspend",
        "/sys/class/power_supply/usb/input_suspend",
        "/sys/class/power_supply/battery/charge_enabled"
    )

    private val SYSFS_CYCLE_PATHS = listOf(
        "/sys/class/power_supply/battery/cycle_count",
        "/sys/class/power_supply/bms/cycle_count",
        "/sys/class/power_supply/battery/charge_counter",
        "/sys/class/power_supply/bms/charge_cycle_count",
        "/sys/class/power_supply/battery/battery_cycle",
        "/sys/class/power_supply/max170xx_battery/cycle_count",
        "/sys/class/power_supply/ds2780-battery/cycle_count",
        "/sys/class/power_supply/fg-battery/cycle_count",
        "/sys/class/power_supply/main/cycle_count"
    )

    private val SYSFS_DESIGN_CAPACITY_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/bms/charge_full_design",
        "/sys/class/power_supply/battery/energy_full_design",
        "/sys/class/power_supply/bms/energy_full_design",
        "/sys/class/power_supply/battery/capacity_design_uah",
        "/sys/class/power_supply/max170xx_battery/charge_full_design",
        "/sys/class/power_supply/ds2780-battery/charge_full_design",
        "/sys/class/power_supply/fg-battery/charge_full_design"
    )

    private val SYSFS_FULL_CAPACITY_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_full",
        "/sys/class/power_supply/battery/energy_full",
        "/sys/class/power_supply/bms/energy_full",
        "/sys/class/power_supply/max170xx_battery/charge_full",
        "/sys/class/power_supply/ds2780-battery/charge_full",
        "/sys/class/power_supply/fg-battery/charge_full"
    )

    private val SYSFS_CURRENT_NOW_PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/main/current_now",
        "/sys/class/power_supply/battery/BatteryCurrent",
        "/sys/class/power_supply/battery/batt_current_now",
        "/sys/class/power_supply/usb/current_now",
        "/sys/class/power_supply/max170xx_battery/current_now",
        "/sys/class/power_supply/fg-battery/current_now"
    )

    private val SYSFS_CHARGE_COUNTER_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_counter",
        "/sys/class/power_supply/bms/charge_counter"
    )

    private val SYSFS_INPUT_CURRENT_PATHS = listOf(
        "/sys/class/power_supply/usb/input_current_max",
        "/sys/class/power_supply/usb/current_max",
        "/sys/class/power_supply/ac/input_current_max",
        "/sys/class/power_supply/ac/current_max",
        "/sys/class/power_supply/battery/input_current_max",
        "/sys/class/power_supply/wireless/input_current_max"
    )

    private val SYSFS_CHARGE_TYPE_PATHS = listOf(
        "/sys/class/power_supply/usb/real_type",
        "/sys/class/power_supply/usb/charge_type",
        "/sys/class/power_supply/battery/charge_type",
        "/sys/class/power_supply/ac/charge_type"
    )

    private val SYSFS_BATTERY_RESISTANCE_PATHS = listOf(
        "/sys/class/power_supply/battery/resistance",
        "/sys/class/power_supply/bms/resistance",
        "/sys/class/power_supply/battery/resistance_id"
    )

    private val SYSFS_TECHNOLOGY_PATHS = listOf(
        "/sys/class/power_supply/battery/technology",
        "/sys/class/power_supply/battery/type",
        "/sys/class/power_supply/bms/battery_type"
    )

    private fun readSysfs(path: String): String? {
        return try { File(path).readText().trim().takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }

    private fun findFirstSysfs(paths: List<String>): String? {
        return paths.firstNotNullOfOrNull { readSysfs(it) }
    }

    fun getCurrentMa(isCharging: Boolean): Int {
        val raw = findFirstSysfs(SYSFS_CURRENT_NOW_PATHS)?.toLongOrNull() ?: return 0
        var mA = raw.toFloat()
        if (kotlin.math.abs(mA) > 10_000_000) mA /= 1_000_000f
        else if (kotlin.math.abs(mA) > 10_000) mA /= 1_000f
        return if (isCharging) kotlin.math.abs(mA).toInt() else -kotlin.math.abs(mA).toInt()
    }

    fun getWattage(context: Context, isCharging: Boolean): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageRaw = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val volt = voltageRaw / 1000f
        val amp  = kotlin.math.abs(getCurrentMa(isCharging)) / 1000f
        return volt * amp
    }

    fun getBatteryInfo(context: Context): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return result

        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp    = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val health  = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tech    = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            ?: findFirstSysfs(SYSFS_TECHNOLOGY_PATHS)

        val levelPct   = if (scale > 0) (level * 100 / scale) else level
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        result[context.getString(R.string.info_key_level)]       = "$levelPct%"
        result[context.getString(R.string.info_key_temperature)] = "$temp°C"
        result[context.getString(R.string.info_key_voltage)]     = "${voltage}mV"
        result[context.getString(R.string.info_key_status)]      = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING     -> context.getString(R.string.status_charging)
            BatteryManager.BATTERY_STATUS_DISCHARGING  -> context.getString(R.string.status_discharging)
            BatteryManager.BATTERY_STATUS_FULL         -> context.getString(R.string.status_full)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> context.getString(R.string.status_not_charging)
            else                                        -> context.getString(R.string.status_unknown)
        }
        result[context.getString(R.string.info_key_health)] = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD                -> context.getString(R.string.health_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT            -> context.getString(R.string.health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD                -> context.getString(R.string.health_dead)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE        -> context.getString(R.string.health_over_voltage)
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> context.getString(R.string.health_failure)
            BatteryManager.BATTERY_HEALTH_COLD                -> context.getString(R.string.health_cold)
            else                                               -> context.getString(R.string.status_unknown)
        }
        result[context.getString(R.string.info_key_charger)] = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC       -> context.getString(R.string.charger_ac)
            BatteryManager.BATTERY_PLUGGED_USB      -> context.getString(R.string.charger_usb)
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> context.getString(R.string.charger_wireless)
            else                                    -> context.getString(R.string.charger_unplugged)
        }
        if (!tech.isNullOrBlank()) result[context.getString(R.string.info_key_technology)] = tech

        findFirstSysfs(SYSFS_CYCLE_PATHS)?.let {
            if (it != "0") result[context.getString(R.string.info_key_cycle_count)] = it
        }

        val currentMa = getCurrentMa(isCharging)
        if (currentMa != 0) {
            result[context.getString(R.string.info_key_current)] = "${currentMa}mA"
            val watt = getWattage(context, isCharging)
            if (watt > 0f) result[context.getString(R.string.info_key_wattage)] = "%.2fW".format(watt)
        }

        findFirstSysfs(SYSFS_INPUT_CURRENT_PATHS)?.toLongOrNull()?.let { uA ->
            val mA = uA / 1000L
            if (mA > 0) result[context.getString(R.string.info_key_input_current)] = "${mA}mA"
        }

        findFirstSysfs(SYSFS_CHARGE_TYPE_PATHS)?.let { chargeType ->
            if (chargeType.isNotBlank() && chargeType != "Unknown")
                result[context.getString(R.string.info_key_charge_type)] = chargeType
        }

        findFirstSysfs(SYSFS_BATTERY_RESISTANCE_PATHS)?.toIntOrNull()?.let { mohm ->
            if (mohm > 0) result[context.getString(R.string.info_key_resistance)] = "${mohm}mΩ"
        }

        findFirstSysfs(SYSFS_CHARGE_COUNTER_PATHS)?.toLongOrNull()?.let { uAh ->
            val mAh = uAh / 1000L
            if (mAh > 0) result[context.getString(R.string.info_key_remaining_charge)] = "${mAh}mAh"
        }

        val designRaw = findFirstSysfs(SYSFS_DESIGN_CAPACITY_PATHS)
        val fullRaw   = findFirstSysfs(SYSFS_FULL_CAPACITY_PATHS)
        if (designRaw != null) {
            val raw = designRaw.toLongOrNull() ?: 0L
            val designMah = if (raw > 100_000) raw / 1000 else raw
            if (designMah > 0) result[context.getString(R.string.info_key_design_capacity)] = "${designMah}mAh"
            if (fullRaw != null) {
                val rawFull = fullRaw.toLongOrNull() ?: 0L
                val fullMah = if (rawFull > 100_000) rawFull / 1000 else rawFull
                if (fullMah > 0 && designMah > 0) {
                    result[context.getString(R.string.info_key_current_capacity)] = "${fullMah}mAh"
                    val wear = 100 - (fullMah * 100 / designMah)
                    result[context.getString(R.string.info_key_wear_level)] = "$wear%"
                }
            }
        }

        return result
    }

    fun getChargeLimitPath(): String? {
        return SYSFS_CHARGE_LIMIT_PATHS.firstOrNull { File(it).exists() }
    }

    fun getChargingEnabledPath(): String? {
        return (SYSFS_CHARGING_ENABLED_PATHS + SYSFS_INPUT_SUSPEND_PATHS)
            .firstOrNull { File(it).exists() }
    }

    suspend fun applyChargeLimit(limit: Int): Boolean = withContext(Dispatchers.IO) {
        val path = getChargeLimitPath() ?: return@withContext false
        try {
            TweakExecutor.execute("echo $limit > $path")
            val verify = readSysfs(path)?.toIntOrNull()
            verify != null && verify <= limit
        } catch (_: Exception) { false }
    }

    suspend fun removeChargeLimit(): Boolean = withContext(Dispatchers.IO) {
        val path = getChargeLimitPath() ?: return@withContext false
        try { TweakExecutor.execute("echo 100 > $path"); true } catch (_: Exception) { false }
    }

    fun isChargeLimitEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIMIT, false)
    }

    fun getChargeLimitValue(context: Context): Int {
        return context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT_VAL, 80)
    }

    fun saveChargeLimitPref(context: Context, enabled: Boolean, value: Int) {
        context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LIMIT, enabled)
            .putInt(KEY_LIMIT_VAL, value)
            .apply()
    }

    fun recordSnapshot(context: Context, snapshot: BatterySnapshot) {
        val prefs    = context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
        val existing = loadHistory(prefs).toMutableList()
        existing.add(snapshot)
        if (existing.size > MAX_HISTORY) existing.removeAt(0)
        saveHistory(prefs, existing)
    }

    fun loadHistoryFromContext(context: Context): List<BatterySnapshot> {
        return loadHistory(context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE))
    }

    private fun loadHistory(prefs: android.content.SharedPreferences): List<BatterySnapshot> {
        return try {
            val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BatterySnapshot(
                    o.getLong("ts"),
                    o.getInt("level"),
                    o.getDouble("temp").toFloat(),
                    o.getInt("voltage"),
                    o.getBoolean("charging"),
                    o.optInt("currentMa", 0),
                    o.optDouble("watt", 0.0).toFloat()
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveHistory(prefs: android.content.SharedPreferences, list: List<BatterySnapshot>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("ts", s.timestamp)
                put("level", s.level)
                put("temp", s.temperature.toDouble())
                put("voltage", s.voltage)
                put("charging", s.isCharging)
                put("currentMa", s.currentMa)
                put("watt", s.watt.toDouble())
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun formatTimestamp(ts: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    fun isMonitorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
            .getBoolean(KEY_MONITOR_ENABLED, false)
    }

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MONITOR_ENABLED, enabled).apply()
    }

    fun getOverheatThreshold(context: Context): Float {
        return context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
            .getFloat(KEY_OVERHEAT_THRESH, DEFAULT_OVERHEAT_THRESH)
    }

    fun setOverheatThreshold(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_OVERHEAT_THRESH, value).apply()
    }

    fun getLowBatteryThreshold(context: Context): Int {
        return context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE)
            .getInt(KEY_LOW_THRESH, DEFAULT_LOW_THRESH)
    }

    fun setLowBatteryThreshold(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_BATTERY, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LOW_THRESH, value).apply()
    }
}
