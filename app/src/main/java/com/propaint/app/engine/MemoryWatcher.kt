package com.propaint.app.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * メモリ使用量を監視し、圧迫時に自動的にキャッシュを削減する。
 *
 * 方針:
 *  - 使用率 > 70%: Undo/Redo スタックを段階的に縮小
 *  - 使用率 > 85%: 強制 GC + composite cache クリア + Undo 最小化
 *  - ストローク終了ごとにチェック
 */
object MemoryWatcher {
    private const val TAG = "MemoryWatcher"

    private const val TRIM_THRESHOLD = 0.70    // 70% → 段階的 trim
    private const val CRITICAL_THRESHOLD = 0.85 // 85% → 強制縮小

    data class MemoryState(
        val usedMB: Long,
        val maxMB: Long,
        val usageRatio: Double,
    )

    fun getMemoryState(): MemoryState {
        val rt = Runtime.getRuntime()
        val maxMem = rt.maxMemory()
        val totalMem = rt.totalMemory()
        val freeMem = rt.freeMemory()
        val usedMem = totalMem - freeMem
        return MemoryState(
            usedMB = usedMem / (1024 * 1024),
            maxMB = maxMem / (1024 * 1024),
            usageRatio = usedMem.toDouble() / maxMem,
        )
    }

    /**
     * メモリ状態を確認し、必要に応じて CanvasDocument のキャッシュを削減する。
     * @return true = メモリ圧迫により削減を実行した
     */
    fun checkAndTrim(doc: CanvasDocument): Boolean {
        val state = getMemoryState()

        if (state.usageRatio < TRIM_THRESHOLD) return false

        if (state.usageRatio >= CRITICAL_THRESHOLD) {
            // 危機的: undo を最小限に、redo を全削除
            Log.w(TAG, "CRITICAL memory: ${state.usedMB}MB / ${state.maxMB}MB (${(state.usageRatio * 100).toInt()}%)")
            doc.trimUndoStack(5)
            doc.clearRedoStack()
            doc.clearCompositeCache()
            System.gc()
            return true
        }

        // 段階的: undo スタックを半分に
        Log.i(TAG, "High memory: ${state.usedMB}MB / ${state.maxMB}MB (${(state.usageRatio * 100).toInt()}%)")
        val currentSize = doc.undoStackSize
        if (currentSize > 10) {
            doc.trimUndoStack(currentSize / 2)
        }
        doc.clearRedoStack()
        return true
    }

    /**
     * デバイスのメモリクラスを取得 (MB)。
     * 大きなキャンバスを許可するかどうかの判断に使用。
     */
    fun getMemoryClassMB(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.memoryClass
    }

    /** low-memory 判定 (アプリの RAM 上限が 256MB 以下) */
    fun isLowMemoryDevice(context: Context): Boolean = getMemoryClassMB(context) <= 256
}
