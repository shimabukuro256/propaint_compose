package com.propaint.app.engine

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Layer(
    val id: Int, var name: String,
    val content: TiledSurface,
    var opacity: Float = 1f,
    var blendMode: Int = PixelOps.BLEND_NORMAL,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var isClipToBelow: Boolean = false,
    var isAlphaLocked: Boolean = false,
) {
    /** Indirect 描画用サブレイヤー */
    var sublayer: TiledSurface? = null
}

/**
 * 全キャンバス状態の単一ソース。
 *
 * v1→v2 で修正した致命的バグ:
 *  ・ストローク中のレイヤー切替で描画不能
 *    → strokeLayerId でストローク開始レイヤーを記録し、endStroke は必ずそこに合成
 *  ・サブレイヤーが放置される
 *    → レイヤー切替時に強制 endStroke / サブレイヤー破棄
 */
class CanvasDocument(val width: Int, val height: Int) {

    val dirtyTracker = DirtyTileTracker()
    val brushEngine = BrushEngine(dirtyTracker)
    var selectionMask: SelectionMask? = null
        private set

    /** GL スレッドへの選択マスク COW スナップショット。consumeAndSet パターン。 */
    val selectionSnapshot = AtomicReference<ByteArray?>(null)

    /** 選択が現在アクティブか (GL スレッドから参照可能) */
    @Volatile var hasActiveSelection = false
        private set

    private val lock = ReentrantLock()
    private val _layers = ArrayList<Layer>()
    val layers: List<Layer> get() = _layers

    var activeLayerId: Int = -1; private set
    private var nextLayerId = 1

    // Undo
    private val undoStack = ArrayList<UndoEntry>(50)
    private val redoStack = ArrayList<UndoEntry>(50)

    // ストローク中フラグ
    private var strokeInProgress = false
    private var strokeBrush: BrushConfig? = null

    // 合成キャッシュ
    private val tilesX get() = (width + Tile.SIZE - 1) / Tile.SIZE
    private val tilesY get() = (height + Tile.SIZE - 1) / Tile.SIZE
    val compositeCache: Array<IntArray?> = arrayOfNulls(tilesX * tilesY)

    init { addLayer("レイヤー 1") }

    // ── レイヤー操作 ────────────────────────────────────────────────

    fun addLayer(name: String, atIndex: Int = _layers.size): Layer = lock.withLock {
        forceEndStrokeIfNeeded()
        val l = Layer(nextLayerId++, name, TiledSurface(width, height))
        _layers.add(atIndex, l)
        if (activeLayerId < 0) activeLayerId = l.id
        dirtyTracker.markFullRebuild(); l
    }

    fun removeLayer(layerId: Int): Boolean = lock.withLock {
        if (_layers.size <= 1) return false
        forceEndStrokeIfNeeded()
        val idx = _layers.indexOfFirst { it.id == layerId }; if (idx < 0) return false
        pushUndoStructural()
        _layers.removeAt(idx)
        if (activeLayerId == layerId) activeLayerId = _layers[maxOf(0, idx - 1)].id
        dirtyTracker.markFullRebuild(); true
    }

    fun setActiveLayer(layerId: Int) = lock.withLock {
        if (!_layers.any { it.id == layerId }) return
        // ストローク中にレイヤー切替 → 現在のストロークを強制確定
        forceEndStrokeIfNeeded()
        activeLayerId = layerId
    }

    fun getActiveLayer(): Layer? = _layers.find { it.id == activeLayerId }

    /** strokeLayerId で指定されたレイヤーを取得 (endStroke はこちらを使う) */
    private fun getStrokeLayer(): Layer? =
        _layers.find { it.id == brushEngine.strokeLayerId }

    fun moveLayer(from: Int, to: Int) = lock.withLock {
        if (from == to || from !in _layers.indices || to !in _layers.indices) return
        forceEndStrokeIfNeeded()
        val l = _layers.removeAt(from); _layers.add(to, l)
        dirtyTracker.markFullRebuild()
    }

    fun setLayerOpacity(id: Int, v: Float) = lock.withLock {
        _layers.find { it.id == id }?.let { it.opacity = v.coerceIn(0f, 1f); dirtyTracker.markFullRebuild() }
    }

    fun setLayerBlendMode(id: Int, mode: Int) = lock.withLock {
        _layers.find { it.id == id }?.let { it.blendMode = mode; dirtyTracker.markFullRebuild() }
    }

    fun setLayerVisibility(id: Int, v: Boolean) = lock.withLock {
        _layers.find { it.id == id }?.let { it.isVisible = v; dirtyTracker.markFullRebuild() }
    }

    fun setLayerClipToBelow(id: Int, clip: Boolean) = lock.withLock {
        _layers.find { it.id == id }?.let { it.isClipToBelow = clip; dirtyTracker.markFullRebuild() }
    }

    fun setLayerLocked(id: Int, locked: Boolean) = lock.withLock {
        _layers.find { it.id == id }?.let { it.isLocked = locked }
    }

    fun duplicateLayer(id: Int): Layer? = lock.withLock {
        forceEndStrokeIfNeeded()
        val src = _layers.find { it.id == id } ?: return null
        val idx = _layers.indexOf(src)
        val copy = Layer(nextLayerId++, "${src.name} コピー", src.content.snapshot(),
            src.opacity, src.blendMode, src.isVisible)
        _layers.add(idx + 1, copy)
        dirtyTracker.markFullRebuild(); copy
    }

    fun mergeDown(id: Int): Boolean = lock.withLock {
        forceEndStrokeIfNeeded()
        val idx = _layers.indexOfFirst { it.id == id }; if (idx <= 0) return false
        pushUndoStructural()
        val upper = _layers[idx]; val lower = _layers[idx - 1]
        compositeTwoLayers(lower.content, upper.content, upper.opacity, upper.blendMode)
        _layers.removeAt(idx)
        if (activeLayerId == upper.id) activeLayerId = lower.id
        dirtyTracker.markFullRebuild(); true
    }

    fun clearLayer(id: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == id } ?: return
        pushUndoTileDelta(layer)
        layer.content.clear()
        dirtyTracker.markFullRebuild()
    }

    /** レイヤーを左右反転 */
    fun flipLayerHorizontal(id: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == id } ?: return
        pushUndoTileDelta(layer)
        flipSurfaceHorizontal(layer.content)
        dirtyTracker.markFullRebuild()
    }

    /** レイヤーを上下反転 */
    fun flipLayerVertical(id: Int) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == id } ?: return
        pushUndoTileDelta(layer)
        flipSurfaceVertical(layer.content)
        dirtyTracker.markFullRebuild()
    }

    private fun flipSurfaceHorizontal(surface: TiledSurface) {
        val pixels = surface.toPixelArray()
        val w = surface.width; val h = surface.height
        for (y in 0 until h) {
            val off = y * w
            var l = 0; var r = w - 1
            while (l < r) {
                val tmp = pixels[off + l]; pixels[off + l] = pixels[off + r]; pixels[off + r] = tmp
                l++; r--
            }
        }
        writePixelsToSurface(surface, pixels, w, h)
    }

    private fun flipSurfaceVertical(surface: TiledSurface) {
        val pixels = surface.toPixelArray()
        val w = surface.width; val h = surface.height
        var top = 0; var bot = h - 1
        while (top < bot) {
            val topOff = top * w; val botOff = bot * w
            for (x in 0 until w) {
                val tmp = pixels[topOff + x]; pixels[topOff + x] = pixels[botOff + x]; pixels[botOff + x] = tmp
            }
            top++; bot--
        }
        writePixelsToSurface(surface, pixels, w, h)
    }

    private fun writePixelsToSurface(surface: TiledSurface, pixels: IntArray, w: Int, h: Int) {
        surface.clear()
        for (ty in 0 until surface.tilesY) for (tx in 0 until surface.tilesX) {
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            var hasData = false
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= h) break
                val cw = minOf(Tile.SIZE, w - bx)
                for (lx in 0 until cw) {
                    if (pixels[py * w + bx + lx] != 0) { hasData = true; break }
                }
                if (hasData) break
            }
            if (!hasData) continue
            val tile = surface.getOrCreateMutable(tx, ty)
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= h) break
                val cw = minOf(Tile.SIZE, w - bx)
                System.arraycopy(pixels, py * w + bx, tile.pixels, ly * Tile.SIZE, cw)
            }
        }
    }

    // ── 選択操作 ────────────────────────────────────────────────────

    /** 選択マスクが有効か */
    val hasSelection: Boolean get() = selectionMask?.hasSelection == true

    /** 選択マスクを取得または作成 */
    fun getOrCreateSelectionMask(): SelectionMask {
        var m = selectionMask
        if (m == null || m.width != width || m.height != height) {
            m = SelectionMask(width, height)
            selectionMask = m
        }
        return m
    }

    /**
     * 選択マスクのスナップショットを GL スレッドに公開し、dirty 通知する。
     * コンポジットキャッシュの再構築は不要 (GPU オーバーレイで表示)。
     */
    fun publishSelectionSnapshot() {
        val mask = selectionMask
        if (mask != null && mask.hasSelection) {
            selectionSnapshot.set(mask.data.copyOf())
            hasActiveSelection = true
        } else {
            selectionSnapshot.set(null)
            hasActiveSelection = false
        }
        dirtyTracker.markSelectionDirty()
    }

    /** 矩形選択 */
    fun selectRect(x1: Int, y1: Int, x2: Int, y2: Int, addMode: Boolean = false) = lock.withLock {
        getOrCreateSelectionMask().selectRect(x1, y1, x2, y2, addMode)
        publishSelectionSnapshot()
    }

    /** 自動選択 (色域) */
    fun autoSelect(startX: Int, startY: Int, tolerance: Int, addMode: Boolean = false) = lock.withLock {
        val layer = getActiveLayer() ?: return
        getOrCreateSelectionMask().autoSelect(layer.content, startX, startY, tolerance, addMode)
        publishSelectionSnapshot()
    }

    /** 選択範囲を全選択 */
    fun selectAll() = lock.withLock {
        getOrCreateSelectionMask().selectAll()
        publishSelectionSnapshot()
    }

    /** 選択範囲を解除 */
    fun clearSelection() = lock.withLock {
        selectionMask?.clear()
        selectionMask = null
        selectionSnapshot.set(null)
        hasActiveSelection = false
        dirtyTracker.markSelectionDirty()
    }

    /** 選択範囲を反転 */
    fun invertSelection() = lock.withLock {
        if (selectionMask == null) {
            getOrCreateSelectionMask().selectAll()
        } else {
            selectionMask!!.invert()
        }
        publishSelectionSnapshot()
    }

    // ── 描画操作 ────────────────────────────────────────────────────

    fun beginStroke(brush: BrushConfig) = lock.withLock {
        val layer = getActiveLayer() ?: return
        if (layer.isLocked) return

        // 前のストロークが残っていたら強制確定
        forceEndStrokeIfNeeded()

        pushUndoTileDelta(layer)

        // BrushEngine に選択マスク参照を渡す
        brushEngine.selectionMask = if (selectionMask?.hasSelection == true) selectionMask else null

        // BrushEngine にストローク開始レイヤーを記録
        brushEngine.beginStroke(layer.id)
        strokeBrush = brush
        strokeInProgress = true

        // Indirect モード: サブレイヤーを作成
        if (brush.indirect && !brush.isEraser && !brush.isBlur) {
            layer.sublayer = TiledSurface(width, height)
        }
    }

    fun strokeTo(point: BrushEngine.StrokePoint, brush: BrushConfig) = lock.withLock {
        if (!strokeInProgress) return
        val layer = getStrokeLayer() ?: return

        // 描画先: Indirect → sublayer, Direct → content
        val drawTarget = layer.sublayer ?: layer.content
        // サンプリング元: 常に layer.content (sublayer ではない!)
        // ※ これが Drawpile の get_sample_layer_content パターン
        val sampleSource = layer.content

        brushEngine.addPoint(point, drawTarget, sampleSource, brush)
        // 注意: 個別タイルの dirty マークは BrushEngine.applyDabToSurface 内で行われる。
        // ここで markFullRebuild() を呼ぶとダーティタイル最適化が無効化されるため不要。
    }

    fun endStroke(brush: BrushConfig) = lock.withLock {
        if (!strokeInProgress) return
        val layer = getStrokeLayer()
        brushEngine.endStroke()
        strokeInProgress = false
        strokeBrush = null

        if (layer != null) {
            val sub = layer.sublayer
            if (sub != null) {
                // サブレイヤーを本体に合成
                // マーカー: BLEND_MARKER で合成 (既存アルファより高い部分のみ描画)
                val mergeBlend = if (brush.isMarker) PixelOps.BLEND_MARKER else PixelOps.BLEND_NORMAL
                compositeTwoLayers(layer.content, sub, brush.opacity, mergeBlend)
                layer.sublayer = null
            }
        }
        redoStack.clear()
        dirtyTracker.markFullRebuild()
    }

    /** ストローク中にレイヤー切替等が起きた場合の強制確定 */
    private fun forceEndStrokeIfNeeded() {
        if (!strokeInProgress) return
        val brush = strokeBrush ?: BrushConfig()
        val layer = getStrokeLayer()
        brushEngine.endStroke()
        strokeInProgress = false
        strokeBrush = null
        if (layer != null) {
            val sub = layer.sublayer
            if (sub != null) {
                val mergeBlend = if (brush.isMarker) PixelOps.BLEND_MARKER else PixelOps.BLEND_NORMAL
                compositeTwoLayers(layer.content, sub, brush.opacity, mergeBlend)
                layer.sublayer = null
            }
        }
    }

    // ── スポイト (Eyedropper) ────────────────────────────────────────

    /**
     * 合成済みキャンバスから色をサンプリング。
     * compositeCache を使わず単一ピクセルの合成を直接計算する。
     * (compositeCache は GL スレッドのみが使用するため、メインスレッドから安全にアクセスできない)
     */
    fun eyedropperAt(px: Int, py: Int): Int = lock.withLock {
        if (px < 0 || px >= width || py < 0 || py >= height) return 0xFF000000.toInt()
        var result = 0xFFFFFFFF.toInt() // 白背景
        for (layer in _layers) {
            if (!layer.isVisible) continue
            val op255 = (layer.opacity * 255f).toInt(); if (op255 <= 0) continue
            val mainPixel = layer.content.getPixelAt(px, py)
            val subPixel = layer.sublayer?.getPixelAt(px, py) ?: 0
            val pixel = if (subPixel != 0 && mainPixel != 0) {
                PixelOps.blendSrcOver(mainPixel, subPixel)
            } else if (subPixel != 0) subPixel else mainPixel
            if (PixelOps.alpha(pixel) == 0) continue
            result = PixelOps.blendSrcOverOpacity(result, pixel, op255)
        }
        result
    }

    // ── レイヤー合成 ────────────────────────────────────────────────

    fun rebuildCompositeCache() = lock.withLock {
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            rebuildCompositeTileInner(tx, ty, ty * tilesX + tx)
        }
    }

    fun rebuildCompositeTile(tx: Int, ty: Int, cacheIdx: Int) = lock.withLock {
        rebuildCompositeTileInner(tx, ty, cacheIdx)
    }

    private fun rebuildCompositeTileInner(tx: Int, ty: Int, cacheIdx: Int) {
        val result = compositeCache[cacheIdx]
            ?: IntArray(Tile.LENGTH).also { compositeCache[cacheIdx] = it }
        result.fill(0xFFFFFFFF.toInt())

        // クリッピング用ベースタイル追跡
        var clipBaseTile: IntArray? = null

        for (layer in _layers) {
            if (!layer.isVisible) continue
            val op255 = (layer.opacity * 255f).toInt(); if (op255 <= 0) continue

            val mainTile = layer.content.getTile(tx, ty)
            val subTile = layer.sublayer?.getTile(tx, ty)
            if (mainTile == null && subTile == null) {
                // 空レイヤーはクリッピングベースを更新しない (null にしてしまうと
                // 後続のクリッピングレイヤーが機能しなくなるバグの修正)
                continue
            }

            // 本体 + サブレイヤー合成
            val srcPixels: IntArray = when {
                mainTile != null && subTile != null -> {
                    mainTile.pixels.copyOf().also {
                        PixelOps.compositeLayer(it, subTile.pixels, 255, PixelOps.BLEND_NORMAL)
                    }
                }
                subTile != null -> subTile.pixels
                else -> mainTile!!.pixels
            }

            // クリッピング
            if (layer.isClipToBelow && clipBaseTile != null) {
                val clipped = srcPixels.copyOf()
                for (i in 0 until Tile.LENGTH) {
                    val ma = PixelOps.alpha(clipBaseTile[i])
                    if (ma < 255) {
                        val sa = PixelOps.alpha(clipped[i])
                        val na = PixelOps.div255(sa * ma)
                        if (na == 0) { clipped[i] = 0; continue }
                        if (sa > 0) {
                            val s = na.toFloat() / sa
                            clipped[i] = PixelOps.pack(na,
                                (PixelOps.red(clipped[i]) * s).toInt(),
                                (PixelOps.green(clipped[i]) * s).toInt(),
                                (PixelOps.blue(clipped[i]) * s).toInt())
                        }
                    }
                }
                PixelOps.compositeLayer(result, clipped, op255, layer.blendMode)
            } else {
                PixelOps.compositeLayer(result, srcPixels, op255, layer.blendMode)
                clipBaseTile = srcPixels // 次のクリッピング用ベース更新
            }
        }
        // 選択マスク表示は GPU オーバーレイに移行 (CanvasRenderer で処理)
    }

    private fun compositeTwoLayers(dst: TiledSurface, src: TiledSurface, opacity: Float, blendMode: Int) {
        val op255 = (opacity * 255f).toInt().coerceIn(0, 255)
        for (ty in 0 until src.tilesY) for (tx in 0 until src.tilesX) {
            val st = src.getTile(tx, ty) ?: continue
            val dt = dst.getOrCreateMutable(tx, ty)
            PixelOps.compositeLayer(dt.pixels, st.pixels, op255, blendMode)
            dirtyTracker.markDirty(tx, ty)
        }
    }

    // ── フィルター ──────────────────────────────────────────────────

    fun applyHslFilter(layerId: Int, hue: Float, sat: Float, lit: Float) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        for (i in layer.content.tiles.indices) {
            val tile = layer.content.tiles[i] ?: continue
            val mt = if (tile.refCount > 1) { tile.decRef(); tile.mutableCopy().also { layer.content.tiles[i] = it } } else tile
            for (p in mt.pixels.indices) {
                val c = mt.pixels[p]; val a = PixelOps.alpha(c); if (a == 0) continue
                val up = PixelOps.unpremultiply(c)
                val hsv = FloatArray(3)
                android.graphics.Color.RGBToHSV(PixelOps.red(up), PixelOps.green(up), PixelOps.blue(up), hsv)
                hsv[0] = (hsv[0] + hue + 360f) % 360f
                hsv[1] = (hsv[1] + sat).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] + lit).coerceIn(0f, 1f)
                val rgb = android.graphics.Color.HSVToColor(a, hsv)
                mt.pixels[p] = PixelOps.premultiply(rgb)
            }
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyBrightnessContrast(layerId: Int, brightness: Float, contrast: Float) = lock.withLock {
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)
        val factor = (259f * (contrast * 255f + 255f)) / (255f * (259f - contrast * 255f))
        for (i in layer.content.tiles.indices) {
            val tile = layer.content.tiles[i] ?: continue
            val mt = if (tile.refCount > 1) { tile.decRef(); tile.mutableCopy().also { layer.content.tiles[i] = it } } else tile
            for (p in mt.pixels.indices) {
                val c = mt.pixels[p]; val a = PixelOps.alpha(c); if (a == 0) continue
                val up = PixelOps.unpremultiply(c)
                var r = PixelOps.red(up); var g = PixelOps.green(up); var b = PixelOps.blue(up)
                // brightness
                r = (r + brightness * 255f).toInt(); g = (g + brightness * 255f).toInt(); b = (b + brightness * 255f).toInt()
                // contrast
                r = (factor * (r - 128) + 128).toInt(); g = (factor * (g - 128) + 128).toInt(); b = (factor * (b - 128) + 128).toInt()
                mt.pixels[p] = PixelOps.premultiply(PixelOps.pack(a, r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255)))
            }
        }
        dirtyTracker.markFullRebuild()
    }

    fun applyBlurFilter(layerId: Int, radius: Int) = lock.withLock {
        if (radius <= 0) return
        forceEndStrokeIfNeeded()
        val layer = _layers.find { it.id == layerId } ?: return
        pushUndoTileDelta(layer)

        val src = layer.content.toPixelArray()
        val blurred = GaussianBlur.blur(src, width, height, radius)

        // タイルに書き戻す
        for (ty in 0 until layer.content.tilesY) for (tx in 0 until layer.content.tilesX) {
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            // ぼかし結果にピクセルがあるかチェック
            var hasContent = false
            for (ly in 0 until minOf(Tile.SIZE, height - by)) {
                val off = (by + ly) * width + bx
                val cw = minOf(Tile.SIZE, width - bx)
                for (lx in 0 until cw) { if (blurred[off + lx] != 0) { hasContent = true; break } }
                if (hasContent) break
            }
            if (!hasContent) {
                // タイルを空にする
                val idx = layer.content.tileIndex(tx, ty)
                layer.content.tiles[idx]?.decRef()
                layer.content.tiles[idx] = null
                continue
            }
            val tile = layer.content.getOrCreateMutable(tx, ty)
            for (ly in 0 until minOf(Tile.SIZE, height - by)) {
                val py = by + ly
                val cw = minOf(Tile.SIZE, width - bx)
                System.arraycopy(blurred, py * width + bx, tile.pixels, ly * Tile.SIZE, cw)
            }
        }
        dirtyTracker.markFullRebuild()
    }

    // ── Undo/Redo ───────────────────────────────────────────────────

    sealed class UndoEntry {
        data class TileDelta(val layerId: Int, val snapshots: Map<Int, IntArray>) : UndoEntry()
        data class Structural(val snapshots: List<LayerSnapshot>, val activeId: Int) : UndoEntry()
    }

    data class LayerSnapshot(
        val id: Int, val name: String, val surface: TiledSurface,
        val opacity: Float, val blendMode: Int, val isVisible: Boolean, val isClip: Boolean,
    )

    /** 塗りつぶし等の単発操作前に undo スナップショットを記録 */
    fun pushUndoForFill() = lock.withLock {
        val layer = layers.find { it.id == activeLayerId } ?: return
        pushUndoTileDelta(layer)
        redoStack.clear()
    }

    private fun pushUndoTileDelta(layer: Layer) {
        val snaps = HashMap<Int, IntArray>()
        for (i in layer.content.tiles.indices) {
            layer.content.tiles[i]?.let { snaps[i] = it.pixels.copyOf() }
        }
        undoStack.add(UndoEntry.TileDelta(layer.id, snaps))
        if (undoStack.size > 50) undoStack.removeAt(0)
    }

    private fun pushUndoStructural() {
        undoStack.add(UndoEntry.Structural(
            _layers.map { LayerSnapshot(it.id, it.name, it.content.snapshot(), it.opacity, it.blendMode, it.isVisible, it.isClipToBelow) },
            activeLayerId,
        ))
        if (undoStack.size > 50) undoStack.removeAt(0)
    }

    fun undo(): Boolean = lock.withLock {
        if (undoStack.isEmpty()) return false
        forceEndStrokeIfNeeded()
        val entry = undoStack.removeLast()
        when (entry) {
            is UndoEntry.TileDelta -> {
                val layer = _layers.find { it.id == entry.layerId } ?: return false
                // 現在の状態を redo に保存
                val redoSnaps = HashMap<Int, IntArray>()
                for (i in layer.content.tiles.indices) {
                    layer.content.tiles[i]?.let { redoSnaps[i] = it.pixels.copyOf() }
                }
                redoStack.add(UndoEntry.TileDelta(entry.layerId, redoSnaps))
                // 復元
                for (i in layer.content.tiles.indices) {
                    val snap = entry.snapshots[i]
                    if (snap != null) {
                        val tx = i % layer.content.tilesX
                        val ty = i / layer.content.tilesX
                        val t = layer.content.getOrCreateMutable(tx, ty)
                        System.arraycopy(snap, 0, t.pixels, 0, Tile.LENGTH)
                    } else {
                        layer.content.tiles[i]?.decRef()
                        layer.content.tiles[i] = null
                    }
                }
            }
            is UndoEntry.Structural -> {
                pushRedoStructural()
                _layers.clear()
                for (s in entry.snapshots) {
                    _layers.add(Layer(s.id, s.name, s.surface, s.opacity, s.blendMode, s.isVisible, isClipToBelow = s.isClip))
                }
                activeLayerId = entry.activeId
                nextLayerId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
            }
        }
        dirtyTracker.markFullRebuild(); true
    }

    fun redo(): Boolean = lock.withLock {
        if (redoStack.isEmpty()) return false
        forceEndStrokeIfNeeded()
        val entry = redoStack.removeLast()
        when (entry) {
            is UndoEntry.TileDelta -> {
                val layer = _layers.find { it.id == entry.layerId } ?: return false
                val undoSnaps = HashMap<Int, IntArray>()
                for (i in layer.content.tiles.indices) {
                    layer.content.tiles[i]?.let { undoSnaps[i] = it.pixels.copyOf() }
                }
                undoStack.add(UndoEntry.TileDelta(entry.layerId, undoSnaps))
                for (i in layer.content.tiles.indices) {
                    val snap = entry.snapshots[i]
                    if (snap != null) {
                        val tx = i % layer.content.tilesX
                        val ty = i / layer.content.tilesX
                        val t = layer.content.getOrCreateMutable(tx, ty)
                        System.arraycopy(snap, 0, t.pixels, 0, Tile.LENGTH)
                    } else {
                        layer.content.tiles[i]?.decRef(); layer.content.tiles[i] = null
                    }
                }
            }
            is UndoEntry.Structural -> {
                pushUndoStructural()
                _layers.clear()
                for (s in entry.snapshots) {
                    _layers.add(Layer(s.id, s.name, s.surface, s.opacity, s.blendMode, s.isVisible, isClipToBelow = s.isClip))
                }
                activeLayerId = entry.activeId
                nextLayerId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
            }
        }
        dirtyTracker.markFullRebuild(); true
    }

    private fun pushRedoStructural() {
        redoStack.add(UndoEntry.Structural(
            _layers.map { LayerSnapshot(it.id, it.name, it.content.snapshot(), it.opacity, it.blendMode, it.isVisible, it.isClipToBelow) },
            activeLayerId,
        ))
    }

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val undoStackSize get() = undoStack.size

    // ── メモリ管理 ──────────────────────────────────────────────────

    /** Undo スタックを指定サイズまで縮小 (古いエントリから削除) */
    fun trimUndoStack(maxEntries: Int) = lock.withLock {
        while (undoStack.size > maxEntries) {
            val removed = undoStack.removeAt(0)
            // Structural エントリの TiledSurface 参照を解放
            if (removed is UndoEntry.Structural) {
                for (snap in removed.snapshots) {
                    snap.surface.clear()
                }
            }
        }
    }

    /** Redo スタック全削除 */
    fun clearRedoStack() = lock.withLock {
        for (entry in redoStack) {
            if (entry is UndoEntry.Structural) {
                for (snap in entry.snapshots) { snap.surface.clear() }
            }
        }
        redoStack.clear()
    }

    /** 合成キャッシュをクリア (メモリ解放、次フレームで再構築される) */
    fun clearCompositeCache() {
        for (i in compositeCache.indices) compositeCache[i] = null
        dirtyTracker.markFullRebuild()
    }

    /** 合成済み全ピクセル (エクスポート用) — ロック内で rebuild + 読み取りを完結させる */
    fun getCompositePixels(): IntArray = lock.withLock {
        // エクスポート用に一時的にキャッシュを再構築して読み取る
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            rebuildCompositeTileInner(tx, ty, ty * tilesX + tx)
        }
        val out = IntArray(width * height)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val data = compositeCache[ty * tilesX + tx] ?: continue
            val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
            for (ly in 0 until Tile.SIZE) {
                val py = by + ly; if (py >= height) break
                System.arraycopy(data, ly * Tile.SIZE, out, py * width + bx, minOf(Tile.SIZE, width - bx))
            }
        }
        out
    }
}
