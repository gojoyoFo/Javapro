package com.javapro.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException

data class SystemSnapshot(
    val cpuUsagePct   : Float,
    val cpuTempC      : Float,
    val clusters      : List<ClusterSnapshot>,
    val gpuUsagePct   : Float,
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

    private const val TAG = "SystemInfoReader"

    private val cpuLock = Any()
    private var prevIdle  = -1L
    private var prevTotal = -1L

    @Volatile private var cachedCpuTempPath: String?   = null
    @Volatile private var cachedCpuTempDiv : Int        = 1000
    @Volatile private var cpuTempDetected  : Boolean    = false

    @Volatile private var cachedPolicyClusters: List<List<Int>>? = null

    @Volatile private var cachedGpuUsagePath: String? = null
    @Volatile private var gpuUsagePathDetected: Boolean = false

    private val GPU_TEMP_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/temp"            to 1000,
        "/sys/class/thermal/thermal_zone2/temp"     to 1000,
        "/sys/class/thermal/thermal_zone3/temp"     to 1000,
        "/sys/kernel/gpu/gpu_tmu"                   to 1000,
        "/sys/class/hwmon/hwmon1/temp1_input"       to 1000
    )

    suspend fun read(context: Context): SystemSnapshot = withContext(Dispatchers.IO) {
        val cpuUsage  = readCpuUsage()
        val cpuTemp   = readCpuTemp()
        val clusters  = readClusters()
        val gpuUsage  = readGpuUsage()
        val gpuTemp   = readGpuTemp()
        val (ramUsed, ramTotal) = readRam(context)
        val (batPct, batTemp, batVolt, charging) = readBattery(context)

        SystemSnapshot(
            cpuUsagePct   = cpuUsage,
            cpuTempC      = cpuTemp,
            clusters      = clusters,
            gpuUsagePct   = gpuUsage,
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

            synchronized(cpuLock) {
                val prev  = prevTotal
                val prevI = prevIdle
                prevTotal = total
                prevIdle  = idle

                if (prev == -1L || total == prev) return@synchronized 0f
                val diffTotal = total - prev
                val diffIdle  = idle  - prevI
                if (diffTotal <= 0L) return@synchronized 0f
                (100f * (diffTotal - diffIdle) / diffTotal).coerceIn(0f, 100f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readCpuUsage failed: ${e.message}")
            0f
        }
    }

    private fun readCpuTemp(): Float {
        val (path, divider) = getCpuTempInfo()
        if (path == null) return 0f
        val raw = readFirstLine(path)?.trim()?.toFloatOrNull() ?: return 0f
        val celsius = raw / divider
        return if (celsius in 1f..150f) celsius else 0f
    }

    private fun getCpuTempInfo(): Pair<String?, Int> {
        if (cpuTempDetected) return Pair(cachedCpuTempPath, cachedCpuTempDiv)

        val path = findCpuTempPath()
        val divider = if (path != null) detectTempDivider(path) else 1000

        cachedCpuTempPath = path
        cachedCpuTempDiv  = divider
        cpuTempDetected   = true

        Log.i(TAG, "CPU temp path detected: $path (divider=$divider)")
        return Pair(path, divider)
    }

    private fun findCpuTempPath(): String? {
        val thermalBaseDirs = arrayOf("/sys/class/thermal", "/sys/devices/virtual/thermal")
        val priorityTypes   = arrayOf("cpu_therm", "cpuss", "cpu", "cluster0")

        for (baseDirPath in thermalBaseDirs) {
            val baseDir = File(baseDirPath)
            if (!baseDir.exists() || !baseDir.isDirectory) continue

            for (priorityType in priorityTypes) {
                baseDir.listFiles()?.forEach { zone ->
                    val typeFile = File(zone, "type")
                    val tempFile = File(zone, "temp")
                    if (!typeFile.exists() || !tempFile.exists() || !tempFile.canRead()) return@forEach
                    try {
                        val type = typeFile.readText().trim().lowercase()
                        val isMatch = when {
                            priorityType == "cpuss" && type.startsWith("cpuss") -> true
                            else -> type == priorityType
                        }
                        if (isMatch) return tempFile.absolutePath
                    } catch (_: Exception) {}
                }
            }

            baseDir.listFiles()?.forEach { zone ->
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")
                if (!typeFile.exists() || !tempFile.exists() || !tempFile.canRead()) return@forEach
                try {
                    val type = typeFile.readText().trim().lowercase()
                    if (type.contains("cpu") || type.contains("core") ||
                        type.contains("cluster") || type.contains("tsens") ||
                        type.startsWith("thermal")) {
                        val tempValue = tempFile.readText().trim().toIntOrNull()
                        if (tempValue != null && tempValue in 20000..90000) {
                            return tempFile.absolutePath
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val fallbacks = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/hwmon/hwmon0/temp1_input",
            "/proc/mtktscpu/mtktscpu"
        )
        for (path in fallbacks) {
            if (File(path).let { it.exists() && it.canRead() }) return path
        }
        return null
    }

    private fun detectTempDivider(path: String): Int {
        return try {
            val raw = File(path).readText().trim().toInt()
            when {
                raw in 20000..150000 -> 1000
                raw in 2000..15000   -> 100
                raw in 200..1500     -> 10
                raw in 20..150       -> 1
                else                 -> 1000
            }
        } catch (_: Exception) { 1000 }
    }

    private fun readClusters(): List<ClusterSnapshot> {
        return try {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until cpuCount).map { core ->
                val cur = readFirstLine("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                    ?.trim()?.toLongOrNull() ?: 0L
                val max = readFirstLine("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                    ?.trim()?.toLongOrNull() ?: 0L
                cur to max
            }

            val policyClusters = getPolicyClusters()
            val groups: List<List<Int>> = if (policyClusters.isNotEmpty()) {
                policyClusters
            } else {
                val uniqueMax = freqs.map { it.second }.distinct().filter { it > 0L }.sorted()
                uniqueMax.map { maxFreq ->
                    freqs.mapIndexedNotNull { i, f -> if (f.second == maxFreq) i else null }
                }
            }

            val labels = listOf("Little", "Mid", "Big", "Prime")
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
        } catch (e: Exception) {
            Log.w(TAG, "readClusters failed: ${e.message}")
            emptyList()
        }
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
                    val cores = runCatching {
                        f.bufferedReader().use { it.readLine() ?: "" }
                    }.getOrElse { return@mapNotNull null }
                        .trim().split(Regex("\\s+"))
                        .mapNotNull { it.toIntOrNull() }.sorted()
                    if (cores.isEmpty()) null else cores
                }.orEmpty()
            if (result.isNotEmpty()) cachedPolicyClusters = result
            result
        } catch (e: Exception) {
            Log.w(TAG, "getPolicyClusters failed: ${e.message}")
            emptyList()
        }
    }

    private fun readGpuUsage(): Float {
        val cached = if (gpuUsagePathDetected) cachedGpuUsagePath else null
        if (cached != null) {
            val result = tryReadGpuUsageFromPath(cached)
            if (result > 0f) return result
        }

        val candidatePaths = listOf(
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/kernel/ged/hal/current_freqency",
            "/proc/mtk_mali/utilization",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/devices/platform/gpu/gpubusy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/utilization_pp",
            "/sys/kernel/debug/mali/utilization_gp_pp",
            "/sys/devices/platform/13000000.mali/utilization_gp_pp"
        )

        for (path in candidatePaths) {
            val result = tryReadGpuUsageFromPath(path)
            if (result > 0f) {
                cachedGpuUsagePath = path
                gpuUsagePathDetected = true
                Log.i(TAG, "GPU usage path detected: $path -> $result%")
                return result
            }
        }

        gpuUsagePathDetected = true
        cachedGpuUsagePath = null
        return 0f
    }

    private fun tryReadGpuUsageFromPath(path: String): Float {
        val line = readFirstLine(path)?.trim() ?: return 0f
        if (line.isEmpty()) return 0f

        return when {
            path.contains("ged/hal/gpu_utilization") -> parseGedHalUtilization(line)
            path.contains("mtk_mali/utilization")    -> parseMtkMaliUtilization(line)
            path.contains("kgsl") && path.contains("gpubusy") && !path.contains("percentage") -> parseKgslBusy(line)
            else -> {
                val cleaned = line.replace("%", "").split(Regex("[\\s,]+")).firstOrNull()?.trim() ?: return 0f
                val v = cleaned.toFloatOrNull() ?: return 0f
                if (v in 0f..100f) v else 0f
            }
        }
    }

    private fun parseGedHalUtilization(line: String): Float {
        val parts = line.trim().split(Regex("\\s+"))
        for (part in parts) {
            val v = part.replace("%", "").toFloatOrNull() ?: continue
            if (v in 0f..100f) return v
        }
        return 0f
    }

    private fun parseMtkMaliUtilization(line: String): Float {
        if (line.contains(":")) {
            val afterColon = line.substringAfter(":").trim()
            val v = afterColon.replace("%", "").split(Regex("\\s+")).firstOrNull()?.toFloatOrNull()
            if (v != null && v in 0f..100f) return v
        }
        val v = line.replace("%", "").split(Regex("[\\s,]+")).firstOrNull()?.trim()?.toFloatOrNull()
        return if (v != null && v in 0f..100f) v else 0f
    }

    private fun parseKgslBusy(line: String): Float {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 2) {
            val numerator   = parts[0].toLongOrNull() ?: return 0f
            val denominator = parts[1].toLongOrNull() ?: return 0f
            if (denominator > 0L) {
                val pct = (numerator.toFloat() / denominator.toFloat()) * 100f
                if (pct in 0f..100f) return pct
            }
        }
        val v = parts.firstOrNull()?.toFloatOrNull()
        return if (v != null && v in 0f..100f) v else 0f
    }

    private fun readGpuTemp(): Float {
        val kgslPath = "/sys/class/kgsl/kgsl-3d0/temp"
        val kgslRaw = readFirstLine(kgslPath)?.trim()?.toFloatOrNull()
        if (kgslRaw != null) {
            val c = kgslRaw / 1000f
            if (c in 1f..150f) return c
        }
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
            if (memTotal == 0L) fallbackRam(context)
            else Pair((memTotal - memAvailable) / 1024, memTotal / 1024)
        } catch (_: Exception) { fallbackRam(context) }
    }

    private fun fallbackRam(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Pair((mi.totalMem - mi.availMem) / (1024 * 1024), mi.totalMem / (1024 * 1024))
    }

    private fun readBattery(context: Context): Quadruple<Int, Float, Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Quadruple(0, 0f, 0, false)
        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp    = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volt    = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct     = if (scale > 0) level * 100 / scale else level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        return Quadruple(pct, temp, volt, charging)
    }

    private fun parseMemKb(line: String): Long =
        line.trim().split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L

    private fun readFirstLine(path: String): String? {
        return try { BufferedReader(FileReader(path)).use { it.readLine() } }
        catch (_: IOException) { null }
        catch (_: Exception)   { null }
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
