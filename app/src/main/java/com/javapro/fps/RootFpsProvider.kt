package com.javapro.fps

import android.util.Log
import java.io.File
import java.util.LinkedList

class RootFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "RootFpsProvider"

    // ── Persistent shell ──────────────────────────────────────────────────────
    private val shell = PersistentSuShell()

    // ── Layer 1: sysfs cached path ────────────────────────────────────────────
    @Volatile private var activeFpsPath: String? = null
    @Volatile private var sysfsScanned  = false

    // ── Layer 2: SurfaceFlinger latency ───────────────────────────────────────
    private var lastLatencyTimestamp: Long = 0L

    // Cache layer game (di-refresh setiap LAYER_CACHE_TTL_MS)
    @Volatile private var cachedGameLayer: String? = null   // null = belum pernah di-resolve
    private var layerCacheMs: Long = 0L
    private val LAYER_CACHE_TTL_MS = 3_000L

    // Reconnect debounce
    private var lastReconnectMs: Long = 0L
    private val RECONNECT_COOLDOWN_MS = 5_000L

    // SMA buffer 12 sampel
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 12

    // Dollar sign literal — agar tidak confuse Kotlin string template vs shell variable
    private val D = "\$"

    companion object {
        /** Dipakai oleh FpsService.hasRoot() sebagai shared lock. */
        @JvmStatic val SU_LOCK = Any()
    }

    init {
        scanSysfsFpsNode()
        connectShell()
    }

    override fun getInstantFps(): Float {
        // Layer 1: sysfs node (tanpa shell, paling cepat)
        val sysfs = readSysfsFps()
        if (sysfs > 0f) return applySma(sysfs)

        // Pastikan shell hidup sebelum lanjut
        ensureShellAlive()

        // Layer 2: combined script via persistent shell
        val latency = readLatencyFps()
        if (latency > 0f) return latency

        // Layer 3: service call 1013 via persistent shell
        val svcCall = readServiceCallFps()
        if (svcCall > 0f) return applySma(svcCall)

        return 0f
    }

    override fun release() {
        smaBuffer.clear()
        lastLatencyTimestamp = 0L
        cachedGameLayer = null
        shell.close()
    }

    // ── Shell lifecycle ───────────────────────────────────────────────────────

    private fun connectShell() {
        try {
            val ok = shell.connect()
            if (!ok) Log.w(TAG, "Shell connect failed, will retry on next tick")
        } catch (e: Exception) {
            Log.e(TAG, "connectShell exception: ${e.message}")
        }
    }

    private fun ensureShellAlive() {
        if (shell.connected && shell.isAlive()) return
        val now = System.currentTimeMillis()
        if (now - lastReconnectMs < RECONNECT_COOLDOWN_MS) return
        Log.w(TAG, "Shell dead, reconnecting...")
        lastReconnectMs = now
        connectShell()
    }

    // ── Layer 1: Sysfs FPS node ───────────────────────────────────────────────

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

    // ── Layer 2: Smart Layer Targeting + SurfaceFlinger --latency ─────────────

    /**
     * Combined one-shot script per tick:
     *
     * Skenario A — layer cache VALID (setiap tick, ~1 detik):
     *   Kirim hanya satu perintah --latency → paling cepat, tidak ada overhead --list.
     *
     * Skenario B — layer cache EXPIRED (setiap ~3 detik):
     *   Kirim script gabungan:
     *     1. Detect layer via --list (grep SurfaceView/Sprite/Unity)
     *     2. echo nama layer → baris pertama output
     *     3. Jalankan --latency pada layer tsb → baris 2+ output
     *   Pecah output di Kotlin berdasarkan baris pertama vs sisanya.
     */
    private fun readLatencyFps(): Float {
        if (!shell.connected) return 0f

        val now = System.currentTimeMillis()
        val cacheValid = cachedGameLayer != null &&
                         (now - layerCacheMs) < LAYER_CACHE_TTL_MS

        val timestampOutput: String

        if (cacheValid) {
            // ── Skenario A: cache valid, kirim --latency saja ──────────────
            val layer    = cachedGameLayer!!
            val layerArg = if (layer.isNotEmpty()) "\"$layer\"" else ""
            timestampOutput = shell.execute(latencyOnlyScript(layerArg), timeoutMs = 1500L)
        } else {
            // ── Skenario B: cache expired, combined script ──────────────────
            val raw = shell.execute(combinedLayerAndLatencyScript(), timeoutMs = 1800L)
            val lines = raw.lines()

            // Baris pertama = nama layer (hasil echo "$TARGET")
            val layerName = lines.firstOrNull()?.trim() ?: ""
            cachedGameLayer = layerName
            layerCacheMs    = now
            if (layerName.isNotEmpty()) {
                Log.i(TAG, "Combined script → layer: $layerName")
            } else {
                Log.d(TAG, "Combined script → no game layer, using global")
            }

            // Baris 2+ = timestamps dari --latency
            timestampOutput = lines.drop(1).joinToString("\n")
        }

        if (timestampOutput.isBlank()) {
            // Fallback: jika layer spesifik gagal, reset cache ke global dan coba sekali lagi
            if (!cachedGameLayer.isNullOrEmpty()) {
                Log.d(TAG, "Layer output empty, fallback to global")
                cachedGameLayer = ""
                val globalOut = shell.execute(latencyOnlyScript(""), timeoutMs = 1500L)
                if (globalOut.isBlank()) return 0f
                return parseLatencyOutput(globalOut)
            }
            return 0f
        }

        return parseLatencyOutput(timestampOutput)
    }

    /** Script hanya --latency, dipakai saat cache layer valid. */
    private fun latencyOnlyScript(layerArg: String): String =
        "dumpsys SurfaceFlinger --latency $layerArg" +
        " | tail -127 | awk '{print ${D}2}' | grep -v '^0${D}' | grep -v '^${D}'"

    /**
     * Script gabungan: detect + latency dalam satu execute().
     * Output format:
     *   Baris 1   → nama layer game (kosong jika tidak ditemukan)
     *   Baris 2+  → timestamps nanosecond dari --latency
     */
    private fun combinedLayerAndLatencyScript(): String =
        "TARGET=${D}(dumpsys SurfaceFlinger --list" +
        " | grep -E 'SurfaceView|Sprite|UnityMain|GameActivity' | tail -1);" +
        " echo \"${D}TARGET\";" +
        " dumpsys SurfaceFlinger --latency \"${D}TARGET\"" +
        " | tail -127 | awk '{print ${D}2}' | grep -v '^0${D}' | grep -v '^${D}'"

    private fun parseLatencyOutput(output: String): Float {
        var lastResult = 0f
        for (line in output.lines()) {
            val currentTimestamp = line.trim().toLongOrNull() ?: continue
            if (currentTimestamp <= 0L || currentTimestamp == Long.MAX_VALUE) continue
            if (lastLatencyTimestamp > 0L) {
                val delta = currentTimestamp - lastLatencyTimestamp
                // 2ms – 50ms = 20fps – 500fps
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
        if (!shell.connected) return 0f
        return try {
            val output = shell.execute("service call SurfaceFlinger 1013", timeoutMs = 1000L)
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

    // ── SMA ───────────────────────────────────────────────────────────────────

    private fun applySma(raw: Float): Float {
        smaBuffer.addLast(raw)
        if (smaBuffer.size > SMA_SIZE) smaBuffer.removeFirst()
        return smaBuffer.average().toFloat()
    }
}
