package com.javapro.fps

import android.util.Log
import com.javapro.utils.ShizukuManager
import java.io.File
import java.io.InputStream
import java.util.LinkedList
import java.util.concurrent.TimeUnit

/**
 * ShizukuFpsProvider — Fixed & Optimized
 *
 * Fixes:
 * 1. FPS stuck 61Hz: ganti readFpsTimestats() yg hanya baca global refresh rate
 *    → sekarang gunakan Smart Layer Targeting via --latency pada layer game spesifik
 * 2. Anti-deadlock: stream draining + 500ms hard timeout + destroyForcibly
 * 3. Timestats sebagai Layer 3 fallback tetap ada (delta frame counter per layer)
 */
class ShizukuFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "ShizukuFpsProvider"

    // ── Layer 1: sysfs cached path ────────────────────────────────────────────
    @Volatile private var activeFpsPath: String? = null
    @Volatile private var sysfsScanned  = false

    // ── Layer 2: SurfaceFlinger latency state ─────────────────────────────────
    private var lastLatencyTimestamp: Long = 0L

    // ── Layer 3: Timestats delta state ────────────────────────────────────────
    private val tsLayerSnapshot = mutableMapOf<String, Long>()
    private var tsLastMs = 0L
    private val TS_LAYER_SKIP = listOf(
        "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
        "com.javapro", "WallpaperSurface", "pip-dismiss", "Splash Screen",
        "ShellDropTarget", "PointerLocation", "mouse pointer"
    )

    // SMA buffer 12 sampel
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 12

    // Cache layer game agar tidak re-scan setiap tick
    @Volatile private var cachedGameLayer: String? = null
    private var layerCacheMs: Long = 0L
    private val LAYER_CACHE_TTL_MS = 3_000L

    companion object {
        private const val SHELL_TIMEOUT_MS = 500L
    }

    init {
        scanSysfsFpsNode()
    }

    override fun getInstantFps(): Float {
        // Layer 1: sysfs node (paling cepat)
        val sysfs = readSysfsFps()
        if (sysfs > 0f) return applySma(sysfs)

        // Layer 2: SurfaceFlinger latency – Smart Layer Targeting
        val latency = readLatencyFps()
        if (latency > 0f) return latency

        // Layer 3: timestats delta (fallback non-root)
        val ts = readFpsTimestats()
        if (ts > 0f) return applySma(ts)

        return 0f
    }

    override fun release() {
        smaBuffer.clear()
        lastLatencyTimestamp = 0L
        tsLayerSnapshot.clear()
        cachedGameLayer = null
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

    // ── Layer 2: Smart Layer Targeting + SurfaceFlinger Latency ──────────────

    /**
     * Resolve target layer:
     * Prioritas: SurfaceView > Sprite > UnityMain > GameActivity > global
     * Cache TTL 3 detik agar tidak re-scan setiap tick.
     */
    private fun resolveGameLayer(): String {
        val now = System.currentTimeMillis()
        val cached = cachedGameLayer
        if (cached != null && (now - layerCacheMs) < LAYER_CACHE_TTL_MS) {
            return cached
        }

        val listOutput = runShell("dumpsys SurfaceFlinger --list 2>/dev/null")
        val target = listOutput.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.contains("SurfaceView", ignoreCase = true) ||
                line.contains("Sprite",      ignoreCase = true) ||
                line.contains("UnityMain",   ignoreCase = true) ||
                line.contains("GameActivity",ignoreCase = true)
            } ?: ""

        cachedGameLayer = target
        layerCacheMs    = now
        if (target.isNotEmpty()) {
            Log.i(TAG, "Smart layer resolved: $target")
        } else {
            Log.d(TAG, "Smart layer: no game layer, using global")
        }
        return target
    }

    private fun readLatencyFps(): Float {
        return try {
            val layer    = resolveGameLayer()
            val layerArg = if (layer.isNotEmpty()) "\"$layer\"" else ""
            val script   = buildLatencyScript(layerArg)

            var output = runShell(script)

            // Fallback ke global jika layer spesifik tidak menghasilkan data
            if (output.isBlank() && layer.isNotEmpty()) {
                Log.d(TAG, "Smart layer empty, fallback to global")
                cachedGameLayer = ""
                output = runShell(buildLatencyScript(""))
            }

            if (output.isBlank()) return 0f
            parseLatencyOutput(output)
        } catch (_: Exception) { 0f }
    }

    private fun buildLatencyScript(layerArg: String): String =
        "dumpsys SurfaceFlinger --latency $layerArg 2>/dev/null" +
        " | tail -127 | awk '{print \$2}' | grep -v '^0\$' | grep -v '^\$'"

    private fun parseLatencyOutput(output: String): Float {
        var lastResult = 0f
        for (line in output.lines()) {
            val currentTimestamp = line.trim().toLongOrNull() ?: continue
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
        return lastResult
    }

    // ── Layer 3: Timestats delta (non-root fallback) ──────────────────────────
    // Membaca totalFrames per-layer dan menghitung delta antar polling interval.
    // TIDAK lagi digunakan sebagai primary karena stuck di refresh rate global.
    // Sekarang hanya dipakai jika Layer 1 dan 2 gagal.

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
            // Pilih layer dengan frame count tertinggi, bukan layer sistem
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

    // ── Shell helper: Anti-Deadlock ───────────────────────────────────────────

    /**
     * runShell — Gunakan Shizuku kalau tersedia, fallback ke `sh`.
     * Kedua path punya hard timeout 500ms + aggressive stream drain.
     */
    private fun runShell(cmd: String): String {
        // Coba Shizuku dulu (lebih cepat, non-blocking)
        if (ShizukuManager.isAvailable()) {
            val result = runCatching { ShizukuManager.runCommand(cmd) }.getOrElse { "" }
            if (result.isNotBlank()) return result
        }

        // Fallback ke sh dengan anti-deadlock
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val p = process

            // Drain stderr di background agar tidak block stdout pipe
            val errThread = Thread { drainStream(p.errorStream) }
                .also { it.isDaemon = true; it.start() }

            val output = readWithTimeout(p.inputStream, SHELL_TIMEOUT_MS)

            val exited = p.waitFor(200, TimeUnit.MILLISECONDS)
            if (!exited) {
                Log.w(TAG, "runShell timeout, killing process")
                p.destroyForcibly()
            }

            errThread.join(100)
            output.trim()
        } catch (e: Exception) {
            Log.w(TAG, "runShell error: ${e.message}")
            ""
        } finally {
            process?.runCatching { destroyForcibly() }
        }
    }

    private fun readWithTimeout(stream: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val available = stream.available()
                if (available > 0) {
                    val read = stream.read(buf, 0, minOf(available, buf.size))
                    if (read < 0) break
                    sb.append(String(buf, 0, read))
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun drainStream(stream: InputStream) {
        try {
            val buf = ByteArray(1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
            }
        } catch (_: Exception) {}
    }

    // ── SMA ───────────────────────────────────────────────────────────────────

    private fun applySma(raw: Float): Float {
        smaBuffer.addLast(raw)
        if (smaBuffer.size > SMA_SIZE) smaBuffer.removeFirst()
        return smaBuffer.average().toFloat()
    }
}
