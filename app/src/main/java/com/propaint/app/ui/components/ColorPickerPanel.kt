package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp
import com.propaint.app.viewmodel.PaintViewModel
import kotlin.math.*

/**
 * Procreate-style color picker as bottom popup.
 * Horizontal layout: Hue ring + SV square (left), color info + history (right).
 */
@Composable
fun ColorPickerPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val currentColor by viewModel.currentColor.collectAsState()
    val history by viewModel.colorHistory.collectAsState()

    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (currentColor.red * 255).toInt(), (currentColor.green * 255).toInt(),
            (currentColor.blue * 255).toInt(), hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }

    fun update(h: Float, s: Float, v: Float) {
        hue = h; sat = s; value = v
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        viewModel.setColor(Color(((rgb shr 16) and 0xFF) / 255f,
            ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), addToHistory = false)
    }

    Box(
        modifier = modifier
            .widthIn(max = 440.sdp)
            .shadow(20.sdp, RoundedCornerShape(16.sdp))
            .clip(RoundedCornerShape(16.sdp))
            .background(Color(0xF01E1E1E))
            .padding(16.sdp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.sdp),
        ) {
            // ── Left: Hue ring + SV square ──
            HueRingSVSquare(
                hue = hue, sat = sat, value = value,
                onHueChange = { update(it, sat, value) },
                onSVChange = { s, v -> update(hue, s, v) },
                modifier = Modifier.size(210.sdp),
            )

            // ── Right: Color info + history ──
            Column(Modifier.width(170.sdp)) {
                // Current color + hex + close
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.sdp)
                                .clip(RoundedCornerShape(5.sdp))
                                .background(currentColor)
                                .border(1.5.dp, Color.White, RoundedCornerShape(5.sdp)),
                        )
                        Spacer(Modifier.width(8.sdp))
                        val r = (currentColor.red * 255).toInt()
                        val g = (currentColor.green * 255).toInt()
                        val b = (currentColor.blue * 255).toInt()
                        Text(
                            String.format("#%02X%02X%02X", r, g, b),
                            color = Color(0xFFAAAAAA), fontSize = 11.ssp,
                        )
                    }
                    PanelCloseButton(onDismiss)
                }

                Spacer(Modifier.height(12.sdp))
                Text("カラー履歴", color = Color(0xFFAAAAAA), fontSize = 10.ssp)
                Spacer(Modifier.height(6.sdp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.heightIn(max = 120.sdp),
                    horizontalArrangement = Arrangement.spacedBy(4.sdp),
                    verticalArrangement = Arrangement.spacedBy(4.sdp),
                ) {
                    items(history) { c ->
                        val isSelected = c == currentColor
                        Box(
                            Modifier
                                .size(24.sdp)
                                .clip(RoundedCornerShape(4.sdp))
                                .background(c)
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) Color.White else Color(0xFF444444),
                                    RoundedCornerShape(4.sdp),
                                )
                                .clickable { viewModel.setColor(c) },
                        )
                    }
                }
            }
        }
    }
}

// ── Hue Ring + SV Square ──

private enum class DragTarget { None, Hue, SV }

@Composable
private fun HueRingSVSquare(
    hue: Float, sat: Float, value: Float,
    onHueChange: (Float) -> Unit,
    onSVChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringWidth = 22f
    var dragTarget by remember { mutableStateOf(DragTarget.None) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val dx = offset.x - cx; val dy = offset.y - cy
                    val dist = sqrt(dx * dx + dy * dy)
                    val outerR = minOf(cx, cy)
                    val innerR = outerR - ringWidth

                    if (dist >= innerR && dist <= outerR + 6f) {
                        val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360
                        onHueChange(angle.toFloat())
                    } else if (dist < innerR) {
                        val halfSq = innerR / sqrt(2f) * 0.92f
                        val sx = ((offset.x - cx + halfSq) / (halfSq * 2f)).coerceIn(0f, 1f)
                        val sy = ((offset.y - cy + halfSq) / (halfSq * 2f)).coerceIn(0f, 1f)
                        onSVChange(sx, 1f - sy)
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val dx = offset.x - cx; val dy = offset.y - cy
                        val dist = sqrt(dx * dx + dy * dy)
                        val outerR = minOf(cx, cy)
                        val innerR = outerR - ringWidth
                        dragTarget = when {
                            dist >= innerR - 6f && dist <= outerR + 6f -> DragTarget.Hue
                            dist < innerR -> DragTarget.SV
                            else -> DragTarget.None
                        }
                    },
                    onDragEnd = { dragTarget = DragTarget.None },
                    onDragCancel = { dragTarget = DragTarget.None },
                ) { change, _ ->
                    change.consume()
                    val cx = size.width / 2f; val cy = size.height / 2f
                    when (dragTarget) {
                        DragTarget.Hue -> {
                            val dx = change.position.x - cx
                            val dy = change.position.y - cy
                            val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360
                            onHueChange(angle.toFloat())
                        }
                        DragTarget.SV -> {
                            val outerR = minOf(cx, cy)
                            val innerR = outerR - ringWidth
                            val halfSq = innerR / sqrt(2f) * 0.92f
                            val sx = ((change.position.x - cx + halfSq) / (halfSq * 2f)).coerceIn(0f, 1f)
                            val sy = ((change.position.y - cy + halfSq) / (halfSq * 2f)).coerceIn(0f, 1f)
                            onSVChange(sx, 1f - sy)
                        }
                        DragTarget.None -> {}
                    }
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = minOf(cx, cy)
        val innerR = outerR - ringWidth

        // ── Hue ring ──
        for (i in 0 until 360) {
            val angle = i.toFloat()
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(angle, 1f, 1f))
            val startAngle = angle - 90f
            drawArc(
                color = Color(rgb),
                startAngle = startAngle,
                sweepAngle = 1.5f,
                useCenter = true,
                topLeft = Offset(cx - outerR, cy - outerR),
                size = Size(outerR * 2, outerR * 2),
            )
        }
        drawCircle(Color(0xFF1E1E1E), innerR, Offset(cx, cy))

        // ── SV square ──
        val halfSq = innerR / sqrt(2f) * 0.92f
        val sqLeft = cx - halfSq
        val sqTop = cy - halfSq
        val sqSize = halfSq * 2f
        val step = 3f

        var px = 0f
        while (px < sqSize) {
            var py = 0f
            while (py < sqSize) {
                val s = px / sqSize
                val v = 1f - py / sqSize
                val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v))
                drawRect(Color(rgb), Offset(sqLeft + px, sqTop + py), Size(step + 0.5f, step + 0.5f))
                py += step
            }
            px += step
        }

        // SV cursor
        val svCx = sqLeft + sat * sqSize
        val svCy = sqTop + (1f - value) * sqSize
        drawCircle(Color.White, 7f, Offset(svCx, svCy), style = Stroke(2.5f))
        drawCircle(Color.Black, 5f, Offset(svCx, svCy), style = Stroke(1f))

        // Hue cursor
        val hueRad = Math.toRadians((hue - 90).toDouble())
        val hueR = (innerR + outerR) / 2f
        val hueCx = cx + cos(hueRad).toFloat() * hueR
        val hueCy = cy + sin(hueRad).toFloat() * hueR
        drawCircle(Color.White, ringWidth / 2f - 1f, Offset(hueCx, hueCy), style = Stroke(3f))
        drawCircle(Color.Black, ringWidth / 2f - 3f, Offset(hueCx, hueCy), style = Stroke(1f))
    }
}
