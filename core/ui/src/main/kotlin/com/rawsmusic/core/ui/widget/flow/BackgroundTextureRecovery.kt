package com.rawsmusic.core.ui.widget.flow

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T : Any> recoverBackgroundTexture(
    pause: suspend (Long) -> Unit = { delay(it) },
    load: suspend () -> T?,
): T? {
    for (waitMs in longArrayOf(0L, 420L, 1_400L)) {
        currentCoroutineContext().ensureActive()
        if (waitMs > 0L) pause(waitMs)
        load()?.let { return it }
    }
    return null
}
