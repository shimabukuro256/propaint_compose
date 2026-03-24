package com.propaint.app.engine

/**
 * CPU ピクセル演算。全て premultiplied ARGB_8888。
 * Drawpile pixels.c / blend_mode.c 準拠。
 */
object PixelOps {

    inline fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    inline fun red(c: Int): Int   = (c ushr 16) and 0xFF
    inline fun green(c: Int): Int = (c ushr  8) and 0xFF
    inline fun blue(c: Int): Int  =  c          and 0xFF

    inline fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun premultiply(color: Int): Int {
        val a = alpha(color); if (a == 0) return 0; if (a == 255) return color
        return pack(a, ((color ushr 16) and 0xFF) * a / 255,
            ((color ushr 8) and 0xFF) * a / 255, (color and 0xFF) * a / 255)
    }

    fun unpremultiply(color: Int): Int {
        val a = alpha(color); if (a == 0) return 0; if (a == 255) return color
        return pack(a, minOf(255, red(color) * 255 / a),
            minOf(255, green(color) * 255 / a), minOf(255, blue(color) * 255 / a))
    }

    inline fun div255(v: Int): Int { val t = v + 128; return (t + (t shr 8)) shr 8 }

    // ── SrcOver (premultiplied) ─────────────────────────────────────

    fun blendSrcOver(dst: Int, src: Int): Int {
        val sa = alpha(src); if (sa == 0) return dst; if (sa == 255) return src
        val inv = 255 - sa
        return pack(sa + div255(alpha(dst) * inv), red(src) + div255(red(dst) * inv),
            green(src) + div255(green(dst) * inv), blue(src) + div255(blue(dst) * inv))
    }

    fun blendSrcOverOpacity(dst: Int, src: Int, opacity: Int): Int {
        if (opacity <= 0) return dst; if (opacity >= 255) return blendSrcOver(dst, src)
        val sa = div255(alpha(src) * opacity); val sr = div255(red(src) * opacity)
        val sg = div255(green(src) * opacity); val sb = div255(blue(src) * opacity)
        val inv = 255 - sa
        return pack(sa + div255(alpha(dst) * inv), sr + div255(red(dst) * inv),
            sg + div255(green(dst) * inv), sb + div255(blue(dst) * inv))
    }

    // ── Erase ───────────────────────────────────────────────────────

    fun blendErase(dst: Int, srcAlpha: Int): Int {
        if (srcAlpha <= 0) return dst; if (srcAlpha >= 255) return 0
        val keep = 255 - srcAlpha
        return pack(div255(alpha(dst) * keep), div255(red(dst) * keep),
            div255(green(dst) * keep), div255(blue(dst) * keep))
    }

    // ── Channel blend functions (unpremultiplied 0..255) ────────────

    fun blendMultiply(cb: Int, cs: Int): Int = div255(cb * cs)
    fun blendScreen(cb: Int, cs: Int): Int = cb + cs - div255(cb * cs)
    fun blendOverlay(cb: Int, cs: Int): Int =
        if (cb <= 127) minOf(255, div255(2 * cs * cb))
        else maxOf(0, 255 - div255(2 * (255 - cs) * (255 - cb)))
    fun blendHardLight(cb: Int, cs: Int): Int = blendOverlay(cs, cb)
    fun blendSoftLight(cb: Int, cs: Int): Int {
        val b = cb / 255f; val s = cs / 255f
        val r = if (s <= 0.5f) b - (1f - 2f * s) * b * (1f - b)
        else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b
                    else kotlin.math.sqrt(b.toDouble()).toFloat()
            b + (2f * s - 1f) * (d - b)
        }
        return (r * 255f).toInt().coerceIn(0, 255)
    }
    fun blendDarken(cb: Int, cs: Int): Int = minOf(cb, cs)
    fun blendLighten(cb: Int, cs: Int): Int = maxOf(cb, cs)
    fun blendColorDodge(cb: Int, cs: Int): Int {
        if (cb == 0) return 0; if (cs == 255) return 255
        return minOf(255, cb * 255 / (255 - cs))
    }
    fun blendColorBurn(cb: Int, cs: Int): Int {
        if (cb == 255) return 255; if (cs == 0) return 0
        return maxOf(0, 255 - (255 - cb) * 255 / cs)
    }
    fun blendDifference(cb: Int, cs: Int): Int = kotlin.math.abs(cb - cs)
    fun blendExclusion(cb: Int, cs: Int): Int = cb + cs - div255(2 * cb * cs)
    fun blendAdd(cb: Int, cs: Int): Int = minOf(255, cb + cs)
    fun blendSubtract(cb: Int, cs: Int): Int = maxOf(0, cb - cs)
    fun blendLinearBurn(cb: Int, cs: Int): Int = maxOf(0, cb + cs - 255)
    fun blendLinearLight(cb: Int, cs: Int): Int = (cb + 2 * cs - 255).coerceIn(0, 255)
    fun blendVividLight(cb: Int, cs: Int): Int =
        if (cs <= 127) blendColorBurn(cb, minOf(255, cs * 2))
        else blendColorDodge(cb, (cs - 128) * 2)
    fun blendPinLight(cb: Int, cs: Int): Int =
        if (cs <= 127) minOf(cb, cs * 2) else maxOf(cb, (cs - 128) * 2)

    // ── ダブ合成 (Drawpile transient_tile_brush_apply) ──────────────

    fun applyDabToTile(
        tilePixels: IntArray,
        mask: IntArray, maskW: Int,
        color: Int, opacity: Int, blendMode: Int,
        clipL: Int, clipT: Int, clipR: Int, clipB: Int,
        maskOffX: Int, maskOffY: Int,
    ) {
        val ca = alpha(color); val cr = red(color)
        val cg = green(color); val cb = blue(color)

        for (ty in clipT until clipB) {
            val my = ty - maskOffY; if (my < 0 || my >= maskW) continue
            val tOff = ty * Tile.SIZE; val mOff = my * maskW
            for (tx in clipL until clipR) {
                val mx = tx - maskOffX; if (mx < 0 || mx >= maskW) continue
                val mv = mask[mOff + mx]; if (mv <= 0) continue
                val da = div255(mv * opacity); if (da <= 0) continue
                val di = tOff + tx; val dst = tilePixels[di]

                when (blendMode) {
                    BLEND_ERASE -> {
                        tilePixels[di] = blendErase(dst, div255(da * ca))
                    }
                    BLEND_MARKER -> {
                        // Marker: 既存ピクセルのアルファより高い場合のみ描画 (replace)
                        val sa = div255(ca * da)
                        if (sa <= alpha(dst)) continue
                        tilePixels[di] = pack(sa, div255(cr * da), div255(cg * da), div255(cb * da))
                    }
                    BLEND_MIX -> {
                        // 混色ブレンド: dst と src を線形補間 (RGB も alpha も混ざる)
                        // da = div255(mv * opacity) は既に計算済み
                        val invDa = 255 - da
                        tilePixels[di] = pack(
                            div255(alpha(dst) * invDa + ca * da),
                            div255(red(dst) * invDa + cr * da),
                            div255(green(dst) * invDa + cg * da),
                            div255(blue(dst) * invDa + cb * da),
                        )
                    }
                    else -> { // BLEND_NORMAL and others
                        val sa = div255(ca * da); val sr = div255(cr * da)
                        val sg = div255(cg * da); val sb = div255(cb * da)
                        val inv = 255 - sa
                        tilePixels[di] = pack(
                            sa + div255(alpha(dst) * inv),
                            sr + div255(red(dst) * inv),
                            sg + div255(green(dst) * inv),
                            sb + div255(blue(dst) * inv))
                    }
                }
            }
        }
    }

    /**
     * 単一ピクセルブレンド (選択マスク付きダブ合成用)。
     * opacity は既にダブマスク×選択マスクの乗算済み (0..255)。
     */
    fun blendPixel(tilePixels: IntArray, idx: Int, color: Int, opacity: Int, blendMode: Int) {
        if (opacity <= 0) return
        val ca = alpha(color); val cr = red(color)
        val cg = green(color); val cb = blue(color)
        val da = opacity
        val dst = tilePixels[idx]
        when (blendMode) {
            BLEND_ERASE -> {
                tilePixels[idx] = blendErase(dst, div255(da * ca))
            }
            BLEND_MARKER -> {
                val sa = div255(ca * da)
                if (sa <= alpha(dst)) return
                tilePixels[idx] = pack(sa, div255(cr * da), div255(cg * da), div255(cb * da))
            }
            BLEND_MIX -> {
                val invDa = 255 - da
                tilePixels[idx] = pack(
                    div255(alpha(dst) * invDa + ca * da),
                    div255(red(dst) * invDa + cr * da),
                    div255(green(dst) * invDa + cg * da),
                    div255(blue(dst) * invDa + cb * da),
                )
            }
            else -> {
                val sa = div255(ca * da); val sr = div255(cr * da)
                val sg = div255(cg * da); val sb = div255(cb * da)
                val inv = 255 - sa
                tilePixels[idx] = pack(
                    sa + div255(alpha(dst) * inv),
                    sr + div255(red(dst) * inv),
                    sg + div255(green(dst) * inv),
                    sb + div255(blue(dst) * inv))
            }
        }
    }

    // ── レイヤー合成 (タイル全体) ───────────────────────────────────

    fun compositeLayer(dst: IntArray, src: IntArray, opacity: Int, blendMode: Int) {
        if (opacity <= 0) return
        for (i in 0 until Tile.LENGTH) {
            val s = src[i]; if (alpha(s) == 0) continue
            val d = dst[i]
            dst[i] = when (blendMode) {
                BLEND_NORMAL -> blendSrcOverOpacity(d, s, opacity)
                BLEND_ERASE -> blendErase(d, div255(alpha(s) * opacity))
                BLEND_MARKER -> {
                    val eff = blendSrcOverOpacity(d, s, opacity)
                    pack(maxOf(alpha(eff), alpha(d)), red(eff), green(eff), blue(eff))
                }
                else -> applyChannelBlend(d, s, opacity, blendMode)
            }
        }
    }

    private fun applyChannelBlend(dst: Int, src: Int, opacity: Int, mode: Int): Int {
        val da = alpha(dst); val sa = alpha(src)
        if (da == 0) return blendSrcOverOpacity(dst, src, opacity)
        val dr = if (da > 0) red(dst) * 255 / da else 0
        val dg = if (da > 0) green(dst) * 255 / da else 0
        val db = if (da > 0) blue(dst) * 255 / da else 0
        val sr = if (sa > 0) red(src) * 255 / sa else 0
        val sg = if (sa > 0) green(src) * 255 / sa else 0
        val sb = if (sa > 0) blue(src) * 255 / sa else 0
        val blendFn: (Int, Int) -> Int = when (mode) {
            BLEND_MULTIPLY -> ::blendMultiply; BLEND_SCREEN -> ::blendScreen
            BLEND_OVERLAY -> ::blendOverlay; BLEND_DARKEN -> ::blendDarken
            BLEND_LIGHTEN -> ::blendLighten; BLEND_COLOR_DODGE -> ::blendColorDodge
            BLEND_COLOR_BURN -> ::blendColorBurn; BLEND_HARD_LIGHT -> ::blendHardLight
            BLEND_SOFT_LIGHT -> ::blendSoftLight; BLEND_DIFFERENCE -> ::blendDifference
            BLEND_EXCLUSION -> ::blendExclusion; BLEND_ADD -> ::blendAdd
            BLEND_SUBTRACT -> ::blendSubtract; BLEND_LINEAR_BURN -> ::blendLinearBurn
            BLEND_LINEAR_LIGHT -> ::blendLinearLight; BLEND_VIVID_LIGHT -> ::blendVividLight
            BLEND_PIN_LIGHT -> ::blendPinLight
            else -> return blendSrcOverOpacity(dst, src, opacity)
        }
        val br = blendFn(dr, sr); val bg = blendFn(dg, sg); val bb = blendFn(db, sb)
        val effA = div255(sa * opacity); val invA = 255 - effA
        val outA = effA + div255(da * invA); if (outA == 0) return 0
        val outR = (div255(br * effA) + div255(dr * da / 255 * invA)).coerceIn(0, outA)
        val outG = (div255(bg * effA) + div255(dg * da / 255 * invA)).coerceIn(0, outA)
        val outB = (div255(bb * effA) + div255(db * da / 255 * invA)).coerceIn(0, outA)
        return pack(outA, outR, outG, outB)
    }

    /** premultiplied 色の線形補間 (smudge mix 用) */
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a; if (t >= 1f) return b
        val t1 = 1f - t
        return pack(
            (alpha(a) * t1 + alpha(b) * t).toInt(),
            (red(a) * t1 + red(b) * t).toInt(),
            (green(a) * t1 + green(b) * t).toInt(),
            (blue(a) * t1 + blue(b) * t).toInt(),
        )
    }

    // ── Blend mode IDs (Drawpile blend_mode.h 準拠) ─────────────────

    const val BLEND_NORMAL = 0
    const val BLEND_MULTIPLY = 1
    const val BLEND_SCREEN = 2
    const val BLEND_OVERLAY = 3
    const val BLEND_DARKEN = 4
    const val BLEND_LIGHTEN = 5
    const val BLEND_COLOR_DODGE = 6
    const val BLEND_COLOR_BURN = 7
    const val BLEND_HARD_LIGHT = 8
    const val BLEND_SOFT_LIGHT = 9
    const val BLEND_DIFFERENCE = 10
    const val BLEND_EXCLUSION = 11
    const val BLEND_ADD = 12
    const val BLEND_SUBTRACT = 13
    const val BLEND_LINEAR_BURN = 14
    const val BLEND_ERASE = 15
    const val BLEND_HUE = 16
    const val BLEND_SATURATION = 17
    const val BLEND_COLOR = 18
    const val BLEND_LUMINOSITY = 19
    const val BLEND_LINEAR_LIGHT = 20
    const val BLEND_VIVID_LIGHT = 21
    const val BLEND_PIN_LIGHT = 22
    const val BLEND_MARKER = 23
    const val BLEND_MIX = 24

    val BLEND_MODE_NAMES = arrayOf(
        "通常", "乗算", "スクリーン", "オーバーレイ",
        "暗く", "明るく", "覆い焼きカラー", "焼き込みカラー",
        "ハードライト", "ソフトライト", "差の絶対値", "除外",
        "加算", "減算", "焼き込みリニア", "消しゴム",
        "色相", "彩度", "カラー", "輝度",
        "リニアライト", "ビビッドライト", "ピンライト", "マーカー", "混色",
    )
}
