/*
 * Original FPS detection logic from: helloklf (vtools)
 * Modified and integrated by: Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.javapro

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.TextView
import com.javapro.utils.PreferenceManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.roundToInt

class FpsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var fpsView: View
    private lateinit var params: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    private var isRunning = false

    private var currentMode: FpsMode = FpsMode.UNIVERSAL_DEVICES
    private var fpsFilePath: String? = null

    companion object {
        private const val TAG = "FpsService"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private val _currentFps = MutableStateFlow(0f)
        val currentFps: StateFlow<Float> = _currentFps
    }

    enum class FpsMode {
        UNIVERSAL_GPU,
        UNIVERSAL_DEVICES
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FpsService created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        fpsView = LayoutInflater.from(this).inflate(R.layout.overlay_fps, null)
        setupWindowParams()
        setupDragListener()
        try {
            windowManager.addView(fpsView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        val prefManager = PreferenceManager(this)
        val modeString = prefManager.fpsModeFlow.value
        Log.d(TAG, "Mode from prefs: $modeString")

        currentMode = when (modeString) {
            "universal_gpu" -> FpsMode.UNIVERSAL_GPU
            else -> FpsMode.UNIVERSAL_DEVICES
        }

        Log.d(TAG, "Current mode: $currentMode")

        fpsFilePath = null

        monitoringJob?.cancel()
        isRunning = true
        startFpsMonitoring()

        return START_STICKY
    }

    private fun setupWindowParams() {
        val layoutType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
    }

    private fun setupDragListener() {
        fpsView.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(fpsView, params)
                        } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startFpsMonitoring() {
        monitoringJob = serviceScope.launch {
            if (currentMode == FpsMode.UNIVERSAL_DEVICES) {
                Log.d(TAG, "Enabling timestats")
                Shell.cmd("dumpsys SurfaceFlinger --timestats -enable -clear").exec()
            }

            delay(800)

            while (coroutineContext.isActive && isRunning) {
                val fps = try {
                    when (currentMode) {
                        FpsMode.UNIVERSAL_GPU -> getKernelFps()
                        FpsMode.UNIVERSAL_DEVICES -> getDumpsysFps()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading FPS", e)
                    0f
                }

                Log.d(TAG, "FPS Read: $fps (Mode: $currentMode)")

                withContext(Dispatchers.Main) {
                    updateFpsDisplay(fps)
                }

                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun getKernelFps(): Float {
        if (fpsFilePath.isNullOrEmpty()) {
            findFpsPath()
            if (fpsFilePath.isNullOrEmpty()) {
                Log.w(TAG, "No valid FPS file path found")
                return 0f
            }
        }

        return try {
            val result = Shell.cmd("cat $fpsFilePath").exec()
            val output = result.out.joinToString("").trim()

            Log.d(TAG, "Kernel FPS ($fpsFilePath): '$output'")

            if (output.isEmpty()) return 0f

            val cleaned = output.lowercase()
                .replace("fps", "")
                .replace("refresh", "")
                .replace("rate", "")
                .replace(":", "")
                .replace("=", "")
                .trim()

            val fps = cleaned.toFloatOrNull()
            if (fps != null && fps in 1f..360f) {
                Log.d(TAG, "Parsed kernel FPS: $fps")
                fps
            } else {
                Log.w(TAG, "Failed to parse FPS from: '$output' -> cleaned: '$cleaned'")
                0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading kernel FPS", e)
            0f
        }
    }

    private fun findFpsPath() {
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
                val checkResult = Shell.cmd("[ -r $path ] && echo 1 || echo 0").exec()
                val exists = checkResult.out.joinToString("").trim() == "1"
                if (exists) {
                    val testRead = Shell.cmd("cat $path").exec()
                    val testOutput = testRead.out.joinToString("").trim()
                    if (testOutput.isNotEmpty() && testOutput.length < 20) {
                        fpsFilePath = path
                        Log.i(TAG, "Found valid FPS path: $path -> $testOutput")
                        return
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        fpsFilePath = ""
        Log.w(TAG, "No FPS path found on this device")
    }

    private fun getDumpsysFps(): Float {
        return try {
            val dumpResult = Shell.cmd("dumpsys SurfaceFlinger --timestats -dump").exec()
            val dumpOutput = dumpResult.out.joinToString("\n")

            Shell.cmd("dumpsys SurfaceFlinger --timestats -clear -enable").exec()

            val avgPattern = Pattern.compile("averageFPS\\s*[=:]\\s*([0-9]+\\.?[0-9]*)")
            val matcher = avgPattern.matcher(dumpOutput)
            if (matcher.find()) {
                val value = matcher.group(1)?.toFloatOrNull() ?: 0f
                Log.d(TAG, "Parsed timestats averageFPS: $value")
                return value
            }

            val fpsPattern = Pattern.compile("fps[=:]\\s*([0-9]+\\.?[0-9]*)", Pattern.CASE_INSENSITIVE)
            val fpsMatcher = fpsPattern.matcher(dumpOutput)
            if (fpsMatcher.find()) {
                val value = fpsMatcher.group(1)?.toFloatOrNull() ?: 0f
                Log.d(TAG, "Parsed fallback FPS: $value")
                return value
            }

            0f
        } catch (e: Exception) {
            Log.e(TAG, "Error timestats", e)
            0f
        }
    }

    private fun updateFpsDisplay(fps: Float) {
        val textView = fpsView.findViewById<TextView>(R.id.fps_text)

        val finalFps = when {
            fps > 360f || fps < 0f -> 0
            else -> fps.roundToInt()
        }

        _currentFps.value = finalFps.toFloat()

        textView?.text = if (finalFps > 0) "$finalFps" else "--"

        textView?.setTextColor(
            when {
                finalFps in 1..29 -> android.graphics.Color.RED
                finalFps in 30..54 -> android.graphics.Color.YELLOW
                finalFps >= 55 -> android.graphics.Color.WHITE
                else -> android.graphics.Color.GRAY
            }
        )

        if (finalFps > 0) Log.d(TAG, "Display FPS: $finalFps")
    }

    override fun onDestroy() {
        Log.d(TAG, "FpsService destroying")
        isRunning = false
        monitoringJob?.cancel()

        serviceScope.launch {
            if (currentMode == FpsMode.UNIVERSAL_DEVICES) {
                Shell.cmd("dumpsys SurfaceFlinger --timestats -disable").exec()
            }
        }

        serviceScope.cancel()

        if (::fpsView.isInitialized && fpsView.windowToken != null) {
            try {
                windowManager.removeView(fpsView)
            } catch (_: IllegalArgumentException) {}
        }

        super.onDestroy()
    }
}
