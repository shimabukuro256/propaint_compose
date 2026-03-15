package com.propaint.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.ViewModel
import com.propaint.app.model.*

class PaintViewModel : ViewModel() {

    // ── Brush ──
    var brushSettings by mutableStateOf(BrushSettings())
        private set
    var currentColor by mutableStateOf(Color.Black)
        private set
    val colorHistory = mutableStateListOf(
        Color.Black, Color.White, Color.Red, Color.Blue,
        Color.Green, Color.Yellow, Color(0xFFFF6600), Color(0xFF9900FF),
    )

    // ── Layers ──
    val layers = mutableStateListOf(PaintLayer(name = "レイヤー 1"))
    var activeLayerId by mutableStateOf(layers.first().id)
        private set

    val activeLayer: PaintLayer?
        get() = layers.find { it.id == activeLayerId }

    // ── Drawing state ──
    // 通常の ArrayList を使用し Compose スナップショット追跡コストを排除する。
    // 読み取り側は strokeVersion を observe して再描画トリガーとする。
    private val _currentStrokePoints = ArrayList<StrokePoint>(256)
    val currentStrokePoints: List<StrokePoint> get() = _currentStrokePoints
    var strokeVersion by mutableIntStateOf(0)
        private set
    var isDrawing by mutableStateOf(false)
        private set

    // ── Canvas view ──
    var zoom by mutableFloatStateOf(1f)
        private set
    var panOffset by mutableStateOf(Offset.Zero)
        private set

    // ── Options ──
    var showGrid by mutableStateOf(false)
        private set
    var symmetryEnabled by mutableStateOf(false)
        private set

    // ── Watercolor color sampler ──
    // DrawingCanvas がストローク開始前にオフスクリーンビットマップをキャプチャし、
    // このラムダをセットする。呼び出し元はキャンバス座標を渡すと既存ピクセル色を返す。
    var colorSampler: ((Offset) -> Color)? = null

    // ── History ──
    private val undoStack = mutableListOf<CanvasAction>()
    private val redoStack = mutableListOf<CanvasAction>()
    private val maxHistory = 50

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ── Brush ──

    fun setBrush(settings: BrushSettings) {
        brushSettings = settings
    }

    fun selectBrushType(type: BrushType) {
        brushSettings = BrushSettings(type = type)
    }

    fun setBrushSize(size: Float) {
        brushSettings = brushSettings.copy(size = size.coerceIn(1f, 200f))
    }

    fun setBrushOpacity(opacity: Float) {
        brushSettings = brushSettings.copy(opacity = opacity.coerceIn(0.01f, 1f))
    }

    fun setBrushDensity(density: Float) {
        brushSettings = brushSettings.copy(density = density.coerceIn(0.01f, 1f))
    }

    fun setBrushSpacing(spacing: Float) {
        brushSettings = brushSettings.copy(spacing = spacing.coerceIn(0.1f, 5.0f))
    }

    fun togglePressureSize() {
        brushSettings = brushSettings.copy(pressureSizeEnabled = !brushSettings.pressureSizeEnabled)
    }

    fun togglePressureOpacity() {
        brushSettings = brushSettings.copy(pressureOpacityEnabled = !brushSettings.pressureOpacityEnabled)
    }

    fun togglePressureBlend() {
        brushSettings = brushSettings.copy(pressureBlendEnabled = !brushSettings.pressureBlendEnabled)
    }

    // ── Color ──

    fun setColor(color: Color) {
        currentColor = color
        if (color !in colorHistory) {
            colorHistory.add(0, color)
            if (colorHistory.size > 20) colorHistory.removeAt(colorHistory.lastIndex)
        }
    }

    // ── Drawing ──

    fun startStroke(position: Offset, pressure: Float = 1f) {
        val layer = activeLayer ?: return
        if (layer.isLocked) return
        isDrawing = true
        _currentStrokePoints.clear()
        _currentStrokePoints.add(StrokePoint(position, pressure, watercolorMix(position, pressure)))
        strokeVersion++
    }

    fun addStrokePoint(position: Offset, pressure: Float = 1f) {
        if (!isDrawing) return
        if (_currentStrokePoints.isNotEmpty()) {
            val last = _currentStrokePoints.last()
            val dist = (position - last.position).getDistance()
            if (dist < 1f) return
            // Smoothing
            val s = brushSettings.type.smoothing * 0.5f
            val smoothed = Offset(
                last.position.x + (position.x - last.position.x) * (1f - s),
                last.position.y + (position.y - last.position.y) * (1f - s),
            )
            _currentStrokePoints.add(StrokePoint(smoothed, pressure, watercolorMix(smoothed, pressure)))
            strokeVersion++
        }
    }

    /**
     * 水彩ブラシ専用: キャンバスの既存色を [colorSampler] でサンプリングし、
     * [currentColor] と線形補間した混色結果を返す。
     * 非水彩ブラシや sampler 未設定時は null を返す（stroke.color をそのまま使用）。
     *
     * blendStrength = 描画色の支配度 (0=全サンプリング色, 1=全描画色)
     *  - pressureBlendEnabled OFF: 固定 0.5 (SAI デフォルトに近い 50/50 混色)
     *  - pressureBlendEnabled ON : 筆圧に比例 (軽=キャンバス色優位, 強=描画色優位)
     */
    private fun watercolorMix(position: Offset, pressure: Float): Color? {
        if (brushSettings.type != BrushType.Watercolor) return null
        val sampled = colorSampler?.invoke(position) ?: return null
        val blendStrength = if (brushSettings.pressureBlendEnabled)
            (0.15f + 0.85f * pressure).coerceIn(0f, 1f)
        else 0.5f
        // RGB: キャンバス色と描画色を blendStrength で線形補間
        val newMix = lerp(sampled, currentColor, blendStrength)
        // Alpha: 同じアルゴリズム — キャンバスの不透明度とブラシ濃度を同比率で混色
        // density = スタンプ 1 回の塗り量上限（opacity は saveLayer 側で管理）
        val mixedAlpha = sampled.alpha + (brushSettings.density - sampled.alpha) * blendStrength
        val newMixWithAlpha = newMix.copy(alpha = mixedAlpha)
        // 時系列スムージング: 前ポイントの混色結果と補間し、急激な色変化を抑える
        val prevColor = _currentStrokePoints.lastOrNull()?.color ?: newMixWithAlpha
        return lerp(prevColor, newMixWithAlpha, 0.35f)
    }

    fun endStroke() {
        if (!isDrawing) return
        isDrawing = false

        if (_currentStrokePoints.size >= 2) {
            val stroke = Stroke(
                points = _currentStrokePoints.toList(),
                brush = brushSettings.copy(),
                color = currentColor,
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
        layers.add(idx + 1, newLayer)
        activeLayerId = newLayer.id
        pushUndo(CanvasAction.AddLayer(newLayer))
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
            id = java.util.UUID.randomUUID().toString(),
            name = "${source.name} コピー",
        )
        layers.add(idx + 1, copy)
        activeLayerId = copy.id
    }

    fun mergeDown(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx <= 0) return
        val upper = layers[idx]
        val lower = layers[idx - 1]
        layers[idx - 1] = lower.copy(strokes = lower.strokes + upper.strokes)
        layers.removeAt(idx)
        activeLayerId = layers[idx - 1].id
    }

    // ── View ──

    fun updateZoom(z: Float) { zoom = z.coerceIn(0.1f, 10f) }
    fun updatePanOffset(offset: Offset) { panOffset = offset }
    fun resetView() { zoom = 1f; panOffset = Offset.Zero }
    fun toggleGrid() { showGrid = !showGrid }
    fun toggleSymmetry() { symmetryEnabled = !symmetryEnabled }

    // ── Undo / Redo ──

    private fun pushUndo(action: CanvasAction) {
        undoStack.add(action)
        if (undoStack.size > maxHistory) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        when (action) {
            is CanvasAction.AddStroke -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) {
                    val l = layers[idx]
                    if (l.strokes.isNotEmpty()) {
                        layers[idx] = l.copy(strokes = l.strokes.dropLast(1))
                    }
                }
            }
            is CanvasAction.AddLayer -> {
                layers.removeAll { it.id == action.layer.id }
                if (activeLayerId == action.layer.id && layers.isNotEmpty()) {
                    activeLayerId = layers.last().id
                }
            }
            is CanvasAction.RemoveLayer -> {
                layers.add(minOf(action.index, layers.size), action.layer)
            }
            is CanvasAction.ClearLayer -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(strokes = action.previousStrokes)
            }
        }
        redoStack.add(action)
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        when (action) {
            is CanvasAction.AddStroke -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) {
                    layers[idx] = layers[idx].copy(strokes = layers[idx].strokes + action.stroke)
                }
            }
            is CanvasAction.AddLayer -> {
                layers.add(action.layer)
                activeLayerId = action.layer.id
            }
            is CanvasAction.RemoveLayer -> {
                layers.removeAll { it.id == action.layer.id }
            }
            is CanvasAction.ClearLayer -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(strokes = emptyList())
            }
        }
        undoStack.add(action)
    }
}
