package com.propaint.app.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import com.propaint.app.model.*
import kotlin.math.*
import kotlin.random.Random

object StrokeRenderer {

    fun DrawScope.renderLayers(
        layers: List<PaintLayer>,
        currentPoints: List<StrokePoint>,
        currentBrush: BrushSettings,
        currentColor: Color,
        activeLayerId: String,
        backgroundColor: Color,
        showGrid: Boolean,
        layerBitmaps: Map<String, ImageBitmap> = emptyMap(),
        // ライブストローク増分描画: ベイク済みビットマップ + 末尾テール点
        liveStrokeBitmap: ImageBitmap? = null,
        liveStrokeTail: List<StrokePoint> = emptyList(),
    ) {
        // Background
        drawRect(backgroundColor)

        // Grid
        if (showGrid) drawGrid()

        // Layers
        for (layer in layers) {
            if (!layer.isVisible) continue

            val cached = layerBitmaps[layer.id]
            if (cached != null) {
                drawImage(image = cached, alpha = layer.opacity)
            } else {
                val layerAlpha = layer.opacity
                for (stroke in layer.strokes) {
                    drawStroke(stroke, layerAlpha)
                }
            }

            // Live stroke on active layer
            if (layer.id == activeLayerId) {
                when {
                    // ベイク済みビットマップ + テール: 長いストロークを高速描画
                    liveStrokeBitmap != null -> {
                        drawImage(image = liveStrokeBitmap, alpha = layer.opacity)
                        if (liveStrokeTail.size >= 2) {
                            drawStroke(
                                Stroke(liveStrokeTail, currentBrush, currentColor, activeLayerId),
                                layer.opacity,
                            )
                        }
                    }
                    // ビットマップ未作成 (ストローク序盤): 全点をライブ描画
                    currentPoints.size >= 2 -> {
                        drawStroke(
                            Stroke(currentPoints, currentBrush, currentColor, activeLayerId),
                            layer.opacity,
                        )
                    }
                }
            }
        }
    }

    /** キャッシュビットマップへの増分描画用。背景を描かずストロークのみ描画する。 */
    fun DrawScope.renderStrokes(strokes: List<Stroke>, layerAlpha: Float = 1f) {
        for (stroke in strokes) {
            if (stroke.points.size >= 2) drawStroke(stroke, layerAlpha)
        }
    }

    /**
     * ストローク全体をオフスクリーンレイヤーへ描画し、[alpha] でまとめて合成する。
     * セグメント同士のキャップ重複部でアルファが累積する「継ぎ目」問題を解消する。
     */
    private inline fun DrawScope.withStrokeLayer(alpha: Float, block: DrawScope.() -> Unit) {
        if (alpha >= 1f) {
            block()
            return
        }
        drawContext.canvas.saveLayer(
            Rect(Offset.Zero, size),
            Paint().apply { this.alpha = alpha },
        )
        block()
        drawContext.canvas.restore()
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
            BrushType.Airbrush -> drawAirbrushStroke(stroke, layerAlpha)
            BrushType.Watercolor -> drawWatercolorStroke(stroke, layerAlpha)
            BrushType.Crayon -> drawCrayonStroke(stroke, layerAlpha)
            BrushType.Calligraphy -> drawCalligraphyStroke(stroke, layerAlpha)
            BrushType.Eraser -> drawEraserStroke(stroke)
            else -> drawNormalStroke(stroke, layerAlpha)
        }
    }

    private fun DrawScope.drawNormalStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val points = stroke.points

        if (b.pressureSizeEnabled || b.pressureOpacityEnabled) {
            // 流量モデル: saveLayer(opacity) が天井、内部は SrcOver でスタンプを蓄積
            // density = 1 スタンプあたりの塗り量（低いほど重ね塗りが必要）
            val opacity = b.opacity * layerAlpha
            withStrokeLayer(alpha = opacity) {
                for (i in 0 until points.size - 1) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val pressure = (p0.pressure + p1.pressure) * 0.5f

                    val width = if (b.pressureSizeEnabled)
                        b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressure)
                    else b.size

                    val alpha = b.density * if (b.pressureOpacityEnabled)
                        (0.15f + 0.85f * pressure).coerceIn(0f, 1f)
                    else 1f

                    drawLine(
                        color = stroke.color.copy(alpha = alpha),
                        start = p0.position,
                        end = p1.position,
                        strokeWidth = width,
                        cap = b.type.cap,
                        blendMode = BlendMode.SrcOver,
                    )
                }
            }
        } else {
            // Uniform: opacity × density が実効 alpha
            val path = buildSmoothPath(points)
            drawPath(
                path = path,
                color = stroke.color.copy(alpha = b.opacity * b.density * layerAlpha),
                style = ComposeStroke(
                    width = b.size,
                    cap = b.type.cap,
                    join = b.type.join,
                ),
            )
        }
    }

    private fun DrawScope.drawAirbrushStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val rng = Random(42)

        withStrokeLayer(alpha = b.opacity * layerAlpha) {
            for (point in stroke.points) {
                val pressure = point.pressure
                val radius = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressure)
                else b.size
                // density = スプレー 1 粒あたりの基本不透明度
                val alpha = b.density * if (b.pressureOpacityEnabled)
                    (0.2f + 0.8f * pressure).coerceIn(0f, 1f)
                else 1f

                repeat(8) {
                    val offset = Offset(
                        point.position.x + (rng.nextFloat() - 0.5f) * radius * 2f,
                        point.position.y + (rng.nextFloat() - 0.5f) * radius * 2f,
                    )
                    val r = radius * 0.3f * rng.nextFloat()
                    drawCircle(
                        color = stroke.color.copy(alpha = alpha),
                        radius = r,
                        center = offset,
                    )
                }
            }
        }
    }

    private fun DrawScope.drawWatercolorStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val points = stroke.points

        // 流量モデル（ストロークごとの opacity 天井なし）:
        // ・各スタンプを SrcOver で直接キャンバスへ蓄積する。
        // ・per-stamp alpha は watercolorMix(density) が担う。
        // ・withStrokeLayer は layerAlpha のみ（レイヤー不透明度）。
        withStrokeLayer(alpha = layerAlpha) {
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val dx = p1.position.x - p0.position.x
                val dy = p1.position.y - p0.position.y
                val dist = sqrt(dx * dx + dy * dy)

                val avgPressure = (p0.pressure + p1.pressure) * 0.5f
                val avgRadius = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * avgPressure) / 2f
                else b.size / 2f

                // spacing 倍率でステップを伸縮。基準: avgRadius * 0.5
                val step = maxOf(1f, avgRadius * 0.5f * b.spacing)
                val steps = maxOf(1, (dist / step).toInt())

                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    val pressure = p0.pressure + (p1.pressure - p0.pressure) * t
                    val pos = Offset(p0.position.x + dx * t, p0.position.y + dy * t)

                    val radius = if (b.pressureSizeEnabled)
                        b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressure) / 2f
                    else b.size / 2f

                    // fallback: 混色なし時は stroke.color の alpha = density で代替
                    val c0 = p0.color ?: stroke.color.copy(alpha = b.density)
                    val c1 = p1.color ?: stroke.color.copy(alpha = b.density)
                    val stampColor = lerp(c0, c1, t)

                    // 間隔補正: spacing が小さい(密)ほど 1 スタンプの alpha を下げ、
                    // spacing が大きい(疎)ほど alpha を上げる。単位長さあたりの塗り量を一定に保つ。
                    val pressureFactor = if (b.pressureOpacityEnabled)
                        (0.3f + 0.7f * pressure).coerceIn(0f, 1f)
                    else 1f
                    val finalAlpha = (stampColor.alpha * b.spacing * pressureFactor).coerceIn(0f, 1f)

                    drawCircle(
                        color = stampColor.copy(alpha = finalAlpha),
                        radius = radius,
                        center = pos,
                        blendMode = BlendMode.SrcOver,
                    )
                }
            }
        }
    }

    private fun DrawScope.drawCrayonStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val rng = Random(0)

        withStrokeLayer(alpha = b.opacity * layerAlpha) {
            for (i in 0 until stroke.points.size - 1) {
                val p0 = stroke.points[i]
                val p1 = stroke.points[i + 1]
                val pressure = (p0.pressure + p1.pressure) * 0.5f
                val dist = (p1.position - p0.position).getDistance()
                val steps = maxOf(1, (dist / 2f).toInt())
                val radius = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressure)
                else b.size

                for (s in 0 until steps) {
                    val t = s.toFloat() / steps
                    val pos = Offset(
                        p0.position.x + (p1.position.x - p0.position.x) * t,
                        p0.position.y + (p1.position.y - p0.position.y) * t,
                    )
                    repeat(3) {
                        val off = Offset(
                            pos.x + (rng.nextFloat() - 0.5f) * radius * 0.8f,
                            pos.y + (rng.nextFloat() - 0.5f) * radius * 0.8f,
                        )
                        // density でクレヨン粒のランダム alpha をスケール
                        drawCircle(
                            color = stroke.color.copy(
                                alpha = (b.density * (0.4f + rng.nextFloat() * 0.6f)).coerceIn(0f, 1f)
                            ),
                            radius = radius * 0.2f * rng.nextFloat(),
                            center = off,
                        )
                    }
                }
            }
        }
    }

    private fun DrawScope.drawCalligraphyStroke(stroke: Stroke, layerAlpha: Float) {
        val b = stroke.brush
        val points = stroke.points

        // 流量モデル: saveLayer(opacity) が天井、内部は SrcOver でスタンプを蓄積
        val opacity = b.opacity * layerAlpha
        withStrokeLayer(alpha = opacity) {
            for (i in 0 until points.size - 1) {
                val p0 = points[i].position
                val p1 = points[i + 1].position
                val pressure = points[i].pressure
                val angle = atan2(p1.y - p0.y, p1.x - p0.x)
                val widthFactor = 0.3f + 0.7f * abs(sin(angle))
                val pf = if (b.pressureSizeEnabled)
                    b.minSizeRatio + (1f - b.minSizeRatio) * pressure
                else 1f
                val width = b.size * widthFactor * pf
                val alpha = b.density * if (b.pressureOpacityEnabled)
                    (0.2f + 0.8f * pressure).coerceIn(0f, 1f)
                else 1f

                drawLine(
                    color = stroke.color.copy(alpha = alpha),
                    start = p0,
                    end = p1,
                    strokeWidth = width,
                    cap = StrokeCap.Square,
                    blendMode = BlendMode.SrcOver,
                )
            }
        }
    }

    private fun DrawScope.drawEraserStroke(stroke: Stroke) {
        val b = stroke.brush
        val path = buildSmoothPath(stroke.points)
        drawPath(
            path = path,
            color = Color.Transparent,
            style = ComposeStroke(width = b.size, cap = StrokeCap.Round),
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
                val p0 = points[i].position
                val p1 = points[i + 1].position
                val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                path.quadraticBezierTo(p0.x, p0.y, mid.x, mid.y)
            }
            val last = points.last().position
            path.lineTo(last.x, last.y)
        }
        return path
    }
}
