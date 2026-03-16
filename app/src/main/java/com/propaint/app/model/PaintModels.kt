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
    val defaultDensity: Float,   // Pencil:濃度 / Fude&Watercolor:混色率 / Airbrush:流量 / Marker:無視
    val defaultSpacing: Float,
    val defaultStabilizer: Float,
    val defaultHardness: Float,
) {
    Pencil("鉛筆",         4f,  0.9f, 0.80f, 0.06f, 0.3f, 0.95f),
    Fude("筆",            10f,  0.8f, 0.50f, 0.12f, 0.5f, 0.40f),
    Watercolor("水彩筆",   14f,  0.7f, 0.40f, 0.15f, 0.6f, 0.20f),
    Airbrush("エアブラシ", 22f,  0.6f, 0.07f, 0.25f, 0.7f, 0.00f),
    Marker("マーカー",     12f,  0.9f, 1.00f, 0.07f, 0.5f, 1.00f),
    Eraser("消しゴム",     20f,  1.0f, 1.00f, 0.05f, 0.4f, 0.80f),
    Blur("ぼかし",         14f,  0.7f, 0.50f, 0.15f, 0.5f, 0.30f),
}

/** ブラシの種類別カテゴリ判定 */
val BrushType.needsCanvasCapture: Boolean get() =
    this == BrushType.Fude || this == BrushType.Watercolor ||
    this == BrushType.Marker || this == BrushType.Blur

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
    val pressureSizeEnabled: Boolean = true,
    val pressureOpacityEnabled: Boolean = false,
    val minSizeRatio: Float = 0.2f,
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
)

// ── Canvas action for undo/redo ──

sealed class CanvasAction {
    data class AddStroke(val stroke: Stroke, val layerId: String) : CanvasAction()
    data class AddLayer(val layer: PaintLayer, val atIndex: Int) : CanvasAction()
    data class RemoveLayer(val layer: PaintLayer, val index: Int) : CanvasAction()
    data class ClearLayer(val layerId: String, val previousStrokes: List<Stroke>) : CanvasAction()
    data class MergeDown(val upper: PaintLayer, val lower: PaintLayer, val upperIndex: Int) : CanvasAction()
    data class DuplicateLayer(val newLayer: PaintLayer, val atIndex: Int) : CanvasAction()
}