package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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

// ── ブラシアイコンマップ ──────────────────────────────────────────────────

val brushIcons: Map<BrushType, ImageVector> = mapOf(
    BrushType.Pencil     to Icons.Default.Edit,
    BrushType.Fude       to Icons.Default.Brush,
    BrushType.Watercolor to Icons.Default.WaterDrop,
    BrushType.Airbrush   to Icons.Default.BlurOn,
    BrushType.Marker     to Icons.Default.FormatPaint,
    BrushType.Eraser     to Icons.Default.AutoFixHigh,
    BrushType.Blur       to Icons.Default.Flare,
)

// ── TopBar (2 行) ────────────────────────────────────────────────────────
//
// 行 1: アプリ名 | Undo/Redo | ─── | Grid | Zoom% | Color | スポイト | Layers
// 行 2: [ブラシ種別スクロール] | ブラシ設定ボタン | 筆圧ボタン

@Composable
fun TopBar(
    vm: PaintViewModel,
    onColorPicker: () -> Unit,
    onLayerPanel: () -> Unit,
    onBrushPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xE6141414))
            .fillMaxWidth(),
    ) {
        // ── 行 1 ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ProPaint",
                color      = Color(0xFF4A90D9),
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold,
            )

            ToolDivider()

            ToolBtn(Icons.AutoMirrored.Filled.Undo, "戻す",    enabled = vm.canUndo) { vm.undo() }
            ToolBtn(Icons.AutoMirrored.Filled.Redo, "やり直し", enabled = vm.canRedo) { vm.redo() }

            Spacer(Modifier.weight(1f))

            ToolBtn(Icons.Default.GridOn, "グリッド", isSelected = vm.showGrid) { vm.toggleGrid() }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A))
                    .clickable { vm.resetView() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("${(vm.zoom * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }

            ToolDivider()

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(vm.currentColor)
                    .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable { onColorPicker() },
            )

            Spacer(Modifier.width(4.dp))

            // スポイト (スポットカラーピッカー)
            ToolBtn(
                icon       = Icons.Default.ColorLens,
                label      = "スポイト",
                isSelected = vm.isEyedropperActive,
            ) {
                if (vm.isEyedropperActive) vm.deactivateEyedropper()
                else vm.activateEyedropper()
            }

            ToolBtn(Icons.Default.Layers, "レイヤー") { onLayerPanel() }
        }

        HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)

        // ── 行 2: ブラシ種別 + ブラシ設定 + 筆圧 ────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ブラシ種別スクロール選択
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (type in BrushType.entries) {
                    val isSelected = vm.brushSettings.type == type
                    BrushTypeChip(
                        type       = type,
                        isSelected = isSelected,
                        onClick    = { vm.selectBrushType(type) },
                    )
                }
            }

            ToolDivider()

            // ブラシ設定ボタン
            ToolBtn(
                icon  = Icons.Default.Tune,
                label = "ブラシ設定",
            ) { onBrushPanel() }

            // 筆圧オン/オフ
            ToolBtn(
                icon       = Icons.Default.Speed,
                label      = if (vm.brushSettings.pressureSizeEnabled) "筆圧ON" else "筆圧OFF",
                isSelected = vm.brushSettings.pressureSizeEnabled,
            ) { vm.togglePressureSize() }
        }
    }
}

@Composable
private fun BrushTypeChip(
    type: BrushType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) Color(0xFF4A90D9).copy(alpha = 0.2f) else Color.Transparent,
            )
            .border(
                width  = if (isSelected) 1.5.dp else 0.dp,
                color  = if (isSelected) Color(0xFF4A90D9) else Color.Transparent,
                shape  = RoundedCornerShape(8.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            brushIcons[type] ?: Icons.Default.Brush,
            contentDescription = type.displayName,
            modifier = Modifier.size(18.dp),
            tint = if (isSelected) Color(0xFF6AB0FF) else Color.White.copy(alpha = 0.55f),
        )
        Text(
            type.displayName,
            fontSize = 8.sp,
            color = if (isSelected) Color(0xFF6AB0FF) else Color.White.copy(alpha = 0.45f),
        )
    }
}

// ── 共通ウィジェット ──────────────────────────────────────────────────────

@Composable
fun ToolBtn(
    icon: ImageVector,
    label: String,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled   -> Color.White.copy(alpha = 0.2f)
                isSelected -> Color(0xFF6AB0FF)
                else       -> Color.White.copy(alpha = 0.7f)
            },
        )
    }
}

@Composable
fun ToolDivider() {
    Spacer(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(24.dp)
            .background(Color(0xFF333333))
    )
}
