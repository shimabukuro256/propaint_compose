package com.propaint.app.io

import android.graphics.Bitmap
import com.propaint.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ── Data classes ──────────────────────────────────────────────────────────

data class CanvasData(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val activeLayerId: String,
    val layers: List<PaintLayer>,          // no strokes; pixels come separately
    val layerPixels: List<Pair<String, ByteArray>>,  // layerId → raw RGBA bytes (GL order)
)

data class CanvasMeta(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val modifiedAt: Long,
)

// ── CanvasSerializer ──────────────────────────────────────────────────────

object CanvasSerializer {

    /**
     * Write a .ppaint ZIP to [out].
     * [compositePixels] is raw RGBA in GL order (row 0 = bottom); may be null.
     */
    fun write(
        out: OutputStream,
        id: String,
        title: String,
        width: Int,
        height: Int,
        activeLayerId: String,
        layers: List<PaintLayer>,
        layerPixels: List<Pair<String, ByteArray>>,
        compositePixels: ByteArray?,
    ) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->

            // ── metadata.json ──────────────────────────────────────────
            val meta = buildMetaJson(id, title, width, height, activeLayerId, layers)
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(meta.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // ── thumbnail.jpg ──────────────────────────────────────────
            if (compositePixels != null) {
                val thumbBytes = buildThumbnail(compositePixels, width, height)
                if (thumbBytes != null) {
                    zip.putNextEntry(ZipEntry("thumbnail.jpg"))
                    zip.write(thumbBytes)
                    zip.closeEntry()
                }
            }

            // ── layers/{id}.bin ────────────────────────────────────────
            for ((layerId, pixels) in layerPixels) {
                zip.putNextEntry(ZipEntry("layers/$layerId.bin"))
                zip.write(pixels)
                zip.closeEntry()
            }
        }
    }

    /**
     * Read a .ppaint ZIP from [inp]. Returns null on error.
     */
    fun read(inp: InputStream): CanvasData? {
        return try {
            var metaJson: String? = null
            val pixelMap = mutableMapOf<String, ByteArray>()

            ZipInputStream(BufferedInputStream(inp)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "metadata.json" -> {
                            metaJson = zip.readBytes().toString(Charsets.UTF_8)
                        }
                        entry.name.startsWith("layers/") && entry.name.endsWith(".bin") -> {
                            val layerId = entry.name
                                .removePrefix("layers/")
                                .removeSuffix(".bin")
                            pixelMap[layerId] = zip.readBytes()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val jsonStr = metaJson ?: return null
            val json = JSONObject(jsonStr)
            val canvasId      = json.getString("id")
            val title         = json.getString("title")
            val width         = json.getInt("width")
            val height        = json.getInt("height")
            val activeLayerId = json.getString("activeLayerId")

            val layersJson = json.getJSONArray("layers")
            val layers = mutableListOf<PaintLayer>()
            for (i in 0 until layersJson.length()) {
                val lj = layersJson.getJSONObject(i)
                val filter = if (lj.has("filterType") && !lj.isNull("filterType")) {
                    parseFilter(lj)
                } else null
                val layer = PaintLayer(
                    id             = lj.getString("id"),
                    name           = lj.getString("name"),
                    isVisible      = lj.getBoolean("isVisible"),
                    isLocked       = lj.getBoolean("isLocked"),
                    opacity        = lj.getDouble("opacity").toFloat(),
                    blendMode      = LayerBlendMode.entries.getOrElse(lj.getInt("blendModeOrdinal")) { LayerBlendMode.Normal },
                    strokes        = emptyList(),
                    isClippingMask = lj.optBoolean("isClippingMask", false),
                    filter         = filter,
                )
                layers.add(layer)
            }

            val layerPixels = layers.mapNotNull { l ->
                val px = pixelMap[l.id] ?: return@mapNotNull null
                Pair(l.id, px)
            }

            CanvasData(
                id            = canvasId,
                title         = title,
                width         = width,
                height        = height,
                activeLayerId = activeLayerId,
                layers        = layers,
                layerPixels   = layerPixels,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun buildMetaJson(
        id: String,
        title: String,
        width: Int,
        height: Int,
        activeLayerId: String,
        layers: List<PaintLayer>,
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":${jsonString(id)},")
        sb.append("\"title\":${jsonString(title)},")
        sb.append("\"width\":$width,")
        sb.append("\"height\":$height,")
        sb.append("\"activeLayerId\":${jsonString(activeLayerId)},")
        sb.append("\"layers\":[")
        layers.forEachIndexed { index, layer ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":${jsonString(layer.id)},")
            sb.append("\"name\":${jsonString(layer.name)},")
            sb.append("\"isVisible\":${layer.isVisible},")
            sb.append("\"isLocked\":${layer.isLocked},")
            sb.append("\"opacity\":${layer.opacity},")
            sb.append("\"blendModeOrdinal\":${layer.blendMode.ordinal},")
            sb.append("\"isClippingMask\":${layer.isClippingMask},")
            val f = layer.filter
            if (f != null) {
                sb.append("\"filterType\":${f.type.ordinal},")
                sb.append("\"filterHue\":${f.hue},")
                sb.append("\"filterSat\":${f.saturation},")
                sb.append("\"filterLit\":${f.lightness},")
                sb.append("\"filterBlur\":${f.blurRadius},")
                sb.append("\"filterContrast\":${f.contrast},")
                sb.append("\"filterBrightness\":${f.brightness}")
            } else {
                sb.append("\"filterType\":null")
            }
            sb.append("}")
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    private fun parseFilter(lj: JSONObject): LayerFilter {
        val typeOrdinal = lj.getInt("filterType")
        val type = FilterType.entries.getOrElse(typeOrdinal) { FilterType.HSL }
        return LayerFilter(
            type       = type,
            hue        = lj.optDouble("filterHue", 0.0).toFloat(),
            saturation = lj.optDouble("filterSat", 0.0).toFloat(),
            lightness  = lj.optDouble("filterLit", 0.0).toFloat(),
            blurRadius = lj.optDouble("filterBlur", 0.0).toFloat(),
            contrast   = lj.optDouble("filterContrast", 0.0).toFloat(),
            brightness = lj.optDouble("filterBrightness", 0.0).toFloat(),
        )
    }

    private fun buildThumbnail(compositePixels: ByteArray, width: Int, height: Int): ByteArray? {
        return try {
            // Flip Y and convert premultiplied RGBA → ARGB int array
            val argb = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val off = ((height - 1 - y) * width + x) * 4
                    val r = compositePixels[off    ].toInt() and 0xFF
                    val g = compositePixels[off + 1].toInt() and 0xFF
                    val b = compositePixels[off + 2].toInt() and 0xFF
                    val a = compositePixels[off + 3].toInt() and 0xFF
                    argb[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            var bmp = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
            // Scale to max 512px
            val maxSide = 512
            if (width > maxSide || height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(width, height)
                val sw = (width * scale).toInt().coerceAtLeast(1)
                val sh = (height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                bmp.recycle()
                bmp = scaled
            }
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bmp.recycle()
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** Escape a string for JSON. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
