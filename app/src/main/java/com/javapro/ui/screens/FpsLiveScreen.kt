package com.javapro.ui.screens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.javapro.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.FpsService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsLiveScreen(navController: NavController, lang: String) {

    var fpsHistory by remember { mutableStateOf(listOf<Float>()) }
    var currentFps by remember { mutableStateOf(0f) }
    var maxFps by remember { mutableStateOf(0f) }
    var minFps by remember { mutableStateOf(0f) }
    var avgFps by remember { mutableStateOf(0f) }
    var isRecording by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (!isRecording) continue
            val fps = FpsService.currentFps.value
            if (fps > 0f) {
                currentFps = fps
                val updated = (fpsHistory + fps).takeLast(120)
                fpsHistory = updated
                maxFps = updated.max()
                minFps = updated.filter { it > 0f }.minOrNull() ?: 0f
                avgFps = if (updated.isNotEmpty()) updated.average().toFloat() else 0f
            }
        }
    }

    val fpsColor = when {
        currentFps < 30f -> MaterialTheme.colorScheme.error
        currentFps < 55f -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF4CAF50)
    }

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val gridColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val labelColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.fps_title), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        Text(stringResource(R.string.fps_chart_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = fpsColor.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, fpsColor.copy(alpha = 0.4f))
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(fpsColor.copy(pulseAlpha), CircleShape))
                            Text(stringResource(R.string.status_current), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = if (currentFps > 0f) "${currentFps.toInt()}" else "--",
                            fontSize = 52.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = fpsColor
                        )
                        Text("FPS", fontSize = 12.sp, color = fpsColor.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold)
                    }
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FpsStatCard(label = stringResource(R.string.fps_max), value = if (maxFps > 0f) "${maxFps.toInt()}" else "--", color = Color(0xFF4CAF50))
                    FpsStatCard(label = stringResource(R.string.fps_min), value = if (minFps > 0f) "${minFps.toInt()}" else "--", color = MaterialTheme.colorScheme.error)
                    FpsStatCard(label = stringResource(R.string.fps_avg), value = if (avgFps > 0f) "${avgFps.toInt()}" else "--", color = MaterialTheme.colorScheme.primary)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF0D0D0D) else Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("FPS", color = labelColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            stringResource(R.string.fps_points, fpsHistory.size),
                            color = labelColor, fontSize = 11.sp
                        )
                    }

                    if (fpsHistory.size >= 2) {
                        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            val w = size.width
                            val h = size.height
                            val leftPad = 44f
                            val rightPad = 16f
                            val topPad = 8f
                            val bottomPad = 26f
                            val plotW = (w - leftPad - rightPad).coerceAtLeast(1f)
                            val plotH = (h - topPad - bottomPad).coerceAtLeast(1f)

                            val rawMax = fpsHistory.maxOrNull() ?: 60f
                            val fpsStep = 30f
                            val yMax = ((rawMax / fpsStep).toInt() + 1).coerceAtLeast(2) * fpsStep
                            val yMin = 0f
                            val steps = (yMax / fpsStep).toInt()

                            for (i in 0..steps) {
                                val y = topPad + plotH * i / steps
                                drawLine(
                                    gridColor,
                                    start = Offset(leftPad, y),
                                    end = Offset(leftPad + plotW, y),
                                    strokeWidth = 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                )
                                val value = yMax - fpsStep * i
                                drawContext.canvas.nativeCanvas.drawText(
                                    "${value.toInt()}",
                                    leftPad - 6f,
                                    y - 6f,
                                    android.graphics.Paint().apply {
                                        color = labelColor.toArgb()
                                        textSize = 22f
                                        isAntiAlias = true
                                        textAlign = android.graphics.Paint.Align.RIGHT
                                    }
                                )
                            }

                            val xSteps = 4
                            for (i in 0..xSteps) {
                                val x = leftPad + plotW * i / xSteps
                                drawLine(
                                    gridColor,
                                    start = Offset(x, topPad),
                                    end = Offset(x, topPad + plotH),
                                    strokeWidth = 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                )
                                val secs = fpsHistory.size * i / xSteps / 2
                                val label = if (secs < 60) "${secs}s" else "${secs / 60}m${secs % 60}s"
                                drawContext.canvas.nativeCanvas.drawText(
                                    label,
                                    x,
                                    topPad + plotH + 24f,
                                    android.graphics.Paint().apply {
                                        color = labelColor.toArgb()
                                        textSize = 22f
                                        isAntiAlias = true
                                        textAlign = when (i) {
                                            0 -> android.graphics.Paint.Align.LEFT
                                            xSteps -> android.graphics.Paint.Align.RIGHT
                                            else -> android.graphics.Paint.Align.CENTER
                                        }
                                    }
                                )
                            }

                            val count = fpsHistory.size
                            val fillPath = Path().apply {
                                moveTo(leftPad, topPad + plotH)
                                fpsHistory.forEachIndexed { i, v ->
                                    val x = leftPad + plotW * i / (count - 1).coerceAtLeast(1)
                                    val y = topPad + plotH - ((v - yMin) / (yMax - yMin)) * plotH
                                    lineTo(x, y)
                                }
                                lineTo(leftPad + plotW * (count - 1) / (count - 1).coerceAtLeast(1), topPad + plotH)
                                close()
                            }
                            drawPath(
                                fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0x804CAF50), Color(0x104CAF50)),
                                    startY = topPad, endY = topPad + plotH
                                )
                            )

                            val linePath = Path().apply {
                                fpsHistory.forEachIndexed { i, v ->
                                    val x = leftPad + plotW * i / (count - 1).coerceAtLeast(1)
                                    val y = topPad + plotH - ((v - yMin) / (yMax - yMin)) * plotH
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                            }
                            drawPath(linePath, color = Color(0xFF4CAF50), style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                            val lastX = leftPad + plotW
                            val lastY = topPad + plotH - ((fpsHistory.last() - yMin) / (yMax - yMin)) * plotH
                            drawCircle(Color(0xFF4CAF50).copy(alpha = 0.3f), radius = 12f, center = Offset(lastX, lastY))
                            drawCircle(Color(0xFF4CAF50), radius = 5f, center = Offset(lastX, lastY))
                            drawCircle(Color.White, radius = 2.5f, center = Offset(lastX, lastY))
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = Color(0xFF4CAF50), modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                                Text(stringResource(R.string.status_collecting_fps), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(
                            "MAX" to (if (maxFps > 0f) "${maxFps.toInt()}" else "--"),
                            "MIN" to (if (minFps > 0f) "${minFps.toInt()}" else "--"),
                            "AVG" to (if (avgFps > 0f) "${avgFps.toInt()}" else "--")
                        ).forEach { (label, value) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, color = labelColor, fontSize = 10.sp)
                                Text(value, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { isRecording = !isRecording },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (isRecording)
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else
                        ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50)),
                    border = BorderStroke(1.dp, if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else Color(0xFF4CAF50).copy(alpha = 0.6f))
                ) {
                    Text(if (isRecording) stringResource(R.string.action_pause) else stringResource(R.string.action_resume))
                }

                OutlinedButton(
                    onClick = {
                        fpsHistory = emptyList()
                        currentFps = 0f
                        maxFps = 0f
                        minFps = 0f
                        avgFps = 0f
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            }

            if (!isRecording) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.fps_chart_paused),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FpsStatCard(label: String, value: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 11.sp, color = color.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = color)
        }
    }
}
