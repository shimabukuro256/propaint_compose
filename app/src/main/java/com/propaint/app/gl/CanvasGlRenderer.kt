package com.propaint.app.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.compose.ui.graphics.Color
import com.propaint.app.model.*
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

    // ── FBO ─────────────────────────────────────────────────────────────

    private val layerFbos         = mutableMapOf<String, LayerFbo>()
    private val layerStrokeCounts = mutableMapOf<String, Int>()

    // ping-pong (レイヤー合成作業用)
    private var compA: LayerFbo? = null
    private var compB: LayerFbo? = null

    // ── ライブストローク FBO ─────────────────────────────────────────────
    //
    // strokeSnapshotFbo: ストローク開始時の layerFbo のコピー (読み取り専用)
    // strokeMarksFbo   : 現在ストロークの全スタンプ蓄積 (opacity 適用前)
    // liveStrokeFbo    : snapshot + marks×opacity の合成結果 (合成表示・確定用)
    //
    // Eraser は直接 liveStrokeFbo へ描画 (destination-out で opacity 上限不要)。
    private var strokeSnapshotFbo:     LayerFbo? = null
    private var strokeMarksFbo:        LayerFbo? = null
    private var liveStrokeFbo:         LayerFbo? = null
    private var liveStrokeActiveLayerId: String? = null
    private var lastLivePointCount:    Int = 0
    private var liveDistToNextStamp:   Float = 0f
    private var liveIsEraser:          Boolean = false
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
    )
    private val layerMeta     = mutableMapOf<String, LayerMeta>()
    private var lastLayerOrder = emptyList<String>()
    private var lastLiveSize   = -1
    private var lastActiveId   = ""
    private var lastShowGrid   = false
    private var lastSnapshot: RenderSnapshot? = null

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
        bakingMarksFbo?.delete();    bakingMarksFbo    = null
        compositeCache?.delete();    compositeCache    = null
        compA?.delete(); compA = LayerFbo(width, height)
        compB?.delete(); compB = LayerFbo(width, height)

        liveStrokeActiveLayerId = null
        lastLivePointCount      = 0
        liveDistToNextStamp     = 0f
        liveIsEraser            = false
        pendingLiveMergeLayerId = null

        lastLiveSize   = -1
        lastLayerOrder = emptyList()

        lastSnapshot?.layers?.let { rebuildAll(it) }
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

        brushRenderer.setProjection(w.toFloat(), h.toFloat())

        watercolorCaptureRequest.getAndSet(null)?.let { (id, cb) ->
            captureLayerPixels(id, cb)
        }

        updateLiveStrokeFbo(snap, w, h)

        val layerFbosChanged = updateLayerFbos(snap, w, h)

        val liveSize    = snap.currentPoints.size
        val liveChanged = liveSize != lastLiveSize
        val metaChanged = snap.showGrid != lastShowGrid || snap.activeLayerId != lastActiveId
        if (liveChanged) lastLiveSize = liveSize
        if (metaChanged) { lastShowGrid = snap.showGrid; lastActiveId = snap.activeLayerId }

        val contentChanged = layerFbosChanged || liveChanged || metaChanged

        val cache = compositeCache ?: LayerFbo(w, h).also { compositeCache = it }
        if (contentChanged) buildCompositeCache(snap, w, h, cache)

        drawCacheToScreen(snap, w, h, cache)
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
        val isNew    = layerId != liveStrokeActiveLayerId ||
                       newCount < lastLivePointCount ||
                       isEraser != liveIsEraser

        if (isNew || liveStrokeFbo == null) {
            // ── ストローク開始: スナップショットを各 FBO へコピー ──────────
            val srcFbo = layerFbos[layerId]

            // liveStrokeFbo: スナップショットで初期化 (非消しゴムはここから rebuild で上書き)
            val live = liveStrokeFbo ?: LayerFbo(w, h).also { liveStrokeFbo = it }
            live.clear(); live.bind()
            GLES20.glDisable(GLES20.GL_BLEND)
            srcFbo?.let { drawTexFlat(normalComposite, it.texId, -1, 1f, w, h) }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            if (!isEraser) {
                // strokeSnapshotFbo: 読み取り専用コピー (rebuild の src)
                val snap2 = strokeSnapshotFbo ?: LayerFbo(w, h).also { strokeSnapshotFbo = it }
                snap2.clear(); snap2.bind()
                GLES20.glDisable(GLES20.GL_BLEND)
                srcFbo?.let { drawTexFlat(normalComposite, it.texId, -1, 1f, w, h) }
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                // strokeMarksFbo: 空でリセット
                val marks = strokeMarksFbo ?: LayerFbo(w, h).also { strokeMarksFbo = it }
                marks.clear()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }

            liveStrokeActiveLayerId = layerId
            lastLivePointCount      = 0
            liveDistToNextStamp     = 0f
            liveIsEraser            = isEraser
        }

        if (newCount >= 2 && newCount > lastLivePointCount) {
            val stroke = Stroke(livePts, brush, snap.currentColor, layerId)

            if (isEraser) {
                // 消しゴム: 直接 liveStrokeFbo へ destination-out
                liveStrokeFbo!!.bind()
                liveDistToNextStamp = brushRenderer.renderStrokeSegments(
                    stroke          = stroke,
                    fromPointIdx    = lastLivePointCount,
                    distToNextStamp = liveDistToNextStamp,
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            } else {
                // 非消しゴム: marks に追加 → liveStrokeFbo を rebuild
                strokeMarksFbo!!.bind()
                liveDistToNextStamp = brushRenderer.renderStrokeSegments(
                    stroke          = stroke,
                    fromPointIdx    = lastLivePointCount,
                    distToNextStamp = liveDistToNextStamp,
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                rebuildLiveFromMarks(brush, w, h)
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

        // Step2: marks を opacity でブレンド (プリマルチプライドアルファ)
        GLES20.glEnable(GLES20.GL_BLEND)
        if (brush.type == BrushType.Marker) {
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_MAX)
            drawTexFlat(normalComposite, marks.texId, -1, brush.opacity, w, h)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        } else {
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            drawTexFlat(normalComposite, marks.texId, -1, brush.opacity, w, h)
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

            val newMeta = LayerMeta(count, layer.isVisible, layer.opacity, layer.blendMode.ordinal)
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
     * 消しゴム → 直接 layerFbo へ destination-out
     * 非消しゴム → bakingMarksFbo 経由で opacity を適用してから SrcOver
     */
    private fun bakeStroke(stroke: Stroke, layerFbo: LayerFbo, w: Int, h: Int) {
        if (stroke.brush.type == BrushType.Eraser) {
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

        // layerFbo へ opacity でプリマルチプライド SrcOver (Marker は alpha に GL_MAX)
        layerFbo.bind()
        GLES20.glEnable(GLES20.GL_BLEND)
        if (stroke.brush.type == BrushType.Marker) {
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_MAX)
            drawTexFlat(normalComposite, marks.texId, -1, stroke.brush.opacity, w, h)
            GLES30.glBlendEquationSeparate(GLES30.GL_FUNC_ADD, GLES30.GL_FUNC_ADD)
        } else {
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            drawTexFlat(normalComposite, marks.texId, -1, stroke.brush.opacity, w, h)
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

        val hasLive = liveStrokeFbo != null && liveStrokeActiveLayerId == snap.activeLayerId
        for (layer in snap.layers) {
            if (!layer.isVisible) continue

            val fbo: LayerFbo = if (layer.id == snap.activeLayerId && hasLive) {
                liveStrokeFbo!!
            } else {
                layerFbos[layer.id] ?: continue
            }

            if (layer.blendMode == LayerBlendMode.Normal) {
                current.bind()
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFuncSeparate(
                    GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                    GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                )
                drawTexFlat(normalComposite, fbo.texId, -1, layer.opacity, w, h)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            } else {
                swap.clear(); swap.bind()
                GLES20.glDisable(GLES20.GL_BLEND)
                drawTexFlat(blendProgFor(layer.blendMode), fbo.texId, current.texId, layer.opacity, w, h)
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
        w: Int, h: Int,
    ) {
        val cx = w / 2f; val cy = h / 2f
        val z  = snap.zoom
        val cosT = cos(snap.rotation.toDouble()).toFloat()
        val sinT = sin(snap.rotation.toDouble()).toFloat()
        val hw = w / 2f; val hh = h / 2f
        val scx = cx + snap.panX; val scy = cy + snap.panY

        val tlX = scx + z * (-hw * cosT + hh * sinT); val tlY = scy + z * (-hw * sinT - hh * cosT)
        val trX = scx + z * ( hw * cosT + hh * sinT); val trY = scy + z * ( hw * sinT - hh * cosT)
        val brX = scx + z * ( hw * cosT - hh * sinT); val brY = scy + z * ( hw * sinT + hh * cosT)
        val blX = scx + z * (-hw * cosT - hh * sinT); val blY = scy + z * (-hw * sinT + hh * cosT)

        val quadVerts = floatArrayOf(
            tlX, tlY,  0f, 1f,
            trX, trY,  1f, 1f,
            brX, brY,  1f, 0f,
            tlX, tlY,  0f, 1f,
            brX, brY,  1f, 0f,
            blX, blY,  0f, 0f,
        )
        val mvp = FloatArray(16); ortho(mvp, 0f, w.toFloat(), h.toFloat(), 0f)
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
        val fbo = layerFbos[layerId] ?: return
        val w = surfaceWidth; val h = surfaceHeight
        fbo.bind()
        val bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        bb.position(0)
        val bytes = ByteArray(w * h * 4); bb.get(bytes)
        callback(bytes, w, h)
    }

    fun requestWatercolorCapture(layerId: String, cb: (ByteArray, Int, Int) -> Unit) {
        watercolorCaptureRequest.set(Pair(layerId, cb))
    }

    // ── ユーティリティ ───────────────────────────────────────────────────

    private fun rebuildAll(layers: List<PaintLayer>) {
        val w = surfaceWidth; val h = surfaceHeight
        if (w == 0 || h == 0) return
        brushRenderer.setProjection(w.toFloat(), h.toFloat())
        for (layer in layers) {
            if (layer.strokes.isEmpty()) continue
            val fbo = LayerFbo(w, h).also { it.clear() }
            for (stroke in layer.strokes) bakeStroke(stroke, fbo, w, h)
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

    fun cleanup() {
        layerFbos.values.forEach { it.delete() }; layerFbos.clear()
        strokeSnapshotFbo?.delete()
        strokeMarksFbo?.delete()
        liveStrokeFbo?.delete()
        bakingMarksFbo?.delete()
        compositeCache?.delete()
        compA?.delete(); compB?.delete()
        brushRenderer.delete()
        listOf(normalComposite, multiplyComposite, screenComposite,
               overlayComposite, darkenComposite, lightenComposite,
               gridProgram).forEach { it.delete() }
    }
}
