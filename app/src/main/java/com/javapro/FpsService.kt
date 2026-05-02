package com.javapro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.view.FrameMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException

class FpsService : Service() {

    private enum class FpsMode { ROOT, SHIZUKU, STANDARD_NON_ROOT }

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

    private var prevCpuIdle = -1L
    private var prevCpuTotal = -1L
    private val cpuLock = Any()

    @Volatile private var cachedGpuUsagePath: String? = null
    @Volatile private var gpuPathDetected = false

    private var detectedMode: FpsMode = FpsMode.STANDARD_NON_ROOT

    private val smaWindow = ArrayDeque<Float>(20)
    private val smaSize = 15
    @Volatile private var smoothedFps = 0f

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    private var frameMetricsAttached = false

    private val forcedInvalidateRunnable = object : Runnable {
        override fun run() {
            if (isRunning && frameMetricsAttached) {
                overlayView.invalidate()
                mainHandler.postDelayed(this, 16)
            }
        }
    }

    @Volatile private var isRootConfirmed = false
    @Volatile private var rootCheckDone = false

    companion object {
        private const val TAG = "FpsService"
        private const val POLL_MS = 800L
        const val PREF_FILE = "overlay_prefs"
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
        private const val CHANNEL_ID = "fps_overlay_channel"
        private const val NOTIF_ID = 9001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startBgThread()
        buildOverlay()
        setupParams()
        setupDrag()
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            serviceScope.launch {
                detectedMode = detectFpsMode()
                Log.i(TAG, "FPS mode: $detectedMode")
                when (detectedMode) {
                    FpsMode.ROOT, FpsMode.SHIZUKU -> startShellFpsLoop()
                    FpsMode.STANDARD_NON_ROOT -> {
                        mainHandler.post { attachFrameMetrics() }
                        startShellFpsLoop()
                    }
                }
                pollSystemStats()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        detachFrameMetrics()
        stopBgThread()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun startBgThread() {
        bgThread = HandlerThread("FpsBgThread").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBgThread() {
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    private suspend fun detectFpsMode(): FpsMode {
        if (hasRoot()) return FpsMode.ROOT
        if (isShizukuAvailable()) return FpsMode.SHIZUKU
        return FpsMode.STANDARD_NON_ROOT
    }

    private fun hasRoot(): Boolean {
        if (rootCheckDone) return isRootConfirmed
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
            val out = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            isRootConfirmed = out == "ok"
            rootCheckDone = true
            isRootConfirmed
        } catch (_: Exception) {
            rootCheckDone = true
            false
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val isAlive = shizukuClass.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!isAlive) return false
            val result = shizukuClass.getMethod("checkSelfPermission").invoke(null) as? Int ?: -1
            result == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    private suspend fun startShellFpsLoop() {
        while (currentCoroutineContext().isActive && isRunning) {
            val fps = when (detectedMode) {
                FpsMode.ROOT     -> readFpsRoot()
                FpsMode.SHIZUKU  -> readFpsShizuku()
                FpsMode.STANDARD_NON_ROOT -> smoothedFps
            }
            if (fps > 0f) addToSma(fps)
            delay(POLL_MS)
        }
    }

    private fun readFpsRoot(): Float {
        val sfFps = readSurfaceFlingerServiceCall()
        if (sfFps > 0f) return sfFps
        val latencyFps = readSurfaceFlingerLatency(root = true)
        if (latencyFps > 0f) return latencyFps
        for (path in listOf(
            "/sys/kernel/ged/hal/curr_fps",
            "/sys/kernel/ged/hal/fps",
            "/proc/mtk_mali/fps",
            "/sys/class/drm/card0/fps"
        )) {
            val fps = readSysfsFpsRoot(path)
            if (fps > 0f) return fps
        }
        return 0f
    }

    private fun readSurfaceFlingerServiceCall(): Float {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "service call SurfaceFlinger 1013"))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(); proc.destroy()
            if (out.isBlank()) return 0f
            val match = Regex("\\(([0-9a-fA-F]+)\\)").find(out) ?: return 0f
            val raw = java.lang.Long.parseLong(match.groupValues[1], 16)
            if (raw <= 0L) return 0f
            val fps = 1_000_000_000f / raw
            if (fps in 1f..400f) fps else 0f
        } catch (_: Exception) { 0f }
    }

    private fun readSurfaceFlingerLatency(root: Boolean): Float {
        return try {
            val cmd = "dumpsys SurfaceFlinger --latency SurfaceView"
            val proc = if (root)
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            else
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor(); proc.destroy()

            val timestamps = mutableListOf<Long>()
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 3) continue
                val ts = parts[1].toLongOrNull() ?: continue
                if (ts > 0L && ts != Long.MAX_VALUE) timestamps.add(ts)
            }
            if (timestamps.size < 5) return 0f
            val sorted = timestamps.sorted()
            val deltas = (1 until sorted.size).mapNotNull { i ->
                val d = (sorted[i] - sorted[i - 1]).toDouble()
                if (d in 2_000_000.0..50_000_000.0) d else null
            }
            if (deltas.isEmpty()) return 0f
            val fps = (1_000_000_000.0 / deltas.average()).toFloat()
            if (fps in 1f..400f) fps else 0f
        } catch (_: Exception) { 0f }
    }

    private fun readSysfsFpsRoot(path: String): Float {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            if (line.isNullOrEmpty()) return 0f
            val raw = if (line.contains(":")) line.substringAfter(":").trim() else line
            val v = raw.split("\\s+".toRegex()).firstOrNull()?.toFloatOrNull() ?: return 0f
            if (v in 1f..400f) v else 0f
        } catch (_: Exception) { 0f }
    }

    private fun readFpsShizuku(): Float {
        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val process = shizukuClass.getMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).invoke(null,
                arrayOf("dumpsys", "SurfaceFlinger", "--latency", "SurfaceView"),
                null, null
            ) as? Process ?: return 0f
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor(); process.destroy()

            val timestamps = mutableListOf<Long>()
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 3) continue
                val ts = parts[1].toLongOrNull() ?: continue
                if (ts > 0L && ts != Long.MAX_VALUE) timestamps.add(ts)
            }
            if (timestamps.size < 5) return 0f
            val sorted = timestamps.sorted()
            val deltas = (1 until sorted.size).mapNotNull { i ->
                val d = (sorted[i] - sorted[i - 1]).toDouble()
                if (d in 2_000_000.0..50_000_000.0) d else null
            }
            if (deltas.isEmpty()) return 0f
            val fps = (1_000_000_000.0 / deltas.average()).toFloat()
            if (fps in 1f..400f) fps else 0f
        } catch (_: Exception) { 0f }
    }

    private fun attachFrameMetrics() {
        if (frameMetricsAttached) return
        if (overlayView.windowToken == null) {
            overlayView.viewTreeObserver.addOnWindowAttachListener(
                object : android.view.ViewTreeObserver.OnWindowAttachListener {
                    override fun onWindowAttached() {
                        attachFrameMetrics()
                        overlayView.viewTreeObserver.removeOnWindowAttachListener(this)
                    }
                    override fun onWindowDetached() {}
                }
            )
            return
        }

        val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalNs <= 0L) return@OnFrameMetricsAvailableListener
            val instantFps = 1_000_000_000f / totalNs
            if (instantFps in 1f..400f) addToSma(instantFps)
        }
        frameMetricsListener = listener

        try {
            val viewRootImpl = overlayView.javaClass.getMethod("getViewRootImpl").invoke(overlayView)
            if (viewRootImpl != null) {
                val attachInfoField = viewRootImpl.javaClass.getDeclaredField("mAttachInfo")
                    .also { it.isAccessible = true }
                val attachInfo = attachInfoField.get(viewRootImpl)
                if (attachInfo != null) {
                    val window = attachInfo.javaClass.getDeclaredField("mWindow")
                        .also { it.isAccessible = true }
                        .get(attachInfo) as? Window
                    if (window != null) {
                        window.addOnFrameMetricsAvailableListener(listener, bgHandler)
                        frameMetricsAttached = true
                        mainHandler.post(forcedInvalidateRunnable)
                        Log.i(TAG, "FrameMetrics attached, forced invalidate started")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FrameMetrics attach failed: ${e.message}")
        }
        Log.w(TAG, "FrameMetrics unavailable on this device")
    }

    private fun detachFrameMetrics() {
        mainHandler.removeCallbacks(forcedInvalidateRunnable)
        val listener = frameMetricsListener ?: return
        try {
            val viewRootImpl = overlayView.javaClass.getMethod("getViewRootImpl").invoke(overlayView) ?: return
            val attachInfoField = viewRootImpl.javaClass.getDeclaredField("mAttachInfo")
                .also { it.isAccessible = true }
            val attachInfo = attachInfoField.get(viewRootImpl) ?: return
            val window = attachInfo.javaClass.getDeclaredField("mWindow")
                .also { it.isAccessible = true }
                .get(attachInfo) as? Window
            window?.removeOnFrameMetricsAvailableListener(listener)
        } catch (_: Exception) {}
        frameMetricsAttached = false
        frameMetricsListener = null
    }

    private fun addToSma(fps: Float) {
        synchronized(smaWindow) {
            smaWindow.addLast(fps)
            if (smaWindow.size > smaSize) smaWindow.removeFirst()
            smoothedFps = smaWindow.average().toFloat()
        }
    }

    private suspend fun pollSystemStats() {
        readCpuFromProcStat()
        delay(500)
        readCpuFromProcStat()
        delay(500)
        while (currentCoroutineContext().isActive && isRunning) {
            val fps = smoothedFps
            val cpu = readCpuLoad()
            val gpu = readGpuLoadAuto()
            val bat = readBatteryTemp()
            mainHandler.post { updateOverlay(fps, cpu, gpu, bat) }
            delay(POLL_MS)
        }
    }

    private fun readCpuLoad(): Int {
        val fromProc = readCpuFromProcStat()
        if (fromProc >= 0) return fromProc
        return readCpuFromLoadAvg()
    }

    private fun readCpuFromProcStat(): Int {
        return try {
            val line = try {
                File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
            } catch (_: Exception) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat /proc/stat"))
                    .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor(); p.destroy() } }
                    .lines().firstOrNull { it.startsWith("cpu ") }
            } ?: return -1

            val p = line.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (p.size < 8) return -1
            val user    = p[1].toLongOrNull() ?: return -1
            val nice    = p[2].toLongOrNull() ?: return -1
            val system  = p[3].toLongOrNull() ?: return -1
            val idle    = p[4].toLongOrNull() ?: return -1
            val iowait  = p[5].toLongOrNull() ?: 0L
            val irq     = p[6].toLongOrNull() ?: 0L
            val softirq = p[7].toLongOrNull() ?: 0L
            val steal   = p.getOrNull(8)?.toLongOrNull() ?: 0L
            val totalIdle = idle + iowait
            val total = user + nice + system + totalIdle + irq + softirq + steal

            synchronized(cpuLock) {
                val prevT = prevCpuTotal
                val prevI = prevCpuIdle
                prevCpuTotal = total
                prevCpuIdle = totalIdle
                if (prevT < 0) return@synchronized -1
                val dTotal = total - prevT
                val dIdle = totalIdle - prevI
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

    private fun readGpuLoadAuto(): Int {
        if (hasRoot()) return readGpuViaShell()
        val path = getGpuUsagePath()
        if (path != null) return tryReadGpuUsage(path)
        return -1
    }

    private fun readGpuViaShell(): Int {
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
        val cached = if (gpuPathDetected) cachedGpuUsagePath else null
        if (cached != null) {
            val v = readGpuPathShell(cached)
            if (v >= 0) return v
            gpuPathDetected = false
            cachedGpuUsagePath = null
        }
        for (path in candidates) {
            val v = readGpuPathShell(path)
            if (v >= 0) {
                cachedGpuUsagePath = path
                gpuPathDetected = true
                return v
            }
        }
        return readGpuDevfreqShell()
    }

    private fun readGpuPathShell(path: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            if (line.isNullOrEmpty()) -1 else parseGpuLine(path, line)
        } catch (_: Exception) { -1 }
    }

    private fun readGpuDevfreqShell(): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "for d in /sys/class/devfreq/*; do n=\$(basename \$d | tr '[:upper:]' '[:lower:]'); " +
                "case \$n in *gpu*|*kgsl*|*mali*|*mfg*) " +
                "if [ -f \$d/gpu_busy_percentage ]; then cat \$d/gpu_busy_percentage && exit; fi; " +
                "esac; done; echo -1"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor(); proc.destroy()
            line?.toIntOrNull()?.coerceIn(-1, 100) ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun getGpuUsagePath(): String? {
        if (gpuPathDetected) return cachedGpuUsagePath
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
        for (path in candidates) {
            if (tryReadGpuUsage(path) >= 0) {
                cachedGpuUsagePath = path
                gpuPathDetected = true
                return path
            }
        }
        File("/sys/class/devfreq").takeIf { it.exists() }?.listFiles()?.forEach { dir ->
            val n = dir.name.lowercase()
            if (n.contains("gpu") || n.contains("kgsl") || n.contains("mali")) {
                val f = File(dir, "trans_stat")
                if (f.exists() && f.canRead()) {
                    cachedGpuUsagePath = f.absolutePath
                    gpuPathDetected = true
                    return f.absolutePath
                }
            }
        }
        gpuPathDetected = true
        cachedGpuUsagePath = null
        return null
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
                }
                -1
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

    private fun readBatteryTemp(): Float {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0f
        return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    private fun readLine(path: String): String? {
        return try { BufferedReader(FileReader(path)).use { it.readLine() } }
        catch (_: IOException) { null }
        catch (_: Exception) { null }
    }

    private fun updateOverlay(fps: Float, cpu: Int, gpu: Int, batTemp: Float) {
        val fpsInt = if (fps in 1f..400f) fps.toInt() else 0
        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt == 0 -> Color.parseColor("#80FFFFFF")
            fpsInt < 30 -> Color.parseColor("#FF5252")
            fpsInt < 55 -> Color.parseColor("#FFD740")
            else        -> Color.parseColor("#69FF47")
        })
        tvCpuLoad.text = if (cpu >= 0) "$cpu%" else "--"
        tvCpuLoad.setTextColor(when {
            cpu >= 80 -> Color.parseColor("#FF5252")
            cpu >= 50 -> Color.parseColor("#FFD740")
            else      -> Color.parseColor("#58A6FF")
        })
        tvGpuLoad.text = if (gpu >= 0) "$gpu%" else "--"
        tvGpuLoad.setTextColor(when {
            gpu >= 80 -> Color.parseColor("#FF5252")
            gpu >= 50 -> Color.parseColor("#FFD740")
            else      -> Color.parseColor("#CE93D8")
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
            text          = "FPS"
            textSize      = 8f
            typeface      = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
            gravity       = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#66FFFFFF"))
        }
        val fpsBlock = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            gravity      = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(6f)) }
            addView(tvFps)
            addView(fpsLabel)
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(0.7f)
            ).also { it.setMargins(0, 0, 0, dp(6f)) }
            setBackgroundColor(Color.parseColor("#28FFFFFF"))
        }

        fun lbl(text: String) = TextView(this).apply {
            this.text     = text
            textSize      = 7f
            typeface      = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(Color.parseColor("#66FFFFFF"))
        }
        fun valTv(hex: String) = TextView(this).apply {
            text     = "--"
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor(hex))
        }

        tvCpuLoad = valTv("#58A6FF")
        tvGpuLoad = valTv("#CE93D8")
        tvBatTemp = valTv("#66BB6A")

        fun row(label: String, tv: TextView) = LinearLayout(this).apply {
            orientation  = LinearLayout.HORIZONTAL
            gravity      = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(2f), 0, dp(2f)) }
            addView(lbl(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv)
        }

        overlayView.addView(fpsBlock)
        overlayView.addView(divider)
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 16)
            y = prefs.getInt(PREF_Y, 120)
        }
    }

    private fun setupDrag() {
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false

        overlayView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
                            .putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FPS Overlay", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery_notif)
            .setContentTitle("FPS Monitor")
            .setContentText("Overlay aktif")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
