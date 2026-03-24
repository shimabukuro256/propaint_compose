package com.propaint.app.engine

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 選択マスク: ピクセル単位の選択状態を管理。
 *
 * 各ピクセルは 0 (非選択) 〜 255 (完全選択) の値を持つ。
 * ブラシ描画時にマスク値でクリッピングすることで選択範囲外への描画を防ぐ。
 */
class SelectionMask(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "SelectionMask dimensions must be positive: w=$width h=$height" }
    }

    /** 選択マスクデータ (0=非選択, 255=完全選択) */
    val data = ByteArray(width * height)

    /** キャッシュ済み hasSelection フラグ (O(1) 参照) */
    @Volatile
    private var _hasSelection: Boolean = false

    /** 選択が存在するか (キャッシュ済み、O(1)) */
    val hasSelection: Boolean get() = _hasSelection

    /**
     * フルスキャンで _hasSelection を再計算する。
     * ストローク終了時など、頻度の低いタイミングで呼ぶこと。
     */
    fun recomputeHasSelection() {
        _hasSelection = data.any { it != 0.toByte() }
    }

    /** 全選択 */
    fun selectAll() {
        data.fill(255.toByte())
        _hasSelection = true
    }

    /** 全解除 */
    fun clear() {
        data.fill(0)
        _hasSelection = false
    }

    /** 選択範囲を反転 */
    fun invert() {
        for (i in data.indices) {
            data[i] = (255 - (data[i].toInt() and 0xFF)).toByte()
        }
        // 反転後は再スキャン (invert は低頻度操作なので許容)
        recomputeHasSelection()
    }

    /** 指定座標のマスク値を取得 (範囲外は 0) */
    fun getAt(x: Int, y: Int): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0
        return data[y * width + x].toInt() and 0xFF
    }

    /** 指定座標にマスク値を設定 */
    fun setAt(x: Int, y: Int, value: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        val clamped = value.coerceIn(0, 255)
        data[y * width + x] = clamped.toByte()
        if (clamped > 0) _hasSelection = true
    }

    // ── 矩形選択 ────────────────────────────────────────────────────

    /**
     * 矩形領域を選択に追加。
     * @param addMode true=追加, false=新規 (既存をクリアしてから追加)
     */
    fun selectRect(x1: Int, y1: Int, x2: Int, y2: Int, addMode: Boolean = false) {
        if (!addMode) clear()
        val left = maxOf(0, minOf(x1, x2))
        val top = maxOf(0, minOf(y1, y2))
        val right = minOf(width - 1, maxOf(x1, x2))
        val bottom = minOf(height - 1, maxOf(y1, y2))
        if (left <= right && top <= bottom) {
            for (y in top..bottom) {
                val off = y * width
                for (x in left..right) {
                    data[off + x] = 255.toByte()
                }
            }
            _hasSelection = true
        }
        if (DebugConfig.enableDiagnosticLog) {
            Log.d("PaintDebug.Layer", "[Selection] selectRect left=$left top=$top right=$right bottom=$bottom add=$addMode")
        }
    }

    // ── 自動選択 (色域) ──────────────────────────────────────────────

    /**
     * 指定座標の色を基準に、許容差内の連続ピクセルを選択 (フラッドフィル方式)。
     */
    fun autoSelect(
        surface: TiledSurface,
        startX: Int, startY: Int,
        tolerance: Int,
        addMode: Boolean = false,
    ) {
        require(tolerance in 0..255) { "tolerance must be 0..255" }
        if (startX < 0 || startX >= width || startY < 0 || startY >= height) return
        if (!addMode) clear()

        val targetColor = surface.getPixelAt(startX, startY)
        val visited = BooleanArray(width * height)
        val stack = ArrayDeque<Int>(1024)
        val idx0 = startY * width + startX
        stack.addLast(idx0)
        visited[idx0] = true

        while (stack.isNotEmpty()) {
            val pos = stack.removeLast()
            val py = pos / width; val px = pos % width

            // スキャンライン: 左端
            var left = px
            while (left > 0 && !visited[py * width + left - 1] &&
                colorMatch(surface.getPixelAt(left - 1, py), targetColor, tolerance)) {
                left--
                visited[py * width + left] = true
            }

            // 右端
            var right = px
            while (right < width - 1 && !visited[py * width + right + 1] &&
                colorMatch(surface.getPixelAt(right + 1, py), targetColor, tolerance)) {
                right++
                visited[py * width + right] = true
            }

            // この行を選択
            val off = py * width
            for (x in left..right) {
                data[off + x] = 255.toByte()
            }

            // 上下行スキャン
            for (x in left..right) {
                if (py > 0) {
                    val ni = (py - 1) * width + x
                    if (!visited[ni] && colorMatch(surface.getPixelAt(x, py - 1), targetColor, tolerance)) {
                        visited[ni] = true; stack.addLast(ni)
                    }
                }
                if (py < height - 1) {
                    val ni = (py + 1) * width + x
                    if (!visited[ni] && colorMatch(surface.getPixelAt(x, py + 1), targetColor, tolerance)) {
                        visited[ni] = true; stack.addLast(ni)
                    }
                }
            }
        }

        _hasSelection = true
        if (DebugConfig.enableDiagnosticLog) {
            Log.d("PaintDebug.Layer", "[Selection] autoSelect at=($startX,$startY) tolerance=$tolerance add=$addMode")
        }
    }

    // ── ペンマスク選択 (ブラシで追加/削除) ──────────────────────────

    /**
     * ダブ領域にマスクを書き込む (選択ペン / 選択消しゴム)。
     * @param dab ダブマスク
     * @param isErase true=選択消し, false=選択追加
     * @param opacity 不透明度 (0..255)
     */
    fun applyDab(dab: DabMask, isErase: Boolean, opacity: Int) {
        val dr = dab.left + dab.diameter
        val db = dab.top + dab.diameter
        val clampedOpacity = opacity.coerceIn(0, 255)

        for (y in maxOf(0, dab.top) until minOf(height, db)) {
            val dabRow = (y - dab.top) * dab.diameter
            val maskRow = y * width
            for (x in maxOf(0, dab.left) until minOf(width, dr)) {
                val dabVal = dab.data[dabRow + (x - dab.left)].toInt() and 0xFF
                if (dabVal == 0) continue
                val strength = PixelOps.div255(dabVal * clampedOpacity)
                val idx = maskRow + x
                val current = data[idx].toInt() and 0xFF
                val newVal = if (isErase) {
                    maxOf(0, current - strength)
                } else {
                    minOf(255, current + strength)
                }
                data[idx] = newVal.toByte()
            }
        }
        // 追加モードなら確実に true、消去モードは recomputeHasSelection に委ねる
        if (!isErase) _hasSelection = true
    }

    // ── タイル単位でのマスク値取得 (BrushEngine 用) ─────────────────

    /**
     * 指定タイル範囲のマスク値を返す。
     * 返り値が null の場合、タイル全体が完全選択 (255) であることを示す。
     * 全ピクセル 0 の場合は全要素 0 の配列を返す。
     */
    fun getTileMask(tx: Int, ty: Int): ByteArray? {
        val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
        var allFull = true
        var allEmpty = true
        val result = ByteArray(Tile.LENGTH)

        for (ly in 0 until Tile.SIZE) {
            val py = by + ly
            if (py >= height) {
                // 範囲外は非選択
                allFull = false
                continue
            }
            val srcOff = py * width + bx
            val dstOff = ly * Tile.SIZE
            for (lx in 0 until Tile.SIZE) {
                val px = bx + lx
                val v = if (px < width) data[srcOff + lx] else 0
                result[dstOff + lx] = v.toByte()
                val iv = v.toInt() and 0xFF
                if (iv != 255) allFull = false
                if (iv != 0) allEmpty = false
            }
        }

        // 全ピクセル完全選択 → null (最適化: マスク適用不要)
        if (allFull) return null
        return result
    }

    private fun colorMatch(a: Int, b: Int, tolerance: Int): Boolean {
        if (tolerance == 0) return a == b
        val da = abs(PixelOps.alpha(a) - PixelOps.alpha(b))
        val dr = abs(PixelOps.red(a) - PixelOps.red(b))
        val dg = abs(PixelOps.green(a) - PixelOps.green(b))
        val db = abs(PixelOps.blue(a) - PixelOps.blue(b))
        return da <= tolerance && dr <= tolerance && dg <= tolerance && db <= tolerance
    }
}
