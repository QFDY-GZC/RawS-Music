package com.rawsmusic.module.player.devicecontrol.bluetooth

/**
 * Simplified-Chinese display strings derived from HeyMelody 16.9.1's own zh-CN
 * resources. Protocol IDs/modeTypes remain the canonical identity; these strings are
 * presentation only and must never be used to select a command or capability.
 *
 * Source examples:
 * - res string/melody_common_detail_main_set_noise_reduction = 噪声控制
 * - res string/melody_ui_equalizer_title = 均衡器
 * - res string/melody_ui_pref_spatial_audio_title = 空间音频
 * - res string/melody_ui_prompt_volume_title = 耳机提示音
 *
 * EQ modeType mapping is derived from HeyMelody vendor EQ mapping; ANC modeType mapping from
 * vendor ANC mapping plus the middle-noise helper; spatial 0/1/2 rendering is derived from
 * SpatialAudioContentFragment.
 */
internal object BluetoothOpoHeyMelodyUiStrings {
    const val NOISE_CONTROL = "噪声控制"
    const val EQUALIZER = "均衡器"
    const val CUSTOM_EQUALIZER = "自定义"
    const val SPATIAL_AUDIO = "空间音频"
    const val PROMPT_VOLUME = "耳机提示音"

    private val ancByModeType = mapOf(
        1 to "关闭",
        2 to "通透",
        3 to "弱降噪",
        4 to "强降噪",
        5 to "降噪",
        6 to "聆听",
        7 to "智能降噪",
        8 to "中度降噪",
        10 to "自适应",
    )

    /** HeyMelody Equalizer modeType -> exact zh-CN title. */
    private val eqByModeType = mapOf(
        1 to "经典",
        2 to "动感低音",
        3 to "清澈人声",
        4 to "明亮通透",
        5 to "默认",
        6 to "Dynaudio 淳朴悠然",
        7 to "Dynaudio 温暖轻柔",
        8 to "Dynaudio 澎湃爽朗",
        9 to "Dynaudio 纯粹原音",
        10 to "久石让大师调音",
        11 to "均衡（默认）",
        12 to "深海低音",
        13 to "明快清亮",
        14 to "纯净人声",
        15 to "微醺柔音",
        16 to "Enco X 怀旧经典",
        17 to "均衡",
        18 to "Reno特调（破晓曙光）",
        19 to "汉斯季默大师调音",
        20 to "自然竹韵",
        21 to "Reno特调（灿烂朝霞）",
        22 to "纯粹原音",
        23 to "澎湃爽朗",
        24 to "悠扬开阔",
        25 to "Reno特调（幻紫星河）",
        26 to "至臻原音",
        27 to "高清解析",
        28 to "纯享人声",
        29 to "澎湃低音",
        30 to "丹拿特调",
        31 to "脉冲低音",
        32 to "悠扬人声",
        33 to "星河独白",
        34 to "活力动感",
        35 to "默认",
        36 to "Dynaudio 淳朴悠然",
        37 to "Dynaudio 温暖轻柔",
        38 to "Dynaudio 澎湃爽朗",
        39 to "Dynaudio 纯粹原音",
        40 to "丹拿人声",
        41 to "清晰通透",
    )

    /** PollCommandManager feature ID -> exact HeyMelody zh-CN feature title. */
    private val featureById = mapOf(
        4 to "佩戴检测",
        6 to "游戏模式",
        9 to "人声增强",
        11 to "个性化听感",
        12 to "个性化降噪",
        15 to "耳机专注",
        17 to "设备双连",
        19 to "耳机录音",
        21 to "智能免摘对话",
        22 to "防丢提醒",
        23 to "省电模式",
        24 to "Hi-Res 模式",
        25 to "快捷语音指令",
        28 to "智能音量调节",
        29 to "BassWave™动态低音",
        53 to "长按调节音量",
        55 to "Windows Swift Pair",
        57 to "来电语音控制",
        58 to "入睡暂停音乐播放",
        59 to "头部动作",
    )

    fun ancMode(modeType: Int, fallback: String): String = ancByModeType[modeType] ?: fallback

    fun eqMode(modeType: Int, fallback: String): String = eqByModeType[modeType] ?: fallback

    fun feature(featureId: Int, fallback: String): String = featureById[featureId] ?: fallback

    /**
     * HeyMelody renders spatial type 0 as 关闭 and type 2 as 头部跟踪. Type 1 is 固定
     * for the three-mode UI; on switch-style two-mode products it renders the same protocol
     * state as 打开.
     */
    fun spatialType(type: Int, supportedTypes: List<Int>): String = when (type) {
        0 -> "关闭"
        1 -> if (2 in supportedTypes) "固定" else "打开"
        2 -> "头部跟踪"
        else -> "空间音频类型 $type"
    }
}
