package com.rawsmusic.core.ui.widget.bitmaps

import android.view.View
import com.rawsmusic.core.common.utils.PlayerSwitchTrace

/** Compatibility API for removed playback-artwork diagnostics. */
internal enum class PlaybackArtworkPerfStage {
    OBSERVER_DISPATCH,
    HOLDER_BIND,
    HOLDER_TOPOLOGY_SYNC,
    HOLDER_PIXEL_SYNC,
    HOLDER_TRANSFORM_SYNC,
    FULL_DECODE,
    BACKGROUND_FLOW_ENDPOINT,
    BACKGROUND_FLOW_PALETTE,
    BACKGROUND_STATIC_PREPARE,
    BACKGROUND_STATIC_NATIVE,
}

internal enum class PlaybackArtworkPerfEvent {
    MANUAL_REQUEST,
    GESTURE_BEGIN,
    GESTURE_END,
    BIND_SONG,
    TARGET_RETARGET,
    PROGRAMMATIC_CHAIN_RESTART,
    PROGRAMMATIC_BOUNDARY,
    TARGET_INSTALL_HOT,
    TARGET_INSTALL_COLD,
    ARTWORK_REQUEST,
    ARTWORK_LOW_ARRIVAL,
    ARTWORK_HIGH_ARRIVAL,
    ARTWORK_TERMINAL,
    TOPOLOGY_NOTIFY,
    PIXEL_NOTIFY,
    HOLDER_TOPOLOGY_SYNC,
    HOLDER_PIXEL_SYNC,
    HOLDER_BIND,
    HOLDER_REPLACEMENT_START,
    HOLDER_REPLACEMENT_END,
    COLD_TARGET_WAIT,
    BACKGROUND_IDENTITY_CHANGE,
    BACKGROUND_SECONDARY_ADMIT,
    BACKGROUND_CURRENT_COMMIT,
    PLAYER_CONTAINER_COMPOSE_COMMIT,
    PLAYER_MAIN_COMPOSE_COMMIT,
    PLAYER_BODY_COMPOSE_COMMIT,
    PLAYER_ART_CARD_COMPOSE_COMMIT,
    PLAYER_TIMELINE_LANE_COMPOSE_COMMIT,
    PLAYER_BACKDROP_COMPOSE_COMMIT,
    ARTWORK_PAGER_COMPOSE_COMMIT,
    ARTWORK_METADATA_COMPOSE_COMMIT,
}

internal object PlaybackArtworkPerfTrace {
    fun attach(@Suppress("UNUSED_PARAMETER") view: View) = Unit

    fun detach(@Suppress("UNUSED_PARAMETER") view: View) = Unit

    fun isActive(): Boolean = PlayerSwitchTrace.isActive()

    fun overlapSummary(): String = if (isActive()) "player_switch_trace_active" else "idle"

    fun begin(
        kind: String,
        detail: String = "",
    ) {
        PlayerSwitchTrace.begin(kind, detail)
    }

    fun stop(reason: String) {
        PlayerSwitchTrace.finishLater(reason)
    }

    fun discard() {
        PlayerSwitchTrace.finishLater("discard")
    }

    fun setState(summary: String) {
        PlayerSwitchTrace.mark("art_state", summary)
    }

    fun mark(
        name: String,
        detail: String = "",
    ) {
        PlayerSwitchTrace.mark(name, detail)
    }

    fun ratioFrame(value: Float) {
        PlayerSwitchTrace.frame("art_ratio", value)
    }

    fun count(
        event: PlaybackArtworkPerfEvent,
        amount: Long = 1L,
    ) {
        PlayerSwitchTrace.count(event.name, amount)
    }

    fun recordDuration(
        stage: PlaybackArtworkPerfStage,
        durationNs: Long,
    ) {
        PlayerSwitchTrace.duration(stage.name, durationNs)
    }

    inline fun <T> measure(
        stage: PlaybackArtworkPerfStage,
        block: () -> T,
    ): T {
        if (!isActive()) return block()
        val startedNs = System.nanoTime()
        return try {
            block()
        } finally {
            recordDuration(stage, System.nanoTime() - startedNs)
        }
    }

    fun artworkRequest(key: String) {
        PlayerSwitchTrace.mark("artwork_request", "key=${keyTag(key)}")
    }

    fun artworkArrived(
        key: String,
        quality: Int,
        lane: String,
    ) {
        PlayerSwitchTrace.mark("artwork_arrived", "key=${keyTag(key)} q=$quality lane=$lane")
    }

    fun keyTag(key: String): String = if (key.isBlank()) "-" else Integer.toHexString(key.hashCode())

    fun backgroundIdentity(
        style: String,
        currentKey: String?,
        targetKey: String?,
        moving: Boolean,
    ) {
        PlayerSwitchTrace.count(PlaybackArtworkPerfEvent.BACKGROUND_IDENTITY_CHANGE.name)
        PlayerSwitchTrace.mark(
            "background_identity",
            "style=$style current=${keyTag(currentKey.orEmpty())} target=${keyTag(targetKey.orEmpty())} moving=$moving",
        )
    }

    fun backgroundSecondaryAdmit(
        lane: String,
        key: String?,
        progress: Float,
        detail: String = "",
    ) {
        PlayerSwitchTrace.count(PlaybackArtworkPerfEvent.BACKGROUND_SECONDARY_ADMIT.name)
        PlayerSwitchTrace.mark(
            "background_secondary_admit",
            "lane=$lane key=${keyTag(key.orEmpty())} p=$progress $detail".trim(),
        )
    }

    fun backgroundCurrentCommit(
        lane: String,
        key: String?,
        detail: String = "",
    ) {
        PlayerSwitchTrace.count(PlaybackArtworkPerfEvent.BACKGROUND_CURRENT_COMMIT.name)
        PlayerSwitchTrace.mark(
            "background_current_commit",
            "lane=$lane key=${keyTag(key.orEmpty())} $detail".trim(),
        )
    }
}
