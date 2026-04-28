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
    RecFpsOption("30", 30),
    RecFpsOption("60", 60),
    RecFpsOption("90", 90),
    RecFpsOption("120", 120)
)

private val SR_RES_OPTIONS = listOf(
    RecResOption("720p", 1280, 720),
    RecResOption("1080p", 1920, 1080),
    RecResOption("1440p", 2560, 1440),
    RecResOption("2K", 2048, 1152)
)

private val SR_TIMER_OPTIONS = listOf(
    RecTimerOption("∞", null),
    RecTimerOption("1m", 60),
    RecTimerOption("5m", 300),
    RecTimerOption("10m", 600)
)

private val SR_BITRATE_BPS   = listOf(4_000_000, 8_000_000, 16_000_000, 32_000_000)
private val SR_TIMESTAMP_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
private val SR_SAVE_PATH     = "${Environment.DIRECTORY_MOVIES}/ScreenRecord"

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
    var audioPermGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val fgsMediaProjPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" else null
    var fgsPermGranted by remember {
        mutableStateOf(
            fgsMediaProjPermission == null ||
            ContextCompat.checkSelfPermission(context, fgsMediaProjPermission) == PackageManager.PERMISSION_GRANTED
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
        initialValue = 1f, targetValue = if (isRecording || showCountdown) 1.3f else 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse"
    )

    fun formatDuration(s: Int): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun saveToGallery(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, SR_SAVE_PATH)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
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
    }

    fun stopRecording() {
        timerJob?.cancel()
        timerJob = null
        try {
            virtualDisplay?.release()
            mediaRecorder?.apply { stop(); reset(); release() }
            mediaProjection?.stop()
        } catch (_: Exception) {}
        context.startService(ScreenRecordService.stopIntent(context))
        virtualDisplay   = null
        mediaRecorder    = null
        mediaProjection  = null
        isRecording      = false
        recordingSeconds = 0
        outputFile?.let { f ->
            lastSavedName = f.name
            saveToGallery(f)
            Toast.makeText(context, context.getString(R.string.screen_record_saved, f.name), Toast.LENGTH_LONG).show()
        }
        outputFile = null
    }

    fun startRecording(resultCode: Int, data: android.content.Intent) {
        val act = activity ?: return
        val projManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // FIX: Start foreground service BEFORE getMediaProjection() on Android 14+
        // Service harus sudah running sebelum kita ambil MediaProjection token
        context.startService(ScreenRecordService.startIntent(context))

        val projection = projManager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection

        // FIX: Android 14+ wajib registerCallback sebelum createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (isRecording) {
                        try {
                            virtualDisplay?.release()
                            mediaRecorder?.apply { stop(); reset(); release() }
                        } catch (_: Exception) {}
                        virtualDisplay  = null
                        mediaRecorder   = null
                        mediaProjection = null
                        isRecording     = false
                        recordingSeconds = 0
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }

        val metrics = act.resources.displayMetrics
        val density = metrics.densityDpi
        val resW = if (isPortrait) minOf(selectedRes.width, selectedRes.height)
                   else maxOf(selectedRes.width, selectedRes.height)
        val resH = if (isPortrait) maxOf(selectedRes.width, selectedRes.height)
                   else minOf(selectedRes.width, selectedRes.height)
        val fileName  = "ScreenRecord_${SR_TIMESTAMP_FMT.format(Date())}.mp4"
        val cacheFile = File(context.cacheDir, fileName)
        outputFile    = cacheFile

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

        // FIX: setAudioSource harus dipanggil SEBELUM setVideoSource
        var audioEnabled = recordAudio
        if (audioEnabled) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            } catch (e: RuntimeException) {
                audioEnabled = false
            }
        }

        recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (audioEnabled) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setOutputFile(cacheFile.absolutePath)
            setVideoSize(resW, resH)
            setVideoEncodingBitRate(SR_BITRATE_BPS[selectedBitrate])
            setVideoFrameRate(selectedFps.value)
            prepare()
        }

        mediaRecorder  = recorder
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecord", resW, resH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface, null, null
        )
        recorder.start()

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
    val fgsPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        fgsPermGranted = granted
        if (granted) pendingLaunchAfterFgsPerm = true
    }

    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioPermGranted = granted
        if (!granted) recordAudio = false
    }

    // FIX: projLauncher hanya launch screen capture intent, service distart di dalam startRecording()
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) startRecording(result.resultCode, data)
    }

    fun doLaunch() {
        val act = activity ?: return
        // FIX: JANGAN startService di sini. Service distart di dalam startRecording()
        // setelah user approve permission projection (token valid).
        showCountdown  = true
        countdownValue = 3
        scope.launch {
            repeat(3) { i ->
                countdownValue = 3 - i
                delay(1000L)
            }
            showCountdown = false
            val projManager = act.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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

    if (showCountdown) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.85f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.screen_record_countdown_label, countdownValue), fontSize = 16.sp, color = Color.White.copy(0.7f), fontWeight = FontWeight.Medium)
                Text("$countdownValue", fontSize = 96.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error, modifier = Modifier.scale(pulseScale))
            }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_record_title), fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = { if (!isRecording) navController.popBackStack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                                SrChip(fps.label, selectedFps == fps, !isRecording, Modifier.weight(1f)) { selectedFps = fps }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.HighQuality, stringResource(R.string.screen_record_resolution_label), accentBlue) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SR_RES_OPTIONS.forEach { res ->
                                SrChip(res.label, selectedRes == res, !isRecording, Modifier.weight(1f)) { selectedRes = res }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.Speed, stringResource(R.string.screen_record_bitrate_label), accentBlue) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            bitrateLabels.forEachIndexed { index, label ->
                                SrChip(label, selectedBitrate == index, !isRecording, Modifier.weight(1f)) { selectedBitrate = index }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Mic, null, tint = accentGreen, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.screen_record_audio_mic_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Switch(
                            checked = recordAudio,
                            onCheckedChange = { if (!isRecording) recordAudio = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary, checkedThumbColor = MaterialTheme.colorScheme.onTertiary)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.ScreenRotation, "ORIENTASI", accentOrange) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SrChip(
                                label = "📱 Portrait",
                                selected = isPortrait,
                                enabled = !isRecording,
                                modifier = Modifier.weight(1f)
                            ) { isPortrait = true }
                            SrChip(
                                label = "🖥 Landscape",
                                selected = !isPortrait,
                                enabled = !isRecording,
                                modifier = Modifier.weight(1f)
                            ) { isPortrait = false }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f))

                    SrSettingRow(Icons.Default.Timer, "BATAS WAKTU", accentOrange) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SR_TIMER_OPTIONS.forEach { opt ->
                                SrChip(opt.label, selectedTimer == opt, !isRecording, Modifier.weight(1f)) { selectedTimer = opt }
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
                SrInfoBadge(Icons.Default.Videocam,       "${selectedFps.label} FPS",                    accentBlue)
                SrInfoBadge(Icons.Default.HighQuality,    selectedRes.label,                             accentBlue)
                SrInfoBadge(Icons.Default.Speed,          bitrateLabels[selectedBitrate],                accentOrange)
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
                    Text(stringResource(R.string.screen_record_last_saved, lastSavedName ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                }
            }

            Button(
                onClick  = { if (isRecording) stopRecording() else launchWithCountdown() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.error,
                    contentColor   = if (isRecording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onError
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

@Composable
private fun SrStatusCard(isRecording: Boolean, recordingSeconds: Int, accentRed: Color, pulseScale: Float, formatDuration: (Int) -> String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isRecording) accentRed.copy(0.08f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(if (isRecording) 1.dp else 0.8.dp, if (isRecording) accentRed.copy(0.4f) else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (isRecording) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Default.FiberManualRecord, null, tint = accentRed, modifier = Modifier.size(12.dp).scale(pulseScale))
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(recordingSeconds), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = accentRed)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.screen_record_status_recording), fontSize = 12.sp, color = accentRed.copy(0.7f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.screen_record_status_ready), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.screen_record_status_ready_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = tint, letterSpacing = 1.2.sp)
        }
        content()
    }
}

@Composable
private fun SrChip(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick  = onClick,
        enabled  = enabled,
        shape    = RoundedCornerShape(8.dp),
        color    = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border   = BorderStroke(
            if (selected) 1.dp else 0.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize    = 11.sp,
                fontWeight  = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                color       = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
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
