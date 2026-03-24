package com.propaint.app.engine

/**
 * バケツ (塗りつぶし) ツール。
 * スキャンライン塗りつぶしアルゴリズムで高速に処理。
 */
object FillTool {

    /**
     * 指定座標から塗りつぶしを実行。
     *
     * @param surface 対象サーフェス
     * @param startX 開始X座標
     * @param startY 開始Y座標
     * @param fillColor 塗りつぶし色 (premultiplied ARGB)
     * @param tolerance 色の許容差 (0=完全一致のみ, 255=全域)
     * @param dirtyTracker ダーティタイル通知
     */
    fun floodFill(
        surface: TiledSurface,
        startX: Int, startY: Int,
        fillColor: Int,
        tolerance: Int,
        dirtyTracker: DirtyTileTracker,
    ) {
        require(tolerance in 0..255) { "tolerance must be 0..255" }

        val w = surface.width; val h = surface.height
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return

        val targetColor = surface.getPixelAt(startX, startY)
        // 既に同じ色なら何もしない
        if (colorMatch(targetColor, fillColor, 0)) return

        // visited ビットマップ (メモリ効率: 1bit/pixel)
        val visited = BooleanArray(w * h)
        val stack = ArrayDeque<Int>(1024)
        stack.addLast(startY * w + startX)
        visited[startY * w + startX] = true

        while (stack.isNotEmpty()) {
            val pos = stack.removeLast()
            val py = pos / w; val px = pos % w

            // スキャンライン: 左端を探す
            var left = px
            while (left > 0 && !visited[py * w + left - 1] &&
                colorMatch(surface.getPixelAt(left - 1, py), targetColor, tolerance)) {
                left--
                visited[py * w + left] = true
            }

            // 右端を探す
            var right = px
            while (right < w - 1 && !visited[py * w + right + 1] &&
                colorMatch(surface.getPixelAt(right + 1, py), targetColor, tolerance)) {
                right++
                visited[py * w + right] = true
            }

            // この行を塗りつぶし
            for (x in left..right) {
                writeFillPixel(surface, x, py, fillColor, dirtyTracker)
            }

            // 上下の行をスキャン
            for (x in left..right) {
                if (py > 0) {
                    val idx = (py - 1) * w + x
                    if (!visited[idx] && colorMatch(surface.getPixelAt(x, py - 1), targetColor, tolerance)) {
                        visited[idx] = true
                        stack.addLast(idx)
                    }
                }
                if (py < h - 1) {
                    val idx = (py + 1) * w + x
                    if (!visited[idx] && colorMatch(surface.getPixelAt(x, py + 1), targetColor, tolerance)) {
                        visited[idx] = true
                        stack.addLast(idx)
                    }
                }
            }
        }
    }

    private fun colorMatch(a: Int, b: Int, tolerance: Int): Boolean {
        if (tolerance == 0) return a == b
        val da = kotlin.math.abs(PixelOps.alpha(a) - PixelOps.alpha(b))
        val dr = kotlin.math.abs(PixelOps.red(a) - PixelOps.red(b))
        val dg = kotlin.math.abs(PixelOps.green(a) - PixelOps.green(b))
        val db = kotlin.math.abs(PixelOps.blue(a) - PixelOps.blue(b))
        return da <= tolerance && dr <= tolerance && dg <= tolerance && db <= tolerance
    }

    private fun writeFillPixel(
        surface: TiledSurface, px: Int, py: Int,
        color: Int, dirtyTracker: DirtyTileTracker,
    ) {
        val tx = px / Tile.SIZE; val ty = py / Tile.SIZE
        val tile = surface.getOrCreateMutable(tx, ty)
        val lx = px - tx * Tile.SIZE; val ly = py - ty * Tile.SIZE
        tile.pixels[ly * Tile.SIZE + lx] = color
        dirtyTracker.markDirty(tx, ty)
    }
}
