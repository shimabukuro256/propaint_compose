package com.propaint.app.io

import android.graphics.Bitmap
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 基本的な PSD ファイルデコーダー。
 * 対応: バージョン1 (PSB非対応), 8bit RGB / RGBA, 非圧縮 & PackBits RLE。
 * マージ済み (フラット化) コンポジット画像を返す。レイヤー分割は非対応。
 */
object PsdImporter {

    fun decode(input: InputStream): Bitmap? {
        return try {
            val data = input.readBytes()
            val buf  = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            // ── Header ────────────────────────────────────────────────────────
            val sig = ByteArray(4).also { buf.get(it) }
            if (String(sig, Charsets.US_ASCII) != "8BPS") return null
            if (buf.short.toInt() != 1) return null   // version: PSB(v2) 非対応
            repeat(6) { buf.get() }                   // reserved
            val channels  = buf.short.toInt() and 0xFFFF
            val height    = buf.int
            val width     = buf.int
            val depth     = buf.short.toInt() and 0xFFFF
            val colorMode = buf.short.toInt() and 0xFFFF
            if (colorMode != 3) return null            // RGB のみ
            if (depth != 8) return null                // 8bit のみ

            // ── Color Mode Data / Image Resources / Layer & Mask Info ─────────
            skip(buf, buf.int)
            skip(buf, buf.int)
            skip(buf, buf.int)

            // ── Image Data (merged composite) ─────────────────────────────────
            val compression = buf.short.toInt() and 0xFFFF
            val pixCount    = width * height
            val numCh       = channels.coerceIn(1, 4)   // R, G, B[, A]

            val chData = Array(numCh) { ByteArray(pixCount) }
            when (compression) {
                0 -> {
                    chData.forEach { buf.get(it) }
                    // channels > numCh の場合 (スポットカラー等) 余分なチャンネルをスキップ
                    if (channels > numCh) skip(buf, (channels - numCh) * pixCount)
                }
                1 -> {
                    // 行バイト数テーブルは「全チャンネル数 × 高さ」エントリ (各2バイト)
                    // numCh ではなく channels を使わないと channels > 4 のとき読み位置がずれる
                    skip(buf, channels * height * 2)
                    chData.indices.forEach { chData[it] = unpackBits(buf, pixCount) }
                }
                else -> return null
            }

            val r = chData[0]; val g = chData.getOrNull(1) ?: r
            val b = chData.getOrNull(2) ?: r; val a = chData.getOrNull(3)

            val argb = IntArray(pixCount) { i ->
                val ai = a?.get(i)?.toInt()?.and(0xFF) ?: 255
                (ai shl 24) or
                ((r[i].toInt() and 0xFF) shl 16) or
                ((g[i].toInt() and 0xFF) shl 8)  or
                (b[i].toInt() and 0xFF)
            }
            Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) { null }
    }

    private fun skip(buf: ByteBuffer, n: Int) {
        if (n > 0 && buf.remaining() >= n) buf.position(buf.position() + n)
    }

    private fun unpackBits(buf: ByteBuffer, target: Int): ByteArray {
        val out = ByteArray(target)
        var i = 0
        while (i < target && buf.hasRemaining()) {
            val h = buf.get().toInt()
            when {
                h == -128  -> Unit
                h >= 0     -> repeat((h + 1).coerceAtMost(target - i))    { if (buf.hasRemaining()) out[i++] = buf.get() }
                else       -> {
                    val b = if (buf.hasRemaining()) buf.get() else 0
                    repeat((-h + 1).coerceAtMost(target - i)) { out[i++] = b }
                }
            }
        }
        return out
    }
}
