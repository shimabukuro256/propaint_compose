package com.propaint.app.engine

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ブラシエンジン: Drawpile brush_engine.c 準拠の完全書き直し。
 *
 * v1/旧v2 の致命的バグを修正:
 *  ・ぼかしツールに描画色が乗る → blur は smudge=1.0 でキャンバス色のみ使用
 *  ・Fude/Watercolor が混色しない → smudge サンプリングを CPU タイルから実行
 *  ・全ブラシが同じ描画パスを通る → Direct/Indirect を種別ごとに区別
 *
 * Drawpile のダブ色決定フロー:
 *   1. update_classic_smudge(): resmudge 距離ごとにキャンバスから色サンプリング
 *   2. blend_classic_color():   smudge_color × smudge + brush_color × (1-smudge)
 *   3. 合成した色でダブマスクをタイルに適用
 */
class BrushEngine(
    private val dirtyTracker: DirtyTileTracker,
) {
    /** 選択マスク参照 (null = マスクなし / 全選択) */
    var selectionMask: SelectionMask? = null
    // ── ストローク状態 ──────────────────────────────────────────────

    private var distToNextDab = 0f
    private val pointBuffer = ArrayList<StrokePoint>(512)

    // ストローク開始時のレイヤー ID (レイヤー切替バグ修正)
    var strokeLayerId: Int = -1
        private set

    data class StrokePoint(
        val x: Float, val y: Float,
        val pressure: Float = 1f,
        val timestamp: Long = 0L,
    )

    /** ストローク開始 */
    fun beginStroke(layerId: Int) {
        distToNextDab = 0f
        pointBuffer.clear()
        strokeLayerId = layerId
    }

    /**
     * ストロークにポイントを追加し、ダブを配置する。
     *
     * @param drawTarget  描画先サーフェス (Indirect→sublayer, Direct→layer.content)
     * @param sampleSource smudge/blur サンプリング元 (常に layer.content)
     *                     ※ sublayer ではなく本体レイヤーから読む (Drawpile 準拠)
     */
    fun addPoint(
        point: StrokePoint,
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        brush: BrushConfig,
    ) {
        pointBuffer.add(point)
        if (pointBuffer.size < 2) return

        val fromIdx = maxOf(0, pointBuffer.size - 2)
        distToNextDab = renderSegments(
            pointBuffer, fromIdx, distToNextDab,
            drawTarget, sampleSource, brush, applyExitTaper = false,
        )
    }

    fun endStroke() {
        pointBuffer.clear()
        distToNextDab = 0f
        strokeLayerId = -1
    }

    /** ベイク用: 全ストロークを再描画 */
    fun renderFullStroke(
        points: List<StrokePoint>,
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        brush: BrushConfig,
    ) {
        if (points.size < 2) return
        renderSegments(points, 0, 0f, drawTarget, sampleSource, brush, applyExitTaper = true)
    }

    // ── コア: セグメント描画 ─────────────────────────────────────────

    private fun renderSegments(
        pts: List<StrokePoint>,
        fromSegIdx: Int,
        initialDist: Float,
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        brush: BrushConfig,
        applyExitTaper: Boolean,
    ): Float {
        val nomRad = brush.size / 2f
        val spRad = minOf(nomRad, sqrt(nomRad * 30f))
        // マーカー: 内部スペーシングを固定 (Indirect モードで継ぎ目なし描画)
        val actualSpacing = if (brush.isMarker) 0.15f else brush.spacing
        val stepDist = maxOf(1f, spRad * 2f * actualSpacing)
        val effSpacing = (spRad / nomRad * actualSpacing).coerceAtLeast(0.001f)

        // 累積距離 (テーパー用)
        val cumDist = FloatArray(pts.size)
        for (k in 1 until pts.size) {
            val dx = pts[k].x - pts[k - 1].x; val dy = pts[k].y - pts[k - 1].y
            cumDist[k] = cumDist[k - 1] + sqrt(dx * dx + dy * dy)
        }
        val totalLen = if (pts.isNotEmpty()) cumDist.last() else 0f
        val taperLen = if (brush.taperEnabled && totalLen > 0f)
            (nomRad * 8f).coerceIn(20f, 400f).coerceAtMost(totalLen * 0.45f) else 0f

        // 混色ブラシ判定: smudge 付きの筆・水彩筆 (マーカー/ぼかし/消しゴムは除外)
        val isMixingBrush = brush.smudge > 0f && !brush.isMarker && !brush.isBlur && !brush.isEraser

        // ブレンドモードID
        // 混色ブラシは BLEND_NORMAL で描画し、後からフィルタで混色する
        val blendId = when {
            brush.isEraser -> PixelOps.BLEND_ERASE
            brush.isMarker -> PixelOps.BLEND_MARKER
            else -> PixelOps.BLEND_NORMAL
        }

        var dist = initialDist

        for (i in fromSegIdx until pts.size - 1) {
            val p0 = pts[i]; val p1 = pts[i + 1]
            val pPrev = pts[maxOf(0, i - 1)]
            val pNext = pts[minOf(pts.size - 1, i + 2)]
            val dx = p1.x - p0.x; val dy = p1.y - p0.y
            val segLen = sqrt(dx * dx + dy * dy)
            if (segLen < 0.001f) continue

            while (dist <= segLen) {
                val t = (dist / segLen).coerceIn(0f, 1f)

                // Catmull-Rom
                val px = catmullRom(pPrev.x, p0.x, p1.x, pNext.x, t)
                val py = catmullRom(pPrev.y, p0.y, p1.y, pNext.y, t)
                val pressure = p0.pressure + (p1.pressure - p0.pressure) * t

                // 筆圧→サイズ
                val pRad = if (brush.pressureSizeEnabled) {
                    nomRad * (brush.minSizeRatio + (1f - brush.minSizeRatio) * pressureCurve(pressure, brush.pressureSizeIntensity))
                } else nomRad

                // 筆圧→不透明度
                val pAlpha = if (brush.pressureOpacityEnabled)
                    pressureCurve(pressure, brush.pressureOpacityIntensity)
                else 1f

                // テーパー
                val dabDist = cumDist[i] + dist
                var taper = 1f
                if (taperLen > 0f) {
                    if (dabDist < taperLen) taper = smoothStep(dabDist / taperLen)
                    if (applyExitTaper) {
                        val fromEnd = totalLen - dabDist
                        if (fromEnd < taperLen) taper *= smoothStep(fromEnd / taperLen)
                    }
                }

                val finalRad = pRad * taper
                if (finalRad < 0.1f) { dist += stepDist; continue }

                // ── Smudge サンプリング (Drawpile update_classic_smudge) ────
                // マーカー/混色ブラシはフィルタ経由で処理するためサンプリング不要

                // ── ぼかしブラシ: ガウシアンブラーをダブ領域に適用 ────────
                if (brush.isBlur) {
                    val dab = DabMaskGenerator.createDab(px, py, finalRad * 2f, brush.hardness)
                    if (dab != null) {
                        val blurRad = maxOf(1, (finalRad * brush.blurStrength).toInt())
                        applyBlurDabToSurface(drawTarget, sampleSource, dab, blurRad,
                            (brush.density * pAlpha * taper).coerceIn(0f, 1f))
                    }
                    dist += stepDist; continue
                }

                // ── ダブ色決定 ────────────────────────────────────────────
                // 全ブラシ共通: 描画色をそのまま使用
                // マーカー/混色ブラシはフィルタ経由で色混合
                val dabColor: Int = brush.colorPremul

                // ── 混色ブラシ: ぼかし筆圧による着色/フィルタ分離 ──
                // threshold 以下: 着色なし + フィルタ100% (ぼかしツール的挙動)
                // threshold 以上: 着色が緩やかに増加 + フィルタが緩やかに減衰
                val colorBlend: Float  // 0=着色なし, 1=着色フル
                val filterBlend: Float // 0=フィルタなし, 1=フィルタフル
                if (isMixingBrush) {
                    val th = brush.filterPressureThreshold
                    if (pressure <= th) {
                        colorBlend = 0f
                        filterBlend = 1f
                    } else {
                        // threshold 超過分を 0..1 に正規化し、smoothStep で緩やかに遷移
                        val t = ((pressure - th) / (1f - th).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                        val smooth = t * t * (3f - 2f * t)
                        colorBlend = smooth
                        filterBlend = 1f - smooth * 0.8f // フィルタは完全には消えない (最低20%)
                    }
                } else {
                    colorBlend = 1f
                    filterBlend = 0f
                }

                // ダブ不透明度
                val opacityFactor = when {
                    brush.isMarker -> brush.opacity
                    isMixingBrush -> colorBlend * (1f - brush.smudge * 0.6f).coerceAtLeast(0.05f)
                    else -> 1f
                }
                val dabOpacity = (brush.density * pAlpha * taper * opacityFactor * 255f).toInt().coerceIn(0, 255)
                val compensated = spacingCompensate(dabOpacity / 255f, effSpacing)
                val finalOpacity = (compensated * 255f).toInt().coerceIn(0, 255)

                // ダブマスク生成
                val dab = if (brush.isBinaryPen)
                    DabMaskGenerator.createBinaryDab(px, py, finalRad * 2f)
                else
                    DabMaskGenerator.createDab(px, py, finalRad * 2f, brush.hardness)

                // ── 混色フィルタ: dab 描画前にブラー結果を事前計算 ──
                // Direct モードでは drawTarget == sampleSource なので、
                // dab 描画後にブラーすると描画色が混入する。
                // 先にキャンバスの現状からブラー結果を計算しておく。
                var preBlurResult: GaussianBlur.BlurResult? = null
                var mixDab: DabMask? = null
                var mixFilterIntensity = 0f
                if (isMixingBrush && filterBlend > 0.01f) {
                    val isWatercolor = brush.waterContent > 0f
                    mixDab = DabMaskGenerator.createDab(px, py, finalRad * 2f, brush.hardness * 0.5f)
                    if (mixDab != null) {
                        val mixBlurRad = if (isWatercolor) {
                            maxOf(2, (finalRad * brush.waterContent).toInt())
                        } else {
                            maxOf(2, (finalRad * brush.smudge).toInt())
                        }
                        mixFilterIntensity = brush.smudge * filterBlend
                        val rawAreaRadius = mixDab.diameter / 2
                        val areaRadius = rawAreaRadius.coerceAtMost(512)
                        val blurCx = mixDab.left + rawAreaRadius
                        val blurCy = mixDab.top + rawAreaRadius
                        val clampedBlurRadius = mixBlurRad.coerceIn(1, 20)
                        // sampleSource からブラーを事前計算 (dab 描画前の状態)
                        preBlurResult = GaussianBlur.blurLocalArea(
                            sampleSource, blurCx, blurCy, areaRadius, clampedBlurRadius,
                            useBoxBlur = !isWatercolor,
                        )
                    }
                }

                // ダブをタイルに合成 (着色: colorBlend > 0 の場合)
                if (dab != null && finalOpacity > 0) {
                    applyDabToSurface(drawTarget, dab, dabColor, finalOpacity, blendId)
                }

                // ── 混色フィルタ: 事前計算したブラー結果を書き戻し ──
                // 筆: ボックスブラー (平均化) — 均一な色混合
                // 水彩筆: ガウシアンブラー — 中心重み付き滲み効果
                if (preBlurResult != null && mixDab != null) {
                    applyPrecomputedBlurToSurface(
                        drawTarget, preBlurResult, mixDab, mixFilterIntensity,
                    )
                }

                // ── マーカー色混合フィルタ: アルファ比較 + キャンバス色ピックアップ ──
                // サブレイヤー上の描画済みピクセルの RGB をキャンバス色に寄せる
                // アルファは変更しない (BLEND_MARKER merge で不透明度比較に使う)
                if (brush.isMarker && brush.colorStretch > 0.01f && dab != null) {
                    applyMarkerColorMixFilter(
                        drawTarget, sampleSource, dab,
                        brush.colorStretch, brush.opacity,
                    )
                }

                dist += stepDist
            }
            dist -= segLen
        }
        return dist
    }

    // ── ダブ → タイル ────────────────────────────────────────────────

    private fun applyDabToSurface(
        surface: TiledSurface, dab: DabMask,
        color: Int, opacity: Int, blendMode: Int,
    ) {
        val dr = dab.left + dab.diameter; val db = dab.top + dab.diameter
        val tx0 = maxOf(0, surface.pixelToTile(dab.left))
        val ty0 = maxOf(0, surface.pixelToTile(dab.top))
        val tx1 = minOf(surface.tilesX - 1, surface.pixelToTile(dr - 1))
        val ty1 = minOf(surface.tilesY - 1, surface.pixelToTile(db - 1))

        val sel = selectionMask

        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            // 選択マスクが存在する場合、タイル全体が非選択ならスキップ
            val tileMask = if (sel != null && sel.hasSelection) sel.getTileMask(tx, ty) else null
            if (sel != null && sel.hasSelection && tileMask != null) {
                // タイル全体が 0 かチェック
                var hasAny = false
                for (b in tileMask) { if (b != 0.toByte()) { hasAny = true; break } }
                if (!hasAny) continue
            }

            val tile = surface.getOrCreateMutable(tx, ty)
            val ox = tx * Tile.SIZE; val oy = ty * Tile.SIZE

            if (tileMask != null) {
                // 選択マスク付き: ダブデータにマスクを乗算した一時コピーで描画
                applyDabToTileWithMask(
                    tile.pixels, dab, color, opacity, blendMode,
                    ox, oy, tileMask,
                )
            } else {
                // マスクなし (全選択 or 選択なし): 通常描画
                PixelOps.applyDabToTile(
                    tile.pixels, dab.data, dab.diameter, color, opacity, blendMode,
                    maxOf(0, dab.left - ox), maxOf(0, dab.top - oy),
                    minOf(Tile.SIZE, dr - ox), minOf(Tile.SIZE, db - oy),
                    dab.left - ox, dab.top - oy,
                )
            }
            dirtyTracker.markDirty(tx, ty)
        }
    }

    /**
     * 選択マスク付きのダブ→タイル合成。
     * ダブマスク値と選択マスク値を乗算してからブレンドする。
     */
    private fun applyDabToTileWithMask(
        tilePixels: IntArray,
        dab: DabMask,
        color: Int, opacity: Int, blendMode: Int,
        tileOX: Int, tileOY: Int,
        tileMask: ByteArray,
    ) {
        val dr = dab.left + dab.diameter; val db = dab.top + dab.diameter
        val localL = maxOf(0, dab.left - tileOX)
        val localT = maxOf(0, dab.top - tileOY)
        val localR = minOf(Tile.SIZE, dr - tileOX)
        val localB = minOf(Tile.SIZE, db - tileOY)
        val dabOX = dab.left - tileOX
        val dabOY = dab.top - tileOY

        for (ly in localT until localB) {
            val tileOff = ly * Tile.SIZE
            val dabRowOff = (ly - dabOY) * dab.diameter
            val maskOff = ly * Tile.SIZE
            for (lx in localL until localR) {
                val dabX = lx - dabOX
                if (dabX < 0 || dabX >= dab.diameter) continue
                val dabVal = dab.data[dabRowOff + dabX].toInt() and 0xFF
                if (dabVal == 0) continue

                val selVal = tileMask[maskOff + lx].toInt() and 0xFF
                if (selVal == 0) continue

                // ダブマスク値に選択マスクを乗算
                val maskedOpacity = PixelOps.div255(opacity * PixelOps.div255(dabVal * selVal))
                if (maskedOpacity == 0) continue

                PixelOps.blendPixel(tilePixels, tileOff + lx, color, maskedOpacity, blendMode)
            }
        }
    }

    // ── ガウシアンブラーダブ → タイル ─────────────────────────────────

    /**
     * ダブ領域に局所ガウシアンブラーを適用する。
     * 1. sampleSource からダブ周辺のピクセルを読み取りガウシアンブラーを適用
     * 2. ダブマスクを強度マスクとして、元のピクセルとブラー済みピクセルをブレンド
     * 3. 結果を drawTarget に書き戻す
     */
    private fun applyBlurDabToSurface(
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        dab: DabMask,
        blurRadius: Int,
        intensity: Float,
        useBoxBlur: Boolean = false,
    ) {
        val rawAreaRadius = dab.diameter / 2
        val areaRadius = rawAreaRadius.coerceAtMost(512) // blurLocalArea 側でダウンサンプリング
        val cx = dab.left + rawAreaRadius
        val cy = dab.top + rawAreaRadius
        val clampedBlurRadius = blurRadius.coerceIn(1, 20)

        val blurResult = GaussianBlur.blurLocalArea(
            sampleSource, cx, cy, areaRadius, clampedBlurRadius, useBoxBlur,
        )

        // ダブマスクを使ってブラー結果を drawTarget に書き込む
        val side = blurResult.side
        val oX = blurResult.originX
        val oY = blurResult.originY
        val sel = selectionMask

        val tx0 = maxOf(0, drawTarget.pixelToTile(oX))
        val ty0 = maxOf(0, drawTarget.pixelToTile(oY))
        val tx1 = minOf(drawTarget.tilesX - 1, drawTarget.pixelToTile(oX + side - 1))
        val ty1 = minOf(drawTarget.tilesY - 1, drawTarget.pixelToTile(oY + side - 1))

        for (tileY in ty0..ty1) for (tileX in tx0..tx1) {
            // 選択マスクチェック: タイル全体が非選択ならスキップ
            val tileMask = if (sel != null && sel.hasSelection) sel.getTileMask(tileX, tileY) else null
            if (sel != null && sel.hasSelection && tileMask != null) {
                var hasAny = false
                for (b in tileMask) { if (b != 0.toByte()) { hasAny = true; break } }
                if (!hasAny) continue
            }

            val tile = drawTarget.getOrCreateMutable(tileX, tileY)
            val tileOX = tileX * Tile.SIZE
            val tileOY = tileY * Tile.SIZE

            val localL = maxOf(0, oX - tileOX)
            val localT = maxOf(0, oY - tileOY)
            val localR = minOf(Tile.SIZE, oX + side - tileOX)
            val localB = minOf(Tile.SIZE, oY + side - tileOY)

            for (ly in localT until localB) {
                val py = tileOY + ly
                val blurY = py - oY
                if (blurY < 0 || blurY >= side) continue
                val tileOff = ly * Tile.SIZE
                val blurOff = blurY * side

                for (lx in localL until localR) {
                    val px = tileOX + lx
                    val blurX = px - oX
                    if (blurX < 0 || blurX >= side) continue

                    // ダブマスク値を取得 (0..255)
                    val maskX = px - dab.left
                    val maskY = py - dab.top
                    if (maskX < 0 || maskX >= dab.diameter || maskY < 0 || maskY >= dab.diameter) continue
                    val maskVal = dab.data[maskY * dab.diameter + maskX]
                    if (maskVal <= 0) continue

                    // 選択マスクで書込みをクリップ
                    val selVal = if (tileMask != null) tileMask[ly * Tile.SIZE + lx].toInt() and 0xFF else 255
                    if (selVal == 0) continue

                    // マスク値 × 強度 × 選択マスクでブレンド量を決定
                    val blend = (maskVal / 255f * intensity * selVal / 255f).coerceIn(0f, 1f)
                    val blurredPixel = blurResult.pixels[blurOff + blurX]
                    val origPixel = tile.pixels[tileOff + lx]

                    // 元のピクセルとブラー済みピクセルを線形補間
                    tile.pixels[tileOff + lx] = PixelOps.lerpColor(origPixel, blurredPixel, blend)
                }
            }
            dirtyTracker.markDirty(tileX, tileY)
        }
    }

    // ── 混色ブラシ: 事前計算済みブラー結果の適用 ───────────────────────

    /**
     * 事前計算済みブラー結果をダブマスクに従って drawTarget に書き戻す。
     *
     * applyBlurDabToSurface と異なり、ブラー計算は完了済み。
     * Direct モードで dab 描画前のキャンバス状態からブラーを計算し、
     * dab 描画後にこの関数で書き戻すことで、描画色の混入を防ぐ。
     */
    private fun applyPrecomputedBlurToSurface(
        drawTarget: TiledSurface,
        blurResult: GaussianBlur.BlurResult,
        dab: DabMask,
        intensity: Float,
    ) {
        val side = blurResult.side
        val oX = blurResult.originX
        val oY = blurResult.originY
        val sel = selectionMask

        val tx0 = maxOf(0, drawTarget.pixelToTile(oX))
        val ty0 = maxOf(0, drawTarget.pixelToTile(oY))
        val tx1 = minOf(drawTarget.tilesX - 1, drawTarget.pixelToTile(oX + side - 1))
        val ty1 = minOf(drawTarget.tilesY - 1, drawTarget.pixelToTile(oY + side - 1))

        for (tileY in ty0..ty1) for (tileX in tx0..tx1) {
            // 選択マスクチェック: タイル全体が非選択ならスキップ
            val tileMask = if (sel != null && sel.hasSelection) sel.getTileMask(tileX, tileY) else null
            if (sel != null && sel.hasSelection && tileMask != null) {
                var hasAny = false
                for (b in tileMask) { if (b != 0.toByte()) { hasAny = true; break } }
                if (!hasAny) continue
            }

            val tile = drawTarget.getOrCreateMutable(tileX, tileY)
            val tileOX = tileX * Tile.SIZE
            val tileOY = tileY * Tile.SIZE

            val localL = maxOf(0, oX - tileOX)
            val localT = maxOf(0, oY - tileOY)
            val localR = minOf(Tile.SIZE, oX + side - tileOX)
            val localB = minOf(Tile.SIZE, oY + side - tileOY)

            for (ly in localT until localB) {
                val py = tileOY + ly
                val blurY = py - oY
                if (blurY < 0 || blurY >= side) continue
                val tileOff = ly * Tile.SIZE
                val blurOff = blurY * side

                for (lx in localL until localR) {
                    val px = tileOX + lx
                    val blurX = px - oX
                    if (blurX < 0 || blurX >= side) continue

                    // ダブマスク値を取得 (0..255)
                    val maskX = px - dab.left
                    val maskY = py - dab.top
                    if (maskX < 0 || maskX >= dab.diameter || maskY < 0 || maskY >= dab.diameter) continue
                    val maskVal = dab.data[maskY * dab.diameter + maskX]
                    if (maskVal <= 0) continue

                    // 選択マスクで書込みをクリップ
                    val selVal = if (tileMask != null) tileMask[ly * Tile.SIZE + lx].toInt() and 0xFF else 255
                    if (selVal == 0) continue

                    // マスク値 × 強度 × 選択マスクでブレンド量を決定
                    val blend = (maskVal / 255f * intensity * selVal / 255f).coerceIn(0f, 1f)
                    val blurredPixel = blurResult.pixels[blurOff + blurX]
                    val origPixel = tile.pixels[tileOff + lx]

                    // 元のピクセル (dab 描画済み) とブラー済みピクセル (dab 描画前) を線形補間
                    tile.pixels[tileOff + lx] = PixelOps.lerpColor(origPixel, blurredPixel, blend)
                }
            }
            dirtyTracker.markDirty(tileX, tileY)
        }
    }

    // ── マーカー色混合フィルタ ───────────────────────────────────────

    /**
     * マーカーブラシ用: サブレイヤー上の描画済みピクセルの RGB をキャンバス色に寄せる。
     * アルファは変更しない (BLEND_MARKER merge で不透明度比較に使うため)。
     *
     * 処理: sublayerRGB = lerp(sublayerRGB, canvasRGB, colorStretch)
     * ただし sublayer alpha == 0 のピクセルはスキップ。
     *
     * @param drawTarget サブレイヤー (Indirect モード)
     * @param sampleSource キャンバス本体 (layer.content)
     * @param dab ダブマスク (処理範囲限定用)
     * @param colorStretch 色伸び量 (0=純ブラシ色, 1=完全にキャンバス色)
     * @param markerOpacity マーカーの不透明度 (アルファ上限)
     */
    private fun applyMarkerColorMixFilter(
        drawTarget: TiledSurface,
        sampleSource: TiledSurface,
        dab: DabMask,
        colorStretch: Float,
        @Suppress("UNUSED_PARAMETER") markerOpacity: Float,
    ) {
        val dr = dab.left + dab.diameter; val db = dab.top + dab.diameter
        val tx0 = maxOf(0, drawTarget.pixelToTile(dab.left))
        val ty0 = maxOf(0, drawTarget.pixelToTile(dab.top))
        val tx1 = minOf(drawTarget.tilesX - 1, drawTarget.pixelToTile(dr - 1))
        val ty1 = minOf(drawTarget.tilesY - 1, drawTarget.pixelToTile(db - 1))

        for (tileY in ty0..ty1) for (tileX in tx0..tx1) {
            // まず読み取り専用でチェック (空タイルならスキップ)
            if (drawTarget.getTile(tileX, tileY) == null) continue
            val tile = drawTarget.getOrCreateMutable(tileX, tileY)
            val tileOX = tileX * Tile.SIZE
            val tileOY = tileY * Tile.SIZE
            val localL = maxOf(0, dab.left - tileOX)
            val localT = maxOf(0, dab.top - tileOY)
            val localR = minOf(Tile.SIZE, dr - tileOX)
            val localB = minOf(Tile.SIZE, db - tileOY)
            var modified = false

            for (ly in localT until localB) {
                val py = tileOY + ly
                val maskY = py - dab.top
                if (maskY < 0 || maskY >= dab.diameter) continue
                val tileOff = ly * Tile.SIZE

                for (lx in localL until localR) {
                    val px = tileOX + lx
                    val maskX = px - dab.left
                    if (maskX < 0 || maskX >= dab.diameter) continue
                    val maskVal = dab.data[maskY * dab.diameter + maskX]
                    if (maskVal <= 0) continue

                    val subPixel = tile.pixels[tileOff + lx]
                    val subA = PixelOps.alpha(subPixel)
                    if (subA == 0) continue

                    // キャンバスから色を読み取り
                    val canvasPixel = sampleSource.getPixelAt(px, py)
                    val canvasA = PixelOps.alpha(canvasPixel)
                    if (canvasA == 0) continue

                    // マスク強度に応じた混合量
                    val blend = (maskVal / 255f * colorStretch).coerceIn(0f, 1f)

                    // RGB のみ混合 (premultiplied なので alpha 比率で un-premul → mix → re-premul)
                    // 簡易版: premultiplied のまま RGB をブレンド、alpha は保持
                    val subR = PixelOps.red(subPixel)
                    val subG = PixelOps.green(subPixel)
                    val subB = PixelOps.blue(subPixel)
                    val canR = PixelOps.red(canvasPixel)
                    val canG = PixelOps.green(canvasPixel)
                    val canB = PixelOps.blue(canvasPixel)

                    // キャンバス色の premul 値をサブレイヤーのアルファに合わせてスケール
                    val scale = if (canvasA > 0) subA.toFloat() / canvasA.toFloat() else 0f
                    val scaledR = (canR * scale).toInt().coerceIn(0, subA)
                    val scaledG = (canG * scale).toInt().coerceIn(0, subA)
                    val scaledB = (canB * scale).toInt().coerceIn(0, subA)

                    val newR = (subR + (scaledR - subR) * blend).toInt().coerceIn(0, subA)
                    val newG = (subG + (scaledG - subG) * blend).toInt().coerceIn(0, subA)
                    val newB = (subB + (scaledB - subB) * blend).toInt().coerceIn(0, subA)

                    // アルファは保持 (endStroke の merge で opacity 制御)
                    tile.pixels[tileOff + lx] = PixelOps.pack(subA, newR, newG, newB)
                    modified = true
                }
            }
            if (modified) dirtyTracker.markDirty(tileX, tileY)
        }
    }

    // ── ヘルパー ─────────────────────────────────────────────────────

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun smoothStep(t: Float): Float {
        val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c)
    }

    /**
     * 筆圧カーブ with intensity (Drawpile DP_ClassicBrushCurve 簡易版)
     * intensity=100 → gamma=0.65 (標準)
     */
    private fun pressureCurve(p: Float, intensity: Int = 100): Float {
        val gamma = if (intensity <= 100) 0.1f + (intensity - 1) / 99f * 0.55f
        else 0.65f + (intensity - 100) / 100f * 1.35f
        return p.coerceIn(0f, 1f).toDouble().pow(gamma.toDouble()).toFloat()
    }

    private fun spacingCompensate(density: Float, spacing: Float): Float {
        val s = spacing.coerceAtLeast(0.001f)
        return (1f - (1f - density).toDouble().pow(s.toDouble()).toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * ブラシ設定。v1 の BrushSettings を Drawpile DP_ClassicBrush に寄せて再設計。
 */
data class BrushConfig(
    val size: Float = 10f,
    val opacity: Float = 1f,
    val density: Float = 0.8f,
    val spacing: Float = 0.1f,
    val hardness: Float = 0.8f,
    val colorPremul: Int = 0xFF000000.toInt(),
    // ── Drawpile 準拠フラグ ──
    val isEraser: Boolean = false,
    val isMarker: Boolean = false,
    val isBlur: Boolean = false,
    /** バイナリペン (2値化: AA なし、硬さ無視) */
    val isBinaryPen: Boolean = false,
    /** Smudge 量 (0=描画色のみ, 1=キャンバス色のみ)。Drawpile cb->smudge.max */
    val smudge: Float = 0f,
    /** 何ダブごとに再サンプリングするか。Drawpile cb->resmudge。0=毎ダブ */
    val resmudge: Int = 0,
    /** ぼかし強度 (blur 用: サンプリング半径の倍率) */
    val blurStrength: Float = 1f,
    /** Drawpile paint_mode: true=Indirect(Wash), false=Direct */
    val indirect: Boolean = true,
    // ── 筆圧設定 ──
    val pressureSizeEnabled: Boolean = true,
    val pressureOpacityEnabled: Boolean = false,
    val pressureSmudgeEnabled: Boolean = false,
    val pressureSizeIntensity: Int = 100,
    val pressureOpacityIntensity: Int = 100,
    val pressureSmudgeIntensity: Int = 100,
    val minSizeRatio: Float = 0.2f,
    // ── テーパー ──
    val taperEnabled: Boolean = true,
    // ── 水彩 ──
    val waterContent: Float = 0f,
    val colorStretch: Float = 0f,
    // ── ぼかし筆圧 (混色ブラシ用) ──
    /** この筆圧以下ではブラシ色を塗らずフィルタのみ適用。以上では色が乗りフィルタが減衰。 */
    val filterPressureThreshold: Float = 0.3f,
    // ── AA ──
    val antiAliasing: Float = 1f,
) {
    companion object {
        fun fromColor(argb: Int, size: Float = 10f, hardness: Float = 0.8f) =
            BrushConfig(size = size, hardness = hardness,
                colorPremul = PixelOps.premultiply(argb))
    }
}
