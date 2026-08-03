package com.rawsmusic.module.player

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Sticky USB status notices delivered by the foreground Activity.
 *
 * USB permission sheets can temporarily stop MainActivity and several OEM builds suppress
 * Toasts created from a service/application Context during that window. Keep the latest notice
 * pending until a resumed Activity displays and acknowledges it.
 */
object UsbStatusNoticeBus {
    data class Notice(
        val id: Long,
        val message: String,
    )

    private val nextId = AtomicLong(0L)
    private val pending = AtomicReference<Notice?>(null)
    private val listener = AtomicReference<((Notice) -> Unit)?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun post(message: String) {
        if (message.isBlank()) return
        pending.set(Notice(nextId.incrementAndGet(), message))
        dispatchPending()
    }

    fun attach(callback: (Notice) -> Unit) {
        listener.set(callback)
        dispatchPending()
    }

    fun detach(callback: (Notice) -> Unit) {
        listener.compareAndSet(callback, null)
    }

    fun acknowledge(id: Long) {
        while (true) {
            val current = pending.get() ?: return
            if (current.id != id) return
            if (pending.compareAndSet(current, null)) return
        }
    }

    private fun dispatchPending() {
        mainHandler.post {
            val callback = listener.get() ?: return@post
            val notice = pending.get() ?: return@post
            callback(notice)
        }
    }
}
