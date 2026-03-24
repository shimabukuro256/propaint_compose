package com.propaint.app.engine

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class DirtyTileTracker {
    private val queue = ConcurrentLinkedQueue<Long>()
    private val _fullRebuildNeeded = AtomicBoolean(true)
    private val _selectionDirty = AtomicBoolean(false)

    fun markDirty(tx: Int, ty: Int) { queue.add(packCoord(tx, ty)) }
    fun markFullRebuild() { _fullRebuildNeeded.set(true) }

    /** 選択マスクの表示更新が必要なことを通知 (コンポジットキャッシュは不変) */
    fun markSelectionDirty() { _selectionDirty.set(true) }

    fun drain(): Set<Long> {
        val r = HashSet<Long>()
        while (true) { r.add(queue.poll() ?: break) }
        return r
    }

    /** アトミックに check-and-clear。2スレッドが同時に true を見ることはない。 */
    fun checkAndClearFullRebuild(): Boolean = _fullRebuildNeeded.compareAndSet(true, false)

    /** 選択マスク dirty のアトミック check-and-clear */
    fun checkAndClearSelectionDirty(): Boolean = _selectionDirty.compareAndSet(true, false)

    companion object {
        fun packCoord(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        fun unpackX(p: Long): Int = (p shr 32).toInt()
        fun unpackY(p: Long): Int = p.toInt()
    }
}
