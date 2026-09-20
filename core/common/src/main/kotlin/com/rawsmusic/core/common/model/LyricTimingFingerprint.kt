package com.rawsmusic.core.common.model

import java.security.MessageDigest

/**
 * Creates a deterministic identity for the exact lyric timeline presented to the user.
 *
 * The fingerprint includes lyric content and timing metadata, so a persisted timing decision
 * cannot be applied to a later parser result that only happens to belong to the same song.
 */
fun LyricData.stableTimingFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val canonical = buildString {
        appendField("offset", offset)
        appendField("lineCount", lines.size)
        lines.forEachIndexed { index, line ->
            appendField("line[$index].timeStamp", line.timeStamp)
            appendField("line[$index].endTime", line.endTime)
            appendField("line[$index].text", line.text)
            appendField("line[$index].translation", line.translation)
            appendField("line[$index].romanization", line.romanization)
            appendField("line[$index].agent", line.agent.orEmpty())
            appendField("line[$index].agentName", line.agentName.orEmpty())
            appendField("line[$index].backgroundText", line.backgroundText.orEmpty())
            appendField("line[$index].backgroundTranslation", line.backgroundTranslation.orEmpty())
            appendField("line[$index].backgroundStartTime", line.backgroundStartTime ?: -1L)
            appendField("line[$index].backgroundEndTime", line.backgroundEndTime ?: -1L)
            appendField("line[$index].isTtml", line.isTtml)
            appendWords("line[$index].words", line.words)
            appendWords("line[$index].pronunciationWords", line.pronunciationWords)
            appendWords("line[$index].backgroundWords", line.backgroundWords)
        }
    }
    return digest.digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun StringBuilder.appendWords(prefix: String, words: List<LyricWord>) {
    appendField("$prefix.count", words.size)
    words.forEachIndexed { index, word ->
        appendField("$prefix[$index].text", word.text)
        appendField("$prefix[$index].begin", word.begin)
        appendField("$prefix[$index].end", word.end)
        appendField("$prefix[$index].duration", word.duration)
    }
}

private fun StringBuilder.appendField(name: String, value: Any?) {
    val text = value?.toString().orEmpty()
    append(name.length).append(':').append(name)
        .append(text.length).append(':').append(text).append(';')
}
