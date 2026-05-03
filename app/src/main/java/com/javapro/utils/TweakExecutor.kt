package com.javapro.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object TweakExecutor {

    private var rootChecked = false
    private var isRooted = false

    fun checkRoot(): Boolean {
        if (rootChecked) return isRooted
        isRooted = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val line = reader.readLine()
                line?.contains("uid=0") == true
            }
        } catch (e: Exception) {
            false
        }
        rootChecked = true
        return isRooted
    }

    suspend fun execute(command: String) = withContext(Dispatchers.IO) {
        try {
            if (checkRoot()) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
            } else if (ShizukuManager.isAvailable()) {
                ShizukuManager.runCommand(command)
            }
        } catch (e: Exception) { }
    }

    suspend fun executeWithOutput(command: String): String = withContext(Dispatchers.IO) {
        try {
            if (checkRoot()) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.trim()
            } else if (ShizukuManager.isAvailable()) {
                ShizukuManager.runCommand(command)
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Versi blocking (non-suspend) dari executeWithOutput.
     * Dipakai dari fungsi non-coroutine seperti readFreq() di HomeScreen.
     * Harus dipanggil dari thread IO (Dispatchers.IO), bukan main thread.
     */
    fun executeWithOutputSync(command: String): String {
        return try {
            if (checkRoot()) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                process.destroyForcibly()
                output.trim()
            } else if (ShizukuManager.isAvailable()) {
                ShizukuManager.runCommand(command)
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun getDeviceInfo(context: Context): Map<String, String> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val ramString = String.format("%.1f GB", totalRamGb)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryString = if (batteryLevel >= 0 && batteryScale > 0) {
            "${(batteryLevel * 100 / batteryScale)}%"
        } else {
            "--%"
        }

        val kernelString = try {
            System.getProperty("os.version") ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        return mapOf(
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "manufacturer" to Build.MANUFACTURER,
            "board" to Build.BOARD,
            "hardware" to Build.HARDWARE,
            "kernel" to kernelString,
            "RAM" to ramString,
            "Battery" to batteryString
        )
    }

    suspend fun resetResolution() {
        execute("wm size reset")
        execute("wm density reset")
    }

    suspend fun applyGlobalResolution(context: Context, scale: Float) {
        if (scale >= 0.99f) {
            resetResolution()
        } else {
            val metrics = DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
            val targetW = (metrics.widthPixels * scale).toInt()
            val targetH = (metrics.heightPixels * scale).toInt()
            execute("wm size ${targetW}x${targetH}")
            execute("wm density ${metrics.densityDpi}")
        }
    }

    suspend fun applyColorModifier(rUI: Float, gUI: Float, bUI: Float, satUI: Float) {
        val r = (rUI / 1000f * 255).toInt()
        val g = (gUI / 1000f * 255).toInt()
        val b = (bUI / 1000f * 255).toInt()
        val sat = satUI / 1000f
        execute("service call SurfaceFlinger 1022 f $sat")
        execute("echo \"$r $g $b\" > /sys/devices/platform/kcal_ctrl.0/kcal")
        execute("echo 1 > /sys/devices/platform/kcal_ctrl.0/kcal_enable")
    }
}
