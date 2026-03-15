package com.propaint.app.model

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Offset
import java.util.UUID

// ── Brush ──

enum class BrushType(
    val displayName: String,
    val defaultSize: Float,
    // 不透明度: ストローク全体の alpha 上限
    val defaultOpacity: Float,
    // ブラシ濃度: スタンプ 1 回あたりの塗り量 (0=薄い, 1=不透明度上限まで一発で塗る)
    val defaultDensity: Float,
    // スタンプ間隔: 基準ステップ (ブラシ半径 × 0.5) に対する倍率
    // 1.0 = デフォルト間隔、0.5 = 2倍密、2.0 = 2倍疎
    val defaultSpacing: Float,
    val smoothing: Float,
    val cap: StrokeCap,
    val join: StrokeJoin,
) {
    Pencil("鉛筆",     3f,  0.9f, 0.6f, 1.0f, 0.3f, StrokeCap.Round,  StrokeJoin.Round),
    Pen("ペン",        4f,  1.0f, 1.0f, 1.0f, 0.5f, StrokeCap.Round,  StrokeJoin.Round),
    Marker("マーカー", 12f, 0.8f, 0.9f, 1.0f, 0.6f, StrokeCap.Square, StrokeJoin.Bevel),
    Airbrush("エアブラシ", 20f, 0.6f, 0.08f, 1.0f, 0.8f, StrokeCap.Round, StrokeJoin.Round),
    Watercolor("水彩", 15f, 0.7f, 0.5f, 1.0f, 0.7f, StrokeCap.Round,  StrokeJoin.Round),
    Crayon("クレヨン",  8f, 0.9f, 0.6f, 1.0f, 0.2f, StrokeCap.Round,  StrokeJoin.Round),
    Calligraphy("書道", 6f, 1.0f, 1.0f, 1.0f, 0.4f, StrokeCap.Square, StrokeJoin.Bevel),
    Eraser("消しゴム", 20f, 1.0f, 1.0f, 1.0f, 0.5f, StrokeCap.Round,  StrokeJoin.Round);
}

data class BrushSettings(
    val type: BrushType = BrushType.Pen,
    val size: Float = type.defaultSize,
    // 不透明度: saveLayer の alpha 上限。ストロークを重ねるほどこの値に近づく
    val opacity: Float = type.defaultOpacity,
    // ブラシ濃度: スタンプ 1 回あたりの塗り量。opacity とは独立して塗り重ねの速さを制御
    val density: Float = type.defaultDensity,
    // スタンプ間隔倍率: 大きいほど疎、小さいほど密。密度は自動補正される
    val spacing: Float = type.defaultSpacing,
    val pressureSizeEnabled: Boolean = true,
    val pressureOpacityEnabled: Boolean = false,
    val pressureBlendEnabled: Boolean = false,
    val minSizeRatio: Float = 0.2f,
)

// ── Stroke ──

data class StrokePoint(
    val position: Offset,
    val pressure: Float = 1f,
    // 水彩ブラシの SAI スタイル混色: 描画時点でキャンバス色と混合した結果を格納。
    // null の場合は Stroke.color をそのまま使用する。
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
    data class AddLayer(val layer: PaintLayer) : CanvasAction()
    data class RemoveLayer(val layer: PaintLayer, val index: Int) : CanvasAction()
    data class ClearLayer(val layerId: String, val previousStrokes: List<Stroke>) : CanvasAction()
}
