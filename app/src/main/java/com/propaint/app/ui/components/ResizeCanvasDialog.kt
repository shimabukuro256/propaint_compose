package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp

/**
 * アンカー位置: キャンバスリサイズ時にコンテンツを配置する位置。
 */
enum class AnchorPosition(val label: String, val offsetX: Float, val offsetY: Float) {
    TopLeft("↖", 0f, 0f),
    TopCenter("↑", 0.5f, 0f),
    TopRight("↗", 1f, 0f),
    CenterLeft("←", 0f, 0.5f),
    Center("●", 0.5f, 0.5f),
    CenterRight("→", 1f, 0.5f),
    BottomLeft("↙", 0f, 1f),
    BottomCenter("↓", 0.5f, 1f),
    BottomRight("↘", 1f, 1f),
}

@Composable
fun ResizeCanvasDialog(
    currentWidth: Int,
    currentHeight: Int,
    onConfirm: (newWidth: Int, newHeight: Int, anchor: AnchorPosition) -> Unit,
    onDismiss: () -> Unit,
) {
    var widthText by remember { mutableStateOf(currentWidth.toString()) }
    var heightText by remember { mutableStateOf(currentHeight.toString()) }
    var anchor by remember { mutableStateOf(AnchorPosition.Center) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF222222),
        title = { Text("キャンバスリサイズ", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.sdp)) {
                // サイズ入力
                Row(horizontalArrangement = Arrangement.spacedBy(8.sdp)) {
                    OutlinedTextField(
                        value = widthText, onValueChange = { widthText = it.filter { c -> c.isDigit() } },
                        label = { Text("幅", color = Color(0xFFAAAAAA), fontSize = 11.ssp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF6CB4EE), unfocusedBorderColor = Color(0xFF555555),
                        ),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = heightText, onValueChange = { heightText = it.filter { c -> c.isDigit() } },
                        label = { Text("高さ", color = Color(0xFFAAAAAA), fontSize = 11.ssp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF6CB4EE), unfocusedBorderColor = Color(0xFF555555),
                        ),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }

                // プリセット
                Text("プリセット", color = Color(0xFF6CB4EE), fontSize = 11.ssp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.sdp)) {
                    for ((label, w, h) in listOf(
                        Triple("A4", 2480, 3508),
                        Triple("1080p", 1920, 1080),
                        Triple("2K", 2048, 2048),
                        Triple("4K", 4096, 4096),
                        Triple("正方形", 2048, 2048),
                    )) {
                        FilterChip(
                            selected = false,
                            onClick = { widthText = w.toString(); heightText = h.toString() },
                            label = { Text(label, fontSize = 10.ssp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF333333), labelColor = Color.White,
                            ),
                        )
                    }
                }

                // アンカーグリッド (3x3)
                Text("アンカー位置", color = Color(0xFF6CB4EE), fontSize = 11.ssp)
                val grid = listOf(
                    listOf(AnchorPosition.TopLeft, AnchorPosition.TopCenter, AnchorPosition.TopRight),
                    listOf(AnchorPosition.CenterLeft, AnchorPosition.Center, AnchorPosition.CenterRight),
                    listOf(AnchorPosition.BottomLeft, AnchorPosition.BottomCenter, AnchorPosition.BottomRight),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    for (row in grid) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.sdp)) {
                            for (pos in row) {
                                val sel = anchor == pos
                                Box(
                                    modifier = Modifier.size(32.sdp).clip(RoundedCornerShape(4.sdp))
                                        .background(if (sel) Color(0xFF3A5A7C) else Color(0xFF333333))
                                        .border(1.dp, if (sel) Color(0xFF6CB4EE) else Color(0xFF555555), RoundedCornerShape(4.sdp))
                                        .clickable { anchor = pos },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(pos.label, color = Color.White, fontSize = 12.ssp)
                                }
                            }
                        }
                    }
                }

                Text("現在: ${currentWidth}×${currentHeight}", color = Color(0xFF888888), fontSize = 10.ssp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = widthText.toIntOrNull()?.coerceIn(64, 8192) ?: currentWidth
                    val h = heightText.toIntOrNull()?.coerceIn(64, 8192) ?: currentHeight
                    onConfirm(w, h, anchor)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C)),
            ) { Text("リサイズ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル", color = Color(0xFFAAAAAA)) }
        },
    )
}
