package com.propaint.app.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper

/**
 * OpenGL ES 2.0 描画を受け持つ GLSurfaceView ラッパー。
 * Compose の AndroidView からホストされる。
 * タッチ入力は Compose 側 (DrawingCanvas.kt) が処理し、
 * このビューは描画専用。
 */
class GlCanvasView(context: Context) : GLSurfaceView(context) {

    internal val renderer = CanvasGlRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        // 状態変更時のみ描画 (連続描画は不要)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * レンダリングスナップショットを提出し、次フレームの描画をリクエストする。
     * メインスレッドから呼ぶ。
     */
    fun submitSnapshot(snapshot: RenderSnapshot) {
        renderer.pendingSnapshot.set(snapshot)
        requestRender()
    }

    /**
     * zoom/pan/rotation のみを GL スレッドへ直接届ける高速パス。
     * Compose の再コンポーズを経由しないため、ピンチ・回転ジェスチャー中でも 60fps を維持できる。
     * コンテンツ (ストローク/レイヤー) は変化しないことが前提。
     */
    fun submitTransformFast(zoom: Float, panX: Float, panY: Float, rotation: Float) {
        renderer.pendingTransform.set(floatArrayOf(zoom, panX, panY, rotation))
        requestRender()
    }

    /**
     * 水彩ブラシ用: アクティブレイヤーの現在ピクセルを非同期でキャプチャし、
     * [onReady] をメインスレッドで呼び出す。
     *
     * ピクセルデータは RGBA バイト列 (行優先, GL座標: y=0 が下端)。
     * カラーサンプラー内で canvas_y → gl_y 変換 (height-1-y) が必要。
     */
    fun requestWatercolorCapture(
        layerId: String,
        onReady: (pixels: ByteArray, width: Int, height: Int) -> Unit,
    ) {
        renderer.requestWatercolorCapture(layerId) { bytes, w, h ->
            mainHandler.post { onReady(bytes, w, h) }
        }
        requestRender()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        queueEvent { renderer.cleanup() }
    }
}
