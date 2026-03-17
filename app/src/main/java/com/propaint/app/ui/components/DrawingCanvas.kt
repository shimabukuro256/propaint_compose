package com.propaint.app.ui.components

import android.os.Environment
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
import com.propaint.app.model.needsWetCanvas
import com.propaint.app.viewmodel.PaintViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                        // マルチフィンガータップ状態管理 (2/3指タップでundo/redo)
                        var multiTapFingerCount = 0
                        var multiTapStartTime   = 0L
                        var multiTapMaxMoved    = 0f
                        var multiTapActive      = false

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
                                            // ウェットキャンバス系ブラシ: フレームごとにキャンバス状態を再取得。
                                            // AtomicReference により GL スレッドは最大 1 フレームに 1 回処理するため
                                            // ここで毎ポイント要求しても glReadPixels は throttle される。
                                            if (viewModel.brushSettings.type.needsWetCanvas) {
                                                requestCapture(viewModel.activeLayerId)
                                            }
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

                                // ── 2本指以上: タップ判定 + ピンチ / パン / 回転 ──
                                changes.size >= 2 -> {
                                    if (isPointerDrawing) {
                                        viewModel.endStroke()
                                        viewModel.clearPixelCapture()
                                        isPointerDrawing = false
                                    }

                                    // マルチフィンガータップ検出
                                    if (!multiTapActive && changes.any { it.changedToDown() }) {
                                        multiTapActive      = true
                                        multiTapFingerCount = changes.size
                                        multiTapStartTime   = System.currentTimeMillis()
                                        multiTapMaxMoved    = 0f
                                    }
                                    if (multiTapActive) {
                                        changes.forEach { c ->
                                            val moved = (c.position - c.previousPosition).getDistance()
                                            if (moved > multiTapMaxMoved) multiTapMaxMoved = moved
                                        }
                                    }
                                    if (multiTapActive && changes.all { it.changedToUp() }) {
                                        val elapsed = System.currentTimeMillis() - multiTapStartTime
                                        if (elapsed < 300 && multiTapMaxMoved < 20f) {
                                            // タップ確定
                                            when (multiTapFingerCount) {
                                                2 -> viewModel.undo()
                                                3 -> viewModel.redo()
                                            }
                                            changes.forEach { it.consume() }
                                        }
                                        multiTapActive = false
                                    }

                                    // タップ中でなければピンチ/パン/回転処理
                                    if (!multiTapActive || changes.none { it.changedToUp() }) {
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
                                }

                                // ── 1 本指: 描画 or スポイト ─────────────────────
                                changes.size == 1 -> {
                                    // タップが終わったらリセット
                                    if (multiTapActive && changes.all { !it.pressed }) {
                                        multiTapActive = false
                                    }

                                    val change   = changes[0]
                                    val pressure = change.pressure.coerceIn(0f, 1f)
                                    val pos      = screenToCanvas(change.position)

                                    when {
                                        change.changedToDown() -> {
                                            if (viewModel.isEyedropperActive) {
                                                // スポイト: コンポジットをキャプチャして色を取得
                                                val canvasPos = screenToCanvas(change.position)
                                                glView.requestCompositeCapture { pixels, w, h ->
                                                    viewModel.pickColorFromPixels(
                                                        pixels,
                                                        canvasPos.x.toInt(),
                                                        canvasPos.y.toInt(),
                                                        w, h,
                                                    )
                                                    viewModel.deactivateEyedropper()
                                                }
                                                change.consume()
                                            } else {
                                                isPointerDrawing = true
                                                requestCapture(viewModel.activeLayerId)
                                                viewModel.startStroke(pos, pressure)
                                                change.consume()
                                            }
                                        }
                                        change.pressed && isPointerDrawing -> {
                                            for (h in change.historical)
                                                viewModel.addStrokePoint(screenToCanvas(h.position), pressure)
                                            viewModel.addStrokePoint(pos, pressure)
                                            if (viewModel.brushSettings.type.needsWetCanvas) {
                                                requestCapture(viewModel.activeLayerId)
                                            }
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

    // ── PSD export ──────────────────────────────────────────────────────────
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(viewModel.psdExportRequested) {
        if (!viewModel.psdExportRequested) return@LaunchedEffect
        viewModel.clearPsdExportRequest()
        val layerIds = viewModel.layers.map { it.id }
        glView.requestAllLayersCapture(layerIds) { layerPixels, w, h ->
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                            ?: context.filesDir
                        dir.mkdirs()
                        val file = java.io.File(dir, "propaint_export_${System.currentTimeMillis()}.psd")
                        file.outputStream().use { stream ->
                            viewModel.exportPsdFromPixels(layerPixels, w, h, stream)
                        }
                    } catch (_: Exception) { /* silently ignore */ }
                }
            }
        }
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
