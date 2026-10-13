package com.rawsmusic.core.ui.widget.player

internal enum class ImmersiveVerticalAction { None, Next, Previous, Dismiss }

internal fun immersiveVerticalChildOwnsMotion(consumed: Boolean, dx: Float, dy: Float): Boolean =
    consumed && (dx != 0f || dy != 0f)

internal fun immersiveVerticalAction(enabled: Boolean, playerPage: Boolean, blocked: Boolean,
    startedOnArtwork: Boolean, deltaY: Float): ImmersiveVerticalAction = when {
    !enabled || !playerPage || blocked || !deltaY.isFinite() || deltaY == 0f -> ImmersiveVerticalAction.None
    deltaY < 0f -> ImmersiveVerticalAction.Next
    startedOnArtwork -> ImmersiveVerticalAction.Dismiss
    else -> ImmersiveVerticalAction.Previous
}

internal fun immersiveVerticalSignedProgress(deltaY: Float, height: Float): Float {
    if (!deltaY.isFinite() || !height.isFinite() || height <= 0f) return 0f
    return (deltaY / height).coerceIn(-1f, 1f)
}
