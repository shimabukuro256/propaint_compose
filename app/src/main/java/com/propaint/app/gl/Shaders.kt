package com.propaint.app.gl

/**
 * 全 GLSL シェーダーのソースコード定数。
 *
 * 座標系: キャンバス左上=(0,0), Y 軸下向き。
 * MVP 行列は ortho(left=0, right=W, top=0, bottom=H) で Y 反転済み。
 * FBO テクスチャのサンプリング UV は Y 反転 (v=1 がキャンバス上端)。
 */
internal object Shaders {

    // ── スタンプブラシ共通 ────────────────────────────────────────────────
    // 頂点データ: [x, y, u, v, alpha, r, g, b, a]  stride=36 bytes
    // UV=(0..1)×(0..1) の円をフラグメントシェーダーで softclip する。

    const val STAMP_VERT = """
attribute vec2 aPos;
attribute vec2 aUV;
attribute float aAlpha;
attribute vec4 aColor;
uniform mat4 uMVP;
varying vec2 vUV;
varying float vAlpha;
varying vec4 vColor;
void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
    vUV    = aUV;
    vAlpha = aAlpha;
    vColor = aColor;
}"""

    /**
     * 通常スタンプ: 円形 softclip + hardness エッジ + スクリーン座標 AA。
     * uHardness=1 → ハードエッジ円形, uHardness=0 → Gaussian 的ソフトエッジ。
     * uAA=0 → AA なし, uAA=1 → 1px フェード (デフォルト), uAA=2 → 2px フェード。
     * dFdx/dFdy でスタンプ半径に依らず常に N ピクセル幅の AA 帯域を生成する。
     */
    const val STAMP_FRAG = """
#extension GL_OES_standard_derivatives : enable
precision mediump float;
uniform float uHardness;
uniform float uAA;
varying vec2 vUV;
varying float vAlpha;
varying vec4 vColor;
void main() {
    float d = length(vUV - 0.5);
    float pw = length(vec2(dFdx(d), dFdy(d)));
    float aaSpan = max(uAA * pw, 1e-5);
    float outerR = 0.5 + aaSpan * 0.5;
    float innerR = 0.5 - aaSpan * 0.5;
    if (d > outerR) discard;
    float hardEdge = 1.0 - smoothstep(0.5 * uHardness, 0.5, d);
    float aaEdge   = 1.0 - smoothstep(innerR, outerR, d);
    float edge = min(hardEdge, aaEdge);
    float a = vAlpha * edge;
    gl_FragColor = vec4(vColor.rgb * a, a);
}"""

    /**
     * 消しゴムスタンプ: 円形 softclip + hardness エッジ + スクリーン座標 AA。
     * Destination-out ブレンドと組み合わせて使用。
     * glBlendFuncSeparate(ZERO, ONE_MINUS_SRC_ALPHA, ZERO, ONE_MINUS_SRC_ALPHA) が必要。
     */
    const val ERASER_STAMP_FRAG = """
#extension GL_OES_standard_derivatives : enable
precision mediump float;
uniform float uHardness;
uniform float uAA;
varying vec2 vUV;
varying float vAlpha;
void main() {
    float d = length(vUV - 0.5);
    float pw = length(vec2(dFdx(d), dFdy(d)));
    float aaSpan = max(uAA * pw, 1e-5);
    float outerR = 0.5 + aaSpan * 0.5;
    float innerR = 0.5 - aaSpan * 0.5;
    if (d > outerR) discard;
    float hardEdge = 1.0 - smoothstep(0.5 * uHardness, 0.5, d);
    float aaEdge   = 1.0 - smoothstep(innerR, outerR, d);
    float edge = min(hardEdge, aaEdge);
    gl_FragColor = vec4(0.0, 0.0, 0.0, vAlpha * edge);
}"""

    // ── テクスチャコンポジット (FBO → FBO / Screen) ──────────────────────
    // 頂点データ: [x, y, u, v]  stride=16 bytes

    const val COMPOSITE_VERT = """
attribute vec2 aPos;
attribute vec2 aUV;
uniform mat4 uMVP;
varying vec2 vUV;
void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
    vUV = aUV;
}"""

    /** Normal (SrcOver) — プリマルチプライドアルファ入力を想定。GL_ONE, GL_ONE_MINUS_SRC_ALPHA と組み合わせて使用。 */
    const val COMPOSITE_FRAG_NORMAL = """
precision mediump float;
uniform sampler2D uTex;
uniform float uAlpha;
varying vec2 vUV;
void main() {
    vec4 c = texture2D(uTex, vUV);
    gl_FragColor = vec4(c.rgb * uAlpha, c.a * uAlpha);
}"""

    // ── グリッドライン ────────────────────────────────────────────────────
    // 頂点データ: [x, y, alpha]  stride=12 bytes

    const val LINE_VERT = """
attribute vec2 aPos;
attribute float aAlpha;
uniform mat4 uMVP;
varying float vAlpha;
void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
    vAlpha = aAlpha;
}"""

    const val LINE_FRAG = """
precision mediump float;
uniform vec4 uColor;
varying float vAlpha;
void main() {
    gl_FragColor = vec4(uColor.rgb, uColor.a * vAlpha);
}"""

    // ── 高度なブレンドモード (ping-pong FBO で使用) ───────────────────────
    // uTex = レイヤー, uDst = 現在のコンポジット結果

    private fun blendShader(blendExpr: String) = """
precision mediump float;
uniform sampler2D uTex;
uniform sampler2D uDst;
uniform float uAlpha;
varying vec2 vUV;
void main() {
    vec4 src = texture2D(uTex, vUV);
    vec4 dst = texture2D(uDst, vUV);
    float as_ = src.a * uAlpha;
    float ad  = dst.a;
    vec3 Cs = src.rgb / max(src.a, 0.0001);
    vec3 Cd = dst.rgb / max(dst.a, 0.0001);
    vec3 Cm = $blendExpr;
    float ao = as_ + ad * (1.0 - as_);
    vec3 co  = (Cm * as_ + Cd * ad * (1.0 - as_)) / max(ao, 0.0001);
    gl_FragColor = vec4(co * ao, ao);
}"""

    val COMPOSITE_FRAG_MULTIPLY = blendShader("Cs * Cd")
    val COMPOSITE_FRAG_SCREEN   = blendShader("Cs + Cd - Cs * Cd")
    val COMPOSITE_FRAG_OVERLAY  = blendShader(
        "mix(2.0*Cs*Cd, 1.0 - 2.0*(1.0-Cs)*(1.0-Cd), step(0.5, Cd))"
    )
    val COMPOSITE_FRAG_DARKEN   = blendShader("min(Cs, Cd)")
    val COMPOSITE_FRAG_LIGHTEN  = blendShader("max(Cs, Cd)")
}
