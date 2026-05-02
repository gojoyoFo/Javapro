package com.javapro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.javapro.utils.SystemInfoReader
import com.javapro.utils.SystemSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

class FpsService : Service() {

    private lateinit var windowManager : WindowManager
    private lateinit var overlayView   : LinearLayout
    private lateinit var params        : WindowManager.LayoutParams

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    private var isRunning    = false

    private var activeMethod       : FpsMethod = FpsMethod.NONE
    private var fpsFilePath        : String?   = null
    private var taskFpsCallback    : Any?   = null
    private var callbackRegistered = false
    private var currentTaskId      = -1
    private var lastCallbackTime   = 0L
    private var callbackFps        = 0f
    private val fpsHistory         = mutableListOf<Float>()
    private val maxHistory         = 120

    private lateinit var tvFps      : TextView
    private lateinit var tvCpuUsage : TextView
    private lateinit var tvCpuTemp  : TextView
    private lateinit var tvGpuUsage : TextView
    private lateinit var tvGpuFreq  : TextView
    private lateinit var tvRam      : TextView
    private lateinit var tvBattery  : TextView
    private lateinit var tvBatTemp  : TextView

    companion object {
        private const val TAG           = "FpsService"
        private const val SAMPLE_MS     = 1000L
        private const val TASK_CHECK_MS = 1000L
        private const val STALE_MS      = 2500L
        private const val PREF_FILE     = "overlay_prefs"
        private const val PREF_X        = "overlay_x"
        private const val PREF_Y        = "overlay_y"
        private val _currentFps = MutableStateFlow(0f)
        val currentFps: StateFlow<Float> = _currentFps
    }

    private enum class FpsMethod { TASK_CALLBACK, DUMPSYS, SYSFS, NONE }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        setupParams()
        setupDrag()
        try { windowManager.addView(overlayView, params) }
        catch (e: Exception) { Log.e(TAG, "addView failed", e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        monitoringJob?.cancel()
        isRunning = true
        serviceScope.launch { resolveAndStart() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        monitoringJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterCallback()
        if (activeMethod == FpsMethod.DUMPSYS) {
            serviceScope.launch {
                try { shell("dumpsys SurfaceFlinger --timestats -disable") } catch (_: Exception) {}
            }
        }
        serviceScope.cancel()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun buildOverlay() {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val bg = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = dp(20f).toFloat()
            setColor(Color.parseColor("#E00A0C10"))
            setStroke(dp(0.8f), Color.parseColor("#33FFFFFF"))
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = bg
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }

        fun label(text: String) = TextView(this).apply {
            this.text     = text
            textSize      = 7f
            typeface      = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(Color.parseColor("#66FFFFFF"))
        }

        fun value(init: String, hex: String) = TextView(this).apply {
            text     = init
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor(hex))
        }

        fun divider() = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(0.7f)
            ).also { it.setMargins(0, dp(5f), 0, dp(5f)) }
            setBackgroundColor(Color.parseColor("#28FFFFFF"))
        }

        fun row(lbl: String, tv: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(1.5f), 0, dp(1.5f)) }
            layoutParams = lp
            addView(label(lbl), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv)
        }

        tvFps = TextView(this).apply {
            text      = "--"
            textSize  = 36f
            typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity   = Gravity.CENTER_HORIZONTAL
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
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(4f)) }
            layoutParams = lp
            addView(tvFps)
            addView(fpsLabel)
        }

        tvCpuUsage = value("--", "#58A6FF")
        tvCpuTemp  = value("--", "#FF6B6B")
        tvGpuUsage = value("--", "#CE93D8")
        tvGpuFreq  = value("--", "#AB47BC")
        tvRam      = value("--", "#26C6DA")
        tvBattery  = value("--", "#66BB6A")
        tvBatTemp  = value("--", "#80FFFFFF")

        overlayView.addView(fpsBlock)
        overlayView.addView(divider())
        overlayView.addView(row("CPU", tvCpuUsage))
        overlayView.addView(row("TMP", tvCpuTemp))
        overlayView.addView(divider())
        overlayView.addView(row("GPU", tvGpuUsage))
        overlayView.addView(row("FRQ", tvGpuFreq))
        overlayView.addView(divider())
        overlayView.addView(row("RAM", tvRam))
        overlayView.addView(divider())
        overlayView.addView(row("BAT", tvBattery))
        overlayView.addView(row("TMP", tvBatTemp))
    }

    private fun setupParams() {
        val prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val type  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            (76 * resources.displayMetrics.density).toInt(),
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
        overlayView.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f; private var downRawY = 0f
            private var startX   = 0;  private var startY   = 0
            private var moved    = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        startX = params.x; startY = params.y
                        moved = false; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        params.x = startX + dx; params.y = startY + dy
                        try { windowManager.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
                                .putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private suspend fun resolveAndStart() {
        activeMethod = detectMethod()
        Log.i(TAG, "FPS method resolved: $activeMethod")
        if (activeMethod == FpsMethod.TASK_CALLBACK) startTaskCallback()
        else startPolling()
    }

    private fun detectMethod(): FpsMethod {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canUseTaskFpsCallback())
            return FpsMethod.TASK_CALLBACK
        return try {
            val r = shell("dumpsys SurfaceFlinger --timestats -enable -clear")
            if (r != null) FpsMethod.DUMPSYS
            else if (findSysfsPath() != null) FpsMethod.SYSFS
            else FpsMethod.NONE
        } catch (_: Exception) {
            if (findSysfsPath() != null) FpsMethod.SYSFS else FpsMethod.NONE
        }
    }

    private fun canUseTaskFpsCallback(): Boolean {
        return try {
            Class.forName("android.window.TaskFpsCallback")
            windowManager.javaClass.getMethod(
                "registerTaskFpsCallback", Int::class.java,
                java.util.concurrent.Executor::class.java,
                Class.forName("android.window.TaskFpsCallback")
            )
            true
        } catch (_: Exception) { false }
    }

    private fun startTaskCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { startPolling(); return }
        taskFpsCallback = createTaskFpsCallback()
        if (taskFpsCallback == null) {
            Log.e(TAG, "TaskFpsCallback creation failed, falling back to DUMPSYS")
            activeMethod = FpsMethod.DUMPSYS
            startPolling(); return
        }
        val taskId = getFocusedTaskId()
        if (taskId > 0) registerCallback(taskId)
        else Log.w(TAG, "No focused task at start, will register via taskCheckRunnable")
        mainHandler.post(taskCheckRunnable)
        startPolling(fpsFromCallback = true)
    }

    private fun createTaskFpsCallback(): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            TaskFpsCallbackFactory.create(
                onFps = { fps ->
                    if (fps > 0f) {
                        callbackFps = fps
                        lastCallbackTime = System.currentTimeMillis()
                        recordHistory(fps)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "createTaskFpsCallback failed: ${e.message}")
            null
        }
    }

    private var _fpsCallbackInvoker: java.lang.reflect.InvocationHandler? = null

    private fun registerCallback(taskId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        unregisterCallback()
        val cb = taskFpsCallback ?: return
        try {
            val cbClass = Class.forName("android.window.TaskFpsCallback")
            val register = windowManager.javaClass.getMethod(
                "registerTaskFpsCallback",
                Int::class.java,
                java.util.concurrent.Executor::class.java,
                cbClass
            )
            register.invoke(windowManager, taskId, java.util.concurrent.Executors.newSingleThreadExecutor(), cb)
            callbackRegistered = true; currentTaskId = taskId
            lastCallbackTime = System.currentTimeMillis()
            Log.i(TAG, "TaskFpsCallback registered for taskId=$taskId")
        } catch (e: Exception) { Log.e(TAG, "registerCallback failed: ${e.message}") }
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        val cb = taskFpsCallback ?: run { callbackRegistered = false; return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val cbClass = Class.forName("android.window.TaskFpsCallback")
                val unregister = windowManager.javaClass.getMethod("unregisterTaskFpsCallback", cbClass)
                unregister.invoke(windowManager, cb)
            }
        } catch (_: Exception) {}
        callbackRegistered = false
    }

    private fun startPolling(fpsFromCallback: Boolean = false) {
        monitoringJob = serviceScope.launch {
            if (!fpsFromCallback && activeMethod == FpsMethod.DUMPSYS) {
                shell("dumpsys SurfaceFlinger --timestats -enable -clear")
                delay(800)
            }
            while (coroutineContext.isActive && isRunning) {
                val fps = when {
                    fpsFromCallback -> {
                        val stale = lastCallbackTime > 0 &&
                            System.currentTimeMillis() - lastCallbackTime > STALE_MS
                        if (stale) { callbackFps = 0f }
                        callbackFps
                    }
                    activeMethod == FpsMethod.DUMPSYS -> readDumpsysFps()
                    activeMethod == FpsMethod.SYSFS   -> readSysfsFps()
                    else                              -> 0f
                }
                if (!fpsFromCallback) recordHistory(fps)
                val snap = runCatching { SystemInfoReader.read(this@FpsService) }.getOrNull()
                mainHandler.post { updateOverlay(fps, snap) }
                delay(SAMPLE_MS)
            }
        }
    }

    private val taskCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val newId = getFocusedTaskId()
            when {
                newId > 0 && newId != currentTaskId -> reinitCallback(newId)
                !callbackRegistered && newId > 0     -> registerCallback(newId)
                callbackRegistered && lastCallbackTime > 0 &&
                    System.currentTimeMillis() - lastCallbackTime > STALE_MS ->
                        reinitCallback(newId.takeIf { it > 0 } ?: currentTaskId)
            }
            mainHandler.postDelayed(this, TASK_CHECK_MS)
        }
    }

    private fun reinitCallback(taskId: Int) {
        unregisterCallback()
        mainHandler.postDelayed({ registerCallback(taskId) }, 500)
    }

    private fun updateOverlay(fps: Float, snap: SystemSnapshot?) {
        val fpsInt = if (fps in 1f..360f) fps.roundToInt() else 0
        _currentFps.value = fpsInt.toFloat()

        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt in 1..29  -> Color.parseColor("#FF5252")
            fpsInt in 30..54 -> Color.parseColor("#FFD740")
            fpsInt >= 55     -> Color.parseColor("#69FF47")
            else             -> Color.parseColor("#80FFFFFF")
        })

        snap ?: return

        tvCpuUsage.text = if (snap.cpuUsagePct > 0f) "${snap.cpuUsagePct.toInt()}%" else "--"
        tvCpuUsage.setTextColor(when {
            snap.cpuUsagePct >= 80f -> Color.parseColor("#FF5252")
            snap.cpuUsagePct >= 50f -> Color.parseColor("#FFD740")
            else                    -> Color.parseColor("#58A6FF")
        })

        tvCpuTemp.text = if (snap.cpuTempC > 0f) "${"%.0f".format(snap.cpuTempC)}°C" else "--"
        tvCpuTemp.setTextColor(when {
            snap.cpuTempC >= 70f -> Color.parseColor("#FF5252")
            snap.cpuTempC >= 50f -> Color.parseColor("#FFD740")
            else                 -> Color.parseColor("#FF8A65")
        })

        tvGpuUsage.text = if (snap.gpuUsagePct > 0f) "${snap.gpuUsagePct.toInt()}%" else "--"
        tvGpuUsage.setTextColor(when {
            snap.gpuUsagePct >= 80f -> Color.parseColor("#FF5252")
            snap.gpuUsagePct >= 50f -> Color.parseColor("#FFD740")
            else                    -> Color.parseColor("#CE93D8")
        })

        tvGpuFreq.text = if (snap.gpuFreqMhz > 0) "${snap.gpuFreqMhz}M" else "--"

        val ramPct = if (snap.ramTotalMb > 0) snap.ramUsedMb * 100 / snap.ramTotalMb else 0L
        tvRam.text = if (snap.ramTotalMb > 0) "${snap.ramUsedMb}M" else "--"
        tvRam.setTextColor(when {
            ramPct >= 85 -> Color.parseColor("#FF5252")
            ramPct >= 65 -> Color.parseColor("#FFD740")
            else         -> Color.parseColor("#26C6DA")
        })

        tvBattery.text = "${snap.batteryPct}%"
        tvBattery.setTextColor(when {
            snap.isCharging       -> Color.parseColor("#69FF47")
            snap.batteryPct <= 15 -> Color.parseColor("#FF5252")
            snap.batteryPct <= 30 -> Color.parseColor("#FFD740")
            else                  -> Color.parseColor("#66BB6A")
        })

        tvBatTemp.text = if (snap.batteryTempC > 0f) "${"%.0f".format(snap.batteryTempC)}°C" else "--"
    }

    private fun readDumpsysFps(): Float {
        return try {
            val out = com.topjohnwu.superuser.Shell.cmd("dumpsys SurfaceFlinger --timestats -dump")
                .exec().out.joinToString("\n")
            com.topjohnwu.superuser.Shell.cmd("dumpsys SurfaceFlinger --timestats -clear -enable").exec()
            val m1 = Pattern.compile("averageFPS\\s*[=:]\\s*([0-9]+\\.?[0-9]*)").matcher(out)
            if (m1.find()) return m1.group(1)?.toFloatOrNull()?.takeIf { it in 1f..360f } ?: 0f
            val m2 = Pattern.compile("fps[=:]\\s*([0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE).matcher(out)
            if (m2.find()) return m2.group(1)?.toFloatOrNull()?.takeIf { it in 1f..360f } ?: 0f
            0f
        } catch (_: Exception) { 0f }
    }

    private fun readSysfsFps(): Float {
        val path = fpsFilePath ?: return 0f
        return try {
            val raw = com.topjohnwu.superuser.Shell.cmd("cat $path").exec().out.joinToString("").trim()
            val cleaned = raw.lowercase()
                .replace("fps","").replace("refresh","")
                .replace("rate","").replace(":","").replace("=","").trim()
            cleaned.toFloatOrNull()?.takeIf { it in 1f..360f } ?: 0f
        } catch (_: Exception) { 0f }
    }

    private fun findSysfsPath(): String? {
        val paths = listOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/graphics/fb0/measured_fps",
            "/sys/class/drm/card0/sde-crtc-0/measured_fps",
            "/sys/class/drm/card0/measured_fps",
            "/sys/class/graphics/fb0/dynamic_fps",
            "/sys/devices/platform/13000000.dispsys/fps",
            "/sys/class/drm/card0/device/perf/fps",
            "/sys/module/mali/parameters/fps",
            "/sys/class/drm/sde-crtc-1/measured_fps"
        )
        for (path in paths) {
            try {
                val exists = com.topjohnwu.superuser.Shell.cmd("[ -r $path ] && echo 1 || echo 0")
                    .exec().out.joinToString("").trim() == "1"
                if (!exists) continue
                val v = com.topjohnwu.superuser.Shell.cmd("cat $path").exec().out.joinToString("").trim()
                if (v.isNotEmpty() && v.length < 20) { fpsFilePath = path; return path }
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun getFocusedTaskId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return -1
        return try {
            val atm  = Class.forName("android.app.ActivityTaskManager")
            val svc  = atm.getDeclaredMethod("getService").invoke(null)
            val info = svc.javaClass.getMethod("getFocusedRootTaskInfo").invoke(svc) ?: return -1
            try { info.javaClass.getField("taskId").getInt(info) }
            catch (_: NoSuchFieldException) { info.javaClass.getField("mTaskId").getInt(info) }
        } catch (_: Exception) { -1 }
    }

    private fun shell(cmd: String): String? {
        return try {
            val r = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
            if (r.isSuccess) r.out.joinToString("\n") else null
        } catch (_: Exception) { null }
    }

    private fun recordHistory(fps: Float) {
        if (fps <= 0f) return
        synchronized(fpsHistory) {
            fpsHistory.add(fps)
            if (fpsHistory.size > maxHistory) fpsHistory.removeAt(0)
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object TaskFpsCallbackFactory {
    fun create(onFps: (Float) -> Unit): android.window.TaskFpsCallback {
        return object : android.window.TaskFpsCallback() {
            override fun onFpsReported(fps: Float) = onFps(fps)
        }
    }
}
