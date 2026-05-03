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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.javapro.fps.IFpsProvider
import com.javapro.fps.RootFpsProvider
import com.javapro.fps.ShizukuFpsProvider
import com.javapro.utils.ShizukuManager
import com.javapro.utils.SystemInfoReader
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

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

    private var fpsProvider: IFpsProvider? = null

    // TaskFpsCallback (root API33+ — paling akurat)
    private var taskFpsCallbackObj: Any? = null
    private var callbackRegistered = false
    private var currentTaskId = -1
    @Volatile private var callbackFps = 0f
    private var lastFpsUpdateTime = System.currentTimeMillis()

    // HandlerThread khusus untuk shell/blocking ops (task check)
    private val shellThread = HandlerThread("FpsShellThread").also { it.start() }
    private val shellHandler = Handler(shellThread.looper)

    // Root detection cache — hanya dicek SATU kali
    @Volatile private var isRootConfirmed = false
    @Volatile private var rootCheckDone   = false
    private val rootCheckLock = Any()

    // Consecutive zero-fps counter
    @Volatile private var consecutiveZeroFps = 0
    private val ZERO_FPS_THRESHOLD = 3

    companion object {
        private const val TAG = "FpsService"
        private const val POLL_MS        = 1000L
        private const val STALENESS_MS   = 2000L
        private const val TASK_CHECK_MS  = 1000L
        private const val SYSINFO_POLL_MS = 1200L
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
        catch (e: Exception) { Log.e(TAG, "addView: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            serviceScope.launch(Dispatchers.IO) {
                initFpsProvider()
                startTaskFpsCallbackIfRoot()
                // FPS loop dan System Info loop berjalan paralel dan independen
                launch { pollFpsLoop() }
                launch { pollSystemInfoLoop() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        unregisterCallback()
        mainHandler.removeCallbacks(taskCheckRunnable)
        fpsProvider?.release()
        fpsProvider = null
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        shellThread.quitSafely()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ── Provider selection ────────────────────────────────────────────────────

    private fun initFpsProvider() {
        val refreshRate = getDeviceRefreshRate()
        fpsProvider = when {
            hasRoot() -> {
                Log.i(TAG, "FPS strategy: RootFpsProvider (persistent shell)")
                RootFpsProvider(refreshRate)
            }
            ShizukuManager.isAvailable() -> {
                Log.i(TAG, "FPS strategy: ShizukuFpsProvider (shizuku)")
                ShizukuFpsProvider(refreshRate)
            }
            else -> {
                Log.i(TAG, "FPS strategy: ShizukuFpsProvider (sh fallback)")
                ShizukuFpsProvider(refreshRate)
            }
        }
    }

    // ── TaskFpsCallback (root API33+) ─────────────────────────────────────────

    private fun startTaskFpsCallbackIfRoot() {
        if (!hasRoot() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        initTaskFpsCallback()
        shellHandler.post {
            val taskId = getFocusedTaskId()
            if (taskId > 0) mainHandler.post { registerCallback(taskId) }
            mainHandler.post(taskCheckRunnable)
        }
    }

    private fun initTaskFpsCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            taskFpsCallbackObj = object : android.window.TaskFpsCallback() {
                override fun onFpsReported(fps: Float) {
                    if (fps > 0f) { callbackFps = fps; lastFpsUpdateTime = System.currentTimeMillis() }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "initTaskFpsCallback: ${e.message}") }
    }

    private fun registerCallback(taskId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val cb = taskFpsCallbackObj ?: return false
        return try {
            // Cari di WindowManager interface, bukan di class implementasi (WindowManagerImpl)
            val method = WindowManager::class.java.getMethod(
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
            Log.e(TAG, "registerCallback FAILED: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        val cb = taskFpsCallbackObj ?: run { callbackRegistered = false; return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                WindowManager::class.java.getMethod(
                    "unregisterTaskFpsCallback",
                    android.window.TaskFpsCallback::class.java
                ).invoke(windowManager, cb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "unregisterCallback: ${e.message}")
        }
        callbackRegistered = false
    }

    private fun reinitCallback() {
        unregisterCallback()
        mainHandler.postDelayed({
            if (isRunning) shellHandler.post {
                val id = getFocusedTaskId()
                if (id > 0) mainHandler.post { registerCallback(id) }
            }
        }, 500)
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
                    } else if (now - lastFpsUpdateTime > STALENESS_MS) {
                        mainHandler.post { reinitCallback() }
                    }
                }
            }
            mainHandler.postDelayed(this, TASK_CHECK_MS)
        }
    }

    private fun getFocusedTaskId(): Int {
        // ActivityTaskManager.getService() blocked di TargetSdk 36 — pakai shell su
        return try {
            // "am stack list" output: "taskId=123 ..."  baris focused task
            val output = runSuCommand("dumpsys activity activities | grep -E 'mFocused|isFocused=true' | grep -oE 'taskId=[0-9]+' | head -1")
            val taskId = Regex("""taskId=(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            if (taskId > 0) Log.d(TAG, "getFocusedTaskId via su: $taskId")
            taskId
        } catch (_: Exception) { -1 }
    }

    // ── FPS resolution: TaskFpsCallback > Provider ────────────────────────────

    private fun getCurrentFps(): Float {
        val max = getDeviceRefreshRate() * 1.05f
        if (callbackRegistered) {
            val now = System.currentTimeMillis()
            if (now - lastFpsUpdateTime < STALENESS_MS) {
                val v = callbackFps.coerceIn(0f, max)
                if (v > 0f) return v
            }
        }
        return fpsProvider?.getInstantFps()?.coerceIn(0f, max) ?: 0f
    }

    // ── Poll loop: FPS (independen dari System Info) ──────────────────────────

    private suspend fun pollFpsLoop() {
        // RootFpsProvider memiliki internal sampler 250ms, tunggu sampai warmup selesai
        delay(900)

        while (currentCoroutineContext().isActive && isRunning) {
            // getInstantFps() sekarang NON-BLOCKING:
            // RootFpsProvider mengembalikan nilai cached dari background sampler 250ms.
            // ShizukuFpsProvider tetap blocking ringan (sysfs/shell).
            val fps = withContext(Dispatchers.IO) { getCurrentFps() }

            // Log jika root provider beralih ke fallback
            val provider = fpsProvider
            if (provider is RootFpsProvider && provider.rootExhausted) {
                Log.i(TAG, "RootFpsProvider exhausted → running via non-root fallback internally")
            }

            if (fps <= 0f) {
                consecutiveZeroFps++
                if (consecutiveZeroFps >= ZERO_FPS_THRESHOLD) {
                    Log.w(TAG, "FPS stuck at 0 for $consecutiveZeroFps ticks")
                    consecutiveZeroFps = 0
                }
            } else {
                consecutiveZeroFps = 0
            }

            // SELALU update UI meski fps == 0 → tampil "--" bukan hilang
            withContext(Dispatchers.Main) {
                updateFpsOverlay(fps)
            }

            delay(POLL_MS)
        }
    }

    // ── Poll loop: CPU/GPU/BAT (independen dari FPS) ──────────────────────────

    private suspend fun pollSystemInfoLoop() {
        // Warmup: baca sekali untuk init prevIdle/prevTotal
        withContext(Dispatchers.IO) { SystemInfoReader.read(this@FpsService) }
        delay(400)

        while (currentCoroutineContext().isActive && isRunning) {
            val snapshot = withContext(Dispatchers.IO) {
                SystemInfoReader.read(this@FpsService)
            }

            // Update CPU/GPU/BAT — selalu dipanggil meski FPS sedang "--"
            withContext(Dispatchers.Main) {
                updateSystemOverlay(
                    cpuLoad = snapshot.cpuUsagePct.toInt(),
                    gpuLoad = snapshot.gpuUsagePct.toInt(),
                    batTemp = snapshot.batteryTempC
                )
            }

            delay(SYSINFO_POLL_MS)
        }
    }

    private fun hasRoot(): Boolean {
        if (rootCheckDone) return isRootConfirmed
        return synchronized(rootCheckLock) {
            if (rootCheckDone) return@synchronized isRootConfirmed
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
                val out  = proc.inputStream.bufferedReader().use { it.readLine()?.trim() }
                proc.errorStream.bufferedReader().use { it.readText() }
                val exited = proc.waitFor(2000, TimeUnit.MILLISECONDS)
                if (!exited) proc.destroyForcibly()
                proc.destroy()
                isRootConfirmed = out == "ok"
                rootCheckDone   = true
                Log.i(TAG, "Root check: $isRootConfirmed")
                isRootConfirmed
            } catch (_: Exception) {
                rootCheckDone = true
                false
            }
        }
    }

    private fun getDeviceRefreshRate(): Float {
        return try {
            @Suppress("DEPRECATION")
            val r = windowManager.defaultDisplay.refreshRate
            if (r in 30f..360f) r else 60f
        } catch (_: Exception) { 60f }
    }

    // ── Overlay UI ────────────────────────────────────────────────────────────

    /** Update hanya baris FPS. Dipanggil SELALU, meski fps == 0. */
    private fun updateFpsOverlay(fps: Float) {
        val fpsInt = if (fps in 1f..400f) fps.toInt() else 0
        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt == 0  -> Color.parseColor("#80FFFFFF")
            fpsInt < 30  -> Color.parseColor("#FF5252")
            fpsInt < 55  -> Color.parseColor("#FFD740")
            else         -> Color.parseColor("#69FF47")
        })
    }

    /** Update CPU/GPU/BAT. Dipanggil oleh pollSystemInfoLoop secara independen. */
    private fun updateSystemOverlay(cpuLoad: Int, gpuLoad: Int, batTemp: Float) {
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
            text = "--"; textSize = 38f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL; setTextColor(Color.WHITE)
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
    private fun runSuCommand(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            proc.destroy()
            out.trim()
        } catch (_: Exception) { "" }
    }

}
