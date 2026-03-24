package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun FilterPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var filterType by remember { mutableIntStateOf(0) } // 0=HSL, 1=Contrast, 2=Blur
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var lit by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var blurRadius by remember { mutableFloatStateOf(3f) }

    Box(
        modifier = modifier
            .width(320.sdp)
            .shadow(16.sdp, RoundedCornerShape(14.sdp))
            .clip(RoundedCornerShape(14.sdp))
            .background(Color(0xF01E1E1E))
            .padding(14.sdp),
    ) {
        Column {
            // ── Header ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("フィルター", color = Color.White, fontSize = 13.ssp)
                PanelCloseButton(onDismiss)
            }
            Spacer(Modifier.height(10.sdp))

            // ── Filter type chips ──
            Row(horizontalArrangement = Arrangement.spacedBy(6.sdp)) {
                FilterTypeChip("HSL", filterType == 0) { filterType = 0 }
                FilterTypeChip("明るさ/コントラスト", filterType == 1) { filterType = 1 }
                FilterTypeChip("ぼかし", filterType == 2) { filterType = 2 }
            }
            Spacer(Modifier.height(14.sdp))

            // ── Filter controls ──
            when (filterType) {
                0 -> {
                    ParamSlider("色相", "${hue.toInt()}°", hue, -180f..180f) { hue = it }
                    ParamSlider("彩度", "${(sat * 100).toInt()}%", sat, -1f..1f) { sat = it }
                    ParamSlider("明度", "${(lit * 100).toInt()}%", lit, -1f..1f) { lit = it }
                    Spacer(Modifier.height(8.sdp))
                    ApplyResetRow(
                        onApply = {
                            viewModel.applyHslFilter(hue, sat, lit)
                            hue = 0f; sat = 0f; lit = 0f
                        },
                        onReset = { hue = 0f; sat = 0f; lit = 0f },
                    )
                }
                1 -> {
                    ParamSlider("明るさ", "${(brightness * 100).toInt()}%", brightness, -1f..1f) { brightness = it }
                    ParamSlider("コントラスト", "${(contrast * 100).toInt()}%", contrast, -1f..1f) { contrast = it }
                    Spacer(Modifier.height(8.sdp))
                    ApplyResetRow(
                        onApply = {
                            viewModel.applyBrightnessContrast(brightness, contrast)
                            brightness = 0f; contrast = 0f
                        },
                        onReset = { brightness = 0f; contrast = 0f },
                    )
                }
                2 -> {
                    ParamSlider("半径", "${blurRadius.toInt()}px", blurRadius, 1f..30f) { blurRadius = it }
                    Spacer(Modifier.height(8.sdp))
                    ApplyResetRow(
                        onApply = { viewModel.applyBlurFilter(blurRadius.toInt()) },
                        onReset = { blurRadius = 3f },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.sdp))
            .background(if (selected) Color(0xFF3A5A7C) else Color(0xFF2A2A2A))
            .border(1.2.dp, if (selected) Color(0xFF6CB4EE) else Color.Transparent, RoundedCornerShape(7.sdp))
            .clickable { onClick() }
            .padding(horizontal = 10.sdp, vertical = 6.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else Color(0xFFCCCCCC), fontSize = 11.ssp)
    }
}

@Composable
private fun ApplyResetRow(onApply: () -> Unit, onReset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.sdp)) {
        Button(
            onClick = onApply,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C)),
            contentPadding = PaddingValues(horizontal = 16.sdp, vertical = 8.sdp),
            shape = RoundedCornerShape(7.sdp),
            elevation = ButtonDefaults.buttonElevation(0.dp),
        ) {
            Text("適用", fontSize = 12.ssp)
        }
        TextButton(onClick = onReset) {
            Text("リセット", fontSize = 12.ssp, color = Color(0xFFAAAAAA))
        }
    }
}
