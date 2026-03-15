package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas as FoundationCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas  // Canvas(ImageBitmap) 工場関数の呼び出しに必要
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.engine.StrokeRenderer.renderLayers
import com.propaint.app.engine.StrokeRenderer.renderStrokes
import com.propaint.app.model.*
import com.propaint.app.viewmodel.PaintViewModel
import kotlin.math.sqrt

/**
 * Drawing canvas that:
 * - Uses Compose pointerInput (respects z-order, so UI panels block drawing)
 * - Converts screen coordinates → canvas coordinates accounting for zoom/pan
 * - Reads PointerInputChange.pressure for stylus/S Pen pressure
 * - Supports pinch-zoom and two-finger pan
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawingCanvas(
    viewModel: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    var lastPressure by remember { mutableFloatStateOf(0f) }
    var isDrawing by remember { mutableStateOf(false) }

    // We need the canvas size for coordinate conversion
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    var canvasHeight by remember { mutableFloatStateOf(1f) }

    // 水彩ブラシの混色用: ストローク開始前にキャンバス全体をビットマップへレンダリングするスコープ
    val canvasDrawScope = remember { CanvasDrawScope() }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // SAI インスパイア: コミット済みストロークの per-layer フラットビットマップキャッシュ
    // 通常 Map (非 Compose State) — 描画フェーズ内で同期更新し、同一フレームで即反映する
    // (旧 LaunchedEffect 方式は 1 フレーム遅延が発生していたため廃止)
    val layerBitmaps = remember { mutableMapOf<String, ImageBitmap>() }
    val layerStrokeCounts = remember { mutableMapOf<String, Int>() }

    // Photoshop タイル方式インスパイア: ライブストロークを増分ビットマップへベイク
    // 描画フレームごとに全点を再描画する O(N) コストを O(定数テール) へ削減する
    val liveStrokeBitmapHolder = remember { arrayOfNulls<ImageBitmap>(1) }
    val liveRenderedCountHolder = remember { IntArray(1) }

    /** Convert screen position to canvas position (inverse of the draw transform) */
    fun screenToCanvas(screen: Offset): Offset {
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        return Offset(
            (screen.x - viewModel.panOffset.x - cx) / viewModel.zoom + cx,
            (screen.y - viewModel.panOffset.y - cy) / viewModel.zoom + cy,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        FoundationCanvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2A2A2A))
                .pointerInput(Unit) {
                    canvasWidth = size.width.toFloat()
                    canvasHeight = size.height.toFloat()

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val changes = event.changes

                            when {
                                // ── Two+ pointers: pinch zoom / pan ──
                                changes.size >= 2 -> {
                                    // Cancel drawing if in progress
                                    if (isDrawing) {
                                        viewModel.endStroke()
                                        isDrawing = false
                                    }

                                    val p0 = changes[0].position
                                    val p1 = changes[1].position
                                    val p0prev = changes[0].previousPosition
                                    val p1prev = changes[1].previousPosition

                                    // Current and previous distance/center
                                    val curDist = dist(p0, p1)
                                    val prevDist = dist(p0prev, p1prev)
                                    val curCenter = Offset(
                                        (p0.x + p1.x) / 2f,
                                        (p0.y + p1.y) / 2f,
                                    )
                                    val prevCenter = Offset(
                                        (p0prev.x + p1prev.x) / 2f,
                                        (p0prev.y + p1prev.y) / 2f,
                                    )

                                    // Zoom
                                    if (prevDist > 10f) {
                                        val scale = curDist / prevDist
                                        viewModel.updateZoom(viewModel.zoom * scale)
                                    }

                                    // Pan
                                    val panDelta = curCenter - prevCenter
                                    viewModel.updatePanOffset(viewModel.panOffset + panDelta)

                                    // Consume so nothing else handles these pointers
                                    changes.forEach { it.consume() }
                                }

                                // ── Single pointer: draw ──
                                changes.size == 1 -> {
                                    val change = changes[0]
                                    val pressure = change.pressure.coerceIn(0f, 1f)
                                    val canvasPos = screenToCanvas(change.position)

                                    when {
                                        change.changedToDown() -> {
                                            lastPressure = pressure
                                            isDrawing = true

                                            // Detect eraser end of stylus
                                            if (change.type == PointerType.Eraser) {
                                                viewModel.selectBrushType(BrushType.Eraser)
                                            }

                                            // 水彩ブラシ: ストローク開始前に現在のキャンバス状態を
                                            // オフスクリーンビットマップへレンダリングし、混色サンプラーを設定する。
                                            // ビットマップはズーム/パンなしのキャンバス座標系で作成する。
                                            // ストロークポイントもキャンバス座標系なので、そのままサンプリング位置として使える。
                                            if (viewModel.brushSettings.type == BrushType.Watercolor) {
                                                val w = canvasWidth.toInt().coerceAtLeast(1)
                                                val h = canvasHeight.toInt().coerceAtLeast(1)
                                                val bitmap = ImageBitmap(w, h)
                                                canvasDrawScope.draw(
                                                    density = density,
                                                    layoutDirection = layoutDirection,
                                                    canvas = Canvas(bitmap),
                                                    size = Size(w.toFloat(), h.toFloat()),
                                                ) {
                                                    renderLayers(
                                                        layers = viewModel.layers.filter { it.id == viewModel.activeLayerId },
                                                        currentPoints = emptyList(),
                                                        currentBrush = viewModel.brushSettings,
                                                        currentColor = viewModel.currentColor,
                                                        activeLayerId = viewModel.activeLayerId,
                                                        backgroundColor = Color.Transparent,
                                                        showGrid = false,
                                                    )
                                                }
                                                val pixelMap = bitmap.toPixelMap()
                                                viewModel.colorSampler = { pos ->
                                                    pixelMap[
                                                        pos.x.toInt().coerceIn(0, pixelMap.width - 1),
                                                        pos.y.toInt().coerceIn(0, pixelMap.height - 1),
                                                    ]
                                                }
                                            }

                                            viewModel.startStroke(canvasPos, pressure)
                                            change.consume()
                                        }

                                        change.pressed && isDrawing -> {
                                            // Process inter-frame points to eliminate input lag.
                                            // HistoricalChange has no pressure field in Compose 1.6,
                                            // so reuse the current event's pressure (stable within a frame).
                                            for (historical in change.historical) {
                                                val histPos = screenToCanvas(historical.position)
                                                viewModel.addStrokePoint(histPos, pressure)
                                            }
                                            lastPressure = pressure
                                            viewModel.addStrokePoint(canvasPos, pressure)
                                            change.consume()
                                        }

                                        change.changedToUp() -> {
                                            if (isDrawing) {
                                                viewModel.endStroke()
                                                isDrawing = false
                                            }
                                            viewModel.colorSampler = null
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            // strokeVersion を読み取り、ストローク点が追加されるたびに再描画を受け取る
            val _sv = viewModel.strokeVersion

            canvasWidth = size.width
            canvasHeight = size.height

            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)

            // ── 同期レイヤービットマップ更新 (SAI タイル方式) ──
            // キャンバスサイズ変更時は全キャッシュを破棄
            val sizeChanged = layerBitmaps.values.firstOrNull()
                ?.let { it.width != w || it.height != h } ?: false
            if (sizeChanged) {
                layerBitmaps.clear()
                layerStrokeCounts.clear()
                liveStrokeBitmapHolder[0] = null
                liveRenderedCountHolder[0] = 0
            }

            // 削除されたレイヤーのキャッシュを除去
            val activeIds = viewModel.layers.map { it.id }.toSet()
            layerBitmaps.keys.retainAll(activeIds)
            layerStrokeCounts.keys.retainAll(activeIds)

            for (layer in viewModel.layers) {
                val cachedCount = layerStrokeCounts[layer.id] ?: 0
                val strokeCount = layer.strokes.size
                when {
                    strokeCount == 0 -> {
                        layerBitmaps.remove(layer.id)
                        layerStrokeCounts[layer.id] = 0
                    }
                    strokeCount > cachedCount -> {
                        val bitmap = layerBitmaps[layer.id] ?: ImageBitmap(w, h)
                        canvasDrawScope.draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                            renderStrokes(layer.strokes.subList(cachedCount, strokeCount))
                        }
                        layerBitmaps[layer.id] = bitmap
                        layerStrokeCounts[layer.id] = strokeCount
                    }
                    else -> {
                        // strokeCount < cachedCount: undo などによる減少 → 全再描画
                        val bitmap = ImageBitmap(w, h)
                        canvasDrawScope.draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                            renderStrokes(layer.strokes)
                        }
                        layerBitmaps[layer.id] = bitmap
                        layerStrokeCounts[layer.id] = strokeCount
                    }
                }
            }

            // ── ライブストローク増分ベイク (Photoshop タイル方式) ──
            // 末尾 TAIL_SIZE 点は毎フレームライブ描画し saveLayer による正確な合成を維持する。
            // それ以前の確定済み部分はビットマップへ一度だけ描画し再利用する。
            val allPoints = viewModel.currentStrokePoints
            val totalPoints = allPoints.size
            val liveStrokeTail: List<StrokePoint>

            if (!viewModel.isDrawing || totalPoints < 2) {
                liveStrokeBitmapHolder[0] = null
                liveRenderedCountHolder[0] = 0
                liveStrokeTail = emptyList()
            } else {
                val tailSize = 4
                val bakeEnd = totalPoints - tailSize
                val renderedCount = liveRenderedCountHolder[0]
                if (bakeEnd > renderedCount + 1) {
                    val bakeStart = maxOf(0, renderedCount - 1)
                    val subPoints = allPoints.subList(bakeStart, bakeEnd + 1)
                    if (subPoints.size >= 2) {
                        val bitmap = liveStrokeBitmapHolder[0] ?: ImageBitmap(w, h).also { liveStrokeBitmapHolder[0] = it }
                        canvasDrawScope.draw(density, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                            renderStrokes(listOf(Stroke(subPoints, viewModel.brushSettings, viewModel.currentColor, viewModel.activeLayerId)))
                        }
                        liveRenderedCountHolder[0] = bakeEnd
                    }
                }
                val tailStart = maxOf(0, liveRenderedCountHolder[0] - 1)
                liveStrokeTail = if (tailStart < totalPoints) allPoints.subList(tailStart, totalPoints) else allPoints
            }

            val cx = size.width / 2f
            val cy = size.height / 2f

            withTransform({
                translate(viewModel.panOffset.x + cx, viewModel.panOffset.y + cy)
                scale(viewModel.zoom, viewModel.zoom, Offset.Zero)
                translate(-cx, -cy)
            }) {
                renderLayers(
                    layers = viewModel.layers,
                    currentPoints = viewModel.currentStrokePoints,
                    currentBrush = viewModel.brushSettings,
                    currentColor = viewModel.currentColor,
                    activeLayerId = viewModel.activeLayerId,
                    backgroundColor = Color.White,
                    showGrid = viewModel.showGrid,
                    layerBitmaps = layerBitmaps,
                    liveStrokeBitmap = liveStrokeBitmapHolder[0],
                    liveStrokeTail = liveStrokeTail,
                )
            }
        }

        // Pressure indicator
        if (isDrawing) {
            Text(
                text = "筆圧: ${(lastPressure * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        Color(0xFF1E1E1E).copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
