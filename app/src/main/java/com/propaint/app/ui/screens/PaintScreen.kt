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

@Composable
fun PaintScreen(vm: PaintViewModel = viewModel()) {
    var showBrush by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }

    fun closeAll() { showBrush = false; showColor = false; showLayers = false }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top toolbar
        TopToolbar(
            vm = vm,
            onBrushPanel = {
                showBrush = !showBrush; showColor = false; showLayers = false
            },
            onColorPicker = {
                showColor = !showColor; showBrush = false; showLayers = false
            },
            onLayerPanel = {
                showLayers = !showLayers; showBrush = false; showColor = false
            },
        )

        // Main content
        Row(modifier = Modifier.weight(1f)) {
            // Left panel: brush or color
            AnimatedVisibility(
                visible = showBrush,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
            ) {
                BrushPanel(vm = vm, onClose = { showBrush = false })
            }

            AnimatedVisibility(
                visible = showColor,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
            ) {
                ColorPickerPanel(vm = vm, onClose = { showColor = false })
            }

            // Canvas (fills remaining space)
            Box(modifier = Modifier.weight(1f)) {
                DrawingCanvas(viewModel = vm)

                // Side quick bar (floating left)
                SideQuickBar(
                    vm = vm,
                    modifier = Modifier.align(Alignment.CenterStart)
                        .padding(start = 8.dp),
                )
            }

            // Right panel: layers
            AnimatedVisibility(
                visible = showLayers,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                LayerPanel(vm = vm, onClose = { showLayers = false })
            }
        }
    }
}
