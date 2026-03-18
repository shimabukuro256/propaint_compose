package com.propaint.app.model

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import java.util.UUID

// ── Brush ──

enum class BrushType(
    val displayName: String,
    val defaultSize: Float,
    val defaultOpacity: Float,
    val defaultDensity: Float,
    val defaultSpacing: Float,
    val defaultStabilizer: Float,
    val defaultHardness: Float,
    /** 水分量デフォルト値。Fude / Watercolor のみ有効 (0=ドライ, 1=びしょびしょ)。 */
    val defaultWaterContent: Float,
) {
    Pencil("鉛筆",         4f,  0.9f, 0.80f, 0.06f, 0.3f, 0.95f, 0f),
    Fude("筆",            10f,  0.8f, 0.70f, 0.01f, 0.5f, 0.80f, 0.5f),
    Watercolor("水彩筆",   14f,  0.7f, 1.00f, 0.01f, 0.6f, 0.20f, 0.7f),
    Airbrush("エアブラシ", 22f,  0.6f, 0.07f, 0.25f, 0.7f, 0.00f, 0f),
    Marker("マーカー",     12f,  0.9f, 1.00f, 0.07f, 0.5f, 1.00f, 0f),
    Eraser("消しゴム",     20f,  1.0f, 1.00f, 0.05f, 0.4f, 0.80f, 0f),
    Blur("ぼかし",         14f,  0.7f, 0.50f, 0.15f, 0.5f, 1.00f, 0f),
}

/** ブラシの種類別カテゴリ判定 */
val BrushType.needsCanvasCapture: Boolean get() =
    this == BrushType.Fude || this == BrushType.Watercolor ||
    this == BrushType.Marker || this == BrushType.Blur

/**
 * ウェットキャンバス方式が必要なブラシ。
 * Blur のみ wetCanvasFbo に直接書き込む。
 * Fude / Watercolor は strokeMarksFbo + opacity キャップ方式に変更 (SrcOver 直書きによる
 * アルファ暴走と CPU/GPU 混色不整合を解消するため)。
 */
val BrushType.needsWetCanvas: Boolean get() =
    this == BrushType.Blur || this == BrushType.Fude || this == BrushType.Watercolor

data class BrushSettings(
    val type: BrushType = BrushType.Pencil,
    val size: Float = type.defaultSize,
    val opacity: Float = type.defaultOpacity,
    val density: Float = type.defaultDensity,
    val spacing: Float = type.defaultSpacing,
    val hardness: Float = type.defaultHardness,
    val stabilizer: Float = type.defaultStabilizer,
    /** Blur ツール専用: ボックスブラーの半径倍率 (0=最小, 1=最大) */
    val blurStrength: Float = 0.5f,
    /** Watercolor ツール専用: ぼかし強度 (1=デフォルト, 100=10倍の半径) */
    val watercolorBlurStrength: Int = 1,
    /**
     * 色伸び: 前スタンプの色を次スタンプへ引きずる量 (0=引きずりなし, 1=最大)。
     * Fude / Watercolor / Marker で有効。
     */
    val colorStretch: Float = 0.5f,
    val pressureSizeEnabled: Boolean = true,
    val pressureOpacityEnabled: Boolean = false,
    val minSizeRatio: Float = 0.2f,
    /**
     * ぼかし筆圧しきい値 (Fude / Watercolor 専用)。
     * この値より低い筆圧では描画色を加えず混色のみ行う (0=無効)。
     */
    val blurPressureThreshold: Float = 0f,
    /**
     * 水分量 (Fude / Watercolor 専用, 0=ドライ, 1=びしょびしょ)。
     * - 高いほどキャンバス色吸収が増加し、描画色が薄まる。
     * - 低筆圧との組み合わせで自動的にぼかし挙動に近づく。
     */
    val waterContent: Float = type.defaultWaterContent,
    /**
     * アンチエイリアス強度 (0=なし, 1=1px フェード, 2=2px フェード …)。
     * シェーダー内でスクリーン微分 (dFdx/dFdy) を使い、ブラシサイズに依らず
     * 常に N ピクセル幅のエッジフェードを生成する。
     */
    val antiAliasing: Float = 1.0f,
    /** 筆圧 → サイズ の感度 (1..200, デフォルト 100) */
    val pressureSizeIntensity: Int = 100,
    /** 筆圧 → 不透明度 の感度 (1..200, デフォルト 100) */
    val pressureOpacityIntensity: Int = 100,
    /** 筆圧 → 混色 の有効フラグ (Fude / Watercolor 専用) */
    val pressureMixEnabled: Boolean = false,
    /** 筆圧 → 混色 の感度 (1..200, デフォルト 100) */
    val pressureMixIntensity: Int = 100,
)

// ── Stroke ──

data class StrokePoint(
    val position: Offset,
    val pressure: Float = 1f,
    /**
     * サンプリング混色結果。
     * Fude / Watercolor / Marker / Blur では ViewModel が生成。
     * null の場合は Stroke.color をそのまま使用。
     */
    val color: Color? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class Stroke(
    val points: List<StrokePoint>,
    val brush: BrushSettings,
    val color: Color,
    val layerId: String,
)

// ── Layer ──

enum class LayerBlendMode(val displayName: String, val composeMode: BlendMode) {
    Normal("通常", BlendMode.SrcOver),
    Multiply("乗算", BlendMode.Multiply),
    Screen("スクリーン", BlendMode.Screen),
    Overlay("オーバーレイ", BlendMode.Overlay),
    Darken("暗く", BlendMode.Darken),
    Lighten("明るく", BlendMode.Lighten),
}

data class PaintLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val opacity: Float = 1f,
    val blendMode: LayerBlendMode = LayerBlendMode.Normal,
    val strokes: List<Stroke> = emptyList(),
    val isClippingMask: Boolean = false,
    val filter: LayerFilter? = null,
)

// ── Filter ──

enum class FilterType {
    HSL,      // 色相/彩度/明度
    BLUR,     // ガウスブラー
    CONTRAST, // コントラスト/明るさ
}

data class LayerFilter(
    val type: FilterType,
    val hue: Float = 0f,           // 色相シフト (-180..180 degrees)
    val saturation: Float = 0f,    // 彩度デルタ (-1..1)
    val lightness: Float = 0f,     // 明度デルタ (-1..1)
    val blurRadius: Float = 0f,    // ぼかし半径 (0..1 正規化)
    val contrast: Float = 0f,      // コントラストデルタ (-1..1)
    val brightness: Float = 0f,    // 明るさデルタ (-1..1)
)

// ── Canvas action for undo/redo ──

sealed class CanvasAction {
    data class AddStroke(val stroke: Stroke, val layerId: String) : CanvasAction()
    data class AddLayer(val layer: PaintLayer, val atIndex: Int) : CanvasAction()
    data class RemoveLayer(val layer: PaintLayer, val index: Int) : CanvasAction()
    data class ClearLayer(val layerId: String, val previousStrokes: List<Stroke>) : CanvasAction()
    data class MergeDown(val upper: PaintLayer, val lower: PaintLayer, val upperIndex: Int) : CanvasAction()
    data class DuplicateLayer(val newLayer: PaintLayer, val atIndex: Int) : CanvasAction()
    data class SetLayerFilter(
        val layerId: String,
        val newFilter: LayerFilter?,
        val previousFilter: LayerFilter?,
    ) : CanvasAction()
}
