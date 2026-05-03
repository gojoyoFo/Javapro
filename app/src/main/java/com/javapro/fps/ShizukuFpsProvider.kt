package com.javapro.fps

import android.util.Log
import com.javapro.utils.ShizukuManager
import java.io.InputStream
import java.util.LinkedList
import java.util.concurrent.TimeUnit

/**
 * ShizukuFpsProvider — Non-Root FPS via Shizuku
 *
 * Strategi berlapis:
 *  L1 → service call SurfaceFlinger 1013 (Hex Parcel → IEEE-754 Float)
 *       Paling akurat, langsung dapat FPS float dari SurfaceFlinger
 *  L2 → dumpsys SurfaceFlinger --latency (Smart Layer Targeting)
 *       Scene-delta: timestamp ns → FPS hitung dari delta
 *  L3 → dumpsys SurfaceFlinger --timestats (frame counter delta)
 *       Fallback terakhir kalau L1 dan L2 gagal
 *
 * Semua command via ShizukuManager, fallback ke sh kalau Shizuku tidak tersedia.
 * Tidak ada File() direct read, tidak ada hiddenapi, tidak ada avc:denied.
 */
class ShizukuFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "ShizukuFpsProvider"

    // ── State ─────────────────────────────────────────────────────────────────
    private var lastLatencyTimestamp: Long = 0L
    private val tsLayerSnapshot = mutableMapOf<String, Long>()
    private var tsLastMs = 0L

    // Smart layer cache
    @Volatile private var cachedGameLayer: String? = null
    private var layerCacheMs: Long = 0L
    private val LAYER_CACHE_TTL_MS = 3_000L

    // SMA 12 sampel
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 12

    private val LAYER_SKIP = listOf(
        "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
        "com.javapro", "WallpaperSurface", "pip-dismiss", "Splash Screen",
        "ShellDropTarget", "PointerLocation", "mouse pointer"
    )

    companion object {
        private const val SHELL_TIMEOUT_MS = 600L
    }

    // ── Public API ────────────────────────────────────────────────────────────

    override fun getInstantFps(): Float {
        // L1: service call SurfaceFlinger 1013
        val svcFps = readServiceCall1013()
        if (svcFps > 0f) return applySma(svcFps)

        // L2: --latency Smart Layer Targeting
        val latencyFps = readLatencyFps()
        if (latencyFps > 0f) return latencyFps

        // L3: --timestats delta
        val tsFps = readTimestats()
        if (tsFps > 0f) return applySma(tsFps)

        return 0f
    }

    override fun release() {
        smaBuffer.clear()
        lastLatencyTimestamp = 0L
        tsLayerSnapshot.clear()
        cachedGameLayer = null
    }

    // ── L1: service call SurfaceFlinger 1013 ─────────────────────────────────

    private fun readServiceCall1013(): Float {
        return try {
            val output = runShell("service call SurfaceFlinger 1013")
            if (output.isBlank()) return 0f
            parseHexParcel(output)
        } catch (_: Exception) { 0f }
    }

    private fun parseHexParcel(output: String): Float {
        val hexRegex = Regex("""([0-9a-fA-F]{8})""")
        for (hex in hexRegex.findAll(output).map { it.value }) {
            if (hex.equals("00000000", ignoreCase = true)) continue
            val bits = hex.toLongOrNull(16)?.toInt() ?: continue
            val f    = java.lang.Float.intBitsToFloat(bits)
            if (f.isFinite() && f in 1f..maxRefreshRate * 1.05f) {
                Log.d(TAG, "SvcCall 1013 → $f Hz")
                return f
            }
        }
        return 0f
    }

    // ── L2: Smart Layer Targeting + SurfaceFlinger --latency ─────────────────

    private fun resolveGameLayer(): String {
        val now = System.currentTimeMillis()
        val cached = cachedGameLayer
        if (cached != null && (now - layerCacheMs) < LAYER_CACHE_TTL_MS) return cached

        val listOutput = runShell("dumpsys SurfaceFlinger --list 2>/dev/null")
        val target = listOutput.lines()
            .map { it.trim() }
            .filter { line -> LAYER_SKIP.none { skip -> line.contains(skip, ignoreCase = true) } }
            .firstOrNull { line ->
                line.contains("SurfaceView",  ignoreCase = true) ||
                line.contains("Sprite",       ignoreCase = true) ||
                line.contains("UnityMain",    ignoreCase = true) ||
                line.contains("GameActivity", ignoreCase = true) ||
                line.contains("RenderThread", ignoreCase = true)
            } ?: ""

        cachedGameLayer = target
        layerCacheMs    = now
        if (target.isNotEmpty()) Log.i(TAG, "Game layer: $target")
        return target
    }

    private fun readLatencyFps(): Float {
        return try {
            val layer    = resolveGameLayer()
            val layerArg = if (layer.isNotEmpty()) "\"$layer\"" else ""
            val script   = buildLatencyScript(layerArg)
            var output   = runShell(script)

            if (output.isBlank() && layer.isNotEmpty()) {
                Log.d(TAG, "Layer latency empty, fallback global")
                cachedGameLayer = ""
                output = runShell(buildLatencyScript(""))
            }
            if (output.isBlank()) return 0f
            parseLatencyOutput(output)
        } catch (_: Exception) { 0f }
    }

    private fun buildLatencyScript(layerArg: String): String =
        "dumpsys SurfaceFlinger --latency-clear $layerArg >/dev/null 2>&1;" +
        " dumpsys SurfaceFlinger --latency $layerArg 2>/dev/null" +
        " | awk '{print \$2}' | grep -v '^0\$' | grep -v '^\$' | tail -64"

    private fun parseLatencyOutput(output: String): Float {
        val timestamps = output.lines()
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L && it != Long.MAX_VALUE }

        if (timestamps.size < 5) return 0f

        val tMin = timestamps.first()
        val tMax = timestamps.last()
        val rangeNs = tMax - tMin
        if (rangeNs <= 0) return 0f

        val intervalNs = rangeNs.toFloat() / (timestamps.size - 1)
        val fps = (1_000_000_000f / intervalNs).coerceIn(1f, maxRefreshRate * 1.05f)
        return applySma(fps)
    }

    // ── L3: Timestats delta ───────────────────────────────────────────────────

    private fun readTimestats(): Float {
        return try {
            val output = runShell("dumpsys SurfaceFlinger --timestats -dump 2>/dev/null")
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
                .filter { (l, _) -> LAYER_SKIP.none { s -> l.contains(s, ignoreCase = true) } }
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

    // ── Shell helper ──────────────────────────────────────────────────────────

    private fun runShell(cmd: String): String {
        // Prioritas Shizuku
        if (ShizukuManager.isAvailable()) {
            val result = runCatching { ShizukuManager.runCommand(cmd) }.getOrElse { "" }
            if (!result.isNullOrBlank()) return result
        }

        // Fallback sh dengan anti-deadlock
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val p = process

            Thread { drainStream(p.errorStream) }
                .also { it.isDaemon = true; it.start() }

            val output = readWithTimeout(p.inputStream, SHELL_TIMEOUT_MS)
            val exited = p.waitFor(200, TimeUnit.MILLISECONDS)
            if (!exited) p.destroyForcibly()
            output.trim()
        } catch (e: Exception) {
            Log.w(TAG, "runShell error: ${e.message}")
            ""
        } finally {
            process?.runCatching { destroyForcibly() }
        }
    }

    private fun readWithTimeout(stream: InputStream, timeoutMs: Long): String {
        val sb       = StringBuilder()
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
        try { val buf = ByteArray(1024); while (stream.read(buf) >= 0) {} }
        catch (_: Exception) {}
    }

    // ── SMA ───────────────────────────────────────────────────────────────────

    @Synchronized
    private fun applySma(raw: Float): Float {
        smaBuffer.addLast(raw)
        if (smaBuffer.size > SMA_SIZE) smaBuffer.removeFirst()
        return smaBuffer.average().toFloat()
    }
}
