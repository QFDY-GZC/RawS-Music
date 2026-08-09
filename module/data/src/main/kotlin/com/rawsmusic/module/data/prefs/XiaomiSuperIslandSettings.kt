package com.rawsmusic.module.data.prefs

import org.json.JSONObject

/**
 * Xiaomi Super Island lyric presentation settings.
 *
 * Keep this model in the data module so the player service and the settings UI use the same
 * bounds and defaults. The encoded form is deliberately small because it is stored in MMKV.
 */
data class XiaomiSuperIslandSettings(
    val lyricTextMode: Int = TEXT_ORIGINAL,
    val lyricMode: Int = LYRIC_MODE_STANDARD,
    val fullLyricShowLeftCover: Boolean = true,
    val scrollingEnabled: Boolean = true,
    val rightTextChars: Int = 7,
    val leftWithCoverTextChars: Int = 6,
    val leftWithoutCoverTextChars: Int = 8,
    val textColorEnabled: Boolean = false,
    val colorSource: Int = COLOR_SOURCE_ALBUM,
    val customColor: Int = DEFAULT_CUSTOM_COLOR,
    val progressColorEnabled: Boolean = false,
    val actionStyle: Int = ACTION_STYLE_DISABLED,
    val notificationStyle: Int = NOTIFICATION_STYLE_STANDARD,
    val mediaButtonLayout: Int = MEDIA_BUTTON_LAYOUT_TWO,
    val clickStyle: Int = CLICK_STYLE_DEFAULT,
    val shareEnabled: Boolean = true,
    val shareFormat: Int = SHARE_FORMAT_LYRIC_AND_SONG,
    val xmsfBypassMode: Int = XMSF_MODE_STANDARD,
    val xmsfCustomDurationMs: Int = XMSF_STANDARD_DURATION_MS,
    val dismissDelayMs: Int = 0
) {
    fun sanitized(): XiaomiSuperIslandSettings = copy(
        lyricTextMode = lyricTextMode.coerceIn(TEXT_ORIGINAL, TEXT_PRONUNCIATION),
        lyricMode = lyricMode.coerceIn(LYRIC_MODE_STANDARD, LYRIC_MODE_FULL),
        rightTextChars = rightTextChars.coerceIn(6, 14),
        leftWithCoverTextChars = leftWithCoverTextChars.coerceIn(4, 10),
        leftWithoutCoverTextChars = leftWithoutCoverTextChars.coerceIn(6, 14),
        colorSource = colorSource.coerceIn(COLOR_SOURCE_ALBUM, COLOR_SOURCE_CUSTOM),
        customColor = customColor or ALPHA_MASK,
        actionStyle = actionStyle.coerceIn(ACTION_STYLE_DISABLED, ACTION_STYLE_MEDIA_CONTROLS),
        notificationStyle = notificationStyle.coerceIn(NOTIFICATION_STYLE_STANDARD, NOTIFICATION_STYLE_ADVANCED),
        mediaButtonLayout = mediaButtonLayout.coerceIn(MEDIA_BUTTON_LAYOUT_TWO, MEDIA_BUTTON_LAYOUT_THREE),
        clickStyle = clickStyle.coerceIn(CLICK_STYLE_DEFAULT, CLICK_STYLE_OPEN_APP),
        shareFormat = shareFormat.coerceIn(SHARE_FORMAT_LYRIC_AND_SONG, SHARE_FORMAT_ARTIST_AND_SONG),
        xmsfBypassMode = xmsfBypassMode.coerceIn(XMSF_MODE_DISABLED, XMSF_MODE_AGGRESSIVE),
        xmsfCustomDurationMs = xmsfCustomDurationMs
            .coerceIn(XMSF_CUSTOM_DURATION_MIN_MS, XMSF_CUSTOM_DURATION_MAX_MS)
            .let { it - (it % XMSF_CUSTOM_DURATION_STEP_MS) },
        dismissDelayMs = DISMISS_DELAYS_MS.minBy { kotlin.math.abs(it - dismissDelayMs) }
    )

    fun encode(): String {
        val value = sanitized()
        return JSONObject()
            .put("text", value.lyricTextMode)
            .put("mode", value.lyricMode)
            .put("leftCover", value.fullLyricShowLeftCover)
            .put("scroll", value.scrollingEnabled)
            .put("rightChars", value.rightTextChars)
            .put("leftCoverChars", value.leftWithCoverTextChars)
            .put("leftChars", value.leftWithoutCoverTextChars)
            .put("colorize", value.textColorEnabled)
            .put("colorSource", value.colorSource)
            .put("customColor", value.customColor)
            .put("progressColor", value.progressColorEnabled)
            .put("actions", value.actionStyle)
            .put("notificationStyle", value.notificationStyle)
            .put("buttonLayout", value.mediaButtonLayout)
            .put("click", value.clickStyle)
            .put("share", value.shareEnabled)
            .put("shareFormat", value.shareFormat)
            .put("xmsf", value.xmsfBypassMode)
            .put("xmsfDuration", value.xmsfCustomDurationMs)
            .put("dismissDelay", value.dismissDelayMs)
            .toString()
    }

    companion object {
        const val TEXT_ORIGINAL = 0
        const val TEXT_TRANSLATION = 1
        const val TEXT_PRONUNCIATION = 2
        const val LYRIC_MODE_STANDARD = 0
        const val LYRIC_MODE_FULL = 1
        const val COLOR_SOURCE_ALBUM = 0
        const val COLOR_SOURCE_CUSTOM = 1
        const val DEFAULT_CUSTOM_COLOR = -0x00CB7D01
        const val ACTION_STYLE_DISABLED = 0
        const val ACTION_STYLE_MEDIA_CONTROLS = 1
        const val NOTIFICATION_STYLE_STANDARD = 0
        const val NOTIFICATION_STYLE_ADVANCED = 1
        const val MEDIA_BUTTON_LAYOUT_TWO = 0
        const val MEDIA_BUTTON_LAYOUT_THREE = 1
        const val CLICK_STYLE_DEFAULT = 0
        const val CLICK_STYLE_MEDIA_CONTROLS = 1
        const val CLICK_STYLE_OPEN_APP = 2
        const val SHARE_FORMAT_LYRIC_AND_SONG = 0
        const val SHARE_FORMAT_INLINE = 1
        const val SHARE_FORMAT_ARTIST_AND_SONG = 2
        const val XMSF_MODE_DISABLED = 0
        const val XMSF_MODE_STANDARD = 1
        const val XMSF_MODE_CUSTOM = 2
        const val XMSF_MODE_AGGRESSIVE = 3
        const val XMSF_STANDARD_DURATION_MS = 100
        const val XMSF_CUSTOM_DURATION_MIN_MS = 100
        const val XMSF_CUSTOM_DURATION_MAX_MS = 500
        const val XMSF_CUSTOM_DURATION_STEP_MS = 50
        val DISMISS_DELAYS_MS = listOf(0, 1_000, 3_000, 5_000)

        private const val ALPHA_MASK = -0x1000000

        fun decode(encoded: String?): XiaomiSuperIslandSettings {
            if (encoded.isNullOrBlank()) return XiaomiSuperIslandSettings()
            return runCatching {
                val json = JSONObject(encoded)
                XiaomiSuperIslandSettings(
                    lyricTextMode = json.optInt("text", TEXT_ORIGINAL),
                    lyricMode = json.optInt("mode", LYRIC_MODE_STANDARD),
                    fullLyricShowLeftCover = json.optBoolean("leftCover", true),
                    scrollingEnabled = json.optBoolean("scroll", true),
                    rightTextChars = json.optInt("rightChars", 7),
                    leftWithCoverTextChars = json.optInt("leftCoverChars", 6),
                    leftWithoutCoverTextChars = json.optInt("leftChars", 8),
                    textColorEnabled = json.optBoolean("colorize", false),
                    colorSource = json.optInt("colorSource", COLOR_SOURCE_ALBUM),
                    customColor = json.optInt("customColor", DEFAULT_CUSTOM_COLOR),
                    progressColorEnabled = json.optBoolean("progressColor", false),
                    actionStyle = json.optInt("actions", ACTION_STYLE_DISABLED),
                    notificationStyle = json.optInt("notificationStyle", NOTIFICATION_STYLE_STANDARD),
                    mediaButtonLayout = json.optInt("buttonLayout", MEDIA_BUTTON_LAYOUT_TWO),
                    clickStyle = json.optInt("click", CLICK_STYLE_DEFAULT),
                    shareEnabled = json.optBoolean("share", true),
                    shareFormat = json.optInt("shareFormat", SHARE_FORMAT_LYRIC_AND_SONG),
                    xmsfBypassMode = json.optInt("xmsf", XMSF_MODE_STANDARD),
                    xmsfCustomDurationMs = json.optInt("xmsfDuration", XMSF_STANDARD_DURATION_MS),
                    dismissDelayMs = json.optInt("dismissDelay", 0)
                ).sanitized()
            }.getOrDefault(XiaomiSuperIslandSettings())
        }
    }
}
