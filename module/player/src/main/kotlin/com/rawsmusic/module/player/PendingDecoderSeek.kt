package com.rawsmusic.module.player

/**
 * Atomic pairing of a decoder seek target and its ownership serial.
 *
 * The old implementation stored targetMs and serial in separate volatile fields,
 * so the decoder thread could consume target A while observing serial B. This
 * holder publishes and consumes them as one immutable request.
 */
internal class PendingDecoderSeek {
    data class Request(
        val serial: Long,
        val targetMs: Long,
    )

    private val lock = Any()
    private var request: Request? = null

    fun publish(serial: Long, targetMs: Long): Request? {
        if (serial <= 0L) return null
        val next = Request(serial, targetMs.coerceAtLeast(0L))
        synchronized(lock) {
            request = next
        }
        return next
    }

    fun consumeLatest(): Request? = synchronized(lock) {
        request.also { request = null }
    }

    fun cancel(serial: Long) {
        if (serial <= 0L) return
        synchronized(lock) {
            if (request?.serial == serial) request = null
        }
    }

    fun clear() {
        synchronized(lock) { request = null }
    }

    fun peek(): Request? = synchronized(lock) { request }
}
