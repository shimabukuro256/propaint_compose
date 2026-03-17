package com.propaint.app.viewmodel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.propaint.app.model.*
import java.io.*

/**
 * CanvasAction をバイナリ形式でローカルファイルへ保存/読み込みするキャッシュ。
 * ファイル名は `h_{index}.bin` 形式。
 */
internal class HistoryDiskCache(private val cacheDir: File) {

    init { cacheDir.mkdirs() }

    private fun fileFor(index: Int) = File(cacheDir, "h_$index.bin")

    fun write(index: Int, action: CanvasAction) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(fileFor(index)))).use {
            writeAction(it, action)
        }
    }

    fun read(index: Int): CanvasAction? {
        val file = fileFor(index)
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { readAction(it) }
        } catch (_: Exception) { null }
    }

    fun delete(index: Int) { fileFor(index).delete() }

    fun clearAll() { cacheDir.listFiles()?.forEach { it.delete() } }

    // ── Action ──────────────────────────────────────────────────────────────

    private fun writeAction(out: DataOutputStream, action: CanvasAction) {
        when (action) {
            is CanvasAction.AddStroke -> {
                out.writeInt(0)
                out.writeUTF(action.layerId)
                writeStroke(out, action.stroke)
            }
            is CanvasAction.AddLayer -> {
                out.writeInt(1)
                writeLayer(out, action.layer)
                out.writeInt(action.atIndex)
            }
            is CanvasAction.RemoveLayer -> {
                out.writeInt(2)
                writeLayer(out, action.layer)
                out.writeInt(action.index)
            }
            is CanvasAction.ClearLayer -> {
                out.writeInt(3)
                out.writeUTF(action.layerId)
                out.writeInt(action.previousStrokes.size)
                action.previousStrokes.forEach { writeStroke(out, it) }
            }
            is CanvasAction.MergeDown -> {
                out.writeInt(4)
                writeLayer(out, action.upper)
                writeLayer(out, action.lower)
                out.writeInt(action.upperIndex)
            }
            is CanvasAction.DuplicateLayer -> {
                out.writeInt(5)
                writeLayer(out, action.newLayer)
                out.writeInt(action.atIndex)
            }
        }
    }

    private fun readAction(inp: DataInputStream): CanvasAction {
        return when (inp.readInt()) {
            0 -> {
                val layerId = inp.readUTF()
                val stroke  = readStroke(inp)
                CanvasAction.AddStroke(stroke, layerId)
            }
            1 -> {
                val layer   = readLayer(inp)
                val atIndex = inp.readInt()
                CanvasAction.AddLayer(layer, atIndex)
            }
            2 -> {
                val layer = readLayer(inp)
                val idx   = inp.readInt()
                CanvasAction.RemoveLayer(layer, idx)
            }
            3 -> {
                val layerId = inp.readUTF()
                val strokes = List(inp.readInt()) { readStroke(inp) }
                CanvasAction.ClearLayer(layerId, strokes)
            }
            4 -> {
                val upper      = readLayer(inp)
                val lower      = readLayer(inp)
                val upperIndex = inp.readInt()
                CanvasAction.MergeDown(upper, lower, upperIndex)
            }
            5 -> {
                val newLayer = readLayer(inp)
                val atIndex  = inp.readInt()
                CanvasAction.DuplicateLayer(newLayer, atIndex)
            }
            else -> throw IOException("Unknown action type")
        }
    }

    // ── Stroke ──────────────────────────────────────────────────────────────

    private fun writeStroke(out: DataOutputStream, stroke: Stroke) {
        out.writeUTF(stroke.layerId)
        writeColor(out, stroke.color)
        writeBrushSettings(out, stroke.brush)
        out.writeInt(stroke.points.size)
        stroke.points.forEach { writeStrokePoint(out, it) }
    }

    private fun readStroke(inp: DataInputStream): Stroke {
        val layerId = inp.readUTF()
        val color   = readColor(inp)
        val brush   = readBrushSettings(inp)
        val points  = List(inp.readInt()) { readStrokePoint(inp) }
        return Stroke(points, brush, color, layerId)
    }

    private fun writeStrokePoint(out: DataOutputStream, sp: StrokePoint) {
        out.writeFloat(sp.position.x)
        out.writeFloat(sp.position.y)
        out.writeFloat(sp.pressure)
        out.writeBoolean(sp.color != null)
        sp.color?.let { writeColor(out, it) }
        out.writeLong(sp.timestamp)
    }

    private fun readStrokePoint(inp: DataInputStream): StrokePoint {
        val x        = inp.readFloat()
        val y        = inp.readFloat()
        val pressure = inp.readFloat()
        val color    = if (inp.readBoolean()) readColor(inp) else null
        val timestamp = inp.readLong()
        return StrokePoint(Offset(x, y), pressure, color, timestamp)
    }

    // ── BrushSettings ────────────────────────────────────────────────────────

    private fun writeBrushSettings(out: DataOutputStream, bs: BrushSettings) {
        out.writeInt(bs.type.ordinal)
        out.writeFloat(bs.size)
        out.writeFloat(bs.opacity)
        out.writeFloat(bs.density)
        out.writeFloat(bs.spacing)
        out.writeFloat(bs.hardness)
        out.writeFloat(bs.stabilizer)
        out.writeFloat(bs.blurStrength)
        out.writeFloat(bs.colorStretch)
        out.writeFloat(bs.blurPressureThreshold)
        out.writeBoolean(bs.pressureSizeEnabled)
        out.writeBoolean(bs.pressureOpacityEnabled)
        out.writeFloat(bs.minSizeRatio)
        out.writeInt(bs.pressureSizeIntensity)
        out.writeInt(bs.pressureOpacityIntensity)
        out.writeBoolean(bs.pressureMixEnabled)
        out.writeInt(bs.pressureMixIntensity)
    }

    private fun readBrushSettings(inp: DataInputStream): BrushSettings {
        val typeOrdinal = inp.readInt()
        // BrushType の ordinal が変化した場合はデフォルトブラシにフォールバック
        val type = BrushType.entries.getOrNull(typeOrdinal) ?: BrushType.Pencil
        val size              = inp.readFloat()
        val opacity           = inp.readFloat()
        val density           = inp.readFloat()
        val spacing           = inp.readFloat()
        val hardness          = try { inp.readFloat() } catch (_: Exception) { type.defaultHardness }
        val stabilizer        = try { inp.readFloat() } catch (_: Exception) { type.defaultStabilizer }
        val blurStrength      = try { inp.readFloat() } catch (_: Exception) { 0.5f }
        val colorStretch      = try { inp.readFloat() } catch (_: Exception) { 0.5f }
        val blurPressureThreshold = try { inp.readFloat() } catch (_: Exception) { 0f }
        val presSize          = try { inp.readBoolean() } catch (_: Exception) { true }
        val presOpacity       = try { inp.readBoolean() } catch (_: Exception) { false }
        val minSizeRatio      = try { inp.readFloat() } catch (_: Exception) { 0.2f }
        val pressureSizeIntensity    = try { inp.readInt() } catch (_: Exception) { 100 }
        val pressureOpacityIntensity = try { inp.readInt() } catch (_: Exception) { 100 }
        val pressureMixEnabled       = try { inp.readBoolean() } catch (_: Exception) { false }
        val pressureMixIntensity     = try { inp.readInt() } catch (_: Exception) { 100 }
        return BrushSettings(
            type                     = type,
            size                     = size,
            opacity                  = opacity,
            density                  = density,
            spacing                  = spacing,
            hardness                 = hardness,
            stabilizer               = stabilizer,
            blurStrength             = blurStrength,
            colorStretch             = colorStretch,
            blurPressureThreshold    = blurPressureThreshold,
            pressureSizeEnabled      = presSize,
            pressureOpacityEnabled   = presOpacity,
            minSizeRatio             = minSizeRatio,
            pressureSizeIntensity    = pressureSizeIntensity,
            pressureOpacityIntensity = pressureOpacityIntensity,
            pressureMixEnabled       = pressureMixEnabled,
            pressureMixIntensity     = pressureMixIntensity,
        )
    }

    // ── PaintLayer ───────────────────────────────────────────────────────────

    private fun writeLayer(out: DataOutputStream, layer: PaintLayer) {
        out.writeUTF(layer.id)
        out.writeUTF(layer.name)
        out.writeBoolean(layer.isVisible)
        out.writeBoolean(layer.isLocked)
        out.writeFloat(layer.opacity)
        out.writeInt(layer.blendMode.ordinal)
        out.writeInt(layer.strokes.size)
        layer.strokes.forEach { writeStroke(out, it) }
        out.writeBoolean(layer.isClippingMask)
    }

    private fun readLayer(inp: DataInputStream): PaintLayer {
        val id        = inp.readUTF()
        val name      = inp.readUTF()
        val isVisible = inp.readBoolean()
        val isLocked  = inp.readBoolean()
        val opacity   = inp.readFloat()
        val blendMode = LayerBlendMode.entries.getOrElse(inp.readInt()) { LayerBlendMode.Normal }
        val strokes   = List(inp.readInt()) { readStroke(inp) }
        val isClippingMask = try { inp.readBoolean() } catch (_: Exception) { false }
        return PaintLayer(id, name, isVisible, isLocked, opacity, blendMode, strokes, isClippingMask)
    }

    // ── Color ────────────────────────────────────────────────────────────────

    private fun writeColor(out: DataOutputStream, color: Color) {
        out.writeLong(color.value.toLong())
    }

    private fun readColor(inp: DataInputStream): Color = Color(inp.readLong().toULong())
}
