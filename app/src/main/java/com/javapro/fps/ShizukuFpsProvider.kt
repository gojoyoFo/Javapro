package com.javapro.fps

import android.util.Log
import com.javapro.utils.ShizukuManager
import java.io.File
import java.util.LinkedList
import java.util.concurrent.TimeUnit

class ShizukuFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "ShizukuFpsProvider"

    // Layer 1: sysfs cached path
    private var activeFpsPath: String? = null
    private var sysfsScanned  = false

    // Layer 2: SurfaceFlinger latency state
    private var lastLatencyTimestamp: Long = 0L

    // Timestats delta state (non-root fallback)
    private val tsLayerSnapshot = mutableMapOf<String, Long>()
    private var tsLastMs = 0L
    private val TS_LAYER_SKIP = listOf(
        "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
        "com.javapro", "WallpaperSurface", "pip-dismiss", "Splash Screen",
        "ShellDropTarget", "PointerLocation", "mouse pointer"
    )

    // SMA buffer 15 sampel
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 15

    // Shell script untuk latency parsing langsung di shell
    private val LATENCY_SHELL_SCRIPT =
        "dumpsys SurfaceFlinger --latency 2>/dev/null | tail -127 | awk '{print \$2}' | grep -v '^0\$' | grep -v '^\$'"

    init {
        scanSysfsFpsNode()
    }

    override fun getInstantFps(): Float {
        // Layer 1: sysfs node
        val sysfs = readSysfsFps()
        if (sysfs > 0f) return applySma(sysfs)

        // Layer 2: SurfaceFlinger latency via Shizuku atau sh
        val latency = readLatencyFps()
        if (latency > 0f) return latency  // SMA sudah diapply di dalam

        // Layer 3 (non-root fallback): timestats delta
        val ts = readFpsTimestats()
        if (ts > 0f) return applySma(ts)

        return 0f
    }

    override fun release() {
        smaBuffer.clear()
        lastLatencyTimestamp = 0L
        tsLayerSnapshot.clear()
    }

    // ── Layer 1: Sysfs Scanner ────────────────────────────────────────────────

    private fun scanSysfsFpsNode() {
        if (sysfsScanned) return
        sysfsScanned = true
        val patterns = listOf(
            "/sys/class/drm"                to "measured_fps",
            "/sys/class/graphics"           to "measured_fps",
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
        }
    }

    private fun readSysfsFps(): Float {
        val path = activeFpsPath ?: return 0f
        return try {
            File(path).readText().trim().toFloatOrNull()
                ?.coerceIn(1f, maxRefreshRate * 1.05f) ?: 0f
        } catch (_: Exception) { activeFpsPath = null; 0f }
    }

    // ── Layer 2: SurfaceFlinger Latency via Shizuku / sh ─────────────────────

    private fun readLatencyFps(): Float {
        return try {
            val output = runShell(LATENCY_SHELL_SCRIPT)
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

    // ── Timestats delta (non-root, tidak butuh permission khusus) ────────────

    private fun readFpsTimestats(): Float {
        return try {
            val output = runShell("dumpsys SurfaceFlinger --timestats -dump")
            if (output.isBlank()) return 0f
            val nowMs   = System.currentTimeMillis()
            val elapsed = if (tsLastMs > 0L) nowMs - tsLastMs else 0L
            tsLastMs    = nowMs
            val frameMap = mutableMapOf<String, Long>()
            var cur: String? = null
            for (line in output.lines()) {
                val t = line.trim()
                when {
                    t.startsWith("layerName = ") -> cur = t.removePrefix("layerName = ")
                    t.startsWith("Layer: ")      -> cur = t.removePrefix("Layer: ")
                    t.startsWith("totalFrames = ") && cur != null -> {
                        t.removePrefix("totalFrames = ").trim().toLongOrNull()
                            ?.takeIf { it > 0L }?.let { frameMap[cur!!] = it }
                        cur = null
                    }
                }
            }
            if (frameMap.isEmpty()) return 0f
            val best = frameMap.entries
                .filter { (l, _) -> TS_LAYER_SKIP.none { s -> l.contains(s, ignoreCase = true) } }
                .maxByOrNull { it.value }?.key ?: return 0f
            val current = frameMap[best] ?: return 0f
            val prev    = tsLayerSnapshot[best]
            tsLayerSnapshot[best] = current
            if (prev == null || elapsed <= 0L) return 0f
            val delta = current - prev
            if (delta <= 0L) return 0f
            val fps = delta * 1000f / elapsed
            if (fps in 1f..maxRefreshRate * 1.05f) fps else 0f
        } catch (_: Exception) { 0f }
    }

    // ── Shell helper: Shizuku kalau tersedia, fallback sh ─────────────────────

    private fun runShell(cmd: String): String {
        if (ShizukuManager.isAvailable()) {
            val result = ShizukuManager.runCommand(cmd)
            if (result.isNotBlank()) return result
        }
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
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
