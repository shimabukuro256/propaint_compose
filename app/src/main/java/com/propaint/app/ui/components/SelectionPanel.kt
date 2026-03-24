package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brush
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
import com.propaint.app.viewmodel.ToolMode

@Composable
fun SelectionPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val toolMode by viewModel.toolMode.collectAsState()
    val hasSelection by viewModel.hasSelection.collectAsState()
    val tolerance by viewModel.selectionTolerance.collectAsState()
    val addMode by viewModel.selectionAddMode.collectAsState()

    Box(
        modifier = modifier
            .width(260.sdp)
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
                Text("選択範囲", color = Color.White, fontSize = 13.ssp)
                PanelCloseButton(onDismiss)
            }
            Spacer(Modifier.height(10.sdp))

            // ── Selection tools ──
            SectionLabel("選択ツール")
            Spacer(Modifier.height(6.sdp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.sdp)) {
                ToolChip("矩形", ToolMode.SelectRect, toolMode) { viewModel.setToolMode(ToolMode.SelectRect) }
                ToolChip("自動", ToolMode.SelectAuto, toolMode) { viewModel.setToolMode(ToolMode.SelectAuto) }
                ToolChip("ペン", ToolMode.SelectPen, toolMode) { viewModel.setToolMode(ToolMode.SelectPen) }
                ToolChip("消し", ToolMode.SelectEraser, toolMode) { viewModel.setToolMode(ToolMode.SelectEraser) }
            }
            Spacer(Modifier.height(10.sdp))

            // ── Add mode ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = addMode,
                    onCheckedChange = { viewModel.toggleSelectionAddMode() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF6CB4EE),
                        uncheckedColor = Color(0xFF666666),
                    ),
                    modifier = Modifier.size(28.sdp),
                )
                Spacer(Modifier.width(4.sdp))
                Text("追加モード", color = Color.White, fontSize = 11.ssp)
            }

            // ── Tolerance slider (auto select) ──
            if (toolMode == ToolMode.SelectAuto) {
                Spacer(Modifier.height(6.sdp))
                ParamSlider("許容値", "${tolerance.toInt()}", tolerance, 0f..255f) {
                    viewModel.setSelectionTolerance(it)
                }
            }

            Spacer(Modifier.height(10.sdp))

            // ── Selection actions ──
            SectionLabel("選択操作")
            Spacer(Modifier.height(6.sdp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.sdp)) {
                ActionChip("全選択") { viewModel.selectAll() }
                ActionChip("反転") { viewModel.invertSelection() }
                ActionChip("解除") { viewModel.clearSelection() }
            }

            Spacer(Modifier.height(8.sdp))

            // ── Back to draw ──
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.sdp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable {
                        viewModel.setToolMode(ToolMode.Draw)
                        onDismiss()
                    }
                    .padding(horizontal = 8.sdp, vertical = 5.sdp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Brush, null, tint = Color.White, modifier = Modifier.size(14.sdp))
                    Spacer(Modifier.width(4.sdp))
                    Text("描画に戻る", color = Color.White, fontSize = 11.ssp)
                }
            }

            // ── Selection status ──
            if (hasSelection) {
                Spacer(Modifier.height(6.sdp))
                Text("選択範囲あり", color = Color(0xFF66BB6A), fontSize = 10.ssp)
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, mode: ToolMode, currentMode: ToolMode, onClick: () -> Unit) {
    val sel = currentMode == mode
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.sdp))
            .background(if (sel) Color(0xFF3A5A7C) else Color(0xFF2A2A2A))
            .border(1.2.dp, if (sel) Color(0xFF6CB4EE) else Color.Transparent, RoundedCornerShape(7.sdp))
            .clickable { onClick() }
            .padding(horizontal = 12.sdp, vertical = 6.sdp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (sel) Color.White else Color(0xFFCCCCCC), fontSize = 11.ssp)
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.sdp))
            .background(Color(0xFF6CB4EE).copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 10.sdp, vertical = 5.sdp),
    ) {
        Text(label, color = Color(0xFF6CB4EE), fontSize = 11.ssp)
    }
}
