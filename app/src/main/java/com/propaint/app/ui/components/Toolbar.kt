package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.model.BrushType
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun TopToolbar(
    vm: PaintViewModel,
    onBrushPanel: () -> Unit,
    onColorPicker: () -> Unit,
    onLayerPanel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App title
        Text("ProPaint", color = Color(0xFF4A90D9), fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

        ToolDivider()

        // Undo/Redo
        ToolBtn(Icons.AutoMirrored.Filled.Undo, "戻す", enabled = vm.canUndo) { vm.undo() }
        ToolBtn(Icons.AutoMirrored.Filled.Redo, "やり直し", enabled = vm.canRedo) { vm.redo() }

        ToolDivider()

        // Quick brush selectors
        val quickBrushes = listOf(
            BrushType.Pen to Icons.Default.Create,
            BrushType.Pencil to Icons.Default.Edit,
            BrushType.Marker to Icons.Default.FormatPaint,
            BrushType.Eraser to Icons.Default.AutoFixHigh,
        )
        for ((type, icon) in quickBrushes) {
            ToolBtn(
                icon = icon,
                label = type.displayName,
                isSelected = vm.brushSettings.type == type,
            ) { vm.selectBrushType(type) }
        }

        // Brush settings
        ToolBtn(Icons.Default.Tune, "ブラシ設定") { onBrushPanel() }

        ToolDivider()

        // Pressure toggle
        ToolBtn(
            icon = Icons.Default.Speed,
            label = if (vm.brushSettings.pressureSizeEnabled) "筆圧ON" else "筆圧OFF",
            isSelected = vm.brushSettings.pressureSizeEnabled,
        ) { vm.togglePressureSize() }

        // Color swatch
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(vm.currentColor)
                .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .clickable { onColorPicker() },
        )

        Spacer(Modifier.weight(1f))

        // Grid
        ToolBtn(Icons.Default.GridOn, "グリッド", isSelected = vm.showGrid) { vm.toggleGrid() }

        // Symmetry
        ToolBtn(Icons.Default.AutoAwesome, "対称", isSelected = vm.symmetryEnabled) { vm.toggleSymmetry() }

        ToolDivider()

        // Zoom
        Box(
            modifier = Modifier
                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                .clickable { vm.resetView() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("${(vm.zoom * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        }

        ToolDivider()

        // Layers
        ToolBtn(Icons.Default.Layers, "レイヤー") { onLayerPanel() }
    }
}

@Composable
fun ToolBtn(
    icon: ImageVector,
    label: String,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.2f)
                isSelected -> Color(0xFF6AB0FF)
                else -> Color.White.copy(alpha = 0.7f)
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
