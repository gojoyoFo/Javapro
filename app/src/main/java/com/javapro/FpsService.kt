package com.javapro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.javapro.utils.SystemInfoReader
import kotlinx.coroutines.*

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

    private var detectedMode: FpsMode = FpsMode.STANDARD_NON_ROOT

    // SMA untuk smoothing fps
    private val smaWindow = ArrayDeque<Float>(20)
    private val smaSize = 10
    @Volatile private var smoothedFps = 0f

    // FrameMetrics fallback
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

    // Root check
    @Volatile private var isRootConfirmed = false
    @Volatile private var rootCheckDone   = false

    // Timestats state: simpan totalFrames snapshot terakhir per layer
    private val layerFrameSnapshot = mutableMapOf<String, Long>()
    private var lastTimestatsMs = 0L

    companion object {
        private const val TAG      = "FpsService"
        private const val POLL_MS  = 800L
        const val  PREF_FILE       = "overlay_prefs"
        private const val PREF_X   = "overlay_x"
        private const val PREF_Y   = "overlay_y"
        private const val CHANNEL_ID = "fps_overlay_channel"
        private const val NOTIF_ID   = 9001

        // Layer yang harus di-skip (system UI, overlay kita sendiri)
        private val LAYER_SKIP = listOf(
            "NavigationBar", "StatusBar", "ScreenDecor",
            "InputMethod", "com.javapro", "WallpaperSurface",
            "pip-dismiss-overlay", "Splash Screen", "ShellDropTarget",
            "PointerLocation", "mouse pointer"
        )
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
        try { windowManager.addView(overlayView, params) }
        catch (e: Exception) { Log.e(TAG, "addView failed: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            serviceScope.launch {
                detectedMode = detectFpsMode()
                Log.i(TAG, "FPS mode: $detectedMode")
                startFpsLoop()
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

    // ─── Thread ──────────────────────────────────────────────────────────────

    private fun startBgThread() {
        bgThread = HandlerThread("FpsBgThread").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }
    private fun stopBgThread() {
        bgThread?.quitSafely(); bgThread = null; bgHandler = null
    }

    // ─── Mode detection ──────────────────────────────────────────────────────

    private suspend fun detectFpsMode(): FpsMode {
        if (hasRoot()) return FpsMode.ROOT
        if (isShizukuAvailable()) return FpsMode.SHIZUKU
        return FpsMode.STANDARD_NON_ROOT
    }

    private fun hasRoot(): Boolean {
        if (rootCheckDone) return isRootConfirmed
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
            val ok = p.inputStream.bufferedReader().readLine()?.trim() == "ok"
            p.waitFor(); p.destroy()
            isRootConfirmed = ok; rootCheckDone = true; ok
        } catch (_: Exception) { rootCheckDone = true; false }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val alive = cls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            if (!alive) return false
            val perm = cls.getMethod("checkSelfPermission").invoke(null) as? Int ?: -1
            perm == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    // ─── FPS loop utama ──────────────────────────────────────────────────────

    private suspend fun startFpsLoop() {
        // Non-root: attach FrameMetrics sebagai last-resort fallback
        if (detectedMode == FpsMode.STANDARD_NON_ROOT) {
            mainHandler.post { attachFrameMetrics() }
        }

        while (currentCoroutineContext().isActive && isRunning) {
            val fps = readFps()
            if (fps > 0f) addToSma(fps)
            delay(POLL_MS)
        }
    }

    /**
     * Urutan prioritas:
     * 1. dumpsys SurfaceFlinger --timestats  (root / shizuku / sh non-root)
     * 2. /sys/kernel/ged sysfs              (root, MTK)
     * 3. FrameMetrics                        (non-root last-resort)
     */
    private fun readFps(): Float {
        val tsFps = readFpsTimestats()
        if (tsFps > 0f) return tsFps

        if (detectedMode == FpsMode.ROOT) {
            for (path in listOf(
                "/sys/kernel/ged/hal/curr_fps",
                "/sys/kernel/ged/hal/fps",
                "/proc/mtk_mali/fps"
            )) {
                val v = readSysfsFps(path)
                if (v > 0f) return v
            }
        }

        return smoothedFps  // dari FrameMetrics listener
    }

    // ─── Timestats ───────────────────────────────────────────────────────────

    /**
     * Baca `dumpsys SurfaceFlinger --timestats -dump`.
     * Hitung fps = delta(totalFrames) / delta(waktu).
     *
     * Berjalan di semua mode: su, shizuku, maupun sh biasa (banyak device
     * mengizinkan dumpsys SurfaceFlinger tanpa root di Android < 12).
     * Di Android 12+ non-root output mungkin terpotong tapi tetap ada data.
     */
    private fun readFpsTimestats(): Float {
        return try {
            val output = runDumpsys("SurfaceFlinger", "--timestats", "-dump")
                ?: return 0f
            if (output.isBlank()) return 0f

            val nowMs = System.currentTimeMillis()
            val elapsedMs = if (lastTimestatsMs > 0) nowMs - lastTimestatsMs else 0L
            lastTimestatsMs = nowMs

            val frameMap = parseTimestatsFrames(output)
            if (frameMap.isEmpty()) return 0f

            val bestLayer = pickForegroundLayer(frameMap.keys.toList()) ?: return 0f
            val currentFrames = frameMap[bestLayer] ?: return 0f

            val prevFrames = layerFrameSnapshot[bestLayer]
            layerFrameSnapshot[bestLayer] = currentFrames

            if (prevFrames == null || elapsedMs <= 0L) return 0f

            val deltaFrames = currentFrames - prevFrames
            if (deltaFrames <= 0L) return 0f

            val fps = deltaFrames * 1000f / elapsedMs
            Log.d(TAG, "timestats layer=$bestLayer fps=%.1f delta=$deltaFrames elapsed=${elapsedMs}ms".format(fps))

            if (fps in 1f..400f) fps else 0f
        } catch (e: Exception) {
            Log.w(TAG, "readFpsTimestats error: ${e.message}")
            0f
        }
    }

    private fun parseTimestatsFrames(output: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        var currentLayer: String? = null

        for (line in output.lines()) {
            val t = line.trim()
            when {
                // Format: "layerName = com.example/..." atau "Layer: ..."
                t.startsWith("layerName = ") -> {
                    currentLayer = t.removePrefix("layerName = ").trim()
                }
                t.startsWith("Layer: ") -> {
                    currentLayer = t.removePrefix("Layer: ").trim()
                }
                // Format: "totalFrames = 1234"
                t.startsWith("totalFrames = ") && currentLayer != null -> {
                    val frames = t.removePrefix("totalFrames = ").trim().toLongOrNull()
                    if (frames != null && frames > 0L) result[currentLayer!!] = frames
                    currentLayer = null
                }
            }
        }
        return result
    }

    /**
     * Pilih layer foreground terbaik.
     * Prioritas: SurfaceView (game) > package name > apapun selain system UI.
     */
    private fun pickForegroundLayer(layers: List<String>): String? {
        if (layers.isEmpty()) return null

        val filtered = layers.filter { layer ->
            LAYER_SKIP.none { skip -> layer.contains(skip, ignoreCase = true) }
        }
        if (filtered.isEmpty()) return null

        // Game/video pakai SurfaceView
        filtered.firstOrNull { it.contains("SurfaceView", ignoreCase = true) }
            ?.let { return it }

        // Layer dengan package name (ada titik, bukan android system)
        filtered.firstOrNull {
            it.contains(".") &&
            !it.contains("android", ignoreCase = true) &&
            !it.contains("miui", ignoreCase = true)
        }?.let { return it }

        return filtered.first()
    }

    // ─── Shell runner ────────────────────────────────────────────────────────

    private fun runDumpsys(vararg args: String): String? {
        return when (detectedMode) {
            FpsMode.ROOT             -> runWithSu("dumpsys ${args.joinToString(" ")}")
            FpsMode.SHIZUKU          -> runWithShizuku(args.toList())
            FpsMode.STANDARD_NON_ROOT -> runWithSh("dumpsys ${args.joinToString(" ")}")
        }
    }

    private fun runWithSu(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); p.destroy()
        out.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun runWithSh(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); p.destroy()
        out.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun runWithShizuku(args: List<String>): String? = try {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val p = cls.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).invoke(null, args.toTypedArray(), null, null) as? Process ?: return null
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); p.destroy()
        out.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    // ─── Sysfs FPS (MTK root) ────────────────────────────────────────────────

    private fun readSysfsFps(path: String): Float = try {
        val line = runWithSu("cat $path")?.trim() ?: return 0f
        val raw  = if (line.contains(":")) line.substringAfter(":").trim() else line
        val v    = raw.split("\\s+".toRegex()).firstOrNull()?.toFloatOrNull() ?: return 0f
        if (v in 1f..400f) v else 0f
    } catch (_: Exception) { 0f }

    // ─── FrameMetrics (non-root last resort) ────────────────────────────────

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

        val listener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            val ns = fm.getMetric(FrameMetrics.TOTAL_DURATION)
            if (ns <= 0L) return@OnFrameMetricsAvailableListener
            val f = 1_000_000_000f / ns
            if (f in 1f..400f) addToSma(f)
        }
        frameMetricsListener = listener

        try {
            val vri = overlayView.javaClass.getMethod("getViewRootImpl").invoke(overlayView) ?: return
            val ai  = vri.javaClass.getDeclaredField("mAttachInfo")
                .also { it.isAccessible = true }.get(vri) ?: return
            val win = ai.javaClass.getDeclaredField("mWindow")
                .also { it.isAccessible = true }.get(ai) as? Window ?: return
            win.addOnFrameMetricsAvailableListener(listener, bgHandler)
            frameMetricsAttached = true
            mainHandler.post(forcedInvalidateRunnable)
            Log.i(TAG, "FrameMetrics attached")
        } catch (e: Exception) {
            Log.w(TAG, "FrameMetrics attach failed: ${e.message}")
        }
    }

    private fun detachFrameMetrics() {
        mainHandler.removeCallbacks(forcedInvalidateRunnable)
        val listener = frameMetricsListener ?: return
        try {
            val vri = overlayView.javaClass.getMethod("getViewRootImpl").invoke(overlayView) ?: return
            val ai  = vri.javaClass.getDeclaredField("mAttachInfo")
                .also { it.isAccessible = true }.get(vri) ?: return
            val win = ai.javaClass.getDeclaredField("mWindow")
                .also { it.isAccessible = true }.get(ai) as? Window ?: return
            win.removeOnFrameMetricsAvailableListener(listener)
        } catch (_: Exception) {}
        frameMetricsAttached = false
        frameMetricsListener = null
    }

    // ─── SMA ────────────────────────────────────────────────────────────────

    private fun addToSma(fps: Float) {
        synchronized(smaWindow) {
            smaWindow.addLast(fps)
            if (smaWindow.size > smaSize) smaWindow.removeFirst()
            smoothedFps = smaWindow.average().toFloat()
        }
    }

    // ─── System stats (delegate ke SystemInfoReader) ─────────────────────────

    private suspend fun pollSystemStats() {
        // Warm-up 2x agar delta CPU valid
        SystemInfoReader.read(this@FpsService)
        delay(500)
        SystemInfoReader.read(this@FpsService)
        delay(500)

        while (currentCoroutineContext().isActive && isRunning) {
            val fps      = smoothedFps
            val snapshot = SystemInfoReader.read(this@FpsService)
            val cpu      = snapshot.cpuUsagePct.toInt()
            val gpu      = snapshot.gpuUsagePct.toInt()
            val batTemp  = snapshot.batteryTempC
            mainHandler.post { updateOverlay(fps, cpu, gpu, batTemp) }
            delay(POLL_MS)
        }
    }

    // ─── Overlay UI ──────────────────────────────────────────────────────────

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
            shape        = GradientDrawable.RECTANGLE
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
            addView(tvFps); addView(fpsLabel)
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

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FPS Overlay", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null)
            }
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery_notif)
            .setContentTitle("FPS Monitor")
            .setContentText("Overlay aktif")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
