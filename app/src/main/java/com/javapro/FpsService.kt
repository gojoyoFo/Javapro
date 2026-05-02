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

    private var prevCpuIdle  = -1L
    private var prevCpuTotal = -1L
    private val cpuLock = Any()

    @Volatile private var cachedGpuUsagePath: String? = null
    @Volatile private var gpuPathDetected = false

    // FPS state
    private var taskFpsCallbackObj: Any? = null
    private var callbackRegistered = false
    private var currentTaskId = -1
    @Volatile private var callbackFps = 0f
    private var lastFpsUpdateTime = System.currentTimeMillis()

    @Volatile private var sfFps = 0f

    // Timestats delta state
    private val tsLayerSnapshot = mutableMapOf<String, Long>()
    private var tsLastMs = 0L
    private val TS_LAYER_SKIP = listOf(
        "NavigationBar", "StatusBar", "ScreenDecor", "InputMethod",
        "com.javapro", "WallpaperSurface", "pip-dismiss", "Splash Screen",
        "ShellDropTarget", "PointerLocation", "mouse pointer"
    )

    companion object {
        private const val TAG = "FpsService"
        private const val POLL_MS = 1000L
        private const val STALENESS_THRESHOLD_MS = 2000L
        private const val TASK_CHECK_INTERVAL_MS = 1000L
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
        if (!isRunning) {
            isRunning = true
            startFpsTracking()
            serviceScope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopFpsTracking()
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        if (::overlayView.isInitialized && overlayView.windowToken != null) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ── FPS Tracking ─────────────────────────────────────────────────────────

    private fun startFpsTracking() {
        stopFpsTracking()
        val rooted = hasRoot()
        Log.i(TAG, "startFpsTracking rooted=$rooted sdkInt=${Build.VERSION.SDK_INT}")
        when {
            rooted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                initTaskFpsCallback()
                val taskId = getFocusedTaskId()
                if (taskId > 0) registerCallback(taskId)
                mainHandler.post(taskCheckRunnable)
            }
            else -> {
                serviceScope.launch { sfPollLoop() }
            }
        }
    }

    private fun stopFpsTracking() {
        unregisterCallback()
        mainHandler.removeCallbacks(taskCheckRunnable)
    }

    private fun reinitCallback() {
        unregisterCallback()
        mainHandler.postDelayed({
            if (isRunning) {
                val taskId = getFocusedTaskId()
                if (taskId > 0) registerCallback(taskId)
            }
        }, 500)
    }

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
            Log.w(TAG, "initTaskFpsCallback failed: ${e.message}")
            taskFpsCallbackObj = null
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
            currentTaskId = taskId
            lastFpsUpdateTime = System.currentTimeMillis()
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
                val newTaskId = getFocusedTaskId()
                if (newTaskId > 0 && newTaskId != currentTaskId) {
                    reinitCallback()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastFpsUpdateTime > STALENESS_THRESHOLD_MS) {
                        reinitCallback()
                    }
                }
            }
            mainHandler.postDelayed(this, TASK_CHECK_INTERVAL_MS)
        }
    }

    private fun getFocusedTaskId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return -1
        return try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getServiceMethod = atmClass.getDeclaredMethod("getService")
            val atmService = getServiceMethod.invoke(null)
            val taskInfo = atmService.javaClass
                .getMethod("getFocusedRootTaskInfo")
                .invoke(atmService) ?: return -1
            try {
                taskInfo.javaClass.getField("taskId").getInt(taskInfo)
            } catch (_: NoSuchFieldException) {
                taskInfo.javaClass.getField("mTaskId").getInt(taskInfo)
            }
        } catch (_: Exception) { -1 }
    }

    // ── SurfaceFlinger FPS ────────────────────────────────────────────────────
    // Non-root: pakai "dumpsys SurfaceFlinger" (tanpa --fps).
    // Output lengkap berisi baris "fps=XX.XX" atau "FPS: XX.XX" per layer.
    // Kita ambil nilai tertinggi dari semua layer aktif = FPS foreground app.
    //
    // Root/Shizuku Android < 13 fallback: sama, pakai SurfaceFlinger juga
    // karena lebih reliable dari gfxinfo untuk semua ROM.

    private var foregroundPkg    = ""
    private var fgPkgLastCheck   = 0L

    private suspend fun sfPollLoop() {
        foregroundPkg  = getForegroundPackage()
        fgPkgLastCheck = System.currentTimeMillis()
        while (currentCoroutineContext().isActive && isRunning) {
            sfFps = readFpsNonRoot()
            delay(POLL_MS)
        }
    }

    private fun readFpsNonRoot(): Float {
        val now = System.currentTimeMillis()
        if (foregroundPkg.isEmpty() || now - fgPkgLastCheck > 5000L) {
            foregroundPkg  = getForegroundPackage()
            fgPkgLastCheck = now
        }
        val pkg = foregroundPkg
        // Metode 1: gfxinfo framestats — akurat, berjalan root & non-root
        if (pkg.isNotEmpty() && pkg != packageName) {
            val fps = readGfxInfoFps(pkg)
            if (fps > 0f) return fps
        }
        // Metode 2: --timestats delta — reliable semua mode, tidak perlu tahu package
        val tsFps = readFpsTimestats()
        if (tsFps > 0f) return tsFps
        // Metode 3: SurfaceFlinger --fps — ringan, Android 11+
        return readSfFpsFlag()
    }

    /**
     * Baca fps via `dumpsys SurfaceFlinger --timestats -dump`.
     * Cara kerja: hitung delta totalFrames antar poll / delta waktu = fps aktual.
     * Berjalan root (su) maupun non-root (sh) — tidak perlu tahu package foreground.
     */
    private fun readFpsTimestats(): Float {
        return try {
            val output = runShellCommand("dumpsys SurfaceFlinger --timestats -dump")
            if (output.isBlank()) return 0f

            val nowMs   = System.currentTimeMillis()
            val elapsed = if (tsLastMs > 0L) nowMs - tsLastMs else 0L
            tsLastMs    = nowMs

            // Parse semua layer + totalFrames
            val frameMap  = mutableMapOf<String, Long>()
            var curLayer: String? = null
            for (line in output.lines()) {
                val t = line.trim()
                when {
                    t.startsWith("layerName = ") ->
                        curLayer = t.removePrefix("layerName = ").trim()
                    t.startsWith("Layer: ") ->
                        curLayer = t.removePrefix("Layer: ").trim()
                    t.startsWith("totalFrames = ") && curLayer != null -> {
                        val f = t.removePrefix("totalFrames = ").trim().toLongOrNull()
                        if (f != null && f > 0L) frameMap[curLayer!!] = f
                        curLayer = null
                    }
                }
            }
            if (frameMap.isEmpty()) return 0f

            // Pilih layer dengan totalFrames terbesar, skip system UI
            val bestLayer = frameMap.entries
                .filter { (l, _) -> TS_LAYER_SKIP.none { s -> l.contains(s, ignoreCase = true) } }
                .maxByOrNull { it.value }
                ?.key ?: return 0f

            val current = frameMap[bestLayer] ?: return 0f
            val prev    = tsLayerSnapshot[bestLayer]
            tsLayerSnapshot[bestLayer] = current

            if (prev == null || elapsed <= 0L) return 0f
            val delta = current - prev
            if (delta <= 0L) return 0f

            val fps = delta * 1000f / elapsed
            Log.d(TAG, "timestats layer=$bestLayer fps=%.1f delta=$delta elapsed=${elapsed}ms".format(fps))
            val maxRefresh = getDeviceRefreshRate() * 1.05f
            if (fps in 1f..maxRefresh) fps else 0f
        } catch (e: Exception) {
            Log.w(TAG, "readFpsTimestats: ${e.message}")
            0f
        }
    }

    private fun readGfxInfoFps(pkg: String): Float {
        return try {
            val output = runShellCommand("dumpsys gfxinfo $pkg framestats")
            if (output.isBlank()) return 0f
            val lines = output.lines()

            // Cari baris header kolom
            val headerIdx = lines.indexOfFirst { l ->
                l.startsWith("Flags,") || l.startsWith("IntendedVsync,")
            }
            if (headerIdx < 0) return 0f

            val header   = lines[headerIdx].split(",").map { it.trim() }
            val flagCol  = header.indexOf("Flags")
            val vsyncCol = header.indexOf("IntendedVsync")
            if (vsyncCol < 0) return 0f

            val timestamps = mutableListOf<Long>()
            for (i in headerIdx + 1 until lines.size) {
                val l = lines[i].trim()
                if (l.isEmpty() || l.startsWith("---")) break
                val cols = l.split(",")
                if (flagCol >= 0) {
                    val flag = cols.getOrNull(flagCol)?.trim()?.toLongOrNull() ?: continue
                    if (flag != 0L) continue
                }
                val ts = cols.getOrNull(vsyncCol)?.trim()?.toLongOrNull() ?: continue
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
            val maxRefresh = getDeviceRefreshRate() * 1.05f
            if (fps in 1f..maxRefresh) fps else 0f
        } catch (_: Exception) { 0f }
    }

    private fun readSfFpsFlag(): Float {
        return try {
            val out = runShellCommand("dumpsys SurfaceFlinger --fps")
            if (out.isBlank()) return 0f
            val maxRefresh = getDeviceRefreshRate() * 1.05f
            listOf(
                Regex("""([\d.]+)\s+fps""", RegexOption.IGNORE_CASE),
                Regex("""fps\s*=\s*([\d.]+)""", RegexOption.IGNORE_CASE),
                Regex("""fps\s*:\s*([\d.]+)""", RegexOption.IGNORE_CASE)
            ).forEach { re ->
                val v = re.find(out)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return@forEach
                if (v in 1f..maxRefresh) return v
            }
            0f
        } catch (_: Exception) { 0f }
    }

    // UsageStatsManager — tidak butuh root, tapi butuh permission PACKAGE_USAGE_STATS (manual grant)
    // HomeScreen sudah handle redirect ke Settings kalau belum di-grant
    private fun getForegroundPackage(): String {
        return try {
            val usm = getSystemService(android.content.Context.USAGE_STATS_SERVICE)
                as? android.app.usage.UsageStatsManager ?: return ""
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 10_000L, now
            )
            stats?.filter { it.lastTimeUsed > 0 }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName ?: ""
        } catch (_: Exception) { "" }
    }

    private fun runShellCommand(cmd: String): String {
        return try {
            // Pakai su kalau root tersedia, sh -c kalau tidak — WAJIB pakai shell wrapper
            // supaya PATH dan piping berfungsi. exec(String) tanpa wrapper sering gagal.
            val args = if (hasRoot()) arrayOf("su", "-c", cmd) else arrayOf("sh", "-c", cmd)
            val proc = Runtime.getRuntime().exec(args)
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            proc.destroy()
            out
        } catch (_: Exception) { "" }
    }

    private fun getDeviceRefreshRate(): Float {
        return try {
            @Suppress("DEPRECATION")
            val rate = windowManager.defaultDisplay.refreshRate
            if (rate in 30f..360f) rate else 60f
        } catch (_: Exception) { 60f }
    }

    private fun getCurrentFps(): Float {
        val maxRefresh = getDeviceRefreshRate() * 1.05f
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> sfFps.coerceIn(0f, maxRefresh)
            callbackRegistered -> {
                val now = System.currentTimeMillis()
                if (lastFpsUpdateTime > 0 && (now - lastFpsUpdateTime) > STALENESS_THRESHOLD_MS) {
                    callbackFps = 0f
                    sfFps.coerceIn(0f, maxRefresh)
                } else {
                    callbackFps.coerceIn(0f, maxRefresh)
                }
            }
            else -> sfFps.coerceIn(0f, maxRefresh)
        }
    }

    // ── Poll loop ─────────────────────────────────────────────────────────────

    private suspend fun pollLoop() {
        // Warmup: 2 baca berjarak 500ms supaya delta CPU pertama valid
        readCpuFromProcStat()
        delay(500)
        readCpuFromProcStat()
        delay(500)
        while (currentCoroutineContext().isActive && isRunning) {
            val fps     = getCurrentFps()
            val cpuLoad = readCpuLoad()
            val gpuLoad = readGpuLoadAuto()
            val batTemp = readBatteryTemp()
            mainHandler.post { updateOverlay(fps, cpuLoad, gpuLoad, batTemp) }
            delay(POLL_MS)
        }
    }

    // ── CPU Load ──────────────────────────────────────────────────────────────
    // /proc/stat bisa dibaca tanpa root di semua Android versi.
    // Kalau gagal (ROM aneh/SELinux ketat), fallback ke ActivityManager.

    private fun readCpuLoad(): Int {
        // Coba baca /proc/stat langsung
        val fromProc = readCpuFromProcStat()
        if (fromProc >= 0) return fromProc

        // Fallback: estimasi dari ActivityManager (kurang akurat tapi selalu bisa)
        return readCpuFromActivityManager()
    }

    private fun readCpuFromProcStat(): Int {
        return try {
            // File.readLines() — sama persis dengan HomeScreen, terbukti bisa non-root
            val directLine = try {
                File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
            } catch (_: Exception) { null }

            // Fallback shell: root pakai su, non-root langsung cat
            val line = directLine ?: run {
                val cmd = if (hasRoot()) "su -c cat /proc/stat" else "cat /proc/stat"
                runShellCommand(cmd).lines().firstOrNull { it.startsWith("cpu ") }
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
            val total     = user + nice + system + totalIdle + irq + softirq + steal

            synchronized(cpuLock) {
                val prevT = prevCpuTotal
                val prevI = prevCpuIdle
                prevCpuTotal = total
                prevCpuIdle  = totalIdle
                if (prevT < 0) return@synchronized -1
                val dTotal = total - prevT
                val dIdle  = totalIdle - prevI
                if (dTotal <= 0) return@synchronized -1
                ((100f * (dTotal - dIdle) / dTotal).coerceIn(0f, 100f)).toInt()
            }
        } catch (e: Exception) {
            Log.d(TAG, "readCpuFromProcStat failed: ${e.message}")
            -1
        }
    }

    private fun readCpuFromActivityManager(): Int {
        return try {
            // ActivityManager.getProcessMemoryInfo tidak kasih CPU tapi kita bisa
            // estimasi dari jumlah proses aktif / memory pressure sebagai proxy.
            // Ini bukan CPU usage yang akurat tapi lebih baik dari "--".
            // Alternatif: baca /proc/loadavg yang lebih mudah di-parse.
            val line = BufferedReader(FileReader("/proc/loadavg")).use { it.readLine() }
                ?: return -1
            // Format: "0.52 0.48 0.44 1/312 1234"
            // Nilai pertama = load average 1 menit
            // Normalize ke 0-100 berdasarkan jumlah CPU core
            val load1min = line.trim().split("\\s+".toRegex()).firstOrNull()?.toFloatOrNull()
                ?: return -1
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((load1min / cores) * 100f).coerceIn(0f, 100f).toInt()
        } catch (_: Exception) { -1 }
    }

    // ── GPU Load ──────────────────────────────────────────────────────────────
    // Auto-detect root tanpa bergantung fpsMethod (yang tidak dikirim dari HomeScreen).
    // Root: su shell bypass SELinux. Non-root: coba baca langsung.

    @Volatile private var isRootConfirmed = false
    @Volatile private var rootCheckDone   = false

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

    private fun readGpuLoadAuto(): Int {
        if (hasRoot()) return readGpuLoadViaShell()
        val path = getGpuUsagePath()
        if (path != null) return tryReadGpuUsage(path)
        return -1
    }

    // Baca GPU lewat su shell — ini yang BENAR-BENAR berhasil di root
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

        // Kalau sudah punya cached path, pakai itu dulu
        val cached = if (gpuPathDetected) cachedGpuUsagePath else null
        if (cached != null) {
            val result = readGpuPathViaShell(cached)
            if (result >= 0) return result
            // Path lama tidak valid lagi, reset
            gpuPathDetected = false
            cachedGpuUsagePath = null
        }

        // Scan semua kandidat via shell
        for (path in candidates) {
            val result = readGpuPathViaShell(path)
            if (result >= 0) {
                cachedGpuUsagePath = path
                gpuPathDetected = true
                Log.i(TAG, "GPU path via shell found: $path -> $result%")
                return result
            }
        }

        // Scan devfreq dinamis via shell
        return readGpuFromDevfreqShell()
    }

    private fun readGpuPathViaShell(path: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor()
            proc.destroy()
            if (line.isNullOrEmpty()) return -1
            parseGpuLine(path, line)
        } catch (_: Exception) { -1 }
    }

    private fun readGpuFromDevfreqShell(): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "for d in /sys/class/devfreq/*; do n=\$(basename \$d | tr '[:upper:]' '[:lower:]'); " +
                "case \$n in *gpu*|*kgsl*|*mali*|*mfg*) " +
                "if [ -f \$d/gpu_busy_percentage ]; then cat \$d/gpu_busy_percentage && exit; fi; " +
                "esac; done; echo -1"))
            val line = proc.inputStream.bufferedReader().readLine()?.trim()
            proc.waitFor()
            proc.destroy()
            line?.toIntOrNull()?.coerceIn(-1, 100) ?: -1
        } catch (_: Exception) { -1 }
    }

    // Fallback baca langsung (non-root, kalau device izinkan)
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
                Log.i(TAG, "GPU usage path (direct): $path")
                return path
            }
        }
        // Scan devfreq
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
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return raw / 10f
    }

    // ── Overlay UI ────────────────────────────────────────────────────────────

    private fun updateOverlay(fps: Float, cpuLoad: Int, gpuLoad: Int, batTemp: Float) {
        val fpsInt = if (fps in 1f..400f) fps.toInt() else 0
        tvFps.text = if (fpsInt > 0) "$fpsInt" else "--"
        tvFps.setTextColor(when {
            fpsInt == 0 -> Color.parseColor("#80FFFFFF")
            fpsInt < 30 -> Color.parseColor("#FF5252")
            fpsInt < 55 -> Color.parseColor("#FFD740")
            else        -> Color.parseColor("#69FF47")
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

    private fun readLine(path: String): String? {
        return try { BufferedReader(FileReader(path)).use { it.readLine() } }
        catch (_: IOException) { null }
        catch (_: Exception)   { null }
    }
}
