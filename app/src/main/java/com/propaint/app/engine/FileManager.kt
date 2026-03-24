package com.propaint.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ファイル入出力マネージャー。
 * - .propaint: 独自形式 (ZIP: meta.json + layer PNG)
 * - .psd: PSD エクスポート
 * - PNG/JPEG/WebP: フラット画像 import/export
 */
object FileManager {

    // ── .propaint 保存 ──────────────────────────────────────────────

    fun savePropaint(doc: CanvasDocument, output: OutputStream) {
        val zos = ZipOutputStream(output)

        // meta.json
        val meta = JSONObject().apply {
            put("version", 1)
            put("width", doc.width)
            put("height", doc.height)
            put("activeLayerId", doc.activeLayerId)
            val layersArr = JSONArray()
            for (layer in doc.layers) {
                layersArr.put(JSONObject().apply {
                    put("id", layer.id)
                    put("name", layer.name)
                    put("opacity", layer.opacity.toDouble())
                    put("blendMode", layer.blendMode)
                    put("visible", layer.isVisible)
                    put("locked", layer.isLocked)
                    put("clipToBelow", layer.isClipToBelow)
                    put("alphaLocked", layer.isAlphaLocked)
                })
            }
            put("layers", layersArr)
        }
        zos.putNextEntry(ZipEntry("meta.json"))
        zos.write(meta.toString(2).toByteArray())
        zos.closeEntry()

        // 各レイヤーを PNG で保存
        for (layer in doc.layers) {
            val pixels = layer.content.toPixelArray()
            // premultiplied → straight alpha に変換してから Bitmap に渡す
            val straight = IntArray(pixels.size)
            for (i in pixels.indices) {
                straight[i] = PixelOps.unpremultiply(pixels[i])
            }
            val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(straight, 0, doc.width, 0, 0, doc.width, doc.height)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()

            zos.putNextEntry(ZipEntry("layers/${layer.id}.png"))
            zos.write(baos.toByteArray())
            zos.closeEntry()
        }

        zos.finish()
        zos.close()
    }

    // ── .propaint 読み込み ───────────────────────────────────────────

    fun loadPropaint(input: InputStream): CanvasDocument? {
        val entries = HashMap<String, ByteArray>()
        val zis = ZipInputStream(input)
        var entry = zis.nextEntry
        while (entry != null) {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (zis.read(buf).also { n = it } > 0) baos.write(buf, 0, n)
            entries[entry.name] = baos.toByteArray()
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()

        val metaBytes = entries["meta.json"] ?: return null
        val meta = JSONObject(String(metaBytes))
        val version = meta.optInt("version", 1)
        val width = meta.getInt("width")
        val height = meta.getInt("height")
        val activeLayerId = meta.getInt("activeLayerId")

        val doc = CanvasDocument(width, height)
        // デフォルトレイヤーを削除
        val defaultLayerId = doc.layers.first().id
        // layersリストを構築
        val layersArr = meta.getJSONArray("layers")

        // 既存のデフォルトレイヤーをクリア → 新しいレイヤーで上書き
        // CanvasDocument は最低1レイヤーを維持するため、先にロード後に削除
        val loadedLayers = ArrayList<LayerInfo>()
        for (i in 0 until layersArr.length()) {
            val lj = layersArr.getJSONObject(i)
            loadedLayers.add(LayerInfo(
                id = lj.getInt("id"),
                name = lj.getString("name"),
                opacity = lj.getDouble("opacity").toFloat(),
                blendMode = lj.getInt("blendMode"),
                visible = lj.getBoolean("visible"),
                locked = lj.optBoolean("locked", false),
                clipToBelow = lj.optBoolean("clipToBelow", false),
                alphaLocked = lj.optBoolean("alphaLocked", false),
            ))
        }

        // レイヤーを追加 (保存時のIDと新ドキュメントのIDをマッピング)
        val idMap = HashMap<Int, Int>() // savedId → newId
        for ((idx, info) in loadedLayers.withIndex()) {
            val layer = if (idx == 0) {
                // 最初のレイヤーはデフォルトレイヤーを再利用
                val l = doc.layers.first()
                l.name = info.name
                l
            } else {
                doc.addLayer(info.name)
            }
            idMap[info.id] = layer.id
            layer.opacity = info.opacity
            layer.blendMode = info.blendMode
            layer.isVisible = info.visible
            layer.isLocked = info.locked
            layer.isClipToBelow = info.clipToBelow
            layer.isAlphaLocked = info.alphaLocked

            // PNG からピクセルデータを読み込み (保存時のIDでファイル名を検索)
            val pngBytes = entries["layers/${info.id}.png"]
            if (pngBytes != null) {
                val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                if (bmp != null) {
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp.recycle()
                    // straight alpha → premultiplied に変換
                    for (p in pixels.indices) {
                        pixels[p] = PixelOps.premultiply(pixels[p])
                    }
                    // タイルに書き込み
                    writePixelsToSurface(layer.content, pixels, width, height)
                }
            }
        }

        // マッピングされたIDでアクティブレイヤーを設定
        val newActiveId = idMap[activeLayerId] ?: doc.layers.first().id
        doc.setActiveLayer(newActiveId)
        doc.dirtyTracker.markFullRebuild()
        return doc
    }

    private data class LayerInfo(
        val id: Int, val name: String, val opacity: Float, val blendMode: Int,
        val visible: Boolean, val locked: Boolean, val clipToBelow: Boolean,
        val alphaLocked: Boolean,
    )

    // ── PSD エクスポート ─────────────────────────────────────────────

    fun exportPsd(doc: CanvasDocument, output: OutputStream) {
        PsdWriter.write(doc, output)
    }

    // ── 画像エクスポート (PNG/JPEG/WebP) ─────────────────────────────

    fun exportImage(doc: CanvasDocument, output: OutputStream, format: ImageFormat, quality: Int = 95) {
        val pixels = doc.getCompositePixels()
        // premultiplied → straight alpha
        val straight = IntArray(pixels.size)
        for (i in pixels.indices) {
            straight[i] = PixelOps.unpremultiply(pixels[i])
        }
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(straight, 0, doc.width, 0, 0, doc.width, doc.height)
        @Suppress("DEPRECATION")
        val compressFormat = when (format) {
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30)
                Bitmap.CompressFormat.WEBP_LOSSLESS
            else Bitmap.CompressFormat.WEBP
        }
        bmp.compress(compressFormat, quality, output)
        bmp.recycle()
    }

    // ── 画像インポート (新しいレイヤーとして) ────────────────────────

    fun importImage(doc: CanvasDocument, input: InputStream, layerName: String = "インポート"): Layer? {
        val bmp = BitmapFactory.decodeStream(input) ?: return null
        // キャンバスサイズにリサイズ (必要に応じて)
        val scaledBmp = if (bmp.width != doc.width || bmp.height != doc.height) {
            Bitmap.createScaledBitmap(bmp, doc.width, doc.height, true).also { bmp.recycle() }
        } else bmp

        val pixels = IntArray(scaledBmp.width * scaledBmp.height)
        scaledBmp.getPixels(pixels, 0, scaledBmp.width, 0, 0, scaledBmp.width, scaledBmp.height)
        scaledBmp.recycle()

        // straight alpha → premultiplied
        for (i in pixels.indices) {
            pixels[i] = PixelOps.premultiply(pixels[i])
        }

        val layer = doc.addLayer(layerName)
        writePixelsToSurface(layer.content, pixels, doc.width, doc.height)
        doc.dirtyTracker.markFullRebuild()
        return layer
    }

    // ── ヘルパー ────────────────────────────────────────────────────

    private fun writePixelsToSurface(surface: TiledSurface, pixels: IntArray, w: Int, h: Int) {
        for (ty in 0 until surface.tilesY) for (tx in 0 until surface.tilesX) {
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            var hasContent = false
            val cw = minOf(Tile.SIZE, w - bx)
            val ch = minOf(Tile.SIZE, h - by)
            for (ly in 0 until ch) {
                val off = (by + ly) * w + bx
                for (lx in 0 until cw) {
                    if (pixels[off + lx] != 0) { hasContent = true; break }
                }
                if (hasContent) break
            }
            if (!hasContent) continue
            val tile = surface.getOrCreateMutable(tx, ty)
            for (ly in 0 until ch) {
                System.arraycopy(pixels, (by + ly) * w + bx, tile.pixels, ly * Tile.SIZE, cw)
            }
        }
    }

    enum class ImageFormat { PNG, JPEG, WEBP }
}
