package com.rawsmusic.module.player

internal object TransitionLyricContent {
    private val credits = Regex(
        "^\\s*(?:作词|作曲|词曲|编曲|制作人?|演唱|歌手|原唱|混音|录音|母带|和声|监制|出品|发行|策划|" +
            "lyrics?(?:\\s+by)?|music(?:\\s+by)?|composer|arrang(?:er|ement)|producer|vocal(?:s)?|" +
            "作詞|作曲|編曲|歌唱)\\s*[:：－-]", RegexOption.IGNORE_CASE)

    fun isPerformed(text: String): Boolean = text.isNotBlank() && !credits.containsMatchIn(text)

    private val endingWord = Regex("\\bend\\b", RegexOption.IGNORE_CASE)
    fun isEnding(text: String): Boolean = endingWord.containsMatchIn(text) ||
        listOf("结束", "完结", "終わり", "結束", "完結").any(text::contains)
}
