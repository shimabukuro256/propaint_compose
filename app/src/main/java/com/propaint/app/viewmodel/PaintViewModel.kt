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
import com.propaint.app.gl.PsdExporter
import com.propaint.app.gl.PsdLayerData
import com.propaint.app.io.CanvasData
import com.propaint.app.model.*
import java.io.File
import java.io.OutputStream
import kotlin.math.pow
import kotlin.math.sqrt

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

    // ── Eyedropper ──
    var isEyedropperActive by mutableStateOf(false)
        private set

    // ── Canvas identity ──
    var canvasId by mutableStateOf(java.util.UUID.randomUUID().toString())
        private set
    var canvasTitle by mutableStateOf("無題")
        private set
    var canvasDocWidth by mutableIntStateOf(0)
        private set
    var canvasDocHeight by mutableIntStateOf(0)
        private set

    // ── Export request ──
    enum class ExportType { PNG, JPEG, WEBP, PSD, PPAINT }
    var exportRequest: ExportType? by mutableStateOf(null)
        private set

    // ── Auto-save (ギャラリーへ戻る前にトリガー) ──
    var saveCanvasRequest by mutableIntStateOf(0)
        private set
    fun requestSaveCanvas() { saveCanvasRequest++ }

    // ── Layer pixel upload (for loading saved canvases) ──
    var pendingPixelUploads: List<Pair<String, ByteArray>>? = null
        private set
    var pendingPixelWidth = 0
        private set
    var pendingPixelHeight = 0
        private set
    var pixelUploadVersion by mutableIntStateOf(0)
        private set

    // ── Filter preview ──
    var filterPreview: LayerFilter? by mutableStateOf(null)
        private set

    // ── Canvas pixel capture (for Fude / Watercolor / Marker / Blur) ──
    private var capturedPixels: ByteArray? = null
    private var capturedW: Int = 0
    private var capturedH: Int = 0

    // ── 走行インク (Fude / Watercolor / Marker) ──
    // ストローク開始時に null にリセット。ストローク内でキャンバス色を吸収しながら進化する。
    private var strokeInkColor: Color? = null

    // ── 混色サンプリングスロットル (Fude / Watercolor / Blur) ──
    // spacing=1 等の過密スタンプでも直前の自分のピクセルを読み返すフィードバックを防ぐ。
    // ブラシ直径に比例した距離ごとのみサンプリングを更新し、中間はキャッシュを返す。
    private var lastMixPosition: Offset? = null
    private var lastMixColor: Color? = null

    // ── ストローク方向追跡 (折り返し検出) ──
    private var lastMoveDir: Offset = Offset.Zero

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

    fun setBrushSize(size: Float) { setBrush(brushSettings.copy(size = size.coerceIn(1f, 2000f))) }
    fun setBrushOpacity(opacity: Float) { setBrush(brushSettings.copy(opacity = opacity.coerceIn(0.01f, 1f))) }
    fun setBrushDensity(density: Float) { setBrush(brushSettings.copy(density = density.coerceIn(0.01f, 1f))) }
    fun setBrushSpacing(spacing: Float) { setBrush(brushSettings.copy(spacing = spacing.coerceIn(0.01f, 2.0f))) }
    fun setBrushHardness(hardness: Float) { setBrush(brushSettings.copy(hardness = hardness.coerceIn(0f, 1f))) }
    fun setBrushStabilizer(stabilizer: Float) { setBrush(brushSettings.copy(stabilizer = stabilizer.coerceIn(0f, 1f))) }
    fun setBrushBlurStrength(v: Float) { setBrush(brushSettings.copy(blurStrength = v.coerceIn(0.05f, 1f))) }
    fun setBrushColorStretch(v: Float) { setBrush(brushSettings.copy(colorStretch = v.coerceIn(0f, 1f))) }
    fun togglePressureSize() { setBrush(brushSettings.copy(pressureSizeEnabled = !brushSettings.pressureSizeEnabled)) }
    fun togglePressureOpacity() { setBrush(brushSettings.copy(pressureOpacityEnabled = !brushSettings.pressureOpacityEnabled)) }
    fun setPressureSizeIntensity(v: Int) { setBrush(brushSettings.copy(pressureSizeIntensity = v.coerceIn(1, 200))) }
    fun setPressureOpacityIntensity(v: Int) { setBrush(brushSettings.copy(pressureOpacityIntensity = v.coerceIn(1, 200))) }
    fun togglePressureMix() { setBrush(brushSettings.copy(pressureMixEnabled = !brushSettings.pressureMixEnabled)) }
    fun setPressureMixIntensity(v: Int) { setBrush(brushSettings.copy(pressureMixIntensity = v.coerceIn(1, 200))) }
    fun setBrushWaterContent(v: Float) { setBrush(brushSettings.copy(waterContent = v.coerceIn(0f, 1f))) }
    fun setBrushWatercolorBlurStrength(v: Int) { setBrush(brushSettings.copy(watercolorBlurStrength = v.coerceIn(1, 100))) }
    fun setBrushAntiAliasing(v: Float) { setBrush(brushSettings.copy(antiAliasing = v.coerceIn(0f, 4f))) }

    // ── Color ──

    fun setColor(color: Color) {
        currentColor = color
        if (color !in colorHistory) {
            colorHistory.add(0, color)
            if (colorHistory.size > 20) colorHistory.removeAt(colorHistory.lastIndex)
        }
    }

    // ── Eyedropper ──

    fun activateEyedropper() { isEyedropperActive = true }
    fun deactivateEyedropper() { isEyedropperActive = false }

    fun requestExport(type: ExportType) { exportRequest = type }
    fun clearExportRequest() { exportRequest = null }

    // Keep backward compat for callers still using requestPsdExport
    fun requestPsdExport() { requestExport(ExportType.PSD) }

    // Called when loading a saved canvas
    fun loadFromCanvasData(data: CanvasData) {
        layers.clear()
        layers.addAll(data.layers)
        activeLayerId = data.activeLayerId
        canvasId = data.id
        canvasTitle = data.title
        canvasDocWidth  = data.width
        canvasDocHeight = data.height
        undoStack.clear()
        redoStack.clear()
        filterPreview = null
        pendingPixelUploads = data.layerPixels
        pendingPixelWidth   = data.width
        pendingPixelHeight  = data.height
        pixelUploadVersion++
        strokeVersion++
    }

    // Called when creating a new empty canvas
    fun newCanvas(title: String = "無題") {
        layers.clear()
        layers.add(PaintLayer(name = "レイヤー 1"))
        activeLayerId = layers.first().id
        canvasId = java.util.UUID.randomUUID().toString()
        canvasTitle = title
        canvasDocWidth = 0
        canvasDocHeight = 0
        undoStack.clear()
        redoStack.clear()
        filterPreview = null
        pendingPixelUploads = null
        pixelUploadVersion++
        strokeVersion++
    }

    fun setCanvasTitleDirectly(title: String) { canvasTitle = title }

    /**
     * 外部からインポートした Bitmap を新規キャンバスとして読み込む。
     * pixels は GL 座標系 (y=0 が下端、プリマルチプライドなし) に変換して渡す。
     */
    fun importBitmap(bitmap: android.graphics.Bitmap, title: String) {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        // Android ARGB → GL RGBA (y-flip, straight alpha)
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px  = src[y * w + x]
                val glY = h - 1 - y
                val off = (glY * w + x) * 4
                bytes[off    ] = ((px shr 16) and 0xFF).toByte()
                bytes[off + 1] = ((px shr  8) and 0xFF).toByte()
                bytes[off + 2] = (px and 0xFF).toByte()
                bytes[off + 3] = ((px shr 24) and 0xFF).toByte()
            }
        }
        layers.clear()
        val newLayer = PaintLayer(name = "レイヤー 1")
        layers.add(newLayer)
        activeLayerId      = newLayer.id
        canvasId           = java.util.UUID.randomUUID().toString()
        canvasTitle        = title
        canvasDocWidth     = w
        canvasDocHeight    = h
        undoStack.clear()
        redoStack.clear()
        filterPreview      = null
        pendingPixelUploads  = listOf(Pair(newLayer.id, bytes))
        pendingPixelWidth    = w
        pendingPixelHeight   = h
        pixelUploadVersion++
        strokeVersion++
    }

    // Called after pixels are uploaded, to acknowledge completion
    fun clearPendingPixelUploads() {
        pendingPixelUploads = null
    }

    // ── Filter ──

    @JvmName("updateFilterPreview")
    fun setFilterPreview(filter: LayerFilter?) { filterPreview = filter }

    fun applyFilter() {
        val filter = filterPreview ?: return
        val layerId = activeLayerId
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return
        val prev = layers[idx].filter
        layers[idx] = layers[idx].copy(filter = filter)
        pushUndo(CanvasAction.SetLayerFilter(layerId, filter, prev))
        filterPreview = null
    }

    fun cancelFilter() { filterPreview = null }

    /**
     * キャプチャされたコンポジットピクセルから色をサンプリングしてカレントカラーに設定する。
     * pixels: GL 座標系 RGBA バイト列 (y=0 が下端、プリマルチプライド)。
     */
    fun pickColorFromPixels(pixels: ByteArray, x: Int, y: Int, w: Int, h: Int) {
        val px = x.coerceIn(0, w - 1)
        val py = (h - 1 - y).coerceIn(0, h - 1)  // GL座標 → 画面座標変換
        val off = (py * w + px) * 4
        if (off + 3 >= pixels.size) return
        val a = (pixels[off + 3].toInt() and 0xFF) / 255f
        val color = if (a < 0.001f) {
            Color.White  // 透明ピクセルは白
        } else {
            val invA = 1f / a
            Color(
                red   = ((pixels[off    ].toInt() and 0xFF) / 255f * invA).coerceIn(0f, 1f),
                green = ((pixels[off + 1].toInt() and 0xFF) / 255f * invA).coerceIn(0f, 1f),
                blue  = ((pixels[off + 2].toInt() and 0xFF) / 255f * invA).coerceIn(0f, 1f),
                alpha = 1f,
            )
        }
        setColor(color)
    }

    // ── Pixel sampling helpers ──

    /**
     * キャンバス座標 (Y上向き=画面上端) から GL ピクセルバッファをサンプル。
     * FBO はプリマルチプライド RGBA で格納されているためアンプリマルチプライドして返す。
     * 透明ピクセル (alpha≈0) は色情報がないため null を返す。
     */
    private fun samplePixelAt(position: Offset): Color? {
        val pixels = capturedPixels ?: return null
        val w = capturedW; val h = capturedH
        val px = position.x.toInt().coerceIn(0, w - 1)
        val py = (h - 1 - position.y.toInt()).coerceIn(0, h - 1)
        val off = (py * w + px) * 4
        val a = (pixels[off + 3].toInt() and 0xFF) / 255f
        if (a < 0.001f) return null  // 透明ピクセルは色情報なし
        val invA = 1f / a
        return Color(
            red   = ((pixels[off    ].toInt() and 0xFF) / 255f * invA).coerceIn(0f, 1f),
            green = ((pixels[off + 1].toInt() and 0xFF) / 255f * invA).coerceIn(0f, 1f),
            blue  = ((pixels[off + 2].toInt() and 0xFF) / 255f * invA).coerceIn(0f, 1f),
            alpha = a,
        )
    }

    /**
     * 重み付きブラー: position 周辺 radius px を距離重み (線形コーン) でサンプリングする。
     *
     * 仕様書 Step 1 — サンプリング (周囲の色を拾う):
     *   カーソルの中心に近いほど強く、外側ほど弱く色を拾うことで境界を自然にぼかす。
     *
     * weight(d) = max(0,  1 − d / radius)   ← 中心=1.0, 境界=0.0 の円錐フィルタ
     *
     * ピクセルバッファはプリマルチプライド RGBA。重み付き和を取った後にアンプリマルチプライドで返す。
     * 有効な重み合計が極小 or 全ピクセルが透明な場合は null を返す。
     */
    private fun sampleWeightedBlurAt(position: Offset, radius: Int): Color? {
        val pixels = capturedPixels ?: return null
        val w = capturedW; val h = capturedH
        var rSum = 0f; var gSum = 0f; var bSum = 0f; var aSum = 0f; var wSum = 0f
        val cx = position.x; val cy = position.y
        val rF = radius.toFloat()
        for (sy in (cy.toInt() - radius)..(cy.toInt() + radius)) {
            for (sx in (cx.toInt() - radius)..(cx.toInt() + radius)) {
                val dx = sx - cx; val dy = sy - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > rF) continue                          // 円形カーネル
                val weight = 1f - dist / rF                      // 線形コーン重み
                val px = sx.coerceIn(0, w - 1)
                val py = (h - 1 - sy).coerceIn(0, h - 1)        // GL Y 反転
                val off = (py * w + px) * 4
                rSum += (pixels[off    ].toInt() and 0xFF) / 255f * weight
                gSum += (pixels[off + 1].toInt() and 0xFF) / 255f * weight
                bSum += (pixels[off + 2].toInt() and 0xFF) / 255f * weight
                aSum += (pixels[off + 3].toInt() and 0xFF) / 255f * weight
                wSum += weight
            }
        }
        if (wSum < 0.001f) return null
        val avgA = aSum / wSum
        if (avgA < 0.001f) return null
        // プリマルチプライドの重み付き和をアンプリマルチプライド
        val invA = 1f / avgA
        return Color(
            red   = (rSum / wSum * invA).coerceIn(0f, 1f),
            green = (gSum / wSum * invA).coerceIn(0f, 1f),
            blue  = (bSum / wSum * invA).coerceIn(0f, 1f),
            alpha = avgA,
        )
    }

    /**
     * ぼかし筆圧モード用サンプリング (Fude / Watercolor 共通)。
     *
     * 仕様書 Step 1+2+3 をまとめて実装:
     *   Step 1: 重み付きサンプリングで周囲の色を収集 (中心ほど高重み)
     *   Step 2: 新しい色 = キャンバス色 × 水分量 (ぼかしモードは水分量=1)
     *   Step 3: ガウス拡散 — 重み付きサンプリングが拡散を内包する
     *
     * canvasAlpha を alpha に格納して返す (GPU 側で透明域でのスタンプ抑制に使用)。
     */
    private fun canvasBlurMix(position: Offset, blurRadius: Int): Color? {
        return sampleWeightedBlurAt(position, blurRadius)
    }

    /**
     * ブラシ種別に応じたキャンバス混色計算。StrokePoint.color として保存され GPU 描画に使われる。
     *
     * ─── デジタル水彩の 3 ステップ (仕様書より) ───────────────────────────────
     *   Step 1 サンプリング: sampleWeightedBlurAt で距離重み付き平均 (中心 > 外側)
     *   Step 2 重み付き平均: 新しい色 = (描画色 × 筆の強さ) + (背景色 × 水分量)
     *                        水分量 = colorStretch パラメータに対応
     *   Step 3 拡散処理:     Step 1 の重み付きサンプリング半径が拡散を担う
     *
     * ─── ブラシ別の走行インク (strokeInkColor) ────────────────────────────────
     *   Fude      : キャンバスを微弱吸収しつつブラシ色を強く補充 → 長いインク伸び
     *   Watercolor: キャンバスを吸収せずブラシ色へ収束 → スタンプ毎にローカル混色
     *   Marker    : キャンバスと混色しつつ走行インクを引きずる
     *
     * ─── 戻り値の alpha チャンネル ────────────────────────────────────────────
     *   Fude/Watercolor 通常モード: canvasAlpha (GPU 側での透明域抑制・ぼかしモード判定に使用)
     *   Fude/Watercolor ぼかしモード: canvasAlpha (透明域でスタンプを非表示にする)
     *   Blur: sampleWeightedBlurAt の自然な avgA
     */
    private fun canvasMix(position: Offset, pressure: Float): Color? {
        // ── サンプリングスロットル (Fude / Watercolor / Blur) ─────────────────
        // spacing=1 等の過密スタンプでも「直前に書いた自分のピクセル」を
        // 毎スタンプ読み返すフィードバックを防ぐ。
        // ブラシ直径の MIX_STEP_FACTOR 倍の距離ごとのみサンプリングを更新し、
        // 中間スタンプはキャッシュを返す → spacing=1 でも spacing=10 と同等の混色間隔。
        val doThrottle = brushSettings.type == BrushType.Fude ||
                         brushSettings.type == BrushType.Watercolor ||
                         brushSettings.type == BrushType.Blur
        if (doThrottle) {
            val mixStep = (brushSettings.size * MIX_STEP_FACTOR).coerceIn(MIN_MIX_STEP, MAX_MIX_STEP)
            if (lastMixPosition != null && (position - lastMixPosition!!).getDistance() < mixStep) {
                return lastMixColor
            }
        }

        val result = when (brushSettings.type) {

            // ── Fude: 微弱キャンバス吸収 + 強いブラシ色補充 → 引きずり感のある筆 ──
            BrushType.Fude -> {
                // 水分量: 低筆圧ほど有効水分量が高まる (仕様書: 筆圧低→水分量高)
                val effectiveWater = (brushSettings.waterContent * (1f + (1f - pressure) * 0.5f)).coerceIn(0f, 1f)
                // ぼかし筆圧モード: blurPressureThreshold が明示的に設定された場合のみ発動。
                // waterBlurBoost を廃止 (CPU/GPU の blurThreshold 不整合を防ぐ)。
                if (brushSettings.blurPressureThreshold > 0f && pressure < brushSettings.blurPressureThreshold) {
                    val r = (brushSettings.size * 0.20f).toInt().coerceIn(2, 18)
                    canvasBlurMix(position, r)
                } else {
                    // 通常モード
                    // 1点サンプリングではなく重み付きブラーで周辺色を読む → 色の拡散を改善
                    val diffuseR = (brushSettings.size * 0.20f).toInt().coerceIn(2, 15)
                    val canvasRgb = sampleWeightedBlurAt(position, diffuseR)
                    // 重み付き平均アルファを使用 (単一ピクセルより滑らか)
                    val canvasAlpha = canvasRgb?.alpha ?: 0f
                    val ink = strokeInkColor ?: currentColor.copy(alpha = 1f)
                    val p  = pressure.coerceIn(0f, 1f)
                    val ps = p * p * (3f - 2f * p)
                    val mixScale = if (brushSettings.pressureMixEnabled)
                        pressureCurveWithIntensity(pressure, brushSettings.pressureMixIntensity) else 1f
                    // Step 2: 水分量が高いほどキャンバス吸収が増加し、描画色が薄まる
                    val absorptionWaterScale = 1f + effectiveWater * 2f
                    val absorption = (brushSettings.colorStretch * mixScale * canvasAlpha * ps * FUDE_ABSORB_SCALE * absorptionWaterScale)
                        .coerceIn(0f, 0.6f)
                    val absorbed = if (canvasRgb != null)
                        lerp(ink, canvasRgb.copy(alpha = 1f), absorption) else ink
                    val refreshWaterScale = (1f - effectiveWater * 0.7f).coerceAtLeast(0.05f)
                    val refreshRate = (brushSettings.density * mixScale * FUDE_REFRESH_RATE * ps * refreshWaterScale)
                        .coerceIn(0f, 0.9f)
                    val newInk = lerp(absorbed, currentColor.copy(alpha = 1f), refreshRate)
                    strokeInkColor = newInk.copy(alpha = 1f)
                    newInk.copy(alpha = canvasAlpha)
                }
            }

            // ── Watercolor: 走行インクはブラシ色へ収束、スタンプ毎にキャンバスと混色 ──
            BrushType.Watercolor -> {
                val effectiveWater = (brushSettings.waterContent * (1f + (1f - pressure) * 0.5f)).coerceIn(0f, 1f)
                // ぼかし強度倍率: 1=デフォルト半径, 100=10倍の半径 (線形補間)
                val blurMult = 1f + (brushSettings.watercolorBlurStrength - 1) / 99f * 9f
                // waterBlurBoost を廃止 (CPU/GPU 不整合解消)。blurPressureThreshold が0のとき
                // blur モードに入らず、キャンバス上に何もない場合でも正しくブラシ色が描画される。
                if (brushSettings.blurPressureThreshold > 0f && pressure < brushSettings.blurPressureThreshold) {
                    val r = (brushSettings.size * 0.30f * blurMult).toInt().coerceIn(3, 150)
                    canvasBlurMix(position, r)
                } else {
                    val p  = pressure.coerceIn(0f, 1f)
                    val ps = p * p * (3f - 2f * p)
                    val mixScale = if (brushSettings.pressureMixEnabled)
                        pressureCurveWithIntensity(pressure, brushSettings.pressureMixIntensity) else 1f
                    // Step 1: サンプリング半径を拡大 (拡散改善) + 水分量でさらに広がる + ぼかし強度倍率
                    val samplingRadius = (brushSettings.size * 0.25f * (1f + effectiveWater * 0.5f) * blurMult).toInt().coerceIn(3, 150)
                    val canvasRgb = sampleWeightedBlurAt(position, samplingRadius)
                    // 重み付き平均アルファを使用 (単一ピクセルより滑らか)
                    val canvasAlpha = canvasRgb?.alpha ?: 0f
                    val refreshWaterScale = (1f - effectiveWater * 0.7f).coerceAtLeast(0.05f)
                    val refreshRate = (brushSettings.density * mixScale * WATERCOLOR_REFRESH_RATE * ps * refreshWaterScale)
                        .coerceIn(0f, 0.9f)
                    val ink = strokeInkColor ?: currentColor.copy(alpha = 1f)
                    val newInk = lerp(ink, currentColor.copy(alpha = 1f), refreshRate)
                    strokeInkColor = newInk.copy(alpha = 1f)
                    // Step 2: 水分量パラメータが高いほどキャンバス色が強く混ざる
                    val waterMixScale = 1f + effectiveWater * 1.5f
                    val waterAmount = (brushSettings.colorStretch * mixScale * canvasAlpha * ps * WATERCOLOR_MIX_SCALE * waterMixScale)
                        .coerceIn(0f, 0.85f)
                    val stampRgb = if (canvasRgb != null)
                        lerp(newInk, canvasRgb.copy(alpha = 1f), waterAmount) else newInk
                    stampRgb.copy(alpha = canvasAlpha)
                }
            }

            // ── Marker: GL_MAX アルファ + 走行インク ─────────────────────────
            BrushType.Marker -> {
                val sampled = samplePixelAt(position)
                val drag    = brushSettings.colorStretch
                if (sampled != null) {
                    // 筆ツール混色に近いバランスで既存色とインクを混ぜる (density=1.0 で約60%インク)
                    val strength = (brushSettings.density * 0.45f + 0.15f).coerceIn(0.1f, 0.65f)
                    val freshMix = lerp(sampled, currentColor, strength)
                    val carried  = strokeInkColor
                    val dragged  = if (carried != null && drag > 0f)
                        lerp(freshMix, carried, drag) else freshMix
                    strokeInkColor = dragged.copy(alpha = 1f)
                    dragged
                } else {
                    val carried = strokeInkColor
                    val result  = if (carried != null && drag > 0f)
                        lerp(currentColor, carried, drag) else currentColor
                    strokeInkColor = result.copy(alpha = 1f)
                    result
                }
            }

            // ── Blur: Step 1+3 のみ (重み付きサンプリング = にじみ拡散) ────────
            BrushType.Blur -> {
                val radius = ((brushSettings.size / 2f) * brushSettings.blurStrength).toInt().coerceIn(2, 50)
                sampleWeightedBlurAt(position, radius)
            }

            else -> null
        }

        if (doThrottle) {
            lastMixPosition = position
            lastMixColor = result
        }
        return result
    }

    companion object {
        // ── 筆 (Fude) ──────────────────────────────────────────────────────
        /**
         * キャンバス吸収スケール (仕様書 Step 2 の「水分量」制御)。
         * 吸収率 = colorStretch × canvasAlpha × ps × ABSORB_SCALE  (上限 0.5)
         * colorStretch=0.5, canvasAlpha=0.7, ps=1.0 → 吸収 ≈ 8.75%/stamp
         * → ink が少しずつキャンバス色に近づき「引きずり感」を生む。
         */
        private const val FUDE_ABSORB_SCALE  = 0.25f
        /**
         * ブラシ色補充率 (仕様書 Step 2 の「筆の強さ」)。density=70%, ps=1 で ≈ 21%/stamp 補充。
         * 吸収の後に補充することでブラシ色が長く維持される。
         */
        private const val FUDE_REFRESH_RATE  = 0.30f

        // ── 水彩筆 (Watercolor) ─────────────────────────────────────────────
        /**
         * 走行インクのブラシ色収束率 (仕様書 Step 2 の「筆の強さ」)。
         * density=100%, ps=1 で ≈ 20%/stamp。ink はキャンバスを吸収せず収束のみ。
         */
        private const val WATERCOLOR_REFRESH_RATE = 0.20f
        /**
         * スタンプ時点の水分量スケール (仕様書 Step 2 の「キャンバスの水分量」)。
         * waterAmount = colorStretch × canvasAlpha × ps × MIX_SCALE  (上限 0.7)
         * colorStretch=0.5, canvasAlpha=0.7 → ≈ 17.5%/stamp キャンバス色が混入。
         */
        private const val WATERCOLOR_MIX_SCALE    = 0.50f

        // ── 折り返し検出 ─────────────────────────────────────────────────────
        /**
         * 折り返し判定のコサイン閾値。正規化方向ベクトルの内積がこれを下回ると折り返しとみなす。
         * -0.5 ≈ cos(120°): 120° 以上の方向転換で発動。
         */
        private const val REVERSAL_DOT_THRESHOLD = -0.5f
        /**
         * 折り返し時に走行インクをブラシ色へリセットする割合 (0=リセットなし, 1=完全リセット)。
         * 0.8 = 80% ブラシ色に近づける → 引きずり色がほぼ解消される。
         */
        private const val REVERSAL_INK_RESET = 0.8f

        // ── 混色サンプリングスロットル ────────────────────────────────────────
        /**
         * サンプリング間隔 = ブラシ直径 × MIX_STEP_FACTOR。
         * spacing=1 でも spacing=10 と同等の混色密度を保つ。
         * 値を上げると色の変化が遅くなり (より長い引きずり)、
         * 値を下げると密なサンプリングになる (より即時の混色)。
         */
        private const val MIX_STEP_FACTOR = 0.40f
        /** サンプリング間隔の最小値 (px)。小さいブラシで過密にならないように。 */
        private const val MIN_MIX_STEP    = 3f
        /** サンプリング間隔の最大値 (px)。大きいブラシで疎になりすぎないように。 */
        private const val MAX_MIX_STEP    = 50f

        /**
         * 感度付き筆圧カーブ: p^gamma
         * intensity=1..100: gamma = 0.1..0.65 (低強度=ほぼ均一, 標準=0.65)
         * intensity=101..200: gamma = 0.65..2.0 (高強度=急峻な感度)
         */
        fun pressureCurveWithIntensity(p: Float, intensity: Int): Float {
            val gamma = if (intensity <= 100) {
                0.1f + (intensity - 1) / 99f * 0.55f
            } else {
                0.65f + (intensity - 100) / 100f * 1.35f
            }
            return p.coerceIn(0f, 1f).toDouble().pow(gamma.toDouble()).toFloat()
        }
    }



    // ── Drawing ──

    fun startStroke(position: Offset, pressure: Float = 1f) {
        val layer = activeLayer ?: return
        if (layer.isLocked) return
        isDrawing = true
        strokeInkColor = null
        lastMixPosition = null
        lastMixColor = null
        lastMoveDir = Offset.Zero
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

            // 折り返し検出: 前フレームとの方向ベクトルの内積が負 → 大きな方向転換
            val delta = smoothed - last.position
            val moveDist = delta.getDistance()
            if (moveDist > 0.5f) {
                val dir = delta / moveDist
                if (lastMoveDir != Offset.Zero) {
                    val dot = lastMoveDir.x * dir.x + lastMoveDir.y * dir.y
                    if (dot < REVERSAL_DOT_THRESHOLD) {
                        onStrokeReversal()
                    }
                }
                lastMoveDir = dir
            }

            _currentStrokePoints.add(StrokePoint(smoothed, pressure, canvasMix(smoothed, pressure)))
            strokeVersion++
        }
    }

    /**
     * ストローク折り返し時の処理。
     * 走行インクをブラシ色に近づけて引きずりをリセットし、
     * サンプリングスロットルもリセットして折り返し後のサンプリングを即座に有効化する。
     */
    private fun onStrokeReversal() {
        if (brushSettings.type != BrushType.Fude &&
            brushSettings.type != BrushType.Watercolor &&
            brushSettings.type != BrushType.Blur) return
        // 走行インクをブラシ色へリセット (引きずり解消)
        strokeInkColor = strokeInkColor?.let {
            lerp(it, currentColor.copy(alpha = 1f), REVERSAL_INK_RESET)
        }
        // スロットルリセット: 折り返し後は即座にフレッシュなサンプリングから開始
        lastMixPosition = null
        lastMixColor = null
    }

    fun endStroke() {
        if (!isDrawing) return
        isDrawing = false
        strokeInkColor = null
        lastMixPosition = null
        lastMixColor = null

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

    fun renameLayer(layerId: String, newName: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) layers[idx] = layers[idx].copy(name = newName)
    }

    fun toggleClippingMask(layerId: String) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) layers[idx] = layers[idx].copy(isClippingMask = !layers[idx].isClippingMask)
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
        strokeVersion++
    }

    fun redo() {
        val action = redoStack.pop() ?: return
        applyRedo(action)
        undoStack.push(action)
        strokeVersion++
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
            is CanvasAction.SetLayerFilter -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(filter = action.previousFilter)
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
            is CanvasAction.SetLayerFilter -> {
                val idx = layers.indexOfFirst { it.id == action.layerId }
                if (idx >= 0) layers[idx] = layers[idx].copy(filter = action.newFilter)
            }
        }
    }

    // ── PSD Export ──

    /**
     * PSD エクスポート用ヘルパー。GlCanvasView から渡されたピクセルデータを元に PSD を書き出す。
     */
    fun exportPsdFromPixels(
        layerPixelData: List<Pair<String, ByteArray>>,
        canvasWidth: Int,
        canvasHeight: Int,
        outputStream: OutputStream,
    ) {
        val psdLayers = layerPixelData.mapNotNull { (id, pixels) ->
            val layer = layers.find { it.id == id } ?: return@mapNotNull null
            PsdLayerData(
                name          = layer.name,
                pixels        = pixels,
                width         = canvasWidth,
                height        = canvasHeight,
                opacity       = layer.opacity,
                blendMode     = layer.blendMode,
                isVisible     = layer.isVisible,
                isClippingMask = layer.isClippingMask,
            )
        }
        PsdExporter.export(psdLayers, canvasWidth, canvasHeight, outputStream)
    }

    override fun onCleared() {
        super.onCleared()
        undoStack.clear()
        redoStack.clear()
    }
}
