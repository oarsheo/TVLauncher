package com.arshe.cams

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Current weather from Open-Meteo (free, no key). Needs internet; the wall just hides it offline. */
data class Weather(
    val temp: Int,
    val feels: Int,
    val code: Int,
    val day: Boolean,
    val wind: Int,
    val humidity: Int,
    val max: Int,
    val min: Int,
    val rain: Int,
) {
    val icon: String get() = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1 -> if (day) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    val text: String get() = when (code) {
        0 -> "Ясно"
        1 -> "Малооблачно"
        2 -> "Переменная облачность"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Ледяная морось"
        61 -> "Небольшой дождь"
        63 -> "Дождь"
        65 -> "Сильный дождь"
        66, 67 -> "Ледяной дождь"
        71 -> "Небольшой снег"
        73 -> "Снег"
        75 -> "Сильный снег"
        77 -> "Снежная крупа"
        80, 81 -> "Ливень"
        82 -> "Сильный ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        else -> ""
    }

    companion object {
        fun fetch(lat: Double, lon: Double): Weather {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1&wind_speed_unit=ms",
                lat, lon,
            )
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) error("weather ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val cur = j.getJSONObject("current")
            val day = j.getJSONObject("daily")
            fun first(name: String) = day.getJSONArray(name).optDouble(0, 0.0)
            return Weather(
                temp = cur.getDouble("temperature_2m").roundToInt(),
                feels = cur.getDouble("apparent_temperature").roundToInt(),
                code = cur.getInt("weather_code"),
                day = cur.optInt("is_day", 1) == 1,
                wind = cur.getDouble("wind_speed_10m").roundToInt(),
                humidity = cur.getInt("relative_humidity_2m"),
                max = first("temperature_2m_max").roundToInt(),
                min = first("temperature_2m_min").roundToInt(),
                rain = first("precipitation_probability_max").roundToInt(),
            )
        }
    }
}
