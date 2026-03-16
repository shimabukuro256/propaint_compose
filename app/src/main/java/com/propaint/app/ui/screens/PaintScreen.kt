package com.propaint.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.propaint.app.ui.components.*
import com.propaint.app.viewmodel.PaintViewModel

// TopBar の高さ: 行1(44dp) + 区切り(0.5dp) + 行2(50dp) ≈ 95dp
private val TOP_BAR_HEIGHT = 95.dp

@Composable
fun PaintScreen(vm: PaintViewModel = viewModel()) {
    var showBrush  by remember { mutableStateOf(false) }
    var showColor  by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── キャンバス (画面全体) ────────────────────────────────────────
        DrawingCanvas(viewModel = vm, modifier = Modifier.fillMaxSize())

        // ── 浮遊 TopBar (2 行) ───────────────────────────────────────────
        TopBar(
            vm            = vm,
            onColorPicker = { showColor = !showColor;  showBrush = false; showLayers = false },
            onLayerPanel  = { showLayers = !showLayers; showBrush = false; showColor = false },
            onBrushPanel  = { showBrush = !showBrush;  showColor = false; showLayers = false },
            modifier      = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        )

        // ── 左サイドクイックバー (TopBar の下に配置・重ならないよう) ───
        SideQuickBar(
            vm       = vm,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = TOP_BAR_HEIGHT, start = 0.dp)
                .fillMaxHeight(0.65f),
        )

        // ── ブラシパネル (左からスライドイン) ────────────────────────────
        AnimatedVisibility(
            visible  = showBrush,
            enter    = slideInHorizontally { -it },
            exit     = slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = TOP_BAR_HEIGHT),
        ) {
            BrushPanel(vm = vm, onClose = { showBrush = false })
        }

        // ── カラーピッカー (左からスライドイン) ──────────────────────────
        AnimatedVisibility(
            visible  = showColor,
            enter    = slideInHorizontally { -it },
            exit     = slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = TOP_BAR_HEIGHT),
        ) {
            ColorPickerPanel(vm = vm, onClose = { showColor = false })
        }

        // ── レイヤーパネル (右からスライドイン) ──────────────────────────
        AnimatedVisibility(
            visible  = showLayers,
            enter    = slideInHorizontally { it },
            exit     = slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = TOP_BAR_HEIGHT),
        ) {
            LayerPanel(vm = vm, onClose = { showLayers = false })
        }
    }
}
