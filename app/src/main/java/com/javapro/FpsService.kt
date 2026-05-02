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

    // ── FPS state ─────────────────────────────────────────────────────────────
    // Pakai TaskFpsCallback langsung (bukan reflection) karena TaskFpsCallback.kt
    // sudah ada di project sebagai stub — sama persis dengan cara GameBar.
    private var taskFpsCallback    : android.window.TaskFpsCallback? = null
    private var callbackRegistered = false
    private var currentTaskId      = -1
    private var lastCallbackTime   = 0L
    @Volatile private var callbackFps = 0f

    private val fpsHistory = mutableListOf<Float>()
    private val maxHistory = 120

    // Refresh rate device — dipakai untuk clamp FPS agar tidak ngawur
    private var deviceRefreshRate = 60f

    private lateinit var tvFps      : TextView
    private lateinit var tvCpuUsage : TextView
    private lateinit var tvCpuTemp  : TextView
    private lateinit var tvGpuUsage : TextView
    private lateinit var tvRam      : TextView
    private lateinit var tvBattery  : TextView
    private lateinit var tvBatTemp  : TextView

    companion object {
        private const val TAG            = "FpsService"
        private const val SAMPLE_MS      = 1000L
        private const val TASK_CHECK_MS  = 1000L
        /** Kalau callback tidak update lebih dari ini, anggap stale → re-register */
        private const val STALE_MS       = 2500L
        private const val PREF_FILE      = "overlay_prefs"
        private const val PREF_X         = "overlay_x"
        private const val PREF_Y         = "overlay_y"

        private val _currentFps = MutableStateFlow(0f)
        val currentFps: StateFlow<Float> = _currentFps
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Deteksi refresh rate device sekali saja
        deviceRefreshRate = getDeviceRefreshRate()
        Log.i(TAG, "Device refresh rate: $deviceRefreshRate Hz")

        buildOverlay()
        setupParams()
        setupDrag()
        try { windowManager.addView(overlayView, params) }
        catch (e: Exception) { Log.e(TAG, "addView failed", e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        monitoringJob?.cancel()
        isRunning = true
        serviceScope.launch { startMonitoring() }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        monitoringJob?.cancel()
        unregisterFpsCallback()
        serviceScope.cancel()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ── Monitoring utama ──────────────────────────────────────────────────────

    private suspend fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            initTaskFpsCallback()
            val taskId = getFocusedTaskId()
            if (taskId > 0) registerFpsCallback(taskId)
            // Jalankan task checker di main thread
            mainHandler.post(taskCheckRunnable)
        }
        // Loop polling: update system info + ambil FPS dari callback
        startPollingLoop()
    }

    private fun startPollingLoop() {
        monitoringJob = serviceScope.launch {
            while (coroutineContext.isActive && isRunning) {
                val fps = getCurrentFps()
                val snap = runCatching { SystemInfoReader.read(this@FpsService) }.getOrNull()
                mainHandler.post { updateOverlay(fps, snap) }
                delay(SAMPLE_MS)
            }
        }
    }

    /**
     * Ambil FPS saat ini dari callback.
     * - Kalau callback stale (tidak update > STALE_MS) → return 0 (tampil --)
     * - Clamp ke deviceRefreshRate + toleransi kecil (5%) supaya tidak ngawur
     */
    private fun getCurrentFps(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0f
        val now = System.currentTimeMillis()
        val isStale = lastCallbackTime > 0 && (now - lastCallbackTime) > STALE_MS
        if (isStale) {
            callbackFps = 0f
            return 0f
        }
        val raw = callbackFps
        if (raw <= 0f) return 0f

        // Clamp: tidak boleh melebihi refresh rate + 5% toleransi
        val maxAllowed = deviceRefreshRate * 1.05f
        val clamped = raw.coerceIn(1f, maxAllowed)

        recordHistory(clamped)
        return clamped
    }

    // ── TaskFpsCallback — direct API (bukan reflection) ───────────────────────

    private fun initTaskFpsCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // Sama persis dengan cara GameBarFpsMeter: langsung subclass TaskFpsCallback
        taskFpsCallback = object : android.window.TaskFpsCallback() {
            override fun onFpsReported(fps: Float) {
                if (fps > 0f) {
                    callbackFps = fps
                    lastCallbackTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun registerFpsCallback(taskId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        unregisterFpsCallback()
        val cb = taskFpsCallback ?: return
        try {
            // Direct API — tidak pakai reflection
            windowManager.registerTaskFpsCallback(taskId, Runnable::run, cb)
            callbackRegistered = true
            currentTaskId      = taskId
            lastCallbackTime   = System.currentTimeMillis()
            Log.i(TAG, "TaskFpsCallback registered for taskId=$taskId")
        } catch (e: Exception) {
            Log.e(TAG, "registerFpsCallback failed: ${e.message}")
        }
    }

    private fun unregisterFpsCallback() {
        if (!callbackRegistered) return
        val cb = taskFpsCallback ?: run { callbackRegistered = false; return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                windowManager.unregisterTaskFpsCallback(cb)
            }
        } catch (_: Exception) {}
        callbackRegistered = false
    }

    // ── Task checker — re-register kalau task berubah atau stale ─────────────

    private val taskCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val newId = getFocusedTaskId()
            val now   = System.currentTimeMillis()
            when {
                // Task berganti → re-register ke task baru
                newId > 0 && newId != currentTaskId -> {
                    Log.d(TAG, "Task changed $currentTaskId → $newId, re-registering")
                    reinitCallback(newId)
                }
                // Belum terdaftar padahal ada task → daftarkan
                !callbackRegistered && newId > 0 -> {
                    Log.d(TAG, "Callback not registered, registering for task $newId")
                    registerFpsCallback(newId)
                }
                // Terdaftar tapi sudah stale → re-register
                callbackRegistered && lastCallbackTime > 0 &&
                    (now - lastCallbackTime) > STALE_MS -> {
                    Log.d(TAG, "Callback stale, re-registering for task ${newId.takeIf { it > 0 } ?: currentTaskId}")
                    reinitCallback(newId.takeIf { it > 0 } ?: currentTaskId)
                }
            }
            mainHandler.postDelayed(this, TASK_CHECK_MS)
        }
    }

    private fun reinitCallback(taskId: Int) {
        unregisterFpsCallback()
        // Delay sedikit sebelum re-register, sama seperti GameBar
        mainHandler.postDelayed({ if (isRunning) registerFpsCallback(taskId) }, 500)
    }

    // ── getFocusedTaskId (reflection karena API internal) ─────────────────────

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

    // ── Refresh rate detection ─────────────────────────────────────────────────

    private fun getDeviceRefreshRate(): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics  // pastikan WM sudah init
                // Ambil dari display modes
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.refreshRate
            }.let { rate ->
                // Sanity check: pastikan nilainya masuk akal
                if (rate in 30f..360f) rate else 60f
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDeviceRefreshRate failed: ${e.message}, defaulting to 60")
            60f
        }
    }

    // ── Overlay UI ────────────────────────────────────────────────────────────

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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(1.5f), 0, dp(1.5f)) }
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(4f)) }
            addView(tvFps)
            addView(fpsLabel)
        }

        tvCpuUsage = value("--", "#58A6FF")
        tvCpuTemp  = value("--", "#FF6B6B")
        tvGpuUsage = value("--", "#CE93D8")
        // tvGpuFreq DIHAPUS
        tvRam      = value("--", "#26C6DA")
        tvBattery  = value("--", "#66BB6A")
        tvBatTemp  = value("--", "#80FFFFFF")

        overlayView.addView(fpsBlock)
        overlayView.addView(divider())
        overlayView.addView(row("CPU", tvCpuUsage))
        overlayView.addView(row("TMP", tvCpuTemp))
        overlayView.addView(divider())
        overlayView.addView(row("GPU", tvGpuUsage))
        // Row FRQ DIHAPUS
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

    // ── Update overlay UI ─────────────────────────────────────────────────────

    private fun updateOverlay(fps: Float, snap: SystemSnapshot?) {
        val fpsInt = if (fps in 1f..360f) fps.roundToInt() else 0
        _currentFps.value = fpsInt.toFloat()

        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt == 0      -> Color.parseColor("#80FFFFFF")
            fpsInt in 1..29  -> Color.parseColor("#FF5252")
            fpsInt in 30..54 -> Color.parseColor("#FFD740")
            else             -> Color.parseColor("#69FF47")
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

    // ── History ───────────────────────────────────────────────────────────────

    private fun recordHistory(fps: Float) {
        if (fps <= 0f) return
        synchronized(fpsHistory) {
            fpsHistory.add(fps)
            if (fpsHistory.size > maxHistory) fpsHistory.removeAt(0)
        }
    }
}
