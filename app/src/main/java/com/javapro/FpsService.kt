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
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException

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

    private var prevCpuIdle = -1L
    private var prevCpuTotal = -1L
    private val cpuLock = Any()

    @Volatile private var cachedGpuUsagePath: String? = null
    @Volatile private var gpuPathDetected = false

    private var taskFpsCallback: Any? = null
    private var callbackRegistered = false
    private var currentTaskId = -1
    @Volatile private var callbackFps = 0f
    private var lastCallbackTime = 0L

    private val choreographerFpsCounter = ChoreographerFpsCounter()

    companion object {
        private const val TAG = "OverlayService"
        private const val POLL_MS = 1000L
        private const val STALE_MS = 2500L
        private const val TASK_CHECK_MS = 1000L
        const val PREF_FILE = "overlay_prefs"
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
        val method = intent?.getStringExtra("fps_method") ?: "non_root"
        if (!isRunning) {
            isRunning = true
            startFpsTracking(method)
            serviceScope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        choreographerFpsCounter.stop()
        unregisterFpsCallback()
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun startFpsTracking(method: String = "non_root") {
        if (method != "non_root" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            initTaskFpsCallbackInstance()
            val taskId = getFocusedTaskId()
            if (taskId > 0 && registerFpsCallbackForTask(taskId)) {
                mainHandler.post(taskCheckRunnable)
                return
            }
        }
        mainHandler.post { choreographerFpsCounter.start() }
    }

    private fun tryRegisterTaskFpsCallback(): Boolean {
        return try {
            val cbClass = Class.forName("android.window.TaskFpsCallback")
            taskFpsCallback = object : Any() {}.let {
                java.lang.reflect.Proxy.newProxyInstance(
                    cbClass.classLoader,
                    arrayOf(cbClass)
                ) { _, method, args ->
                    if (method.name == "onFpsReported") {
                        val fps = (args?.getOrNull(0) as? Float) ?: return@newProxyInstance null
                        if (fps > 0f) {
                            callbackFps = fps
                            lastCallbackTime = System.currentTimeMillis()
                        }
                    }
                    null
                }
            }

            taskFpsCallback = cbClass.getDeclaredConstructor().newInstance().also { cb ->
                val onFps = cb.javaClass.getDeclaredMethod("onFpsReported", Float::class.java)
                onFps.isAccessible = true
            }

            val taskId = getFocusedTaskId()
            if (taskId <= 0) return false
            registerFpsCallbackForTask(taskId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "TaskFpsCallback not available: ${e.message}")
            false
        }
    }

    private fun initTaskFpsCallbackInstance() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            taskFpsCallback = object : android.window.TaskFpsCallback() {
                override fun onFpsReported(fps: Float) {
                    if (fps > 0f) {
                        callbackFps = fps
                        lastCallbackTime = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TaskFpsCallback init failed: ${e.message}")
        }
    }

    private fun registerFpsCallbackForTask(taskId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val cb = taskFpsCallback as? android.window.TaskFpsCallback ?: return false
        return try {
            windowManager.registerTaskFpsCallback(taskId, Runnable::run, cb)
            callbackRegistered = true
            currentTaskId = taskId
            lastCallbackTime = System.currentTimeMillis()
            Log.i(TAG, "TaskFpsCallback registered taskId=$taskId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "registerTaskFpsCallback failed: ${e.message}")
            false
        }
    }

    private fun unregisterFpsCallback() {
        if (!callbackRegistered) return
        val cb = taskFpsCallback as? android.window.TaskFpsCallback ?: run {
            callbackRegistered = false; return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                windowManager.unregisterTaskFpsCallback(cb)
            }
        } catch (_: Exception) {}
        callbackRegistered = false
    }

    private val taskCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val newId = getFocusedTaskId()
            val now = System.currentTimeMillis()
            when {
                newId > 0 && newId != currentTaskId -> {
                    unregisterFpsCallback()
                    mainHandler.postDelayed({
                        if (isRunning) registerFpsCallbackForTask(newId)
                    }, 300)
                }
                callbackRegistered && lastCallbackTime > 0 && (now - lastCallbackTime) > STALE_MS -> {
                    unregisterFpsCallback()
                    mainHandler.postDelayed({
                        val id = getFocusedTaskId()
                        if (isRunning && id > 0) registerFpsCallbackForTask(id)
                    }, 300)
                }
                !callbackRegistered && newId > 0 -> registerFpsCallbackForTask(newId)
            }
            mainHandler.postDelayed(this, TASK_CHECK_MS)
        }
    }

    private fun getFocusedTaskId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return -1
        return try {
            val atm = Class.forName("android.app.ActivityTaskManager")
            val svc = atm.getDeclaredMethod("getService").invoke(null)
            val info = svc.javaClass.getMethod("getFocusedRootTaskInfo").invoke(svc) ?: return -1
            try { info.javaClass.getField("taskId").getInt(info) }
            catch (_: NoSuchFieldException) { info.javaClass.getField("mTaskId").getInt(info) }
        } catch (_: Exception) { -1 }
    }

    private fun getCurrentFps(): Float {
        if (callbackRegistered) {
            val now = System.currentTimeMillis()
            if (lastCallbackTime > 0 && (now - lastCallbackTime) > STALE_MS) {
                callbackFps = 0f
                return 0f
            }
            val raw = callbackFps
            if (raw > 0f) return raw.coerceIn(1f, 400f)
        }
        return choreographerFpsCounter.getFps()
    }

    private suspend fun pollLoop() {
        readCpuLoad()
        delay(200)
        while (coroutineContext.isActive && isRunning) {
            val fps = getCurrentFps()
            val cpuLoad = readCpuLoad()
            val gpuLoad = readGpuLoad()
            val batTemp = readBatteryTemp()
            mainHandler.post { updateOverlay(fps, cpuLoad, gpuLoad, batTemp) }
            delay(POLL_MS)
        }
    }

    private fun readCpuLoad(): Int {
        return try {
            val line = File("/proc/stat").bufferedReader().readLine() ?: return -1
            if (!line.startsWith("cpu ")) return -1
            val p = line.trim().split("\\s+".toRegex())
            if (p.size < 8) return -1
            val user    = p[1].toLong()
            val nice    = p[2].toLong()
            val system  = p[3].toLong()
            val idle    = p[4].toLong()
            val iowait  = p[5].toLong()
            val irq     = p[6].toLong()
            val softirq = p[7].toLong()
            val steal   = if (p.size > 8) p[8].toLong() else 0L
            val total   = user + nice + system + idle + iowait + irq + softirq + steal
            synchronized(cpuLock) {
                val prevT = prevCpuTotal
                val prevI = prevCpuIdle
                prevCpuTotal = total
                prevCpuIdle  = idle
                if (prevT < 0) return@synchronized -1
                val dTotal = total - prevT
                val dIdle  = idle  - prevI
                if (dTotal <= 0) return@synchronized -1
                ((100f * (dTotal - dIdle) / dTotal).coerceIn(0f, 100f)).toInt()
            }
        } catch (_: Exception) { -1 }
    }

    private fun readGpuLoad(): Int {
        val path = getGpuUsagePath() ?: return -1
        return tryReadGpuUsage(path)
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
            "/sys/devices/platform/gpu/gpubusy",
            "/sys/kernel/debug/mali/utilization_gp_pp"
        )
        for (path in candidates) {
            if (tryReadGpuUsage(path) >= 0) {
                cachedGpuUsagePath = path
                gpuPathDetected = true
                Log.i(TAG, "GPU usage path: $path")
                return path
            }
        }
        val devfreq = File("/sys/class/devfreq")
        if (devfreq.exists()) {
            devfreq.listFiles()?.forEach { dir ->
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
        }
        gpuPathDetected = true
        cachedGpuUsagePath = null
        return null
    }

    private fun tryReadGpuUsage(path: String): Int {
        val line = readLine(path)?.trim() ?: return -1
        if (line.isEmpty()) return -1
        return when {
            path.contains("ged/hal/gpu_utilization") -> {
                val parts = line.split("\\s+".toRegex())
                for (part in parts) {
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
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 0f
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return raw / 10f
    }

    private fun updateOverlay(fps: Float, cpuLoad: Int, gpuLoad: Int, batTemp: Float) {
        val fpsInt = if (fps in 1f..400f) fps.toInt() else 0
        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt == 0     -> Color.parseColor("#80FFFFFF")
            fpsInt < 30     -> Color.parseColor("#FF5252")
            fpsInt < 55     -> Color.parseColor("#FFD740")
            else            -> Color.parseColor("#69FF47")
        })

        tvCpuLoad.text = if (cpuLoad >= 0) "$cpuLoad%" else "--"
        tvCpuLoad.setTextColor(when {
            cpuLoad >= 80   -> Color.parseColor("#FF5252")
            cpuLoad >= 50   -> Color.parseColor("#FFD740")
            else            -> Color.parseColor("#58A6FF")
        })

        tvGpuLoad.text = if (gpuLoad >= 0) "$gpuLoad%" else "--"
        tvGpuLoad.setTextColor(when {
            gpuLoad >= 80   -> Color.parseColor("#FF5252")
            gpuLoad >= 50   -> Color.parseColor("#FFD740")
            else            -> Color.parseColor("#CE93D8")
        })

        tvBatTemp.text = if (batTemp > 0f) "${"%.0f".format(batTemp)}°" else "--"
        tvBatTemp.setTextColor(when {
            batTemp >= 45f  -> Color.parseColor("#FF5252")
            batTemp >= 38f  -> Color.parseColor("#FFD740")
            else            -> Color.parseColor("#66BB6A")
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
        var startX = 0;  var startY = 0
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
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
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

    private fun readLine(path: String): String? {
        return try { BufferedReader(FileReader(path)).use { it.readLine() } }
        catch (_: IOException) { null }
        catch (_: Exception) { null }
    }

    inner class ChoreographerFpsCounter {
        private var running = false
        private var frameCount = 0
        private var lastNanos = 0L
        @Volatile private var currentFps = 0f
        private val choreographerHandler = Handler(Looper.getMainLooper())

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                frameCount++
                if (lastNanos == 0L) {
                    lastNanos = frameTimeNanos
                } else {
                    val elapsed = frameTimeNanos - lastNanos
                    if (elapsed >= 500_000_000L) {
                        currentFps = frameCount * 1_000_000_000f / elapsed
                        frameCount = 0
                        lastNanos = frameTimeNanos
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun start() {
            if (running) return
            running = true
            frameCount = 0
            lastNanos = 0L
            choreographerHandler.post {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        fun stop() {
            running = false
            choreographerHandler.post {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
            }
        }

        fun getFps(): Float = currentFps
    }
}
