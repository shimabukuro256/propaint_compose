package com.propaint.app.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import com.propaint.app.model.*
import kotlin.math.*

/**
 * Compose Canvas ベースのソフトウェアレンダラー (参照実装)。
 * 実際の描画は GlBrushRenderer / CanvasGlRenderer が担当する。
 * このクラスは互換性のため残してある。
 */
object StrokeRenderer {

    fun DrawScope.renderLayers(
        layers: List<PaintLayer>,
        currentPoints: List<StrokePoint>,
        currentBrush: BrushSettings,
        currentColor: Color,
        activeLayerId: String,
        backgroundColor: Color,
        showGrid: Boolean,
    ) {
        drawRect(backgroundColor)
        if (showGrid) drawGrid()

        for (layer in layers) {
            if (!layer.isVisible) continue
            val layerAlpha = layer.opacity
            for (stroke in layer.strokes) {
                drawStroke(stroke, layerAlpha)
            }
            if (layer.id == activeLayerId && currentPoints.size >= 2) {
                drawStroke(Stroke(currentPoints, currentBrush, currentColor, activeLayerId), layerAlpha)
            }
        }
    }

    private fun DrawScope.drawGrid() {
        val gridPaint = Color.Gray.copy(alpha = 0.15f)
        val step = 50f
        var x = 0f
        while (x < size.width) {
            drawLine(gridPaint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridPaint, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
            y += step
        }
    }

    private fun DrawScope.drawStroke(stroke: Stroke, layerAlpha: Float) {
        if (stroke.points.size < 2) return
        when (stroke.brush.type) {
            BrushType.Eraser    -> drawEraserStroke(stroke)
            BrushType.Airbrush  -> drawAirbrushStroke(stroke, layerAlpha)
            BrushType.Fude,
            BrushType.Watercolor,
            BrushType.Marker,
            BrushType.Blur      -> drawSampledStroke(stroke, layerAlpha)
            else                -> drawNormalStroke(stroke, layerAlpha)
        }
    }

    private fun DrawScope.drawNormalStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val path = buildSmoothPath(stroke.points)
        drawPath(
            path  = path,
            color = stroke.color.copy(alpha = b.opacity * b.density * layerAlpha),
            style = ComposeStroke(width = b.size, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    private fun DrawScope.drawSampledStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val points = stroke.points
        for (i in 0 until points.size - 1) {
            val p0 = points[i]; val p1 = points[i + 1]
            val t = 0.5f
            val c0 = p0.color ?: stroke.color
            val c1 = p1.color ?: stroke.color
            val color = Color(
                red   = c0.red   + (c1.red   - c0.red)   * t,
                green = c0.green + (c1.green - c0.green) * t,
                blue  = c0.blue  + (c1.blue  - c0.blue)  * t,
                alpha = (b.opacity * b.density * layerAlpha).coerceIn(0f, 1f),
            )
            drawLine(color, p0.position, p1.position, strokeWidth = b.size, cap = StrokeCap.Round)
        }
    }

    private fun DrawScope.drawAirbrushStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        for (point in stroke.points) {
            val radius = b.size
            drawCircle(
                color  = stroke.color.copy(alpha = b.opacity * b.density * layerAlpha * 0.5f),
                radius = radius,
                center = point.position,
            )
        }
    }

    private fun DrawScope.drawEraserStroke(stroke: Stroke) {
        val b = stroke.brush
        val path = buildSmoothPath(stroke.points)
        drawPath(
            path      = path,
            color     = Color.Transparent,
            style     = ComposeStroke(width = b.size, cap = StrokeCap.Round),
            blendMode = BlendMode.Clear,
        )
    }

    private fun buildSmoothPath(points: List<StrokePoint>): Path {
        val path = Path()
        path.moveTo(points[0].position.x, points[0].position.y)
        if (points.size == 2) {
            path.lineTo(points[1].position.x, points[1].position.y)
        } else {
            for (i in 1 until points.size - 1) {
                val p0 = points[i].position; val p1 = points[i + 1].position
                val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                path.quadraticBezierTo(p0.x, p0.y, mid.x, mid.y)
            }
            val last = points.last().position
            path.lineTo(last.x, last.y)
        }
        return path
    }
}
