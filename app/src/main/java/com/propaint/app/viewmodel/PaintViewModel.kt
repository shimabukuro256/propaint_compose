package com.propaint.app.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.AndroidViewModel
import com.propaint.app.model.*
import java.io.File

class PaintViewModel(application: Application) : AndroidViewModel(application) {

    private val historyCache = HistoryDiskCache(File(application.cacheDir, "paint_history"))

    // ── Brush ──
    var brushSettings by mutableStateOf(BrushSettings())
        private set
    var currentColor by mutableStateOf(Color.Black)
        private set
    val colorHistory = mutableStateListOf(
        Color.Black, Color.White, Color.Red, Color.Blue,
        Color.Green, Color.Yellow, Color(0xFFFF6600), Color(0xFF9900FF),
    )

    private val brushSettingsMap = HashMap<BrushType, BrushSettings>()

    // ── Layers ──
    val layers = mutableStateListOf(PaintLayer(name = "レイヤー 1"))
    var activeLayerId by mutableStateOf(layers.first().id)
        private set

    val activeLayer: PaintLayer?
        get() = layers.find { it.id == activeLayerId }

    // ── Drawing state ──
    private val _currentStrokePoints = ArrayList<StrokePoint>(256)
    val currentStrokePoints: List<StrokePoint> get() = _currentStrokePoints
    var strokeVersion by mutableIntStateOf(0)
        private set
    var isDrawing by mutableStateOf(false)
        private set

    // ── Canvas pixel capture (for Fude / Watercolor / Marker / Blur) ──
    private var capturedPixels: ByteArray? = null
    private var capturedW: Int = 0
    private var capturedH: Int = 0

    fun setPixelCapture(pixels: ByteArray, w: Int, h: Int) {
        capturedPixels = pixels; capturedW = w; capturedH = h
    }

    fun clearPixelCapture() { capturedPixels = null }

    // ── Canvas view ──
    data class ViewTransform(
        val zoom: Float = 1f,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val rotation: Float = 0f,
    )
    var viewTransform by mutableStateOf(ViewTransform())
        private set
    val zoom: Float get() = viewTransform.zoom
    val panOffset: Offset get() = Offset(viewTransform.panX, viewTransform.panY)
    val rotation: Float get() = viewTransform.rotation

    // ── Options ──
    var showGrid by mutableStateOf(false)
        private set
    var symmetryEnabled by mutableStateOf(false)
        private set

    // ── History ──
    private val undoStack = HybridHistoryStack(historyCache, maxMemory = 10, maxTotal = 50)
    private val redoStack = HybridHistoryStack(
        HistoryDiskCache(File(getApplication<Application>().cacheDir, "paint_history_redo")),
        maxMemory = 10, maxTotal = 50,
    )

    val canUndo get() = undoStack.isNotEmpty
    val canRedo get() = redoStack.isNotEmpty

    // ── Brush ──

    fun setBrush(settings: BrushSettings) {
        brushSettings = settings
        brushSettingsMap[settings.type] = settings
    }

    fun selectBrushType(type: BrushType) {
        brushSettingsMap[brushSettings.type] = brushSettings
        brushSettings = brushSettingsMap[type] ?: BrushSettings(type = type)
    }

    fun setBrushSize(size: Float) { setBrush(brushSettings.copy(size = size.coerceIn(1f, 200f))) }
    fun setBrushOpacity(opacity: Float) { setBrush(brushSettings.copy(opacity = opacity.coerceIn(0.01f, 1f))) }
    fun setBrushDensity(density: Float) { setBrush(brushSettings.copy(density = density.coerceIn(0.01f, 1f))) }
    fun setBrushSpacing(spacing: Float) { setBrush(brushSettings.copy(spacing = spacing.coerceIn(0.01f, 2.0f))) }
    fun setBrushHardness(hardness: Float) { setBrush(brushSettings.copy(hardness = hardness.coerceIn(0f, 1f))) }
    fun setBrushStabilizer(stabilizer: Float) { setBrush(brushSettings.copy(stabilizer = stabilizer.coerceIn(0f, 1f))) }
    fun setBrushBlurStrength(v: Float) { setBrush(brushSettings.copy(blurStrength = v.coerceIn(0.05f, 1f))) }
    fun togglePressureSize() { setBrush(brushSettings.copy(pressureSizeEnabled = !brushSettings.pressureSizeEnabled)) }
    fun togglePressureOpacity() { setBrush(brushSettings.copy(pressureOpacityEnabled = !brushSettings.pressureOpacityEnabled)) }

    // ── Color ──

    fun setColor(color: Color) {
        currentColor = color
        if (color !in colorHistory) {
            colorHistory.add(0, color)
            if (colorHistory.size > 20) colorHistory.removeAt(colorHistory.lastIndex)
        }
    }

    // ── Pixel sampling helpers ──

    /** キャンバス座標 (Y上向き=画面上端) から GL ピクセルバッファをサンプル */
    private fun samplePixelAt(position: Offset): Color? {
        val pixels = capturedPixels ?: return null
        val w = capturedW; val h = capturedH
        val px = position.x.toInt().coerceIn(0, w - 1)
        val py = (h - 1 - position.y.toInt()).coerceIn(0, h - 1)
        val off = (py * w + px) * 4
        return Color(
            red   = (pixels[off    ].toInt() and 0xFF) / 255f,
            green = (pixels[off + 1].toInt() and 0xFF) / 255f,
            blue  = (pixels[off + 2].toInt() and 0xFF) / 255f,
            alpha = (pixels[off + 3].toInt() and 0xFF) / 255f,
        )
    }

    /** ボックスブラー: position 周辺 radius ピクセルを平均 */
    private fun sampleBoxBlurAt(position: Offset, radius: Int): Color? {
        val pixels = capturedPixels ?: return null
        val w = capturedW; val h = capturedH
        var r = 0f; var g = 0f; var b = 0f; var a = 0f; var count = 0
        val cx = position.x.toInt(); val cy = position.y.toInt()
        for (sy in (cy - radius)..(cy + radius)) {
            for (sx in (cx - radius)..(cx + radius)) {
                val px = sx.coerceIn(0, w - 1)
                val py = (h - 1 - sy).coerceIn(0, h - 1)
                val off = (py * w + px) * 4
                r += (pixels[off    ].toInt() and 0xFF) / 255f
                g += (pixels[off + 1].toInt() and 0xFF) / 255f
                b += (pixels[off + 2].toInt() and 0xFF) / 255f
                a += (pixels[off + 3].toInt() and 0xFF) / 255f
                count++
            }
        }
        if (count == 0) return null
        return Color(r / count, g / count, b / count, a / count)
    }

    /**
     * ブラシ種別に応じたキャンバス混色計算。
     * - Fude / Marker : サンプル1点 → density 割合でブラシ色と線形補間
     * - Watercolor    : ボックスブラー → density 割合でブラシ色と線形補間
     * - Blur          : ボックスブラーのみ (ブラシ色なし)
     */
    private fun canvasMix(position: Offset, pressure: Float): Color? {
        return when (brushSettings.type) {
            BrushType.Fude, BrushType.Marker -> {
                val sampled = samplePixelAt(position) ?: return null
                val strength = brushSettings.density.coerceIn(0.1f, 0.9f)
                lerp(sampled, currentColor, strength)
            }
            BrushType.Watercolor -> {
                val blurred = sampleBoxBlurAt(position, 3) ?: return null
                val strength = (brushSettings.density * 0.7f + 0.1f).coerceIn(0.05f, 0.95f)
                lerp(blurred, currentColor, strength)
            }
            BrushType.Blur -> {
                val radius = (brushSettings.blurStrength * 8f).toInt().coerceIn(1, 8)
                sampleBoxBlurAt(position, radius)
            }
            else -> null
        }
    }

    // ── Drawing ──

    fun startStroke(position: Offset, pressure: Float = 1f) {
        val layer = activeLayer ?: return
        if (layer.isLocked) return
        isDrawing = true
        _currentStrokePoints.clear()
        _currentStrokePoints.add(StrokePoint(position, pressure, canvasMix(position, pressure)))
        strokeVersion++
    }

    fun addStrokePoint(position: Offset, pressure: Float = 1f) {
        if (!isDrawing) return
        if (_currentStrokePoints.isNotEmpty()) {
            val last = _currentStrokePoints.last()
            val dist = (position - last.position).getDistance()
            if (dist < 1f) return
            val s = brushSettings.stabilizer * 0.5f
            val smoothed = Offset(
                last.position.x + (position.x - last.position.x) * (1f - s),
                last.position.y + (position.y - last.position.y) * (1f - s),
            )
            _currentStrokePoints.add(StrokePoint(smoothed, pressure, canvasMix(smoothed, pressure)))
            strokeVersion++
        }
    }

    fun endStroke() {
        if (!isDrawing) return
        isDrawing = false

        if (_currentStrokePoints.size >= 2) {
            val stroke = Stroke(
                points  = _currentStrokePoints.toList(),
                brush   = brushSettings.copy(),
                color   = currentColor,
                layerId = activeLayerId,
            )
            val idx = layers.indexOfFirst { it.id == activeLayerId }
            if (idx >= 0) {
                val layer = layers[idx]
                layers[idx] = layer.copy(strokes = layer.strokes + stroke)
                pushUndo(CanvasAction.AddStroke(stroke, activeLayerId))
            }
        }
        _currentStrokePoints.clear()
        strokeVersion++
    }

    // ── Layers ──

    fun addLayer() {
        val newLayer = PaintLayer(name = "レイヤー ${layers.size + 1}")
        val idx = layers.indexOfFirst { it.id == activeLayerId }
        val insertAt = idx + 1
        layers.add(insertAt, newLayer)
        activeLayerId = newLayer.id
        pushUndo(CanvasAction.AddLayer(newLayer, insertAt))
    }

    fun removeLayer(layerId: String) {
        if (layers.size <= 1) return
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return
        val removed = layers.removeAt(idx)
        if (activeLayerId == layerId) {
            activeLayerId = layers[maxOf(0, idx - 1)].id
        }
        pushUndo(CanvasAction.RemoveLayer(removed, idx))
    }

    fun selectLayer(layerId: String) { activeLayerId = layerId }

    fun toggleLayerVisibility(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) layers[idx] = layers[idx].copy(isVisible = !layers[idx].isVisible)
    }

    fun toggleLayerLock(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) layers[idx] = layers[idx].copy(isLocked = !layers[idx].isLocked)
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) layers[idx] = layers[idx].copy(opacity = opacity.coerceIn(0f, 1f))
    }

    fun setLayerBlendMode(layerId: String, mode: LayerBlendMode) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) layers[idx] = layers[idx].copy(blendMode = mode)
    }

    fun clearLayer(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) {
            val prev = layers[idx].strokes
            layers[idx] = layers[idx].copy(strokes = emptyList())
            pushUndo(CanvasAction.ClearLayer(layerId, prev))
        }
    }

    fun duplicateLayer(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return
        val source = layers[idx]
        val copy = source.copy(
            id   = java.util.UUID.randomUUID().toString(),
            name = "${source.name} コピー",
        )
        val insertAt = idx + 1
        layers.add(insertAt, copy)
        activeLayerId = copy.id
        pushUndo(CanvasAction.DuplicateLayer(copy, insertAt))
    }

    fun mergeDown(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx <= 0) return
        val upper = layers[idx]
        val lower = layers[idx - 1]
        layers[idx - 1] = lower.copy(strokes = lower.strokes + upper.strokes)
        layers.removeAt(idx)
        activeLayerId = layers[idx - 1].id
        pushUndo(CanvasAction.MergeDown(upper, lower, idx))
    }

    // ── View ──

    fun updateViewTransform(zoom: Float, pan: Offset, rotation: Float = viewTransform.rotation) {
        viewTransform = ViewTransform(zoom.coerceIn(0.1f, 10f), pan.x, pan.y, rotation)
    }
    fun updateZoom(z: Float) { viewTransform = viewTransform.copy(zoom = z.coerceIn(0.1f, 10f)) }
    fun updatePanOffset(offset: Offset) { viewTransform = viewTransform.copy(panX = offset.x, panY = offset.y) }
    fun resetView() { viewTransform = ViewTransform() }
    fun toggleGrid() { showGrid = !showGrid }
    fun toggleSymmetry() { symmetryEnabled = !symmetryEnabled }

    // ── Undo / Redo ──

    private fun pushUndo(action: CanvasAction) {
        undoStack.push(action)
        redoStack.clear()
    }

    fun undo() {
        val action = undoStack.pop() ?: return
        applyUndo(action)
        redoStack.push(action)
    }

    fun redo() {
        val action = redoStack.pop() ?: return
        applyRedo(action)
        undoStack.push(action)
    }

    private fun applyUndo(action: CanvasAction) {
        when (action) {
            is CanvasAction.AddStroke -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) {
                    val l = layers[idx]
                    if (l.strokes.isNotEmpty()) layers[idx] = l.copy(strokes = l.strokes.dropLast(1))
                }
            }
            is CanvasAction.AddLayer -> {
                val idx = layers.indexOfFirst { it.id == action.layer.id }
                if (idx >= 0) {
                    layers.removeAt(idx)
                    if (activeLayerId == action.layer.id && layers.isNotEmpty())
                        activeLayerId = layers[maxOf(0, idx - 1)].id
                }
            }
            is CanvasAction.RemoveLayer -> {
                layers.add(minOf(action.index, layers.size), action.layer)
            }
            is CanvasAction.ClearLayer -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(strokes = action.previousStrokes)
            }
            is CanvasAction.MergeDown -> {
                // merged に upper の strokes が付いているので分割
                val mergedIdx = layers.indexOfFirst { it.id == action.lower.id }
                if (mergedIdx >= 0) {
                    layers[mergedIdx] = action.lower
                    layers.add(mergedIdx + 1, action.upper)
                    activeLayerId = action.upper.id
                }
            }
            is CanvasAction.DuplicateLayer -> {
                val idx = layers.indexOfFirst { it.id == action.newLayer.id }
                if (idx >= 0) {
                    layers.removeAt(idx)
                    if (activeLayerId == action.newLayer.id && layers.isNotEmpty())
                        activeLayerId = layers[maxOf(0, idx - 1)].id
                }
            }
        }
    }

    private fun applyRedo(action: CanvasAction) {
        when (action) {
            is CanvasAction.AddStroke -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(strokes = layers[idx].strokes + action.stroke)
            }
            is CanvasAction.AddLayer -> {
                layers.add(minOf(action.atIndex, layers.size), action.layer)
                activeLayerId = action.layer.id
            }
            is CanvasAction.RemoveLayer -> {
                val idx = layers.indexOfFirst { it.id == action.layer.id }
                if (idx >= 0) layers.removeAt(idx)
            }
            is CanvasAction.ClearLayer -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(strokes = emptyList())
            }
            is CanvasAction.MergeDown -> {
                val lowerIdx = layers.indexOfFirst { it.id == action.lower.id }
                val upperIdx = layers.indexOfFirst { it.id == action.upper.id }
                if (lowerIdx >= 0 && upperIdx >= 0) {
                    layers[lowerIdx] = action.lower.copy(strokes = action.lower.strokes + action.upper.strokes)
                    layers.removeAt(upperIdx)
                    activeLayerId = layers[lowerIdx].id
                }
            }
            is CanvasAction.DuplicateLayer -> {
                layers.add(minOf(action.atIndex, layers.size), action.newLayer)
                activeLayerId = action.newLayer.id
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        undoStack.clear()
        redoStack.clear()
    }
}