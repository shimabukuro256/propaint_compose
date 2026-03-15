package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun SideQuickBar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(48.dp)
            .padding(vertical = 24.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1E1E1E).copy(alpha = 0.92f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Brush size dot preview
        Box(
            modifier = Modifier
                .size(30.dp)
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val dotSize = (vm.brushSettings.size / 200f * 22f).coerceIn(3f, 22f)
            Box(
                Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(vm.currentColor),
            )
        }

        Spacer(Modifier.height(6.dp))

        // ── Size slider ──
        VerticalDragSlider(
            value = vm.brushSettings.size,
            range = 1f..200f,
            fillColor = Color(0xFF4A90D9),
            modifier = Modifier.weight(1f).width(28.dp),
            onValueChange = { vm.setBrushSize(it) },
        )

        Text(
            "${vm.brushSettings.size.toInt()}",
            color = Color.White.copy(alpha = 0.54f),
            fontSize = 9.sp,
        )

        Spacer(Modifier.height(10.dp))

        Text("α", color = Color.White.copy(alpha = 0.38f), fontSize = 10.sp)

        Spacer(Modifier.height(2.dp))

        // ── Opacity slider ──
        VerticalDragSlider(
            value = vm.brushSettings.opacity,
            range = 0.01f..1f,
            fillColor = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f).width(28.dp),
            onValueChange = { vm.setBrushOpacity(it) },
        )

        Text(
            "${(vm.brushSettings.opacity * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.54f),
            fontSize = 9.sp,
        )

        Spacer(Modifier.height(10.dp))

        // Pressure toggle
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (vm.brushSettings.pressureSizeEnabled)
                        Color(0xFF4A90D9).copy(alpha = 0.3f)
                    else Color.Transparent,
                )
                .border(
                    1.dp,
                    if (vm.brushSettings.pressureSizeEnabled)
                        Color(0xFF4A90D9) else Color.White.copy(alpha = 0.24f),
                    CircleShape,
                )
                .clickable { vm.togglePressureSize() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = "筆圧",
                tint = if (vm.brushSettings.pressureSizeEnabled)
                    Color(0xFF6AB0FF) else Color.White.copy(alpha = 0.38f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Procreate-style vertical slider drawn with Canvas.
 * Drag up to increase, drag down to decrease.
 * Filled portion rises from the bottom.
 */
@Composable
private fun VerticalDragSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fillColor: Color,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)

    // rememberUpdatedState keeps references current inside the coroutine
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                val h = size.height.toFloat()
                detectDragGestures(
                    onDragStart = { offset ->
                        // Tap: set value directly from touch position
                        val f = 1f - (offset.y / h).coerceIn(0f, 1f)
                        currentOnChange((range.start + f * span).coerceIn(range.start, range.endInclusive))
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Use currentValue (always up-to-date) + drag delta
                        val delta = -dragAmount.y / h * span
                        currentOnChange((currentValue + delta).coerceIn(range.start, range.endInclusive))
                    },
                )
            },
    ) {
        val trackWidth = 6.dp.toPx()
        val thumbRadius = 8.dp.toPx()
        val centerX = size.width / 2f
        val trackLeft = centerX - trackWidth / 2f

        // Background track
        drawRoundRect(
            color = Color(0xFF333333),
            topLeft = Offset(trackLeft, 0f),
            size = Size(trackWidth, size.height),
            cornerRadius = CornerRadius(trackWidth / 2f),
        )

        // Filled track (bottom-up)
        val fillHeight = size.height * fraction
        if (fillHeight > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(trackLeft, size.height - fillHeight),
                size = Size(trackWidth, fillHeight),
                cornerRadius = CornerRadius(trackWidth / 2f),
            )
        }

        // Thumb
        val thumbY = size.height * (1f - fraction)
        val clampedY = thumbY.coerceIn(thumbRadius, size.height - thumbRadius)
        drawCircle(Color.White, thumbRadius, Offset(centerX, clampedY))
        drawCircle(fillColor, thumbRadius - 2.dp.toPx(), Offset(centerX, clampedY))
    }
}
