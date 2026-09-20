package com.rawsmusic.core.ui.scene.pages

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val HOME_HEADER_PREFS = "home_header_preferences"
private const val HOME_WEATHER_VISIBLE_KEY = "weather_visible"
private const val HOME_CAROUSEL_VISIBLE_KEY = "carousel_visible"
private const val HOME_CAROUSEL_STYLE_KEY = "carousel_style"
private const val HOME_CAROUSEL_LYRIC_VISIBLE_KEY = "carousel_lyric_visible"
private const val HOME_CAROUSEL_GESTURE_LOCKED_KEY = "carousel_gesture_locked"
private const val HOME_FLOATING_GLASS_ENABLED_KEY = "floating_glass_enabled"
private const val HOME_FLOATING_LONG_PRESS_SPRING_ENABLED_KEY = "floating_long_press_spring_enabled"
private const val HOME_FLOATING_PRESS_DISPLACEMENT_ENABLED_KEY = "floating_press_displacement_enabled"
private const val HOME_TOP_FEATHER_ENABLED_KEY = "top_feather_enabled"
private const val HOME_TOP_FEATHER_STRENGTH_KEY = "top_feather_strength"
private const val HOME_MOST_PLAYED_VISIBLE_KEY = "most_played_visible"

enum class HomeTopFeatherStrength(val value: Int) {
    Soft(0),
    Standard(1),
    Strong(2);

    companion object {
        fun from(value: Int): HomeTopFeatherStrength =
            entries.firstOrNull { it.value == value } ?: Strong
    }
}

/** Shared home-header settings used by both the hamburger rail and the legacy popup. */
@Stable
class HomeHeaderOptionsState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        HOME_HEADER_PREFS,
        Context.MODE_PRIVATE,
    )

    var weatherVisible by mutableStateOf(
        preferences.getBoolean(HOME_WEATHER_VISIBLE_KEY, true),
    )
        private set

    var carouselVisible by mutableStateOf(
        preferences.getBoolean(HOME_CAROUSEL_VISIBLE_KEY, true),
    )
        private set

    var carouselLyricVisible by mutableStateOf(
        preferences.getBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, true),
    )
        private set

    var carouselGestureLocked by mutableStateOf(
        preferences.getBoolean(HOME_CAROUSEL_GESTURE_LOCKED_KEY, false),
    )
        private set

    var carouselStyle by mutableStateOf(
        HomeArtworkCarouselStyle.from(
            preferences.getInt(
                HOME_CAROUSEL_STYLE_KEY,
                HomeArtworkCarouselStyle.CurrentCarousel.value,
            ),
        ),
    )
        private set

    var floatingGlassEnabled by mutableStateOf(
        preferences.getBoolean(HOME_FLOATING_GLASS_ENABLED_KEY, true),
    )
        private set

    var floatingLongPressSpringEnabled by mutableStateOf(
        preferences.getBoolean(HOME_FLOATING_LONG_PRESS_SPRING_ENABLED_KEY, true),
    )
        private set

    var floatingPressDisplacementEnabled by mutableStateOf(
        preferences.getBoolean(HOME_FLOATING_PRESS_DISPLACEMENT_ENABLED_KEY, true),
    )
        private set

    var topFeatherEnabled by mutableStateOf(
        preferences.getBoolean(HOME_TOP_FEATHER_ENABLED_KEY, true),
    )
        private set

    var topFeatherStrength by mutableStateOf(
        HomeTopFeatherStrength.from(
            preferences.getInt(
                HOME_TOP_FEATHER_STRENGTH_KEY,
                HomeTopFeatherStrength.Strong.value,
            ),
        ),
    )
        private set

    var mostPlayedVisible by mutableStateOf(
        preferences.getBoolean(HOME_MOST_PLAYED_VISIBLE_KEY, true),
    )
        private set

    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            HOME_WEATHER_VISIBLE_KEY -> weatherVisible =
                preferences.getBoolean(HOME_WEATHER_VISIBLE_KEY, true)
            HOME_CAROUSEL_VISIBLE_KEY -> carouselVisible =
                preferences.getBoolean(HOME_CAROUSEL_VISIBLE_KEY, true)
            HOME_CAROUSEL_LYRIC_VISIBLE_KEY -> carouselLyricVisible =
                preferences.getBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, true)
            HOME_CAROUSEL_GESTURE_LOCKED_KEY -> carouselGestureLocked =
                preferences.getBoolean(HOME_CAROUSEL_GESTURE_LOCKED_KEY, false)
            HOME_CAROUSEL_STYLE_KEY -> carouselStyle = HomeArtworkCarouselStyle.from(
                preferences.getInt(
                    HOME_CAROUSEL_STYLE_KEY,
                    HomeArtworkCarouselStyle.CurrentCarousel.value,
                ),
            )
            HOME_FLOATING_GLASS_ENABLED_KEY -> floatingGlassEnabled =
                preferences.getBoolean(HOME_FLOATING_GLASS_ENABLED_KEY, true)
            HOME_FLOATING_LONG_PRESS_SPRING_ENABLED_KEY -> floatingLongPressSpringEnabled =
                preferences.getBoolean(HOME_FLOATING_LONG_PRESS_SPRING_ENABLED_KEY, true)
            HOME_FLOATING_PRESS_DISPLACEMENT_ENABLED_KEY -> floatingPressDisplacementEnabled =
                preferences.getBoolean(HOME_FLOATING_PRESS_DISPLACEMENT_ENABLED_KEY, true)
            HOME_TOP_FEATHER_ENABLED_KEY -> topFeatherEnabled =
                preferences.getBoolean(HOME_TOP_FEATHER_ENABLED_KEY, true)
            HOME_TOP_FEATHER_STRENGTH_KEY -> topFeatherStrength = HomeTopFeatherStrength.from(
                preferences.getInt(
                    HOME_TOP_FEATHER_STRENGTH_KEY,
                    HomeTopFeatherStrength.Strong.value,
                ),
            )
            HOME_MOST_PLAYED_VISIBLE_KEY -> mostPlayedVisible =
                preferences.getBoolean(HOME_MOST_PLAYED_VISIBLE_KEY, true)
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun updateWeatherVisible(value: Boolean) {
        weatherVisible = value
        preferences.edit().putBoolean(HOME_WEATHER_VISIBLE_KEY, value).apply()
    }

    fun updateCarouselVisible(value: Boolean) {
        carouselVisible = value
        preferences.edit().putBoolean(HOME_CAROUSEL_VISIBLE_KEY, value).apply()
    }

    fun updateCarouselLyricVisible(value: Boolean) {
        carouselLyricVisible = value
        preferences.edit().putBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, value).apply()
    }

    fun updateCarouselGestureLocked(value: Boolean) {
        carouselGestureLocked = value
        preferences.edit().putBoolean(HOME_CAROUSEL_GESTURE_LOCKED_KEY, value).apply()
    }

    fun updateCarouselStyle(value: HomeArtworkCarouselStyle) {
        carouselStyle = value
        preferences.edit().putInt(HOME_CAROUSEL_STYLE_KEY, value.value).apply()
    }

    fun updateFloatingGlassEnabled(value: Boolean) {
        floatingGlassEnabled = value
        preferences.edit().putBoolean(HOME_FLOATING_GLASS_ENABLED_KEY, value).apply()
    }

    fun updateFloatingLongPressSpringEnabled(value: Boolean) {
        floatingLongPressSpringEnabled = value
        preferences.edit().putBoolean(HOME_FLOATING_LONG_PRESS_SPRING_ENABLED_KEY, value).apply()
    }

    fun updateFloatingPressDisplacementEnabled(value: Boolean) {
        floatingPressDisplacementEnabled = value
        preferences.edit().putBoolean(HOME_FLOATING_PRESS_DISPLACEMENT_ENABLED_KEY, value).apply()
    }

    fun updateTopFeatherEnabled(value: Boolean) {
        topFeatherEnabled = value
        preferences.edit().putBoolean(HOME_TOP_FEATHER_ENABLED_KEY, value).apply()
    }

    fun updateTopFeatherStrength(value: HomeTopFeatherStrength) {
        topFeatherStrength = value
        preferences.edit().putInt(HOME_TOP_FEATHER_STRENGTH_KEY, value.value).apply()
    }

    fun updateMostPlayedVisible(value: Boolean) {
        mostPlayedVisible = value
        preferences.edit().putBoolean(HOME_MOST_PLAYED_VISIBLE_KEY, value).apply()
    }

    fun dispose() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }
}

@Composable
fun rememberHomeHeaderOptionsState(): HomeHeaderOptionsState {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { HomeHeaderOptionsState(context) }
    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}
