package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

private val brushIcons = mapOf(
    BrushType.Pencil to Icons.Default.Edit,
    BrushType.Pen to Icons.Default.Create,
    BrushType.Marker to Icons.Default.FormatPaint,
    BrushType.Airbrush to Icons.Default.BlurOn,
    BrushType.Watercolor to Icons.Default.WaterDrop,
    BrushType.Crayon to Icons.Default.Gesture,
    BrushType.Calligraphy to Icons.Default.FontDownload,
    BrushType.Eraser to Icons.Default.AutoFixHigh,
)

@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(Color(0xFF1E1E1E)),
    ) {
        // Header
        PanelHeader(title = "ブラシ設定", icon = Icons.Default.Brush, onClose = onClose)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            // Brush type grid
            BrushTypeGrid(
                selected = vm.brushSettings.type,
                onSelect = { vm.selectBrushType(it) },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                vm.brushSettings.type.displayName,
                color = Color(0xFF4A90D9),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )

            Spacer(Modifier.height(16.dp))

            // Size
            LabeledSlider(
                label = "サイズ",
                value = vm.brushSettings.size,
                range = 1f..200f,
                display = "${vm.brushSettings.size.toInt()}px",
                onValueChange = { vm.setBrushSize(it) },
            )

            // Opacity
            LabeledSlider(
                label = "不透明度",
                value = vm.brushSettings.opacity,
                range = 0.01f..1f,
                display = "${(vm.brushSettings.opacity * 100).toInt()}%",
                onValueChange = { vm.setBrushOpacity(it) },
            )

            // Density
            LabeledSlider(
                label = "ブラシ濃度",
                value = vm.brushSettings.density,
                range = 0.01f..1f,
                display = "${(vm.brushSettings.density * 100).toInt()}%",
                onValueChange = { vm.setBrushDensity(it) },
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(12.dp))

            // Pressure settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, tint = Color(0xFF4A90D9), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("筆圧設定", color = Color(0xFF4A90D9), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            PressureToggleCard(
                label = "筆圧 → サイズ",
                subtitle = "強く押すと太く描ける",
                checked = vm.brushSettings.pressureSizeEnabled,
                onToggle = { vm.togglePressureSize() },
            )

            if (vm.brushSettings.pressureSizeEnabled) {
                LabeledSlider(
                    label = "最小サイズ比",
                    value = vm.brushSettings.minSizeRatio,
                    range = 0.01f..0.9f,
                    display = "${(vm.brushSettings.minSizeRatio * 100).toInt()}%",
                    onValueChange = {
                        vm.setBrush(vm.brushSettings.copy(minSizeRatio = it))
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            PressureToggleCard(
                label = "筆圧 → 不透明度",
                subtitle = "強く押すと濃く描ける",
                checked = vm.brushSettings.pressureOpacityEnabled,
                onToggle = { vm.togglePressureOpacity() },
            )

            if (vm.brushSettings.type == BrushType.Watercolor) {
                Spacer(Modifier.height(4.dp))
                PressureToggleCard(
                    label = "筆圧 → 混色",
                    subtitle = "強く押すと顔料が濃く混ざる",
                    checked = vm.brushSettings.pressureBlendEnabled,
                    onToggle = { vm.togglePressureBlend() },
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF333333))
                Spacer(Modifier.height(12.dp))

                LabeledSlider(
                    label = "スタンプ間隔",
                    value = vm.brushSettings.spacing,
                    range = 0.1f..5.0f,
                    display = "×${"%.2f".format(vm.brushSettings.spacing)}",
                    onValueChange = { vm.setBrushSpacing(it) },
                )
            }
        }
    }
}

@Composable
private fun BrushTypeGrid(selected: BrushType, onSelect: (BrushType) -> Unit) {
    val types = BrushType.entries
    Column {
        for (row in types.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (type in row) {
                    val isSelected = type == selected
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color(0xFF4A90D9) else Color(0xFF2A2A2A)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF6AB0FF) else Color(0xFF444444),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(type) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            brushIcons[type] ?: Icons.Default.Brush,
                            contentDescription = type.displayName,
                            tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(display, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF4A90D9),
                inactiveTrackColor = Color(0xFF333333),
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
fun PressureToggleCard(
    label: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) Color(0xFF4A90D9).copy(alpha = 0.12f) else Color(0xFF2A2A2A))
            .border(
                1.dp,
                if (checked) Color(0xFF4A90D9).copy(alpha = 0.4f) else Color(0xFF3A3A3A),
                RoundedCornerShape(8.dp),
            )
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint = if (checked) Color(0xFF4A90D9) else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = if (checked) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.38f), fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.height(24.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF4A90D9)),
        )
    }
}

@Composable
fun PanelHeader(title: String, icon: ImageVector, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, "閉じる", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
    HorizontalDivider(color = Color(0xFF333333))
}
