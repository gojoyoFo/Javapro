package com.javapro.fps

import android.util.Log
import java.io.File
import java.util.LinkedList
import java.util.concurrent.TimeUnit

class RootFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "RootFpsProvider"

    // Layer 1: sysfs cached path
    private var activeFpsPath: String? = null
    private var sysfsScanned  = false

    // Layer 2: SurfaceFlinger latency state
    private var lastLatencyTimestamp: Long = 0L

    // SMA buffer 15 sampel
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 15

    // Shell script untuk latency parsing langsung di shell
    // Kolom ke-2 (index 1) = actual present time dalam nanosecond
    private val LATENCY_SHELL_SCRIPT =
        "dumpsys SurfaceFlinger --latency 2>/dev/null | tail -127 | awk '{print \$2}' | grep -v '^0\$' | grep -v '^\$'"

    init {
        scanSysfsFpsNode()
    }

    override fun getInstantFps(): Float {
        // Layer 1: sysfs node (tercepat, tanpa parse)
        val sysfs = readSysfsFps()
        if (sysfs > 0f) return applySma(sysfs)

        // Layer 2: SurfaceFlinger latency via su shell script
        val latency = readLatencyFps()
        if (latency > 0f) return latency  // SMA sudah diapply di dalam

        // Layer 3: service call 1013 fallback
        val svcCall = readServiceCallFps()
        if (svcCall > 0f) return applySma(svcCall)

        return 0f
    }

    override fun release() {
        smaBuffer.clear()
        lastLatencyTimestamp = 0L
    }

    // ── Layer 1: Sysfs Scanner ────────────────────────────────────────────────

    private fun scanSysfsFpsNode() {
        if (sysfsScanned) return
        sysfsScanned = true
        val patterns = listOf(
            "/sys/class/drm"              to "measured_fps",
            "/sys/class/graphics"         to "measured_fps",
            "/sys/devices/platform/display" to "fps"
        )
        for ((dir, filename) in patterns) {
            File(dir).takeIf { it.exists() }?.walkTopDown()?.maxDepth(4)?.forEach { f ->
                if (f.name == filename && f.canRead()) {
                    val v = f.readText().trim().toFloatOrNull()
                    if (v != null && v > 0f) {
                        activeFpsPath = f.absolutePath
                        Log.i(TAG, "Sysfs fps node: ${f.absolutePath}")
                        return
                    }
                }
            }
        }
        val debug = "/sys/kernel/debug/fps_data"
        if (File(debug).canRead() &&
            File(debug).readText().trim().toFloatOrNull()?.let { it > 0f } == true) {
            activeFpsPath = debug
            Log.i(TAG, "Sysfs fps node (debug): $debug")
        }
    }

    private fun readSysfsFps(): Float {
        val path = activeFpsPath ?: return 0f
        return try {
            File(path).readText().trim().toFloatOrNull()
                ?.coerceIn(1f, maxRefreshRate * 1.05f) ?: 0f
        } catch (_: Exception) { activeFpsPath = null; 0f }
    }

    // ── Layer 2: SurfaceFlinger Latency via su ────────────────────────────────

    private fun readLatencyFps(): Float {
        return try {
            val output = runSu(LATENCY_SHELL_SCRIPT)
            if (output.isBlank()) return 0f
            var lastResult = 0f
            for (line in output.lines()) {
                val currentTimestamp: Long = line.trim().toLongOrNull() ?: continue
                if (currentTimestamp <= 0L || currentTimestamp == Long.MAX_VALUE) continue
                if (lastLatencyTimestamp > 0L) {
                    val delta = currentTimestamp - lastLatencyTimestamp
                    if (delta in 2_000_000L..50_000_000L) {
                        val instantFps = 1_000_000_000f / delta
                        if (instantFps in 1f..maxRefreshRate * 1.05f) {
                            lastResult = applySma(instantFps)
                        }
                    }
                }
                lastLatencyTimestamp = currentTimestamp
            }
            lastResult
        } catch (_: Exception) { 0f }
    }

    // ── Layer 3: service call SurfaceFlinger 1013 ─────────────────────────────

    private fun readServiceCallFps(): Float {
        return try {
            val output = runSu("service call SurfaceFlinger 1013")
            if (output.isBlank()) return 0f
            val hexRegex = Regex("""([0-9a-fA-F]{8})""")
            for (hex in hexRegex.findAll(output).map { it.value }) {
                if (hex == "00000000") continue
                val bits = hex.toLongOrNull(16)?.toInt() ?: continue
                val f = java.lang.Float.intBitsToFloat(bits)
                if (f in 1f..maxRefreshRate * 1.05f) return f
            }
            0f
        } catch (_: Exception) { 0f }
    }

    // ── Shell helper ──────────────────────────────────────────────────────────

    private fun runSu(cmd: String): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            output.trim()
        } catch (_: Exception) { "" }
        finally {
            process?.inputStream?.runCatching { close() }
            process?.errorStream?.runCatching { close() }
            process?.outputStream?.runCatching { close() }
            process?.destroy()
        }
    }

    // ── SMA ───────────────────────────────────────────────────────────────────

    private fun applySma(raw: Float): Float {
        smaBuffer.addLast(raw)
        if (smaBuffer.size > SMA_SIZE) smaBuffer.removeFirst()
        return smaBuffer.average().toFloat()
    }
}
