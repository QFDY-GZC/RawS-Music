package com.rawsmusic.module.data.repository

/**
 * Describes the one-time library handoff used while the process is starting.
 *
 * The snapshot is deliberately not a visible state. It is only a warm fallback
 * and a source for preloading work; the first UI frame is released after the
 * Room list and its derived indexes are published together.
 */
enum class LibraryStartupPhase {
    IDLE,
    LOADING,
    READY,
    EMPTY,
    FAILED,
}

data class LibraryStartupState(
    val phase: LibraryStartupPhase = LibraryStartupPhase.IDLE,
    val generation: Long = 0L,
    val startedAtElapsedMs: Long = 0L,
    val songs: Int = 0,
    val fromSnapshotFallback: Boolean = false,
) {
    val isReady: Boolean
        get() = phase == LibraryStartupPhase.READY ||
            phase == LibraryStartupPhase.EMPTY ||
            phase == LibraryStartupPhase.FAILED
}
