package com.rawsmusic.ai.melody

/**
 * Stable per-instrument MIDI identity.
 *
 * Melody analysis is shared by every timbre. The profile only changes the MIDI cache identity and
 * the SMF playback metadata, so switching instruments never repeats RMVPE analysis.
 */
internal data class AiInstrumentMidiProfile(
    val id: String,
    val aliases: Set<String>,
    val program: Int?,
    val channel: Int = 0,
    val pitchBendEnabled: Boolean = true,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,47}")))
        require(program == null || program in 0..127)
        require(channel in 0..15)
    }
}

internal object AiInstrumentMidiProfiles {
    val piano = profile("piano", 0, "piano", "grand_piano", "acoustic_piano")

    private val known = listOf(
        piano,
        profile("koto", 107, "koto", "guzheng", "zheng"),
        profile("cucurbit_flute", 79, "cucurbit_flute", "hulusi"),
        profile("dizi", 73, "dizi", "zigzag_flute", "bamboo_flute", "qudi"),
        profile("music_box", 10, "music_box", "musicbox"),
        profile("suona", 111, "suona", "sona", "shanai"),
        profile("handpan", 114, "handpan", "hang", "steel_drum", "steel_drums"),
        profile("electric_guitar", 27, "electric_guitar", "eguitar"),
        percussion("drums", "drum", "drums", "drum_kit", "kit"),
        profile("kazoo", 68, "kazoo"),
        profile("healing", 89, "healing", "healing_pad", "ambient_pad"),
        profile("serinette", 20, "serinette", "bird_organ", "reed_organ"),
        percussion("triangle", "triangle"),
        percussion("tambourine", "tambourine"),
        profile("cello", 42, "cello"),
        profile("saxophone", 65, "saxophone", "sax", "alto_sax"),
        profile("trumpet", 56, "trumpet"),
        profile("guitar", 24, "guitar", "acoustic_guitar"),
    )

    fun resolve(instrument: String): AiInstrumentMidiProfile {
        val normalized = normalize(instrument)
        known.firstOrNull { normalized in it.aliases }?.let { return it }
        val fallbackId = normalized
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "instrument" }
        return AiInstrumentMidiProfile(
            id = "custom_$fallbackId",
            aliases = setOf(normalized),
            program = 0,
        )
    }

    private fun profile(id: String, program: Int, vararg aliases: String) = AiInstrumentMidiProfile(
        id = id,
        aliases = aliases.mapTo(linkedSetOf(), ::normalize) + id,
        program = program,
    )

    private fun percussion(id: String, vararg aliases: String) = AiInstrumentMidiProfile(
        id = id,
        aliases = aliases.mapTo(linkedSetOf(), ::normalize) + id,
        program = null,
        channel = 9,
        pitchBendEnabled = false,
    )

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace('-', '_')
        .replace(' ', '_')
}
