package com.propaint.app.io

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.propaint.app.gl.PsdExporter
import com.propaint.app.gl.PsdLayerData
import com.propaint.app.model.PaintLayer
import org.json.JSONObject
import java.io.*

object CanvasFileManager {

    private const val DIR_CANVASES = "canvases"
    private const val DIR_SHARE    = "share"
    private const val FILE_CANVAS  = "canvas.ppaint"
    private const val FILE_META    = "meta.json"

    private fun canvasDir(context: Context, id: String): File =
        File(context.filesDir, "$DIR_CANVASES/$id")

    private fun shareDir(context: Context): File =
        File(context.cacheDir, DIR_SHARE).also { it.mkdirs() }

    // ── List all saved canvases ───────────────────────────────────────────

    fun listCanvases(context: Context): List<CanvasMeta> {
        val root = File(context.filesDir, DIR_CANVASES)
        if (!root.exists()) return emptyList()
        val result = mutableListOf<CanvasMeta>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val metaFile = File(dir, FILE_META)
            if (!metaFile.exists()) return@forEach
            try {
                val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                result.add(
                    CanvasMeta(
                        id         = json.getString("id"),
                        title      = json.getString("title"),
                        width      = json.getInt("width"),
                        height     = json.getInt("height"),
                        modifiedAt = json.getLong("modifiedAt"),
                    )
                )
            } catch (_: Exception) { /* skip corrupt entries */ }
        }
        return result.sortedByDescending { it.modifiedAt }
    }

    // ── Save canvas ───────────────────────────────────────────────────────

    fun saveCanvas(
        context: Context,
        id: String,
        title: String,
        width: Int,
        height: Int,
        activeLayerId: String,
        layers: List<PaintLayer>,
        layerPixels: List<Pair<String, ByteArray>>,
        compositePixels: ByteArray?,
    ): File {
        val dir = canvasDir(context, id)
        dir.mkdirs()

        // Write .ppaint
        val ppaintFile = File(dir, FILE_CANVAS)
        ppaintFile.outputStream().use { out ->
            CanvasSerializer.write(out, id, title, width, height, activeLayerId, layers, layerPixels, compositePixels)
        }

        // Write quick meta.json for gallery listing
        val metaJson = buildMetaJson(id, title, width, height, System.currentTimeMillis())
        File(dir, FILE_META).writeText(metaJson, Charsets.UTF_8)

        // Copy thumbnail out of the .ppaint for quick gallery loading
        // (thumbnail is embedded in the ZIP, extract it to thumbnail.jpg)
        extractThumbnail(ppaintFile, dir)

        return ppaintFile
    }

    private fun extractThumbnail(ppaintFile: File, destDir: File) {
        try {
            val zip = java.util.zip.ZipInputStream(BufferedInputStream(ppaintFile.inputStream()))
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "thumbnail.jpg") {
                    val bytes = zip.readBytes()
                    File(destDir, "thumbnail.jpg").writeBytes(bytes)
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            zip.close()
        } catch (_: Exception) { /* ignore */ }
    }

    // ── Load canvas ───────────────────────────────────────────────────────

    fun loadCanvas(context: Context, id: String): CanvasData? {
        val file = File(canvasDir(context, id), FILE_CANVAS)
        if (!file.exists()) return null
        return file.inputStream().use { CanvasSerializer.read(it) }
    }

    // ── Delete canvas ─────────────────────────────────────────────────────

    fun deleteCanvas(context: Context, id: String) {
        canvasDir(context, id).deleteRecursively()
    }

    // ── Rename canvas ─────────────────────────────────────────────────────

    fun renameCanvas(context: Context, id: String, newTitle: String) {
        val dir = canvasDir(context, id)
        // meta.json を更新
        val metaFile = File(dir, FILE_META)
        if (metaFile.exists()) {
            try {
                val json = org.json.JSONObject(metaFile.readText(Charsets.UTF_8))
                val w = json.optInt("width", 0)
                val h = json.optInt("height", 0)
                metaFile.writeText(buildMetaJson(id, newTitle, w, h, System.currentTimeMillis()), Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        // .ppaint 内の metadata.json も更新 (zip を再書き込み)
        val ppaintFile = File(dir, FILE_CANVAS)
        if (!ppaintFile.exists()) return
        val tmpFile = File(dir, "canvas_rename_tmp.ppaint")
        try {
            java.util.zip.ZipInputStream(ppaintFile.inputStream().buffered()).use { zipIn ->
                java.util.zip.ZipOutputStream(tmpFile.outputStream().buffered()).use { zipOut ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name == "metadata.json") {
                            val jo = org.json.JSONObject(zipIn.readBytes().toString(Charsets.UTF_8))
                            jo.put("title", newTitle)
                            val newBytes = jo.toString().toByteArray(Charsets.UTF_8)
                            zipOut.putNextEntry(java.util.zip.ZipEntry("metadata.json"))
                            zipOut.write(newBytes)
                            zipOut.closeEntry()
                        } else {
                            zipOut.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            zipIn.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }
            ppaintFile.delete()
            tmpFile.renameTo(ppaintFile)
        } catch (_: Exception) {
            tmpFile.delete()
        }
    }

    // ── Auto-title ────────────────────────────────────────────────────────

    /** 既存リストと被らない "アートワーク N" を返す。 */
    fun nextAutoTitle(existingMetas: List<CanvasMeta>): String {
        val pattern = Regex("^アートワーク (\\d+)$")
        val used = existingMetas.mapNotNull { pattern.find(it.title)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        var n = 1
        while (n in used) n++
        return "アートワーク $n"
    }

    // ── Share .ppaint ─────────────────────────────────────────────────────

    fun getShareUriForCanvas(context: Context, id: String): Uri? {
        val src = File(canvasDir(context, id), FILE_CANVAS)
        if (!src.exists()) return null
        val dest = File(shareDir(context), "$id.ppaint")
        src.copyTo(dest, overwrite = true)
        return uriFor(context, dest)
    }

    // ── Save temp PNG ─────────────────────────────────────────────────────

    fun saveTempPng(
        context: Context,
        title: String,
        compositePixels: ByteArray,
        width: Int,
        height: Int,
    ): Uri? {
        return try {
            val bmp = pixelsToArgbBitmap(compositePixels, width, height)
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(shareDir(context), "${safeTitle}_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            uriFor(context, file)
        } catch (_: Exception) { null }
    }

    // ── Save temp JPEG ────────────────────────────────────────────────────

    fun saveTempJpeg(
        context: Context,
        title: String,
        compositePixels: ByteArray,
        width: Int,
        height: Int,
    ): Uri? {
        return try {
            val bmp = pixelsToArgbBitmap(compositePixels, width, height)
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(shareDir(context), "${safeTitle}_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            uriFor(context, file)
        } catch (_: Exception) { null }
    }

    // ── Save temp WEBP ────────────────────────────────────────────────────

    fun saveTempWebp(
        context: Context,
        title: String,
        compositePixels: ByteArray,
        width: Int,
        height: Int,
    ): Uri? {
        return try {
            val bmp = pixelsToArgbBitmap(compositePixels, width, height)
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(shareDir(context), "${safeTitle}_${System.currentTimeMillis()}.webp")
            file.outputStream().use {
                @Suppress("DEPRECATION")
                val fmt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                bmp.compress(fmt, 100, it)
            }
            bmp.recycle()
            uriFor(context, file)
        } catch (_: Exception) { null }
    }

    // ── Import .ppaint from external URI ──────────────────────────────────

    fun importPpaint(context: android.content.Context, uri: android.net.Uri): CanvasData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { CanvasSerializer.read(it) }
        } catch (_: Exception) { null }
    }

    // ── Save temp PSD ─────────────────────────────────────────────────────

    fun saveTempPsd(
        context: Context,
        title: String,
        layerPixels: List<Pair<String, ByteArray>>,
        layers: List<PaintLayer>,
        width: Int,
        height: Int,
    ): Uri? {
        return try {
            val psdLayers = layerPixels.mapNotNull { (id, pixels) ->
                val layer = layers.find { it.id == id } ?: return@mapNotNull null
                PsdLayerData(
                    name           = layer.name,
                    pixels         = pixels,
                    width          = width,
                    height         = height,
                    opacity        = layer.opacity,
                    blendMode      = layer.blendMode,
                    isVisible      = layer.isVisible,
                    isClippingMask = layer.isClippingMask,
                )
            }
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(shareDir(context), "${safeTitle}_${System.currentTimeMillis()}.psd")
            file.outputStream().use { out ->
                PsdExporter.export(psdLayers, width, height, out)
            }
            uriFor(context, file)
        } catch (_: Exception) { null }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** GL RGBA bytes (y-bottom-up) → Android ARGB_8888 Bitmap */
    private fun pixelsToArgbBitmap(pixels: ByteArray, width: Int, height: Int): Bitmap {
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val off = ((height - 1 - y) * width + x) * 4
                val r = pixels[off    ].toInt() and 0xFF
                val g = pixels[off + 1].toInt() and 0xFF
                val b = pixels[off + 2].toInt() and 0xFF
                val a = pixels[off + 3].toInt() and 0xFF
                argb[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun buildMetaJson(
        id: String, title: String, width: Int, height: Int, modifiedAt: Long,
    ): String {
        // Simple manual JSON
        val escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"id\":\"$id\",\"title\":\"$escapedTitle\",\"width\":$width,\"height\":$height,\"modifiedAt\":$modifiedAt}"
    }
}
