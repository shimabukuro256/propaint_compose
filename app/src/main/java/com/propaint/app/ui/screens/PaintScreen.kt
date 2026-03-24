package com.propaint.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.engine.FileManager
import com.propaint.app.gl.GlCanvasView
import com.propaint.app.ui.UiScale.sdp
import com.propaint.app.ui.UiScale.ssp
import com.propaint.app.ui.components.*
import com.propaint.app.viewmodel.PaintViewModel
import com.propaint.app.viewmodel.ToolMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PaintScreen(viewModel: PaintViewModel) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var recoveryChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!recoveryChecked) {
            recoveryChecked = true
            if (viewModel.document != null) {
                // Already loaded from project
            } else if (viewModel.hasRecoveryData()) {
                showRecoveryDialog = true
            } else {
                val w = with(density) { config.screenWidthDp.dp.roundToPx() }
                val h = with(density) { config.screenHeightDp.dp.roundToPx() }
                viewModel.initCanvas(w, h)
            }
        }
    }

    // Panel visibility — only one at a time
    var activePanel by remember { mutableStateOf(ActivePanel.None) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    val toolMode by viewModel.toolMode.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    fun togglePanel(panel: ActivePanel) {
        activePanel = if (activePanel == panel) ActivePanel.None else panel
    }

    // Status toast
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatus()
        }
    }

    // ── SAF Launchers ──
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let { os -> viewModel.savePropaint(os) } }
    }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { context.contentResolver.openInputStream(it)?.let { ins -> viewModel.loadPropaint(ins) } }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { context.contentResolver.openInputStream(it)?.let { ins -> viewModel.importImage(ins) } }
    }

    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let { os ->
            viewModel.exportImage(os, FileManager.ImageFormat.PNG)
        } }
    }

    val exportJpegLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let { os ->
            viewModel.exportImage(os, FileManager.ImageFormat.JPEG, 95)
        } }
    }

    val exportWebpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/webp")
    ) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let { os ->
            viewModel.exportImage(os, FileManager.ImageFormat.WEBP)
        } }
    }

    val exportPsdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let { os -> viewModel.exportPsd(os) } }
    }

    // ── 向き別のパネル位置パラメータ ──
    val toolbarHeight = 44.sdp
    val panelTopPadding = toolbarHeight + 4.sdp
    val panelEndPadding = 6.sdp

    // ══════════════ MAIN LAYOUT ══════════════
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {

        // Canvas (full screen, behind everything)
        GlCanvasView(
            renderer = viewModel.renderer,
            onTouch = { viewModel.onTouchEvent(it) },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Top toolbar (thin, semi-transparent) ──
        Toolbar(
            viewModel = viewModel,
            activePanel = activePanel,
            onBrushClick = { togglePanel(ActivePanel.Brush) },
            onColorClick = { togglePanel(ActivePanel.Color) },
            onLayerClick = { togglePanel(ActivePanel.Layer) },
            onFilterClick = { togglePanel(ActivePanel.Filter) },
            onSelectionClick = { togglePanel(ActivePanel.Selection) },
            onFileAction = { action ->
                when (action) {
                    FileAction.NewCanvas -> showNewCanvasDialog = true
                    FileAction.Save -> saveLauncher.launch("artwork.propaint")
                    FileAction.Load -> loadLauncher.launch(arrayOf("*/*"))
                    FileAction.ImportImage -> importLauncher.launch("image/*")
                    FileAction.ExportPng -> exportPngLauncher.launch("artwork.png")
                    FileAction.ExportJpeg -> exportJpegLauncher.launch("artwork.jpg")
                    FileAction.ExportWebP -> exportWebpLauncher.launch("artwork.webp")
                    FileAction.ExportPsd -> exportPsdLauncher.launch("artwork.psd")
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // ── Left side sliders ──
        SideQuickBar(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.sdp),
        )

        // ── Tool mode label ──
        val toolLabel = when (toolMode) {
            ToolMode.Eyedropper -> "スポイト"
            ToolMode.SelectRect -> "矩形選択"
            ToolMode.SelectAuto -> "自動選択"
            ToolMode.SelectPen -> "選択ペン"
            ToolMode.SelectEraser -> "選択消し"
            else -> null
        }
        if (toolLabel != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = panelTopPadding + 4.sdp)
                    .background(Color(0xBB1E1E1E), shape = RoundedCornerShape(6.sdp))
                    .padding(horizontal = 14.sdp, vertical = 5.sdp),
            ) {
                Text(toolLabel, color = Color(0xFFCCCCCC), fontSize = 12.ssp)
            }
        }

        // ── Busy indicator ──
        if (isBusy) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), shape = RoundedCornerShape(12.sdp))
                    .padding(24.sdp),
            ) {
                Text("処理中...", color = Color.White, fontSize = 16.ssp)
            }
        }

        // ══════════════ ANIMATED PANELS ══════════════

        // Brush panel — slide in from right
        AnimatedVisibility(
            visible = activePanel == ActivePanel.Brush,
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = panelTopPadding, end = panelEndPadding),
        ) {
            BrushPanel(viewModel, { activePanel = ActivePanel.None })
        }

        // Color picker — slide in from bottom
        AnimatedVisibility(
            visible = activePanel == ActivePanel.Color,
            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.sdp),
        ) {
            ColorPickerPanel(viewModel, { activePanel = ActivePanel.None })
        }

        // Layer panel — slide in from right
        AnimatedVisibility(
            visible = activePanel == ActivePanel.Layer,
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = panelTopPadding, end = panelEndPadding),
        ) {
            LayerPanel(viewModel, { activePanel = ActivePanel.None })
        }

        // Filter panel — slide in from top
        AnimatedVisibility(
            visible = activePanel == ActivePanel.Filter,
            enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = panelTopPadding),
        ) {
            FilterPanel(viewModel, { activePanel = ActivePanel.None })
        }

        // Selection panel — slide in from right
        AnimatedVisibility(
            visible = activePanel == ActivePanel.Selection,
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = panelTopPadding, end = panelEndPadding),
        ) {
            SelectionPanel(viewModel, { activePanel = ActivePanel.None })
        }

        // ── New canvas dialog ──
        if (showNewCanvasDialog) {
            NewCanvasDialog(
                onConfirm = { w, h ->
                    viewModel.newCanvas(w, h)
                    showNewCanvasDialog = false
                },
                onDismiss = { showNewCanvasDialog = false },
            )
        }

        // ── Recovery dialog ──
        if (showRecoveryDialog) {
            val timestamp = viewModel.getRecoveryTimestamp()
            val timeStr = if (timestamp > 0) {
                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            } else "不明"

            AlertDialog(
                onDismissRequest = {},
                title = { Text("キャンバスの復旧") },
                text = { Text("前回の作業データが見つかりました。\n保存日時: $timeStr\n\n復旧しますか？") },
                confirmButton = {
                    TextButton(onClick = {
                        showRecoveryDialog = false
                        viewModel.recoverCanvas()
                    }) { Text("復旧する") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showRecoveryDialog = false
                        viewModel.discardRecovery()
                        val w = with(density) { config.screenWidthDp.dp.roundToPx() }
                        val h = with(density) { config.screenHeightDp.dp.roundToPx() }
                        if (viewModel.document == null) viewModel.initCanvas(w, h)
                    }) { Text("破棄して新規作成") }
                },
            )
        }
    }
}
