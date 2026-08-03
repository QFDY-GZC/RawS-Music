package com.rawsmusic.core.common.prefs

import com.tencent.mmkv.MMKV

object UIPreferences {

    private val kv by lazy { MMKV.defaultMMKV() }

    var themeMode: Int
        get() = kv.decodeInt("ui_theme_mode", 1)  // 默认亮色主题
        set(value) { kv.encode("ui_theme_mode", value) }

    var accentColor: Int
        get() = kv.decodeInt("ui_accent_color", 0)
        set(value) { kv.encode("ui_accent_color", value) }

    var isBlurEnabled: Boolean
        get() = kv.decodeBool("ui_blur_enabled", true)
        set(value) { kv.encode("ui_blur_enabled", value) }

    var blurRadius: Int
        get() = kv.decodeInt("ui_blur_radius", 20)
        set(value) { kv.encode("ui_blur_radius", value) }

    var isBottomBarEnabled: Boolean
        get() = kv.decodeBool("ui_bottom_bar_enabled", true)
        set(value) { kv.encode("ui_bottom_bar_enabled", value) }

    var colorThemeMode: Int
        get() = kv.decodeInt("ui_color_theme_mode", 0)  // 0=miuix, 1=monet_auto, 2=monet_light, 3=monet_dark
        set(value) { kv.encode("ui_color_theme_mode", value) }
}
