package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp
import com.propaint.app.viewmodel.BrushType
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun BrushPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val type by viewModel.currentBrushType.collectAsState()
    val size by viewModel.brushSize.collectAsState()
    val opacity by viewModel.brushOpacity.collectAsState()
    val hardness by viewModel.brushHardness.collectAsState()
    val density by viewModel.brushDensity.collectAsState()
    val spacing by viewModel.brushSpacing.collectAsState()
    val colorStretch by viewModel.colorStretch.collectAsState()
    val waterContent by viewModel.waterContent.collectAsState()
    val blurStrength by viewModel.blurStrength.collectAsState()
    val pressureSize by viewModel.pressureSizeEnabled.collectAsState()
    val pressureOpacity by viewModel.pressureOpacityEnabled.collectAsState()
    val pressureSmudge by viewModel.pressureSmudgeEnabled.collectAsState()
    val filterPressureThreshold by viewModel.filterPressureThreshold.collectAsState()
    val fillTolerance by viewModel.fillTolerance.collectAsState()

    val isMixBrush = type == BrushType.Fude || type == BrushType.Watercolor
    val isMarker = type == BrushType.Marker
    val isBlur = type == BrushType.Blur
    val isBinary = type == BrushType.BinaryPen
    val isFill = type == BrushType.Fill
    val hasSmudge = isMixBrush || isMarker
    val showOpacity = !isMixBrush && !isBlur && !isFill
    val showBrushParams = !isFill

    Box(
        modifier = modifier
            .width(300.sdp)
            .heightIn(max = 500.sdp)
            .shadow(16.sdp, RoundedCornerShape(14.sdp))
            .clip(RoundedCornerShape(14.sdp))
            .background(Color(0xF01E1E1E)),
    ) {
        Column(
            modifier = Modifier
                .padding(14.sdp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Header ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ブラシ設定", color = Color.White, fontSize = 13.ssp)
                PanelCloseButton(onDismiss)
            }
            Spacer(Modifier.height(10.sdp))

            // ── Brush type grid ──
            BrushTypeGrid(type, viewModel)
            Spacer(Modifier.height(14.sdp))

            // ── Fill parameters ──
            if (isFill) {
                ParamSlider("許容値", "${fillTolerance.toInt()}", fillTolerance, 0f..255f) { viewModel.setFillTolerance(it) }
                ParamSlider("不透明度", "${(opacity * 100).toInt()}%", opacity, 0.01f..1f) { viewModel.setBrushOpacity(it) }
            }

            // ── Common brush parameters ──
            if (showBrushParams) {
                ParamSlider("サイズ", "${size.toInt()}px", size, 1f..2000f) { viewModel.setBrushSize(it) }
                if (showOpacity) {
                    ParamSlider("不透明度", "${(opacity * 100).toInt()}%", opacity, 0.01f..1f) { viewModel.setBrushOpacity(it) }
                }
                if (!isBinary) {
                    ParamSlider("硬さ", "${(hardness * 100).toInt()}%", hardness, 0f..1f) { viewModel.setBrushHardness(it) }
                }
                ParamSlider("濃度", "${(density * 100).toInt()}%", density, 0.01f..1f) { viewModel.setBrushDensity(it) }
                ParamSlider("間隔", "${(spacing * 100).toInt()}%", spacing, 0.01f..2f) { viewModel.setBrushSpacing(it) }

                // ── Mixing parameters ──
                if (hasSmudge) {
                    Spacer(Modifier.height(8.sdp))
                    SectionLabel("混色設定")
                    ParamSlider("色伸び", "${(colorStretch * 100).toInt()}%", colorStretch, 0f..1f) { viewModel.setColorStretch(it) }
                    if (isMixBrush) {
                        ParamSlider("水分量", "${(waterContent * 100).toInt()}%", waterContent, 0f..1f) { viewModel.setWaterContent(it) }
                        ParamSlider("ぼかし筆圧", "${(filterPressureThreshold * 100).toInt()}%", filterPressureThreshold, 0f..0.9f) { viewModel.setFilterPressureThreshold(it) }
                    }
                }

                // ── Blur parameters ──
                if (isBlur) {
                    Spacer(Modifier.height(8.sdp))
                    SectionLabel("ぼかし設定")
                    ParamSlider("強度", "${(blurStrength * 100).toInt()}%", blurStrength, 0.05f..1f) { viewModel.setBlurStrength(it) }
                }

                // ── Pressure settings ──
                if (!isBinary) {
                    Spacer(Modifier.height(8.sdp))
                    SectionLabel("筆圧")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PressureCheck("サイズ", pressureSize) { viewModel.togglePressureSize() }
                        if (showOpacity) {
                            Spacer(Modifier.width(12.sdp))
                            PressureCheck("不透明度", pressureOpacity) { viewModel.togglePressureOpacity() }
                        }
                    }
                    if (hasSmudge) {
                        PressureCheck("混色", pressureSmudge) { viewModel.togglePressureSmudge() }
                    }
                }
            }

            Spacer(Modifier.height(10.sdp))
            TextButton(
                onClick = { viewModel.clearActiveLayer() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("レイヤーをクリア", fontSize = 12.ssp)
            }
        }
    }
}

/** Brush type selection grid with icon + name chips. */
@Composable
private fun BrushTypeGrid(currentType: BrushType, viewModel: PaintViewModel) {
    // Use FlowRow-like layout via multiple rows
    val types = BrushType.entries
    val chunked = types.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(5.sdp)) {
        for (row in chunked) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.sdp)) {
                for (t in row) {
                    val sel = t == currentType
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.sdp))
                            .background(if (sel) Color(0xFF3A5A7C) else Color(0xFF2A2A2A))
                            .border(
                                1.2.dp,
                                if (sel) Color(0xFF6CB4EE) else Color.Transparent,
                                RoundedCornerShape(7.sdp)
                            )
                            .clickable { viewModel.setBrushType(t) }
                            .padding(horizontal = 10.sdp, vertical = 6.sdp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t.displayName,
                            color = if (sel) Color.White else Color(0xFFCCCCCC),
                            fontSize = 11.ssp,
                        )
                    }
                }
            }
        }
    }
}

/** Compact parameter slider matching Flutter design. */
@Composable
internal fun ParamSlider(
    label: String, valueText: String, value: Float,
    range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.sdp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFAAAAAA), fontSize = 11.ssp)
            Text(valueText, color = Color.White, fontSize = 11.ssp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(28.sdp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF6CB4EE),
                inactiveTrackColor = Color(0xFF3A3A3A),
            ),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF6CB4EE),
        fontSize = 12.ssp,
        modifier = Modifier.padding(bottom = 4.sdp),
    )
}

@Composable
private fun PressureCheck(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF6CB4EE),
                uncheckedColor = Color(0xFF666666),
            ),
            modifier = Modifier.size(28.sdp),
        )
        Text(label, color = Color.White, fontSize = 11.ssp)
    }
}

/** Reusable panel close button (small × icon). */
@Composable
internal fun PanelCloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.sdp)
            .clip(RoundedCornerShape(6.sdp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, "Close", tint = Color(0xFF888888), modifier = Modifier.size(14.sdp))
    }
}
