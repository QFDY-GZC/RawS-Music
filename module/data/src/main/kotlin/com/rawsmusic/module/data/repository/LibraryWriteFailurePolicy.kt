package com.rawsmusic.module.data.repository

internal object LibraryWriteFailurePolicy {
    fun check(error: Exception, strict: Boolean) {
        if (strict || error is kotlinx.coroutines.CancellationException) throw error
    }
}
