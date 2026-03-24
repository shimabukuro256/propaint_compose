package com.propaint.app.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.propaint.app.engine.CanvasDocument
import com.propaint.app.engine.DirtyTileTracker
import com.propaint.app.engine.Tile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 表示専用 Renderer。GPU は描画処理に一切関与しない。 */
class CanvasRenderer : GLSurfaceView.Renderer {
    var document: CanvasDocument? = null
    val pendingTransform = AtomicReference<FloatArray?>(null)
    @Volatile var zoom = 1f; @Volatile var panX = 0f
    @Volatile var panY = 0f; @Volatile var rotation = 0f
    var surfaceWidth = 0; private set
    var surfaceHeight = 0; private set

    /** 選択範囲オーバーレイモード (メインスレッドからセット) */
    @Volatile var selectionOverlayMode: Int = OVERLAY_NONE

    private lateinit var quadProg: GlProgram
    private lateinit var blueProg: GlProgram
    private lateinit var antsProg: GlProgram
    private var canvasTexId = 0; private var canvasTexW = 0; private var canvasTexH = 0
    private var selTexId = 0; private var selTexW = 0; private var selTexH = 0
    private var uploadBuffer: ByteBuffer? = null
    private var selUploadBuffer: ByteBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.16f, 0.16f, 0.16f, 1f)
        quadProg = GlProgram(Shaders.QUAD_VERT, Shaders.QUAD_FRAG)
        blueProg = GlProgram(Shaders.QUAD_VERT, Shaders.SELECTION_BLUE_FRAG)
        antsProg = GlProgram(Shaders.QUAD_VERT, Shaders.SELECTION_ANTS_FRAG)
        // GL コンテキスト喪失時にテクスチャハンドルは無効化される
        canvasTexId = 0; selTexId = 0
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        surfaceWidth = w; surfaceHeight = h; GLES20.glViewport(0, 0, w, h)
        if (canvasTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(canvasTexId), 0); canvasTexId = 0 }
        if (selTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0); selTexId = 0 }
    }

    override fun onDrawFrame(gl: GL10?) {
        val doc = document ?: run { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); return }
        pendingTransform.getAndSet(null)?.let { zoom = it[0]; panX = it[1]; panY = it[2]; rotation = it[3] }

        val dw = doc.width; val dh = doc.height
        val txc = (dw + Tile.SIZE - 1) / Tile.SIZE; val tyc = (dh + Tile.SIZE - 1) / Tile.SIZE
        if (canvasTexId == 0 || canvasTexW != dw || canvasTexH != dh) {
            canvasTexId = createTexture(dw, dh); canvasTexW = dw; canvasTexH = dh
            doc.dirtyTracker.markFullRebuild()
        }

        // ── キャンバスタイルアップロード ──
        val full = doc.dirtyTracker.checkAndClearFullRebuild()
        if (full) { doc.rebuildCompositeCache(); uploadAllTiles(doc, txc, tyc, dw, dh) }
        else {
            val dirty = doc.dirtyTracker.drain()
            if (dirty.isNotEmpty()) {
                for (c in dirty) {
                    val tx = DirtyTileTracker.unpackX(c); val ty = DirtyTileTracker.unpackY(c)
                    if (tx < txc && ty < tyc) { val idx = ty * txc + tx; doc.rebuildCompositeTile(tx, ty, idx); uploadTile(doc, tx, ty, idx, dw, dh) }
                }
            }
        }

        // ── 選択マスクテクスチャアップロード ──
        val selSnap = doc.selectionSnapshot.getAndSet(null)
        if (selSnap != null) {
            uploadSelectionTexture(selSnap, dw, dh)
        } else if (doc.dirtyTracker.checkAndClearSelectionDirty() && !doc.hasActiveSelection) {
            // 選択解除: テクスチャ不要
        }

        // ── 描画 ──
        GLES20.glClearColor(0.16f, 0.16f, 0.16f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val mode = selectionOverlayMode
        if (mode != OVERLAY_NONE && selTexId != 0 && doc.hasActiveSelection) {
            when (mode) {
                OVERLAY_BLUE -> drawCanvasWithOverlay(dw, dh, blueProg)
                OVERLAY_MARCHING_ANTS -> drawCanvasWithOverlay(dw, dh, antsProg)
                else -> drawCanvasToScreen(dw, dh)
            }
        } else {
            drawCanvasToScreen(dw, dh)
        }
    }

    // ── テクスチャ生成 ────────────────────────────────────────────

    private fun createTexture(w: Int, h: Int): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); val t = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) { buf.put(-1); buf.put(-1); buf.put(-1); buf.put(-1) }
        buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); return t
    }

    private fun createSelectionTexture(w: Int, h: Int): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); val t = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // GL_LUMINANCE: 1 byte/pixel → shader で .r でサンプル (L, L, L, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); return t
    }

    // ── 選択マスクテクスチャアップロード ──────────────────────────

    private fun uploadSelectionTexture(maskData: ByteArray, w: Int, h: Int) {
        if (selTexId == 0 || selTexW != w || selTexH != h) {
            if (selTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
            selTexId = createSelectionTexture(w, h); selTexW = w; selTexH = h
        }
        val sz = w * h
        check(maskData.size >= sz) { "SelectionMask data size mismatch: ${maskData.size} < $sz" }
        val buf = selUploadBuffer?.let { if (it.capacity() >= sz) it else null }
            ?: ByteBuffer.allocateDirect(sz).order(ByteOrder.nativeOrder()).also { selUploadBuffer = it }
        buf.position(0); buf.limit(sz)
        buf.put(maskData, 0, sz)
        buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTexId)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ── キャンバスタイルアップロード ──────────────────────────────

    private fun uploadAllTiles(doc: CanvasDocument, txc: Int, tyc: Int, dw: Int, dh: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        for (ty in 0 until tyc) for (tx in 0 until txc) uploadTileInner(doc, tx, ty, ty * txc + tx, dw, dh)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadTile(doc: CanvasDocument, tx: Int, ty: Int, idx: Int, dw: Int, dh: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        uploadTileInner(doc, tx, ty, idx, dw, dh)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadTileInner(doc: CanvasDocument, tx: Int, ty: Int, idx: Int, dw: Int, dh: Int) {
        val data = doc.compositeCache[idx] ?: return
        val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
        val tw = min(Tile.SIZE, dw - bx); val th = min(Tile.SIZE, dh - by)
        if (tw <= 0 || th <= 0) return
        val sz = tw * th * 4
        val buf = uploadBuffer?.let { if (it.capacity() >= sz) it else null }
            ?: ByteBuffer.allocateDirect(sz).order(ByteOrder.nativeOrder()).also { uploadBuffer = it }
        buf.position(0); buf.limit(sz)
        for (ly in 0 until th) { val off = ly * Tile.SIZE
            for (lx in 0 until tw) { val p = data[off + lx]
                buf.put(((p shr 16) and 0xFF).toByte()); buf.put(((p shr 8) and 0xFF).toByte())
                buf.put((p and 0xFF).toByte()); buf.put(((p shr 24) and 0xFF).toByte())
            }
        }
        buf.position(0)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, bx, by, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
    }

    // ── 描画 ─────────────────────────────────────────────────────

    /** キャンバスの quad 頂点配列とMVPを計算する共通ヘルパー */
    private fun buildCanvasQuad(dw: Int, dh: Int): Pair<FloatArray, FloatArray> {
        val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()
        val bs = min(sw / dw, sh / dh); val fs = bs * zoom
        val cx = sw / 2f + panX; val cy = sh / 2f + panY
        val hw = dw / 2f; val hh = dh / 2f
        val c = cos(rotation.toDouble()).toFloat(); val s = sin(rotation.toDouble()).toFloat()
        fun tx(ox: Float, oy: Float) = cx + fs * (ox * c - oy * s) to cy + fs * (ox * s + oy * c)
        val (tlx, tly) = tx(-hw, -hh); val (trx, try_) = tx(hw, -hh)
        val (brx, bry) = tx(hw, hh); val (blx, bly) = tx(-hw, hh)
        val verts = floatArrayOf(tlx, tly, 0f, 0f, trx, try_, 1f, 0f, brx, bry, 1f, 1f,
            tlx, tly, 0f, 0f, brx, bry, 1f, 1f, blx, bly, 0f, 1f)
        val mvp = FloatArray(16); ortho(mvp, 0f, sw, sh, 0f)
        return verts to mvp
    }

    /** 通常のキャンバス描画 (選択オーバーレイなし) */
    private fun drawCanvasToScreen(dw: Int, dh: Int) {
        val (verts, mvp) = buildCanvasQuad(dw, dh)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        quadProg.use()
        GLES20.glUniformMatrix4fv(quadProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(quadProg.uniform("uAlpha"), 1f)
        GLES20.glUniform1i(quadProg.uniform("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        drawQuad(quadProg, verts)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** 選択オーバーレイ付きキャンバス描画 (青 / マーチングアンツ 共通) */
    private fun drawCanvasWithOverlay(dw: Int, dh: Int, prog: GlProgram) {
        val (verts, mvp) = buildCanvasQuad(dw, dh)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        prog.use()
        GLES20.glUniformMatrix4fv(prog.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(prog.uniform("uAlpha"), 1f)
        // テクスチャ 0: キャンバス
        GLES20.glUniform1i(prog.uniform("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        // テクスチャ 1: 選択マスク
        GLES20.glUniform1i(prog.uniform("uSelMask"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTexId)
        // マーチングアンツ用 uniform (blueProg は uniform が存在しなければ -1 で無視される)
        val timeLoc = prog.uniform("uTime")
        if (timeLoc >= 0) {
            GLES20.glUniform1f(timeLoc, (System.nanoTime() / 1_000_000_000.0).toFloat())
        }
        val sizeLoc = prog.uniform("uCanvasSize")
        if (sizeLoc >= 0) {
            GLES20.glUniform2f(sizeLoc, dw.toFloat(), dh.toFloat())
        }
        drawQuad(prog, verts)
        // テクスチャバインド解除
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** quad の頂点属性をセットアップして描画 */
    private fun drawQuad(prog: GlProgram, verts: FloatArray) {
        val buf = verts.toFloatBuffer()
        val p = prog.attrib("aPos"); val u = prog.attrib("aUV")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(u)
        buf.position(0); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2); GLES20.glVertexAttribPointer(u, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(u)
    }

    fun cleanup() {
        if (canvasTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(canvasTexId), 0)
        if (selTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
        if (::quadProg.isInitialized) quadProg.delete()
        if (::blueProg.isInitialized) blueProg.delete()
        if (::antsProg.isInitialized) antsProg.delete()
    }

    companion object {
        const val OVERLAY_NONE = 0
        const val OVERLAY_BLUE = 1
        const val OVERLAY_MARCHING_ANTS = 2

        fun ortho(m: FloatArray, l: Float, r: Float, b: Float, t: Float) {
            val rw = 2f / (r - l); val rh = 2f / (t - b)
            m[0] = rw; m[4] = 0f; m[8] = 0f; m[12] = -(r + l) / (r - l)
            m[1] = 0f; m[5] = rh; m[9] = 0f; m[13] = -(t + b) / (t - b)
            m[2] = 0f; m[6] = 0f; m[10] = -1f; m[14] = 0f
            m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        }
    }
}

fun FloatArray.toFloatBuffer(): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer(); fb.put(this); fb.position(0); return fb
}
