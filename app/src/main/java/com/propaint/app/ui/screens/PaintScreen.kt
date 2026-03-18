package com.propaint.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.propaint.app.ui.components.*
import com.propaint.app.viewmodel.PaintViewModel

// TopBar の高さ: 行1(44dp) + 区切り(0.5dp) + 行2(50dp) ≈ 95dp
private val TOP_BAR_HEIGHT = 95.dp

@Composable
fun PaintScreen(vm: PaintViewModel = viewModel(), onGallery: () -> Unit = {}) {
    var showBrush  by remember { mutableStateOf(false) }
    var showColor  by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showShare  by remember { mutableStateOf(false) }
    var isSavingForExit by remember { mutableStateOf(false) }

    // ギャラリーへ戻る前に保存してから遷移
    fun saveAndGoGallery() {
        isSavingForExit = true
        showBrush = false; showColor = false; showLayers = false; showFilter = false; showShare = false
        vm.requestSaveCanvas()
    }

    BackHandler(enabled = !isSavingForExit) { saveAndGoGallery() }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── キャンバス (画面全体) ────────────────────────────────────────
        DrawingCanvas(
            viewModel = vm,
            onSaveComplete = { if (isSavingForExit) onGallery() },
            modifier = Modifier.fillMaxSize(),
        )

        // ── 浮遊 TopBar (2 行) ───────────────────────────────────────────
        TopBar(
            vm            = vm,
            onColorPicker = {
                showColor = !showColor
                showBrush = false; showLayers = false; showFilter = false; showShare = false
                if (vm.isEyedropperActive) vm.deactivateEyedropper()
            },
            onLayerPanel  = {
                showLayers = !showLayers
                showBrush = false; showColor = false; showFilter = false; showShare = false
                if (vm.isEyedropperActive) vm.deactivateEyedropper()
            },
            onBrushPanel  = {
                showBrush = !showBrush
                showColor = false; showLayers = false; showFilter = false; showShare = false
                if (vm.isEyedropperActive) vm.deactivateEyedropper()
            },
            onFilterPanel = {
                showFilter = !showFilter
                showBrush = false; showColor = false; showLayers = false; showShare = false
                if (vm.isEyedropperActive) vm.deactivateEyedropper()
            },
            onSharePanel  = {
                showShare = !showShare
                showBrush = false; showColor = false; showLayers = false; showFilter = false
                if (vm.isEyedropperActive) vm.deactivateEyedropper()
            },
            onGallery     = { saveAndGoGallery() },
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
            LayerPanel(
                vm          = vm,
                onClose     = { showLayers = false },
                onExportPsd = { vm.requestPsdExport() },
            )
        }

        // ── フィルターパネル (下からスライドイン) ─────────────────────────
        AnimatedVisibility(
            visible  = showFilter,
            enter    = slideInVertically { it },
            exit     = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FilterPanel(
                vm      = vm,
                onClose = { showFilter = false },
            )
        }

        // ── 共有シート (下からスライドイン) ──────────────────────────────
        AnimatedVisibility(
            visible  = showShare,
            enter    = slideInVertically { it },
            exit     = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ShareSheet(vm = vm, onClose = { showShare = false })
        }

        // ── 保存中オーバーレイ ────────────────────────────────────────────
        if (isSavingForExit) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("保存中…", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ShareSheet(vm: PaintViewModel, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "共有",
            color      = Color.White,
            fontSize   = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.padding(vertical = 12.dp),
        )
        TextButton(
            onClick = { vm.requestExport(PaintViewModel.ExportType.PNG); onClose() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("PNG として共有", color = Color.White, fontSize = 15.sp) }
        TextButton(
            onClick = { vm.requestExport(PaintViewModel.ExportType.JPEG); onClose() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("JPEG として共有", color = Color.White, fontSize = 15.sp) }
        TextButton(
            onClick = { vm.requestExport(PaintViewModel.ExportType.WEBP); onClose() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("WebP として共有", color = Color.White, fontSize = 15.sp) }
        TextButton(
            onClick = { vm.requestExport(PaintViewModel.ExportType.PSD); onClose() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("PSD として共有", color = Color.White, fontSize = 15.sp) }
        TextButton(
            onClick = { vm.requestExport(PaintViewModel.ExportType.PPAINT); onClose() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("ProPaint 形式で共有 (.ppaint)", color = Color.White, fontSize = 15.sp) }
        Spacer(Modifier.height(8.dp))
    }
}
