package com.propaint.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.propaint.app.gl.GlCanvasView
import com.propaint.app.gl.RenderSnapshot
import com.propaint.app.model.BrushType
import com.propaint.app.model.needsCanvasCapture
import com.propaint.app.viewmodel.PaintViewModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 描画キャンバス。
 * - GlCanvasView (GLSurfaceView) が GPU でレンダリング
 * - Compose pointerInput でタッチ/スタイラス入力を処理
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawingCanvas(
    viewModel: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val glView  = remember { GlCanvasView(context) }

    var isPointerDrawing by remember { mutableStateOf(false) }
    var canvasWidth  by remember { mutableFloatStateOf(1f) }
    var canvasHeight by remember { mutableFloatStateOf(1f) }

    @Suppress("UNUSED_VARIABLE")
    val _sv = viewModel.strokeVersion

    fun screenToCanvas(screen: Offset): Offset {
        val cx   = canvasWidth  / 2f
        val cy   = canvasHeight / 2f
        val dx   = screen.x - viewModel.panOffset.x - cx
        val dy   = screen.y - viewModel.panOffset.y - cy
        val cosT = cos(viewModel.rotation.toDouble()).toFloat()
        val sinT = sin(viewModel.rotation.toDouble()).toFloat()
        return Offset(
            cx + ( cosT * dx + sinT * dy) / viewModel.zoom,
            cy + (-sinT * dx + cosT * dy) / viewModel.zoom,
        )
    }

    /** キャンバス色サンプリングが必要なブラシの場合にピクセルキャプチャを要求 */
    fun requestCapture(layerId: String) {
        if (viewModel.brushSettings.type.needsCanvasCapture) {
            glView.requestWatercolorCapture(layerId) { pixels, w, h ->
                viewModel.setPixelCapture(pixels, w, h)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    canvasWidth  = size.width.toFloat()
                    canvasHeight = size.height.toFloat()
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event   = awaitPointerEvent()
                            val changes = event.changes

                            val hasStylusPointer = changes.any {
                                it.type == PointerType.Stylus || it.type == PointerType.Eraser
                            }

                            when {
                                // ── スタイラス描画 ────────────────────────────
                                hasStylusPointer -> {
                                    val change   = changes.first {
                                        it.type == PointerType.Stylus || it.type == PointerType.Eraser
                                    }
                                    val pressure = change.pressure.coerceIn(0f, 1f)
                                    val pos      = screenToCanvas(change.position)

                                    when {
                                        change.changedToDown() -> {
                                            isPointerDrawing = true
                                            if (change.type == PointerType.Eraser)
                                                viewModel.selectBrushType(BrushType.Eraser)
                                            requestCapture(viewModel.activeLayerId)
                                            viewModel.startStroke(pos, pressure)
                                            change.consume()
                                        }
                                        change.pressed && isPointerDrawing -> {
                                            for (h in change.historical)
                                                viewModel.addStrokePoint(screenToCanvas(h.position), pressure)
                                            viewModel.addStrokePoint(pos, pressure)
                                            change.consume()
                                        }
                                        change.changedToUp() -> {
                                            if (isPointerDrawing) {
                                                viewModel.endStroke()
                                                viewModel.clearPixelCapture()
                                                isPointerDrawing = false
                                            }
                                            change.consume()
                                        }
                                    }
                                }

                                // ── 2 本指: ピンチ / パン / 回転 ──────────────
                                changes.size >= 2 -> {
                                    if (isPointerDrawing) {
                                        viewModel.endStroke()
                                        viewModel.clearPixelCapture()
                                        isPointerDrawing = false
                                    }
                                    val p0 = changes[0]; val p1 = changes[1]
                                    val curDist  = dist(p0.position, p1.position)
                                    val prevDist = dist(p0.previousPosition, p1.previousPosition)
                                    val curCenter  = (p0.position + p1.position) / 2f
                                    val prevCenter = (p0.previousPosition + p1.previousPosition) / 2f

                                    val cx = canvasWidth / 2f; val cy = canvasHeight / 2f
                                    val oldZoom = viewModel.zoom
                                    val oldPan  = viewModel.panOffset
                                    val oldRot  = viewModel.rotation

                                    val newZoom = if (prevDist > 10f)
                                        (oldZoom * (curDist / prevDist)).coerceIn(0.1f, 10f)
                                    else oldZoom

                                    val curVec  = p1.position - p0.position
                                    val prevVec = p1.previousPosition - p0.previousPosition
                                    val rawDA = if (prevDist > 10f)
                                        (atan2(curVec.y.toDouble(), curVec.x.toDouble()) -
                                         atan2(prevVec.y.toDouble(), prevVec.x.toDouble())).toFloat()
                                    else 0f
                                    val dAngle = when {
                                        rawDA >  Math.PI.toFloat() -> rawDA - 2f * Math.PI.toFloat()
                                        rawDA < -Math.PI.toFloat() -> rawDA + 2f * Math.PI.toFloat()
                                        else -> rawDA
                                    }
                                    val newRot = oldRot + dAngle

                                    val k    = newZoom / oldZoom
                                    val dvx  = prevCenter.x - cx - oldPan.x
                                    val dvy  = prevCenter.y - cy - oldPan.y
                                    val cosDA = cos(dAngle.toDouble()).toFloat()
                                    val sinDA = sin(dAngle.toDouble()).toFloat()
                                    val newPanX = curCenter.x - cx - k * (cosDA * dvx - sinDA * dvy)
                                    val newPanY = curCenter.y - cy - k * (sinDA * dvx + cosDA * dvy)

                                    viewModel.updateViewTransform(newZoom, Offset(newPanX, newPanY), newRot)
                                    glView.submitTransformFast(newZoom, newPanX, newPanY, newRot)
                                    changes.forEach { it.consume() }
                                }

                                // ── 1 本指: 描画 ─────────────────────────────
                                changes.size == 1 -> {
                                    val change   = changes[0]
                                    val pressure = change.pressure.coerceIn(0f, 1f)
                                    val pos      = screenToCanvas(change.position)

                                    when {
                                        change.changedToDown() -> {
                                            isPointerDrawing = true
                                            requestCapture(viewModel.activeLayerId)
                                            viewModel.startStroke(pos, pressure)
                                            change.consume()
                                        }
                                        change.pressed && isPointerDrawing -> {
                                            for (h in change.historical)
                                                viewModel.addStrokePoint(screenToCanvas(h.position), pressure)
                                            viewModel.addStrokePoint(pos, pressure)
                                            change.consume()
                                        }
                                        change.changedToUp() -> {
                                            if (isPointerDrawing) {
                                                viewModel.endStroke()
                                                viewModel.clearPixelCapture()
                                                isPointerDrawing = false
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        )
    }

    SideEffect {
        glView.submitSnapshot(
            RenderSnapshot(
                layers        = viewModel.layers.toList(),
                activeLayerId = viewModel.activeLayerId,
                currentPoints = viewModel.currentStrokePoints.toList(),
                currentBrush  = viewModel.brushSettings,
                currentColor  = viewModel.currentColor,
                showGrid      = viewModel.showGrid,
                zoom          = viewModel.zoom,
                panX          = viewModel.panOffset.x,
                panY          = viewModel.panOffset.y,
                rotation      = viewModel.rotation,
            )
        )
    }
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
