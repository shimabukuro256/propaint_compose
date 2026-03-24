package com.propaint.app.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp
import com.propaint.app.viewmodel.PaintViewModel
import kotlinx.coroutines.delay

/**
 * Procreate-style minimal side sliders.
 * Two vertical sliders on the left edge: brush size (top) and opacity (bottom).
 * Slider length adapts to screen height for both portrait and landscape.
 */
@Composable
fun SideQuickBar(viewModel: PaintViewModel, modifier: Modifier = Modifier) {
    val size by viewModel.brushSize.collectAsState()
    val opacity by viewModel.brushOpacity.collectAsState()
    val config = LocalConfiguration.current
    val isPortrait = config.orientation == Configuration.ORIENTATION_PORTRAIT

    // スライダー長を画面高さに応じて動的計算
    // ツールバー(~66dp) + マージン分を引いた残りの高さの約35%ずつを各スライダーに割り当て
    val availableHeight = config.screenHeightDp.dp - 80.sdp // ツールバー + 上下マージン
    val sliderHeight = (availableHeight * 0.35f).coerceIn(120.dp, 350.dp)
    val gap = if (isPortrait) 30.sdp else 20.sdp

    Column(
        modifier = modifier.width(36.sdp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brush size slider
        MinimalVerticalSlider(
            value = size,
            onValueChange = { viewModel.setBrushSize(it) },
            valueRange = 1f..500f,
            label = "${size.toInt()}",
            sliderLength = sliderHeight,
            modifier = Modifier.height(sliderHeight),
        )

        Spacer(Modifier.height(gap))

        // Opacity slider
        MinimalVerticalSlider(
            value = opacity,
            onValueChange = { viewModel.setBrushOpacity(it) },
            valueRange = 0.01f..1f,
            label = "${(opacity * 100).toInt()}%",
            sliderLength = sliderHeight,
            modifier = Modifier.height(sliderHeight),
        )
    }
}

@Composable
private fun MinimalVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    sliderLength: Dp,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var showLabel by remember { mutableStateOf(false) }

    LaunchedEffect(isDragging) {
        if (isDragging) {
            showLabel = true
        } else {
            delay(800)
            showLabel = false
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Floating label (right side, visible only during drag)
        AnimatedVisibility(
            visible = showLabel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 38.sdp),
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xDD1E1E1E), RoundedCornerShape(5.sdp))
                    .padding(horizontal = 8.sdp, vertical = 3.sdp),
            ) {
                Text(label, color = Color.White, fontSize = 11.ssp)
            }
        }

        // Rotated slider — width must match container height for proper rotation
        Slider(
            value = value,
            onValueChange = {
                isDragging = true
                onValueChange(it)
            },
            onValueChangeFinished = { isDragging = false },
            valueRange = valueRange,
            modifier = Modifier
                .width(sliderLength)
                .rotate(-90f)
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.height, p.width) {
                        p.place(-p.width / 2 + p.height / 2, p.width / 2 - p.height / 2)
                    }
                },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}
