package com.javapro

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun DraggableFpsCounter() {
    val fps by FpsService.currentFps.collectAsState()
    var offset by remember { mutableStateOf(IntOffset(20, 100)) }

    Text(
        text = "FPS: ${fps.roundToInt()}",
        color = Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .offset { offset }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset = IntOffset(
                        (offset.x + dragAmount.x).roundToInt(),
                        (offset.y + dragAmount.y).roundToInt()
                    )
                }
            }
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(4.dp)
    )
}
