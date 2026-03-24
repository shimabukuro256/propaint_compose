package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.engine.PixelOps
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp
import com.propaint.app.viewmodel.PaintViewModel
import com.propaint.app.viewmodel.UiLayer

@Composable
fun LayerPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val layers by viewModel.layers.collectAsState()

    Box(
        modifier = modifier
            .width(270.sdp)
            .heightIn(max = 450.sdp)
            .shadow(16.sdp, RoundedCornerShape(14.sdp))
            .clip(RoundedCornerShape(14.sdp))
            .background(Color(0xF01E1E1E)),
    ) {
        Column {
            // ── Header ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.sdp, end = 8.sdp, top = 12.sdp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("レイヤー", color = Color.White, fontSize = 13.ssp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.sdp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Add layer button
                    Box(
                        modifier = Modifier
                            .size(28.sdp)
                            .clip(RoundedCornerShape(6.sdp))
                            .background(Color(0xFF6CB4EE).copy(alpha = 0.15f))
                            .clickable { viewModel.addLayer() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, "Add", tint = Color(0xFF6CB4EE), modifier = Modifier.size(16.sdp))
                    }
                    PanelCloseButton(onDismiss)
                }
            }
            Spacer(Modifier.height(6.sdp))

            // ── Layer list ──
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 10.sdp, vertical = 0.dp),
                verticalArrangement = Arrangement.spacedBy(4.sdp),
                modifier = Modifier.padding(bottom = 10.sdp),
            ) {
                itemsIndexed(layers.reversed()) { _, layer ->
                    LayerItem(layer, viewModel)
                }
            }
        }
    }
}

@Composable
private fun LayerItem(layer: UiLayer, vm: PaintViewModel) {
    val bg = if (layer.isActive) Color(0xFF3A5A7C) else Color(0xFF2A2A2A)
    val borderColor = if (layer.isActive) Color(0xFF6CB4EE) else Color.Transparent
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.sdp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.sdp))
            .clickable { vm.selectLayer(layer.id) }
            .padding(8.sdp),
    ) {
        // ── Main row ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Visibility
            MiniIconBtn(
                icon = if (layer.isVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                tint = if (layer.isVisible) Color.White else Color(0xFF555555),
                size = 15.sdp,
                onClick = { vm.setLayerVisibility(layer.id, !layer.isVisible) },
            )
            // Clip
            MiniIconBtn(
                icon = Icons.Rounded.ContentCut,
                tint = if (layer.isClipToBelow) Color(0xFF6CB4EE) else Color(0xFF444444),
                size = 13.sdp,
                onClick = { vm.setLayerClip(layer.id, !layer.isClipToBelow) },
            )
            // Lock
            MiniIconBtn(
                icon = if (layer.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                tint = if (layer.isLocked) Color(0xFFFF6B6B) else Color(0xFF444444),
                size = 13.sdp,
                onClick = { vm.setLayerLocked(layer.id, !layer.isLocked) },
            )

            Spacer(Modifier.width(4.sdp))
            // Name
            Text(
                layer.name, color = Color.White, fontSize = 12.ssp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            // Opacity %
            Text("${(layer.opacity * 100).toInt()}%", color = Color(0xFFAAAAAA), fontSize = 10.ssp)
            // Expand toggle
            MiniIconBtn(
                icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                tint = Color(0xFF888888),
                size = 14.sdp,
                onClick = { expanded = !expanded },
            )
        }

        // ── Expanded details ──
        if (expanded) {
            Spacer(Modifier.height(6.sdp))
            // Opacity slider
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("不透明度", color = Color(0xFFAAAAAA), fontSize = 10.ssp, modifier = Modifier.width(50.sdp))
                Slider(
                    value = layer.opacity,
                    onValueChange = { vm.setLayerOpacity(layer.id, it) },
                    modifier = Modifier.weight(1f).height(24.sdp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF6CB4EE),
                        inactiveTrackColor = Color(0xFF3A3A3A),
                    ),
                )
            }

            // Blend mode
            var showMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("合成", color = Color(0xFFAAAAAA), fontSize = 10.ssp, modifier = Modifier.width(50.sdp))
                Box {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.sdp))
                            .background(Color(0xFF333333))
                            .clickable { showMenu = true }
                            .padding(horizontal = 8.sdp, vertical = 3.sdp),
                    ) {
                        val name = if (layer.blendMode < PixelOps.BLEND_MODE_NAMES.size)
                            PixelOps.BLEND_MODE_NAMES[layer.blendMode] else "通常"
                        Text(name, color = Color.White, fontSize = 11.ssp)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        for (mode in listOf(
                            PixelOps.BLEND_NORMAL, PixelOps.BLEND_MULTIPLY,
                            PixelOps.BLEND_SCREEN, PixelOps.BLEND_OVERLAY,
                            PixelOps.BLEND_DARKEN, PixelOps.BLEND_LIGHTEN,
                            PixelOps.BLEND_ADD, PixelOps.BLEND_SUBTRACT,
                            PixelOps.BLEND_SOFT_LIGHT, PixelOps.BLEND_HARD_LIGHT,
                            PixelOps.BLEND_DIFFERENCE, PixelOps.BLEND_MARKER,
                        )) {
                            DropdownMenuItem(
                                text = { Text(PixelOps.BLEND_MODE_NAMES[mode], fontSize = 12.ssp) },
                                onClick = { vm.setLayerBlendMode(layer.id, mode); showMenu = false },
                                modifier = Modifier.height(36.sdp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.sdp))

            // Action buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.sdp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniIconBtn(Icons.Rounded.KeyboardArrowUp, Color(0xFF6CB4EE), 16.sdp) { vm.moveLayerUp(layer.id) }
                MiniIconBtn(Icons.Rounded.KeyboardArrowDown, Color(0xFF6CB4EE), 16.sdp) { vm.moveLayerDown(layer.id) }
                MiniTextBtn("複製") { vm.duplicateLayer(layer.id) }
                MiniTextBtn("下に結合") { vm.mergeDown(layer.id) }
                Spacer(Modifier.weight(1f))
                MiniIconBtn(Icons.Rounded.Delete, Color(0xFFFF6B6B), 14.sdp) { vm.removeLayer(layer.id) }
            }

            // Transform buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.sdp),
            ) {
                TransformBtn(Icons.Rounded.Flip, "左右反転") { vm.flipLayerHorizontal(layer.id) }
                TransformBtn(Icons.Rounded.Flip, "上下反転") { vm.flipLayerVertical(layer.id) }
            }
        }
    }
}

@Composable
private fun MiniIconBtn(
    icon: ImageVector,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.sdp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
private fun MiniTextBtn(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 6.sdp, vertical = 2.sdp),
    ) {
        Text(text, fontSize = 10.ssp, color = Color(0xFF6CB4EE))
    }
}

@Composable
private fun TransformBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 4.sdp, vertical = 2.sdp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF6CB4EE), modifier = Modifier.size(12.sdp))
            Spacer(Modifier.width(2.sdp))
            Text(label, fontSize = 10.ssp, color = Color(0xFF6CB4EE))
        }
    }
}
