package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.model.BrushType
import com.propaint.app.viewmodel.PaintViewModel
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    val b = vm.brushSettings

    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(Color(0xF01E1E1E)),
    ) {
        PanelHeader(title = "ブラシ設定", icon = Icons.Default.Brush, onClose = onClose)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            // ── ブラシ種別グリッド ──────────────────────────────────────
            BrushTypeGrid(
                selected = b.type,
                onSelect = { vm.selectBrushType(it) },
            )

            Spacer(Modifier.height(6.dp))
            Text(
                b.type.displayName,
                color      = Color(0xFF4A90D9),
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            // ── サイズ (非線形スケール) ─────────────────────────────────
            SizeSlider(
                size          = b.size,
                onValueChange = { vm.setBrushSize(it) },
            )

            // ── 不透明度 (Pencil・Marker のみ) ─────────────────────────
            val showOpacity = b.type == BrushType.Pencil || b.type == BrushType.Marker
            if (showOpacity) {
                LabeledSlider(
                    label    = "不透明度",
                    value    = b.opacity,
                    range    = 0.01f..1f,
                    display  = "${(b.opacity * 100).toInt()}%",
                    onValueChange = { vm.setBrushOpacity(it) },
                )
            }

            // ── 濃度 / 混色率 (Eraser・Blur 以外) ─────────────────────
            // 筆・水彩筆・エアブラシは「濃度」、マーカーは「混色率」
            val densityLabel = if (b.type == BrushType.Marker) "混色率" else "濃度"
            if (b.type != BrushType.Eraser && b.type != BrushType.Blur) {
                LabeledSlider(
                    label    = densityLabel,
                    value    = b.density,
                    range    = 0.01f..1f,
                    display  = "${(b.density * 100).toInt()}%",
                    onValueChange = { vm.setBrushDensity(it) },
                )
            }

            // ── ぼかし強度 (Blur のみ) ──────────────────────────────────
            if (b.type == BrushType.Blur) {
                LabeledSlider(
                    label    = "ぼかし強度",
                    value    = b.blurStrength,
                    range    = 0.05f..1f,
                    display  = "${(b.blurStrength * 100).toInt()}%",
                    onValueChange = { vm.setBrushBlurStrength(it) },
                )
            }

            // ── ぼかし強度 (Watercolor のみ) ────────────────────────────
            if (b.type == BrushType.Watercolor) {
                LabeledSlider(
                    label    = "ぼかし強度",
                    value    = b.watercolorBlurStrength.toFloat(),
                    range    = 1f..100f,
                    display  = "${b.watercolorBlurStrength}",
                    onValueChange = { vm.setBrushWatercolorBlurStrength(it.roundToInt()) },
                )
            }

            // ── ハードネス (Pencil・Marker・Eraser・Fude・Watercolor・Airbrush) ──
            val showHardness = b.type != BrushType.Blur
            if (showHardness) {
                LabeledSlider(
                    label    = "ハードネス",
                    value    = b.hardness,
                    range    = 0f..1f,
                    display  = "${(b.hardness * 100).toInt()}%",
                    onValueChange = { vm.setBrushHardness(it) },
                )
            }

            // ── アンチエイリアス (全ブラシ) ────────────────────────────────────
            LabeledSlider(
                label    = "アンチエイリアス",
                value    = b.antiAliasing,
                range    = 0f..4f,
                display  = if (b.antiAliasing < 0.05f) "OFF"
                           else "${"%.1f".format(b.antiAliasing)}px",
                onValueChange = { vm.setBrushAntiAliasing(it) },
            )

            // ── 水分量 (Fude / Watercolor のみ) ────────────────────────
            val showWaterContent = b.type == BrushType.Fude || b.type == BrushType.Watercolor
            if (showWaterContent) {
                LabeledSlider(
                    label    = "水分量",
                    value    = b.waterContent,
                    range    = 0f..1f,
                    display  = "${(b.waterContent * 100).toInt()}%",
                    onValueChange = { vm.setBrushWaterContent(it) },
                )
            }

            // ── 色伸び (Fude / Watercolor / Marker のみ) ───────────────
            val showColorStretch = b.type == BrushType.Fude ||
                                   b.type == BrushType.Watercolor ||
                                   b.type == BrushType.Marker
            if (showColorStretch) {
                LabeledSlider(
                    label    = "色伸び",
                    value    = b.colorStretch,
                    range    = 0f..1f,
                    display  = "${(b.colorStretch * 100).toInt()}",
                    onValueChange = { vm.setBrushColorStretch(it) },
                )
            }

            // ── 間隔 (全ブラシ) ────────────────────────────────────────
            LabeledSlider(
                label    = "間隔",
                value    = b.spacing,
                range    = 0.01f..2.0f,
                display  = "${(b.spacing * 100).toInt()}%",
                onValueChange = { vm.setBrushSpacing(it) },
            )

            // ── スタビライザー (全ブラシ) ──────────────────────────────
            LabeledSlider(
                label    = "スタビライザー",
                value    = b.stabilizer,
                range    = 0f..1f,
                display  = "${(b.stabilizer * 100).toInt()}%",
                onValueChange = { vm.setBrushStabilizer(it) },
            )

            if (b.type != BrushType.Eraser) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(12.dp))

                // ── 筆圧設定 ──────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Speed, null,
                        tint     = Color(0xFF4A90D9),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "筆圧設定",
                        color      = Color(0xFF4A90D9),
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))

                PressureToggleCard(
                    label    = "筆圧 → サイズ",
                    subtitle = "強く押すと太く描ける",
                    checked  = b.pressureSizeEnabled,
                    onToggle = { vm.togglePressureSize() },
                )

                if (b.pressureSizeEnabled) {
                    LabeledSlider(
                        label    = "最小サイズ比",
                        value    = b.minSizeRatio,
                        range    = 0.01f..0.9f,
                        display  = "${(b.minSizeRatio * 100).toInt()}%",
                        onValueChange = { vm.setBrush(b.copy(minSizeRatio = it)) },
                    )
                    LabeledSlider(
                        label    = "筆圧感度 (サイズ)",
                        value    = b.pressureSizeIntensity.toFloat(),
                        range    = 1f..200f,
                        display  = "${b.pressureSizeIntensity}",
                        onValueChange = { vm.setPressureSizeIntensity(it.roundToInt()) },
                    )
                }

                if (b.type != BrushType.Blur) {
                    val pressureLabel = when (b.type) {
                        BrushType.Fude, BrushType.Watercolor, BrushType.Airbrush -> "筆圧 → 濃度"
                        else -> "筆圧 → 不透明度"
                    }
                    Spacer(Modifier.height(4.dp))
                    PressureToggleCard(
                        label    = pressureLabel,
                        subtitle = "強く押すと濃く描ける",
                        checked  = b.pressureOpacityEnabled,
                        onToggle = { vm.togglePressureOpacity() },
                    )
                    if (b.pressureOpacityEnabled) {
                        LabeledSlider(
                            label    = "筆圧感度 (不透明度)",
                            value    = b.pressureOpacityIntensity.toFloat(),
                            range    = 1f..200f,
                            display  = "${b.pressureOpacityIntensity}",
                            onValueChange = { vm.setPressureOpacityIntensity(it.roundToInt()) },
                        )
                    }
                }

                // ── 筆圧 → 混色 (Fude / Watercolor のみ) ──────────────
                if (b.type == BrushType.Fude || b.type == BrushType.Watercolor) {
                    Spacer(Modifier.height(4.dp))
                    PressureToggleCard(
                        label    = "筆圧 → 混色",
                        subtitle = "強く押すと混色が強くなる",
                        checked  = b.pressureMixEnabled,
                        onToggle = { vm.togglePressureMix() },
                    )
                    if (b.pressureMixEnabled) {
                        LabeledSlider(
                            label    = "筆圧感度 (混色)",
                            value    = b.pressureMixIntensity.toFloat(),
                            range    = 1f..200f,
                            display  = "${b.pressureMixIntensity}",
                            onValueChange = { vm.setPressureMixIntensity(it.roundToInt()) },
                        )
                    }
                }

                // ── ぼかし筆圧 (Fude・Watercolor のみ) ─────────────────
                if (b.type == BrushType.Fude || b.type == BrushType.Watercolor) {
                    Spacer(Modifier.height(4.dp))
                    LabeledSlider(
                        label    = "ぼかし筆圧",
                        value    = b.blurPressureThreshold,
                        range    = 0f..1f,
                        display  = if (b.blurPressureThreshold < 0.01f) "OFF"
                                   else "${(b.blurPressureThreshold * 100).toInt()}%",
                        onValueChange = { vm.setBrush(b.copy(blurPressureThreshold = it.coerceIn(0f, 1f))) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 非線形サイズスライダー (sqrt スケール)。
 * 視覚的フラクション = sqrt((size-1)/1999)
 * 実際サイズ = fraction^2 * 1999 + 1
 */
@Composable
fun SizeSlider(
    size: Float,
    onValueChange: (Float) -> Unit,
) {
    val visualFraction = sqrt((size - 1f) / 1999f).coerceIn(0f, 1f)

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("サイズ", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text("${size.toInt()}px", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Slider(
            value         = visualFraction,
            onValueChange = { frac ->
                val actualSize = (frac * frac * 1999f + 1f).coerceIn(1f, 2000f)
                onValueChange(actualSize)
            },
            valueRange    = 0f..1f,
            colors        = SliderDefaults.colors(
                thumbColor          = Color.White,
                activeTrackColor    = Color(0xFF4A90D9),
                inactiveTrackColor  = Color(0xFF333333),
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun BrushTypeGrid(selected: BrushType, onSelect: (BrushType) -> Unit) {
    Column {
        for (row in BrushType.entries.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (type in row) {
                    val isSel = type == selected
                    Column(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFF4A90D9) else Color(0xFF2A2A2A))
                            .border(
                                1.dp,
                                if (isSel) Color(0xFF6AB0FF) else Color(0xFF444444),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(type) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            brushIcons[type] ?: Icons.Default.Brush,
                            contentDescription = type.displayName,
                            tint     = if (isSel) Color.White else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            type.displayName,
                            fontSize = 8.sp,
                            color    = if (isSel) Color.White else Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(display, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = range,
            colors        = SliderDefaults.colors(
                thumbColor          = Color.White,
                activeTrackColor    = Color(0xFF4A90D9),
                inactiveTrackColor  = Color(0xFF333333),
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
fun PressureToggleCard(
    label: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) Color(0xFF4A90D9).copy(alpha = 0.12f) else Color(0xFF2A2A2A))
            .border(
                1.dp,
                if (checked) Color(0xFF4A90D9).copy(alpha = 0.4f) else Color(0xFF3A3A3A),
                RoundedCornerShape(8.dp),
            )
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint     = if (checked) Color(0xFF4A90D9) else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color    = if (checked) Color.White else Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Text(subtitle, color = Color.White.copy(alpha = 0.38f), fontSize = 10.sp)
        }
        Switch(
            checked         = checked,
            onCheckedChange = { onToggle() },
            modifier        = Modifier.height(24.dp),
            colors          = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4A90D9)),
        )
    }
}

@Composable
fun PanelHeader(title: String, icon: ImageVector, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Close, "閉じる",
                tint     = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider(color = Color(0xFF2A2A2A))
}
