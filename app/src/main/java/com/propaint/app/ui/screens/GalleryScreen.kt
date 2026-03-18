package com.propaint.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.io.CanvasFileManager
import com.propaint.app.io.CanvasMeta
import com.propaint.app.io.PsdImporter
import com.propaint.app.viewmodel.PaintViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// カラーパレット
// ─────────────────────────────────────────────────────────────────────────────
private val BG        = Color(0xFF1C1C1E)
private val SURFACE   = Color(0xFF2C2C2E)
private val CARD      = Color(0xFF3A3A3C)
private val ACCENT    = Color(0xFF4A90D9)
private val TEXT      = Color(0xFFEEEEEE)
private val TEXT_SUB  = Color(0xFF8E8E93)
private val DIVIDER   = Color(0xFF3A3A3C)

@Composable
fun GalleryScreen(
    vm: PaintViewModel,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var canvasList by remember { mutableStateOf<List<CanvasMeta>>(emptyList()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId   by remember { mutableStateOf("") }
    var renameTargetName by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        canvasList = withContext(Dispatchers.IO) { CanvasFileManager.listCanvases(context) }
    }

    fun refreshList() {
        coroutineScope.launch {
            canvasList = withContext(Dispatchers.IO) { CanvasFileManager.listCanvases(context) }
        }
    }

    fun createNew() {
        val title = CanvasFileManager.nextAutoTitle(canvasList)
        vm.newCanvas(title)
        onOpen()
    }

    // ── ファイルインポートランチャー ──────────────────────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImporting = true
        coroutineScope.launch {
            val fileName = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                } ?: "インポート"
            }
            val baseName = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "").lowercase()

            when (ext) {
                "ppaint" -> {
                    val data = withContext(Dispatchers.IO) {
                        CanvasFileManager.importPpaint(context, uri)
                    }
                    isImporting = false
                    if (data != null) {
                        vm.loadFromCanvasData(data)
                        onOpen()
                    }
                }
                "psd" -> {
                    val bitmap = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { PsdImporter.decode(it) }
                    }
                    isImporting = false
                    if (bitmap != null) {
                        val scaled = scaleBitmapIfNeeded(bitmap, 2048)
                        vm.importBitmap(scaled, baseName)
                        if (scaled !== bitmap) bitmap.recycle()
                        onOpen()
                    }
                }
                else -> {
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        } catch (_: Exception) { null }
                    }
                    isImporting = false
                    if (bitmap != null) {
                        val scaled = scaleBitmapIfNeeded(bitmap, 2048)
                        vm.importBitmap(scaled, baseName)
                        if (scaled !== bitmap) bitmap.recycle()
                        onOpen()
                    }
                }
            }
        }
    }

    // ── リネームダイアログ ────────────────────────────────────────────────
    if (showRenameDialog) {
        RenameDialog(
            initialTitle = renameTargetName,
            onConfirm    = { newTitle ->
                showRenameDialog = false
                val targetId = renameTargetId
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        CanvasFileManager.renameCanvas(context, targetId, newTitle)
                    }
                    if (vm.canvasId == targetId) vm.setCanvasTitleDirectly(newTitle)
                    refreshList()
                }
            },
            onDismiss    = { showRenameDialog = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── ヘッダー ──────────────────────────────────────────────────────
            GalleryHeader(
                count       = canvasList.size,
                onImport    = {
                    importLauncher.launch(
                        arrayOf(
                            "image/png", "image/jpeg", "image/webp", "image/gif",
                            "image/bmp", "image/tiff", "image/heic", "image/heif",
                            "application/octet-stream",
                        )
                    )
                },
                onNewCanvas = { createNew() },
            )

            HorizontalDivider(color = DIVIDER, thickness = 0.5.dp)

            // ── コンテンツ ────────────────────────────────────────────────────
            if (canvasList.isEmpty()) {
                EmptyState(onNew = { createNew() })
            } else {
                LazyVerticalGrid(
                    columns               = GridCells.Fixed(2),
                    modifier              = Modifier.fillMaxSize(),
                    contentPadding        = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement   = Arrangement.spacedBy(10.dp),
                ) {
                    items(canvasList, key = { it.id }) { meta ->
                        CanvasCard(
                            meta     = meta,
                            onClick  = {
                                coroutineScope.launch {
                                    val data = withContext(Dispatchers.IO) {
                                        CanvasFileManager.loadCanvas(context, meta.id)
                                    }
                                    if (data != null) {
                                        vm.loadFromCanvasData(data)
                                        onOpen()
                                    }
                                }
                            },
                            onRename = {
                                renameTargetId   = meta.id
                                renameTargetName = meta.title
                                showRenameDialog = true
                            },
                            onDelete = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        CanvasFileManager.deleteCanvas(context, meta.id)
                                    }
                                    refreshList()
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── インポート中オーバーレイ ──────────────────────────────────────
        if (isImporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }
    }
}

// ── ヘッダー ─────────────────────────────────────────────────────────────────

@Composable
private fun GalleryHeader(count: Int, onImport: () -> Unit, onNewCanvas: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ProPaint",
                color      = ACCENT,
                fontSize   = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (count == 0) "アートワークがありません"
                else           "${count} 件のアートワーク",
                color    = TEXT_SUB,
                fontSize = 12.sp,
            )
        }

        // インポートボタン
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SURFACE)
                .border(1.dp, TEXT_SUB.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .clickable { onImport() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = "インポート",
                tint     = TEXT_SUB,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        // 新規キャンバスボタン
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ACCENT)
                .clickable { onNewCanvas() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "新規キャンバス",
                tint     = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

// ── 空状態 ───────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Box(
        modifier          = Modifier.fillMaxSize(),
        contentAlignment  = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.Brush,
                contentDescription = null,
                tint     = TEXT_SUB,
                modifier = Modifier.size(64.dp),
            )
            Text(
                "アートワークがありません",
                color    = TEXT,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "右上の + ボタンで新しいキャンバスを作成",
                color    = TEXT_SUB,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(ACCENT)
                    .clickable { onNew() }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text("新規キャンバス", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── キャンバスカード (Procreate 風オーバーレイ) ───────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasCard(
    meta:     CanvasMeta,
    onClick:  () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context  = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(meta.id) {
        val bmp = withContext(Dispatchers.IO) {
            try {
                val f = File(context.filesDir, "canvases/${meta.id}/thumbnail.jpg")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
            } catch (_: Exception) { null }
        }
        thumbnail = bmp
    }

    // 削除確認ダイアログ
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest  = { showDeleteConfirm = false },
            title             = { Text("削除の確認", color = TEXT) },
            text              = { Text("「${meta.title}」を削除しますか？この操作は元に戻せません。", color = TEXT_SUB) },
            confirmButton     = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("削除", color = Color(0xFFFF453A))
                }
            },
            dismissButton     = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("キャンセル", color = ACCENT)
                }
            },
            containerColor    = SURFACE,
            shape             = RoundedCornerShape(16.dp),
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 一画面 8 個 (2列 × 4行) に収まる比率
            .aspectRatio(0.82f)
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { showMenu = true },
            ),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CARD),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ── サムネイル ────────────────────────────────────────────────
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap             = thumb,
                    contentDescription = meta.title,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier         = Modifier.fillMaxSize().background(Color(0xFF252527)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        meta.title.take(2).uppercase(),
                        color      = TEXT_SUB,
                        fontSize   = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // ── タイトル・日付 グラデーションオーバーレイ ─────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC000000)),
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column {
                    Text(
                        meta.title,
                        color      = Color.White,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDate(meta.modifiedAt),
                        color    = Color(0xAAFFFFFF),
                        fontSize = 9.sp,
                    )
                }
            }

            // ── 長押しコンテキストメニュー ────────────────────────────────
            DropdownMenu(
                expanded         = showMenu,
                onDismissRequest = { showMenu = false },
                modifier         = Modifier.background(SURFACE),
            ) {
                DropdownMenuItem(
                    text    = { Text("名前を変更", color = TEXT) },
                    onClick = { showMenu = false; onRename() },
                )
                HorizontalDivider(color = DIVIDER)
                DropdownMenuItem(
                    text    = { Text("削除", color = Color(0xFFFF453A)) },
                    onClick = { showMenu = false; showDeleteConfirm = true },
                )
            }
        }
    }
}

// ── リネームダイアログ ────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(
    initialTitle: String,
    onConfirm:    (String) -> Unit,
    onDismiss:    () -> Unit,
) {
    var text by remember { mutableStateOf(initialTitle) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("名前を変更", color = TEXT, fontWeight = FontWeight.SemiBold) },
        text             = {
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                singleLine    = true,
                modifier      = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank()) onConfirm(text.trim())
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor     = TEXT,
                    unfocusedTextColor   = TEXT,
                    focusedBorderColor   = ACCENT,
                    unfocusedBorderColor = TEXT_SUB,
                    cursorColor          = ACCENT,
                    focusedContainerColor   = Color(0xFF1C1C1E),
                    unfocusedContainerColor = Color(0xFF1C1C1E),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled  = text.isNotBlank(),
            ) {
                Text("変更", color = ACCENT, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", color = TEXT_SUB)
            }
        },
        containerColor = SURFACE,
        shape          = RoundedCornerShape(16.dp),
    )
}

// ── 日付フォーマット ─────────────────────────────────────────────────────────

private fun formatDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000L              -> "たった今"
        diff < 3_600_000L           -> "${diff / 60_000} 分前"
        diff < 86_400_000L          -> "${diff / 3_600_000} 時間前"
        diff < 86_400_000L * 7     -> "${diff / 86_400_000} 日前"
        else                        -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(millis))
    }
}

// ── Bitmap スケールヘルパー ───────────────────────────────────────────────────

private fun scaleBitmapIfNeeded(bitmap: android.graphics.Bitmap, maxSize: Int): android.graphics.Bitmap {
    val w = bitmap.width; val h = bitmap.height
    if (w <= maxSize && h <= maxSize) return bitmap
    val scale = maxSize.toFloat() / maxOf(w, h)
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    return android.graphics.Bitmap.createScaledBitmap(bitmap, nw, nh, true)
}
