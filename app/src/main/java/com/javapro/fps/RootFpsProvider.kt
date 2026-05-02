package com.javapro.fps

import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.LinkedList
import java.util.concurrent.TimeUnit

/**
 * RootFpsProvider — Fixed & Optimized
 *
 * Fixes:
 * 1. Anti-deadlock: stream draining + 500ms hard timeout + process.destroy()
 * 2. Smart layer targeting: cari SurfaceView/Sprite dulu, baru fallback ke global
 * 3. Synchronized su access: SU_LOCK mencegah race condition dengan SystemInfoReader
 * 4. Sysfs fallback otomatis jika dumpsys FPS == 0
 */
class RootFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "RootFpsProvider"

    // ── Layer 1: sysfs cached path ────────────────────────────────────────────
    @Volatile private var activeFpsPath: String? = null
    @Volatile private var sysfsScanned  = false

    // ── Layer 2: SurfaceFlinger latency state ─────────────────────────────────
    private var lastLatencyTimestamp: Long = 0L

    // SMA buffer 12 sampel (lebih responsif dari 15)
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 12

    // Cache nama layer target agar tidak re-scan setiap tick
    @Volatile private var cachedGameLayer: String? = null
    private var layerCacheMs: Long = 0L
    private val LAYER_CACHE_TTL_MS = 3_000L  // refresh setiap 3 detik

    companion object {
        /**
         * Global lock untuk semua akses `su`.
         * SystemInfoReader TIDAK boleh buka su bersamaan dengan FpsProvider.
         * Declare di companion agar satu lock untuk seluruh proses.
         */
        @JvmStatic val SU_LOCK = Any()

        private const val SHELL_TIMEOUT_MS = 500L  // hard kill jika tidak ada output dalam 500ms
    }

    init {
        scanSysfsFpsNode()
    }

    override fun getInstantFps(): Float {
        // Layer 1: sysfs node (tercepat, tanpa shell)
        val sysfs = readSysfsFps()
        if (sysfs > 0f) return applySma(sysfs)

        // Layer 2: SurfaceFlinger latency via su (Smart Layer Targeting)
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
        cachedGameLayer = null
    }

    // ── Layer 1: Sysfs Scanner ────────────────────────────────────────────────

    private fun scanSysfsFpsNode() {
        if (sysfsScanned) return
        sysfsScanned = true
        val patterns = listOf(
            "/sys/class/drm"               to "measured_fps",
            "/sys/class/graphics"          to "measured_fps",
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

    // ── Layer 2: Smart Layer Targeting + SurfaceFlinger Latency ──────────────

    /**
     * Resolve target layer:
     * - Cari layer SurfaceView atau Sprite (game engine layers)
     * - Cache selama LAYER_CACHE_TTL_MS agar tidak re-scan setiap tick
     * - Fallback ke string kosong (global) jika tidak ditemukan
     */
    private fun resolveGameLayer(): String {
        val now = System.currentTimeMillis()
        val cached = cachedGameLayer
        if (cached != null && (now - layerCacheMs) < LAYER_CACHE_TTL_MS) {
            return cached
        }

        val listOutput = runSu("dumpsys SurfaceFlinger --list 2>/dev/null")
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
            Log.d(TAG, "Smart layer: no game layer found, using global")
        }
        return target
    }

    private fun readLatencyFps(): Float {
        return try {
            val layer  = resolveGameLayer()
            // Kutip layer name agar aman dari spasi; jika kosong, --latency tanpa arg = global
            val layerArg = if (layer.isNotEmpty()) "\"$layer\"" else ""
            val script = buildLatencyScript(layerArg)

            var output = runSu(script)

            // Jika hasil dengan layer spesifik kosong/0, fallback ke global
            if (output.isBlank() && layer.isNotEmpty()) {
                Log.d(TAG, "Smart layer output empty, fallback to global")
                cachedGameLayer = ""   // reset cache agar next tick global dulu
                output = runSu(buildLatencyScript(""))
            }

            if (output.isBlank()) return 0f

            parseLatencyOutput(output)
        } catch (_: Exception) { 0f }
    }

    private fun buildLatencyScript(layerArg: String): String {
        // One-liner: ambil kolom ke-2 (present timestamp ns), filter 0 dan kosong
        return "dumpsys SurfaceFlinger --latency $layerArg 2>/dev/null" +
               " | tail -127 | awk '{print \$2}' | grep -v '^0\$' | grep -v '^\$'"
    }

    private fun parseLatencyOutput(output: String): Float {
        var lastResult = 0f
        for (line in output.lines()) {
            val currentTimestamp = line.trim().toLongOrNull() ?: continue
            if (currentTimestamp <= 0L || currentTimestamp == Long.MAX_VALUE) continue
            if (lastLatencyTimestamp > 0L) {
                val delta = currentTimestamp - lastLatencyTimestamp
                // Valid range: 2ms – 50ms (20fps – 500fps)
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

    // ── Shell helper: Anti-Deadlock + Synchronized SU ────────────────────────

    /**
     * runSu — Robust shell execution:
     * - synchronized(SU_LOCK): tidak ada dua thread yang buka su bersamaan
     * - Hard timeout 500ms: jika tidak ada output, stream di-drain paksa lalu destroy
     * - stderr selalu di-drain di thread terpisah untuk mencegah buffer blocking
     */
    private fun runSu(cmd: String): String {
        return synchronized(SU_LOCK) {
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val p = process

                // Drain stderr di background thread agar tidak block stdout
                val errThread = Thread { drainStream(p.errorStream) }
                    .also { it.isDaemon = true; it.start() }

                // Baca stdout dengan timeout ketat
                val output = readWithTimeout(p.inputStream, SHELL_TIMEOUT_MS)

                // Tunggu proses selesai, max 200ms tambahan
                val exited = p.waitFor(200, TimeUnit.MILLISECONDS)
                if (!exited) {
                    Log.w(TAG, "runSu timeout, killing process")
                    p.destroyForcibly()
                }

                errThread.join(100)
                output.trim()
            } catch (e: Exception) {
                Log.w(TAG, "runSu error: ${e.message}")
                ""
            } finally {
                process?.runCatching { destroyForcibly() }
            }
        }
    }

    /**
     * Baca InputStream dengan hard timeout.
     * Jika belum selesai dalam [timeoutMs] ms, kembalikan apa yang sudah terbaca.
     */
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

    /** Drain stream ke /dev/null agar pipe buffer tidak block proses. */
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
