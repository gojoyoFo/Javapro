package com.javapro.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.javapro.R
import com.javapro.utils.ReleaseInfo

private val DarkBg       = Color(0xFF0A0C10)
private val DarkCard     = Color(0xFF13171F)
private val DarkCardHigh = Color(0xFF1A1F2B)
private val BorderDark   = Color(0xFF1E2533)

private val LightBg       = Color(0xFFF4F6FA)
private val LightCard     = Color(0xFFFFFFFF)
private val LightCardHigh = Color(0xFFEDF0F7)
private val BorderLight   = Color(0xFFDDE2EC)

private fun dCard(dark: Boolean)     = if (dark) DarkCard     else LightCard
private fun dCardHigh(dark: Boolean) = if (dark) DarkCardHigh else LightCardHigh
private fun dBorder(dark: Boolean)   = if (dark) BorderDark   else BorderLight
private fun dTxtP(dark: Boolean)     = if (dark) Color(0xFFF0F4F8) else Color(0xFF0D1117)
private fun dTxtS(dark: Boolean)     = if (dark) Color(0xFF7A8599) else Color(0xFF5A6478)
private fun dTxtM(dark: Boolean)     = if (dark) Color(0xFF3D4558) else Color(0xFFB0BAD0)
private fun dCtaBg(dark: Boolean)    = if (dark) Color(0xFFF0F4F8) else Color(0xFF0D1117)
private fun dCtaFg(dark: Boolean)    = if (dark) Color(0xFF0D1117) else Color(0xFFF0F4F8)
private fun dShimmer(dark: Boolean)  = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
private fun dHeaderBg(dark: Boolean) = if (dark)
    Brush.verticalGradient(listOf(DarkCardHigh, DarkCard))
else
    Brush.verticalGradient(listOf(LightCardHigh, LightCard))

@Composable
private fun ShimmerCard(
    dark         : Boolean,
    modifier     : Modifier = Modifier,
    cornerRadius : Dp       = 20.dp,
    content      : @Composable BoxScope.() -> Unit
) {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val sx by inf.animateFloat(
        initialValue  = -1f,
        targetValue   = 2f,
        animationSpec = infiniteRepeatable(tween(3500, easing = LinearEasing)),
        label         = "shimX"
    )
    Box(
        modifier = modifier
            .drawBehind {
                val highlight = dShimmer(dark).copy(alpha = if (dark) 0.22f else 0.18f)
                val edge      = dBorder(dark)
                val g = Brush.linearGradient(
                    colors = listOf(edge, highlight, edge),
                    start  = Offset(size.width * (sx - 0.5f), 0f),
                    end    = Offset(size.width * (sx + 0.5f), size.height)
                )
                drawRoundRect(
                    brush        = g,
                    size         = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                    style        = Stroke(width = 0.8.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(cornerRadius))
            .background(dCard(dark)),
        content = content
    )
}

@Composable
private fun BreathingDot(dark: Boolean, size: Dp = 5.dp) {
    val inf = rememberInfiniteTransition(label = "bdot")
    val a by inf.animateFloat(
        initialValue  = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label         = "bdotA"
    )
    Box(Modifier.size(size).background(dTxtS(dark).copy(alpha = a), CircleShape))
}

@Composable
private fun ArcProgress(
    progress    : Float,
    dark        : Boolean,
    arcSize     : Dp = 68.dp,
    strokeWidth : Dp = 4.dp
) {
    Canvas(Modifier.size(arcSize)) {
        val stroke = strokeWidth.toPx()
        val r      = (size.minDimension - stroke) / 2f
        val cx     = size.width  / 2f
        val cy     = size.height / 2f
        drawCircle(color = dBorder(dark), radius = r, style = Stroke(width = stroke, cap = StrokeCap.Round))
        if (progress > 0f) {
            val arcColors = if (dark)
                listOf(Color(0xFF4A5568), Color(0xFFF0F4F8), Color(0xFFCCCCCC))
            else
                listOf(Color(0xFFB0BAD0), Color(0xFF0D1117), Color(0xFF5A6478))
            drawArc(
                brush      = Brush.sweepGradient(arcColors, Offset(cx, cy)),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter  = false,
                style      = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft    = Offset(cx - r, cy - r),
                size       = Size(r * 2, r * 2)
            )
        }
    }
}

@Composable
fun UpdateDialog(
    release   : ReleaseInfo,
    isDark    : Boolean  = true,
    lang      : String   = "en",
    onConfirm : () -> Unit,
    onDismiss : () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(280),
        label         = "dialogAlpha"
    )
    val offsetY by animateFloatAsState(
        targetValue   = if (visible) 0f else 0.05f,
        animationSpec = tween(320),
        label         = "dialogOffsetY"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        ShimmerCard(
            dark         = isDark,
            cornerRadius = 20.dp,
            modifier     = Modifier.graphicsLayer {
                this.alpha        = alpha
                this.translationY = size.height * offsetY
            }
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(dHeaderBg(isDark))
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val step = 22.dp.toPx()
                        val lc   = dShimmer(isDark).copy(alpha = if (isDark) 0.04f else 0.06f)
                        var x = 0f; while (x < size.width)  { drawLine(lc, Offset(x, 0f), Offset(x, size.height)); x += step }
                        var y = 0f; while (y < size.height) { drawLine(lc, Offset(0f, y), Offset(size.width, y));  y += step }
                    }
                    Box(
                        modifier         = Modifier.align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .border(
                                    0.5.dp,
                                    Brush.sweepGradient(
                                        listOf(dBorder(isDark), dTxtS(isDark).copy(0.5f), dBorder(isDark))
                                    ),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier         = Modifier
                                .size(48.dp)
                                .background(dCardHigh(isDark), CircleShape)
                                .border(0.5.dp, dBorder(isDark), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.SystemUpdate,
                                null,
                                tint     = dTxtP(isDark),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier            = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text          = stringResource(R.string.update_dialog_title),
                            fontWeight    = FontWeight.Bold,
                            fontSize      = 19.sp,
                            color         = dTxtP(isDark),
                            letterSpacing = 0.2.sp
                        )
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            BreathingDot(isDark)
                            Text(
                                text     = stringResource(R.string.update_dialog_subtitle),
                                fontSize = 11.sp,
                                color    = dTxtS(isDark)
                            )
                        }
                    }

                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(dCardHigh(isDark))
                            .border(0.5.dp, dBorder(isDark), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text          = stringResource(R.string.update_new_version_label),
                                fontSize      = 9.sp,
                                color         = dTxtS(isDark),
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text       = "v${release.latestVersion}",
                                fontSize   = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color      = dTxtP(isDark)
                            )
                        }
                        if (release.publishedAt.isNotBlank()) {
                            Row(
                                modifier          = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(dCard(isDark))
                                    .border(0.5.dp, dBorder(isDark), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.CalendarToday, null, tint = dTxtS(isDark), modifier = Modifier.size(10.dp))
                                Text(release.publishedAt, fontSize = 11.sp, color = dTxtS(isDark), fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    if (release.releaseNotes.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(dCardHigh(isDark))
                                .border(0.5.dp, dBorder(isDark), RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text          = stringResource(R.string.update_changelog_label),
                                fontSize      = 9.sp,
                                color         = dTxtS(isDark),
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 80.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text       = release.releaseNotes,
                                    fontSize   = 12.sp,
                                    color      = dTxtS(isDark),
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(dBorder(isDark)))

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick  = onDismiss,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(12.dp),
                            border   = androidx.compose.foundation.BorderStroke(0.5.dp, dBorder(isDark)),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = dTxtS(isDark))
                        ) {
                            Text(stringResource(R.string.update_later_btn), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Button(
                            onClick   = onConfirm,
                            modifier  = Modifier.weight(2f).height(44.dp),
                            shape     = RoundedCornerShape(12.dp),
                            colors    = ButtonDefaults.buttonColors(
                                containerColor = dCtaBg(isDark),
                                contentColor   = dCtaFg(isDark)
                            ),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.update_now_btn), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(15.dp))
                        }
                    }

                    Text(
                        text      = stringResource(R.string.update_free_safe_note),
                        fontSize  = 10.sp,
                        color     = dTxtM(isDark),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadProgressDialog(
    progress : Int,
    isDark   : Boolean = true,
    lang     : String  = "en",
    onCancel : () -> Unit
) {
    val isDone = progress >= 100

    val animProgress by animateFloatAsState(
        targetValue   = progress / 100f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label         = "dlp"
    )

    Dialog(
        onDismissRequest = {},
        properties       = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        ShimmerCard(dark = isDark, cornerRadius = 20.dp) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ArcProgress(progress = animProgress, dark = isDark)
                    AnimatedContent(
                        targetState   = isDone,
                        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                        label         = "dlicon"
                    ) { done ->
                        Icon(
                            imageVector        = if (done) Icons.Default.CheckCircle else Icons.Default.Download,
                            contentDescription = null,
                            tint               = dTxtP(isDark),
                            modifier           = Modifier.size(24.dp)
                        )
                    }
                }

                AnimatedContent(
                    targetState = isDone,
                    transitionSpec = {
                        (fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 }) togetherWith fadeOut(tween(150))
                    },
                    label = "dltitle"
                ) { done ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text       = if (done) stringResource(R.string.update_complete_label) else stringResource(R.string.update_downloading_label),
                            fontWeight = FontWeight.Bold,
                            fontSize   = 18.sp,
                            color      = dTxtP(isDark)
                        )
                        Text(
                            text     = if (done) stringResource(R.string.update_opening_installer_label) else stringResource(R.string.update_keep_app_open_note),
                            fontSize = 12.sp,
                            color    = dTxtS(isDark)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(dCardHigh(isDark))
                        .border(0.5.dp, dBorder(isDark), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!isDone) BreathingDot(isDark)
                            Text(
                                text       = stringResource(R.string.update_progress_label),
                                fontSize   = 11.sp,
                                color      = dTxtS(isDark),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        AnimatedContent(
                            targetState = progress,
                            transitionSpec = {
                                (fadeIn(tween(120)) + slideInVertically(tween(120)) { -it }) togetherWith fadeOut(tween(80))
                            },
                            label = "pct"
                        ) { pct ->
                            Text(
                                text       = "$pct%",
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color      = dTxtP(isDark)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dBorder(isDark))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animProgress)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(
                                        if (isDark)
                                            listOf(Color(0xFF4A5568), Color(0xFFCCCCCC), Color(0xFFF0F4F8))
                                        else
                                            listOf(Color(0xFFB0BAD0), Color(0xFF5A6478), Color(0xFF0D1117))
                                    )
                                )
                        )
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(
                            stringResource(R.string.update_connected_label)   to (progress >= 5),
                            stringResource(R.string.update_downloading_label) to (progress in 5..99),
                            stringResource(R.string.update_complete_label)    to isDone
                        ).forEach { (label, active) ->
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .background(if (active) dTxtP(isDark) else dBorder(isDark), CircleShape)
                                )
                                Text(
                                    text       = label,
                                    fontSize   = 10.sp,
                                    color      = if (active) dTxtP(isDark) else dTxtM(isDark),
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = !isDone) {
                    TextButton(
                        onClick  = onCancel,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(dCardHigh(isDark))
                            .border(0.5.dp, dBorder(isDark), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text       = stringResource(R.string.action_cancel),
                            color      = dTxtS(isDark),
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
