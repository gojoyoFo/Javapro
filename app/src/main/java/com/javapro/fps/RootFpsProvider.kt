package com.javapro.fps

import android.util.Log
import java.io.File
import java.util.LinkedList
import kotlinx.coroutines.*

/**
 * RootFpsProvider — Scene 8.1.6 Edition
 *
 * Strategi berlapis (priority order):
 *
 *  L1 ── Universal Path Discovery
 *         Scan 4 jalur spesifik secara berurutan (dari riset Scene 8.1.6).
 *         Termasuk parsing khusus MediaTek fpsgo_status.
 *
 *  L2 ── service call SurfaceFlinger 1013
 *         Hex Parcel → IEEE-754 Float → FPS decimal.
 *         Via PersistentSuShell (tanpa overhead buka proses baru).
 *
 *  L3 ── Aggressive Dumpsys (Last Resort)
 *         --latency-clear → sleep 250ms → --latency
 *         Scene Delta: FPS = frameCount * 1000 / elapsed_ms
 *         Smart Layer Targeting (SurfaceView/Sprite/Unity).
 *
 *  FALLBACK ── Non-Root (ShizukuFpsProvider)
 *         Otomatis aktif setelah ROOT_FAILURE_THRESHOLD kegagalan berturut-turut.
 *
 * Internal sampling: background coroutine 250ms per spec Scene 8.1.6.
 * getInstantFps() = non-blocking, kembalikan nilai cached @Volatile.
 */
class RootFpsProvider(private val maxRefreshRate: Float) : IFpsProvider {

    private val TAG = "RootFpsProvider"

    // ── Sysfs discovery result ────────────────────────────────────────────────
    private enum class SysfsType { DIRECT_FPS, FPSGO_STATUS }

    @Volatile private var activeSysfsPath: String? = null
    @Volatile private var activeSysfsType: SysfsType = SysfsType.DIRECT_FPS

    // ── Persistent shell ──────────────────────────────────────────────────────
    private val shell = PersistentSuShell()

    // ── Non-root fallback (lazy) ──────────────────────────────────────────────
    private var nonRootFallback: IFpsProvider? = null

    // ── Failure tracking ──────────────────────────────────────────────────────
    @Volatile private var consecutiveRootFailures = 0
    @Volatile var rootExhausted = false
        private set

    // ── Cached output (background loop → getInstantFps) ───────────────────────
    @Volatile private var latestFps: Float = 0f

    // ── SMA (8 sampel × 250ms = 2 detik data) ────────────────────────────────
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 8

    // ── Smart layer cache ─────────────────────────────────────────────────────
    @Volatile private var cachedGameLayer: String? = null
    private var layerCacheMs  = 0L
    private val LAYER_CACHE_TTL_MS = 3_000L

    // ── Reconnect debounce ────────────────────────────────────────────────────
    private var lastReconnectMs = 0L
    private val RECONNECT_COOLDOWN_MS = 5_000L

    // ── Background sampling coroutine ─────────────────────────────────────────
    private val samplerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Dollar literal — hindari konflik Kotlin template string vs shell variable
    private val D = "\$"

    companion object {
        @JvmStatic val SU_LOCK = Any()

        private const val TAG_STATIC        = "RootFpsProvider"
        private const val ROOT_FAIL_THRESH  = 5
        private const val SAMPLE_INTERVAL   = 250L   // ms — sesuai spec Scene 8.1.6
        private const val LAYER_SCAN_TTL    = 3_000L

        /**
         * 4 jalur prioritas dari riset Scene 8.1.6 (urutan diikuti persis).
         */
        private val PRIORITY_PATHS = listOf(
            "/sys/class/drm/sde-crtc-0/measured_fps"
                to SysfsType.DIRECT_FPS,
            "/sys/class/graphics/fb0/measured_fps"
                to SysfsType.DIRECT_FPS,
            "/sys/kernel/fpsgo/fstb/fpsgo_status"
                to SysfsType.FPSGO_STATUS,
            "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps"
                to SysfsType.DIRECT_FPS
        )

        // Daftar layer yang diabaikan saat mencari game layer
        private val LAYER_SKIP = listOf(
            "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
            "com.javapro", "WallpaperSurface", "pip-dismiss", "Splash Screen",
            "ShellDropTarget", "PointerLocation", "mouse pointer"
        )
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        connectShell()
        discoverSysfsPath()
        startSamplingLoop()
    }

    // ── Public API (IFpsProvider) ─────────────────────────────────────────────

    /**
     * Non-blocking: kembalikan nilai yang sudah di-cache oleh background sampler.
     * Jika root sudah exhausted, delegate ke non-root fallback.
     */
    override fun getInstantFps(): Float {
        if (rootExhausted) {
            return getNonRootFallback().getInstantFps()
        }
        return latestFps
    }

    override fun release() {
        samplerScope.cancel()
        smaBuffer.clear()
        cachedGameLayer      = null
        latestFps            = 0f
        nonRootFallback?.release()
        nonRootFallback = null
        shell.close()
    }

    // ── Background sampler (250ms interval) ───────────────────────────────────

    /**
     * Coroutine yang berjalan setiap 250ms.
     * Urutan prioritas: L1 sysfs → L2 service call → L3 dumpsys latency.
     * Simpan hasil ke [latestFps] (volatile, aman lintas thread).
     */
    private fun startSamplingLoop() {
        samplerScope.launch {
            // Beri waktu shell init sebelum mulai sampling
            delay(600)

            while (isActive) {
                val tick = System.currentTimeMillis()

                if (rootExhausted) {
                    // Delegasikan ke fallback, sampler tetap jalan agar bisa recover
                    latestFps = getNonRootFallback().getInstantFps()
                    delay(SAMPLE_INTERVAL)
                    continue
                }

                ensureShellAlive()

                // ── L1: Sysfs (direct file read, paling cepat) ──────────────
                val sysfs = readSysfsFps()
                if (sysfs > 0f) {
                    latestFps = applySma(sysfs)
                    onSuccess()
                    sleepRemaining(tick)
                    continue
                }

                // ── L2: service call SurfaceFlinger 1013 ─────────────────────
                val svcCall = readServiceCallFps()
                if (svcCall > 0f) {
                    latestFps = applySma(svcCall)
                    onSuccess()
                    sleepRemaining(tick)
                    continue
                }

                // ── L3: Aggressive Dumpsys --latency (last resort) ───────────
                val latency = readAggressiveDumpsys()
                if (latency > 0f) {
                    latestFps = latency   // SMA sudah diapply di dalam
                    onSuccess()
                    sleepRemaining(tick)
                    continue
                }

                // Semua metode gagal untuk tick ini
                onRootFailure()
                if (rootExhausted) {
                    Log.e(TAG, "Root exhausted → switching to non-root fallback")
                }
                sleepRemaining(tick)
            }
        }
    }

    /** Tidur sisa waktu dari interval 250ms agar setiap tick tepat 250ms. */
    private suspend fun sleepRemaining(tickStart: Long) {
        val elapsed = System.currentTimeMillis() - tickStart
        val remaining = SAMPLE_INTERVAL - elapsed
        if (remaining > 0L) delay(remaining)
    }

    // ── Shell lifecycle ───────────────────────────────────────────────────────

    private fun connectShell() {
        try {
            if (!shell.connect()) Log.w(TAG, "Shell connect failed, will retry")
        } catch (e: Exception) {
            Log.e(TAG, "connectShell: ${e.message}")
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

    // ── Non-root fallback ─────────────────────────────────────────────────────

    private fun getNonRootFallback(): IFpsProvider =
        nonRootFallback ?: ShizukuFpsProvider(maxRefreshRate).also { nonRootFallback = it }

    // ── Failure tracking ──────────────────────────────────────────────────────

    private fun onSuccess() { consecutiveRootFailures = 0 }

    private fun onRootFailure() {
        consecutiveRootFailures++
        Log.w(TAG, "Root sample failed ($consecutiveRootFailures/$ROOT_FAIL_THRESH)")
        if (consecutiveRootFailures >= ROOT_FAIL_THRESH) {
            rootExhausted = true
        }
    }

    // ── L1: Universal Path Discovery ──────────────────────────────────────────

    /**
     * Scan 4 jalur prioritas dari Scene 8.1.6 secara berurutan.
     * Berhenti di jalur pertama yang readable dan valid.
     * Jika tidak ada yang cocok, lakukan broad scan.
     */
    private fun discoverSysfsPath() {
        // Coba 4 jalur prioritas dahulu
        for ((path, type) in PRIORITY_PATHS) {
            val f = File(path)
            if (!f.exists() || !f.canRead()) continue
            val valid = when (type) {
                SysfsType.DIRECT_FPS   -> f.readText().trim().toFloatOrNull()?.let { it > 0f } == true
                SysfsType.FPSGO_STATUS -> parseFpsgoStatus(f.readText()) > 0f
            }
            if (valid) {
                activeSysfsPath = path
                activeSysfsType = type
                Log.i(TAG, "Sysfs node (priority): $path [$type]")
                return
            }
        }

        // Broad scan sebagai fallback jika 4 jalur di atas tidak ada
        val patterns = listOf(
            "/sys/class/drm"                to "measured_fps",
            "/sys/class/graphics"           to "measured_fps",
            "/sys/devices/platform/display" to "fps"
        )
        for ((dir, filename) in patterns) {
            File(dir).takeIf { it.exists() }?.walkTopDown()?.maxDepth(5)?.forEach { f ->
                if (f.name == filename && f.canRead()) {
                    val v = f.readText().trim().toFloatOrNull()
                    if (v != null && v > 0f) {
                        activeSysfsPath = f.absolutePath
                        activeSysfsType = SysfsType.DIRECT_FPS
                        Log.i(TAG, "Sysfs node (broad scan): ${f.absolutePath}")
                        return
                    }
                }
            }
        }
        Log.d(TAG, "No sysfs fps node found on this device")
    }

    private fun readSysfsFps(): Float {
        val path = activeSysfsPath ?: return 0f
        return try {
            val text = File(path).readText()
            when (activeSysfsType) {
                SysfsType.DIRECT_FPS   ->
                    text.trim().toFloatOrNull()?.coerceIn(1f, maxRefreshRate * 1.05f) ?: 0f
                SysfsType.FPSGO_STATUS ->
                    parseFpsgoStatus(text)
            }
        } catch (_: Exception) {
            activeSysfsPath = null   // invalidasi → re-discover saat init berikutnya
            0f
        }
    }

    /**
     * Parse MediaTek FpsGo status.
     *
     * Format umum fpsgo_status:
     *   tid   bufid  target_fps  fps  quota  ...
     *   12345  0      60          58   100   ...
     *
     * Ambil nilai fps tertinggi dari semua baris aktif (non-zero tid).
     * Fallback ke parse angka tunggal jika format tidak ada header.
     */
    private fun parseFpsgoStatus(text: String): Float {
        val lines = text.lines()

        // Cari header row
        val headerIdx = lines.indexOfFirst { it.trimStart().startsWith("tid") }
        if (headerIdx < 0) {
            // Format sederhana: hanya satu angka FPS
            return text.trim().toFloatOrNull()?.coerceIn(1f, maxRefreshRate * 1.05f) ?: 0f
        }

        val headers  = lines[headerIdx].trim().split("\\s+".toRegex())
        val fpsCol   = headers.indexOfFirst { it.equals("fps", ignoreCase = true) }
        if (fpsCol < 0) return 0f

        var best = 0f
        for (line in lines.drop(headerIdx + 1)) {
            if (line.isBlank()) continue
            val cols = line.trim().split("\\s+".toRegex())
            val fps  = cols.getOrNull(fpsCol)?.toFloatOrNull() ?: continue
            if (fps > best && fps <= maxRefreshRate * 1.05f) best = fps
        }
        return best
    }

    // ── L2: service call SurfaceFlinger 1013 ─────────────────────────────────

    /**
     * `service call SurfaceFlinger 1013` mengembalikan Hex Parcel, contoh:
     *   Result: Parcel(00000000 42740000 '....tB')
     *
     * Konversi setiap grup 8 hex → IEEE-754 32-bit float.
     * Lewati 00000000 (status code). Return float pertama yang masuk
     * range FPS valid (1..maxRefreshRate*1.05).
     */
    private fun readServiceCallFps(): Float {
        if (!shell.connected) return 0f
        return try {
            val output = shell.execute("service call SurfaceFlinger 1013", timeoutMs = 1000L)
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
                Log.d(TAG, "SvcCall 1013 → $f Hz (hex=0x$hex)")
                return f
            }
        }
        return 0f
    }

    // ── L3: Aggressive Dumpsys — Scene Delta Calculation ─────────────────────

    /**
     * Algoritma Scene 8.1.6:
     *
     *  1. --latency-clear "$LAYER"  → reset ring buffer SurfaceFlinger
     *  2. usleep 250000             → tunggu 250ms agar frame baru terakumulasi
     *  3. --latency "$LAYER"        → baca present timestamps kolom 2
     *
     * Scene Delta:
     *   FPS = frameCount * 1000 / elapsed_ms
     *
     * Semuanya dikirim dalam SATU execute() agar atomik di dalam persistent shell.
     * Timeout = 1800ms (250ms sleep + overhead dumpsys + awk).
     */
    private fun readAggressiveDumpsys(): Float {
        if (!shell.connected) return 0f

        val layer    = resolveGameLayer()
        val layerArg = if (layer.isNotEmpty()) "\"$layer\"" else ""

        val output   = shell.execute(buildSceneScript(layerArg), timeoutMs = 1800L)

        if (output.isBlank()) {
            // Fallback ke global jika layer spesifik tidak ada data
            if (layer.isNotEmpty()) {
                Log.d(TAG, "Smart layer empty → fallback global")
                cachedGameLayer = ""
                val globalOut = shell.execute(buildSceneScript(""), timeoutMs = 1800L)
                if (globalOut.isBlank()) return 0f
                return parseSceneDelta(globalOut)
            }
            return 0f
        }

        return parseSceneDelta(output)
    }

    /**
     * Script satu baris yang atomik di dalam persistent shell:
     *   clear → usleep 250ms → read latency → filter timestamps
     *
     * `usleep 250000` = 250ms (unit microseconds).
     * Jika usleep tidak tersedia (jarang), fallback ke `sleep 0` (tidak ideal tapi tidak error).
     */
    private fun buildSceneScript(layerArg: String): String =
        "dumpsys SurfaceFlinger --latency-clear $layerArg >/dev/null 2>&1;" +
        " usleep 250000 2>/dev/null || true;" +
        " dumpsys SurfaceFlinger --latency $layerArg" +
        " | tail -127 | awk '{print ${D}2}' | grep -v '^0${D}' | grep -v '^${D}'"

    /**
     * Parse output dari --latency (timestamps nanosecond kolom 2).
     *
     * Metode A — Scene Delta (akurat):
     *   FPS = frameCount * 1000 / elapsed_ms
     *   elapsed ≈ 250ms (dari usleep di script)
     *
     * Metode B — Inter-frame delta (backup):
     *   FPS = 1_000_000_000 / avg_delta_ns
     *
     * Blend keduanya jika sama-sama valid. Prioritaskan Metode A.
     */
    private fun parseSceneDelta(output: String): Float {
        val timestamps = output.lines()
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L && it != Long.MAX_VALUE }

        if (timestamps.isEmpty()) return 0f

        // ── Metode A: Scene Delta (frame count ÷ elapsed) ────────────────────
        // elapsed diambil dari rentang timestamp itu sendiri (lebih akurat dari wall clock)
        val tMin  = timestamps.first()
        val tMax  = timestamps.last()
        val rangeNs = tMax - tMin

        val sceneFps: Float = if (timestamps.size >= 2 && rangeNs in 10_000_000L..2_000_000_000L) {
            // (n-1) intervals untuk n timestamps
            val intervalNs = rangeNs.toFloat() / (timestamps.size - 1)
            (1_000_000_000f / intervalNs).coerceIn(1f, maxRefreshRate * 1.05f)
        } else 0f

        // ── Metode B: Inter-frame delta (baris terakhir sebagai sanity check) ─
        var deltaFps = 0f
        var prevTs   = 0L
        for (ts in timestamps) {
            if (prevTs > 0L) {
                val delta = ts - prevTs
                // Valid window: 2ms – 50ms = 20fps – 500fps
                if (delta in 2_000_000L..50_000_000L) {
                    deltaFps = 1_000_000_000f / delta
                }
            }
            prevTs = ts
        }
        if (deltaFps !in 1f..maxRefreshRate * 1.05f) deltaFps = 0f

        // ── Blend / pilih terbaik ─────────────────────────────────────────────
        val result: Float = when {
            sceneFps > 0f && deltaFps > 0f -> {
                // Ambil rata-rata berbobot: Scene (70%) + Delta (30%)
                sceneFps * 0.7f + deltaFps * 0.3f
            }
            sceneFps > 0f -> sceneFps
            deltaFps > 0f -> deltaFps
            else          -> return 0f
        }

        Log.d(TAG, "SceneDelta: frames=${timestamps.size}, scene=${"%.1f".format(sceneFps)}, " +
                   "delta=${"%.1f".format(deltaFps)}, blended=${"%.1f".format(result)}")

        return applySma(result.coerceIn(1f, maxRefreshRate * 1.05f))
    }

    // ── Smart Layer Detection ─────────────────────────────────────────────────

    /**
     * Deteksi layer game aktif dari SurfaceFlinger.
     * Priority: SurfaceView > Sprite > UnityMain > GameActivity > RenderThread
     * Cache 3 detik agar --list tidak dipanggil setiap tick 250ms.
     */
    private fun resolveGameLayer(): String {
        val now    = System.currentTimeMillis()
        val cached = cachedGameLayer
        if (cached != null && (now - layerCacheMs) < LAYER_CACHE_TTL_MS) return cached

        val listOut = shell.execute("dumpsys SurfaceFlinger --list", timeoutMs = 1500L)
        val target  = listOut.lines()
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
        else Log.d(TAG, "No game layer, using global")
        return target
    }

    // ── SMA ───────────────────────────────────────────────────────────────────

    @Synchronized
    private fun applySma(raw: Float): Float {
        smaBuffer.addLast(raw)
        if (smaBuffer.size > SMA_SIZE) smaBuffer.removeFirst()
        return smaBuffer.average().toFloat()
    }
}
