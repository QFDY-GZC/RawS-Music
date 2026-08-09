package com.rawsmusic.core.ui.scene.pages

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val HOME_HEADER_PREFS = "home_header_preferences"
private const val HOME_WEATHER_VISIBLE_KEY = "weather_visible"
private const val HOME_CAROUSEL_STYLE_KEY = "carousel_style"
private const val HOME_CAROUSEL_LYRIC_VISIBLE_KEY = "carousel_lyric_visible"

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

    var carouselLyricVisible by mutableStateOf(
        preferences.getBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, true),
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

    fun updateWeatherVisible(value: Boolean) {
        weatherVisible = value
        preferences.edit().putBoolean(HOME_WEATHER_VISIBLE_KEY, value).apply()
    }

    fun updateCarouselLyricVisible(value: Boolean) {
        carouselLyricVisible = value
        preferences.edit().putBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, value).apply()
    }

    fun updateCarouselStyle(value: HomeArtworkCarouselStyle) {
        carouselStyle = value
        preferences.edit().putInt(HOME_CAROUSEL_STYLE_KEY, value.value).apply()
    }
}

@Composable
fun rememberHomeHeaderOptionsState(): HomeHeaderOptionsState {
    val context = LocalContext.current.applicationContext
    return remember(context) { HomeHeaderOptionsState(context) }
}
