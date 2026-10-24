package tv.livingroom.home

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

/** What the corner of the home screen shows: the temperature now, and what the sky is doing. */
data class Weather(val city: String, val temperature: Int, val sky: Sky, val isDay: Boolean)

enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Storm }

/**
 * Current weather from Open-Meteo, for wherever the box's internet connection says it is (ipwho.is).
 * Neither needs a key. A TV box has no GPS, so if the lookup fails it falls back to Bangkok.
 * The last answer is kept, so the corner fills in straight away after a restart.
 */
object WeatherSource {
    private const val PREFS = "weather"
    private const val PLACE_MAX_AGE = 24 * 60 * 60 * 1000L
    private val Fallback = Place("Bangkok", 13.7563, 100.5018)

    private data class Place(val city: String, val lat: Double, val lon: Double)

    fun cached(context: Context): Weather? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sky = prefs.getString("sky", null)?.let { name -> Sky.entries.firstOrNull { it.name == name } } ?: return null
        return Weather(
            city = prefs.getString("city", null).orEmpty(),
            temperature = prefs.getInt("temperature", 0),
            sky = sky,
            isDay = prefs.getBoolean("isDay", true)
        )
    }

    /** Blocking; call off the main thread. Null when the weather can't be fetched. */
    fun load(context: Context): Weather? = runCatching {
        val place = place(context)
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto",
            place.lat, place.lon
        )
        val current = JSONObject(get(url)).getJSONObject("current")
        Weather(
            city = place.city,
            temperature = current.getDouble("temperature_2m").roundToInt(),
            sky = sky(current.getInt("weather_code")),
            isDay = current.optInt("is_day", 1) == 1
        ).also { save(context, it) }
    }.getOrNull()

    // The box rarely moves, so the place is looked up once a day.
    private fun place(context: Context): Place {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("placeAt", 0L) < PLACE_MAX_AGE && prefs.contains("lat")) {
            return Place(
                prefs.getString("placeCity", null).orEmpty(),
                prefs.getFloat("lat", 0f).toDouble(),
                prefs.getFloat("lon", 0f).toDouble()
            )
        }
        val found = runCatching {
            val json = JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude"))
            if (!json.optBoolean("success")) null
            else Place(json.optString("city"), json.getDouble("latitude"), json.getDouble("longitude"))
        }.getOrNull() ?: return Fallback
        prefs.edit()
            .putString("placeCity", found.city)
            .putFloat("lat", found.lat.toFloat())
            .putFloat("lon", found.lon.toFloat())
            .putLong("placeAt", System.currentTimeMillis())
            .apply()
        return found
    }

    private fun save(context: Context, weather: Weather) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("city", weather.city)
            .putInt("temperature", weather.temperature)
            .putString("sky", weather.sky.name)
            .putBoolean("isDay", weather.isDay)
            .apply()
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // WMO weather codes, as Open-Meteo reports them.
    private fun sky(code: Int): Sky = when (code) {
        0, 1 -> Sky.Clear
        2 -> Sky.PartlyCloudy
        3 -> Sky.Cloudy
        45, 48 -> Sky.Fog
        in 51..67, in 80..82 -> Sky.Rain
        in 71..77, 85, 86 -> Sky.Snow
        in 95..99 -> Sky.Storm
        else -> Sky.Cloudy
    }
}
