package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
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
import kotlin.math.sqrt

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
            val dotSize = (vm.brushSettings.size / 2000f * 20f).coerceIn(2f, 20f)
            Box(
                Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(vm.currentColor),
            )
        }

        Spacer(Modifier.height(4.dp))

        // スポイトボタン
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (vm.isEyedropperActive) Color(0xFF4A90D9).copy(alpha = 0.3f)
                    else Color.Transparent
                )
                .clickable {
                    if (vm.isEyedropperActive) vm.deactivateEyedropper()
                    else vm.activateEyedropper()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ColorLens,
                contentDescription = "スポイト",
                tint = if (vm.isEyedropperActive) Color(0xFF6AB0FF)
                       else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.height(6.dp))

        // サイズスライダー (sqrt スケール)
        Text("S", color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp)
        Spacer(Modifier.height(2.dp))

        SizeVerticalDragSlider(
            brushSize     = vm.brushSettings.size,
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

        // 不透明度 or 濃度スライダー (筆・水彩筆・エアブラシは濃度)
        val isDensityBrush = vm.brushSettings.type == com.propaint.app.model.BrushType.Fude ||
                             vm.brushSettings.type == com.propaint.app.model.BrushType.Watercolor ||
                             vm.brushSettings.type == com.propaint.app.model.BrushType.Airbrush
        val quickValue  = if (isDensityBrush) vm.brushSettings.density else vm.brushSettings.opacity
        val quickLabel  = if (isDensityBrush) "濃" else "α"
        Text(quickLabel, color = Color.White.copy(alpha = 0.35f), fontSize = 9.sp)
        Spacer(Modifier.height(2.dp))

        VerticalDragSlider(
            value         = quickValue,
            range         = 0.01f..1f,
            fillColor     = Color.White.copy(alpha = 0.6f),
            modifier      = Modifier.weight(1f).width(26.dp),
            onValueChange = { if (isDensityBrush) vm.setBrushDensity(it) else vm.setBrushOpacity(it) },
        )

        Text(
            "${(quickValue * 100).toInt()}%",
            color    = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
        )

        Spacer(Modifier.height(4.dp))
    }
}

/**
 * サイズ用縦型ドラッグスライダー (sqrt スケール)。
 * 上にドラッグで増加、下で減少。
 * ドラッグ量は視覚空間 (sqrt) で計算し、実際のサイズに変換する。
 */
@Composable
fun SizeVerticalDragSlider(
    brushSize: Float,
    fillColor: Color,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    // 視覚的フラクション = sqrt((brushSize-1)/1999)
    val fraction = sqrt((brushSize - 1f) / 1999f).coerceIn(0f, 1f)

    val currentBrushSize by rememberUpdatedState(brushSize)
    val currentOnChange  by rememberUpdatedState(onValueChange)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val h = this.size.height.toFloat()
            detectDragGestures(
                onDragStart = { offset ->
                    val f = (1f - (offset.y / h).coerceIn(0f, 1f))
                    val actualSize = (f * f * 1999f + 1f).coerceIn(1f, 2000f)
                    currentOnChange(actualSize)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    // 現在サイズを視覚フラクションに変換してデルタを加算
                    val curFrac = sqrt((currentBrushSize - 1f) / 1999f).coerceIn(0f, 1f)
                    val newFrac = (curFrac - dragAmount.y / h).coerceIn(0f, 1f)
                    val actualSize = (newFrac * newFrac * 1999f + 1f).coerceIn(1f, 2000f)
                    currentOnChange(actualSize)
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
