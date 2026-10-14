package com.openlauncher.app.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openlauncher.app.R

data class HourlyPoint(
    val hour: Int,           // 0-23, local time
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val precipitationChance: Int = 0   // 0-100, percent
) {
    fun temperatureDisplay(metric: Boolean): String =
        if (metric) "${Math.round(temperatureCelsius)}°" else "${Math.round(celsiusToFahrenheit(temperatureCelsius))}°"

    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, isDay)
}

data class WeatherState(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val windspeedKmh: Double,
    val isDay: Boolean,
    val hourlyForecast: List<HourlyPoint> = emptyList(),
    val feelsLikeCelsius: Double = temperatureCelsius
) {
    // roundToInt, not toInt — truncation displayed 20.9° as 20°
    fun temperatureDisplay(metric: Boolean): String =
        if (metric) "${Math.round(temperatureCelsius)}°C"
        else "${Math.round(celsiusToFahrenheit(temperatureCelsius))}°F"

    fun feelsLikeDisplay(metric: Boolean): String =
        if (metric) "${Math.round(feelsLikeCelsius)}°"
        else "${Math.round(celsiusToFahrenheit(feelsLikeCelsius))}°"

    fun windspeedDisplay(metric: Boolean): String =
        if (metric) "${Math.round(windspeedKmh)} km/h"
        else "${Math.round(windspeedKmh / 1.609)} mph"

    val conditionLabel: String @Composable
    get() = wmoCodeToLabel(weatherCode)
    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, isDay)
}

private fun celsiusToFahrenheit(c: Double) = c * 9.0 / 5.0 + 32.0

@Composable
private fun wmoCodeToLabel(code: Int): String = when (code) {
    0 -> stringResource(R.string.weather_clear)
    1, 2, 3 -> stringResource(R.string.weather_cloudy)
    45, 48 -> stringResource(R.string.weather_foggy)
    51, 53, 55 -> stringResource(R.string.weather_drizzle)
    61, 63, 65 -> stringResource(R.string.weather_rain)
    71, 73, 75 -> stringResource(R.string.weather_snow)
    80, 81, 82 -> stringResource(R.string.weather_showers)
    95 -> stringResource(R.string.weather_thunderstorm)
    96, 99 -> stringResource(R.string.weather_hail)
    else -> stringResource(R.string.weather_unknown)
}

private fun wmoCodeToEmoji(code: Int, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2 -> if (isDay) "⛅" else "🌤"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧️"
    71, 73, 75 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}
