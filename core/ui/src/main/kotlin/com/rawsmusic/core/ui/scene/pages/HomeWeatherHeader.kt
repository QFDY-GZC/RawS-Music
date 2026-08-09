package com.rawsmusic.core.ui.scene.pages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.rawsmusic.core.ui.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val WEATHER_CACHE_VALID_MS = 30L * 60L * 1_000L
private const val WEATHER_REFRESH_MS = 30L * 60L * 1_000L
private const val WEATHER_ICON_ACTIVE_MS = 5_000L
private const val WEATHER_ICON_PAUSE_MS = 2_000L

private data class HomeWeatherSnapshot(
    val weatherCode: Int,
    val temperatureC: Double,
    val apparentTemperatureC: Double,
    val isDay: Boolean,
    val windGustKmh: Double,
    val observedAtMs: Long
)

private data class HomeWeatherPresentation(
    @RawRes val iconRes: Int,
    val title: String,
    val message: String
)

@Composable
internal fun HomeWeatherHeader(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    var hasLocationPermission by remember {
        mutableStateOf(context.hasCoarseLocationPermission())
    }
    var weather by remember {
        mutableStateOf(HomeWeatherRepository.readCached(context))
    }
    var refreshToken by remember { mutableIntStateOf(0) }
    var currentHour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val permissionPrefs = remember {
        context.getSharedPreferences("home_weather_permission", Context.MODE_PRIVATE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        if (granted) refreshToken += 1
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission && !permissionPrefs.getBoolean("asked", false)) {
            delay(900L)
            permissionPrefs.edit().putBoolean("asked", true).apply()
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(hasLocationPermission, refreshToken) {
        if (hasLocationPermission) {
            HomeWeatherRepository.load(context)?.let { weather = it }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@LaunchedEffect
        while (true) {
            delay(WEATHER_REFRESH_MS)
            HomeWeatherRepository.load(context, forceRefresh = true)?.let { weather = it }
        }
    }

    val presentation = weatherPresentation(
        snapshot = weather,
        hour = currentHour,
        context = context
    )
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(presentation.iconRes)
    )
    var iconAnimationPlaying by remember(presentation.iconRes) { mutableStateOf(true) }

    LaunchedEffect(presentation.iconRes, composition) {
        if (composition == null) return@LaunchedEffect
        while (true) {
            iconAnimationPlaying = true
            delay(WEATHER_ICON_ACTIVE_MS)
            iconAnimationPlaying = false
            delay(WEATHER_ICON_PAUSE_MS)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val grantedNow = context.hasCoarseLocationPermission()
                hasLocationPermission = grantedNow
                if (grantedNow) {
                    refreshToken += 1
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(60.dp),
            contentAlignment = Alignment.Center
        ) {
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                isPlaying = iconAnimationPlaying,
                restartOnPlay = false,
                modifier = Modifier.size(58.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = presentation.title,
                fontSize = 20.sp,
                color = MiuixTheme.colorScheme.onBackground
            )
            Text(
                text = presentation.message,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

private object HomeWeatherRepository {
    fun readCached(context: Context): HomeWeatherSnapshot? {
        val prefs = context.getSharedPreferences("home_weather_cache", Context.MODE_PRIVATE)
        val observedAt = prefs.getLong("observed_at", 0L)
        if (observedAt <= 0L) return null
        return HomeWeatherSnapshot(
            weatherCode = prefs.getInt("weather_code", 0),
            temperatureC = prefs.getFloat("temperature", Float.NaN).toDouble(),
            apparentTemperatureC = prefs.getFloat("apparent_temperature", Float.NaN).toDouble(),
            isDay = prefs.getBoolean("is_day", true),
            windGustKmh = prefs.getFloat("wind_gust", 0f).toDouble(),
            observedAtMs = observedAt
        )
    }

    suspend fun load(
        context: Context,
        forceRefresh: Boolean = false
    ): HomeWeatherSnapshot? = withContext(Dispatchers.IO) {
        val cached = readCached(context)
        if (
            !forceRefresh &&
            cached != null &&
            System.currentTimeMillis() - cached.observedAtMs < WEATHER_CACHE_VALID_MS
        ) {
            return@withContext cached
        }

        readXiaomiWeather(context)?.let {
            cache(context, it)
            return@withContext it
        }

        if (!context.hasCoarseLocationPermission()) return@withContext cached
        val location = findCurrentLocation(context) ?: return@withContext cached
        val remote = fetchOpenMeteo(location) ?: return@withContext cached
        cache(context, remote)
        remote
    }

    private fun readXiaomiWeather(context: Context): HomeWeatherSnapshot? {
        val uri = Uri.parse("content://weather/actualWeatherData/2")
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf("weather_type", "description", "temperature"),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val description = cursor.stringOrNull("description").orEmpty()
                val weatherType = cursor.stringOrNull("weather_type").orEmpty()
                val temperature = cursor.stringOrNull("temperature")
                    ?.toDoubleOrNull()
                    ?: Double.NaN
                HomeWeatherSnapshot(
                    weatherCode = descriptionToWmoCode("$description $weatherType"),
                    temperatureC = temperature,
                    apparentTemperatureC = temperature,
                    isDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6..17,
                    windGustKmh = 0.0,
                    observedAtMs = System.currentTimeMillis()
                )
            }
        }.getOrNull()
    }

    private suspend fun findCurrentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val cached = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < 12L * 60L * 60L * 1_000L) {
            return cached
        }

        return withTimeoutOrNull(7_000L) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    override fun onProviderDisabled(provider: String) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                val provider = providers.firstOrNull { candidate ->
                    runCatching { manager.isProviderEnabled(candidate) }.getOrDefault(false)
                }
                if (provider == null) {
                    continuation.resume(cached)
                } else {
                    runCatching {
                        manager.requestSingleUpdate(provider, listener, null)
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(cached)
                    }
                }
            }
        } ?: cached
    }

    private fun fetchOpenMeteo(location: Location): HomeWeatherSnapshot? {
        val endpoint = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=")
            append(location.latitude)
            append("&longitude=")
            append(location.longitude)
            append("&current=temperature_2m,apparent_temperature,is_day,weather_code,wind_gusts_10m")
            append("&timezone=auto")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val root = connection.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(root).getJSONObject("current")
            HomeWeatherSnapshot(
                weatherCode = current.optInt("weather_code", 0),
                temperatureC = current.optDouble("temperature_2m", Double.NaN),
                apparentTemperatureC = current.optDouble("apparent_temperature", Double.NaN),
                isDay = current.optInt("is_day", 1) == 1,
                windGustKmh = current.optDouble("wind_gusts_10m", 0.0),
                observedAtMs = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun cache(context: Context, snapshot: HomeWeatherSnapshot) {
        context.getSharedPreferences("home_weather_cache", Context.MODE_PRIVATE)
            .edit()
            .putInt("weather_code", snapshot.weatherCode)
            .putFloat("temperature", snapshot.temperatureC.toFloat())
            .putFloat("apparent_temperature", snapshot.apparentTemperatureC.toFloat())
            .putBoolean("is_day", snapshot.isDay)
            .putFloat("wind_gust", snapshot.windGustKmh.toFloat())
            .putLong("observed_at", snapshot.observedAtMs)
            .apply()
    }
}

private fun android.database.Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Context.hasCoarseLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun weatherPresentation(
    snapshot: HomeWeatherSnapshot?,
    hour: Int,
    context: Context
): HomeWeatherPresentation {
    val isDay = snapshot?.isDay ?: (hour in 6..17)
    val code = snapshot?.weatherCode ?: 0
    val icon = when (code) {
        0 -> if (isDay) R.raw.weather_clear_day else R.raw.weather_clear_night
        1, 2 -> if (isDay) R.raw.weather_partly_cloudy_day else R.raw.weather_partly_cloudy_night
        3 -> R.raw.weather_overcast
        45, 48 -> R.raw.weather_fog
        in 51..67, in 80..82 -> R.raw.weather_rain
        in 71..77, in 85..86 -> R.raw.weather_snow
        in 95..99 -> R.raw.weather_thunderstorm
        else -> if (isDay) R.raw.weather_partly_cloudy_day else R.raw.weather_partly_cloudy_night
    }
    val temperature = snapshot?.temperatureC
        ?.takeUnless { it.isNaN() }
        ?.roundToInt()
    val condition = context.getString(
        when (code) {
            0 -> R.string.home_weather_clear
            1, 2 -> R.string.home_weather_partly_cloudy
            3 -> R.string.home_weather_overcast
            45, 48 -> R.string.home_weather_fog
            in 51..67, in 80..82 -> R.string.home_weather_rain
            in 71..77, in 85..86 -> R.string.home_weather_snow
            in 95..99 -> R.string.home_weather_thunderstorm
            else -> R.string.home_weather_clear
        }
    )
    val title = if (temperature != null) {
        context.getString(R.string.home_weather_temperature, temperature, condition)
    } else {
        context.getString(timeGreetingTitle(hour))
    }
    val messageRes = when {
        snapshot == null -> timeGreetingMessage(hour)
        snapshot.windGustKmh >= 75.0 -> R.string.home_weather_tip_typhoon
        code in 95..99 -> R.string.home_weather_tip_thunderstorm
        code in 51..67 || code in 80..82 -> R.string.home_weather_tip_rain
        code in 71..77 || code in 85..86 -> R.string.home_weather_tip_snow
        code == 45 || code == 48 -> R.string.home_weather_tip_fog
        snapshot.apparentTemperatureC >= 35.0 -> R.string.home_weather_tip_hot
        snapshot.apparentTemperatureC <= 5.0 -> R.string.home_weather_tip_cold
        code == 3 -> R.string.home_weather_tip_cloudy
        else -> timeGreetingMessage(hour)
    }
    return HomeWeatherPresentation(
        iconRes = icon,
        title = title,
        message = context.getString(messageRes)
    )
}

private fun timeGreetingTitle(hour: Int): Int = when (hour) {
    in 5..10 -> R.string.home_weather_good_morning
    in 11..13 -> R.string.home_weather_good_noon
    in 14..17 -> R.string.home_weather_good_afternoon
    in 18..22 -> R.string.home_weather_good_evening
    else -> R.string.home_weather_good_night
}

private fun timeGreetingMessage(hour: Int): Int = when (hour) {
    in 5..10 -> R.string.home_weather_message_morning
    in 11..13 -> R.string.home_weather_message_noon
    in 14..17 -> R.string.home_weather_message_afternoon
    in 18..22 -> R.string.home_weather_message_evening
    else -> R.string.home_weather_message_night
}

private fun descriptionToWmoCode(description: String): Int {
    val normalized = description.lowercase()
    return when {
        "雷" in normalized || "thunder" in normalized -> 95
        "雪" in normalized || "snow" in normalized -> 71
        "雨" in normalized || "rain" in normalized || "shower" in normalized -> 61
        "雾" in normalized || "霾" in normalized || "fog" in normalized || "haze" in normalized -> 45
        "阴" in normalized || "overcast" in normalized -> 3
        "云" in normalized || "cloud" in normalized -> 2
        else -> 0
    }
}
