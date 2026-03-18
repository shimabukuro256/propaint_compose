package com.propaint.app.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.compose.ui.graphics.Color
import com.propaint.app.model.*
import com.propaint.app.model.needsWetCanvas
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

// ── レンダリングスナップショット ──────────────────────────────────────────

data class RenderSnapshot(
    val layers: List<PaintLayer>,
    val activeLayerId: String,
    val currentPoints: List<StrokePoint>,
    val currentBrush: BrushSettings,
    val currentColor: Color,
    val showGrid: Boolean,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val rotation: Float = 0f,
    val filterPreview: LayerFilter? = null,
    val filterLayerId: String = "",
    val docWidth: Int = 0,   // 0 = use surface dims
    val docHeight: Int = 0,  // 0 = use surface dims
)

// ── CanvasGlRenderer ─────────────────────────────────────────────────────

/**
 * 3 段階レンダリングパイプライン:
 *
 * 1. [updateLayerFbos]  — ストローク差分を各レイヤー FBO へベイク
 * 2. [buildCompositeCache] — 全レイヤーを compositeCache FBO へ合成
 * 3. [drawCacheToScreen] — compositeCache を zoom/pan 付きで画面へ 1 quad 描画
 *
 * ライブストロークアルファ処理:
 *  - strokeSnapshotFbo : ストローク開始時の layerFbo のコピー
 *  - strokeMarksFbo    : 現在ストロークのスタンプ蓄積 (density×pressure alpha のみ)
 *  - liveStrokeFbo     : snapshot + marks を brush.opacity で合成した最終レイヤー画像
 *  各フレーム: marks に新規スタンプを追加 → rebuildLiveFromMarks() で liveStrokeFbo 再合成。
 *  → ストローク内でスタンプが重なっても brush.opacity を超えてアルファが蓄積しない。
 *
 * 消しゴムはスタンプが直接 liveStrokeFbo へ (opacity 上限がないため marks FBO 不要)。
 *
 * ベイク時も同様: bakingMarksFbo にスタンプ→ layerFbo へ brush.opacity で合成。
 */
internal class CanvasGlRenderer : GLSurfaceView.Renderer {

    val pendingSnapshot  = AtomicReference<RenderSnapshot?>()
    val pendingTransform = AtomicReference<FloatArray?>()
    private val watercolorCaptureRequest =
        AtomicReference<Pair<String, (ByteArray, Int, Int) -> Unit>?>()

    // ── Layer pixel upload (for loading saved canvases) ──────────────────
    private data class LayerPixelBatch(
        val uploads: List<Triple<String, ByteArray, Int>>,  // (layerId, pixels, unused)
        val width: Int,
        val height: Int,
        val onDone: () -> Unit,
    )
    private val pendingPixelBatch = AtomicReference<LayerPixelBatch?>()

    fun queueLayerPixelUploads(
        uploads: List<Pair<String, ByteArray>>,
        width: Int,
        height: Int,
        onDone: () -> Unit = {},
    ) {
        pendingPixelBatch.set(LayerPixelBatch(
            uploads.map { (id, px) -> Triple(id, px, 0) },
            width, height, onDone,
        ))
    }

    // コンポジットキャプチャリクエスト (スポイト用)
    private val compositeCaptureRequest =
        AtomicReference<((ByteArray, Int, Int) -> Unit)?>()

    // 全レイヤーキャプチャリクエスト (PSD エクスポート用)
    private data class AllLayersCaptureReq(
        val layerIds: List<String>,
        val callback: (List<Pair<String, ByteArray>>, Int, Int) -> Unit,
    )
    private val allLayersCaptureRequest = AtomicReference<AllLayersCaptureReq?>()

    var surfaceWidth  = 0; private set
    var surfaceHeight = 0; private set

    // ── GL プログラム ────────────────────────────────────────────────────

    private lateinit var brushRenderer:     GlBrushRenderer
    private lateinit var normalComposite:   GlProgram
    private lateinit var multiplyComposite: GlProgram
    private lateinit var screenComposite:   GlProgram
    private lateinit var overlayComposite:  GlProgram
    private lateinit var darkenComposite:   GlProgram
    private lateinit var lightenComposite:  GlProgram
    private lateinit var gridProgram:       GlProgram
    private lateinit var filterHslProg:      GlProgram
    private lateinit var filterBlurHProg:    GlProgram
    private lateinit var filterBlurVProg:    GlProgram
    private lateinit var filterContrastProg: GlProgram
    private var filterTempFbo:  LayerFbo? = null
    private var filterTemp2Fbo: LayerFbo? = null
    private var lastFilterHash: Int = 0
    private var lastFilterLayerId: String = ""

    // ── FBO ─────────────────────────────────────────────────────────────

    private val layerFbos         = mutableMapOf<String, LayerFbo>()
    private val layerStrokeCounts = mutableMapOf<String, Int>()

    // ping-pong (レイヤー合成作業用)
    private var compA: LayerFbo? = null
    private var compB: LayerFbo? = null

    // ── ライブストローク FBO ─────────────────────────────────────────────
    //
    // 【通常ブラシ (Pencil / Marker / Airbrush)】
    //   strokeSnapshotFbo: ストローク開始時の layerFbo のコピー (読み取り専用)
    //   strokeMarksFbo   : 現在ストロークの全スタンプ蓄積 (opacity 適用前)
    //   liveStrokeFbo    : snapshot + marks×opacity の合成結果 (表示・確定用)
    //
    // 【水彩系ブラシ (Fude / Watercolor / Blur) — ウェットキャンバス方式】
    //   wetCanvasFbo     : layerFbo のコピーとして開始し、スタンプを直接書き込む
    //   liveStrokeFbo    = wetCanvasFbo (同じオブジェクト)
    //   → 各スタンプが既存ピクセルと即座にブレンドされ、真の混色が実現する
    //   → ストローク中に周期的に wetCanvasFbo を再キャプチャし CPU 側混色も更新
    //
    // Eraser は直接 liveStrokeFbo へ描画 (destination-out で opacity 上限不要)。
    private var strokeSnapshotFbo:       LayerFbo? = null
    private var strokeMarksFbo:          LayerFbo? = null
    private var liveStrokeFbo:           LayerFbo? = null
    private var wetCanvasFbo:            LayerFbo? = null   // 水彩系ブラシ専用
    private var liveStrokeActiveLayerId: String?  = null
    private var lastLivePointCount:      Int      = 0
    private var liveDistToNextStamp:     Float    = 0f
    private var liveIsEraser:            Boolean  = false
    private var liveIsMixing:            Boolean  = false   // 水彩系フラグ
    // endStroke 直後にスワップ待ちのレイヤー ID
    private var pendingLiveMergeLayerId: String? = null

    // ベイク用一時 FBO (committed stroke のスタンプ蓄積)
    private var bakingMarksFbo: LayerFbo? = null

    // 全レイヤー合成結果のキャッシュ
    private var compositeCache: LayerFbo? = null

    // ── 変化追跡 ─────────────────────────────────────────────────────────

    private data class LayerMeta(
        val strokeCount: Int,
        val isVisible: Boolean,
        val opacity: Float,
        val blendModeOrdinal: Int,
        val filterHash: Int = 0,
    )
    private val layerMeta     = mutableMapOf<String, LayerMeta>()
    private var lastLayerOrder = emptyList<String>()
    private var lastLiveSize   = -1
    private var lastActiveId   = ""
    private var lastShowGrid   = false
    private var lastSnapshot: RenderSnapshot? = null
    private var currentDocWidth  = 0
    private var currentDocHeight = 0

    // ── GLSurfaceView.Renderer ───────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        brushRenderer     = GlBrushRenderer().also { it.init() }
        normalComposite   = GlProgram(Shaders.COMPOSITE_VERT, Shaders.COMPOSITE_FRAG_NORMAL)
        multiplyComposite = GlProgram(Shaders.COMPOSITE_VERT, Shaders.COMPOSITE_FRAG_MULTIPLY)
        screenComposite   = GlProgram(Shaders.COMPOSITE_VERT, Shaders.COMPOSITE_FRAG_SCREEN)
        overlayComposite  = GlProgram(Shaders.COMPOSITE_VERT, Shaders.COMPOSITE_FRAG_OVERLAY)
        darkenComposite   = GlProgram(Shaders.COMPOSITE_VERT, Shaders.COMPOSITE_FRAG_DARKEN)
        lightenComposite  = GlProgram(Shaders.COMPOSITE_VERT, Shaders.COMPOSITE_FRAG_LIGHTEN)
        gridProgram       = GlProgram(Shaders.LINE_VERT, Shaders.LINE_FRAG)
        filterHslProg      = GlProgram(Shaders.COMPOSITE_VERT, Shaders.FILTER_HSL_FRAG)
        filterBlurHProg    = GlProgram(Shaders.COMPOSITE_VERT, Shaders.FILTER_BLUR_H_FRAG)
        filterBlurVProg    = GlProgram(Shaders.COMPOSITE_VERT, Shaders.FILTER_BLUR_V_FRAG)
        filterContrastProg = GlProgram(Shaders.COMPOSITE_VERT, Shaders.FILTER_CONTRAST_FRAG)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth  = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        brushRenderer.setProjection(width.toFloat(), height.toFloat())

        layerFbos.values.forEach { it.delete() }; layerFbos.clear()
        layerStrokeCounts.clear(); layerMeta.clear()

        strokeSnapshotFbo?.delete(); strokeSnapshotFbo = null
        strokeMarksFbo?.delete();    strokeMarksFbo    = null
        liveStrokeFbo?.delete();     liveStrokeFbo     = null
        wetCanvasFbo?.delete();      wetCanvasFbo      = null
        bakingMarksFbo?.delete();    bakingMarksFbo    = null
        compositeCache?.delete();    compositeCache    = null
        filterTempFbo?.delete();  filterTempFbo  = null
        filterTemp2Fbo?.delete(); filterTemp2Fbo = null
        val (dw, dh) = effectiveDoc(lastSnapshot)
        compA?.delete(); compA = LayerFbo(dw, dh)
        compB?.delete(); compB = LayerFbo(dw, dh)
        currentDocWidth = dw; currentDocHeight = dh

        liveStrokeActiveLayerId = null
        lastLivePointCount      = 0
        liveDistToNextStamp     = 0f
        liveIsEraser            = false
        liveIsMixing            = false
        pendingLiveMergeLayerId = null

        lastLiveSize   = -1
        lastLayerOrder = emptyList()

        lastSnapshot?.let { s -> rebuildAll(s.layers, effectiveDoc(s).first, effectiveDoc(s).second) }
    }

    override fun onDrawFrame(gl: GL10?) {
        val fastT   = pendingTransform.getAndSet(null)
        val newSnap = pendingSnapshot.getAndSet(null)

        if (newSnap == null && fastT != null) {
            val base = lastSnapshot ?: return
            val updated = base.copy(
                zoom = fastT[0], panX = fastT[1], panY = fastT[2], rotation = fastT[3],
            )
            lastSnapshot = updated
            val w = surfaceWidth; val h = surfaceHeight
            if (w == 0 || h == 0) return
            val cache = compositeCache ?: return
            drawCacheToScreen(updated, w, h, cache)
            return
        }

        val snap = newSnap ?: lastSnapshot ?: return
        lastSnapshot = snap

        val w = surfaceWidth; val h = surfaceHeight
        if (w == 0 || h == 0) return

        val (docW, docH) = effectiveDoc(snap)
        // ドキュメントサイズが変わった場合 (新規キャンバス / インポート) は合成用 FBO を再生成
        if (docW != currentDocWidth || docH != currentDocHeight) {
            compositeCache?.delete(); compositeCache = null
            compA?.delete(); compA = LayerFbo(docW, docH)
            compB?.delete(); compB = LayerFbo(docW, docH)
            currentDocWidth = docW; currentDocHeight = docH
        }
        // ブラシ投影はドキュメント座標系で設定
        brushRenderer.setProjection(docW.toFloat(), docH.toFloat())

        // ── Layer pixel uploads (for loading saved canvases / importing images) ──
        pendingPixelBatch.getAndSet(null)?.let { batch ->
            val bw = batch.width; val bh = batch.height
            layerFbos.values.forEach { it.delete() }
            layerFbos.clear()
            layerStrokeCounts.clear()
            layerMeta.clear()
            for ((layerId, pixels, _) in batch.uploads) {
                // FBO はドキュメントサイズ (bw×bh) で作成。
                // 表示時の letterbox は drawCacheToScreen が担うため、ここでストレッチしない。
                val fbo = LayerFbo(bw, bh)
                fbo.clear()
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                val texId = texIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val buf = java.nio.ByteBuffer.wrap(pixels)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bw, bh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                fbo.bind()
                GLES20.glDisable(GLES20.GL_BLEND)
                drawTexFlat(normalComposite, texId, -1, 1f, bw, bh)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteTextures(1, texIds, 0)
                layerFbos[layerId] = fbo
                layerStrokeCounts[layerId] = 0
            }
            lastLayerOrder = emptyList()
            compositeCache?.let { buildCompositeCache(snap, docW, docH, it) }
            batch.onDone()
        }

        watercolorCaptureRequest.getAndSet(null)?.let { (id, cb) ->
            captureLayerPixels(id, cb)
        }

        updateLiveStrokeFbo(snap, docW, docH)

        val layerFbosChanged = updateLayerFbos(snap, docW, docH)

        val liveSize    = snap.currentPoints.size
        val liveChanged = liveSize != lastLiveSize
        val metaChanged = snap.showGrid != lastShowGrid || snap.activeLayerId != lastActiveId
        if (liveChanged) lastLiveSize = liveSize
        if (metaChanged) { lastShowGrid = snap.showGrid; lastActiveId = snap.activeLayerId }

        val filterHash = snap.filterPreview?.hashCode() ?: 0
        val filterChanged = filterHash != lastFilterHash || snap.filterLayerId != lastFilterLayerId
        if (filterChanged) { lastFilterHash = filterHash; lastFilterLayerId = snap.filterLayerId }

        val contentChanged = layerFbosChanged || liveChanged || metaChanged || filterChanged

        val cache = compositeCache ?: LayerFbo(docW, docH).also { compositeCache = it }
        if (contentChanged) buildCompositeCache(snap, docW, docH, cache)

        drawCacheToScreen(snap, w, h, cache)

        // コンポジットキャプチャリクエストの処理 (スポイト用)
        compositeCaptureRequest.getAndSet(null)?.let { cb ->
            captureCompositePixels(cb)
        }

        // 全レイヤーキャプチャリクエストの処理 (PSD エクスポート用)
        allLayersCaptureRequest.getAndSet(null)?.let { req ->
            val result = mutableListOf<Pair<String, ByteArray>>()
            for (id in req.layerIds) {
                val fbo = layerFbos[id] ?: continue
                fbo.bind()
                val bb = ByteBuffer.allocateDirect(docW * docH * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, docW, docH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                bb.position(0)
                val bytes = ByteArray(docW * docH * 4); bb.get(bytes)
                result.add(Pair(id, bytes))
            }
            req.callback(result, docW, docH)
        }
    }

    // ── ライブストローク FBO ──────────────────────────────────────────────
    //
    // 非消しゴム:
    //   strokeMarksFbo にスタンプ追加 (density×pressure alpha)
    //   → rebuildLiveFromMarks(): snapshot をコピー、marks を brush.opacity で SrcOver
    //
    // 消しゴム:
    //   liveStrokeFbo に直接 destination-out スタンプ
    //   (opacity は density で代替、上限の問題が生じにくい)

    private fun updateLiveStrokeFbo(snap: RenderSnapshot, w: Int, h: Int) {
        val livePts  = snap.currentPoints
        val newCount = livePts.size

        if (newCount == 0) {
            if (lastLivePointCount > 0) pendingLiveMergeLayerId = liveStrokeActiveLayerId
            lastLivePointCount  = 0
            liveDistToNextStamp = 0f
            return
        }

        val layerId  = snap.activeLayerId
        val brush    = snap.currentBrush
        val isEraser = brush.type == BrushType.Eraser
        val isMixing = brush.type.needsWetCanvas
        val isNew    = layerId != liveStrokeActiveLayerId ||
                       newCount < lastLivePointCount ||
                       isEraser != liveIsEraser ||
                       isMixing != liveIsMixing

        if (isNew || liveStrokeFbo == null) {
            val srcFbo = layerFbos[layerId]

            if (isMixing) {
                // ── ウェットキャンバス方式 (Fude / Watercolor / Blur) ──────────────
                // layerFbo を wetCanvasFbo にコピーしてスタート。
                // 以後のスタンプは直接 wetCanvasFbo へ書き込まれ、既存ピクセルと即座に混合。
                // liveStrokeFbo を wetCanvasFbo と同一にして表示はそこから行う。
                val wet = wetCanvasFbo ?: LayerFbo(w, h).also { wetCanvasFbo = it }
                wet.clear(); wet.bind()
                GLES20.glDisable(GLES20.GL_BLEND)
                srcFbo?.let { drawTexFlat(normalComposite, it.texId, -1, 1f, w, h) }
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                liveStrokeFbo = wet  // 表示は wetCanvasFbo をそのまま使用

            } else {
                // ── 通常方式 (Pencil / Marker / Airbrush / Eraser) ────────────────
                // スナップショット + マーク蓄積 → rebuildLiveFromMarks でコンポジット
                val live = liveStrokeFbo ?: LayerFbo(w, h).also { liveStrokeFbo = it }
                live.clear(); live.bind()
                GLES20.glDisable(GLES20.GL_BLEND)
                srcFbo?.let { drawTexFlat(normalComposite, it.texId, -1, 1f, w, h) }
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                if (!isEraser) {
                    val snap2 = strokeSnapshotFbo ?: LayerFbo(w, h).also { strokeSnapshotFbo = it }
                    snap2.clear(); snap2.bind()
                    GLES20.glDisable(GLES20.GL_BLEND)
                    srcFbo?.let { drawTexFlat(normalComposite, it.texId, -1, 1f, w, h) }
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                    val marks = strokeMarksFbo ?: LayerFbo(w, h).also { strokeMarksFbo = it }
                    marks.clear()
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                }
            }

            liveStrokeActiveLayerId = layerId
            lastLivePointCount      = 0
            liveDistToNextStamp     = 0f
            liveIsEraser            = isEraser
            liveIsMixing            = isMixing
        }

        if (newCount >= 2 && newCount > lastLivePointCount) {
            val stroke = Stroke(livePts, brush, snap.currentColor, layerId)

            when {
                isMixing -> {
                    // ウェットキャンバスへ直接書き込み。
                    // Fude/Watercolor はスタンプ色 (CPU 混色済み) を SrcOver でブレンド。
                    // Blur / ぼかし筆圧モードは 2 パス (dest-out + additive) でぼかしを適用。
                    // いずれも wetCanvasFbo の既存ピクセルと即座に混合される。
                    wetCanvasFbo!!.bind()
                    liveDistToNextStamp = brushRenderer.renderStrokeSegments(
                        stroke          = stroke,
                        fromPointIdx    = lastLivePointCount,
                        distToNextStamp = liveDistToNextStamp,
                    )
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                }
                isEraser -> {
                    liveStrokeFbo!!.bind()
                    liveDistToNextStamp = brushRenderer.renderStrokeSegments(
                        stroke          = stroke,
                        fromPointIdx    = lastLivePointCount,
                        distToNextStamp = liveDistToNextStamp,
                    )
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                }
                else -> {
                    // 通常ブラシ: marks に蓄積 → liveStrokeFbo を再コンポジット
                    strokeMarksFbo!!.bind()
                    liveDistToNextStamp = brushRenderer.renderStrokeSegments(
                        stroke          = stroke,
                        fromPointIdx    = lastLivePointCount,
                        distToNextStamp = liveDistToNextStamp,
                    )
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    rebuildLiveFromMarks(brush, w, h)
                }
            }

            lastLivePointCount = newCount
        }
    }

    /**
     * liveStrokeFbo = snapshot + marks × brush.opacity
     *
     * 手順:
     *  1. snapshot を blend 無効でコピー → liveStrokeFbo
     *  2. marks を SrcOver + uAlpha=opacity でブレンド → liveStrokeFbo
     *     (Marker は alpha equation を GL_MAX に)
     */
    private fun rebuildLiveFromMarks(brush: BrushSettings, w: Int, h: Int) {
        val live     = liveStrokeFbo     ?: return
        val snapshot = strokeSnapshotFbo ?: return
        val marks    = strokeMarksFbo    ?: return

        live.bind()

        // Step1: snapshot をそのままコピー (blend OFF で alpha 二乗を防ぐ)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawTexFlat(normalComposite, snapshot.texId, -1, 1f, w, h)

        // Step2: marks をブレンド (プリマルチプライドアルファ)
        // Pencil のみ opacity でキャップを適用。
        // Fude / Watercolor は needsWetCanvas のため rebuildLiveFromMarks を通らないが、
        // 念のため compositeAlpha = 1.0 に戻す (opacity は水分量でコントロール)。
        val compositeAlpha = when (brush.type) {
            BrushType.Pencil -> brush.opacity
            else -> 1.0f
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        if (brush.type == BrushType.Marker) {
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_MAX)
            drawTexFlat(normalComposite, marks.texId, -1, compositeAlpha, w, h)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        } else {
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            drawTexFlat(normalComposite, marks.texId, -1, compositeAlpha, w, h)
        }
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ── レイヤー FBO 増分更新 ──────────────────────────────────────────

    private fun updateLayerFbos(snap: RenderSnapshot, w: Int, h: Int): Boolean {
        var changed = false

        for (layer in snap.layers) {
            val cached = layerStrokeCounts[layer.id] ?: 0
            val count  = layer.strokes.size

            when {
                count == 0 && cached != 0 -> {
                    layerFbos[layer.id]?.clear()
                    layerStrokeCounts[layer.id] = 0
                    changed = true
                }
                count > cached -> {
                    if (layer.id == pendingLiveMergeLayerId) {
                        // ライブストローク確定: FBO スワップ
                        pendingLiveMergeLayerId = null
                        if (liveStrokeFbo != null) {
                            val old = layerFbos[layer.id]
                            layerFbos[layer.id] = liveStrokeFbo!!
                            liveStrokeFbo = old
                            layerStrokeCounts[layer.id] = count
                            liveStrokeActiveLayerId = null
                            // ウェットキャンバスは layerFbos にスワップ済み。参照を解除する。
                            if (liveIsMixing) { wetCanvasFbo = null; liveIsMixing = false }
                        } else {
                            bakeStrokes(layer, cached, count, w, h)
                        }
                    } else {
                        // 通常増分ベイク (redo など)
                        bakeStrokes(layer, cached, count, w, h)
                    }
                    changed = true
                }
                count < cached -> {
                    // Undo: 全再ベイク
                    val fbo = layerFbos.getOrPut(layer.id) { LayerFbo(w, h) }
                    fbo.clear()
                    for (stroke in layer.strokes) bakeStroke(stroke, fbo, w, h)
                    layerStrokeCounts[layer.id] = count
                    changed = true
                }
            }

            val newMeta = LayerMeta(count, layer.isVisible, layer.opacity, layer.blendMode.ordinal, layer.filter?.hashCode() ?: 0)
            if (layerMeta[layer.id] != newMeta) {
                layerMeta[layer.id] = newMeta
                changed = true
            }
        }

        val activeIds = snap.layers.map { it.id }.toSet()
        for (id in layerFbos.keys.toList().minus(activeIds)) {
            layerFbos.remove(id)?.delete()
            layerStrokeCounts.remove(id)
            layerMeta.remove(id)
            changed = true
        }
        if (pendingLiveMergeLayerId != null && pendingLiveMergeLayerId !in activeIds) {
            pendingLiveMergeLayerId = null
        }

        val order = snap.layers.map { it.id }
        if (order != lastLayerOrder) { lastLayerOrder = order; changed = true }

        return changed
    }

    /**
     * ストロークを layerFbo へベイク。
     * 消しゴム / ウェットキャンバス系 (Fude / Watercolor / Blur) → 直接 layerFbo へ
     *   2パス (dest-out + additive) が既存コンテンツを必要とするため layerFbo に直接適用。
     * 通常ブラシ → bakingMarksFbo 経由で opacity を適用してから SrcOver
     */
    private fun bakeStroke(stroke: Stroke, layerFbo: LayerFbo, w: Int, h: Int) {
        if (stroke.brush.type == BrushType.Eraser || stroke.brush.type.needsWetCanvas) {
            layerFbo.bind()
            brushRenderer.renderStroke(stroke)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return
        }

        // bakingMarksFbo: 空にしてスタンプを描画
        val marks = bakingMarksFbo ?: LayerFbo(w, h).also { bakingMarksFbo = it }
        marks.clear(); marks.bind()
        brushRenderer.renderStroke(stroke)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // layerFbo へ合成アルファで SrcOver (Marker は alpha に GL_MAX)
        // Pencil のみ opacity でキャップを適用。Marker / その他は 1.0。
        val compositeAlpha = when (stroke.brush.type) {
            BrushType.Pencil -> stroke.brush.opacity
            else -> 1.0f
        }
        layerFbo.bind()
        GLES20.glEnable(GLES20.GL_BLEND)
        if (stroke.brush.type == BrushType.Marker) {
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_MAX)
            drawTexFlat(normalComposite, marks.texId, -1, compositeAlpha, w, h)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        } else {
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            drawTexFlat(normalComposite, marks.texId, -1, compositeAlpha, w, h)
        }
        GLES20.glBlendFuncSeparate(
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun bakeStrokes(layer: PaintLayer, from: Int, to: Int, w: Int, h: Int) {
        val fbo = layerFbos.getOrPut(layer.id) { LayerFbo(w, h).also { it.clear() } }
        for (i in from until to) bakeStroke(layer.strokes[i], fbo, w, h)
        layerStrokeCounts[layer.id] = to
    }

    // ── compositeCache 再ビルド ───────────────────────────────────────

    private fun buildCompositeCache(snap: RenderSnapshot, w: Int, h: Int, cache: LayerFbo) {
        val cA = compA ?: return
        val cB = compB ?: return

        cA.bind()
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        var current = cA; var swap = cB

        if (snap.showGrid) {
            current.bind()
            drawGrid(w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        // Fix: liveStrokeFbo が有効かつアクティブレイヤーかつポイントが存在する場合のみ使用
        val hasLive = liveStrokeFbo != null &&
                      liveStrokeActiveLayerId == snap.activeLayerId &&
                      snap.currentPoints.isNotEmpty()

        for (layer in snap.layers) {
            if (!layer.isVisible) continue

            val fbo: LayerFbo = if (layer.id == snap.activeLayerId && hasLive) {
                liveStrokeFbo!!
            } else {
                layerFbos[layer.id] ?: continue
            }

            // フィルター適用: プレビュー中または適用済みフィルターがある場合
            val activeFilter: LayerFilter? = when {
                layer.id == snap.filterLayerId && snap.filterPreview != null -> snap.filterPreview
                else -> layer.filter
            }
            val srcTexId = if (activeFilter != null) applyFilterToTex(fbo.texId, activeFilter, w, h) else fbo.texId

            if (layer.blendMode == LayerBlendMode.Normal) {
                current.bind()
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFuncSeparate(
                    GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                    GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                )
                drawTexFlat(normalComposite, srcTexId, -1, layer.opacity, w, h)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            } else {
                swap.clear(); swap.bind()
                GLES20.glDisable(GLES20.GL_BLEND)
                drawTexFlat(blendProgFor(layer.blendMode), srcTexId, current.texId, layer.opacity, w, h)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                val tmp = current; current = swap; swap = tmp
            }
        }

        cache.clear(); cache.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        drawTexFlat(normalComposite, current.texId, -1, 1f, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ── compositeCache → 画面 ────────────────────────────────────────

    private fun drawCacheToScreen(snap: RenderSnapshot, w: Int, h: Int, cache: LayerFbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0.165f, 0.165f, 0.165f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawTexZoomed(normalComposite, cache.texId, 1f, snap, w, h)
    }

    // ── テクスチャクワッド描画ヘルパー ────────────────────────────────

    private fun drawTexFlat(
        prog: GlProgram,
        srcTexId: Int,
        dstTexId: Int,
        alpha: Float,
        w: Int, h: Int,
    ) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val quadVerts = floatArrayOf(
            0f,  0f,  0f, 1f,
            wf,  0f,  1f, 1f,
            wf,  hf,  1f, 0f,
            0f,  0f,  0f, 1f,
            wf,  hf,  1f, 0f,
            0f,  hf,  0f, 0f,
        )
        val mvp = FloatArray(16); ortho(mvp, 0f, wf, hf, 0f)
        drawTexWithMvp(prog, quadVerts, mvp, srcTexId, dstTexId, alpha)
    }

    private fun drawTexZoomed(
        prog: GlProgram,
        srcTexId: Int,
        alpha: Float,
        snap: RenderSnapshot,
        screenW: Int, screenH: Int,
    ) {
        // ドキュメントサイズ (0 = 画面サイズと同じ)
        val docW = snap.docWidth.takeIf  { it > 0 } ?: screenW
        val docH = snap.docHeight.takeIf { it > 0 } ?: screenH

        // letterbox スケール: ドキュメントが画面に収まる最大スケール
        val baseScale = minOf(screenW.toFloat() / docW, screenH.toFloat() / docH)
        // ユーザーの zoom を合成したスケール
        val finalScale = baseScale * snap.zoom

        val cx = screenW / 2f; val cy = screenH / 2f
        val cosT = cos(snap.rotation.toDouble()).toFloat()
        val sinT = sin(snap.rotation.toDouble()).toFloat()
        // ドキュメントの半サイズ (doc 座標中心を基準にした四隅オフセット)
        val hw = docW / 2f; val hh = docH / 2f
        val scx = cx + snap.panX; val scy = cy + snap.panY

        val tlX = scx + finalScale * (-hw * cosT + hh * sinT); val tlY = scy + finalScale * (-hw * sinT - hh * cosT)
        val trX = scx + finalScale * ( hw * cosT + hh * sinT); val trY = scy + finalScale * ( hw * sinT - hh * cosT)
        val brX = scx + finalScale * ( hw * cosT - hh * sinT); val brY = scy + finalScale * ( hw * sinT + hh * cosT)
        val blX = scx + finalScale * (-hw * cosT - hh * sinT); val blY = scy + finalScale * (-hw * sinT + hh * cosT)

        val quadVerts = floatArrayOf(
            tlX, tlY,  0f, 1f,
            trX, trY,  1f, 1f,
            brX, brY,  1f, 0f,
            tlX, tlY,  0f, 1f,
            brX, brY,  1f, 0f,
            blX, blY,  0f, 0f,
        )
        val mvp = FloatArray(16); ortho(mvp, 0f, screenW.toFloat(), screenH.toFloat(), 0f)
        drawTexWithMvp(prog, quadVerts, mvp, srcTexId, -1, alpha)
    }

    private fun drawTexWithMvp(
        prog: GlProgram,
        quadVerts: FloatArray,
        mvp: FloatArray,
        srcTexId: Int,
        dstTexId: Int,
        alpha: Float,
    ) {
        val buf = quadVerts.toFB()
        prog.use()
        GLES20.glUniformMatrix4fv(prog.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(prog.uniform("uAlpha"), alpha)
        GLES20.glUniform1i(prog.uniform("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        if (dstTexId >= 0) {
            GLES20.glUniform1i(prog.uniform("uDst"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dstTexId)
        }
        val posLoc = prog.attrib("aPos"); val uvLoc = prog.attrib("aUV")
        GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(uvLoc)
        val stride = 16
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(2); GLES20.glVertexAttribPointer(uvLoc,  2, GLES20.GL_FLOAT, false, stride, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ── グリッド ─────────────────────────────────────────────────────────

    private fun drawGrid(w: Int, h: Int) {
        val verts = ArrayList<Float>()
        val step = 50f
        val c = Color.Gray.copy(alpha = 0.15f)
        var x = 0f; while (x <= w) {
            verts.add(x); verts.add(0f);          verts.add(1f)
            verts.add(x); verts.add(h.toFloat()); verts.add(1f)
            x += step
        }
        var y = 0f; while (y <= h) {
            verts.add(0f);          verts.add(y); verts.add(1f)
            verts.add(w.toFloat()); verts.add(y); verts.add(1f)
            y += step
        }
        if (verts.isEmpty()) return
        val buf = verts.toFB()
        val mvp = FloatArray(16); ortho(mvp, 0f, w.toFloat(), h.toFloat(), 0f)
        gridProgram.use()
        GLES20.glUniformMatrix4fv(gridProgram.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform4f(gridProgram.uniform("uColor"), c.red, c.green, c.blue, c.alpha)
        val posLoc = gridProgram.attrib("aPos"); val alphaLoc = gridProgram.attrib("aAlpha")
        GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(alphaLoc)
        buf.position(0); GLES20.glVertexAttribPointer(posLoc,   2, GLES20.GL_FLOAT, false, 12, buf)
        buf.position(2); GLES20.glVertexAttribPointer(alphaLoc, 1, GLES20.GL_FLOAT, false, 12, buf)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(alphaLoc)
    }

    // ── 水彩キャプチャ ───────────────────────────────────────────────────

    fun captureLayerPixels(layerId: String, callback: (ByteArray, Int, Int) -> Unit) {
        // ウェットキャンバスがアクティブな場合はそこからキャプチャする。
        // これにより CPU 側 canvasMix() がストローク中の最新描画状態を参照でき、
        // 自分のストロークとの真の混色が実現する。
        val fbo = if (layerId == liveStrokeActiveLayerId && liveIsMixing && wetCanvasFbo != null)
            wetCanvasFbo!!
        else
            layerFbos[layerId] ?: return
        // ドキュメントサイズで読み取る (画面サイズではない)
        val dw = currentDocWidth.takeIf { it > 0 } ?: surfaceWidth
        val dh = currentDocHeight.takeIf { it > 0 } ?: surfaceHeight
        fbo.bind()
        val bb = ByteBuffer.allocateDirect(dw * dh * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, dw, dh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        bb.position(0)
        val bytes = ByteArray(dw * dh * 4); bb.get(bytes)
        callback(bytes, dw, dh)
    }

    fun requestWatercolorCapture(layerId: String, cb: (ByteArray, Int, Int) -> Unit) {
        watercolorCaptureRequest.set(Pair(layerId, cb))
    }

    // ── コンポジットキャプチャ (スポイト用) ─────────────────────────────

    fun captureCompositePixels(callback: (ByteArray, Int, Int) -> Unit) {
        val cache = compositeCache ?: return
        val dw = currentDocWidth.takeIf { it > 0 } ?: surfaceWidth
        val dh = currentDocHeight.takeIf { it > 0 } ?: surfaceHeight
        if (dw == 0 || dh == 0) return
        cache.bind()
        val bb = ByteBuffer.allocateDirect(dw * dh * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, dw, dh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        bb.position(0)
        val bytes = ByteArray(dw * dh * 4); bb.get(bytes)
        callback(bytes, dw, dh)
    }

    fun requestCompositeCapture(cb: (ByteArray, Int, Int) -> Unit) {
        compositeCaptureRequest.set(cb)
    }

    // ── 全レイヤーキャプチャ (PSD エクスポート用) ────────────────────────

    fun requestAllLayersCapture(
        layerIds: List<String>,
        cb: (List<Pair<String, ByteArray>>, Int, Int) -> Unit,
    ) {
        allLayersCaptureRequest.set(AllLayersCaptureReq(layerIds, cb))
    }

    // ── ユーティリティ ───────────────────────────────────────────────────

    private fun effectiveDoc(snap: RenderSnapshot?): Pair<Int, Int> {
        val dw = snap?.docWidth?.takeIf  { it > 0 } ?: surfaceWidth
        val dh = snap?.docHeight?.takeIf { it > 0 } ?: surfaceHeight
        return Pair(dw, dh)
    }

    private fun rebuildAll(layers: List<PaintLayer>, docW: Int = surfaceWidth, docH: Int = surfaceHeight) {
        if (docW == 0 || docH == 0) return
        brushRenderer.setProjection(docW.toFloat(), docH.toFloat())
        for (layer in layers) {
            if (layer.strokes.isEmpty()) continue
            val fbo = LayerFbo(docW, docH).also { it.clear() }
            for (stroke in layer.strokes) bakeStroke(stroke, fbo, docW, docH)
            layerFbos[layer.id] = fbo
            layerStrokeCounts[layer.id] = layer.strokes.size
        }
    }

    private fun blendProgFor(mode: LayerBlendMode) = when (mode) {
        LayerBlendMode.Multiply -> multiplyComposite
        LayerBlendMode.Screen   -> screenComposite
        LayerBlendMode.Overlay  -> overlayComposite
        LayerBlendMode.Darken   -> darkenComposite
        LayerBlendMode.Lighten  -> lightenComposite
        else                    -> normalComposite
    }

    // ── フィルター適用 ────────────────────────────────────────────────────

    /**
     * フィルターをテクスチャに適用し、結果のテクスチャ ID を返す。
     * filterTempFbo に結果を書き込む (2パスブラーは filterTemp2Fbo も使用)。
     */
    private fun applyFilterToTex(srcTexId: Int, filter: LayerFilter, w: Int, h: Int): Int {
        val temp = filterTempFbo ?: LayerFbo(w, h).also { filterTempFbo = it }
        return when (filter.type) {
            FilterType.HSL -> {
                temp.bind(); GLES20.glDisable(GLES20.GL_BLEND)
                drawFilterQuad(filterHslProg, srcTexId, w, h) { prog ->
                    GLES20.glUniform1f(prog.uniform("uHue"), filter.hue)
                    GLES20.glUniform1f(prog.uniform("uSat"), filter.saturation)
                    GLES20.glUniform1f(prog.uniform("uLit"), filter.lightness)
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                temp.texId
            }
            FilterType.BLUR -> {
                if (filter.blurRadius < 0.001f) return srcTexId
                val radius = filter.blurRadius * 40f  // 0..40px
                val temp2 = filterTemp2Fbo ?: LayerFbo(w, h).also { filterTemp2Fbo = it }
                // 水平パス: src → temp2
                temp2.bind(); GLES20.glDisable(GLES20.GL_BLEND)
                drawFilterQuad(filterBlurHProg, srcTexId, w, h) { prog ->
                    GLES20.glUniform1f(prog.uniform("uRadius"), radius)
                    GLES20.glUniform2f(prog.uniform("uTexelSize"), 1f / w, 1f / h)
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                // 垂直パス: temp2 → temp
                temp.bind(); GLES20.glDisable(GLES20.GL_BLEND)
                drawFilterQuad(filterBlurVProg, temp2.texId, w, h) { prog ->
                    GLES20.glUniform1f(prog.uniform("uRadius"), radius)
                    GLES20.glUniform2f(prog.uniform("uTexelSize"), 1f / w, 1f / h)
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                temp.texId
            }
            FilterType.CONTRAST -> {
                temp.bind(); GLES20.glDisable(GLES20.GL_BLEND)
                drawFilterQuad(filterContrastProg, srcTexId, w, h) { prog ->
                    GLES20.glUniform1f(prog.uniform("uContrast"),   filter.contrast)
                    GLES20.glUniform1f(prog.uniform("uBrightness"), filter.brightness)
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                temp.texId
            }
        }
    }

    /** フルスクリーン quad にフィルターシェーダーを適用する汎用ヘルパー。 */
    private fun drawFilterQuad(
        prog: GlProgram,
        srcTexId: Int,
        w: Int, h: Int,
        setUniforms: (GlProgram) -> Unit,
    ) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val quadVerts = floatArrayOf(
            0f,  0f,  0f, 1f,
            wf,  0f,  1f, 1f,
            wf,  hf,  1f, 0f,
            0f,  0f,  0f, 1f,
            wf,  hf,  1f, 0f,
            0f,  hf,  0f, 0f,
        )
        val mvp = FloatArray(16); ortho(mvp, 0f, wf, hf, 0f)
        val buf = quadVerts.toFB()
        prog.use()
        GLES20.glUniformMatrix4fv(prog.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(prog.uniform("uAlpha"), 1f)
        GLES20.glUniform1i(prog.uniform("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        setUniforms(prog)
        val posLoc = prog.attrib("aPos"); val uvLoc = prog.attrib("aUV")
        GLES20.glEnableVertexAttribArray(posLoc); GLES20.glEnableVertexAttribArray(uvLoc)
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2); GLES20.glVertexAttribPointer(uvLoc,  2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(posLoc); GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun cleanup() {
        layerFbos.values.forEach { it.delete() }; layerFbos.clear()
        strokeSnapshotFbo?.delete()
        strokeMarksFbo?.delete()
        liveStrokeFbo?.delete()
        wetCanvasFbo?.delete()
        bakingMarksFbo?.delete()
        compositeCache?.delete()
        compA?.delete(); compB?.delete()
        filterTempFbo?.delete()
        filterTemp2Fbo?.delete()
        brushRenderer.delete()
        listOf(normalComposite, multiplyComposite, screenComposite,
               overlayComposite, darkenComposite, lightenComposite,
               gridProgram,
               filterHslProg, filterBlurHProg, filterBlurVProg, filterContrastProg,
               ).forEach { it.delete() }
    }
}
