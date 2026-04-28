package com.javapro.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javapro.R
import com.javapro.utils.PremiumManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onLoadingFinished: () -> Unit) {
    val context = LocalContext.current
    val appName = "JavaPro"
    val chars = appName.toList()

    var isRooted  by remember { mutableStateOf<Boolean?>(null) }
    var startAnim by remember { mutableStateOf(false) }
    var showSub   by remember { mutableStateOf(false) }
    var showBottom by remember { mutableStateOf(false) }

    val primary   = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val onSurface = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(Unit) {
        delay(120)
        startAnim = true
        delay(appName.length * 60L + 300L)
        showSub = true
        delay(200)

        val rootCheck = TweakExecutor.checkRoot()
        isRooted = rootCheck
        PremiumManager.checkOnline(context)

        showBottom = true
        delay(900)
        onLoadingFinished()
    }

    val bottomAlpha by animateFloatAsState(
        targetValue  = if (showBottom) 1f else 0f,
        animationSpec = tween(400, easing = EaseOutCubic),
        label        = "bottomAlpha"
    )
    val lineWidth by animateFloatAsState(
        targetValue  = if (showSub) 1f else 0f,
        animationSpec = tween(600, delayMillis = 100, easing = EaseOutCubic),
        label        = "lineWidth"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                chars.forEachIndexed { index, char ->
                    val delayMs = index * 60
                    val charAnim by animateFloatAsState(
                        targetValue  = if (startAnim) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = 420,
                            delayMillis    = delayMs,
                            easing         = EaseOutBack
                        ),
                        label = "char_$index"
                    )
                    val charAlpha by animateFloatAsState(
                        targetValue  = if (startAnim) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = 300,
                            delayMillis    = delayMs,
                            easing         = EaseOutCubic
                        ),
                        label = "charAlpha_$index"
                    )

                    val charColor = if (index < 4) primary else onSurface

                    Text(
                        text       = char.toString(),
                        fontSize   = 52.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = charColor,
                        modifier   = Modifier.graphicsLayer {
                            alpha        = charAlpha
                            translationY = (1f - charAnim) * 40f
                            scaleX       = 0.7f + charAnim * 0.3f
                            scaleY       = 0.7f + charAnim * 0.3f
                        },
                        letterSpacing = (-1).sp
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .height(2.dp)
                    .fillMaxWidth(0.55f * lineWidth)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(colors = listOf(primary, secondary))
                    )
            )

            Spacer(Modifier.height(12.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier            = Modifier.graphicsLayer { alpha = bottomAlpha }
            ) {
                isRooted?.let { rooted ->
                    Row(
                        verticalAlignment   = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(
                                    if (rooted) primary else MaterialTheme.colorScheme.error,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Text(
                            text       = if (rooted) stringResource(R.string.root_active_splash) else stringResource(R.string.non_root_splash),
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = if (rooted) primary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                LinearProgressIndicator(
                    modifier   = Modifier
                        .width(48.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(50)),
                    color      = primary.copy(alpha = 0.5f),
                    trackColor = onSurface.copy(alpha = 0.08f)
                )
            }
        }

        Text(
            text     = "@Java_nih_deks",
            fontSize = 10.sp,
            color    = onSurface.copy(alpha = 0.2f),
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .graphicsLayer { alpha = bottomAlpha }
        )
    }
}
