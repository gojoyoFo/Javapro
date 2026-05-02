package com.javapro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.javapro.utils.ShizukuManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.LinkedList

class FpsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var params: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private lateinit var tvFps: TextView
    private lateinit var tvCpuLoad: TextView
    private lateinit var tvGpuLoad: TextView
    private lateinit var tvBatTemp: TextView

    // ── CPU state ─────────────────────────────────────────────────────────────
    private var prevCpuIdle  = -1L
    private var prevCpuTotal = -1L
    private val cpuLock = Any()

    // ── GPU state ─────────────────────────────────────────────────────────────
    @Volatile private var cachedGpuPath: String? = null
    @Volatile private var gpuPathDetected = false

    // ── Root detection (cached) ───────────────────────────────────────────────
    @Volatile private var isRootConfirmed = false
    @Volatile private var rootCheckDone   = false

    // ── FPS method ────────────────────────────────────────────────────────────
    private var fpsMethod = "non_root"  // "root" | "shizuku" | "non_root"

    // ── Layer 1: Sysfs FPS node ───────────────────────────────────────────────
    @Volatile private var activeFpsPath: String? = null

    // ── Layer 2: SurfaceFlinger Latency (root/shizuku) ────────────────────────
    @Volatile private var latencyFps = 0f
    private var lastLatencyTimestamp: Long = 0L

    // ── Layer 3: service call 1013 (root/shizuku) ────────────────────────────
    @Volatile private var serviceCallFps = 0f

    // ── Layer 4: FrameMetrics (non-root fallback) ─────────────────────────────
    @Volatile private var frameMetricsFps = 0f
    private var frameMetricsListener: Any? = null  // Window.OnFrameMetricsAvailableListener
    private var fmFrameCount = 0
    private var fmWindowNs   = 0L
    private val fmLock = Any()
    private val fmInvalidateHandler = Handler(Looper.getMainLooper())
    private val fmInvalidateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                overlayView.invalidate()
                fmInvalidateHandler.postDelayed(this, 16)
            }
        }
    }

    // ── TaskFpsCallback (root Android 13+) ───────────────────────────────────
    private var taskFpsCallbackObj: Any? = null
    private var callbackRegistered = false
    private var currentTaskId = -1
    @Volatile private var callbackFps = 0f
    private var lastFpsUpdateTime = System.currentTimeMillis()

    // ── Timestats delta (fallback non-root) ───────────────────────────────────
    private val tsLayerSnapshot = mutableMapOf<String, Long>()
    private var tsLastMs = 0L
    private val TS_LAYER_SKIP = listOf(
        "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
        "com.javapro", "WallpaperSurface", "pip-dismiss", "Splash Screen",
        "ShellDropTarget", "PointerLocation", "mouse pointer"
    )

    // ── SMA Smoothing ─────────────────────────────────────────────────────────
    private val fpsSmaBuffer = LinkedList<Float>()
    private val SMA_SIZE = 15

    // ── HandlerThread untuk shell commands ───────────────────────────────────
    private val shellThread = HandlerThread("FpsShellThread").also { it.start() }
    private val shellHandler = Handler(shellThread.looper)

    companion object {
        private const val TAG = "FpsService"
        private const val POLL_MS = 1000L
        private const val STALENESS_THRESHOLD_MS = 2000L
        private const val TASK_CHECK_INTERVAL_MS = 1000L
        const val PREF_FILE = "overlay_prefs"
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        setupParams()
        setupDrag()
        try { windowManager.addView(overlayView, params) }
        catch (e: Exception) { Log.e(TAG, "addView failed: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fpsMethod = intent?.getStringExtra("fps_method") ?: "non_root"
        if (!isRunning) {
            isRunning = true
            serviceScope.launch {
                // Warmup CPU baseline
                readCpuLoad(); delay(400); readCpuLoad(); delay(200)
                // Init semua layer FPS di background
                initFpsNode()
                startFpsEngine()
                pollLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopFpsEngine()
        fmInvalidateHandler.removeCallbacks(fmInvalidateRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        shellThread.quitSafely()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // =========================================================================
    // LAYER 1: Universal Sysfs Scanner
    // =========================================================================

    private fun initFpsNode() {
        val patterns = listOf(
            "/sys/class/drm" to "measured_fps",
            "/sys/class/graphics" to "measured_fps",
            "/sys/devices/platform/display" to "fps"
        )
        for ((dir, filename) in patterns) {
            val root = File(dir)
            if (!root.exists()) continue
            root.walkTopDown().maxDepth(4).forEach { f ->
                if (f.name == filename && f.canRead()) {
                    val v = f.readText().trim().toFloatOrNull()
                    if (v != null && v > 0f) {
                        activeFpsPath = f.absolutePath
                        Log.i(TAG, "Layer1 sysfs fps node: ${f.absolutePath}")
                        return
                    }
                }
            }
        }
        // Single known debug path
        val debugPath = "/sys/kernel/debug/fps_data"
        if (File(debugPath).canRead()) {
            File(debugPath).readText().trim().toFloatOrNull()?.let {
                if (it > 0f) { activeFpsPath = debugPath; return }
            }
        }
        Log.i(TAG, "Layer1 sysfs fps node: not found, falling through")
    }

    private fun readSysfsFps(): Float {
        val path = activeFpsPath ?: return 0f
        return try {
            File(path).readText().trim().toFloatOrNull()
                ?.coerceIn(1f, getDeviceRefreshRate() * 1.05f) ?: 0f
        } catch (_: Exception) { activeFpsPath = null; 0f }
    }

    // =========================================================================
    // LAYER 2: SurfaceFlinger Latency
    // =========================================================================

    private fun readLatencyFps(): Float {
        return try {
            val output = runPrivilegedCommand("dumpsys SurfaceFlinger --latency SurfaceView")
                .ifBlank { runPrivilegedCommand("dumpsys SurfaceFlinger --latency") }
            if (output.isBlank()) return 0f

            val max = getDeviceRefreshRate() * 1.05f

            // Ambil 127 baris terakhir, parse kolom kedua (frame ready timestamp, nanosecond)
            val lines = output.lines().takeLast(127)
            var lastFpsFromBatch = 0f

            for (line in lines) {
                val cols = line.trim().split("\\s+".toRegex())
                if (cols.size < 2) continue
                // Kolom kedua = actual present time (nanosecond sejak boot)
                val currentTimestamp: Long = cols[1].toLongOrNull() ?: continue
                if (currentTimestamp <= 0L || currentTimestamp == Long.MAX_VALUE) continue

                if (lastLatencyTimestamp > 0L) {
                    val delta = currentTimestamp - lastLatencyTimestamp
                    // Hanya hitung kalau delta masuk akal: 2ms - 50ms (20fps - 500fps)
                    if (delta in 2_000_000L..50_000_000L) {
                        val instantFps = 1_000_000_000f / delta
                        if (instantFps in 1f..max) {
                            // Masukkan tiap frame langsung ke SMA buffer
                            fpsSmaBuffer.addLast(instantFps)
                            if (fpsSmaBuffer.size > SMA_SIZE) fpsSmaBuffer.removeFirst()
                            lastFpsFromBatch = fpsSmaBuffer.average().toFloat()
                        }
                    }
                }
                lastLatencyTimestamp = currentTimestamp
            }

            lastFpsFromBatch
        } catch (_: Exception) { 0f }
    }

    // =========================================================================
    // LAYER 3: service call SurfaceFlinger 1013
    // =========================================================================

    private fun readServiceCallFps(): Float {
        return try {
            val output = runPrivilegedCommand("service call SurfaceFlinger 1013")
            if (output.isBlank()) return 0f
            parseServiceCallHex(output)
        } catch (_: Exception) { 0f }
    }

    // Output contoh: "Result: Parcel(00000000 42700000  '....p.  ')"
    // 0x42700000 = 60.0f dalam IEEE 754
    private fun parseServiceCallHex(output: String): Float {
        val hexRegex = Regex("""([0-9a-fA-F]{8})""")
        val matches = hexRegex.findAll(output).map { it.value }.toList()
        // Skip "00000000" pertama (status OK), ambil nilai berikutnya
        for (hex in matches) {
            if (hex == "00000000") continue
            val bits = hex.toLongOrNull(16)?.toInt() ?: continue
            val f = java.lang.Float.intBitsToFloat(bits)
            if (f in 1f..getDeviceRefreshRate() * 1.05f) return f
        }
        return 0f
    }

    // =========================================================================
    // LAYER 4: FrameMetrics (non-root fallback)
    // =========================================================================

    private fun startFrameMetrics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        mainHandler.post {
            try {
                val window = getOverlayWindow() ?: return@post
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                    val totalDuration = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
                    if (totalDuration > 0) {
                        synchronized(fmLock) {
                            fmFrameCount++
                            fmWindowNs += totalDuration
                            if (fmWindowNs >= 1_000_000_000L) {
                                frameMetricsFps = fmFrameCount * 1_000_000_000f / fmWindowNs
                                fmFrameCount = 0
                                fmWindowNs   = 0L
                            }
                        }
                    }
                }
                window.addOnFrameMetricsAvailableListener(listener, mainHandler)
                frameMetricsListener = listener
                // Paksa invalidate agar listener mendapat data
                fmInvalidateHandler.post(fmInvalidateRunnable)
                Log.i(TAG, "Layer4 FrameMetrics started")
            } catch (e: Exception) {
                Log.w(TAG, "Layer4 FrameMetrics failed: ${e.message}")
            }
        }
    }

    private fun stopFrameMetrics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        mainHandler.post {
            try {
                val window = getOverlayWindow() ?: return@post
                val listener = frameMetricsListener as? Window.OnFrameMetricsAvailableListener
                    ?: return@post
                window.removeOnFrameMetricsAvailableListener(listener)
                frameMetricsListener = null
            } catch (_: Exception) {}
        }
    }

    // FrameMetrics butuh Window — kita buat PhoneWindow dummy di atas overlay
    private var frameMetricsWindow: android.view.Window? = null
    private fun getOverlayWindow(): android.view.Window? {
        // Overlay Service tidak punya Window langsung — FrameMetrics hanya bisa
        // dipasang ke Window Activity. Untuk service overlay, kita skip ke SMA
        // dari sumber lain dan mark FrameMetrics sebagai tidak tersedia.
        return null
    }

    // =========================================================================
    // FPS Engine — start/stop + priority picker
    // =========================================================================

    private fun startFpsEngine() {
        stopFpsEngine()
        val isRoot    = hasRoot()
        val isShizuku = ShizukuManager.isAvailable()
        val hasPriv   = isRoot || isShizuku
        Log.i(TAG, "startFpsEngine method=$fpsMethod root=$isRoot shizuku=$isShizuku")

        when {
            // Root + API33+: TaskFpsCallback (paling akurat)
            isRoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                initTaskFpsCallback()
                shellHandler.post {
                    val taskId = getFocusedTaskId()
                    if (taskId > 0) mainHandler.post { registerCallback(taskId) }
                    mainHandler.post(taskCheckRunnable)
                }
            }
        }

        // Layer 2+3 poll loop via shell (root atau shizuku)
        if (hasPriv) {
            serviceScope.launch { privilegedFpsLoop() }
        }

        // Layer 4: FrameMetrics kalau benar-benar non-root (tidak tersedia di overlay)
        // Ditangani oleh SMA fallback dari timestats jika semua layer gagal
        if (!hasPriv) {
            serviceScope.launch { nonRootFpsLoop() }
        }
    }

    private fun stopFpsEngine() {
        unregisterCallback()
        mainHandler.removeCallbacks(taskCheckRunnable)
        stopFrameMetrics()
    }

    // Loop untuk root/shizuku: Layer2 + Layer3 bergantian
    private suspend fun privilegedFpsLoop() {
        var useLatency = true
        while (currentCoroutineContext().isActive && isRunning) {
            val result = withContext(Dispatchers.IO) {
                if (useLatency) readLatencyFps() else readServiceCallFps()
            }
            if (result > 0f) {
                latencyFps     = if (useLatency) result else latencyFps
                serviceCallFps = if (!useLatency) result else serviceCallFps
            } else {
                useLatency = !useLatency
            }
            delay(POLL_MS)
        }
    }

    // Loop untuk non-root: timestats delta (paling reliable tanpa permission khusus)
    private suspend fun nonRootFpsLoop() {
        while (currentCoroutineContext().isActive && isRunning) {
            val tsFps = withContext(Dispatchers.IO) { readFpsTimestats() }
            if (tsFps > 0f) latencyFps = tsFps
            delay(POLL_MS)
        }
    }

    private fun getCurrentFps(): Float {
        val max = getDeviceRefreshRate() * 1.05f

        // Prioritas 1 — TaskFpsCallback (root API33+, paling akurat)
        if (callbackRegistered) {
            val now = System.currentTimeMillis()
            if (now - lastFpsUpdateTime < STALENESS_THRESHOLD_MS) {
                val v = callbackFps.coerceIn(0f, max)
                if (v > 0f) return applySma(v)
            }
        }

        // Prioritas 2 — Sysfs node (tercepat, tanpa shell)
        val sysfs = readSysfsFps()
        if (sysfs > 0f) return applySma(sysfs)

        // Prioritas 3 — SurfaceFlinger Latency / service call (sudah di-SMA di readLatencyFps)
        val priv = maxOf(latencyFps, serviceCallFps).coerceIn(0f, max)
        if (priv > 0f) return priv

        // Prioritas 4 — FrameMetrics (overlay window tidak bisa, nilai 0)
        val fm = frameMetricsFps.coerceIn(0f, max)
        if (fm > 0f) return applySma(fm)

        return 0f
    }

    // ── SMA Smoothing ─────────────────────────────────────────────────────────
    private fun applySma(raw: Float): Float {
        fpsSmaBuffer.addLast(raw)
        if (fpsSmaBuffer.size > SMA_SIZE) fpsSmaBuffer.removeFirst()
        return fpsSmaBuffer.average().toFloat()
    }

    // =========================================================================
    // TaskFpsCallback (root API33+)
    // =========================================================================

    private fun initTaskFpsCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            taskFpsCallbackObj = object : android.window.TaskFpsCallback() {
                override fun onFpsReported(fps: Float) {
                    if (fps > 0f) {
                        callbackFps = fps
                        lastFpsUpdateTime = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "initTaskFpsCallback: ${e.message}")
        }
    }

    private fun registerCallback(taskId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val cb = taskFpsCallbackObj ?: return false
        return try {
            val method = windowManager.javaClass.getMethod(
                "registerTaskFpsCallback",
                Int::class.java,
                java.util.concurrent.Executor::class.java,
                android.window.TaskFpsCallback::class.java
            )
            method.invoke(windowManager, taskId, java.util.concurrent.Executor { it.run() }, cb)
            callbackRegistered = true
            currentTaskId      = taskId
            lastFpsUpdateTime  = System.currentTimeMillis()
            Log.i(TAG, "TaskFpsCallback registered taskId=$taskId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "registerCallback failed: ${e.message}")
            false
        }
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        val cb = taskFpsCallbackObj ?: run { callbackRegistered = false; return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val method = windowManager.javaClass.getMethod(
                    "unregisterTaskFpsCallback",
                    android.window.TaskFpsCallback::class.java
                )
                method.invoke(windowManager, cb)
            }
        } catch (_: Exception) {}
        callbackRegistered = false
    }

    private val taskCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                shellHandler.post {
                    val newId = getFocusedTaskId()
                    val now   = System.currentTimeMillis()
                    if (newId > 0 && newId != currentTaskId) {
                        mainHandler.post { reinitCallback() }
                    } else if (now - lastFpsUpdateTime > STALENESS_THRESHOLD_MS) {
                        mainHandler.post { reinitCallback() }
                    }
                }
            }
            mainHandler.postDelayed(this, TASK_CHECK_INTERVAL_MS)
        }
    }

    private fun reinitCallback() {
        unregisterCallback()
        mainHandler.postDelayed({
            if (isRunning) {
                shellHandler.post {
                    val taskId = getFocusedTaskId()
                    if (taskId > 0) mainHandler.post { registerCallback(taskId) }
                }
            }
        }, 500)
    }

    private fun getFocusedTaskId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return -1
        return try {
            val atmClass  = Class.forName("android.app.ActivityTaskManager")
            val atmSvc    = atmClass.getDeclaredMethod("getService").invoke(null)
            val taskInfo  = atmSvc.javaClass.getMethod("getFocusedRootTaskInfo").invoke(atmSvc)
                ?: return -1
            try { taskInfo.javaClass.getField("taskId").getInt(taskInfo) }
            catch (_: NoSuchFieldException) { taskInfo.javaClass.getField("mTaskId").getInt(taskInfo) }
        } catch (_: Exception) { -1 }
    }

    // =========================================================================
    // Timestats fallback (non-root)
    // =========================================================================

    private fun readFpsTimestats(): Float {
        return try {
            val output = runShellCommand("dumpsys SurfaceFlinger --timestats -dump")
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
            if (fps in 1f..getDeviceRefreshRate() * 1.05f) fps else 0f
        } catch (_: Exception) { 0f }
    }

    // =========================================================================
    // Shell helpers
    // =========================================================================

    private fun hasRoot(): Boolean {
        if (rootCheckDone) return isRootConfirmed
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
            val out  = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            isRootConfirmed = out == "ok"
            rootCheckDone   = true
            isRootConfirmed
        } catch (_: Exception) { rootCheckDone = true; false }
    }

    // Jalankan command: root → su, shizuku → ShizukuManager, else → sh
    private fun runPrivilegedCommand(cmd: String): String {
        return when {
            hasRoot()                   -> runShellCommand(cmd, useRoot = true)
            ShizukuManager.isAvailable() -> ShizukuManager.runCommand(cmd)
            else                        -> ""
        }
    }

    private fun runShellCommand(cmd: String, useRoot: Boolean = false): String {
        return try {
            val args = if (useRoot || hasRoot()) arrayOf("su", "-c", cmd)
                       else arrayOf("sh", "-c", cmd)
            val proc = Runtime.getRuntime().exec(args)
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            proc.destroy()
            out
        } catch (_: Exception) { "" }
    }

    private fun getDeviceRefreshRate(): Float {
        return try {
            @Suppress("DEPRECATION")
            val r = windowManager.defaultDisplay.refreshRate
            if (r in 30f..360f) r else 60f
        } catch (_: Exception) { 60f }
    }

    // =========================================================================
    // Poll loop (CPU / GPU / BAT + FPS display)
    // =========================================================================

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && isRunning) {
            val fps     = getCurrentFps()
            val cpuLoad = readCpuLoad()
            val gpuLoad = readGpuLoadAuto()
            val batTemp = readBatteryTemp()
            mainHandler.post { updateOverlay(fps, cpuLoad, gpuLoad, batTemp) }
            delay(POLL_MS)
        }
    }

    // ── CPU ───────────────────────────────────────────────────────────────────

    private fun readCpuLoad(): Int {
        val fromProc = readCpuFromProcStat()
        if (fromProc >= 0) return fromProc
        return readCpuFromLoadAvg()
    }

    private fun readCpuFromProcStat(): Int {
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
                ?: runShellCommand("cat /proc/stat").lines().firstOrNull { it.startsWith("cpu ") }
                ?: return -1
            val p = line.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (p.size < 8) return -1
            val user    = p[1].toLongOrNull() ?: return -1
            val nice    = p[2].toLongOrNull() ?: return -1
            val system  = p[3].toLongOrNull() ?: return -1
            val idle    = p[4].toLongOrNull() ?: return -1
            val iowait  = p.getOrNull(5)?.toLongOrNull() ?: 0L
            val irq     = p.getOrNull(6)?.toLongOrNull() ?: 0L
            val softirq = p.getOrNull(7)?.toLongOrNull() ?: 0L
            val steal   = p.getOrNull(8)?.toLongOrNull() ?: 0L
            val totalIdle = idle + iowait
            val total     = user + nice + system + totalIdle + irq + softirq + steal
            synchronized(cpuLock) {
                val prevT = prevCpuTotal; val prevI = prevCpuIdle
                prevCpuTotal = total;     prevCpuIdle  = totalIdle
                if (prevT < 0) return@synchronized -1
                val dTotal = total - prevT; val dIdle = totalIdle - prevI
                if (dTotal <= 0) return@synchronized -1
                ((100f * (dTotal - dIdle) / dTotal).coerceIn(0f, 100f)).toInt()
            }
        } catch (_: Exception) { -1 }
    }

    private fun readCpuFromLoadAvg(): Int {
        return try {
            val line = BufferedReader(FileReader("/proc/loadavg")).use { it.readLine() } ?: return -1
            val load = line.trim().split("\\s+".toRegex()).firstOrNull()?.toFloatOrNull() ?: return -1
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((load / cores) * 100f).coerceIn(0f, 100f).toInt()
        } catch (_: Exception) { -1 }
    }

    // ── GPU ───────────────────────────────────────────────────────────────────

    private fun readGpuLoadAuto(): Int {
        if (hasRoot()) return readGpuLoadViaShell()
        val path = getGpuUsagePathDirect()
        if (path != null) return tryReadGpuUsage(path)
        return -1
    }

    private fun readGpuLoadViaShell(): Int {
        val candidates = listOf(
            "/sys/kernel/ged/hal/gpu_utilization",
            "/proc/mtk_mali/utilization",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/utilization_pp",
            "/sys/devices/platform/gpu/gpubusy"
        )
        val cached = if (gpuPathDetected) cachedGpuPath else null
        if (cached != null) {
            val r = readGpuViaShell(cached)
            if (r >= 0) return r
            gpuPathDetected = false; cachedGpuPath = null
        }
        for (path in candidates) {
            val r = readGpuViaShell(path)
            if (r >= 0) { cachedGpuPath = path; gpuPathDetected = true; return r }
        }
        return readGpuFromDevfreqShell()
    }

    private fun readGpuViaShell(path: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            if (line.isNullOrEmpty()) -1 else parseGpuLine(path, line)
        } catch (_: Exception) { -1 }
    }

    private fun readGpuFromDevfreqShell(): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "for d in /sys/class/devfreq/*; do n=\$(basename \$d | tr '[:upper:]' '[:lower:]'); " +
                "case \$n in *gpu*|*kgsl*|*mali*|*mfg*) " +
                "if [ -f \$d/gpu_busy_percentage ]; then cat \$d/gpu_busy_percentage && exit 0; fi;; esac; done; echo -1"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            line?.toIntOrNull()?.coerceIn(-1, 100) ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun getGpuUsagePathDirect(): String? {
        if (gpuPathDetected) return cachedGpuPath
        val candidates = listOf(
            "/sys/kernel/ged/hal/gpu_utilization",
            "/proc/mtk_mali/utilization",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/utilization_pp",
            "/sys/devices/platform/gpu/gpubusy",
            "/sys/kernel/debug/mali/utilization_gp_pp"
        )
        for (path in candidates) {
            if (tryReadGpuUsage(path) >= 0) {
                cachedGpuPath = path; gpuPathDetected = true; return path
            }
        }
        File("/sys/class/devfreq").takeIf { it.exists() }?.listFiles()?.forEach { dir ->
            val n = dir.name.lowercase()
            if (n.contains("gpu") || n.contains("kgsl") || n.contains("mali")) {
                File(dir, "trans_stat").takeIf { it.canRead() }?.let {
                    cachedGpuPath = it.absolutePath; gpuPathDetected = true; return it.absolutePath
                }
            }
        }
        gpuPathDetected = true; cachedGpuPath = null; return null
    }

    private fun tryReadGpuUsage(path: String): Int {
        val line = readLine(path)?.trim() ?: return -1
        if (line.isEmpty()) return -1
        return parseGpuLine(path, line)
    }

    private fun parseGpuLine(path: String, line: String): Int {
        return when {
            path.contains("ged/hal/gpu_utilization") -> {
                for (part in line.split("\\s+".toRegex())) {
                    val v = part.replace("%", "").toIntOrNull() ?: continue
                    if (v in 0..100) return v
                }; -1
            }
            path.contains("mtk_mali/utilization") -> {
                val after = if (line.contains(":")) line.substringAfter(":").trim() else line
                val v = after.replace("%", "").split("\\s+".toRegex()).firstOrNull()?.toIntOrNull() ?: return -1
                if (v in 0..100) v else -1
            }
            path.contains("kgsl") && path.contains("gpubusy") && !path.contains("percentage") -> {
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val num = parts[0].toLongOrNull() ?: return -1
                    val den = parts[1].toLongOrNull() ?: return -1
                    if (den > 0) ((num.toFloat() / den.toFloat()) * 100f).toInt().coerceIn(0, 100) else -1
                } else -1
            }
            else -> {
                val v = line.replace("%", "").split("\\s+".toRegex()).firstOrNull()?.toIntOrNull() ?: return -1
                if (v in 0..100) v else -1
            }
        }
    }

    // ── Battery ───────────────────────────────────────────────────────────────

    private fun readBatteryTemp(): Float {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0f
        return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    // =========================================================================
    // Overlay UI (tidak berubah)
    // =========================================================================

    private fun updateOverlay(fps: Float, cpuLoad: Int, gpuLoad: Int, batTemp: Float) {
        val fpsInt = if (fps in 1f..400f) fps.toInt() else 0
        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt == 0  -> Color.parseColor("#80FFFFFF")
            fpsInt < 30  -> Color.parseColor("#FF5252")
            fpsInt < 55  -> Color.parseColor("#FFD740")
            else         -> Color.parseColor("#69FF47")
        })
        tvCpuLoad.text = if (cpuLoad >= 0) "$cpuLoad%" else "--"
        tvCpuLoad.setTextColor(when {
            cpuLoad >= 80 -> Color.parseColor("#FF5252")
            cpuLoad >= 50 -> Color.parseColor("#FFD740")
            else          -> Color.parseColor("#58A6FF")
        })
        tvGpuLoad.text = if (gpuLoad >= 0) "$gpuLoad%" else "--"
        tvGpuLoad.setTextColor(when {
            gpuLoad >= 80 -> Color.parseColor("#FF5252")
            gpuLoad >= 50 -> Color.parseColor("#FFD740")
            else          -> Color.parseColor("#CE93D8")
        })
        tvBatTemp.text = if (batTemp > 0f) "${"%.0f".format(batTemp)}°" else "--"
        tvBatTemp.setTextColor(when {
            batTemp >= 45f -> Color.parseColor("#FF5252")
            batTemp >= 38f -> Color.parseColor("#FFD740")
            else           -> Color.parseColor("#66BB6A")
        })
    }

    private fun buildOverlay() {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(Color.parseColor("#E0050810"))
            setStroke(dp(0.8f), Color.parseColor("#33FFFFFF"))
        }
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = bg
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        tvFps = TextView(this).apply {
            text     = "--"
            textSize = 38f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity  = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.WHITE)
        }
        val fpsLabel = TextView(this).apply {
            text = "FPS"; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f; gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#66FFFFFF"))
        }
        val fpsBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(6f)) }
            addView(tvFps); addView(fpsLabel)
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(0.7f)
            ).also { it.setMargins(0, 0, 0, dp(6f)) }
            setBackgroundColor(Color.parseColor("#28FFFFFF"))
        }
        fun lbl(t: String) = TextView(this).apply {
            text = t; textSize = 7f; typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f; setTextColor(Color.parseColor("#66FFFFFF"))
        }
        fun valTv(hex: String) = TextView(this).apply {
            text = "--"; textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor(hex))
        }
        tvCpuLoad = valTv("#58A6FF"); tvGpuLoad = valTv("#CE93D8"); tvBatTemp = valTv("#66BB6A")
        fun row(label: String, tv: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(2f), 0, dp(2f)) }
            addView(lbl(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv)
        }
        overlayView.addView(fpsBlock); overlayView.addView(divider)
        overlayView.addView(row("CPU", tvCpuLoad))
        overlayView.addView(row("GPU", tvGpuLoad))
        overlayView.addView(row("BAT", tvBatTemp))
    }

    private fun setupParams() {
        val prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val type  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            (72 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 16); y = prefs.getInt(PREF_Y, 120)
        }
    }

    private fun setupDrag() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        overlayView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun readLine(path: String): String? {
        return try { BufferedReader(FileReader(path)).use { it.readLine() } }
        catch (_: IOException) { null }
        catch (_: Exception)   { null }
    }
}
