package com.propaint.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * キャンバスプロジェクト管理。
 *
 * 内部ストレージに Procreate 風のプロジェクト構造で保存:
 *   projects/{uuid}/canvas.propaint  — キャンバスデータ (ZIP)
 *   projects/{uuid}/thumbnail.png    — サムネイル
 *   projects/{uuid}/meta.json        — メタデータ (名前, サイズ, 更新日時)
 */
object CanvasProjectManager {
    private const val TAG = "ProjectMgr"
    private const val PROJECTS_DIR = "projects"
    private const val CANVAS_FILE = "canvas.propaint"
    private const val THUMBNAIL_FILE = "thumbnail.png"
    private const val META_FILE = "meta.json"
    private const val THUMB_SIZE = 512

    data class ProjectInfo(
        val id: String,
        val name: String,
        val width: Int,
        val height: Int,
        val layerCount: Int,
        val updatedAt: Long,
        val thumbnailFile: File?,
    )

    /** プロジェクト一覧を更新日時降順で取得 */
    fun listProjects(context: Context): List<ProjectInfo> {
        val dir = File(context.filesDir, PROJECTS_DIR)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()?.mapNotNull { projectDir ->
            if (!projectDir.isDirectory) return@mapNotNull null
            val metaFile = File(projectDir, META_FILE)
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val meta = JSONObject(metaFile.readText())
                val thumbFile = File(projectDir, THUMBNAIL_FILE)
                ProjectInfo(
                    id = projectDir.name,
                    name = meta.optString("name", "無題"),
                    width = meta.optInt("width", 0),
                    height = meta.optInt("height", 0),
                    layerCount = meta.optInt("layerCount", 1),
                    updatedAt = meta.optLong("updatedAt", projectDir.lastModified()),
                    thumbnailFile = if (thumbFile.exists()) thumbFile else null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read project meta: ${projectDir.name}", e)
                null
            }
        }?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    /** 新規プロジェクトを作成し ID を返す */
    fun createProject(context: Context, name: String, width: Int, height: Int): String {
        val id = UUID.randomUUID().toString()
        val projectDir = File(context.filesDir, "$PROJECTS_DIR/$id")
        projectDir.mkdirs()

        val doc = CanvasDocument(width, height)
        saveProjectInner(projectDir, doc, name)
        return id
    }

    /** プロジェクトを開いて CanvasDocument を返す */
    fun openProject(context: Context, projectId: String): CanvasDocument? {
        val projectDir = File(context.filesDir, "$PROJECTS_DIR/$projectId")
        val canvasFile = File(projectDir, CANVAS_FILE)
        if (!canvasFile.exists()) return null

        return try {
            FileInputStream(canvasFile).use { fis ->
                FileManager.loadPropaint(fis)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open project: $projectId", e)
            null
        }
    }

    /** プロジェクトを保存 */
    fun saveProject(context: Context, projectId: String, doc: CanvasDocument, name: String? = null) {
        val projectDir = File(context.filesDir, "$PROJECTS_DIR/$projectId")
        if (!projectDir.exists()) projectDir.mkdirs()

        val displayName = name ?: run {
            val metaFile = File(projectDir, META_FILE)
            if (metaFile.exists()) {
                try { JSONObject(metaFile.readText()).optString("name", "無題") }
                catch (e: Exception) { "無題" }
            } else "無題"
        }

        saveProjectInner(projectDir, doc, displayName)
    }

    /** プロジェクトを削除 */
    fun deleteProject(context: Context, projectId: String) {
        val projectDir = File(context.filesDir, "$PROJECTS_DIR/$projectId")
        projectDir.deleteRecursively()
    }

    /** プロジェクト名を変更 */
    fun renameProject(context: Context, projectId: String, newName: String) {
        val metaFile = File(context.filesDir, "$PROJECTS_DIR/$projectId/$META_FILE")
        if (!metaFile.exists()) return
        try {
            val meta = JSONObject(metaFile.readText())
            meta.put("name", newName)
            metaFile.writeText(meta.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename project", e)
        }
    }

    /** サムネイル画像を読み込み */
    fun loadThumbnail(projectInfo: ProjectInfo): Bitmap? {
        val file = projectInfo.thumbnailFile ?: return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) { null }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun saveProjectInner(projectDir: File, doc: CanvasDocument, name: String) {
        // キャンバスデータ
        val canvasFile = File(projectDir, CANVAS_FILE)
        FileOutputStream(canvasFile).use { fos ->
            FileManager.savePropaint(doc, fos)
        }

        // サムネイル生成
        generateThumbnail(doc, File(projectDir, THUMBNAIL_FILE))

        // メタデータ
        val meta = JSONObject().apply {
            put("name", name)
            put("width", doc.width)
            put("height", doc.height)
            put("layerCount", doc.layers.size)
            put("updatedAt", System.currentTimeMillis())
        }
        File(projectDir, META_FILE).writeText(meta.toString())
    }

    private fun generateThumbnail(doc: CanvasDocument, outFile: File) {
        try {
            val pixels = doc.getCompositePixels()
            val w = doc.width; val h = doc.height

            // premultiplied → straight alpha に変換して Bitmap 生成
            val straight = IntArray(pixels.size)
            for (i in pixels.indices) {
                straight[i] = PixelOps.unpremultiply(pixels[i])
            }

            val fullBmp = Bitmap.createBitmap(straight, w, h, Bitmap.Config.ARGB_8888)

            // リサイズ (長辺を THUMB_SIZE に)
            val scale = THUMB_SIZE.toFloat() / maxOf(w, h)
            val tw = maxOf(1, (w * scale).toInt())
            val th = maxOf(1, (h * scale).toInt())
            val thumb = Bitmap.createScaledBitmap(fullBmp, tw, th, true)
            if (thumb !== fullBmp) fullBmp.recycle()

            FileOutputStream(outFile).use { fos ->
                thumb.compress(Bitmap.CompressFormat.PNG, 80, fos)
            }
            thumb.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail generation failed", e)
        }
    }

    /** 日時フォーマット用ヘルパー */
    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
