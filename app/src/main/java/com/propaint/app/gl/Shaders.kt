package com.propaint.app.gl

/**
 * GLSL シェーダーソース (表示専用)。
 * v2 では GPU はキャンバス表示のみ。描画処理は一切なし。
 */
object Shaders {

    /** テクスチャ quad 描画用バーテックスシェーダー */
    const val QUAD_VERT = """
        uniform mat4 uMVP;
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            vUV = aUV;
        }
    """

    /** テクスチャ描画フラグメントシェーダー */
    const val QUAD_FRAG = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform float uAlpha;
        varying vec2 vUV;
        void main() {
            vec4 c = texture2D(uTex, vUV);
            gl_FragColor = c * uAlpha;
        }
    """

    /** チェッカーボード背景 (透明表示用) */
    const val CHECKER_FRAG = """
        precision mediump float;
        varying vec2 vUV;
        uniform vec2 uSize;
        void main() {
            vec2 pos = vUV * uSize;
            float checker = mod(floor(pos.x / 16.0) + floor(pos.y / 16.0), 2.0);
            float gray = mix(0.8, 0.9, checker);
            gl_FragColor = vec4(gray, gray, gray, 1.0);
        }
    """

    // ── 選択範囲オーバーレイ ──────────────────────────────────────

    /**
     * 青オーバーレイ: 選択範囲編集モード用。
     * 選択域に半透明の青ティント、非選択域を暗化。
     * uTex = キャンバステクスチャ, uSelMask = 選択マスク (GL_LUMINANCE)
     */
    const val SELECTION_BLUE_FRAG = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform sampler2D uSelMask;
        uniform float uAlpha;
        varying vec2 vUV;
        void main() {
            vec4 canvas = texture2D(uTex, vUV);
            float sel = texture2D(uSelMask, vUV).r;
            // 選択域: 青ティント (30%ブレンド)
            vec3 blueTint = mix(canvas.rgb, vec3(0.25, 0.45, 0.95), 0.3);
            // 非選択域: 70%暗化 → 選択域: 青ティント
            vec3 color = mix(canvas.rgb * 0.7, blueTint, sel);
            gl_FragColor = vec4(color, canvas.a) * uAlpha;
        }
    """

    /**
     * マーチングアンツ: 描画/フィルタモードでの選択境界表示。
     * 選択マスクのエッジを検出し、対角ストライプパターンをアニメーション表示。
     * uTex = キャンバステクスチャ, uSelMask = 選択マスク (GL_LUMINANCE)
     * uTime = アニメーション時間 (秒), uCanvasSize = キャンバスサイズ (px)
     */
    const val SELECTION_ANTS_FRAG = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform sampler2D uSelMask;
        uniform float uAlpha;
        uniform float uTime;
        uniform vec2 uCanvasSize;
        varying vec2 vUV;
        void main() {
            vec4 canvas = texture2D(uTex, vUV);
            float sel = texture2D(uSelMask, vUV).r;

            // 4方向隣接ピクセルでエッジ検出
            vec2 texel = 1.0 / uCanvasSize;
            float selL = texture2D(uSelMask, vUV + vec2(-texel.x, 0.0)).r;
            float selR = texture2D(uSelMask, vUV + vec2( texel.x, 0.0)).r;
            float selU = texture2D(uSelMask, vUV + vec2(0.0, -texel.y)).r;
            float selD = texture2D(uSelMask, vUV + vec2(0.0,  texel.y)).r;

            // エッジ強度 (選択境界で 1.0)
            float edge = abs(sel - selL) + abs(sel - selR) + abs(sel - selU) + abs(sel - selD);
            edge = clamp(edge * 2.0, 0.0, 1.0);

            // マーチングアンツパターン: 対角ストライプがアニメーション
            vec2 pixelCoord = vUV * uCanvasSize;
            float antPattern = mod(pixelCoord.x - pixelCoord.y + uTime * 60.0, 8.0);
            float ant = step(4.0, antPattern);
            // 白黒交互
            vec3 antColor = mix(vec3(0.0), vec3(1.0), ant);

            // エッジピクセルのみアンツ表示、それ以外はキャンバスそのまま
            vec3 color = mix(canvas.rgb, antColor, edge * 0.9);
            gl_FragColor = vec4(color, canvas.a) * uAlpha;
        }
    """
}
