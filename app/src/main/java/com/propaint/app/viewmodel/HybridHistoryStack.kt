package com.propaint.app.viewmodel

import com.propaint.app.model.CanvasAction

/**
 * undo/redo 用のハイブリッドスタック。
 *
 * 直近 [maxMemory] 件はメモリに保持し、それを超えた古いエントリは [cache] へ
 * バイナリファイルとして書き出す。合計 [maxTotal] 件を超えた場合は最古エントリを破棄する。
 *
 * スタック構造（LIFO）:
 *   [ディスク: 古い順] + [メモリ: 古い→新しい]
 *   push/pop は常にメモリ末尾を操作する。
 */
internal class HybridHistoryStack(
    private val cache: HistoryDiskCache,
    private val maxMemory: Int = 10,
    private val maxTotal: Int = 50,
) {
    /** ディスクに保存済みエントリのインデックス（先頭=最古、末尾=最新ディスクエントリ）*/
    private val diskDeque = ArrayDeque<Int>()
    /** メモリ上のエントリ（先頭=最古、末尾=スタックトップ）*/
    private val memDeque = ArrayDeque<CanvasAction>()
    /** ディスクファイルのインデックス生成カウンタ */
    private var nextDiskIndex = 0

    val size: Int get() = diskDeque.size + memDeque.size
    val isEmpty: Boolean get() = size == 0
    val isNotEmpty: Boolean get() = size > 0

    /** アクションをスタックトップへ積む。上限を超えた場合は最古エントリを破棄する。 */
    fun push(action: CanvasAction) {
        // 合計上限超過 → 最古エントリを捨てる
        if (size >= maxTotal) evictOldest()

        memDeque.addLast(action)

        // メモリ上限超過 → 最古メモリエントリをディスクへ追い出す
        while (memDeque.size > maxMemory) {
            val idx = nextDiskIndex++
            cache.write(idx, memDeque.removeFirst())
            diskDeque.addLast(idx)
        }
    }

    /** スタックトップからアクションを取り出す。ディスクエントリは読み込み後に削除する。 */
    fun pop(): CanvasAction? {
        if (memDeque.isNotEmpty()) return memDeque.removeLast()
        if (diskDeque.isNotEmpty()) {
            val idx = diskDeque.removeLast()
            val action = cache.read(idx)
            cache.delete(idx)
            return action
        }
        return null
    }

    /** スタックを空にし、ディスク上のファイルも削除する。 */
    fun clear() {
        memDeque.clear()
        diskDeque.forEach { cache.delete(it) }
        diskDeque.clear()
    }

    private fun evictOldest() {
        if (diskDeque.isNotEmpty()) {
            cache.delete(diskDeque.removeFirst())
        } else {
            memDeque.removeFirstOrNull()
        }
    }
}
