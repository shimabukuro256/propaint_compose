package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
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

/**
 * 左端の縦型クイックバー。
 * サイズスライダー・不透明度スライダーのみ。
 * ツール切り替えと筆圧ボタンは TopBar に移動済み。
 *
 * [modifier] には位置・サイズ制約を渡す。TopBar の高さ分 paddingTop が必要。
 */
@Composable
fun SideQuickBar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(44.dp)
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(Color(0xEF1A1A1A))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ブラシサイズドットプレビュー
        Box(
            modifier = Modifier
                .size(28.dp)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val dotSize = (vm.brushSettings.size / 200f * 20f).coerceIn(2f, 20f)
            Box(
                Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(vm.currentColor),
            )
        }

        Spacer(Modifier.height(6.dp))

        // サイズスライダー
        Text("S", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp)
        Spacer(Modifier.height(2.dp))

        VerticalDragSlider(
            value         = vm.brushSettings.size,
            range         = 1f..200f,
            fillColor     = Color(0xFF4A90D9),
            modifier      = Modifier.weight(1f).width(26.dp),
            onValueChange = { vm.setBrushSize(it) },
        )

        Text(
            "${vm.brushSettings.size.toInt()}",
            color    = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
        )

        Spacer(Modifier.height(10.dp))

        // 不透明度スライダー
        Text("α", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp)
        Spacer(Modifier.height(2.dp))

        VerticalDragSlider(
            value         = vm.brushSettings.opacity,
            range         = 0.01f..1f,
            fillColor     = Color.White.copy(alpha = 0.6f),
            modifier      = Modifier.weight(1f).width(26.dp),
            onValueChange = { vm.setBrushOpacity(it) },
        )

        Text(
            "${(vm.brushSettings.opacity * 100).toInt()}%",
            color    = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
        )

        Spacer(Modifier.height(4.dp))
    }
}

/** 縦型ドラッグスライダー。上にドラッグで増加、下で減少。 */
@Composable
fun VerticalDragSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fillColor: Color,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    val span     = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)

    val currentValue    by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val h = size.height.toFloat()
            detectDragGestures(
                onDragStart = { offset ->
                    val f = 1f - (offset.y / h).coerceIn(0f, 1f)
                    currentOnChange((range.start + f * span).coerceIn(range.start, range.endInclusive))
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val delta = -dragAmount.y / h * span
                    currentOnChange((currentValue + delta).coerceIn(range.start, range.endInclusive))
                },
            )
        },
    ) {
        val trackW = 6.dp.toPx()
        val thumbR = 7.dp.toPx()
        val cx     = size.width / 2f
        val tl     = cx - trackW / 2f

        drawRoundRect(
            color        = Color(0xFF333333),
            topLeft      = Offset(tl, 0f),
            size         = Size(trackW, size.height),
            cornerRadius = CornerRadius(trackW / 2f),
        )

        val fillH = size.height * fraction
        if (fillH > 0f) {
            drawRoundRect(
                color        = fillColor,
                topLeft      = Offset(tl, size.height - fillH),
                size         = Size(trackW, fillH),
                cornerRadius = CornerRadius(trackW / 2f),
            )
        }

        val thumbY = (size.height * (1f - fraction)).coerceIn(thumbR, size.height - thumbR)
        drawCircle(Color.White, thumbR, Offset(cx, thumbY))
        drawCircle(fillColor, thumbR - 2.dp.toPx(), Offset(cx, thumbY))
    }
}
