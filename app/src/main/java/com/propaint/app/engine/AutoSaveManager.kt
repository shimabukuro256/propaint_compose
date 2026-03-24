package com.propaint.app.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 自動保存マネージャー。
 *
 * Procreate 方式: 内部ストレージに高速な生タイルデータで保存し、
 * クラッシュからの復旧を可能にする。
 *
 * 保存フォーマット:
 *   autosave/meta.json        — キャンバスメタデータ
 *   autosave/layer_N.tiles    — 各レイヤーの生タイルデータ
 *
 * .tiles フォーマット:
 *   [4B: tileCount][per tile: 4B index + 16384B pixels]...
 */
object AutoSaveManager {
    private const val TAG = "AutoSave"
    private const val DIR = "autosave"
    private const val META_FILE = "meta.json"
    private const val TILE_BYTES = Tile.LENGTH * 4 // 16384 bytes

    // ── 保存 ────────────────────────────────────────────────────────

    /**
     * ドキュメントを内部ストレージに高速保存。
     * PNG 圧縮を行わないため非常に高速 (大キャンバスでも < 1秒)。
     */
    fun save(context: Context, doc: CanvasDocument): Boolean {
        return try {
            val dir = File(context.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()

            // メタデータ
            val meta = JSONObject().apply {
                put("version", 1)
                put("width", doc.width)
                put("height", doc.height)
                put("activeLayerId", doc.activeLayerId)
                put("timestamp", System.currentTimeMillis())
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
            File(dir, META_FILE).writeText(meta.toString())

            // 古いタイルファイルを削除
            dir.listFiles()?.filter { it.name.endsWith(".tiles") }?.forEach { it.delete() }

            // 各レイヤーのタイルデータ
            for (layer in doc.layers) {
                saveTiles(File(dir, "layer_${layer.id}.tiles"), layer.content)
            }

            Log.d(TAG, "Auto-save completed: ${doc.layers.size} layers")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auto-save failed", e)
            false
        }
    }

    private fun saveTiles(file: File, surface: TiledSurface) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file), 65536)).use { dos ->
            // 非 null タイルを数える
            var count = 0
            for (t in surface.tiles) if (t != null) count++
            dos.writeInt(count)

            val buf = ByteBuffer.allocate(TILE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (i in surface.tiles.indices) {
                val tile = surface.tiles[i] ?: continue
                dos.writeInt(i)
                buf.clear()
                buf.asIntBuffer().put(tile.pixels)
                dos.write(buf.array())
            }
        }
    }

    // ── 復旧 ────────────────────────────────────────────────────────

    /** 自動保存データが存在するか確認 */
    fun hasRecoveryData(context: Context): Boolean {
        val meta = File(context.filesDir, "$DIR/$META_FILE")
        return meta.exists()
    }

    /** 自動保存のタイムスタンプ (ミリ秒) を取得 */
    fun getRecoveryTimestamp(context: Context): Long {
        return try {
            val meta = JSONObject(File(context.filesDir, "$DIR/$META_FILE").readText())
            meta.optLong("timestamp", 0)
        } catch (e: Exception) { 0 }
    }

    /** 自動保存データからドキュメントを復旧 */
    fun recover(context: Context): CanvasDocument? {
        return try {
            val dir = File(context.filesDir, DIR)
            val meta = JSONObject(File(dir, META_FILE).readText())
            val width = meta.getInt("width")
            val height = meta.getInt("height")
            val activeLayerId = meta.getInt("activeLayerId")
            val layersArr = meta.getJSONArray("layers")

            val doc = CanvasDocument(width, height)
            val idMap = HashMap<Int, Int>()

            for (i in 0 until layersArr.length()) {
                val lj = layersArr.getJSONObject(i)
                val savedId = lj.getInt("id")
                val layer = if (i == 0) {
                    doc.layers.first().also { it.name = lj.getString("name") }
                } else {
                    doc.addLayer(lj.getString("name"))
                }
                idMap[savedId] = layer.id
                layer.opacity = lj.getDouble("opacity").toFloat()
                layer.blendMode = lj.getInt("blendMode")
                layer.isVisible = lj.getBoolean("visible")
                layer.isLocked = lj.optBoolean("locked", false)
                layer.isClipToBelow = lj.optBoolean("clipToBelow", false)
                layer.isAlphaLocked = lj.optBoolean("alphaLocked", false)

                // タイルデータ読み込み
                val tilesFile = File(dir, "layer_${savedId}.tiles")
                if (tilesFile.exists()) {
                    loadTiles(tilesFile, layer.content)
                }
            }

            val newActiveId = idMap[activeLayerId] ?: doc.layers.first().id
            doc.setActiveLayer(newActiveId)
            doc.dirtyTracker.markFullRebuild()

            Log.d(TAG, "Recovery completed: ${doc.layers.size} layers")
            doc
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed", e)
            null
        }
    }

    private fun loadTiles(file: File, surface: TiledSurface) {
        DataInputStream(BufferedInputStream(FileInputStream(file), 65536)).use { dis ->
            val count = dis.readInt()
            val buf = ByteArray(TILE_BYTES)
            val intBuf = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

            for (c in 0 until count) {
                val idx = dis.readInt()
                dis.readFully(buf)
                if (idx < 0 || idx >= surface.tiles.size) continue
                val tx = idx % surface.tilesX
                val ty = idx / surface.tilesX
                val tile = surface.getOrCreateMutable(tx, ty)
                intBuf.clear()
                intBuf.asIntBuffer().get(tile.pixels)
            }
        }
    }

    /** 自動保存データを削除 */
    fun clearRecoveryData(context: Context) {
        val dir = File(context.filesDir, DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }
}
