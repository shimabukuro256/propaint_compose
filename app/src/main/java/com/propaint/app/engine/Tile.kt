package com.propaint.app.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * 64×64 premultiplied ARGB_8888 タイル。Drawpile DP_Tile 準拠。
 * Copy-on-Write: refCount > 1 なら mutableCopy() してから変更。
 */
class Tile private constructor(
    val pixels: IntArray,
    private val _refCount: AtomicInteger,
) {
    constructor() : this(IntArray(LENGTH), AtomicInteger(1))

    val refCount: Int get() = _refCount.get()
    fun incRef(): Tile { _refCount.incrementAndGet(); return this }
    fun decRef(): Int = _refCount.decrementAndGet()
    fun mutableCopy(): Tile = Tile(pixels.copyOf(), AtomicInteger(1))
    fun clear() { pixels.fill(0) }
    fun fill(color: Int) { pixels.fill(color) }

    fun isBlank(): Boolean {
        for (p in pixels) if (p != 0) return false
        return true
    }

    companion object {
        const val SIZE = 64
        const val LENGTH = SIZE * SIZE
    }
}

/**
 * タイルグリッドのサーフェス。Drawpile DP_LayerContent 準拠。
 * null タイル = 完全透明。
 */
class TiledSurface(val width: Int, val height: Int) {
    val tilesX: Int = (width + Tile.SIZE - 1) / Tile.SIZE
    val tilesY: Int = (height + Tile.SIZE - 1) / Tile.SIZE
    val tiles: Array<Tile?> = arrayOfNulls(tilesX * tilesY)

    fun tileIndex(tx: Int, ty: Int): Int = ty * tilesX + tx

    fun getTile(tx: Int, ty: Int): Tile? {
        if (tx < 0 || tx >= tilesX || ty < 0 || ty >= tilesY) return null
        return tiles[tileIndex(tx, ty)]
    }

    fun getOrCreateMutable(tx: Int, ty: Int): Tile {
        if (tx < 0 || tx >= tilesX || ty < 0 || ty >= tilesY)
            throw IndexOutOfBoundsException("Tile ($tx,$ty) out of ($tilesX,$tilesY)")
        val idx = tileIndex(tx, ty)
        val existing = tiles[idx]
        if (existing == null) {
            val t = Tile(); tiles[idx] = t; return t
        }
        if (existing.refCount > 1) {
            existing.decRef()
            val copy = existing.mutableCopy()
            tiles[idx] = copy; return copy
        }
        return existing
    }

    fun setTile(tx: Int, ty: Int, tile: Tile?) {
        if (tx < 0 || tx >= tilesX || ty < 0 || ty >= tilesY) return
        val idx = tileIndex(tx, ty)
        tiles[idx]?.decRef(); tiles[idx] = tile?.incRef()
    }

    fun clear() { for (i in tiles.indices) { tiles[i]?.decRef(); tiles[i] = null } }

    fun pixelToTile(px: Int): Int =
        if (px >= 0) px / Tile.SIZE else (px - Tile.SIZE + 1) / Tile.SIZE

    /** ピクセル座標の色を取得 (premultiplied ARGB) */
    fun getPixelAt(px: Int, py: Int): Int {
        if (px < 0 || px >= width || py < 0 || py >= height) return 0
        val tx = px / Tile.SIZE; val ty = py / Tile.SIZE
        val tile = getTile(tx, ty) ?: return 0
        val lx = px - tx * Tile.SIZE; val ly = py - ty * Tile.SIZE
        return tile.pixels[ly * Tile.SIZE + lx]
    }

    /**
     * 矩形領域のピクセルを一括読み取り (タイル行単位で System.arraycopy)。
     * blurLocalArea 等の高速化用。範囲外は 0 (透明)。
     *
     * @param dst 出力先配列 (サイズ = dstW * h 以上)
     * @param dstW 出力先の行幅
     * @param originX 読み取り領域の左上 X
     * @param originY 読み取り領域の左上 Y
     * @param w 読み取り幅
     * @param h 読み取り高さ
     */
    fun readArea(dst: IntArray, dstW: Int, originX: Int, originY: Int, w: Int, h: Int) {
        // 範囲外は 0 (透明) — 先にゼロクリア
        dst.fill(0, 0, dstW * h)

        // タイル範囲を算出 (サーフェス境界にクランプ)
        val tx0 = pixelToTile(maxOf(0, originX))
        val ty0 = pixelToTile(maxOf(0, originY))
        val tx1 = pixelToTile(minOf(width - 1, originX + w - 1))
        val ty1 = pixelToTile(minOf(height - 1, originY + h - 1))

        for (tileY in ty0..ty1) {
            val tileTop = tileY * Tile.SIZE
            for (tileX in tx0..tx1) {
                val tileLeft = tileX * Tile.SIZE
                val tile = getTile(tileX, tileY)

                // このタイルと読み取り領域の交差部分を計算
                val copyL = maxOf(originX, tileLeft)
                val copyT = maxOf(originY, tileTop)
                val copyR = minOf(originX + w, tileLeft + Tile.SIZE)
                val copyB = minOf(originY + h, tileTop + Tile.SIZE)
                val copyW = copyR - copyL
                if (copyW <= 0) continue

                for (py in copyT until copyB) {
                    val dstOff = (py - originY) * dstW + (copyL - originX)
                    if (tile == null) {
                        // null タイル = 透明: ゼロ埋め
                        java.util.Arrays.fill(dst, dstOff, dstOff + copyW, 0)
                    } else {
                        val srcOff = (py - tileTop) * Tile.SIZE + (copyL - tileLeft)
                        System.arraycopy(tile.pixels, srcOff, dst, dstOff, copyW)
                    }
                }
            }
        }
    }

    /**
     * 円形範囲の平均色をサンプリング。
     * Drawpile layer_content_sample_color_at / tile_sample 準拠。
     * スポイト・Smudge 共用。
     */
    fun sampleColorAt(cx: Int, cy: Int, radius: Int): Int {
        if (radius <= 0) return getPixelAt(cx, cy)
        val r2 = radius * radius
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var aSum = 0L; var count = 0L
        val x0 = maxOf(0, cx - radius); val x1 = minOf(width - 1, cx + radius)
        val y0 = maxOf(0, cy - radius); val y1 = minOf(height - 1, cy + radius)
        for (py in y0..y1) {
            val dy = py - cy
            for (px in x0..x1) {
                val dx = px - cx
                if (dx * dx + dy * dy > r2) continue
                val c = getPixelAt(px, py)
                aSum += PixelOps.alpha(c); rSum += PixelOps.red(c)
                gSum += PixelOps.green(c); bSum += PixelOps.blue(c)
                count++
            }
        }
        if (count == 0L) return 0
        return PixelOps.pack(
            (aSum / count).toInt(), (rSum / count).toInt(),
            (gSum / count).toInt(), (bSum / count).toInt(),
        )
    }

    /** タイル参照をコピー (CoW 用スナップショット) */
    fun snapshot(): TiledSurface {
        val copy = TiledSurface(width, height)
        for (i in tiles.indices) copy.tiles[i] = tiles[i]?.incRef()
        return copy
    }

    /** 全ピクセルを IntArray で取得 (エクスポート用) */
    fun toPixelArray(): IntArray {
        val out = IntArray(width * height)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val tile = getTile(tx, ty) ?: continue
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= height) break
                val cw = minOf(Tile.SIZE, width - bx)
                System.arraycopy(tile.pixels, ly * Tile.SIZE, out, py * width + bx, cw)
            }
        }
        return out
    }
}
