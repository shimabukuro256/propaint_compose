package com.propaint.app.gl

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.compose.ui.graphics.Color
import com.propaint.app.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * ストロークを GPU 上で描画するレンダラー。
 *
 * すべてのブラシ先端は円形スタンプ (STAMP_VERT + STAMP_FRAG / ERASER_STAMP_FRAG)。
 * 矩形ラインクワッドは廃止。
 *
 * ブラシ種別:
 *  - Pencil    : 円形スタンプ (hardness=1 近辺, density×opacity alpha)
 *  - Fude      : CSP スタンプ, StrokePoint.color を混色結果として使用
 *  - Watercolor: CSP スタンプ, StrokePoint.color (ボックスブラー混色)
 *  - Airbrush  : 単一ソフトスタンプ (hardness=0, Photoshop 風ソフトエッジ)
 *  - Marker    : CSP スタンプ, StrokePoint.color + GLES30 GL_MAX alpha blend
 *  - Eraser    : 円形スタンプ, ERASER_STAMP_FRAG + destination-out blend
 *  - Blur      : CSP スタンプ, StrokePoint.color (ブラシ色なし)
 *
 * α処理:
 *  - 非消しゴム: per-stamp alpha = density × pressureAlpha (opacity は CanvasGlRenderer 側で合成)
 *  - 消しゴム  : per-stamp alpha = pressureAlpha (hardness エッジは ERASER_STAMP_FRAG 内)
 */
internal class GlBrushRenderer {

    private lateinit var stampProg:       GlProgram
    private lateinit var eraserStampProg: GlProgram

    private val mvp = FloatArray(16)

    fun init() {
        stampProg       = GlProgram(Shaders.STAMP_VERT, Shaders.STAMP_FRAG)
        eraserStampProg = GlProgram(Shaders.STAMP_VERT, Shaders.ERASER_STAMP_FRAG)
    }

    fun setProjection(w: Float, h: Float) { ortho(mvp, 0f, w, h, 0f) }

    fun delete() {
        stampProg.delete()
        eraserStampProg.delete()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * ストローク全体を FBO へ描画。
     * baking パス (committed stroke) で使用。layerAlpha は常に 1f。
     * opacity は CanvasGlRenderer 側で合成時に uAlpha として適用される。
     */
    fun renderStroke(stroke: Stroke) {
        if (stroke.points.size < 2) return
        when (stroke.brush.type) {
            BrushType.Eraser -> renderEraserStamps(stroke)
            BrushType.Marker -> renderCspStamps(stroke, useMaxAlpha = true)
            else             -> renderCspStamps(stroke)
        }
    }

    /**
     * インクリメンタル描画 (live stroke)。
     * opacity は CanvasGlRenderer 側で rebuildLiveFromMarks() 時に適用。
     * 戻り値: 次スタンプまでの残り距離 (carry-over)。
     */
    fun renderStrokeSegments(
        stroke: Stroke,
        fromPointIdx: Int,
        distToNextStamp: Float,
    ): Float {
        if (stroke.points.size < 2) return distToNextStamp
        val fromSeg = maxOf(0, fromPointIdx - 1)
        return when (stroke.brush.type) {
            BrushType.Eraser -> renderEraserStamps(stroke, fromSeg, distToNextStamp)
            BrushType.Marker -> renderCspStamps(stroke, fromSeg, distToNextStamp, useMaxAlpha = true)
            else             -> renderCspStamps(stroke, fromSeg, distToNextStamp)
        }
    }

    // ── CSP スタンプ系 (Pencil / Fude / Watercolor / Airbrush / Marker / Blur) ──

    private fun renderCspStamps(
        stroke: Stroke,
        fromSegIdx: Int = 0,
        initialDistToNextStamp: Float = 0f,
        useMaxAlpha: Boolean = false,
    ): Float {
        val b    = stroke.brush
        val pts  = stroke.points
        val verts = ArrayList<Float>()
        var distToNextStamp = initialDistToNextStamp

        val hasPointColor = b.type == BrushType.Fude || b.type == BrushType.Watercolor ||
                            b.type == BrushType.Marker || b.type == BrushType.Blur
        val isAirbrush    = b.type == BrushType.Airbrush

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val dx = p1.position.x - p0.position.x
            val dy = p1.position.y - p0.position.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            val avgPressure = (p0.pressure + p1.pressure) * 0.5f
            val avgRadius = if (b.pressureSizeEnabled)
                b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * avgPressure) / 2f
            else b.size / 2f
            val stepDist = maxOf(1f, avgRadius * 2f * b.spacing)

            var pos = distToNextStamp
            while (pos <= segLen) {
                val t = (pos / segLen).coerceIn(0f, 1f)
                val px = p0.position.x + dx * t
                val py = p0.position.y + dy * t
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t

                val r = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressure) / 2f
                else b.size / 2f

                // opacity は CanvasGlRenderer 側で合成時に適用するため、ここでは density × pressure のみ。
                val pressureAlpha = if (b.pressureOpacityEnabled)
                    (0.2f + 0.8f * pressure).coerceIn(0f, 1f) else 1f
                // Blur は density のみ (brush opacity 不適用)
                val stampAlpha = if (b.type == BrushType.Blur)
                    (b.density * pressureAlpha).coerceIn(0f, 1f)
                else
                    (b.density * pressureAlpha).coerceIn(0f, 1f)

                when {
                    isAirbrush -> {
                        // 単一ソフトスタンプ (Photoshop の「硬さを下げた」円ブラシ相当)
                        // hardness は b.hardness (デフォルト=0) でフラグメントシェーダーが softclip
                        addStampQuad(verts, px, py, r, stampAlpha, stroke.color.copy(alpha = 1f))
                    }
                    hasPointColor -> {
                        val c0 = p0.color ?: stroke.color
                        val c1 = p1.color ?: stroke.color
                        val stampColor = lerpColor(c0, c1, t)
                        addStampQuad(verts, px, py, r, stampAlpha, stampColor.copy(alpha = 1f))
                    }
                    else -> {
                        // Pencil など
                        addStampQuad(verts, px, py, r, stampAlpha, stroke.color.copy(alpha = 1f))
                    }
                }
                pos += stepDist
            }
            distToNextStamp = pos - segLen
        }

        if (verts.isNotEmpty()) drawStampVerts(verts, b.hardness, useMaxAlpha)
        return distToNextStamp
    }

    // ── Eraser スタンプ ────────────────────────────────────────────────────

    private fun renderEraserStamps(
        stroke: Stroke,
        fromSegIdx: Int = 0,
        initialDistToNextStamp: Float = 0f,
    ): Float {
        val b   = stroke.brush
        val pts = stroke.points
        val verts = ArrayList<Float>()
        var distToNextStamp = initialDistToNextStamp

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val dx = p1.position.x - p0.position.x
            val dy = p1.position.y - p0.position.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            val avgPressure = (p0.pressure + p1.pressure) * 0.5f
            val avgRadius = if (b.pressureSizeEnabled)
                b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * avgPressure) / 2f
            else b.size / 2f
            val stepDist = maxOf(1f, avgRadius * 2f * b.spacing)

            var pos = distToNextStamp
            while (pos <= segLen) {
                val t = (pos / segLen).coerceIn(0f, 1f)
                val px = p0.position.x + dx * t
                val py = p0.position.y + dy * t
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t

                val r = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressure) / 2f
                else b.size / 2f

                val pressureAlpha = if (b.pressureSizeEnabled)
                    (0.2f + 0.8f * pressure).coerceIn(0f, 1f) else 1f
                // 消しゴムの alpha は density × pressureAlpha (opacity=1 固定)
                val stampAlpha = (b.density * pressureAlpha).coerceIn(0f, 1f)

                // ERASER_STAMP_FRAG は color 属性を使わないが stride を合わせるため Color.Black を渡す
                addStampQuad(verts, px, py, r, stampAlpha, Color.Black)
                pos += stepDist
            }
            distToNextStamp = pos - segLen
        }

        if (verts.isNotEmpty()) drawEraserStampVerts(verts, b.hardness)
        return distToNextStamp
    }

    // ── スタンプクワッド生成 ──────────────────────────────────────────────

    private fun addStampQuad(
        verts: ArrayList<Float>,
        cx: Float, cy: Float, r: Float,
        alpha: Float, color: Color,
    ) {
        // stride=9 floats: [x, y, u, v, alpha, r, g, b, a]
        val cr = color.red; val cg = color.green; val cb = color.blue; val ca = color.alpha
        fun v(x: Float, y: Float, u: Float, vv: Float) {
            verts.add(x); verts.add(y); verts.add(u); verts.add(vv)
            verts.add(alpha); verts.add(cr); verts.add(cg); verts.add(cb); verts.add(ca)
        }
        v(cx - r, cy - r, 0f, 0f)
        v(cx + r, cy - r, 1f, 0f)
        v(cx + r, cy + r, 1f, 1f)
        v(cx - r, cy - r, 0f, 0f)
        v(cx + r, cy + r, 1f, 1f)
        v(cx - r, cy + r, 0f, 1f)
    }

    // ── スタンプ描画 (通常) ───────────────────────────────────────────────

    private fun drawStampVerts(
        verts: ArrayList<Float>,
        hardness: Float,
        useMaxAlpha: Boolean = false,
    ) {
        val buf = verts.toFB()
        stampProg.use()
        GLES20.glUniformMatrix4fv(stampProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(stampProg.uniform("uHardness"), hardness.coerceIn(0f, 1f))
        val posLoc   = stampProg.attrib("aPos")
        val uvLoc    = stampProg.attrib("aUV")
        val alphaLoc = stampProg.attrib("aAlpha")
        val colorLoc = stampProg.attrib("aColor")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glEnableVertexAttribArray(alphaLoc)
        GLES20.glEnableVertexAttribArray(colorLoc)
        val stride = 36  // 9 floats × 4 bytes
        buf.position(0); GLES20.glVertexAttribPointer(posLoc,   2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(2); GLES20.glVertexAttribPointer(uvLoc,    2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(4); GLES20.glVertexAttribPointer(alphaLoc, 1, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(5); GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT, false, stride, buf)
        GLES20.glEnable(GLES20.GL_BLEND)
        if (useMaxAlpha) {
            // Marker: RGB は SrcOver, alpha は max(src, dst)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_MAX)
        } else {
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE,       GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts.size / 9)
        if (useMaxAlpha) {
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        }
        // blend を標準に戻す
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(alphaLoc)
        GLES20.glDisableVertexAttribArray(colorLoc)
    }

    // ── スタンプ描画 (消しゴム: destination-out) ──────────────────────────

    private fun drawEraserStampVerts(verts: ArrayList<Float>, hardness: Float) {
        val buf = verts.toFB()
        eraserStampProg.use()
        GLES20.glUniformMatrix4fv(eraserStampProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(eraserStampProg.uniform("uHardness"), hardness.coerceIn(0f, 1f))
        val posLoc   = eraserStampProg.attrib("aPos")
        val uvLoc    = eraserStampProg.attrib("aUV")
        val alphaLoc = eraserStampProg.attrib("aAlpha")
        val colorLoc = eraserStampProg.attrib("aColor")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glEnableVertexAttribArray(alphaLoc)
        GLES20.glEnableVertexAttribArray(colorLoc)
        val stride = 36
        buf.position(0); GLES20.glVertexAttribPointer(posLoc,   2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(2); GLES20.glVertexAttribPointer(uvLoc,    2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(4); GLES20.glVertexAttribPointer(alphaLoc, 1, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(5); GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT, false, stride, buf)
        GLES20.glEnable(GLES20.GL_BLEND)
        // destination-out: α を削る (RGB は変えない)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts.size / 9)
        // blend を標準に戻す
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(alphaLoc)
        GLES20.glDisableVertexAttribArray(colorLoc)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun lerpColor(a: Color, b: Color, t: Float) = Color(
        red   = a.red   + (b.red   - a.red)   * t,
        green = a.green + (b.green - a.green) * t,
        blue  = a.blue  + (b.blue  - a.blue)  * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
}

// ── FloatBuffer utilities ─────────────────────────────────────────────────

internal fun ArrayList<Float>.toFB(): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer()
    forEach { fb.put(it) }
    fb.position(0)
    return fb
}

internal fun FloatArray.toFB(): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer()
    fb.put(this); fb.position(0)
    return fb
}

/** OpenGL 左上原点 Y 軸下向き ortho 行列 (column-major) */
internal fun ortho(m: FloatArray, left: Float, right: Float, bottom: Float, top: Float) {
    val rw = 2f / (right - left)
    val rh = 2f / (top - bottom)
    m[ 0] = rw;  m[ 4] = 0f;  m[ 8] = 0f;  m[12] = -(right + left) / (right - left)
    m[ 1] = 0f;  m[ 5] = rh;  m[ 9] = 0f;  m[13] = -(top + bottom) / (top - bottom)
    m[ 2] = 0f;  m[ 6] = 0f;  m[10] = -1f; m[14] = 0f
    m[ 3] = 0f;  m[ 7] = 0f;  m[11] = 0f;  m[15] = 1f
}
