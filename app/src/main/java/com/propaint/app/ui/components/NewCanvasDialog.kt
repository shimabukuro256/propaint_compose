package com.propaint.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp

@Composable
fun NewCanvasDialog(onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var widthText by remember { mutableStateOf("2048") }
    var heightText by remember { mutableStateOf("2048") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF222222),
        title = { Text("新規キャンバス", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.sdp)) {
                OutlinedTextField(
                    value = widthText, onValueChange = { widthText = it.filter { c -> c.isDigit() } },
                    label = { Text("幅 (px)", color = Color(0xFFAAAAAA), fontSize = 12.ssp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6CB4EE), unfocusedBorderColor = Color(0xFF555555),
                    ),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = heightText, onValueChange = { heightText = it.filter { c -> c.isDigit() } },
                    label = { Text("高さ (px)", color = Color(0xFFAAAAAA), fontSize = 12.ssp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6CB4EE), unfocusedBorderColor = Color(0xFF555555),
                    ),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                // プリセット
                Row(horizontalArrangement = Arrangement.spacedBy(8.sdp)) {
                    for ((label, w, h) in listOf(
                        Triple("1080p", 1920, 1080),
                        Triple("2K", 2048, 2048),
                        Triple("4K", 4096, 4096),
                    )) {
                        FilterChip(
                            selected = false,
                            onClick = { widthText = w.toString(); heightText = h.toString() },
                            label = { Text(label, fontSize = 11.ssp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF333333),
                                labelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = widthText.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
                    val h = heightText.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
                    onConfirm(w, h)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C)),
            ) { Text("作成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル", color = Color(0xFFAAAAAA)) }
        },
    )
}
