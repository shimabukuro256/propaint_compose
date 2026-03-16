package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.viewmodel.PaintViewModel

private val basicColors = listOf(
    Color.Black, Color.White, Color(0xFF808080),
    Color.Red, Color(0xFFFF6600), Color(0xFFFFA500),
    Color.Yellow, Color(0xFF99FF00), Color.Green,
    Color(0xFF00FF99), Color.Cyan, Color(0xFF0099FF),
    Color.Blue, Color(0xFF6600FF), Color(0xFF9900CC),
    Color(0xFFFF0099), Color(0xFF663300), Color(0xFFFFCC99),
)

private val skinColors = listOf(
    Color(0xFFFFF0E0), Color(0xFFFFDFC4), Color(0xFFEBCFB4),
    Color(0xFFD1A68C), Color(0xFFA87856), Color(0xFF8D5B3E),
    Color(0xFF6E3B24), Color(0xFF4A2410),
)

@Composable
fun ColorPickerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var brightness by remember { mutableFloatStateOf(1f) }

    // Sync from current color on first composition
    LaunchedEffect(Unit) {
        val hsv = FloatArray(3)
        val c = vm.currentColor
        android.graphics.Color.RGBToHSV(
            (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv,
        )
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
    }

    fun applyHSV() {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        vm.setColor(Color(argb))
    }

    fun syncFromColor(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(), hsv,
        )
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
    }

    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(Color(0xFF1E1E1E)),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                    .background(vm.currentColor)
                    .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text("カラー", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Palette, "閉じる", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = Color(0xFF333333))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            // HSV 正方形カラーピッカー (SV 平面 + 色相バー)
            HsvSquarePicker(
                hue = hue,
                saturation = saturation,
                brightness = brightness,
                onHsvChange = { h, s, v ->
                    hue = h; saturation = s; brightness = v
                    applyHSV()
                },
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(12.dp))

            // Palette
            Text("基本カラー", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            ColorGrid(colors = basicColors, selected = vm.currentColor) { color ->
                vm.setColor(color)
                syncFromColor(color)
            }

            Spacer(Modifier.height(12.dp))
            Text("スキンカラー", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            ColorGrid(colors = skinColors, selected = vm.currentColor) { color ->
                vm.setColor(color)
                syncFromColor(color)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(8.dp))

            // History
            Text("履歴", color = Color.White.copy(alpha = 0.54f), fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            ColorGrid(
                colors = vm.colorHistory.take(16),
                selected = vm.currentColor,
            ) { color ->
                vm.setColor(color)
                syncFromColor(color)
            }
        }
    }
}

/**
 * HSV 正方形カラーピッカー
 *  - 上部の正方形: 横軸 = 彩度(S)、縦軸 = 明度(V、上が明るい)
 *  - 下部のバー:   横軸 = 色相(H、0°〜360°)
 *
 * 描画原理:
 *   1. 白 → 純色 の水平グラデーションで S 軸を表現
 *   2. 透明 → 黒 の垂直グラデーションで V 軸を表現
 *   重ね合わせると pixel(s, v) = v × lerp(白, 純色, s) となり HSV と一致する
 */
@Composable
private fun HsvSquarePicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onHsvChange: (h: Float, s: Float, v: Float) -> Unit,
) {
    // pointerInput コルーチンはキーが変わるまで再起動しないため、
    // rememberUpdatedState で最新値を参照する
    val currentHue by rememberUpdatedState(hue)
    val currentSaturation by rememberUpdatedState(saturation)
    val currentBrightness by rememberUpdatedState(brightness)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // ── SV 正方形 ──
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fun update(pos: Offset) {
                            onHsvChange(
                                currentHue,
                                (pos.x / size.width).coerceIn(0f, 1f),
                                (1f - pos.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                        update(down.position)
                        var active = true
                        while (active) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { if (it.pressed) { update(it.position); it.consume() } }
                            active = ev.changes.any { it.pressed }
                        }
                    }
                },
        ) {
            // 白 → 純色 (S 軸)
            val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(currentHue, 1f, 1f)))
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
            // 透明 → 黒 (V 軸)
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

            // カーソル
            val cx = currentSaturation * size.width
            val cy = (1f - currentBrightness) * size.height
            val r = 8.dp.toPx()
            val sw = 2.dp.toPx()
            drawCircle(Color(0x66000000), radius = r + sw, center = Offset(cx, cy), style = Stroke(width = sw))
            drawCircle(Color.White, radius = r, center = Offset(cx, cy), style = Stroke(width = sw))
        }

        // ── 色相バー ──
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fun update(pos: Offset) {
                            onHsvChange(
                                (pos.x / size.width).coerceIn(0f, 1f) * 360f,
                                currentSaturation,
                                currentBrightness,
                            )
                        }
                        update(down.position)
                        var active = true
                        while (active) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { if (it.pressed) { update(it.position); it.consume() } }
                            active = ev.changes.any { it.pressed }
                        }
                    }
                },
        ) {
            // 色相グラデーション: 0° 赤 → 60° 黄 → 120° 緑 → 180° シアン → 240° 青 → 300° マゼンタ → 360° 赤
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFFFF0000),
                        Color(0xFFFFFF00),
                        Color(0xFF00FF00),
                        Color(0xFF00FFFF),
                        Color(0xFF0000FF),
                        Color(0xFFFF00FF),
                        Color(0xFFFF0000),
                    ),
                ),
            )
            // 色相インジケーター
            val x = currentHue / 360f * size.width
            val stroke = 2.dp.toPx()
            drawLine(Color(0x66000000), Offset(x - stroke, 0f), Offset(x - stroke, size.height), strokeWidth = stroke)
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke * 1.5f)
            drawLine(Color(0x66000000), Offset(x + stroke, 0f), Offset(x + stroke, size.height), strokeWidth = stroke)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorGrid(
    colors: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (color in colors) {
            val isSel = color == selected
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .border(
                        if (isSel) 2.dp else 1.dp,
                        if (isSel) Color.White else Color.White.copy(alpha = 0.24f),
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}
