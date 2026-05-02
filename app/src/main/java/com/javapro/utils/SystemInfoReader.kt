package com.javapro.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class SystemSnapshot(
    val cpuUsagePct   : Float,
    val cpuTempC      : Float,
    val clusters      : List<ClusterSnapshot>,
    val gpuUsagePct   : Float,
    val gpuFreqMhz    : Int,
    val gpuTempC      : Float,
    val ramUsedMb     : Long,
    val ramTotalMb    : Long,
    val batteryPct    : Int,
    val batteryTempC  : Float,
    val batteryVoltMv : Int,
    val isCharging    : Boolean
)

data class ClusterSnapshot(
    val label      : String,
    val cores      : List<Int>,
    val curFreqMhz : Int,
    val maxFreqMhz : Int
)

object SystemInfoReader {

    private var prevIdle  = -1L
    private var prevTotal = -1L

    private val CPU_TEMP_PATHS = listOf(
        "/sys/class/thermal/thermal_zone0/temp" to 1000,
        "/sys/class/thermal/thermal_zone1/temp" to 1000,
        "/sys/devices/virtual/thermal/thermal_zone0/temp" to 1000,
        "/sys/class/hwmon/hwmon0/temp1_input" to 1000,
        "/sys/kernel/debug/hisi_thermal/temp" to 1,
        "/proc/mtktscpu/mtktscpu" to 1000
    )

    private val GPU_USAGE_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/gpu/gpubusy",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/devfreq/gpufreq/cur_governor",
        "/sys/kernel/debug/mali/utilization_gp_pp"
    )

    private val GPU_FREQ_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk"                        to 1_000_000L,
        "/sys/class/kgsl/kgsl-3d0/max_gpuclk"                    to 1_000_000L,
        "/sys/kernel/gpu/gpu_clock"                               to 1_000L,
        "/sys/class/devfreq/gpufreq/cur_freq"                     to 1_000_000L,
        "/sys/class/devfreq/ff9a0000.gpu/cur_freq"                to 1_000_000L,
        "/sys/class/devfreq/13000000.mali/cur_freq"               to 1_000_000L,
        "/sys/class/devfreq/fde60000.gpu/cur_freq"                to 1_000_000L,
        "/sys/class/misc/mali0/device/clock"                      to 1_000L,
        "/sys/class/misc/mali0/device/devfreq/mali/cur_freq"      to 1_000_000L,
        "/sys/devices/platform/gpufreq/cur_freq"                  to 1_000_000L,
        "/sys/devices/platform/gpu/devfreq/gpu/cur_freq"          to 1_000_000L
    )

    private val GPU_TEMP_PATHS = listOf(
        "/sys/class/thermal/thermal_zone2/temp" to 1000,
        "/sys/class/thermal/thermal_zone3/temp" to 1000,
        "/sys/kernel/gpu/gpu_tmu"               to 1000,
        "/sys/class/hwmon/hwmon1/temp1_input"   to 1000
    )

    private val RAM_FREQ_PATHS = listOf(
        "/sys/class/devfreq/ddrfreq/cur_freq",
        "/sys/devices/platform/ddrfreq/cur_freq",
        "/sys/kernel/debug/clk/ddr/clk_rate",
        "/sys/class/devfreq/rockchip-dmc/cur_freq"
    )

    @Volatile private var cachedPolicyClusters: List<List<Int>>? = null

    suspend fun read(context: Context): SystemSnapshot = withContext(Dispatchers.IO) {
        val cpuUsage  = readCpuUsage()
        val cpuTemp   = readCpuTemp()
        val clusters  = readClusters()
        val gpuUsage  = readGpuUsage()
        val gpuFreq   = readGpuFreq()
        val gpuTemp   = readGpuTemp()
        val (ramUsed, ramTotal) = readRam(context)
        val (batPct, batTemp, batVolt, charging) = readBattery(context)

        SystemSnapshot(
            cpuUsagePct   = cpuUsage,
            cpuTempC      = cpuTemp,
            clusters      = clusters,
            gpuUsagePct   = gpuUsage,
            gpuFreqMhz    = gpuFreq,
            gpuTempC      = gpuTemp,
            ramUsedMb     = ramUsed,
            ramTotalMb    = ramTotal,
            batteryPct    = batPct,
            batteryTempC  = batTemp,
            batteryVoltMv = batVolt,
            isCharging    = charging
        )
    }

    private fun readCpuUsage(): Float {
        return try {
            val line = File("/proc/stat").bufferedReader().readLine() ?: return 0f
            if (!line.startsWith("cpu ")) return 0f
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 8) return 0f

            val user    = parts[1].toLong()
            val nice    = parts[2].toLong()
            val system  = parts[3].toLong()
            val idle    = parts[4].toLong()
            val iowait  = parts[5].toLong()
            val irq     = parts[6].toLong()
            val softirq = parts[7].toLong()
            val steal   = if (parts.size > 8) parts[8].toLong() else 0L
            val total   = user + nice + system + idle + iowait + irq + softirq + steal

            if (prevTotal != -1L && total != prevTotal) {
                val diffTotal = total - prevTotal
                val diffIdle  = idle  - prevIdle
                val usage = (100f * (diffTotal - diffIdle) / diffTotal).coerceIn(0f, 100f)
                prevTotal = total; prevIdle = idle
                usage
            } else {
                prevTotal = total; prevIdle = idle
                0f
            }
        } catch (_: Exception) { 0f }
    }

    private fun readCpuTemp(): Float {
        for ((path, divider) in CPU_TEMP_PATHS) {
            val raw = readFirstLine(path)?.trim()?.toFloatOrNull() ?: continue
            val celsius = raw / divider
            if (celsius in 1f..150f) return celsius
        }
        return 0f
    }

    private fun readClusters(): List<ClusterSnapshot> {
        return try {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until cpuCount).map { core ->
                val cur = readSysNode("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                    ?.trim()?.toLongOrNull() ?: 0L
                val max = readSysNode("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                    ?.trim()?.toLongOrNull() ?: 0L
                cur to max
            }

            val policyClusters = getPolicyClusters()
            val groups: List<List<Int>> = if (policyClusters.isNotEmpty()) {
                policyClusters
            } else {
                val uniqueMax = freqs.map { it.second }.distinct().filter { it > 0L }.sorted()
                uniqueMax.map { maxFreq -> freqs.mapIndexedNotNull { i, f -> if (f.second == maxFreq) i else null } }
            }

            val labels = listOf("Little", "Mid", "Big")
            groups.mapIndexed { idx, cores ->
                val curValues = cores.mapNotNull { freqs.getOrNull(it)?.first?.takeIf { v -> v > 0L } }
                val avgCur    = if (curValues.isNotEmpty()) curValues.average().toLong() else 0L
                val maxFreq   = cores.mapNotNull { freqs.getOrNull(it)?.second?.takeIf { v -> v > 0L } }.maxOrNull() ?: 0L
                ClusterSnapshot(
                    label      = labels.getOrElse(idx) { "Cluster ${idx + 1}" },
                    cores      = cores,
                    curFreqMhz = (avgCur / 1000).toInt(),
                    maxFreqMhz = (maxFreq / 1000).toInt()
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun getPolicyClusters(): List<List<Int>> {
        cachedPolicyClusters?.let { return it }
        return try {
            val dir = File("/sys/devices/system/cpu/cpufreq")
            if (!dir.exists()) return emptyList()
            val result = dir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("policy") }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
                ?.mapNotNull { policyDir ->
                    val f = File(policyDir, "related_cpus")
                    if (!f.exists() || !f.canRead()) return@mapNotNull null
                    val cores = f.bufferedReader().use { it.readLine() ?: "" }
                        .trim().split(Regex("\\s+"))
                        .mapNotNull { it.toIntOrNull() }.sorted()
                    if (cores.isEmpty()) null else cores
                }.orEmpty()
            if (result.isNotEmpty()) cachedPolicyClusters = result
            result
        } catch (_: Exception) { emptyList() }
    }

    private fun readGpuUsage(): Float {
        for (path in GPU_USAGE_PATHS) {
            val line = readFirstLine(path)?.trim() ?: continue
            val cleaned = line.replace("%", "").split(" ").firstOrNull()?.trim() ?: continue
            val v = cleaned.toFloatOrNull() ?: continue
            if (v in 0f..100f) return v
        }
        return 0f
    }

    private fun readGpuFreq(): Int {
        for ((path, divider) in GPU_FREQ_PATHS) {
            val raw = readSysNode(path)?.trim()?.toLongOrNull() ?: continue
            if (raw <= 0L) continue
            val mhz = (raw / divider).toInt()
            if (mhz in 1..5000) return mhz
            // auto-detect: jika divider gagal coba tebak unit dari magnitude
            val mhzKhz = (raw / 1_000L).toInt()
            if (mhzKhz in 1..5000) return mhzKhz
            val mhzHz  = (raw / 1_000_000L).toInt()
            if (mhzHz  in 1..5000) return mhzHz
        }
        return 0
    }

    private fun readGpuTemp(): Float {
        for ((path, divider) in GPU_TEMP_PATHS) {
            val raw = readFirstLine(path)?.trim()?.toFloatOrNull() ?: continue
            val celsius = raw / divider
            if (celsius in 1f..150f) return celsius
        }
        return 0f
    }

    private fun readRam(context: Context): Pair<Long, Long> {
        return try {
            var memTotal = 0L; var memAvailable = 0L
            File("/proc/meminfo").bufferedReader().use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    when {
                        l.startsWith("MemTotal:")     -> memTotal     = parseMemKb(l)
                        l.startsWith("MemAvailable:") -> memAvailable = parseMemKb(l)
                    }
                    if (memTotal > 0 && memAvailable > 0) return@use
                }
            }
            if (memTotal == 0L) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                return Pair(
                    (mi.totalMem - mi.availMem) / (1024 * 1024),
                    mi.totalMem / (1024 * 1024)
                )
            }
            Pair((memTotal - memAvailable) / 1024, memTotal / 1024)
        } catch (_: Exception) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            Pair((mi.totalMem - mi.availMem) / (1024 * 1024), mi.totalMem / (1024 * 1024))
        }
    }

    private fun readBattery(context: Context): Quadruple<Int, Float, Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Quadruple(0, 0f, 0, false)
        val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp     = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volt     = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct      = if (scale > 0) level * 100 / scale else level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        return Quadruple(pct, temp, volt, charging)
    }

    private fun parseMemKb(line: String): Long =
        line.trim().split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L

    private fun readFirstLine(path: String): String? {
        return try { BufferedReader(FileReader(path)).use { it.readLine() } }
        catch (_: IOException) { null }
        catch (_: Exception) { null }
    }

    private fun readSysNode(path: String): String? {
        val direct = readFirstLine(path)
        if (!direct.isNullOrBlank()) return direct
        return try { TweakExecutor.executeWithOutput("cat $path")?.trim()?.takeIf { it.isNotEmpty() } }
        catch (_: Exception) { null }
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
