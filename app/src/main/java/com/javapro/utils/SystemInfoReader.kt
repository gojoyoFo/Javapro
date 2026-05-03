package com.javapro.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

// ── JNI bridge ────────────────────────────────────────────────────────────────

object SystemInfoNative {

    private const val TAG = "SystemInfoNative"

    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("system_info")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "system_info native lib not found: ${e.message}")
            false
        }
    }

    // CPU usage 0-100%
    external fun getCpuUsage(): Float

    // Flat int array [cur0_kHz, max0_kHz, cur1_kHz, max1_kHz, ...]
    external fun getCpuFreqs(coreCount: Int): IntArray

    // "0,1,2|3,4,5|6,7" — pipe-separated clusters, comma-separated cores
    external fun getCpuPolicyClusters(): String

    external fun getCpuTemp(): Float

    external fun getGpuUsage(): Float

    external fun getGpuTemp(): Float

    // [usedMb, totalMb]
    external fun getRamMb(): LongArray
}

// ── SystemInfoReader — wrapper yang panggil native ────────────────────────────
// Drop-in replacement untuk SystemInfoReader.kt lama.
// Battery tetap pakai Android API karena tidak butuh sysfs.

object SystemInfoReader {

    private const val TAG = "SystemInfoReader"

    private val nativeAvailable: Boolean by lazy { SystemInfoNative.load() }

    // Fallback Kotlin untuk CPU usage kalau native tidak tersedia
    private val cpuLock = Any()
    private var prevIdle  = -1L
    private var prevTotal = -1L

    suspend fun read(context: Context): SystemSnapshot = withContext(Dispatchers.IO) {
        val useNative = nativeAvailable

        val cpuUsage = if (useNative) SystemInfoNative.getCpuUsage()
                       else readCpuUsageFallback()

        val cpuTemp  = if (useNative) SystemInfoNative.getCpuTemp() else 0f

        val clusters = if (useNative) readClustersNative() else emptyList()

        val gpuUsage = if (useNative) SystemInfoNative.getGpuUsage() else 0f

        val gpuTemp  = if (useNative) SystemInfoNative.getGpuTemp() else 0f

        val (ramUsed, ramTotal) = if (useNative) {
            val arr = SystemInfoNative.getRamMb()
            arr[0] to arr[1]
        } else readRamFallback(context)

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

    // ── Cluster parsing dari native string ────────────────────────────────────

    private fun readClustersNative(): List<ClusterSnapshot> {
        return try {
            val coreCount = Runtime.getRuntime().availableProcessors()
            val freqs     = SystemInfoNative.getCpuFreqs(coreCount)  // flat [cur,max,cur,max,...]
            val policyStr = SystemInfoNative.getCpuPolicyClusters()  // "0,1|2,3|4,5,6,7"

            val groups: List<List<Int>> = if (policyStr.isNotBlank()) {
                policyStr.split("|").mapNotNull { group ->
                    val cores = group.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()
                    if (cores.isEmpty()) null else cores
                }
            } else {
                // Fallback: grup berdasarkan max freq yang sama
                val maxFreqs = (0 until coreCount).map { freqs.getOrNull(it * 2 + 1) ?: 0 }
                val uniqueMax = maxFreqs.filter { it > 0 }.distinct().sorted()
                uniqueMax.map { mf -> maxFreqs.mapIndexedNotNull { i, f -> if (f == mf) i else null } }
            }

            val labels = listOf("Little", "Mid", "Big", "Prime")
            groups.mapIndexed { idx, cores ->
                val curValues = cores.mapNotNull {
                    freqs.getOrNull(it * 2)?.toLong()?.takeIf { v -> v > 0L }
                }
                val maxValues = cores.mapNotNull {
                    freqs.getOrNull(it * 2 + 1)?.toLong()?.takeIf { v -> v > 0L }
                }
                val avgCur  = if (curValues.isNotEmpty()) curValues.average().toLong() else 0L
                val maxFreq = maxValues.maxOrNull() ?: 0L
                ClusterSnapshot(
                    label      = labels.getOrElse(idx) { "Cluster ${idx + 1}" },
                    cores      = cores,
                    curFreqMhz = (avgCur  / 1000).toInt(),
                    maxFreqMhz = (maxFreq / 1000).toInt()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "readClustersNative: ${e.message}")
            emptyList()
        }
    }

    // ── Fallback Kotlin (non-root, /proc/stat tersedia tanpa permission) ──────

    private fun readCpuUsageFallback(): Float {
        return try {
            val line = java.io.File("/proc/stat").bufferedReader().readLine() ?: return 0f
            if (!line.startsWith("cpu ")) return 0f
            val p = line.trim().split("\\s+".toRegex())
            if (p.size < 8) return 0f
            val user    = p[1].toLong()
            val nice    = p[2].toLong()
            val system  = p[3].toLong()
            val idle    = p[4].toLong()
            val iowait  = p[5].toLong()
            val irq     = p[6].toLong()
            val softirq = p[7].toLong()
            val steal   = if (p.size > 8) p[8].toLong() else 0L
            val total   = user + nice + system + idle + iowait + irq + softirq + steal
            synchronized(cpuLock) {
                val prevT = prevTotal; val prevI = prevIdle
                prevTotal = total; prevIdle = idle
                if (prevT == -1L || total == prevT) return@synchronized 0f
                val dT = total - prevT; val dI = idle - prevI
                if (dT <= 0L) return@synchronized 0f
                (100f * (dT - dI) / dT).coerceIn(0f, 100f)
            }
        } catch (_: Exception) { 0f }
    }

    private fun readRamFallback(context: Context): Pair<Long, Long> {
        return try {
            var memTotal = 0L; var memAvail = 0L
            java.io.File("/proc/meminfo").bufferedReader().use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    when {
                        l.startsWith("MemTotal:")     -> memTotal = l.trim().split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                        l.startsWith("MemAvailable:") -> memAvail  = l.trim().split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                    if (memTotal > 0 && memAvail > 0) return@use
                }
            }
            if (memTotal == 0L) fallbackRamAm(context)
            else Pair((memTotal - memAvail) / 1024, memTotal / 1024)
        } catch (_: Exception) { fallbackRamAm(context) }
    }

    private fun fallbackRamAm(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Pair((mi.totalMem - mi.availMem) / (1024 * 1024), mi.totalMem / (1024 * 1024))
    }

    // ── Battery (API, tidak butuh sysfs) ──────────────────────────────────────

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

    private data class Quadruple<A,B,C,D>(val a: A, val b: B, val c: C, val d: D)
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component1() = a
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component2() = b
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component3() = c
    private operator fun <A,B,C,D> Quadruple<A,B,C,D>.component4() = d
}
