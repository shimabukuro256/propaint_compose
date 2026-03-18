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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.model.FilterType
import com.propaint.app.model.LayerFilter
import com.propaint.app.viewmodel.PaintViewModel
import kotlin.math.roundToInt

@Composable
fun FilterPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
) {
    // 既存フィルターがあれば初期値として使用
    val existingFilter = remember { vm.activeLayer?.filter }

    var selectedType by remember {
        mutableStateOf(existingFilter?.type ?: FilterType.HSL)
    }
    var hue        by remember { mutableFloatStateOf(existingFilter?.hue        ?: 0f) }
    var saturation by remember { mutableFloatStateOf(existingFilter?.saturation ?: 0f) }
    var lightness  by remember { mutableFloatStateOf(existingFilter?.lightness  ?: 0f) }
    var blurRadius by remember { mutableFloatStateOf(existingFilter?.blurRadius ?: 0f) }
    var contrast   by remember { mutableFloatStateOf(existingFilter?.contrast   ?: 0f) }
    var brightness by remember { mutableFloatStateOf(existingFilter?.brightness ?: 0f) }

    // パネルが開いた直後に VM にプレビューをセット
    LaunchedEffect(Unit) {
        vm.setFilterPreview(buildFilter(selectedType, hue, saturation, lightness, blurRadius, contrast, brightness))
    }

    fun pushPreview() {
        vm.setFilterPreview(buildFilter(selectedType, hue, saturation, lightness, blurRadius, contrast, brightness))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF0141414))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── タブ行 ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterTab("色相/彩度/明度", selectedType == FilterType.HSL) {
                selectedType = FilterType.HSL; pushPreview()
            }
            FilterTab("ぼかし", selectedType == FilterType.BLUR) {
                selectedType = FilterType.BLUR; pushPreview()
            }
            FilterTab("コントラスト", selectedType == FilterType.CONTRAST) {
                selectedType = FilterType.CONTRAST; pushPreview()
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(12.dp))

        // ── スライダー ─────────────────────────────────────────────────────
        when (selectedType) {
            FilterType.HSL -> {
                FilterSliderRow("色相", hue, -180f, 180f, "°") { hue = it; pushPreview() }
                FilterSliderRow("彩度", saturation, -1f, 1f, "") { saturation = it; pushPreview() }
                FilterSliderRow("明度", lightness,  -1f, 1f, "") { lightness  = it; pushPreview() }
            }
            FilterType.BLUR -> {
                FilterSliderRow("半径", blurRadius, 0f, 1f, "") { blurRadius = it; pushPreview() }
            }
            FilterType.CONTRAST -> {
                FilterSliderRow("コントラスト", contrast,   -1f, 1f, "") { contrast   = it; pushPreview() }
                FilterSliderRow("明るさ",       brightness, -1f, 1f, "") { brightness = it; pushPreview() }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(10.dp))

        // ── ボタン行 ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { vm.cancelFilter(); onClose() },
                colors  = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f)),
            ) { Text("キャンセル") }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = { vm.applyFilter(); onClose() },
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90D9)),
                shape   = RoundedCornerShape(6.dp),
            ) { Text("適用", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FilterTab(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected) Color(0xFF4A90D9).copy(alpha = 0.25f) else Color(0xFF1E1E1E),
            )
            .border(
                1.dp,
                if (isSelected) Color(0xFF4A90D9) else Color(0xFF333333),
                RoundedCornerShape(6.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (isSelected) Color(0xFF6AB0FF) else Color.White.copy(alpha = 0.55f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun FilterSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    unit: String,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color    = Color.White.copy(alpha = 0.75f),
            fontSize = 12.sp,
            modifier = Modifier.width(70.dp),
        )
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = min..max,
            modifier      = Modifier.weight(1f),
            colors        = SliderDefaults.colors(
                thumbColor       = Color(0xFF4A90D9),
                activeTrackColor = Color(0xFF4A90D9),
                inactiveTrackColor = Color(0xFF333333),
            ),
        )
        val display = if (unit == "°") "${value.roundToInt()}°"
                      else "%.2f".format(value)
        Text(
            display,
            color    = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            modifier = Modifier.width(48.dp).padding(start = 6.dp),
        )
    }
}

private fun buildFilter(
    type: FilterType,
    hue: Float, saturation: Float, lightness: Float,
    blurRadius: Float, contrast: Float, brightness: Float,
) = LayerFilter(
    type       = type,
    hue        = hue,
    saturation = saturation,
    lightness  = lightness,
    blurRadius = blurRadius,
    contrast   = contrast,
    brightness = brightness,
)
