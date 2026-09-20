package com.rawsmusic.module.player

/** One-line decision record for every manual overlap attempt. */
internal data class ManualCrossfadeDiagnostic(
    val result: String,
    val reason: String,
    val targetPath: String,
    val generation: Int,
    val waitedMs: Long,
    val prepareScheduled: Boolean,
    val prepareState: String,
    val prepareAttempt: Int,
    val prepareDetail: String,
    val currentFormat: String,
    val pendingFormat: String,
    val pendingQueue: String,
    val readyFrames: Long,
    val minimumReadyFrames: Long,
    val nativeReady: Boolean,
) {
    fun message(): String =
        "MANUAL_CROSSFADE_DECISION result=$result reason=$reason " +
            "target=${targetPath.substringAfterLast('/')} gen=$generation waitedMs=$waitedMs " +
            "scheduled=$prepareScheduled prepareState=$prepareState attempt=$prepareAttempt " +
            "prepareDetail=${prepareDetail.replace(' ', '_').take(180)} " +
            "current=$currentFormat pending=$pendingFormat queue=$pendingQueue " +
            "readyFrames=$readyFrames minimum=$minimumReadyFrames nativeReady=$nativeReady"
}

internal data class PreparedCrossfadeGate(
    val allowed: Boolean,
    val reason: String,
    val nativeReady: Boolean,
)
