package com.propaint.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.propaint.app.engine.CanvasProjectManager
import com.propaint.app.engine.CanvasProjectManager.ProjectInfo
import com.propaint.app.ui.theme.ProPaintTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // イマーシブモード
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            ProPaintTheme {
                GalleryScreen(
                    onOpenProject = { projectId -> openPaintActivity(projectId) },
                    onNewCanvas = { w, h, name -> createAndOpenProject(w, h, name) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // PaintActivity から戻ったらリストを更新 (recompose トリガー)
    }

    private fun openPaintActivity(projectId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PROJECT_ID, projectId)
        }
        startActivity(intent)
    }

    private fun createAndOpenProject(width: Int, height: Int, name: String) {
        val projectId = CanvasProjectManager.createProject(applicationContext, name, width, height)
        openPaintActivity(projectId)
    }
}

@Composable
private fun GalleryScreen(
    onOpenProject: (String) -> Unit,
    onNewCanvas: (Int, Int, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    // onResume ごとにリフレッシュ
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        projects = withContext(Dispatchers.IO) {
            CanvasProjectManager.listProjects(context)
        }
    }

    // 画面表示のたびにリフレッシュ (PaintActivity から戻った時)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ヘッダー
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "ギャラリー",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${projects.size} 作品",
                    color = Color(0xFF888888),
                    fontSize = 14.sp,
                )
            }

            // グリッド
            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("作品がありません", color = Color(0xFF666666), fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "+ ボタンで新規キャンバスを作成",
                            color = Color(0xFF555555),
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onOpenProject(project.id) },
                            onDelete = { deleteTarget = project },
                            onRename = { renameTarget = project },
                        )
                    }
                }
            }
        }

        // FAB: 新規作成
        FloatingActionButton(
            onClick = { showNewDialog = true },
            containerColor = Color(0xFF6CB4EE),
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "新規キャンバス", modifier = Modifier.size(28.dp))
        }
    }

    // 新規キャンバスダイアログ
    if (showNewDialog) {
        NewProjectDialog(
            onConfirm = { w, h, name ->
                showNewDialog = false
                onNewCanvas(w, h, name)
            },
            onDismiss = { showNewDialog = false },
        )
    }

    // 削除確認ダイアログ
    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("削除の確認") },
            text = { Text("「${project.name}」を削除しますか？\nこの操作は元に戻せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            CanvasProjectManager.deleteProject(context, project.id)
                            withContext(Dispatchers.Main) { refreshKey++ }
                        }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B)),
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            },
        )
    }

    // リネームダイアログ
    renameTarget?.let { project ->
        var newName by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("名前の変更") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6CB4EE),
                        cursorColor = Color(0xFF6CB4EE),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            CanvasProjectManager.renameProject(context, project.id, newName.trim())
                            withContext(Dispatchers.Main) { refreshKey++ }
                        }
                    }
                    renameTarget = null
                }) { Text("変更") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var thumbnail by remember(project.id, project.updatedAt) { mutableStateOf<ImageBitmap?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(project.id, project.updatedAt) {
        thumbnail = withContext(Dispatchers.IO) {
            CanvasProjectManager.loadThumbnail(project)?.asImageBitmap()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // サムネイル
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = project.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF3A3A3A)),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF3A3A3A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No Preview", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }

            // 下部オーバーレイ: 名前 + メタ情報
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0xCC1A1A1A))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    project.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${project.width}x${project.height}",
                        color = Color(0xFF999999),
                        fontSize = 10.sp,
                    )
                    Text(
                        CanvasProjectManager.formatDate(project.updatedAt),
                        color = Color(0xFF999999),
                        fontSize = 10.sp,
                    )
                }
            }

            // コンテキストメニュー (長押し or アイコン)
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "メニュー",
                        tint = Color(0xAAFFFFFF),
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("名前を変更") },
                        onClick = { showMenu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("削除", color = Color(0xFFFF6B6B)) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6B6B))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    onConfirm: (Int, Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("無題") }
    var widthText by remember { mutableStateOf("2048") }
    var heightText by remember { mutableStateOf("1536") }

    data class Preset(val label: String, val w: Int, val h: Int)
    val presets = listOf(
        Preset("A4 (300dpi)", 2480, 3508),
        Preset("A4 横", 3508, 2480),
        Preset("2K", 2048, 1536),
        Preset("4K", 4096, 3072),
        Preset("1080p", 1920, 1080),
        Preset("正方形", 2048, 2048),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新規キャンバス") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("プロジェクト名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6CB4EE),
                        cursorColor = Color(0xFF6CB4EE),
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it.filter { c -> c.isDigit() } },
                        label = { Text("幅") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6CB4EE),
                            cursorColor = Color(0xFF6CB4EE),
                        ),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter { c -> c.isDigit() } },
                        label = { Text("高さ") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6CB4EE),
                            cursorColor = Color(0xFF6CB4EE),
                        ),
                    )
                }
                Text("プリセット", color = Color(0xFF999999), fontSize = 12.sp)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in presets) {
                        FilterChip(
                            selected = widthText == p.w.toString() && heightText == p.h.toString(),
                            onClick = { widthText = p.w.toString(); heightText = p.h.toString() },
                            label = { Text(p.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF3A5A7C),
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = widthText.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
                val h = heightText.toIntOrNull()?.coerceIn(64, 8192) ?: 1536
                onConfirm(w, h, name.ifBlank { "無題" })
            }) { Text("作成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
