package com.propaint.app.gl

import com.propaint.app.model.LayerBlendMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

data class PsdLayerData(
    val name: String,
    val pixels: ByteArray,       // RGBA, GL order (y=0 is bottom row), premultiplied
    val width: Int,
    val height: Int,
    val opacity: Float,
    val blendMode: LayerBlendMode,
    val isVisible: Boolean,
    val isClippingMask: Boolean,
)

/**
 * PSD ファイルエクスポーター。
 *
 * 仕様:
 *  - PSD バージョン 1 (PSB 非対応)
 *  - チャンネル 4 (RGBA)
 *  - カラーモード 3 (RGB)
 *  - PackBits RLE 圧縮
 *  - レイヤー名は Pascal 文字列 + luni (Unicode) 追加情報
 *  - GL 座標 (Y=0 が下端) → PSD 座標 (Y=0 が上端) に変換
 *  - プリマルチプライドアルファ → ストレートアルファ に変換
 */
object PsdExporter {

    fun export(
        layers: List<PsdLayerData>,
        canvasWidth: Int,
        canvasHeight: Int,
        out: OutputStream,
    ) {
        val dos = DataOutputStream(out)

        // ── 1. File Header ───────────────────────────────────────────────
        writeHeader(dos, canvasWidth, canvasHeight)

        // ── 2. Color Mode Data (空) ──────────────────────────────────────
        dos.writeInt(0)

        // ── 3. Image Resources ───────────────────────────────────────────
        writeImageResources(dos, canvasWidth, canvasHeight)

        // ── 4. Layer and Mask Info ───────────────────────────────────────
        writeLayerAndMaskInfo(dos, layers, canvasWidth, canvasHeight)

        // ── 5. Merged Image Data ─────────────────────────────────────────
        writeMergedImageData(dos, layers, canvasWidth, canvasHeight)

        dos.flush()
    }

    // ── ファイルヘッダー ─────────────────────────────────────────────────

    private fun writeHeader(dos: DataOutputStream, w: Int, h: Int) {
        // Signature: "8BPS"
        dos.writeByte('8'.code); dos.writeByte('B'.code)
        dos.writeByte('P'.code); dos.writeByte('S'.code)
        // Version: 1
        dos.writeShort(1)
        // Reserved: 6 bytes
        repeat(6) { dos.writeByte(0) }
        // Channels: 4 (RGBA)
        dos.writeShort(4)
        // Height, Width
        dos.writeInt(h)
        dos.writeInt(w)
        // Bit depth: 8
        dos.writeShort(8)
        // Color mode: 3 (RGB)
        dos.writeShort(3)
    }

    // ── イメージリソース ─────────────────────────────────────────────────

    private fun writeImageResources(dos: DataOutputStream, w: Int, h: Int) {
        // 解像度情報 (resource ID 0x03ED = 1005)
        val resBlock = ByteArrayOutputStream()
        val resOut = DataOutputStream(resBlock)
        // hRes = 72 dpi (fixed point 16.16)
        resOut.writeInt(72 shl 16)
        resOut.writeShort(1)  // hResUnit: pixels/inch
        // vRes = 72 dpi
        resOut.writeInt(72 shl 16)
        resOut.writeShort(1)  // vResUnit
        resOut.flush()
        val resBytes = resBlock.toByteArray()

        val irBlock = ByteArrayOutputStream()
        val irOut = DataOutputStream(irBlock)
        // Signature "8BIM"
        irOut.writeByte('8'.code); irOut.writeByte('B'.code)
        irOut.writeByte('I'.code); irOut.writeByte('M'.code)
        irOut.writeShort(0x03ED)  // Resource ID 1005
        irOut.writeShort(0)       // Pascal name (empty)
        irOut.writeInt(resBytes.size)
        irOut.write(resBytes)
        // Pad to even
        if (resBytes.size % 2 != 0) irOut.writeByte(0)
        irOut.flush()
        val irBytes = irBlock.toByteArray()

        dos.writeInt(irBytes.size)
        dos.write(irBytes)
    }

    // ── レイヤーおよびマスク情報 ─────────────────────────────────────────

    private fun writeLayerAndMaskInfo(
        dos: DataOutputStream,
        layers: List<PsdLayerData>,
        w: Int, h: Int,
    ) {
        // Layer info section (layer records + channel data)
        val layerBlock = ByteArrayOutputStream()
        val layerOut = DataOutputStream(layerBlock)

        // Layer count (negative = first alpha is transparency for merged image)
        layerOut.writeShort(-layers.size)

        // Layer records
        val channelDataList = mutableListOf<ByteArray>()
        for (layer in layers) {
            writeLayerRecord(layerOut, layer, w, h)
            channelDataList.add(buildChannelData(layer, w, h))
        }

        // Channel image data (following layer records)
        for (chData in channelDataList) {
            layerOut.write(chData)
        }

        layerOut.flush()
        val layerBytes = layerBlock.toByteArray()

        // Pad layer section to 4-byte boundary
        val padded = if (layerBytes.size % 4 != 0) {
            layerBytes + ByteArray(4 - layerBytes.size % 4)
        } else {
            layerBytes
        }

        // Layer & mask info section:
        //   4 bytes: length of layer info (following 4-byte field)
        //   padded layer bytes
        //   4 bytes: global mask length (0 = empty)
        val lmiBlock = ByteArrayOutputStream()
        val lmiOut = DataOutputStream(lmiBlock)
        lmiOut.writeInt(padded.size)  // layer info length
        lmiOut.write(padded)
        lmiOut.writeInt(0)             // global layer mask info: empty
        lmiOut.flush()

        val lmiBytes = lmiBlock.toByteArray()
        dos.writeInt(lmiBytes.size)
        dos.write(lmiBytes)
    }

    // ── レイヤーレコード ─────────────────────────────────────────────────

    private fun writeLayerRecord(
        out: DataOutputStream,
        layer: PsdLayerData,
        w: Int, h: Int,
    ) {
        // Bounding rect: top, left, bottom, right
        out.writeInt(0)
        out.writeInt(0)
        out.writeInt(h)
        out.writeInt(w)

        // Number of channels: 4 (A, R, G, B)
        out.writeShort(4)
        // Channel info: [channel ID (2 bytes), length (4 bytes)]
        // Channel IDs: -1=alpha, 0=R, 1=G, 2=B
        // We'll write placeholder lengths (will be correct when we write actual data)
        // For simplicity, store the actual compressed data and compute length beforehand
        // Here we just write the IDs; lengths will be determined when building channel data
        // Actually for PSD we need to write lengths here in the record.
        // We need to pre-compute channel lengths. Let's do it here.
        val (rleRows, channelLengths) = computeChannelLengths(layer, w, h)

        out.writeShort(-1)  // Alpha channel
        out.writeInt(channelLengths[0])
        out.writeShort(0)   // R
        out.writeInt(channelLengths[1])
        out.writeShort(1)   // G
        out.writeInt(channelLengths[2])
        out.writeShort(2)   // B
        out.writeInt(channelLengths[3])

        // Blend mode signature "8BIM"
        out.writeByte('8'.code); out.writeByte('B'.code)
        out.writeByte('I'.code); out.writeByte('M'.code)
        // Blend mode key (4 chars)
        val key = blendModeKey(layer.blendMode)
        out.writeByte(key[0].code); out.writeByte(key[1].code)
        out.writeByte(key[2].code); out.writeByte(key[3].code)

        // Opacity (0-255)
        out.writeByte((layer.opacity * 255f).toInt().coerceIn(0, 255))

        // Clipping: 0=base, 1=non-base (clip to below)
        out.writeByte(if (layer.isClippingMask) 1 else 0)

        // Flags: bit 1 = not visible
        val flags = if (!layer.isVisible) 0x02 else 0x00
        out.writeByte(flags)

        // Filler
        out.writeByte(0)

        // Extra data
        val extraBlock = ByteArrayOutputStream()
        val extraOut = DataOutputStream(extraBlock)

        // Layer mask data (empty)
        extraOut.writeInt(0)

        // Layer blending ranges (empty)
        extraOut.writeInt(0)

        // Layer name (Pascal string, padded to 4-byte alignment)
        val nameBytes = layer.name.toByteArray(Charsets.UTF_8)
        val nameLenByte = nameBytes.size.coerceIn(0, 255)
        extraOut.writeByte(nameLenByte)
        extraOut.write(nameBytes, 0, nameLenByte)
        // Total so far: 1 + nameLenByte bytes. Pad to 4-byte boundary.
        val nameTotal = 1 + nameLenByte
        val namePad = (4 - nameTotal % 4) % 4
        repeat(namePad) { extraOut.writeByte(0) }

        // Additional layer info: "luni" (Unicode layer name)
        val unicodeNameBlock = ByteArrayOutputStream()
        val unicodeOut = DataOutputStream(unicodeNameBlock)
        val utf16 = layer.name.toByteArray(Charsets.UTF_16BE)
        unicodeOut.writeInt(utf16.size / 2)  // character count
        unicodeOut.write(utf16)
        unicodeOut.flush()
        val unicodeBytes = unicodeNameBlock.toByteArray()
        // Pad to 4-byte boundary
        val uniPadded = if (unicodeBytes.size % 4 != 0) {
            unicodeBytes + ByteArray(4 - unicodeBytes.size % 4)
        } else unicodeBytes

        extraOut.writeByte('8'.code); extraOut.writeByte('B'.code)
        extraOut.writeByte('I'.code); extraOut.writeByte('M'.code)
        extraOut.writeByte('l'.code); extraOut.writeByte('u'.code)
        extraOut.writeByte('n'.code); extraOut.writeByte('i'.code)
        extraOut.writeInt(uniPadded.size)
        extraOut.write(uniPadded)

        extraOut.flush()
        val extraBytes = extraBlock.toByteArray()

        out.writeInt(extraBytes.size)
        out.write(extraBytes)
    }

    // ── チャンネルデータ構築 ─────────────────────────────────────────────

    /**
     * レイヤーの RGBA ピクセルデータ (GL 順) を 4 チャンネル PSD 形式に変換し、
     * RLE 圧縮して返す。
     *
     * GL 座標: y=0 が下端 → PSD 座標: y=0 が上端 (Y 反転が必要)
     * プリマルチプライド → ストレートアルファ変換
     * チャンネル順: A, R, G, B (PSD は -1, 0, 1, 2 の順)
     */
    private fun buildChannelData(layer: PsdLayerData, w: Int, h: Int): ByteArray {
        val pixels = layer.pixels
        // Extract channels (Y-flipped, un-premultiplied)
        val rCh = ByteArray(w * h)
        val gCh = ByteArray(w * h)
        val bCh = ByteArray(w * h)
        val aCh = ByteArray(w * h)

        for (y in 0 until h) {
            val glY = h - 1 - y  // GL Y flip
            for (x in 0 until w) {
                val glOff = (glY * w + x) * 4
                val a  = (pixels[glOff + 3].toInt() and 0xFF)
                val rp = (pixels[glOff    ].toInt() and 0xFF)
                val gp = (pixels[glOff + 1].toInt() and 0xFF)
                val bp = (pixels[glOff + 2].toInt() and 0xFF)
                // Un-premultiply
                val r: Int
                val g: Int
                val b: Int
                if (a > 0) {
                    r = ((rp * 255) / a).coerceIn(0, 255)
                    g = ((gp * 255) / a).coerceIn(0, 255)
                    b = ((bp * 255) / a).coerceIn(0, 255)
                } else {
                    r = 0; g = 0; b = 0
                }
                val dstOff = y * w + x
                rCh[dstOff] = r.toByte()
                gCh[dstOff] = g.toByte()
                bCh[dstOff] = b.toByte()
                aCh[dstOff] = a.toByte()
            }
        }

        val channels = listOf(aCh, rCh, gCh, bCh)
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        for (ch in channels) {
            // Compression type: 1 = PackBits RLE
            dos.writeShort(1)
            // Row byte counts (2 bytes per row)
            val compressedRows = Array(h) { row ->
                packBitsCompress(ch, row * w, w)
            }
            for (row in compressedRows) {
                dos.writeShort(row.size)
            }
            // Compressed row data
            for (row in compressedRows) {
                dos.write(row)
            }
        }

        dos.flush()
        return out.toByteArray()
    }

    /**
     * チャンネル長を事前計算する (レイヤーレコードに必要)。
     * 戻り値: Pair(使用しない, 4チャンネルのバイト長リスト [A, R, G, B])
     */
    private fun computeChannelLengths(
        layer: PsdLayerData,
        w: Int, h: Int,
    ): Pair<Unit, List<Int>> {
        val pixels = layer.pixels

        val rCh = ByteArray(w * h)
        val gCh = ByteArray(w * h)
        val bCh = ByteArray(w * h)
        val aCh = ByteArray(w * h)

        for (y in 0 until h) {
            val glY = h - 1 - y
            for (x in 0 until w) {
                val glOff = (glY * w + x) * 4
                val a  = (pixels[glOff + 3].toInt() and 0xFF)
                val rp = (pixels[glOff    ].toInt() and 0xFF)
                val gp = (pixels[glOff + 1].toInt() and 0xFF)
                val bp = (pixels[glOff + 2].toInt() and 0xFF)
                val r: Int; val g: Int; val b: Int
                if (a > 0) {
                    r = ((rp * 255) / a).coerceIn(0, 255)
                    g = ((gp * 255) / a).coerceIn(0, 255)
                    b = ((bp * 255) / a).coerceIn(0, 255)
                } else {
                    r = 0; g = 0; b = 0
                }
                val dstOff = y * w + x
                rCh[dstOff] = r.toByte()
                gCh[dstOff] = g.toByte()
                bCh[dstOff] = b.toByte()
                aCh[dstOff] = a.toByte()
            }
        }

        val lengths = listOf(aCh, rCh, gCh, bCh).map { ch ->
            // 2 (compression type) + 2*h (row lengths) + sum of compressed row sizes
            var size = 2 + 2 * h
            for (row in 0 until h) {
                size += packBitsCompress(ch, row * w, w).size
            }
            size
        }
        return Pair(Unit, lengths)
    }

    // ── マージされたイメージデータ ───────────────────────────────────────

    /**
     * フラット化した合成イメージを書き出す。
     * 全レイヤーを下から SrcOver で合成 (簡易版)。
     */
    private fun writeMergedImageData(
        dos: DataOutputStream,
        layers: List<PsdLayerData>,
        w: Int, h: Int,
    ) {
        // 合成: 下のレイヤーから上へ SrcOver
        val compositeR = FloatArray(w * h)
        val compositeG = FloatArray(w * h)
        val compositeB = FloatArray(w * h)
        val compositeA = FloatArray(w * h)
        // 初期値: 白背景
        compositeR.fill(1f); compositeG.fill(1f); compositeB.fill(1f); compositeA.fill(1f)

        for (layer in layers) {
            if (!layer.isVisible) continue
            val pixels = layer.pixels
            val layerOpacity = layer.opacity
            for (y in 0 until h) {
                val glY = h - 1 - y
                for (x in 0 until w) {
                    val glOff = (glY * w + x) * 4
                    val a  = (pixels[glOff + 3].toInt() and 0xFF) / 255f * layerOpacity
                    val rp = (pixels[glOff    ].toInt() and 0xFF) / 255f
                    val gp = (pixels[glOff + 1].toInt() and 0xFF) / 255f
                    val bp = (pixels[glOff + 2].toInt() and 0xFF) / 255f
                    // Un-premultiply
                    val r: Float; val g: Float; val b: Float
                    if (a > 0.001f) {
                        val invA = layerOpacity / a  // effectively 1/rawAlpha
                        r = (rp * invA).coerceIn(0f, 1f)
                        g = (gp * invA).coerceIn(0f, 1f)
                        b = (bp * invA).coerceIn(0f, 1f)
                    } else {
                        r = 0f; g = 0f; b = 0f
                    }
                    val dstOff = y * w + x
                    // SrcOver compositing
                    val dstA = compositeA[dstOff]
                    val oneMinusSrcA = 1f - a
                    compositeR[dstOff] = r * a + compositeR[dstOff] * oneMinusSrcA
                    compositeG[dstOff] = g * a + compositeG[dstOff] * oneMinusSrcA
                    compositeB[dstOff] = b * a + compositeB[dstOff] * oneMinusSrcA
                    compositeA[dstOff] = a + dstA * oneMinusSrcA
                }
            }
        }

        // Convert to byte channels
        val rCh = ByteArray(w * h) { (compositeR[it] * 255f).toInt().coerceIn(0, 255).toByte() }
        val gCh = ByteArray(w * h) { (compositeG[it] * 255f).toInt().coerceIn(0, 255).toByte() }
        val bCh = ByteArray(w * h) { (compositeB[it] * 255f).toInt().coerceIn(0, 255).toByte() }
        val aCh = ByteArray(w * h) { (compositeA[it] * 255f).toInt().coerceIn(0, 255).toByte() }

        // Compression type: 1 = PackBits
        dos.writeShort(1)

        // Row byte counts for all 4 channels (R,G,B,A order for merged image)
        val channels = listOf(rCh, gCh, bCh, aCh)
        val allCompressed = channels.map { ch ->
            Array(h) { row -> packBitsCompress(ch, row * w, w) }
        }

        // Write all row byte counts first (all channels)
        for (chRows in allCompressed) {
            for (row in chRows) {
                dos.writeShort(row.size)
            }
        }

        // Write compressed data
        for (chRows in allCompressed) {
            for (row in chRows) {
                dos.write(row)
            }
        }
    }

    // ── PackBits RLE 圧縮 ────────────────────────────────────────────────

    /**
     * PackBits RLE 圧縮。
     * @param data 入力バイト配列
     * @param offset 開始オフセット
     * @param length 圧縮する長さ
     */
    private fun packBitsCompress(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var i = offset
        val end = offset + length

        while (i < end) {
            // ランレングス検索
            var runLen = 1
            while (i + runLen < end && runLen < 128 && data[i + runLen] == data[i]) {
                runLen++
            }

            if (runLen > 1) {
                // ラン: -(runLen-1) を書いてからバイト値
                out.write((-(runLen - 1) and 0xFF))
                out.write(data[i].toInt() and 0xFF)
                i += runLen
            } else {
                // リテラル列: 先頭から非連続の長さを求める
                var litLen = 1
                while (i + litLen < end && litLen < 128) {
                    if (i + litLen + 1 < end && data[i + litLen] == data[i + litLen + 1]) break
                    litLen++
                }
                out.write((litLen - 1) and 0xFF)
                for (j in 0 until litLen) {
                    out.write(data[i + j].toInt() and 0xFF)
                }
                i += litLen
            }
        }

        return out.toByteArray()
    }

    // ── ブレンドモードキー ───────────────────────────────────────────────

    private fun blendModeKey(mode: LayerBlendMode): String = when (mode) {
        LayerBlendMode.Normal   -> "norm"
        LayerBlendMode.Multiply -> "mul "
        LayerBlendMode.Screen   -> "scrn"
        LayerBlendMode.Overlay  -> "over"
        LayerBlendMode.Darken   -> "dark"
        LayerBlendMode.Lighten  -> "lite"
    }
}
