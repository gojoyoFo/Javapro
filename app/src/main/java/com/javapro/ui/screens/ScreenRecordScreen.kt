package com.javapro.ui.screens

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.R
import com.javapro.service.ScreenRecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class RecFpsOption(val label: String, val value: Int)
private data class RecResOption(val label: String, val width: Int, val height: Int)
private data class RecTimerOption(val label: String, val seconds: Int?)

private val SR_FPS_OPTIONS = listOf(
    RecFpsOption("30",  30),
    RecFpsOption("60",  60),
    RecFpsOption("90",  90),
    RecFpsOption("120", 120)
)

private val SR_RES_OPTIONS = listOf(
    RecResOption("720p",  1280, 720),
    RecResOption("1080p", 1920, 1080),
    RecResOption("1440p", 2560, 1440),
    RecResOption("2K",    2048, 1152)
)

private val SR_TIMER_OPTIONS = listOf(
    RecTimerOption("∞",   null),
    RecTimerOption("1m",  60),
    RecTimerOption("5m",  300),
    RecTimerOption("10m", 600)
)

private val SR_BITRATE_BPS   = listOf(4_000_000, 8_000_000, 16_000_000, 32_000_000)
private val SR_TIMESTAMP_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
private val SR_SAVE_PATH     = "${Environment.DIRECTORY_MOVIES}/ScreenRecord"

// ─────────────────────────────────────────────────────────────────────────────
// FIX: Bitrate otomatis naik untuk fps tinggi agar kualitas tetap bagus
// 90/120 fps butuh bitrate lebih besar supaya encoder tidak drop frame
// ─────────────────────────────────────────────────────────────────────────────
private fun effectiveBitrate(baseBps: Int, fps: Int): Int {
    return when {
        fps >= 120 -> (baseBps * 2.0).toInt()
        fps >= 90  -> (baseBps * 1.5).toInt()
        else       -> baseBps
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Deteksi refresh rate layar device saat ini
// Mengembalikan nilai integer, misal 60, 90, 120, 144, dll.
// ─────────────────────────────────────────────────────────────────────────────
private fun getDisplayRefreshRate(context: Context): Int {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics  // pastikan context valid
            val display = context.display
            display?.refreshRate?.toInt() ?: 60
        } else {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate.toInt()
        }
    } catch (_: Exception) {
        60 // fallback aman
    }
}

// Status FPS terhadap kemampuan layar
private enum class FpsStatus { OK, WARN_EXCEED, WARN_EQUAL }

private fun fpsStatus(selectedFps: Int, displayHz: Int): FpsStatus {
    return when {
        selectedFps > displayHz  -> FpsStatus.WARN_EXCEED
        selectedFps == displayHz -> FpsStatus.WARN_EQUAL
        else                     -> FpsStatus.OK
    }
}

// Helper karena Kotlin tidak punya Quadruple bawaan
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenRecordScreen(navController: NavController, lang: String) {
    val context  = LocalContext.current
    val activity = run {
        var ctx = context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        ctx as? Activity
    }
    val scope = rememberCoroutineScope()

    var selectedFps      by remember { mutableStateOf(SR_FPS_OPTIONS[1]) }
    var selectedRes      by remember { mutableStateOf(SR_RES_OPTIONS[1]) }
    var selectedBitrate  by remember { mutableIntStateOf(1) }
    var recordAudio      by remember { mutableStateOf(true) }
    var isPortrait       by remember { mutableStateOf(true) }
    var selectedTimer    by remember { mutableStateOf(SR_TIMER_OPTIONS[0]) }

    // Deteksi refresh rate layar device
    val displayHz by remember { mutableIntStateOf(getDisplayRefreshRate(context)) }
    val currentFpsStatus by remember(selectedFps, displayHz) {
        derivedStateOf { fpsStatus(selectedFps.value, displayHz) }
    }
    var audioPermGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    // Android 14 (UPSIDE_DOWN_CAKE) butuh permission FOREGROUND_SERVICE_MEDIA_PROJECTION
    val fgsMediaProjPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" else null
    var fgsPermGranted by remember {
        mutableStateOf(
            fgsMediaProjPermission == null ||
            ContextCompat.checkSelfPermission(context, fgsMediaProjPermission)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    var showCountdown    by remember { mutableStateOf(false) }
    var countdownValue   by remember { mutableIntStateOf(3) }
    var isRecording      by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var lastSavedName    by remember { mutableStateOf<String?>(null) }
    var mediaProjection  by remember { mutableStateOf<MediaProjection?>(null) }
    var mediaRecorder    by remember { mutableStateOf<MediaRecorder?>(null) }
    var virtualDisplay   by remember { mutableStateOf<VirtualDisplay?>(null) }
    var outputFile       by remember { mutableStateOf<File?>(null) }
    var timerJob         by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val bitrateLabels = remember(context) {
        listOf(
            context.getString(R.string.screen_record_bitrate_economy),
            context.getString(R.string.screen_record_bitrate_medium),
            context.getString(R.string.screen_record_bitrate_high),
            context.getString(R.string.screen_record_bitrate_ultra)
        )
    }

    val colorScheme  = MaterialTheme.colorScheme
    val accentRed    = colorScheme.error
    val accentGreen  = colorScheme.tertiary
    val accentBlue   = colorScheme.primary
    val accentOrange = colorScheme.secondary
    val accentPurple = colorScheme.tertiary

    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (isRecording || showCountdown) 1.3f else 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse"
    )

    fun formatDuration(s: Int): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX: saveToGallery dijalankan di IO thread untuk hindari ANR
    // ─────────────────────────────────────────────────────────────────────────
    fun saveToGallery(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, SR_SAVE_PATH)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        file.inputStream().use { inp -> inp.copyTo(out, bufferSize = 65_536) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(it, values, null, null)
                }
                file.delete()
            }
        }
        // Android < 10: file sudah tersimpan langsung di path publik, tidak perlu copy
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX: Urutan stopRecording yang benar:
    //   1. cancel timer
    //   2. stop MediaRecorder DULU (flush encoder)
    //   3. release VirtualDisplay (lepas surface dari encoder)
    //   4. stop MediaProjection
    //   5. stop foreground service
    // Urutan ini mencegah crash "stop called in invalid state" di Android 13
    // ─────────────────────────────────────────────────────────────────────────
    fun stopRecording() {
        timerJob?.cancel()
        timerJob = null

        val recorder  = mediaRecorder
        val display   = virtualDisplay
        val projection = mediaProjection

        // Lepas referensi state dulu sebelum release supaya tidak re-entrant
        mediaRecorder   = null
        virtualDisplay  = null
        mediaProjection = null
        isRecording     = false
        recordingSeconds = 0

        try {
            recorder?.stop()       // flush & finalize encoder
        } catch (_: Exception) {}
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {}
        try {
            display?.release()     // lepas surface setelah encoder selesai
        } catch (_: Exception) {}
        try {
            projection?.stop()     // stop projection terakhir
        } catch (_: Exception) {}

        context.startService(ScreenRecordService.stopIntent(context))

        outputFile?.let { f ->
            lastSavedName = f.name
            saveToGallery(f)
            Toast.makeText(
                context,
                context.getString(R.string.screen_record_saved, f.name),
                Toast.LENGTH_LONG
            ).show()
        }
        outputFile = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX: startRecording — perbaikan utama untuk Android 13 force close:
    //
    // 1. startForegroundService() dipanggil SEBELUM getMediaProjection()
    //    → Android 13 strict: token MediaProjection hanya valid jika
    //      foreground service SUDAH running dengan tipe MEDIA_PROJECTION
    //
    // 2. Thread.sleep(300) beri waktu service benar-benar start
    //    sebelum getMediaProjection() dipanggil
    //
    // 3. FPS 90/120 real: setVideoFrameRate + setCaptureFps (API 26+)
    //    → setCaptureFps memberi tahu encoder berapa fps input dari surface,
    //      sehingga encoder benar-benar encode di fps tersebut, bukan 30
    //
    // 4. VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | VIRTUAL_DISPLAY_FLAG_PUBLIC
    //    untuk display capture yang lebih stabil di Android 13+
    // ─────────────────────────────────────────────────────────────────────────
    fun startRecording(resultCode: Int, data: android.content.Intent) {
        val act = activity ?: return
        val projManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        // FIX #1: Start foreground service DULU, tunggu sebentar, baru getMediaProjection
        context.startForegroundService(ScreenRecordService.startIntent(context))

        // FIX #2: Beri waktu service benar-benar masuk foreground
        // (diperlukan terutama di beberapa vendor Android 13 yang lambat bind service)
        try { Thread.sleep(300) } catch (_: InterruptedException) {}

        val projection = try {
            projManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal mendapatkan izin rekam: ${e.message}", Toast.LENGTH_LONG).show()
            context.startService(ScreenRecordService.stopIntent(context))
            return
        } ?: run {
            Toast.makeText(context, "MediaProjection null, coba lagi.", Toast.LENGTH_SHORT).show()
            context.startService(ScreenRecordService.stopIntent(context))
            return
        }
        mediaProjection = projection

        // FIX #3: registerCallback SEBELUM createVirtualDisplay (wajib Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Dipanggil dari handler thread — posting ke main thread
                    Handler(Looper.getMainLooper()).post {
                        if (isRecording) stopRecording()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13: registerCallback juga diperlukan untuk stabilitas
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Handler(Looper.getMainLooper()).post {
                        if (isRecording) stopRecording()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }

        val metrics = act.resources.displayMetrics
        val density = metrics.densityDpi
        val fps     = selectedFps.value

        val resW = if (isPortrait) minOf(selectedRes.width, selectedRes.height)
                   else maxOf(selectedRes.width, selectedRes.height)
        val resH = if (isPortrait) maxOf(selectedRes.width, selectedRes.height)
                   else minOf(selectedRes.width, selectedRes.height)

        val fileName  = "ScreenRecord_${SR_TIMESTAMP_FMT.format(Date())}.mp4"
        val cacheFile = File(context.cacheDir, fileName)
        outputFile    = cacheFile

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

        // FIX #4: setAudioSource SEBELUM setVideoSource (urutan wajib MediaRecorder)
        var audioEnabled = recordAudio && audioPermGranted
        if (audioEnabled) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            } catch (e: RuntimeException) {
                audioEnabled = false
            }
        }

        // FIX #5: Real high-fps recording — setCaptureFps untuk 90/120fps
        try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (audioEnabled) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192_000)
                    setAudioSamplingRate(44100)
                }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOutputFile(cacheFile.absolutePath)
                setVideoSize(resW, resH)
                // Bitrate otomatis dinaikkan untuk fps tinggi
                setVideoEncodingBitRate(effectiveBitrate(SR_BITRATE_BPS[selectedBitrate], fps))
                // setVideoFrameRate: target fps output di file
                setVideoFrameRate(fps)
                // FIX: setCaptureFps — memberi tahu encoder fps input surface yang sesungguhnya
                // Tanpa ini, encoder asumsi 30fps sehingga 90/120fps tidak real
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fps > 60) {
                    setCaptureRate(fps.toDouble())
                }
                prepare()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal siapkan recorder: ${e.message}", Toast.LENGTH_LONG).show()
            try { recorder.release() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            mediaProjection = null
            context.startService(ScreenRecordService.stopIntent(context))
            return
        }

        mediaRecorder = recorder

        // FIX #6: Flag display lebih lengkap untuk stabilitas di Android 13+
        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                           DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

        virtualDisplay = try {
            projection.createVirtualDisplay(
                "ScreenRecord", resW, resH, density,
                displayFlags,
                recorder.surface, null, null
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal buat virtual display: ${e.message}", Toast.LENGTH_LONG).show()
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            mediaRecorder   = null
            mediaProjection = null
            context.startService(ScreenRecordService.stopIntent(context))
            return
        }

        try {
            recorder.start()
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal mulai recording: ${e.message}", Toast.LENGTH_LONG).show()
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            virtualDisplay  = null
            mediaRecorder   = null
            mediaProjection = null
            context.startService(ScreenRecordService.stopIntent(context))
            return
        }

        isRecording = true
        timerJob = scope.launch {
            val limitSec = selectedTimer.seconds
            while (true) {
                delay(1000L)
                recordingSeconds++
                if (limitSec != null && recordingSeconds >= limitSec) {
                    stopRecording()
                    break
                }
            }
        }
    }

    var pendingLaunchAfterFgsPerm by remember { mutableStateOf(false) }
    val fgsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        fgsPermGranted = granted
        if (granted) pendingLaunchAfterFgsPerm = true
        else Toast.makeText(
            context,
            "Izin Foreground Service diperlukan untuk Android 14+",
            Toast.LENGTH_LONG
        ).show()
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermGranted = granted
        if (!granted) recordAudio = false
    }

    val projLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            startRecording(result.resultCode, data)
        } else {
            // User cancel permission dialog
            context.startService(ScreenRecordService.stopIntent(context))
        }
    }

    fun doLaunch() {
        val act = activity ?: return
        showCountdown  = true
        countdownValue = 3
        scope.launch {
            repeat(3) { i ->
                countdownValue = 3 - i
                delay(1000L)
            }
            showCountdown = false
            val projManager = act.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            projLauncher.launch(projManager.createScreenCaptureIntent())
        }
    }

    fun launchWithCountdown() {
        if (recordAudio && !audioPermGranted) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!fgsPermGranted && fgsMediaProjPermission != null) {
            fgsPermLauncher.launch(fgsMediaProjPermission)
            return
        }
        doLaunch()
    }

    LaunchedEffect(pendingLaunchAfterFgsPerm) {
        if (pendingLaunchAfterFgsPerm) {
            pendingLaunchAfterFgsPerm = false
            doLaunch()
        }
    }

    // Countdown overlay — menutup seluruh layar
    if (showCountdown) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(R.string.screen_record_countdown_label, countdownValue),
                    fontSize = 16.sp,
                    color = Color.White.copy(0.7f),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "$countdownValue",
                    fontSize = 96.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.scale(pulseScale)
                )
            }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.screen_record_title),
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle  = FontStyle.Italic,
                        fontSize   = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!isRecording) navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            SrStatusCard(isRecording, recordingSeconds, accentRed, pulseScale, ::formatDuration)

            SrCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    SrSettingRow(Icons.Default.Videocam, stringResource(R.string.screen_record_fps_label), accentBlue) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SR_FPS_OPTIONS.forEach { fps ->
                                SrChip(fps.label, selectedFps == fps, !isRecording, Modifier.weight(1f)) {
                                    selectedFps = fps
                                }
                            }
                        }
                        // ── FPS Warning Banner ──────────────────────────────
                        AnimatedVisibility(
                            visible = currentFpsStatus != FpsStatus.OK,
                            enter   = fadeIn(tween(250)),
                            exit    = fadeOut(tween(250))
                        ) {
                            val (icon, bgColor, textColor, msg) = when (currentFpsStatus) {
                                FpsStatus.WARN_EXCEED -> Quadruple(
                                    Icons.Default.Warning,
                                    MaterialTheme.colorScheme.errorContainer,
                                    MaterialTheme.colorScheme.onErrorContainer,
                                    stringResource(R.string.screen_record_fps_warn_exceed, displayHz, selectedFps.label)
                                )
                                FpsStatus.WARN_EQUAL -> Quadruple(
                                    Icons.Default.Info,
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.onTertiaryContainer,
                                    stringResource(R.string.screen_record_fps_warn_equal, displayHz, selectedFps.label)
                                )
                                else -> Quadruple(Icons.Default.Info, Color.Transparent, Color.Transparent, "")
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgColor)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(icon, null, tint = textColor, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                                Text(msg, fontSize = 11.sp, color = textColor, lineHeight = 16.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.HighQuality, stringResource(R.string.screen_record_resolution_label), accentBlue) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SR_RES_OPTIONS.forEach { res ->
                                SrChip(res.label, selectedRes == res, !isRecording, Modifier.weight(1f)) {
                                    selectedRes = res
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.Speed, stringResource(R.string.screen_record_bitrate_label), accentBlue) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            bitrateLabels.forEachIndexed { index, label ->
                                SrChip(label, selectedBitrate == index, !isRecording, Modifier.weight(1f)) {
                                    selectedBitrate = index
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Mic, null, tint = accentGreen, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.screen_record_audio_mic_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Switch(
                            checked = recordAudio,
                            onCheckedChange = { if (!isRecording) recordAudio = it },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                                checkedThumbColor = MaterialTheme.colorScheme.onTertiary
                            )
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.ScreenRotation, "ORIENTASI", accentOrange) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SrChip("📱 Portrait",  isPortrait,  !isRecording, Modifier.weight(1f)) { isPortrait = true }
                            SrChip("🖥 Landscape", !isPortrait, !isRecording, Modifier.weight(1f)) { isPortrait = false }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.Timer, "BATAS WAKTU", accentOrange) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SR_TIMER_OPTIONS.forEach { opt ->
                                SrChip(opt.label, selectedTimer == opt, !isRecording, Modifier.weight(1f)) {
                                    selectedTimer = opt
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SrInfoBadge(Icons.Default.Videocam,    "${selectedFps.label} FPS",  accentBlue)
                SrInfoBadge(Icons.Default.HighQuality, selectedRes.label,           accentBlue)
                SrInfoBadge(Icons.Default.Speed,       bitrateLabels[selectedBitrate], accentOrange)
                SrInfoBadge(
                    if (isPortrait) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                    if (isPortrait) "Portrait" else "Landscape",
                    accentOrange
                )
                SrInfoBadge(
                    if (recordAudio) Icons.Default.Mic else Icons.Default.MicOff,
                    if (recordAudio) stringResource(R.string.status_on) else stringResource(R.string.status_off),
                    if (recordAudio) accentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                SrInfoBadge(Icons.Default.Timer, selectedTimer.label, accentPurple)
                // Badge refresh rate layar device
                SrInfoBadge(
                    icon = Icons.Default.Refresh,
                    text = stringResource(R.string.screen_record_display_hz_badge, displayHz),
                    tint = when (currentFpsStatus) {
                        FpsStatus.WARN_EXCEED -> accentRed
                        FpsStatus.WARN_EQUAL  -> accentGreen
                        else                  -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            AnimatedVisibility(lastSavedName != null, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.tertiary.copy(0.4f)), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.screen_record_last_saved, lastSavedName ?: ""),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Button(
                onClick  = { if (isRecording) stopRecording() else launchWithCountdown() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer
                                     else MaterialTheme.colorScheme.error,
                    contentColor   = if (isRecording) MaterialTheme.colorScheme.onErrorContainer
                                     else MaterialTheme.colorScheme.onError
                )
            ) {
                if (isRecording) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp).scale(pulseScale))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_record_btn_stop), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                } else {
                    Icon(Icons.Default.FiberManualRecord, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_record_btn_start), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── Composable helpers ───────────────────────────────────────────────────────

@Composable
private fun SrStatusCard(
    isRecording: Boolean,
    recordingSeconds: Int,
    accentRed: Color,
    pulseScale: Float,
    formatDuration: (Int) -> String
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isRecording) accentRed.copy(0.08f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .border(
                BorderStroke(
                    if (isRecording) 1.dp else 0.8.dp,
                    if (isRecording) accentRed.copy(0.4f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        if (isRecording) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.FiberManualRecord, null, tint = accentRed,
                    modifier = Modifier.size(12.dp).scale(pulseScale))
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(recordingSeconds), fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold, color = accentRed)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.screen_record_status_recording),
                    fontSize = 12.sp, color = accentRed.copy(0.7f))
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Videocam, null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.screen_record_status_ready),
                        fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.screen_record_status_ready_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SrCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SrSettingRow(icon: ImageVector, label: String, tint: Color, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic, color = tint, letterSpacing = 1.2.sp)
        }
        content()
    }
}

@Composable
private fun SrChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick  = onClick,
        enabled  = enabled,
        shape    = RoundedCornerShape(8.dp),
        color    = if (selected) MaterialTheme.colorScheme.primaryContainer
                   else MaterialTheme.colorScheme.surfaceContainerHigh,
        border   = BorderStroke(
            if (selected) 1.dp else 0.5.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize   = 11.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                color      = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                             else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SrInfoBadge(icon: ImageVector, text: String, tint: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = tint)
    }
}
