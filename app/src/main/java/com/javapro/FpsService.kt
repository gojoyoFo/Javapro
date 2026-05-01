package com.javapro

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import android.window.TaskFpsCallback
import com.javapro.utils.PreferenceManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.FileReader
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

class FpsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var fpsView: View
    private lateinit var params: WindowManager.LayoutParams

    private val serviceScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler    = Handler(Looper.getMainLooper())
    private var monitoringJob  : Job? = null
    private var isRunning      = false

    private var activeMethod   : FpsMethod = FpsMethod.NONE
    private var fpsFilePath    : String?   = null

    private var taskFpsCallback    : TaskFpsCallback? = null
    private var callbackRegistered = false
    private var currentTaskId      = -1
    private var lastCallbackTime   = 0L
    private var callbackFps        = 0f

    private val fpsHistory     = mutableListOf<Float>()
    private val maxHistory     = 120

    companion object {
        private const val TAG                  = "FpsService"
        private const val SAMPLE_INTERVAL_MS   = 1000L
        private const val TASK_CHECK_MS        = 1000L
        private const val STALE_THRESHOLD_MS   = 2500L
        private val _currentFps = MutableStateFlow(0f)
        val currentFps: StateFlow<Float> = _currentFps
    }

    private enum class FpsMethod {
        TASK_CALLBACK,
        DUMPSYS,
        SYSFS,
        NONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        fpsView       = LayoutInflater.from(this).inflate(R.layout.overlay_fps, null)
        setupWindowParams()
        setupDragListener()
        try {
            windowManager.addView(fpsView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        monitoringJob?.cancel()
        isRunning = true
        resolveMethodAndStart()
        return START_STICKY
    }

    private fun resolveMethodAndStart() {
        serviceScope.launch {
            activeMethod = detectBestMethod()
            Log.i(TAG, "Resolved FPS method: $activeMethod")

            when (activeMethod) {
                FpsMethod.TASK_CALLBACK -> startTaskCallbackMode()
                FpsMethod.DUMPSYS       -> startDumpsysMode()
                FpsMethod.SYSFS         -> startSysfsMode()
                FpsMethod.NONE          -> {
                    withContext(Dispatchers.Main) { updateFpsDisplay(0f) }
                }
            }
        }
    }

    private fun detectBestMethod(): FpsMethod {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val taskId = getFocusedTaskId()
            if (taskId > 0) {
                Log.d(TAG, "TaskFpsCallback available, taskId=$taskId")
                return FpsMethod.TASK_CALLBACK
            }
        }

        try {
            val result = Shell.cmd("dumpsys SurfaceFlinger --timestats -enable -clear").exec()
            if (result.isSuccess) {
                Log.d(TAG, "dumpsys timestats available")
                return FpsMethod.DUMPSYS
            }
        } catch (_: Exception) {}

        val sysfsPath = findSysfsPath()
        if (sysfsPath != null) {
            fpsFilePath = sysfsPath
            Log.d(TAG, "sysfs path found: $sysfsPath")
            return FpsMethod.SYSFS
        }

        Log.w(TAG, "No FPS method available on this device")
        return FpsMethod.NONE
    }

    private fun startTaskCallbackMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        taskFpsCallback = object : TaskFpsCallback() {
            override fun onFpsReported(fps: Float) {
                if (fps > 0f) {
                    callbackFps      = fps
                    lastCallbackTime = System.currentTimeMillis()
                    recordHistory(fps)
                    mainHandler.post { updateFpsDisplay(fps) }
                }
            }
        }

        val taskId = getFocusedTaskId()
        if (taskId <= 0) {
            Log.w(TAG, "No focused task, falling back")
            serviceScope.launch { activeMethod = FpsMethod.DUMPSYS; startDumpsysMode() }
            return
        }

        currentTaskId = taskId
        registerCallback(taskId)

        mainHandler.post(taskCheckRunnable)
    }

    private val taskCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val newTaskId = getFocusedTaskId()
            if (newTaskId > 0 && newTaskId != currentTaskId) {
                reinitCallback(newTaskId)
            } else {
                val stale = System.currentTimeMillis() - lastCallbackTime > STALE_THRESHOLD_MS && lastCallbackTime > 0
                if (stale) reinitCallback(newTaskId.takeIf { it > 0 } ?: currentTaskId)
            }
            mainHandler.postDelayed(this, TASK_CHECK_MS)
        }
    }

    private fun registerCallback(taskId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        unregisterCallback()
        try {
            taskFpsCallback?.let {
                windowManager.registerTaskFpsCallback(taskId, Runnable::run, it)
                callbackRegistered = true
                currentTaskId      = taskId
                lastCallbackTime   = System.currentTimeMillis()
                Log.d(TAG, "TaskFpsCallback registered for taskId=$taskId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register TaskFpsCallback", e)
        }
    }

    private fun unregisterCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!callbackRegistered) return
        try {
            taskFpsCallback?.let { windowManager.unregisterTaskFpsCallback(it) }
        } catch (_: Exception) {}
        callbackRegistered = false
    }

    private fun reinitCallback(taskId: Int) {
        unregisterCallback()
        mainHandler.postDelayed({ registerCallback(taskId) }, 500)
    }

    private fun startDumpsysMode() {
        monitoringJob = serviceScope.launch {
            Shell.cmd("dumpsys SurfaceFlinger --timestats -enable -clear").exec()
            delay(800)
            while (coroutineContext.isActive && isRunning) {
                val fps = readDumpsysFps()
                recordHistory(fps)
                withContext(Dispatchers.Main) { updateFpsDisplay(fps) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun startSysfsMode() {
        monitoringJob = serviceScope.launch {
            while (coroutineContext.isActive && isRunning) {
                val fps = readSysfsFps()
                recordHistory(fps)
                withContext(Dispatchers.Main) { updateFpsDisplay(fps) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun readDumpsysFps(): Float {
        return try {
            val result = Shell.cmd("dumpsys SurfaceFlinger --timestats -dump").exec()
            val output = result.out.joinToString("\n")
            Shell.cmd("dumpsys SurfaceFlinger --timestats -clear -enable").exec()

            val avgPattern  = Pattern.compile("averageFPS\\s*[=:]\\s*([0-9]+\\.?[0-9]*)")
            val avgMatcher  = avgPattern.matcher(output)
            if (avgMatcher.find()) {
                val v = avgMatcher.group(1)?.toFloatOrNull() ?: 0f
                if (v in 1f..360f) return v
            }

            val fpsPattern = Pattern.compile("fps[=:]\\s*([0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE)
            val fpsMatcher = fpsPattern.matcher(output)
            if (fpsMatcher.find()) {
                val v = fpsMatcher.group(1)?.toFloatOrNull() ?: 0f
                if (v in 1f..360f) return v
            }
            0f
        } catch (e: Exception) {
            Log.e(TAG, "dumpsys read error", e)
            0f
        }
    }

    private fun readSysfsFps(): Float {
        val path = fpsFilePath ?: return 0f
        return try {
            val result = Shell.cmd("cat $path").exec()
            val raw    = result.out.joinToString("").trim()
            if (raw.isEmpty()) return 0f

            val cleaned = raw.lowercase()
                .replace("fps", "").replace("refresh", "")
                .replace("rate", "").replace(":", "")
                .replace("=", "").trim()

            val v = cleaned.toFloatOrNull() ?: 0f
            if (v in 1f..360f) v else 0f
        } catch (e: Exception) {
            Log.e(TAG, "sysfs read error", e)
            0f
        }
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
            "/sys/class/video/fps_info",
            "/sys/module/mali/parameters/fps",
            "/sys/class/drm/sde-crtc-1/measured_fps"
        )
        for (path in paths) {
            try {
                val exists = Shell.cmd("[ -r $path ] && echo 1 || echo 0").exec()
                    .out.joinToString("").trim() == "1"
                if (!exists) continue
                val value = Shell.cmd("cat $path").exec().out.joinToString("").trim()
                if (value.isNotEmpty() && value.length < 20) {
                    Log.i(TAG, "Found sysfs FPS path: $path -> $value")
                    return path
                }
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun getFocusedTaskId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return -1
        return try {
            val atm     = Class.forName("android.app.ActivityTaskManager")
            val service = atm.getDeclaredMethod("getService").invoke(null)
            val info    = service.javaClass.getMethod("getFocusedRootTaskInfo").invoke(service)
                ?: return -1
            try { info.javaClass.getField("taskId").getInt(info) }
            catch (_: NoSuchFieldException) {
                info.javaClass.getField("mTaskId").getInt(info)
            }
        } catch (_: Exception) { -1 }
    }

    private fun recordHistory(fps: Float) {
        if (fps <= 0f) return
        synchronized(fpsHistory) {
            fpsHistory.add(fps)
            if (fpsHistory.size > maxHistory) fpsHistory.removeAt(0)
        }
    }

    private fun updateFpsDisplay(fps: Float) {
        val textView = fpsView.findViewById<TextView>(R.id.fps_text)
        val final    = if (fps in 1f..360f) fps.roundToInt() else 0

        _currentFps.value = final.toFloat()
        textView?.text    = if (final > 0) "$final" else "--"
        textView?.setTextColor(
            when {
                final in 1..29  -> android.graphics.Color.RED
                final in 30..54 -> android.graphics.Color.YELLOW
                final >= 55     -> android.graphics.Color.WHITE
                else            -> android.graphics.Color.GRAY
            }
        )
    }

    private fun setupWindowParams() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 100
        }
    }

    private fun setupDragListener() {
        fpsView.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX   = 0
            private var startY   = 0
            private var moved    = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        startX   = params.x;   startY   = params.y
                        moved    = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        params.x = startX + dx; params.y = startY + dy
                        try { windowManager.updateViewLayout(fpsView, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        monitoringJob?.cancel()
        unregisterCallback()

        serviceScope.launch {
            if (activeMethod == FpsMethod.DUMPSYS) {
                try { Shell.cmd("dumpsys SurfaceFlinger --timestats -disable").exec() } catch (_: Exception) {}
            }
        }
        serviceScope.cancel()

        if (::fpsView.isInitialized && fpsView.windowToken != null) {
            try { windowManager.removeView(fpsView) } catch (_: IllegalArgumentException) {}
        }
        super.onDestroy()
    }
}
