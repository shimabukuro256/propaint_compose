package com.propaint.app.gl

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.compose.ui.graphics.Color
import com.propaint.app.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.pow
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

    companion object {
        /**
         * スタンプ間隔の sqrt 圧縮基準 (px)。
         * nominalRadius < この値: 間隔は線形 (従来通り)
         * nominalRadius ≥ この値: sqrt(radius × BASE) に圧縮し、大ブラシでも間隔が広くなりすぎない。
         *
         *   radius=30px  → spacingRadius=30   (圧縮なし)
         *   radius=100px → spacingRadius=55px
         *   radius=500px → spacingRadius=122px
         *   radius=1000px→ spacingRadius=173px
         */
        private const val SPACING_COMPRESS_BASE = 30f
    }

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
            BrushType.Blur   -> renderBlurStamps(stroke, applyExitTaper = true)
            // ベイクパスでは抜きテーパーも適用 (全ポイント確定済みのため totalLen が正確)
            BrushType.Marker -> renderCspStamps(stroke, useMaxAlpha = true, applyExitTaper = true)
            else             -> renderCspStamps(stroke, applyExitTaper = true)
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
            BrushType.Blur   -> renderBlurStamps(stroke, fromSeg, distToNextStamp)
            BrushType.Marker -> renderCspStamps(stroke, fromSeg, distToNextStamp, useMaxAlpha = true)
            else             -> renderCspStamps(stroke, fromSeg, distToNextStamp)
        }
    }

    // ── CSP スタンプ系 (Pencil / Fude / Watercolor / Airbrush / Marker) ──────

    /**
     * スタンプを配置してストロークを描画する。
     *
     * パス補間: Catmull-Rom スプライン
     *
     * 入りぬきテーパー (applyTaper=true のブラシのみ):
     *   - 入り (entry): ストローク先頭から taperLen px かけてサイズ・アルファを 0→1 に補間
     *   - 抜き (exit) : applyExitTaper=true のベイクパスのみ。末尾から同様に 1→0 に補間
     *   - 補間関数    : smoothStep (3t²-2t³) で加速度ゼロスタート・ゼロエンド
     *   - テーパー長  : 平均半径 × 8 (4直径分)、最大はストローク全長の 45%
     *   - Marker は均一線幅が特性のためテーパーなし
     *
     * スタンプ間隔の計算:
     *   nominalRadius (= b.size / 2f) を使って間隔を計算する。
     *   筆圧でサイズが変わっても間隔が変動しない (高速描画でも均一な間隔)。
     *
     * Fude / Watercolor の ぼかし筆圧モード:
     *   blurPressureThreshold > 0 かつ圧力 < 閾値 のときは renderBlurStamps と同じ
     *   2 パス方式 (dest-out + additive) で描画する。SrcOver ではアルファが増加してしまう
     *   ため、canvasAlpha の位置に既存ピクセルを置き換える形でぼかしを行う。
     *
     * @param applyExitTaper true = ベイクパス。全点確定済みなので抜きテーパーを適用する。
     *                       false = ライブパス。ストローク終点未確定のため入りのみ。
     */
    private fun renderCspStamps(
        stroke: Stroke,
        fromSegIdx: Int = 0,
        initialDistToNextStamp: Float = 0f,
        useMaxAlpha: Boolean = false,
        applyExitTaper: Boolean = false,
    ): Float {
        val b    = stroke.brush
        val pts  = stroke.points
        // 通常スタンプ頂点バッファ
        val verts      = ArrayList<Float>()
        // ぼかし筆圧モード用 2 パスバッファ (Fude / Watercolor の blur threshold 時)
        val blurEraser = ArrayList<Float>()  // Pass 1: dest-out
        val blurColor  = ArrayList<Float>()  // Pass 2: additive
        var distToNextStamp = initialDistToNextStamp

        val isFudeWc   = b.type == BrushType.Fude || b.type == BrushType.Watercolor
        val hasPointColor = isFudeWc || b.type == BrushType.Marker
        val isAirbrush = b.type == BrushType.Airbrush
        // Marker: 均一線幅が特性のためテーパーなし。Eraser: 均一消去のためテーパーなし。
        val applyTaper = b.type != BrushType.Marker && b.type != BrushType.Eraser

        // ── 累積距離の事前計算 (入りぬきテーパー用) ────────────────────────
        // ライブパスでも fromSegIdx=0 から全点分を計算する (正確な入り距離のため)。
        val cumDist = FloatArray(pts.size)
        for (k in 1 until pts.size) {
            val ddx = pts[k].position.x - pts[k - 1].position.x
            val ddy = pts[k].position.y - pts[k - 1].position.y
            cumDist[k] = cumDist[k - 1] + sqrt(ddx * ddx + ddy * ddy)
        }
        val totalLen = cumDist.last()

        // ── 名目上の半径でスタンプ間隔を計算 (筆圧変動に依存しない) ──────
        val nominalRadius = b.size / 2f
        val spacingRadius = minOf(nominalRadius, sqrt(nominalRadius * SPACING_COMPRESS_BASE))
        val stepDist = maxOf(1f, spacingRadius * 2f * b.spacing)

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            // Catmull-Rom 用の前後補助点 (境界ではクランプ)
            val pPrev = pts[maxOf(0, i - 1)]
            val pNext = pts[minOf(pts.size - 1, i + 2)]

            val dx = p1.position.x - p0.position.x
            val dy = p1.position.y - p0.position.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            val avgPressure = (p0.pressure + p1.pressure) * 0.5f
            val avgRadius = if (b.pressureSizeEnabled)
                b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressureCurveWithIntensity(avgPressure, b.pressureSizeIntensity)) / 2f
            else b.size / 2f

            // テーパー長: 平均半径 × 8 (= 4 直径分)。短いストロークでは超えないよう 45% 以下に制限。
            val rawTaperLen = if (applyTaper) (avgRadius * 8f).coerceIn(20f, 400f) else 0f
            val taperLen = if (applyTaper && totalLen > 0f)
                rawTaperLen.coerceAtMost(totalLen * 0.45f) else rawTaperLen

            var pos = distToNextStamp
            while (pos <= segLen) {
                val t = (pos / segLen).coerceIn(0f, 1f)

                // ── Catmull-Rom スプライン補間でパス上の位置を計算 ────────────
                val px = catmullRom(pPrev.position.x, p0.position.x, p1.position.x, pNext.position.x, t)
                val py = catmullRom(pPrev.position.y, p0.position.y, p1.position.y, pNext.position.y, t)

                // 筆圧・サイズは線形補間 (Catmull-Rom 不要)
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t
                val r = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressureCurveWithIntensity(pressure, b.pressureSizeIntensity)) / 2f
                else b.size / 2f

                // ── 入りぬきテーパー係数 ─────────────────────────────────────
                val taperFactor = if (applyTaper && taperLen > 0f) {
                    val distFromStart = cumDist[i] + pos
                    val entryRatio = (distFromStart / taperLen).coerceIn(0f, 1f)
                    val exitRatio  = if (applyExitTaper)
                        ((totalLen - distFromStart) / taperLen).coerceIn(0f, 1f) else 1f
                    smoothStep(minOf(entryRatio, exitRatio))
                } else 1f

                val taperR = (r * taperFactor).coerceAtLeast(0.5f)

                // ── per-stamp alpha ───────────────────────────────────────────
                //   Fude / Watercolor / Airbrush: 常に筆圧でアルファをスケール (入り抜きの自然応答)
                //   Pencil: pressureOpacityEnabled=false なら均一不透明
                //   Marker: pressureOpacityEnabled=true のとき筆圧を適用
                val pressureAlpha = when {
                    b.type == BrushType.Eraser -> 1f
                    b.type == BrushType.Marker ->
                        if (b.pressureOpacityEnabled)
                            pressureCurveWithIntensity(pressure, b.pressureOpacityIntensity)
                        else 1f
                    b.pressureOpacityEnabled ->
                        (0.2f + 0.8f * pressureCurveWithIntensity(pressure, b.pressureOpacityIntensity)).coerceIn(0f, 1f)
                    b.type == BrushType.Fude || b.type == BrushType.Watercolor || b.type == BrushType.Airbrush ->
                        pressureCurve(pressure)
                    else -> 1f
                }
                // ブラシ種別ごとの密度スケール
                // Fude / Watercolor は水分量が高いほどスタンプのアルファが下がる (描画色の薄まり)
                val effectiveDensity = when (b.type) {
                    BrushType.Fude       -> b.density * 0.45f * (1f - b.waterContent * 0.5f).coerceAtLeast(0.2f)
                    BrushType.Watercolor -> b.density * 0.18f * (1f - b.waterContent * 0.5f).coerceAtLeast(0.2f)
                    BrushType.Airbrush   -> b.density * 0.5f
                    else                 -> b.density
                }
                val stampAlpha = (effectiveDensity * pressureAlpha * taperFactor).coerceIn(0f, 1f)

                when {
                    isAirbrush -> {
                        addStampQuad(verts, px, py, taperR, stampAlpha, stroke.color.copy(alpha = 1f))
                    }

                    isFudeWc -> {
                        val a0 = p0.color?.alpha ?: 0f
                        val a1 = p1.color?.alpha ?: 0f
                        val canvasAlpha = (a0 + (a1 - a0) * t).coerceIn(0f, 1f)
                        val rgb0 = p0.color ?: stroke.color
                        val rgb1 = p1.color ?: stroke.color
                        val stampColor = lerpColor(rgb0, rgb1, t)

                        // ── ぼかし筆圧モード ─────────────────────────────────
                        // blurPressureThreshold > 0 かつ低筆圧: 色を置かず周辺色を拡散する。
                        // ViewModel の canvasBlurMix() が返した「重み付きサンプリング色」を
                        // Blur ブラシと同じ 2 パス (dest-out + additive) で書き込む。
                        // → SrcOver と異なり alpha が増加しないため純粋なぼかしになる。
                        if (b.blurPressureThreshold > 0f && pressure < b.blurPressureThreshold) {
                            val blurA = blurColor(canvasAlpha = canvasAlpha, stampAlpha = stampAlpha)
                            if (blurA > 0.001f && stampAlpha > 0.001f) {
                                addStampQuad(blurEraser, px, py, taperR, stampAlpha,  Color.Black)
                                addStampQuad(blurColor,  px, py, taperR, blurA,        stampColor.copy(alpha = 1f))
                            }
                        } else {
                            // 通常描画モード: StrokePoint.color (ViewModel 混色結果) をそのまま使用
                            addStampQuad(verts, px, py, taperR, stampAlpha, stampColor.copy(alpha = 1f))
                        }
                    }

                    hasPointColor -> {
                        // Marker
                        val c0 = p0.color ?: stroke.color
                        val c1 = p1.color ?: stroke.color
                        val stampColor = lerpColor(c0, c1, t)
                        addStampQuad(verts, px, py, taperR, stampAlpha, stampColor.copy(alpha = 1f))
                    }

                    else -> {
                        // Pencil など
                        addStampQuad(verts, px, py, taperR, stampAlpha, stroke.color.copy(alpha = 1f))
                    }
                }
                pos += stepDist
            }
            distToNextStamp = pos - segLen
        }

        if (verts.isNotEmpty())      drawStampVerts(verts, b.hardness, b.antiAliasing, useMaxAlpha)
        // ぼかし筆圧モードのスタンプを Blur と同じ 2 パスで描画 (縁クッキリ: hardness=1.0)
        if (blurEraser.isNotEmpty()) {
            drawEraserStampVerts(blurEraser, 1.0f, b.antiAliasing)
            drawStampVertsAdditive(blurColor, 1.0f, b.antiAliasing)
        }
        return distToNextStamp
    }

    /**
     * ぼかし筆圧モードのスタンプアルファ計算。
     * canvasAlpha = sampleWeightedBlurAt の avgA (ぼかし色の重み付き平均アルファ)。
     * blurAlpha × stampAlpha が Pass 2 (additive) のアルファとなる。
     * → 透明域 (canvasAlpha≈0) ではぼかすものがないため何も描画しない。
     */
    private fun blurColor(canvasAlpha: Float, stampAlpha: Float): Float =
        (canvasAlpha * stampAlpha).coerceIn(0f, 1f)

    // ── Blur スタンプ (2 パス置換レンダリング) ──────────────────────────────
    //
    // Blur ツールはキャンバス色を「置換」する必要がある。SrcOver の加算では alpha が
    // 増加してしまうため、以下の 2 パスで lerp(original, blurred, strength) を実現:
    //
    //   Pass 1 (dest-out)  : 既存ピクセルを stampAlpha 分だけ消去
    //                        → dst = dst × (1 - stampAlpha × edge)
    //   Pass 2 (additive)  : ブラー色を blurAlpha × stampAlpha 分だけ加算
    //                        → dst += blurredRGB × blurAlpha × stampAlpha × edge
    //                           dst.a += blurAlpha × stampAlpha × edge
    //
    // 合成結果: dst = lerp(original, blurred_premul, stampAlpha × edge)
    // 透明度も正しく扱われる (blurAlpha < original.a なら alpha が減少)。

    private fun renderBlurStamps(
        stroke: Stroke,
        fromSegIdx: Int = 0,
        initialDistToNextStamp: Float = 0f,
        applyExitTaper: Boolean = false,
    ): Float {
        val b   = stroke.brush
        val pts = stroke.points
        var distToNextStamp = initialDistToNextStamp

        val eraserVerts = ArrayList<Float>()  // Pass 1: dest-out
        val colorVerts  = ArrayList<Float>()  // Pass 2: additive

        val cumDist = FloatArray(pts.size)
        for (k in 1 until pts.size) {
            val ddx = pts[k].position.x - pts[k - 1].position.x
            val ddy = pts[k].position.y - pts[k - 1].position.y
            cumDist[k] = cumDist[k - 1] + sqrt(ddx * ddx + ddy * ddy)
        }
        val totalLen = cumDist.last()

        // スタンプ間隔は nominal radius で計算
        val nominalRadius = b.size / 2f
        val spacingRadius = minOf(nominalRadius, sqrt(nominalRadius * SPACING_COMPRESS_BASE))
        val stepDist = maxOf(1f, spacingRadius * 2f * b.spacing)

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val pPrev = pts[maxOf(0, i - 1)]
            val pNext = pts[minOf(pts.size - 1, i + 2)]

            val dx = p1.position.x - p0.position.x
            val dy = p1.position.y - p0.position.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            val avgPressure = (p0.pressure + p1.pressure) * 0.5f
            val avgRadius = if (b.pressureSizeEnabled)
                b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressureCurveWithIntensity(avgPressure, b.pressureSizeIntensity)) / 2f
            else b.size / 2f

            val rawTaperLen = (avgRadius * 8f).coerceIn(20f, 400f)
            val taperLen = rawTaperLen.coerceAtMost(totalLen * 0.45f)

            var pos = distToNextStamp
            while (pos <= segLen) {
                val t = (pos / segLen).coerceIn(0f, 1f)
                val px = catmullRom(pPrev.position.x, p0.position.x, p1.position.x, pNext.position.x, t)
                val py = catmullRom(pPrev.position.y, p0.position.y, p1.position.y, pNext.position.y, t)
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t
                val r = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressureCurveWithIntensity(pressure, b.pressureSizeIntensity)) / 2f
                else b.size / 2f

                val taperFactor = if (taperLen > 0f) {
                    val distFromStart = cumDist[i] + pos
                    val entryRatio = (distFromStart / taperLen).coerceIn(0f, 1f)
                    val exitRatio  = if (applyExitTaper)
                        ((totalLen - distFromStart) / taperLen).coerceIn(0f, 1f) else 1f
                    smoothStep(minOf(entryRatio, exitRatio))
                } else 1f

                val taperR     = (r * taperFactor).coerceAtLeast(0.5f)
                val stampAlpha = (b.density * pressureCurve(pressure) * taperFactor).coerceIn(0f, 1f)

                // ブラー色: StrokePoint.color = canvasMix() のボックスブラー結果
                val c0 = p0.color ?: Color(0f, 0f, 0f, 0f)
                val c1 = p1.color ?: Color(0f, 0f, 0f, 0f)
                val blurColor = lerpColor(c0, c1, t)
                val blurAlpha = blurColor.alpha  // ブラーされた近傍の平均 alpha

                if (blurAlpha > 0.001f && stampAlpha > 0.001f) {
                    // Pass 1: この位置の既存ピクセルを消去
                    addStampQuad(eraserVerts, px, py, taperR, stampAlpha, Color.Black)
                    // Pass 2: ブラー色をプリマルチプライド加算
                    addStampQuad(colorVerts, px, py, taperR, blurAlpha * stampAlpha, blurColor.copy(alpha = 1f))
                }

                pos += stepDist
            }
            distToNextStamp = pos - segLen
        }

        if (eraserVerts.isNotEmpty()) {
            drawEraserStampVerts(eraserVerts, 1.0f, b.antiAliasing)   // dest-out blend (縁クッキリ)
            drawStampVertsAdditive(colorVerts, 1.0f, b.antiAliasing)  // GL_ONE, GL_ONE blend (縁クッキリ)
        }
        return distToNextStamp
    }

    /** 加算ブレンド (GL_ONE, GL_ONE) でスタンプを描画。Blur Pass 2 専用。 */
    private fun drawStampVertsAdditive(verts: ArrayList<Float>, hardness: Float, aa: Float) {
        if (verts.isEmpty()) return
        val buf = verts.toFB()
        stampProg.use()
        GLES20.glUniformMatrix4fv(stampProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(stampProg.uniform("uHardness"), hardness.coerceIn(0f, 1f))
        GLES20.glUniform1f(stampProg.uniform("uAA"), aa.coerceIn(0f, 4f))
        val posLoc   = stampProg.attrib("aPos")
        val uvLoc    = stampProg.attrib("aUV")
        val alphaLoc = stampProg.attrib("aAlpha")
        val colorLoc = stampProg.attrib("aColor")
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
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts.size / 9)
        // 標準プリマルチプライドブレンドに戻す
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(alphaLoc)
        GLES20.glDisableVertexAttribArray(colorLoc)
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

        // スタンプ間隔は nominal radius で計算
        val nominalRadius = b.size / 2f
        val spacingRadius = minOf(nominalRadius, sqrt(nominalRadius * SPACING_COMPRESS_BASE))
        val stepDist = maxOf(1f, spacingRadius * 2f * b.spacing)

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val dx = p1.position.x - p0.position.x
            val dy = p1.position.y - p0.position.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            var pos = distToNextStamp
            while (pos <= segLen) {
                val t = (pos / segLen).coerceIn(0f, 1f)
                val px = p0.position.x + dx * t
                val py = p0.position.y + dy * t
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t

                val r = if (b.pressureSizeEnabled)
                    b.size * (b.minSizeRatio + (1f - b.minSizeRatio) * pressureCurveWithIntensity(pressure, b.pressureSizeIntensity)) / 2f
                else b.size / 2f

                val pressureAlpha = if (b.pressureSizeEnabled)
                    (0.2f + 0.8f * pressureCurve(pressure)).coerceIn(0f, 1f) else 1f
                // 消しゴムの alpha は density × pressureAlpha (opacity=1 固定)
                val stampAlpha = (b.density * pressureAlpha).coerceIn(0f, 1f)

                // ERASER_STAMP_FRAG は color 属性を使わないが stride を合わせるため Color.Black を渡す
                addStampQuad(verts, px, py, r, stampAlpha, Color.Black)
                pos += stepDist
            }
            distToNextStamp = pos - segLen
        }

        if (verts.isNotEmpty()) drawEraserStampVerts(verts, b.hardness, b.antiAliasing)
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
        aa: Float,
        useMaxAlpha: Boolean = false,
    ) {
        val buf = verts.toFB()
        stampProg.use()
        GLES20.glUniformMatrix4fv(stampProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(stampProg.uniform("uHardness"), hardness.coerceIn(0f, 1f))
        GLES20.glUniform1f(stampProg.uniform("uAA"), aa.coerceIn(0f, 4f))
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
            // Marker: RGB はプリマルチプライド SrcOver, alpha は max(src, dst)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_MAX)
        } else {
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts.size / 9)
        if (useMaxAlpha) {
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        }
        // blend をプリマルチプライド標準に戻す
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(alphaLoc)
        GLES20.glDisableVertexAttribArray(colorLoc)
    }

    // ── スタンプ描画 (消しゴム: destination-out) ──────────────────────────

    private fun drawEraserStampVerts(verts: ArrayList<Float>, hardness: Float, aa: Float) {
        val buf = verts.toFB()
        eraserStampProg.use()
        GLES20.glUniformMatrix4fv(eraserStampProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(eraserStampProg.uniform("uHardness"), hardness.coerceIn(0f, 1f))
        GLES20.glUniform1f(eraserStampProg.uniform("uAA"), aa.coerceIn(0f, 4f))
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
        // blend をプリマルチプライド標準に戻す
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(alphaLoc)
        GLES20.glDisableVertexAttribArray(colorLoc)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Catmull-Rom スプライン補間。
     * p1→p2 間 (t=0..1) を補間し、p0/p3 が曲線の接線を決める制御点。
     * 境界では p0=p1 または p3=p2 とクランプして使う。
     */
    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * (
            (2f * p1) +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3
        )
    }

    /**
     * スムーズステップ: f(t) = 3t² - 2t³
     * t=0 と t=1 の両端で微分がゼロになるため、急激な変化なく 0→1 を補間できる。
     * 入りぬきテーパーの入力 [0,1] に適用して自然な加減速感を出す。
     */
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * 筆圧感度カーブ: p^0.65 (標準)
     * smoothStep (S 字) と異なり低〜中筆圧域の感度が高い。
     * 微妙な強弱がサイズ・アルファに素直に反映される。
     *   p=0.1 → 0.22, p=0.3 → 0.51, p=0.5 → 0.64, p=0.7 → 0.78
     */
    private fun pressureCurve(p: Float): Float = p.coerceIn(0f, 1f).toDouble().pow(0.65).toFloat()

    /**
     * 感度付き筆圧カーブ: p^gamma
     * intensity=1..100: gamma = 0.1..0.65 (低強度=ほぼ均一, 標準=0.65)
     * intensity=101..200: gamma = 0.65..2.0 (高強度=急峻な感度)
     */
    private fun pressureCurveWithIntensity(p: Float, intensity: Int): Float {
        val gamma = if (intensity <= 100) {
            0.1f + (intensity - 1) / 99f * 0.55f
        } else {
            0.65f + (intensity - 100) / 100f * 1.35f
        }
        return p.coerceIn(0f, 1f).toDouble().pow(gamma.toDouble()).toFloat()
    }

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
