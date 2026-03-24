package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp
import com.propaint.app.viewmodel.BrushType
import com.propaint.app.viewmodel.PaintViewModel
import com.propaint.app.viewmodel.ToolMode

enum class FileAction {
    NewCanvas, Save, Load, ImportImage,
    ExportPng, ExportJpeg, ExportPsd, ExportWebP,
}

/**
 * Procreate-style thin top bar (44dp).
 * Left:  Settings gear (dropdown) + Undo + Redo
 * Right: Brush + Eraser + Selection + Fill + Layers + Color circle
 */
@Composable
fun Toolbar(
    viewModel: PaintViewModel,
    activePanel: ActivePanel,
    onBrushClick: () -> Unit,
    onColorClick: () -> Unit,
    onLayerClick: () -> Unit,
    onFilterClick: () -> Unit,
    onSelectionClick: () -> Unit = {},
    onFileAction: (FileAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val brushType by viewModel.currentBrushType.collectAsState()
    val currentColor by viewModel.currentColor.collectAsState()
    val hasSelection by viewModel.hasSelection.collectAsState()
    val toolMode by viewModel.toolMode.collectAsState()

    val isDrawMode = toolMode == ToolMode.Draw
    val isEraser = brushType == BrushType.Eraser && isDrawMode
    val isFill = brushType == BrushType.Fill && isDrawMode
    val isBrush = isDrawMode && !isEraser && !isFill
    val isSelecting = toolMode in listOf(
        ToolMode.SelectRect, ToolMode.SelectAuto,
        ToolMode.SelectPen, ToolMode.SelectEraser
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.sdp)
            .background(Color(0xDD1E1E1E))
            .padding(horizontal = 6.sdp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ══════════════ LEFT GROUP ══════════════
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Settings gear
            SettingsMenu(
                viewModel = viewModel,
                onFilterTap = onFilterClick,
                onFileAction = onFileAction,
            )
            Spacer(Modifier.width(2.sdp))
            // Undo
            ToolbarIconBtn(
                icon = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = "Undo",
                enabled = canUndo,
                onClick = { viewModel.undo() },
            )
            // Redo
            ToolbarIconBtn(
                icon = Icons.AutoMirrored.Rounded.Redo,
                contentDescription = "Redo",
                enabled = canRedo,
                onClick = { viewModel.redo() },
            )
        }

        Spacer(Modifier.weight(1f))

        // ══════════════ RIGHT GROUP ══════════════
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Brush
            ToolbarIconBtn(
                icon = Icons.Rounded.Brush,
                contentDescription = "ブラシ",
                isActive = isBrush || activePanel == ActivePanel.Brush,
                onClick = {
                    if (isEraser || isFill || !isDrawMode) {
                        viewModel.setToolMode(ToolMode.Draw)
                        if (isEraser) viewModel.setBrushType(BrushType.Pencil)
                        if (isFill) viewModel.setBrushType(BrushType.Pencil)
                    }
                    onBrushClick()
                },
            )
            // Eraser
            ToolbarIconBtn(
                icon = Icons.Rounded.AutoFixNormal,
                contentDescription = "消しゴム",
                isActive = isEraser,
                onClick = {
                    if (isEraser) {
                        viewModel.setBrushType(BrushType.Pencil)
                    } else {
                        viewModel.setToolMode(ToolMode.Draw)
                        viewModel.setBrushType(BrushType.Eraser)
                    }
                },
            )
            Spacer(Modifier.width(2.sdp))
            // Selection
            ToolbarIconBtn(
                icon = Icons.Rounded.SelectAll,
                contentDescription = "選択",
                isActive = isSelecting || activePanel == ActivePanel.Selection,
                tintOverride = if (hasSelection) Color(0xFF66BB6A) else null,
                onClick = onSelectionClick,
            )
            // Fill
            ToolbarIconBtn(
                icon = Icons.Rounded.FormatColorFill,
                contentDescription = "塗りつぶし",
                isActive = isFill,
                onClick = {
                    if (isFill) {
                        viewModel.setBrushType(BrushType.Pencil)
                    } else {
                        viewModel.setToolMode(ToolMode.Draw)
                        viewModel.setBrushType(BrushType.Fill)
                    }
                },
            )
            Spacer(Modifier.width(4.sdp))
            // Layers
            ToolbarIconBtn(
                icon = Icons.Rounded.Layers,
                contentDescription = "レイヤー",
                isActive = activePanel == ActivePanel.Layer,
                onClick = onLayerClick,
            )
            Spacer(Modifier.width(6.sdp))
            // Color circle
            Box(
                modifier = Modifier
                    .size(28.sdp)
                    .shadow(4.sdp, CircleShape)
                    .clip(CircleShape)
                    .background(currentColor)
                    .border(
                        2.5.dp,
                        if (activePanel == ActivePanel.Color) Color(0xFF6CB4EE) else Color.White,
                        CircleShape
                    )
                    .clickable { onColorClick() },
            )
            Spacer(Modifier.width(4.sdp))
        }
    }
}

/** Reusable toolbar icon with optional active pill highlight. */
@Composable
private fun ToolbarIconBtn(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    enabled: Boolean = true,
    tintOverride: Color? = null,
    onClick: () -> Unit,
) {
    val tint = when {
        tintOverride != null -> tintOverride
        isActive -> Color(0xFF6CB4EE)
        enabled -> Color.White
        else -> Color(0xFF555555)
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(36.sdp)
            .then(
                if (isActive)
                    Modifier
                        .clip(RoundedCornerShape(8.sdp))
                        .background(Color(0xFF6CB4EE).copy(alpha = 0.18f))
                else Modifier
            ),
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.sdp))
    }
}

/** Settings dropdown (gear icon). File ops + Filter + View reset. */
@Composable
private fun SettingsMenu(
    viewModel: PaintViewModel,
    onFilterTap: () -> Unit,
    onFileAction: (FileAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(36.sdp),
        ) {
            Icon(Icons.Rounded.Settings, "設定", tint = Color.White, modifier = Modifier.size(20.sdp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 0.dp),
        ) {
            SettingsMenuItem(Icons.Rounded.Add, "新規キャンバス") { expanded = false; onFileAction(FileAction.NewCanvas) }
            HorizontalDivider(color = Color(0xFF333333))
            SettingsMenuItem(Icons.Rounded.Save, "保存 (.propaint)") { expanded = false; onFileAction(FileAction.Save) }
            SettingsMenuItem(Icons.Rounded.FolderOpen, "開く (.propaint)") { expanded = false; onFileAction(FileAction.Load) }
            HorizontalDivider(color = Color(0xFF333333))
            SettingsMenuItem(Icons.Rounded.Image, "画像をインポート") { expanded = false; onFileAction(FileAction.ImportImage) }
            HorizontalDivider(color = Color(0xFF333333))
            SettingsMenuItem(null, "PNG エクスポート") { expanded = false; onFileAction(FileAction.ExportPng) }
            SettingsMenuItem(null, "JPEG エクスポート") { expanded = false; onFileAction(FileAction.ExportJpeg) }
            SettingsMenuItem(null, "WebP エクスポート") { expanded = false; onFileAction(FileAction.ExportWebP) }
            SettingsMenuItem(null, "PSD エクスポート") { expanded = false; onFileAction(FileAction.ExportPsd) }
            HorizontalDivider(color = Color(0xFF333333))
            SettingsMenuItem(Icons.Rounded.Tune, "フィルター") { expanded = false; onFilterTap() }
            SettingsMenuItem(Icons.Rounded.CenterFocusStrong, "表示リセット") { expanded = false; viewModel.resetView() }
        }
    }
}

@Composable
private fun SettingsMenuItem(icon: ImageVector?, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 13.ssp) },
        onClick = onClick,
        leadingIcon = if (icon != null) {
            { Icon(icon, null, tint = Color(0xFF6CB4EE), modifier = Modifier.size(18.sdp)) }
        } else null,
        modifier = Modifier.height(42.sdp),
    )
}

/** Panel type enum used by PaintScreen to track which panel is open. */
enum class ActivePanel {
    None, Brush, Layer, Color, Selection, Filter,
}
