package com.propaint.app.engine

import kotlin.math.exp
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 分離可能ブラー (ガウシアン / ボックス)。
 * フィルター機能とブラシエンジン (ぼかし/筆/水彩筆) の両方から呼ばれる共通処理。
 * premultiplied ARGB_8888 ピクセル配列に対して動作する。
 *
 * 筆 (Fude): ボックスブラー (平均化フィルタ) — 均一な色混合
 * 水彩筆 (Watercolor): ガウシアンブラー — 中心重み付きの滲み効果
 * ぼかしツール: ガウシアンブラー
 */
object GaussianBlur {

    /**
     * 1D ガウシアンカーネルを生成。
     * @param radius ブラー半径 (カーネルサイズ = 2*radius+1)
     * @return 正規化済みカーネル (合計 ≈ 1.0)
     */
    fun generateKernel(radius: Int): FloatArray {
        if (radius <= 0) return floatArrayOf(1f)
        val size = radius * 2 + 1
        val kernel = FloatArray(size)
        // sigma = radius/3 だと端が切れすぎるので radius/2 を使用
        val sigma = radius / 2.0
        val twoSigmaSq = 2.0 * sigma * sigma
        var sum = 0.0
        for (i in 0 until size) {
            val x = (i - radius).toDouble()
            val v = exp(-(x * x) / twoSigmaSq)
            kernel[i] = v.toFloat()
            sum += v
        }
        // 正規化
        val invSum = (1.0 / sum).toFloat()
        for (i in 0 until size) kernel[i] *= invSum
        return kernel
    }

    /**
     * 分離可能ガウシアンブラーを適用 (全体画像用 — フィルター向け)。
     * @param src premultiplied ARGB ピクセル配列
     * @param w 画像幅
     * @param h 画像高
     * @param radius ブラー半径
     * @return ブラー済みピクセル配列
     */
    fun blur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        val kernel = generateKernel(radius)
        val temp = IntArray(w * h)
        val dst = IntArray(w * h)

        // 水平パス: src → temp
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in kernel.indices) {
                    val sx = x + k - radius
                    val clamped = sx.coerceIn(0, w - 1)
                    val c = src[rowOff + clamped]
                    val kv = kernel[k]
                    a += PixelOps.alpha(c) * kv
                    r += PixelOps.red(c) * kv
                    g += PixelOps.green(c) * kv
                    b += PixelOps.blue(c) * kv
                }
                temp[rowOff + x] = PixelOps.pack(
                    (a + 0.5f).toInt(), (r + 0.5f).toInt(),
                    (g + 0.5f).toInt(), (b + 0.5f).toInt()
                )
            }
        }

        // 垂直パス: temp → dst
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in kernel.indices) {
                    val sy = y + k - radius
                    val clamped = sy.coerceIn(0, h - 1)
                    val c = temp[clamped * w + x]
                    val kv = kernel[k]
                    a += PixelOps.alpha(c) * kv
                    r += PixelOps.red(c) * kv
                    g += PixelOps.green(c) * kv
                    b += PixelOps.blue(c) * kv
                }
                dst[y * w + x] = PixelOps.pack(
                    (a + 0.5f).toInt(), (r + 0.5f).toInt(),
                    (g + 0.5f).toInt(), (b + 0.5f).toInt()
                )
            }
        }
        return dst
    }

    /**
     * 分離可能ボックスブラー (平均化フィルタ)。
     * 各ピクセルを (2*radius+1) 近傍の均一平均で置き換える。
     * ガウシアンより攻撃的に色を混合する — 筆 (Fude) の混色に適する。
     */
    fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        val size = radius * 2 + 1
        val invSize = 1f / size
        val temp = IntArray(w * h)
        val dst = IntArray(w * h)

        // 水平パス: src → temp
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val clamped = (x + k).coerceIn(0, w - 1)
                    val c = src[rowOff + clamped]
                    a += PixelOps.alpha(c); r += PixelOps.red(c)
                    g += PixelOps.green(c); b += PixelOps.blue(c)
                }
                temp[rowOff + x] = PixelOps.pack(
                    (a * invSize + 0.5f).toInt(), (r * invSize + 0.5f).toInt(),
                    (g * invSize + 0.5f).toInt(), (b * invSize + 0.5f).toInt()
                )
            }
        }

        // 垂直パス: temp → dst
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val clamped = (y + k).coerceIn(0, h - 1)
                    val c = temp[clamped * w + x]
                    a += PixelOps.alpha(c); r += PixelOps.red(c)
                    g += PixelOps.green(c); b += PixelOps.blue(c)
                }
                dst[y * w + x] = PixelOps.pack(
                    (a * invSize + 0.5f).toInt(), (r * invSize + 0.5f).toInt(),
                    (g * invSize + 0.5f).toInt(), (b * invSize + 0.5f).toInt()
                )
            }
        }
        return dst
    }

    /**
     * 局所ガウシアンブラー (ブラシ用)。
     * 指定領域のピクセルを読み取り、ブラーをかけて返す。
     * ブラー半径分のパディングを含めてサンプリングし、中央部分のみ返す。
     *
     * @param surface サンプリング元サーフェス
     * @param cx 中心X座標
     * @param cy 中心Y座標
     * @param areaRadius ブラーを適用する領域の半径 (ブラシ半径)
     * @param blurRadius ガウシアンブラーの半径
     * @return ブラー済みピクセル配列 (サイズ = side*side, side = areaRadius*2+1)
     *         と領域の左上座標 (originX, originY)
     */
    /** 局所処理の最大半径。これを超えるブラシはダウンサンプリングで処理 */
    private const val MAX_LOCAL_RADIUS = 512

    /** ダウンサンプリング閾値 */
    private const val DOWNSAMPLE_THRESHOLD_2X = 32
    private const val DOWNSAMPLE_THRESHOLD_4X = 64

    /**
     * @param useBoxBlur true=ボックスブラー(平均化), false=ガウシアンブラー
     */
    fun blurLocalArea(
        surface: TiledSurface,
        cx: Int, cy: Int,
        areaRadius: Int,
        blurRadius: Int,
        useBoxBlur: Boolean = false,
    ): BlurResult {
        val cappedArea = areaRadius.coerceAtMost(MAX_LOCAL_RADIUS)
        val cappedBlur = blurRadius.coerceAtMost(20)
        val side = cappedArea * 2 + 1
        val originX = cx - cappedArea
        val originY = cy - cappedArea

        // ダウンサンプリングスケール決定
        val scale = when {
            cappedArea >= DOWNSAMPLE_THRESHOLD_4X -> 4
            cappedArea >= DOWNSAMPLE_THRESHOLD_2X -> 2
            else -> 1
        }

        if (scale == 1) {
            // フルスケールパス (従来と同等だが readArea で高速化)
            val padded = side + cappedBlur * 2
            val padOriginX = cx - cappedArea - cappedBlur
            val padOriginY = cy - cappedArea - cappedBlur

            val src = IntArray(padded * padded)
            surface.readArea(src, padded, padOriginX, padOriginY, padded, padded)

            val blurred = if (useBoxBlur) boxBlur(src, padded, padded, cappedBlur)
                          else blur(src, padded, padded, cappedBlur)

            val result = IntArray(side * side)
            for (ly in 0 until side) {
                System.arraycopy(blurred, (ly + cappedBlur) * padded + cappedBlur, result, ly * side, side)
            }
            return BlurResult(result, side, originX, originY)
        }

        // ── ダウンサンプリングパス (scale=2 or 4) ──
        // フルサイズで読み取り → 縮小 → ブラー → 拡大
        val fullPadded = side + cappedBlur * 2
        val padOriginX = cx - cappedArea - cappedBlur
        val padOriginY = cy - cappedArea - cappedBlur

        // タイル一括読み取り
        val fullSrc = IntArray(fullPadded * fullPadded)
        surface.readArea(fullSrc, fullPadded, padOriginX, padOriginY, fullPadded, fullPadded)

        // ダウンサンプリング (ポイントサンプル)
        val dsW = (fullPadded + scale - 1) / scale
        val dsH = (fullPadded + scale - 1) / scale
        val dsSrc = IntArray(dsW * dsH)
        for (dy in 0 until dsH) {
            val sy = (dy * scale).coerceAtMost(fullPadded - 1)
            val srcOff = sy * fullPadded
            val dstOff = dy * dsW
            for (dx in 0 until dsW) {
                val sx = (dx * scale).coerceAtMost(fullPadded - 1)
                dsSrc[dstOff + dx] = fullSrc[srcOff + sx]
            }
        }

        // 縮小解像度でブラー (半径もスケールに合わせて縮小)
        val dsBlurRadius = maxOf(1, cappedBlur / scale)
        val dsBlurred = if (useBoxBlur) boxBlur(dsSrc, dsW, dsH, dsBlurRadius)
                        else blur(dsSrc, dsW, dsH, dsBlurRadius)

        // アップサンプリング (最近傍) → 中央領域を抽出
        val blurPadPixels = cappedBlur  // フルスケールでのパディング幅
        val result = IntArray(side * side)
        for (ly in 0 until side) {
            val fullY = ly + blurPadPixels
            val dsY = (fullY / scale).coerceAtMost(dsH - 1)
            val srcRowOff = dsY * dsW
            val dstRowOff = ly * side
            for (lx in 0 until side) {
                val fullX = lx + blurPadPixels
                val dsX = (fullX / scale).coerceAtMost(dsW - 1)
                result[dstRowOff + lx] = dsBlurred[srcRowOff + dsX]
            }
        }

        return BlurResult(result, side, originX, originY)
    }

    data class BlurResult(
        val pixels: IntArray,
        val side: Int,
        val originX: Int,
        val originY: Int,
    )
}
