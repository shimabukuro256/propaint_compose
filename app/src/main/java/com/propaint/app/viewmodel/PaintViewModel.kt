package com.propaint.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propaint.app.engine.*
import com.propaint.app.gl.CanvasRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.*

data class UiLayer(
    val id: Int, val name: String, val opacity: Float, val blendMode: Int,
    val isVisible: Boolean, val isLocked: Boolean,
    val isClipToBelow: Boolean, val isActive: Boolean,
)

enum class BrushType(val displayName: String) {
    Pencil("鉛筆"), BinaryPen("バイナリ"), Fude("筆"), Watercolor("水彩筆"), Airbrush("エアブラシ"),
    Marker("マーカー"), Eraser("消しゴム"), Blur("ぼかし"), Fill("塗潰し"),
}

enum class ToolMode {
    Draw, Eyedropper,
    SelectRect, SelectAuto, SelectPen, SelectEraser,
}

class PaintViewModel : ViewModel() {

    private var _document: CanvasDocument? = null
    val document: CanvasDocument? get() = _document
    val renderer = CanvasRenderer()
    private var appContext: Context? = null
    private var autoSaveJob: Job? = null
    private var autoSaveDirty = false

    // ── UI State ────────────────────────────────────────────────────

    private val _layers = MutableStateFlow<List<UiLayer>>(emptyList())
    val layers: StateFlow<List<UiLayer>> = _layers.asStateFlow()

    private val _brushType = MutableStateFlow(BrushType.Pencil)
    val currentBrushType: StateFlow<BrushType> = _brushType.asStateFlow()

    private val _toolMode = MutableStateFlow(ToolMode.Draw)
    val toolMode: StateFlow<ToolMode> = _toolMode.asStateFlow()

    private val _brushSize = MutableStateFlow(6f)
    val brushSize: StateFlow<Float> = _brushSize.asStateFlow()
    private val _brushOpacity = MutableStateFlow(1f)
    val brushOpacity: StateFlow<Float> = _brushOpacity.asStateFlow()
    private val _brushHardness = MutableStateFlow(1f)
    val brushHardness: StateFlow<Float> = _brushHardness.asStateFlow()
    private val _brushDensity = MutableStateFlow(1f)
    val brushDensity: StateFlow<Float> = _brushDensity.asStateFlow()
    private val _brushSpacing = MutableStateFlow(0.15f)
    val brushSpacing: StateFlow<Float> = _brushSpacing.asStateFlow()
    private val _brushStabilizer = MutableStateFlow(0.3f)
    val brushStabilizer: StateFlow<Float> = _brushStabilizer.asStateFlow()
    private val _colorStretch = MutableStateFlow(0.5f)
    val colorStretch: StateFlow<Float> = _colorStretch.asStateFlow()
    private val _waterContent = MutableStateFlow(0f)
    val waterContent: StateFlow<Float> = _waterContent.asStateFlow()
    private val _blurStrength = MutableStateFlow(0.5f)
    val blurStrength: StateFlow<Float> = _blurStrength.asStateFlow()
    private val _pressureSizeEnabled = MutableStateFlow(true)
    val pressureSizeEnabled: StateFlow<Boolean> = _pressureSizeEnabled.asStateFlow()
    private val _pressureOpacityEnabled = MutableStateFlow(false)
    val pressureOpacityEnabled: StateFlow<Boolean> = _pressureOpacityEnabled.asStateFlow()
    private val _pressureSmudgeEnabled = MutableStateFlow(false)
    val pressureSmudgeEnabled: StateFlow<Boolean> = _pressureSmudgeEnabled.asStateFlow()
    private val _filterPressureThreshold = MutableStateFlow(0.3f)
    val filterPressureThreshold: StateFlow<Float> = _filterPressureThreshold.asStateFlow()
    private val _fillTolerance = MutableStateFlow(32f)
    val fillTolerance: StateFlow<Float> = _fillTolerance.asStateFlow()

    // ── 選択 ──
    private val _hasSelection = MutableStateFlow(false)
    val hasSelection: StateFlow<Boolean> = _hasSelection.asStateFlow()
    private val _selectionTolerance = MutableStateFlow(32f)
    val selectionTolerance: StateFlow<Float> = _selectionTolerance.asStateFlow()
    private val _selectionAddMode = MutableStateFlow(false)
    val selectionAddMode: StateFlow<Boolean> = _selectionAddMode.asStateFlow()

    // 矩形選択ドラッグ状態
    private var rectSelStartX = 0f
    private var rectSelStartY = 0f

    private val _currentColor = MutableStateFlow(Color.Black)
    val currentColor: StateFlow<Color> = _currentColor.asStateFlow()
    val colorHistory = MutableStateFlow(listOf(
        Color.Black, Color.White, Color.Red, Color.Blue,
        Color.Green, Color.Yellow, Color(0xFFFF6600), Color(0xFF9900FF),
        Color(0xFF00CCCC), Color(0xFFFF69B4), Color(0xFF8B4513), Color(0xFF808080),
    ))

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private val _isDrawing = MutableStateFlow(false)
    val isDrawing: StateFlow<Boolean> = _isDrawing.asStateFlow()

    // View transform
    private var _zoom = 1f; private var _panX = 0f; private var _panY = 0f; private var _rotation = 0f
    private var gestureStartDist = 0f; private var gestureStartZoom = 1f
    private var gestureStartAngle = 0f; private var gestureStartRotation = 0f
    private var gestureStartPanX = 0f; private var gestureStartPanY = 0f
    private var gestureStartMidX = 0f; private var gestureStartMidY = 0f
    private var isMultiTouch = false
    // Gesture tap detection (2-finger tap → undo, 3-finger tap → redo)
    private var gestureTotalMovement = 0f
    private var gestureMaxPointers = 0
    private var gestureStartTime = 0L
    // Long-press eyedropper (hold 500ms without moving → sample color)
    private var longPressJob: Job? = null
    private var longPressStartX = 0f
    private var longPressStartY = 0f

    // ブラシ設定マップ (種別ごとに全パラメータ保持)
    data class BrushState(
        val size: Float, val opacity: Float, val hardness: Float,
        val density: Float, val spacing: Float, val stabilizer: Float,
        val colorStretch: Float, val waterContent: Float, val blurStrength: Float,
        val pressureSize: Boolean, val pressureOpacity: Boolean,
        val pressureSmudge: Boolean,
        val filterPressureThreshold: Float = 0.3f,
    )
    private val brushStateMap = HashMap<BrushType, BrushState>()

    // ── 初期化 ──────────────────────────────────────────────────────

    /** Context を設定 (自動保存に必要) */
    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }

    fun initCanvas(width: Int, height: Int) {
        val doc = CanvasDocument(width, height)
        _document = doc; renderer.document = doc; updateLayerState()
        startAutoSave()
    }

    /** 既存の CanvasDocument を直接ロード (プロジェクトから開く場合) */
    fun loadDocument(doc: CanvasDocument) {
        _document = doc; renderer.document = doc; updateLayerState(); updateUndoState()
        startAutoSave()
    }

    /** クラッシュ復旧データがあるか確認 */
    fun hasRecoveryData(): Boolean = appContext?.let { AutoSaveManager.hasRecoveryData(it) } ?: false

    /** クラッシュ復旧データのタイムスタンプ */
    fun getRecoveryTimestamp(): Long = appContext?.let { AutoSaveManager.getRecoveryTimestamp(it) } ?: 0

    /** 復旧データからキャンバスを復元 */
    fun recoverCanvas() {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val doc = AutoSaveManager.recover(ctx)
                if (doc != null) {
                    _document = doc
                    renderer.document = doc
                    _statusMessage.value = "キャンバスを復旧しました"
                    launch(Dispatchers.Main) { updateLayerState(); updateUndoState() }
                    startAutoSave()
                } else {
                    _statusMessage.value = "復旧に失敗しました"
                }
            } catch (e: Exception) {
                _statusMessage.value = "復旧エラー: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** 復旧データを破棄 */
    fun discardRecovery() {
        appContext?.let { AutoSaveManager.clearRecoveryData(it) }
    }

    // ── 自動保存 + メモリ監視 ────────────────────────────────────────

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000) // 30秒ごと
                if (autoSaveDirty) {
                    performAutoSave()
                    autoSaveDirty = false
                }
            }
        }
    }

    /** 手動で自動保存をトリガー (ライフサイクルイベント時) */
    fun triggerAutoSave() {
        val doc = _document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            appContext?.let { AutoSaveManager.save(it, doc) }
        }
    }

    /** プロジェクトファイルに保存 */
    fun saveToProject(context: Context, projectId: String) {
        val doc = _document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            CanvasProjectManager.saveProject(context, projectId, doc)
        }
    }

    private fun performAutoSave() {
        val doc = _document ?: return
        val ctx = appContext ?: return
        AutoSaveManager.save(ctx, doc)
    }

    /** ストローク終了後にメモリチェック + auto-save ダーティフラグ設定 */
    private fun onStrokeEnd() {
        autoSaveDirty = true
        val doc = _document ?: return
        // メモリ監視: 圧迫時に undo スタックを縮小
        viewModelScope.launch(Dispatchers.Default) {
            if (MemoryWatcher.checkAndTrim(doc)) {
                launch(Dispatchers.Main) { updateUndoState() }
                // 危機的な場合は即座に auto-save
                val state = MemoryWatcher.getMemoryState()
                if (state.usageRatio > 0.80) {
                    performAutoSave()
                }
            }
        }
    }

    // ── ブラシ操作 ──────────────────────────────────────────────────

    fun setBrushType(type: BrushType) {
        // 現在の設定を保存
        brushStateMap[_brushType.value] = BrushState(
            _brushSize.value, _brushOpacity.value, _brushHardness.value,
            _brushDensity.value, _brushSpacing.value, _brushStabilizer.value,
            _colorStretch.value, _waterContent.value, _blurStrength.value,
            _pressureSizeEnabled.value, _pressureOpacityEnabled.value,
            _pressureSmudgeEnabled.value, _filterPressureThreshold.value,
        )
        _brushType.value = type
        // 保存済みから復元、なければデフォルト
        val saved = brushStateMap[type]
        if (saved != null) {
            _brushSize.value = saved.size; _brushOpacity.value = saved.opacity
            _brushHardness.value = saved.hardness; _brushDensity.value = saved.density
            _brushSpacing.value = saved.spacing; _brushStabilizer.value = saved.stabilizer
            _colorStretch.value = saved.colorStretch; _waterContent.value = saved.waterContent
            _blurStrength.value = saved.blurStrength
            _pressureSizeEnabled.value = saved.pressureSize
            _pressureOpacityEnabled.value = saved.pressureOpacity
            _pressureSmudgeEnabled.value = saved.pressureSmudge
            _filterPressureThreshold.value = saved.filterPressureThreshold
        } else applyDefaults(type)
    }

    /**
     * ブラシ種別ごとの初期値。
     *
     * 方針:
     *  - エアブラシ以外: 硬さ=最大(1.0)
     *  - 濃度=1.0, 不透明度=1.0, 間隔=0.15
     *  - 鉛筆: サイズ筆圧ON, 不透明度筆圧OFF
     *  - 筆/水彩筆: サイズ筆圧OFF, 混色筆圧ON, Direct モード
     *  - エアブラシ: 硬さ=0(ソフト), 不透明度筆圧ON
     */
    private fun applyDefaults(type: BrushType) {
        when (type) {
            BrushType.Pencil -> {
                _brushSize.value = 6f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0f; _waterContent.value = 0f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = false
            }
            BrushType.BinaryPen -> {
                _brushSize.value = 4f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.1f
                _colorStretch.value = 0f; _waterContent.value = 0f
                _pressureSizeEnabled.value = false
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = false
            }
            BrushType.Fude -> {
                _brushSize.value = 14f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0.7f; _waterContent.value = 0f
                _filterPressureThreshold.value = 0.3f
                _pressureSizeEnabled.value = false
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = true
            }
            BrushType.Watercolor -> {
                _brushSize.value = 18f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.08f
                _colorStretch.value = 0.2f; _waterContent.value = 0.7f
                _filterPressureThreshold.value = 0.3f
                _pressureSizeEnabled.value = false
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = true
            }
            BrushType.Airbrush -> {
                _brushSize.value = 30f; _brushOpacity.value = 1f; _brushHardness.value = 0f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0f; _waterContent.value = 0f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = true
                _pressureSmudgeEnabled.value = false
            }
            BrushType.Marker -> {
                _brushSize.value = 12f; _brushOpacity.value = 0.5f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0.3f; _waterContent.value = 0f
                _pressureSizeEnabled.value = false
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = false
            }
            BrushType.Eraser -> {
                _brushSize.value = 20f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0f; _waterContent.value = 0f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = false
            }
            BrushType.Blur -> {
                _brushSize.value = 20f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0f; _waterContent.value = 0f
                _blurStrength.value = 0.5f
                _pressureSizeEnabled.value = true
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = false
            }
            BrushType.Fill -> {
                _brushSize.value = 1f; _brushOpacity.value = 1f; _brushHardness.value = 1f
                _brushDensity.value = 1f; _brushSpacing.value = 0.15f
                _colorStretch.value = 0f; _waterContent.value = 0f
                _pressureSizeEnabled.value = false
                _pressureOpacityEnabled.value = false
                _pressureSmudgeEnabled.value = false
            }
        }
    }

    fun setBrushSize(v: Float) { _brushSize.value = v.coerceIn(1f, 2000f) }
    fun setBrushOpacity(v: Float) { _brushOpacity.value = v.coerceIn(0.01f, 1f) }
    fun setBrushHardness(v: Float) { _brushHardness.value = v.coerceIn(0f, 1f) }
    fun setBrushDensity(v: Float) { _brushDensity.value = v.coerceIn(0.01f, 1f) }
    fun setBrushSpacing(v: Float) { _brushSpacing.value = v.coerceIn(0.01f, 2f) }
    fun setColorStretch(v: Float) { _colorStretch.value = v.coerceIn(0f, 1f) }
    fun setWaterContent(v: Float) { _waterContent.value = v.coerceIn(0f, 1f) }
    fun setBlurStrength(v: Float) { _blurStrength.value = v.coerceIn(0.05f, 1f) }
    fun setFilterPressureThreshold(v: Float) { _filterPressureThreshold.value = v.coerceIn(0f, 1f) }
    fun setFillTolerance(v: Float) { _fillTolerance.value = v.coerceIn(0f, 255f) }
    fun togglePressureSize() { _pressureSizeEnabled.value = !_pressureSizeEnabled.value }
    fun togglePressureOpacity() { _pressureOpacityEnabled.value = !_pressureOpacityEnabled.value }
    fun togglePressureSmudge() { _pressureSmudgeEnabled.value = !_pressureSmudgeEnabled.value }
    fun setColor(color: Color, addToHistory: Boolean = true) {
        _currentColor.value = color
        if (addToHistory) {
            val hist = colorHistory.value.toMutableList()
            hist.remove(color); hist.add(0, color)
            if (hist.size > 20) hist.removeLast()
            colorHistory.value = hist
        }
    }

    fun activateEyedropper() { _toolMode.value = ToolMode.Eyedropper; updateSelectionOverlayMode() }
    fun deactivateEyedropper() { _toolMode.value = ToolMode.Draw; updateSelectionOverlayMode() }

    // ── 選択ツール ──
    fun setToolMode(mode: ToolMode) {
        _toolMode.value = mode
        updateSelectionOverlayMode()
    }
    fun setSelectionTolerance(v: Float) { _selectionTolerance.value = v.coerceIn(0f, 255f) }
    fun toggleSelectionAddMode() { _selectionAddMode.value = !_selectionAddMode.value }

    fun selectAll() { _document?.selectAll(); updateSelectionState() }
    fun clearSelection() { _document?.clearSelection(); updateSelectionState() }
    fun invertSelection() { _document?.invertSelection(); updateSelectionState() }

    private fun updateSelectionState() {
        _hasSelection.value = _document?.hasSelection == true
        updateSelectionOverlayMode()
    }

    /** ToolMode と hasSelection に基づいて GPU オーバーレイモードを更新 */
    private fun updateSelectionOverlayMode() {
        val mode = _toolMode.value
        val hasSel = _hasSelection.value
        renderer.selectionOverlayMode = when {
            !hasSel -> CanvasRenderer.OVERLAY_NONE
            mode == ToolMode.SelectRect || mode == ToolMode.SelectAuto ||
            mode == ToolMode.SelectPen || mode == ToolMode.SelectEraser -> CanvasRenderer.OVERLAY_BLUE
            mode == ToolMode.Draw || mode == ToolMode.Eyedropper -> CanvasRenderer.OVERLAY_MARCHING_ANTS
            else -> CanvasRenderer.OVERLAY_NONE
        }
    }

    /** 塗りつぶし実行 */
    private fun fillAtPoint(doc: CanvasDocument, x: Int, y: Int) {
        val c = _currentColor.value
        val argb = (0xFF shl 24) or ((c.red * 255).toInt() shl 16) or
            ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        val fillColor = PixelOps.premultiply(argb)
        doc.pushUndoForFill()
        val layer = doc.layers.find { it.id == doc.activeLayerId } ?: return
        FillTool.floodFill(
            layer.content, x, y, fillColor,
            _fillTolerance.value.toInt().coerceIn(0, 255),
            doc.dirtyTracker,
        )
        doc.dirtyTracker.markFullRebuild()
        updateUndoState()
        onStrokeEnd()
    }

    /**
     * BrushConfig を構築。
     *
     * モード方針:
     *  - Pencil: Indirect (Wash) モード → サブレイヤー合成で均一不透明度
     *  - Marker: Direct モード + BLEND_MARKER (アルファ比較) + smudge (色混合)
     *  - Fude, Watercolor: Direct + BLEND_MIX (RGB+alpha 混色)
     *  - Airbrush, Eraser, Blur: Direct モード
     *
     * 筆/水彩筆/ぼかしは不透明度プロパティを使用しない (Direct mode で density が制御)。
     * マーカーは不透明度がアルファ上限を制御する。
     */
    private fun buildBrushConfig(): BrushConfig {
        val c = _currentColor.value
        val argb = (0xFF shl 24) or ((c.red * 255).toInt() shl 16) or
            ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        val type = _brushType.value
        val isMixBrush = type == BrushType.Fude || type == BrushType.Watercolor
        val isMarker = type == BrushType.Marker
        val isBinary = type == BrushType.BinaryPen
        return BrushConfig(
            size = _brushSize.value,
            opacity = _brushOpacity.value,
            density = _brushDensity.value,
            spacing = _brushSpacing.value,
            hardness = if (isBinary) 1f else _brushHardness.value,
            colorPremul = PixelOps.premultiply(argb),
            isEraser = type == BrushType.Eraser,
            isMarker = isMarker,
            isBlur = type == BrushType.Blur,
            isBinaryPen = isBinary,
            smudge = when {
                isMixBrush || isMarker -> _colorStretch.value
                else -> 0f
            },
            resmudge = 0,
            blurStrength = _blurStrength.value,
            // Pencil / BinaryPen: Indirect (均一不透明度)
            // Marker: Indirect (サブレイヤーで継ぎ目なしストローク)
            indirect = type == BrushType.Pencil || type == BrushType.Marker || isBinary,
            pressureSizeEnabled = _pressureSizeEnabled.value,
            pressureOpacityEnabled = _pressureOpacityEnabled.value,
            pressureSmudgeEnabled = _pressureSmudgeEnabled.value,
            pressureSizeIntensity = when (type) {
                BrushType.Pencil -> 100
                BrushType.Airbrush -> 80
                BrushType.Eraser -> 90
                else -> 100
            },
            pressureOpacityIntensity = when (type) {
                BrushType.Airbrush -> 70
                else -> 100
            },
            pressureSmudgeIntensity = when (type) {
                BrushType.Fude -> 80
                BrushType.Watercolor -> 60
                else -> 100
            },
            taperEnabled = type != BrushType.Eraser && type != BrushType.Marker && !isBinary,
            waterContent = _waterContent.value,
            colorStretch = _colorStretch.value,
            filterPressureThreshold = if (isMixBrush) _filterPressureThreshold.value else 0f,
            antiAliasing = if (isBinary) 0f else 1f,
        )
    }

    // ── タッチ入力 ──────────────────────────────────────────────────

    fun onTouchEvent(event: MotionEvent): Boolean {
        val doc = _document ?: return false

        if (event.pointerCount >= 2) { handleMultiTouch(event); return true }

        if (isMultiTouch && event.actionMasked == MotionEvent.ACTION_MOVE) return true
        if (isMultiTouch && event.actionMasked == MotionEvent.ACTION_UP) { isMultiTouch = false; return true }

        // 消しゴム端の自動検出
        val isEraserTip = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        val mode = _toolMode.value

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isMultiTouch = false
                longPressJob?.cancel()
                // Start long-press eyedropper timer for Draw mode
                if (mode == ToolMode.Draw && _brushType.value != BrushType.Fill) {
                    longPressStartX = event.x
                    longPressStartY = event.y
                    longPressJob = viewModelScope.launch {
                        delay(500)
                        // Long press confirmed — sample color at touch point
                        val (dx, dy) = screenToDoc(longPressStartX, longPressStartY)
                        val sampled = doc.eyedropperAt(dx.toInt(), dy.toInt())
                        val up = PixelOps.unpremultiply(sampled)
                        setColor(Color(PixelOps.red(up) / 255f, PixelOps.green(up) / 255f, PixelOps.blue(up) / 255f))
                        // Cancel in-progress stroke
                        if (_isDrawing.value) {
                            doc.undo() // Undo the partial stroke
                            _isDrawing.value = false
                        }
                    }
                }
                if (mode == ToolMode.Eyedropper) {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    val sampled = doc.eyedropperAt(dx.toInt(), dy.toInt())
                    val up = PixelOps.unpremultiply(sampled)
                    setColor(Color(PixelOps.red(up) / 255f, PixelOps.green(up) / 255f, PixelOps.blue(up) / 255f), addToHistory = false)
                    _toolMode.value = ToolMode.Draw
                    return true
                }
                // 自動選択: シングルタップで実行
                if (mode == ToolMode.SelectAuto) {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    doc.autoSelect(dx.toInt(), dy.toInt(),
                        _selectionTolerance.value.toInt().coerceIn(0, 255),
                        _selectionAddMode.value)
                    updateSelectionState()
                    return true
                }
                // 矩形選択: ドラッグ開始
                if (mode == ToolMode.SelectRect) {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    rectSelStartX = dx; rectSelStartY = dy
                    _isDrawing.value = true
                    return true
                }
                // 選択ペン / 選択消しゴム: ブラシでマスク描画
                if (mode == ToolMode.SelectPen || mode == ToolMode.SelectEraser) {
                    val mask = doc.getOrCreateSelectionMask()
                    _isDrawing.value = true
                    processSelectionDraw(event, doc, mask, mode == ToolMode.SelectEraser)
                    return true
                }
                // 塗りつぶしツール: シングルタップで実行
                if (_brushType.value == BrushType.Fill) {
                    val (dx, dy) = screenToDoc(event.x, event.y)
                    fillAtPoint(doc, dx.toInt(), dy.toInt())
                    return true
                }
                var brush = buildBrushConfig()
                if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false)
                doc.beginStroke(brush)
                _isDrawing.value = true
                processDrawPoints(event, doc, brush)
            }
            MotionEvent.ACTION_MOVE -> {
                // Cancel long-press if finger moved significantly
                if (longPressJob?.isActive == true) {
                    val moveDist = sqrt(
                        (event.x - longPressStartX) * (event.x - longPressStartX) +
                        (event.y - longPressStartY) * (event.y - longPressStartY)
                    )
                    if (moveDist > 15f) longPressJob?.cancel()
                }
                if (_isDrawing.value) {
                    if (mode == ToolMode.SelectRect) {
                        // 矩形選択ドラッグ中 — 何もしない (UP で確定)
                    } else if (mode == ToolMode.SelectPen || mode == ToolMode.SelectEraser) {
                        val mask = doc.getOrCreateSelectionMask()
                        processSelectionDraw(event, doc, mask, mode == ToolMode.SelectEraser)
                    } else {
                        var brush = buildBrushConfig()
                        if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false)
                        processDrawPoints(event, doc, brush)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                if (_isDrawing.value) {
                    if (mode == ToolMode.SelectRect) {
                        // 矩形選択確定
                        val (dx, dy) = screenToDoc(event.x, event.y)
                        doc.selectRect(
                            rectSelStartX.toInt(), rectSelStartY.toInt(),
                            dx.toInt(), dy.toInt(),
                            _selectionAddMode.value)
                        updateSelectionState()
                    } else if (mode == ToolMode.SelectPen || mode == ToolMode.SelectEraser) {
                        // 消去モードでマスクが空になった可能性があるため再計算
                        doc.selectionMask?.recomputeHasSelection()
                        doc.publishSelectionSnapshot()
                        updateSelectionState()
                    } else {
                        var brush = buildBrushConfig()
                        if (isEraserTip) brush = brush.copy(isEraser = true, indirect = false)
                        doc.endStroke(brush)
                        updateUndoState()
                        onStrokeEnd()
                    }
                    _isDrawing.value = false
                }
            }
        }
        return true
    }

    /** 選択ペン / 選択消しゴムでマスクにダブを描画 */
    private fun processSelectionDraw(
        event: MotionEvent, doc: CanvasDocument,
        mask: com.propaint.app.engine.SelectionMask, isErase: Boolean,
    ) {
        val size = _brushSize.value
        val hardness = _brushHardness.value
        for (h in 0 until event.historySize) {
            val (dx, dy) = screenToDoc(event.getHistoricalX(h), event.getHistoricalY(h))
            val dab = com.propaint.app.engine.DabMaskGenerator.createDab(dx, dy, size, hardness)
            if (dab != null) mask.applyDab(dab, isErase, 255)
        }
        val (dx, dy) = screenToDoc(event.x, event.y)
        val dab = com.propaint.app.engine.DabMaskGenerator.createDab(dx, dy, size, hardness)
        if (dab != null) mask.applyDab(dab, isErase, 255)
        // 選択マスクのみ更新 (コンポジットキャッシュの再構築は不要)
        doc.publishSelectionSnapshot()
    }

    private fun processDrawPoints(event: MotionEvent, doc: CanvasDocument, brush: BrushConfig) {
        for (h in 0 until event.historySize) {
            val (dx, dy) = screenToDoc(event.getHistoricalX(h), event.getHistoricalY(h))
            doc.strokeTo(BrushEngine.StrokePoint(dx, dy, event.getHistoricalPressure(h)), brush)
        }
        val (dx, dy) = screenToDoc(event.x, event.y)
        doc.strokeTo(BrushEngine.StrokePoint(dx, dy, event.pressure), brush)
    }

    private fun screenToDoc(sx: Float, sy: Float): Pair<Float, Float> {
        val doc = _document ?: return sx to sy
        val sw = renderer.surfaceWidth.toFloat(); val sh = renderer.surfaceHeight.toFloat()
        if (sw <= 0 || sh <= 0) return sx to sy
        val bs = min(sw / doc.width, sh / doc.height); val fs = bs * _zoom
        val cx = sw / 2f + _panX; val cy = sh / 2f + _panY
        val dx = sx - cx; val dy = sy - cy
        val cosR = cos(-_rotation.toDouble()).toFloat(); val sinR = sin(-_rotation.toDouble()).toFloat()
        return Pair((dx * cosR - dy * sinR) / fs + doc.width / 2f,
            (dx * sinR + dy * cosR) / fs + doc.height / 2f)
    }

    private fun handleMultiTouch(event: MotionEvent) {
        longPressJob?.cancel() // Cancel long-press eyedropper on multi-touch
        if (!isMultiTouch && _isDrawing.value) {
            _document?.endStroke(buildBrushConfig()); _isDrawing.value = false
        }
        val x0 = event.getX(0); val y0 = event.getY(0)
        val x1 = event.getX(1); val y1 = event.getY(1)
        val midX = (x0 + x1) / 2f; val midY = (y0 + y1) / 2f
        val dist = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isMultiTouch) {
                    // First multi-touch start
                    gestureTotalMovement = 0f
                    gestureMaxPointers = event.pointerCount
                    gestureStartTime = System.currentTimeMillis()
                } else {
                    // Additional finger added
                    if (event.pointerCount > gestureMaxPointers) {
                        gestureMaxPointers = event.pointerCount
                    }
                }
                isMultiTouch = true; gestureStartDist = dist; gestureStartZoom = _zoom
                gestureStartAngle = angle; gestureStartRotation = _rotation
                gestureStartPanX = _panX; gestureStartPanY = _panY
                gestureStartMidX = midX; gestureStartMidY = midY
            }
            MotionEvent.ACTION_MOVE -> {
                if (isMultiTouch && gestureStartDist > 10f) {
                    val moveDelta = sqrt(
                        (midX - gestureStartMidX) * (midX - gestureStartMidX) +
                        (midY - gestureStartMidY) * (midY - gestureStartMidY)
                    )
                    gestureTotalMovement += moveDelta
                    _zoom = (gestureStartZoom * dist / gestureStartDist).coerceIn(0.1f, 30f)
                    _rotation = gestureStartRotation + (angle - gestureStartAngle)
                    _panX = gestureStartPanX + (midX - gestureStartMidX)
                    _panY = gestureStartPanY + (midY - gestureStartMidY)
                    renderer.pendingTransform.set(floatArrayOf(_zoom, _panX, _panY, _rotation))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Check for tap gesture when last extra finger lifts
                if (event.pointerCount == 2) {
                    val elapsed = System.currentTimeMillis() - gestureStartTime
                    // Tap: short duration (<300ms) and minimal movement (<30px)
                    if (elapsed < 300 && gestureTotalMovement < 30f) {
                        when (gestureMaxPointers) {
                            2 -> undo()
                            3 -> redo()
                        }
                    }
                }
            }
        }
    }

    // ── レイヤー操作 ────────────────────────────────────────────────

    fun addLayer() {
        val doc = _document ?: return
        doc.addLayer("レイヤー ${doc.layers.size + 1}"); updateLayerState()
    }
    fun removeLayer(id: Int) { _document?.removeLayer(id); updateLayerState() }
    fun selectLayer(id: Int) { _document?.setActiveLayer(id); updateLayerState() }
    fun setLayerVisibility(id: Int, v: Boolean) { _document?.setLayerVisibility(id, v); updateLayerState() }
    fun setLayerOpacity(id: Int, v: Float) { _document?.setLayerOpacity(id, v); updateLayerState() }
    fun setLayerBlendMode(id: Int, m: Int) { _document?.setLayerBlendMode(id, m); updateLayerState() }
    fun setLayerClip(id: Int, clip: Boolean) { _document?.setLayerClipToBelow(id, clip); updateLayerState() }
    fun setLayerLocked(id: Int, locked: Boolean) { _document?.setLayerLocked(id, locked); updateLayerState() }
    fun clearActiveLayer() { _document?.let { it.clearLayer(it.activeLayerId) }; updateLayerState() }
    fun duplicateLayer(id: Int) { _document?.duplicateLayer(id); updateLayerState() }
    fun mergeDown(id: Int) { _document?.mergeDown(id); updateLayerState() }
    fun flipLayerHorizontal(id: Int) { _document?.flipLayerHorizontal(id); updateLayerState() }
    fun flipLayerVertical(id: Int) { _document?.flipLayerVertical(id); updateLayerState() }

    /** レイヤーを1つ上に移動 (合成順で上 = リスト末尾方向) */
    fun moveLayerUp(id: Int) {
        val doc = _document ?: return
        val idx = doc.layers.indexOfFirst { it.id == id }
        if (idx < 0 || idx >= doc.layers.size - 1) return
        doc.moveLayer(idx, idx + 1)
        updateLayerState()
    }

    /** レイヤーを1つ下に移動 (合成順で下 = リスト先頭方向) */
    fun moveLayerDown(id: Int) {
        val doc = _document ?: return
        val idx = doc.layers.indexOfFirst { it.id == id }
        if (idx <= 0) return
        doc.moveLayer(idx, idx - 1)
        updateLayerState()
    }

    // ── フィルター ──────────────────────────────────────────────────

    fun applyHslFilter(hue: Float, sat: Float, lit: Float) {
        val doc = _document ?: return
        doc.applyHslFilter(doc.activeLayerId, hue, sat, lit); updateUndoState()
    }

    fun applyBrightnessContrast(brightness: Float, contrast: Float) {
        val doc = _document ?: return
        doc.applyBrightnessContrast(doc.activeLayerId, brightness, contrast); updateUndoState()
    }

    fun applyBlurFilter(radius: Int) {
        val doc = _document ?: return
        doc.applyBlurFilter(doc.activeLayerId, radius); updateUndoState()
    }

    // ── Undo/Redo ───────────────────────────────────────────────────

    fun undo() { _document?.undo(); updateUndoState() }
    fun redo() { _document?.redo(); updateUndoState() }

    // ── View ────────────────────────────────────────────────────────

    fun resetView() {
        _zoom = 1f; _panX = 0f; _panY = 0f; _rotation = 0f
        renderer.pendingTransform.set(floatArrayOf(_zoom, _panX, _panY, _rotation))
    }

    // ── ファイル操作 ──────────────────────────────────────────────

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun clearStatus() { _statusMessage.value = null }

    /** .propaint 形式で保存 */
    fun savePropaint(outputStream: OutputStream) {
        val doc = _document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                FileManager.savePropaint(doc, outputStream)
                _statusMessage.value = "保存しました"
            } catch (e: Exception) {
                _statusMessage.value = "保存エラー: ${e.message}"
            } finally {
                outputStream.close()
                _isBusy.value = false
            }
        }
    }

    /** .propaint 形式から読み込み */
    fun loadPropaint(inputStream: InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val doc = FileManager.loadPropaint(inputStream)
                if (doc != null) {
                    _document = doc
                    renderer.document = doc
                    _statusMessage.value = "読み込みました"
                    launch(Dispatchers.Main) { updateLayerState(); updateUndoState() }
                    startAutoSave()
                } else {
                    _statusMessage.value = "ファイル形式が無効です"
                }
            } catch (e: Exception) {
                _statusMessage.value = "読み込みエラー: ${e.message}"
            } finally {
                inputStream.close()
                _isBusy.value = false
            }
        }
    }

    /** PSD 形式でエクスポート */
    fun exportPsd(outputStream: OutputStream) {
        val doc = _document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                FileManager.exportPsd(doc, outputStream)
                _statusMessage.value = "PSD エクスポート完了"
            } catch (e: Exception) {
                _statusMessage.value = "PSD エラー: ${e.message}"
            } finally {
                outputStream.close()
                _isBusy.value = false
            }
        }
    }

    /** 画像エクスポート (PNG/JPEG/WebP) */
    fun exportImage(outputStream: OutputStream, format: FileManager.ImageFormat, quality: Int = 95) {
        val doc = _document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                FileManager.exportImage(doc, outputStream, format, quality)
                _statusMessage.value = "エクスポート完了"
            } catch (e: Exception) {
                _statusMessage.value = "エクスポートエラー: ${e.message}"
            } finally {
                outputStream.close()
                _isBusy.value = false
            }
        }
    }

    /** 画像インポート (新しいレイヤーとして追加) */
    fun importImage(inputStream: InputStream) {
        val doc = _document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val layer = FileManager.importImage(doc, inputStream)
                if (layer != null) {
                    _statusMessage.value = "インポートしました"
                    launch(Dispatchers.Main) { updateLayerState() }
                } else {
                    _statusMessage.value = "画像の読み込みに失敗しました"
                }
            } catch (e: Exception) {
                _statusMessage.value = "インポートエラー: ${e.message}"
            } finally {
                inputStream.close()
                _isBusy.value = false
            }
        }
    }

    /** 新しいキャンバス */
    fun newCanvas(width: Int, height: Int) {
        val doc = CanvasDocument(width, height)
        _document = doc; renderer.document = doc; updateLayerState(); updateUndoState()
        startAutoSave()
    }

    /** レガシー PNG エクスポート (後方互換) */
    fun exportPng(outputStream: OutputStream) {
        exportImage(outputStream, FileManager.ImageFormat.PNG)
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun updateLayerState() {
        val doc = _document ?: return
        _layers.value = doc.layers.map {
            UiLayer(it.id, it.name, it.opacity, it.blendMode,
                it.isVisible, it.isLocked, it.isClipToBelow, it.id == doc.activeLayerId)
        }
    }

    private fun updateUndoState() {
        val doc = _document ?: return
        _canUndo.value = doc.canUndo; _canRedo.value = doc.canRedo
    }
}
