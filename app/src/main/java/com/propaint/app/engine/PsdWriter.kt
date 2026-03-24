package com.propaint.app.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * 最小限の PSD (Photoshop Document) ライター。
 * Adobe PSD File Format Specification 準拠。
 * - RGB 8-bit, with layers
 * - チャンネルデータは RLE (PackBits) 圧縮
 */
object PsdWriter {

    fun write(doc: CanvasDocument, output: OutputStream) {
        val dos = DataOutputStream(output)
        val w = doc.width; val h = doc.height
        val layers = doc.layers.filter { it.isVisible || true } // 全レイヤー

        // ── 1. File Header ──
        dos.writeBytes("8BPS")     // Signature
        dos.writeShort(1)          // Version
        dos.write(ByteArray(6))    // Reserved
        dos.writeShort(4)          // Channels (ARGB)
        dos.writeInt(h)            // Height
        dos.writeInt(w)            // Width
        dos.writeShort(8)          // Bits per channel
        dos.writeShort(3)          // Color mode: RGB

        // ── 2. Color Mode Data ──
        dos.writeInt(0)            // Length = 0 for RGB

        // ── 3. Image Resources ──
        dos.writeInt(0)            // Length = 0 (none)

        // ── 4. Layer and Mask Information ──
        val layerSection = buildLayerSection(doc, layers, w, h)
        dos.writeInt(layerSection.size) // Length of layer and mask section
        dos.write(layerSection)

        // ── 5. Image Data (composite) ──
        // 圧縮方式: 0 = Raw
        dos.writeShort(0)
        val composite = doc.getCompositePixels()
        // チャンネル順: R, G, B, A
        for (ch in intArrayOf(16, 8, 0, 24)) { // shift amounts for R, G, B, A
            for (i in 0 until w * h) {
                val px = PixelOps.unpremultiply(composite[i])
                dos.writeByte((px ushr ch) and 0xFF)
            }
        }

        dos.flush()
    }

    private fun buildLayerSection(
        doc: CanvasDocument, layers: List<Layer>, w: Int, h: Int,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Layer info
        val layerInfo = buildLayerInfo(doc, layers, w, h)
        dos.writeInt(layerInfo.size) // Length of layer info
        dos.write(layerInfo)

        // Global layer mask info
        dos.writeInt(0) // Length = 0

        dos.flush()
        return baos.toByteArray()
    }

    private fun buildLayerInfo(
        doc: CanvasDocument, layers: List<Layer>, w: Int, h: Int,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(layers.size) // Layer count

        // レイヤー単位のチャンネルデータを先に構築
        val channelDataList = ArrayList<Array<ByteArray>>()

        // ── Layer Records ──
        for (layer in layers) {
            // 各チャンネルデータを生成 (A, R, G, B)
            val pixels = layer.content.toPixelArray()
            val channelDatas = Array(4) { ch ->
                val shift = when (ch) {
                    0 -> 24 // Alpha
                    1 -> 16 // Red
                    2 -> 8  // Green
                    3 -> 0  // Blue
                    else -> 0
                }
                val raw = ByteArray(w * h)
                for (i in 0 until w * h) {
                    val px = PixelOps.unpremultiply(pixels[i])
                    raw[i] = ((px ushr shift) and 0xFF).toByte()
                }
                // RLE 圧縮
                rleCompress(raw, w, h)
            }
            channelDataList.add(channelDatas)

            // Layer record
            dos.writeInt(0)   // Top
            dos.writeInt(0)   // Left
            dos.writeInt(h)   // Bottom
            dos.writeInt(w)   // Right
            dos.writeShort(4) // Channel count

            // Channel info: id + data length
            val channelIds = intArrayOf(-1, 0, 1, 2) // Alpha, R, G, B
            for (i in 0 until 4) {
                dos.writeShort(channelIds[i])
                dos.writeInt(channelDatas[i].size + 2) // +2 for compression type
            }

            // Blend mode signature
            dos.writeBytes("8BIM")
            // Blend mode key
            val modeKey = blendModeKey(layer.blendMode)
            dos.writeBytes(modeKey)
            // Opacity (0-255)
            dos.writeByte((layer.opacity * 255f).toInt().coerceIn(0, 255))
            // Clipping: 0 = base, 1 = non-base (clip to below)
            dos.writeByte(if (layer.isClipToBelow) 1 else 0)
            // Flags
            var flags = 0
            if (!layer.isVisible) flags = flags or 0x02 // bit 1 = invisible
            if (layer.isAlphaLocked) flags = flags or 0x01 // bit 0 = transparency locked
            dos.writeByte(flags)
            dos.writeByte(0) // Filler

            // Extra data
            val extraBaos = ByteArrayOutputStream()
            val extraDos = DataOutputStream(extraBaos)

            // Layer mask data
            extraDos.writeInt(0) // No mask

            // Layer blending ranges
            extraDos.writeInt(0) // No ranges

            // Layer name (Pascal string, padded to 4 bytes)
            val nameBytes = layer.name.toByteArray(Charset.forName("UTF-8"))
            val nameLen = nameBytes.size.coerceAtMost(255)
            extraDos.writeByte(nameLen)
            extraDos.write(nameBytes, 0, nameLen)
            // Pad to multiple of 4
            val padLen = (4 - (1 + nameLen) % 4) % 4
            for (p in 0 until padLen) extraDos.writeByte(0)

            extraDos.flush()
            val extraData = extraBaos.toByteArray()
            dos.writeInt(extraData.size)
            dos.write(extraData)
        }

        // ── Channel Image Data ──
        for (channelDatas in channelDataList) {
            for (chData in channelDatas) {
                dos.writeShort(1) // Compression: 1 = RLE
                dos.write(chData)
            }
        }

        dos.flush()
        val result = baos.toByteArray()
        // PSD layer info must be even-aligned
        return if (result.size % 2 != 0) result + 0 else result
    }

    /** PackBits RLE 圧縮 */
    private fun rleCompress(raw: ByteArray, w: Int, h: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 行ごとの圧縮サイズ (2バイト × h)
        val rowData = ArrayList<ByteArray>()
        for (y in 0 until h) {
            val rowBaos = ByteArrayOutputStream()
            val row = ByteArray(w)
            System.arraycopy(raw, y * w, row, 0, w)
            packBitsRow(row, rowBaos)
            rowData.add(rowBaos.toByteArray())
        }

        // 行サイズテーブル
        for (rd in rowData) dos.writeShort(rd.size)
        // 行データ
        for (rd in rowData) dos.write(rd)

        dos.flush()
        return baos.toByteArray()
    }

    /** PackBits 1行分の圧縮 */
    private fun packBitsRow(data: ByteArray, out: ByteArrayOutputStream) {
        var i = 0
        val len = data.size
        while (i < len) {
            // ランレングスをチェック
            if (i + 1 < len && data[i] == data[i + 1]) {
                // 連続するバイト
                var runLen = 1
                while (i + runLen < len && runLen < 128 && data[i] == data[i + runLen]) runLen++
                out.write(-(runLen - 1)) // -n+1
                out.write(data[i].toInt() and 0xFF)
                i += runLen
            } else {
                // 非連続バイト
                var litLen = 1
                while (i + litLen < len && litLen < 128) {
                    if (i + litLen + 1 < len && data[i + litLen] == data[i + litLen + 1]) break
                    litLen++
                }
                out.write(litLen - 1) // n-1
                for (j in 0 until litLen) out.write(data[i + j].toInt() and 0xFF)
                i += litLen
            }
        }
    }

    private fun blendModeKey(mode: Int): String = when (mode) {
        PixelOps.BLEND_NORMAL -> "norm"
        PixelOps.BLEND_MULTIPLY -> "mul "
        PixelOps.BLEND_SCREEN -> "scrn"
        PixelOps.BLEND_OVERLAY -> "over"
        PixelOps.BLEND_DARKEN -> "dark"
        PixelOps.BLEND_LIGHTEN -> "lite"
        PixelOps.BLEND_COLOR_DODGE -> "div "
        PixelOps.BLEND_COLOR_BURN -> "idiv"
        PixelOps.BLEND_HARD_LIGHT -> "hLit"
        PixelOps.BLEND_SOFT_LIGHT -> "sLit"
        PixelOps.BLEND_DIFFERENCE -> "diff"
        PixelOps.BLEND_EXCLUSION -> "smud"
        PixelOps.BLEND_ADD -> "lddg"
        PixelOps.BLEND_SUBTRACT -> "fsub"
        PixelOps.BLEND_LINEAR_BURN -> "lbrn"
        PixelOps.BLEND_LINEAR_LIGHT -> "lLit"
        PixelOps.BLEND_VIVID_LIGHT -> "vLit"
        PixelOps.BLEND_PIN_LIGHT -> "pLit"
        else -> "norm"
    }
}
