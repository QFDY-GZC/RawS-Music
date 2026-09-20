package com.rawsmusic.core.ui.widget.player

enum class PlayerAiPerformanceMode {
    ORIGINAL,
    INSTRUMENT_PERFORMANCE,
    VOCAL_ENSEMBLE,
}

data class PlayerAiInstrumentOption(
    val key: String,
    val label: String,
    val iconKey: String,
    val selected: Boolean = false,
)

data class PlayerAiPerformanceUiState(
    val songKey: String = "",
    val supported: Boolean = false,
    val mode: PlayerAiPerformanceMode = PlayerAiPerformanceMode.ORIGINAL,
    val busy: Boolean = false,
    val livePlayback: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val instrumentPerformanceReady: Boolean = false,
    val vocalEnsembleReady: Boolean = false,
    val generationAvailable: Boolean = false,
    val melodyModelReady: Boolean = false,
    val instrumentPackReady: Boolean = false,
    val prerequisite: String = "",
    val instruments: List<PlayerAiInstrumentOption> = emptyList(),
    val selectedInstrumentKey: String = "",
) {
    val normalizedProgress: Float get() = progress.coerceIn(0f, 1f)
    val enabled: Boolean get() = mode != PlayerAiPerformanceMode.ORIGINAL || livePlayback || busy
    val toggleEnabled: Boolean get() = supported || enabled
}
