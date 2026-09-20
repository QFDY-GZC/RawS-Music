package com.rawsmusic.module.player.devicecontrol.bluetooth

/**
 * HeyMelody 16.9.1 PollCommandManager feature-switch IDs used by GET 0x010D / SET 0x0403.
 * IDs are included only when their signed whitelist capability is enabled for the resolved model.
 * Non-user-facing/debug-only switches are intentionally omitted from the Hardware DSP page.
 */
internal object BluetoothOpoHeyMelodyFeatureMap {
    data class Spec(
        val id: Int,
        val whitelistKeys: Set<String>,
        val label: String,
    )

    private val specs = listOf(
        Spec(4, setOf("wearDetection"), "佩戴检测"),
        Spec(6, setOf("gameMode", "gameModeList"), "游戏模式"),
        Spec(9, setOf("vocalEnhance"), "人声增强"),
        Spec(11, setOf("hearingEnhancement", "hearingEnhancementNew"), "个性化听感"),
        Spec(12, setOf("personalNoise", "personalNoiseCompat"), "个性化降噪"),
        Spec(15, setOf("zenMode"), "耳机专注"),
        Spec(17, setOf("multiDevicesConnect"), "设备双连"),
        Spec(19, setOf("headSetSoundRecord"), "耳机录音"),
        Spec(21, setOf("smartCall"), "智能免摘对话"),
        Spec(22, setOf("deviceLostRemind"), "防丢提醒"),
        Spec(23, setOf("longPowerMode"), "省电模式"),
        Spec(24, setOf("highToneQuality"), "Hi-Res 模式"),
        Spec(25, setOf("voiceCommand"), "快捷语音指令"),
        Spec(28, setOf("controlAutoVolumeSupport"), "智能音量调节"),
        Spec(29, setOf("bassEngineSupport"), "BassWave™动态低音"),
        Spec(53, setOf("longPressVolume"), "长按调节音量"),
        Spec(55, setOf("swiftPair"), "Swift Pair"),
        Spec(57, setOf("incomingCallControl"), "来电语音控制"),
        Spec(58, setOf("sleepDetection"), "入睡暂停音乐播放"),
        Spec(59, setOf("headMotion"), "头部动作"),
    )

    /** Union read list. GET 0x010D is read-only; rendering is still profile-gated. */
    val queryIds: List<Int> = specs.map(Spec::id).distinct()

    fun supported(profile: BluetoothOpoDeviceProfiles.Profile): List<Spec> =
        specs.filter { spec -> spec.whitelistKeys.any(profile::isFunctionEnabled) }

    fun byId(id: Int): Spec? = specs.firstOrNull { it.id == id }
}
