package com.rawsmusic.core.ui.widget.bitmaps

/** Optional disk thumbnails must not retain an unbounded tail of decoded bitmaps. */
internal class BitmapDiskWriteAdmission(
    private val maxTasks: Int = 8,
    private val maxBytes: Long = 8L * 1024 * 1024,
) {
    private val pending = HashMap<String, Long>()
    private var bytes = 0L

    @Synchronized fun tryAcquire(key: String, byteCount: Long): Boolean {
        if (byteCount <= 0 || byteCount > maxBytes || pending.containsKey(key) ||
            pending.size >= maxTasks || bytes > maxBytes - byteCount) return false
        pending[key] = byteCount
        bytes += byteCount
        return true
    }

    @Synchronized fun release(key: String) {
        bytes -= pending.remove(key) ?: 0L
    }
}
