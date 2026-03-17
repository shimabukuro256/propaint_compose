package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.model.LayerBlendMode
import com.propaint.app.model.PaintLayer
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun LayerPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    onExportPsd: (() -> Unit)? = null,
) {
    var editingLayerId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(Color(0xFF1E1E1E)),
    ) {
        PanelHeader(title = "レイヤー", icon = Icons.Default.Layers, onClose = onClose)

        // Layer list (top layer first)
        LazyColumn(
            modifier = Modifier.weight(1f).padding(4.dp),
        ) {
            val reversed = vm.layers.reversed()
            itemsIndexed(reversed, key = { _, l -> l.id }) { _, layer ->
                val isActive = layer.id == vm.activeLayerId
                LayerTile(
                    layer         = layer,
                    isActive      = isActive,
                    isEditing     = editingLayerId == layer.id,
                    onTap         = { vm.selectLayer(layer.id) },
                    onStartEdit   = { editingLayerId = layer.id },
                    onRename      = { newName ->
                        vm.renameLayer(layer.id, newName)
                        editingLayerId = null
                    },
                    onCancelEdit  = { editingLayerId = null },
                    onVisibility  = { vm.toggleLayerVisibility(layer.id) },
                    onLock        = { vm.toggleLayerLock(layer.id) },
                    onOpacity     = { vm.setLayerOpacity(layer.id, it) },
                    onBlendMode   = { vm.setLayerBlendMode(layer.id, it) },
                    onClipMask    = { vm.toggleClippingMask(layer.id) },
                )
            }
        }

        // Bottom buttons
        HorizontalDivider(color = Color(0xFF333333))
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SmallIconBtn(Icons.Default.Add, "追加") { vm.addLayer() }
            SmallIconBtn(Icons.Default.ContentCopy, "複製") { vm.duplicateLayer(vm.activeLayerId) }
            SmallIconBtn(Icons.AutoMirrored.Filled.MergeType, "結合") { vm.mergeDown(vm.activeLayerId) }
            SmallIconBtn(Icons.Default.CleaningServices, "クリア") { vm.clearLayer(vm.activeLayerId) }
            SmallIconBtn(
                Icons.Default.Delete, "削除",
                enabled = vm.layers.size > 1,
            ) { vm.removeLayer(vm.activeLayerId) }
        }
        if (onExportPsd != null) {
            HorizontalDivider(color = Color(0xFF333333))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onExportPsd) {
                    Text("PSD出力", fontSize = 12.sp, color = Color(0xFF4A90D9))
                }
            }
        }
    }
}

@Composable
private fun LayerTile(
    layer: PaintLayer,
    isActive: Boolean,
    isEditing: Boolean,
    onTap: () -> Unit,
    onStartEdit: () -> Unit,
    onRename: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onVisibility: () -> Unit,
    onLock: () -> Unit,
    onOpacity: (Float) -> Unit,
    onBlendMode: (LayerBlendMode) -> Unit,
    onClipMask: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var editText by remember(layer.name) { mutableStateOf(layer.name) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(isEditing) {
        if (isEditing) {
            editText = layer.name
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) Color(0xFF2A3A4A) else Color.Transparent)
            .then(
                if (isActive) Modifier.border(1.dp, Color(0xFF4A90D9).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable { onTap() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Visibility
            IconButton(onClick = onVisibility, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    null,
                    tint = if (layer.isVisible) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.24f),
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            // Thumbnail
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A))
                    .border(1.dp, Color(0xFF444444), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("${layer.strokes.size}", color = Color.White.copy(alpha = 0.38f), fontSize = 10.sp)
            }

            Spacer(Modifier.width(8.dp))

            // Name and info
            Column(modifier = Modifier.weight(1f)) {
                if (isEditing) {
                    OutlinedTextField(
                        value         = editText,
                        onValueChange = { editText = it },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .focusRequester(focusRequester),
                        singleLine    = true,
                        textStyle     = LocalTextStyle.current.copy(
                            color    = Color.White,
                            fontSize = 12.sp,
                        ),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color(0xFF4A90D9),
                            unfocusedBorderColor = Color(0xFF555555),
                            cursorColor          = Color.White,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboard?.hide()
                                onRename(editText.ifBlank { layer.name })
                            },
                        ),
                    )
                } else {
                    Text(
                        layer.name,
                        color = if (isActive) Color.White else Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.SemiBold
                                     else androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.clickable(onClick = onStartEdit),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (layer.isClippingMask) {
                            Text(
                                "クリップ",
                                color    = Color(0xFF4A90D9).copy(alpha = 0.7f),
                                fontSize = 9.sp,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Text(
                            "${layer.blendMode.displayName} · ${(layer.opacity * 100).toInt()}%",
                            color = Color.White.copy(alpha = 0.38f),
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            if (layer.isLocked) {
                Icon(Icons.Default.Lock, null, tint = Color.White.copy(alpha = 0.38f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }

            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = Color.White.copy(alpha = 0.38f), modifier = Modifier.size(18.dp),
                )
            }
        }

        // Expanded settings
        if (expanded) {
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("不透明度", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
                Slider(
                    value = layer.opacity,
                    onValueChange = onOpacity,
                    modifier = Modifier.weight(1f).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF4A90D9),
                        inactiveTrackColor = Color(0xFF333333),
                    ),
                )
                Text("${(layer.opacity * 100).toInt()}%", color = Color.White.copy(alpha = 0.54f), fontSize = 10.sp,
                    modifier = Modifier.width(30.dp))
            }

            // ブレンドモード: 全6種類を2行に分けて表示
            Text("ブレンド", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            for (row in LayerBlendMode.entries.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (mode in row) {
                        val sel = mode == layer.blendMode
                        Text(
                            mode.displayName,
                            fontSize = 10.sp,
                            color = if (sel) Color.White else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (sel) Color(0xFF4A90D9) else Color(0xFF333333))
                                .clickable { onBlendMode(mode) }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // クリッピングマスク
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("クリッピング", color = Color.White.copy(alpha = 0.54f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = layer.isClippingMask,
                    onCheckedChange = { onClipMask() },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4A90D9)),
                )
            }

            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onLock, modifier = Modifier.height(28.dp)) {
                    Text(if (layer.isLocked) "解除" else "ロック", fontSize = 11.sp)
                }
                if (isEditing) {
                    TextButton(
                        onClick = onCancelEdit,
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("キャンセル", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
fun SmallIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            icon, label,
            tint = if (enabled) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.24f),
            modifier = Modifier.size(18.dp),
        )
    }
}
