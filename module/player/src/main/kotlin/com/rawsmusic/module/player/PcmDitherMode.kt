package com.rawsmusic.module.player

/** PCM quantization dither modes. IDs are persisted in AppPreferences. */
enum class PcmDitherMode(val id: Int) {
    OFF(0),
    RPDF(1),
    TPDF(2),
    TPDF_HIGH_PASS(3),
    GAUSSIAN(4),
    F_WEIGHTED(5),
    MODIFIED_E_WEIGHTED(6),
    SHIBATA(7),
    LOW_SHIBATA(8),
    HIGH_SHIBATA(9);

    companion object {
        fun fromId(id: Int): PcmDitherMode = entries.firstOrNull { it.id == id } ?: MODIFIED_E_WEIGHTED
    }
}
