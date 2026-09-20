package com.rawsmusic.separation

import android.content.Context
import android.util.Log

data class AiLyricForcedAlignmentProviderSelection(
    val provider: AiLyricForcedAlignmentProvider,
    val contract: AiLyricForcedAlignmentContract,
)

/** Resolves only a selected, fully installed, vocabulary-backed external CTC model. */
object AiLyricForcedAlignmentProviderFactory {
    fun resolve(
        context: Context,
        lyrics: com.rawsmusic.core.common.model.LyricData? = null,
    ): AiLyricForcedAlignmentProviderSelection? {
        val store = AiSeparationPluginStore.get(context)
        val selected = store.selectedLyricAlignmentModel()
        val detectedLanguage = lyrics?.let(::detectLanguage).orEmpty()
        val candidates = store.state.value.installedLyricAlignmentModels
            .sortedWith(
                compareByDescending<AiLyricAlignmentInstalledModel> {
                    languageMatches(it.catalog.contract.language, detectedLanguage)
                }.thenByDescending {
                    selected?.catalog?.id == it.catalog.id &&
                        selected.catalog.version == it.catalog.version
                }.thenBy { it.catalog.estimatedMemoryMb },
            )
        val installed = if (detectedLanguage.isBlank()) {
            candidates.firstOrNull { candidate ->
                selected?.catalog?.id == candidate.catalog.id &&
                    selected.catalog.version == candidate.catalog.version
            } ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull {
                languageMatches(it.catalog.contract.language, detectedLanguage)
            }
        }
            ?: return null
        val entry = installed.catalog
        val vocabulary = AiLyricCtcVocabularyLoader.load(installed.vocabularyFile)
            .onFailure { error ->
                Log.w(TAG, "AI_LYRIC_CTC vocabulary rejected id=${entry.id}", error)
            }
            .getOrNull()
            ?: return null
        val outputTokenCount = vocabulary.tokenIds.values.maxOrNull()?.plus(1) ?: return null
        if (outputTokenCount <= 1) {
            Log.w(TAG, "AI_LYRIC_CTC vocabulary has too few tokens id=${entry.id}")
            return null
        }
        Log.i(
            TAG,
            "AI_LYRIC_CTC model_select detected=$detectedLanguage " +
                "model=${entry.id}:${entry.version} language=${entry.contract.language}",
        )
        return AiLyricForcedAlignmentProviderSelection(
            provider = CtcLyricForcedAlignmentProvider(
                vocabulary = vocabulary,
                posteriorRunner = AiLyricOnnxPosteriorRunner(
                    modelFile = installed.modelFile,
                    outputTokenCount = outputTokenCount,
                ),
            ),
            contract = entry.contract,
        )
    }

    private fun detectLanguage(lyrics: com.rawsmusic.core.common.model.LyricData): String {
        val text = lyrics.lines.joinToString(separator = "") { it.text }
        var kana = 0
        var han = 0
        var hangul = 0
        var latin = 0
        text.forEach { character ->
            when (character.code) {
                in 0x3040..0x30ff, in 0x31f0..0x31ff -> kana++
                in 0x3400..0x4dbf, in 0x4e00..0x9fff -> han++
                in 0xac00..0xd7af, in 0x1100..0x11ff -> hangul++
                else -> if (character in 'A'..'Z' || character in 'a'..'z') latin++
            }
        }
        return when {
            kana > 0 -> "ja"
            hangul > 0 -> "ko"
            han > 0 -> "zh"
            latin > 0 -> "en"
            else -> ""
        }
    }

    private fun languageMatches(modelLanguage: String, detectedLanguage: String): Boolean {
        if (detectedLanguage.isBlank()) return true
        val normalized = modelLanguage.trim().lowercase().substringBefore('-')
        if (normalized.isBlank() || normalized in setOf("multi", "multilingual", "universal")) {
            return true
        }
        val alias = when (normalized) {
            "jp", "jpn", "japanese" -> "ja"
            "cn", "zho", "chi", "chinese" -> "zh"
            "kr", "kor", "korean" -> "ko"
            "eng", "english" -> "en"
            else -> normalized
        }
        return alias == detectedLanguage
    }

    private const val TAG = "AiLyricCtc"
}
