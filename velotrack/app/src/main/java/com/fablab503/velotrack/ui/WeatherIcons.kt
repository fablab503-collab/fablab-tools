package com.fablab503.velotrack.ui

/**
 * Maps a met.no symbol code (e.g. `"lightrainshowers_day"`, see
 * https://api.met.no/weatherapi/weathericon/2.0/documentation for the full ~60-code set) to one
 * emoji. Real weather symbols would mean bundling and licensing an icon set; the app already uses
 * emoji for illustration elsewhere (the no-map card's compass), so this keeps the same visual
 * language instead of adding a new one for one small HUD chip.
 *
 * Codes are `<condition>` or `<condition>_<day|night|polartwilight>`; the day/night part only
 * changes the icon for the three clear-ish conditions, so everything else matches on the
 * condition alone once the suffix is stripped.
 */
fun weatherEmoji(symbolCode: String): String {
    val isNight = symbolCode.endsWith("_night")
    val condition = symbolCode.substringBefore('_')
    return when {
        condition == "clearsky" -> if (isNight) "🌙" else "☀️"
        condition == "fair" -> if (isNight) "🌤️" else "🌤️"
        condition == "partlycloudy" -> if (isNight) "☁️" else "⛅"
        condition == "cloudy" -> "☁️"
        condition == "fog" -> "🌫️"
        condition.contains("thunder") -> "⛈️"
        condition.contains("snow") -> "🌨️"
        condition.contains("sleet") -> "🌨️"
        condition.contains("rain") -> if (condition.startsWith("light")) "🌦️" else "🌧️"
        else -> "🌡️" // unrecognised code: still show the temperature, just no matching pictogram
    }
}
