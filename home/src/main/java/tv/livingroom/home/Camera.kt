package tv.livingroom.home

import android.content.Context
import android.net.ConnectivityManager
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** A camera that go2rtc on the home network streams. The tile plays the light stream, full screen the main one. */
data class Camera(val name: String, val tileUrl: String, val fullUrl: String)

/**
 * Finds go2rtc on the home network by itself, so there is nothing to type on the TV and no address in the app:
 * the last place it was found first, then every address on the box's own network on go2rtc's usual port.
 * A stream called `<name>_sd` next to `<name>` is taken as its light version, for the small tile.
 */
object CameraSource {
    private const val PREFS = "camera"
    private const val API_PORT = 1984

    /** Last time's camera, from disk, so the tile is there on the first frame. */
    fun cached(context: Context): Camera? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("name", null) ?: return null
        val tile = prefs.getString("tile", null) ?: return null
        val full = prefs.getString("full", null) ?: return null
        return Camera(name, tile, full)
    }

    /** The first camera go2rtc offers, or null when there's no go2rtc on the network or it has no streams. */
    suspend fun find(context: Context): Camera? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getString("host", null)
        val camera = known?.let { probe(it) } ?: scan(context)
        // Not found is kept as it was: a Pi that's restarting shouldn't take the tile away.
        if (camera != null) {
            prefs.edit()
                .putString("host", camera.second)
                .putString("name", camera.first.name)
                .putString("tile", camera.first.tileUrl)
                .putString("full", camera.first.fullUrl)
                .apply()
        }
        return camera?.first
    }

    private suspend fun scan(context: Context): Pair<Camera, String>? {
        val prefix = localPrefix(context) ?: return null
        // A few dozen at a time: the whole network takes a couple of seconds, without flooding a small router.
        for (batch in (1..254).chunked(32)) {
            val found = coroutineScope { batch.map { async { probe("$prefix.$it") } }.awaitAll() }.firstOrNull { it != null }
            if (found != null) return found
        }
        return null
    }

    /** The first three parts of the box's own address, e.g. "192.168.1". */
    private fun localPrefix(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val link = runCatching { connectivity.getLinkProperties(connectivity.activeNetwork) }.getOrNull() ?: return null
        val address = link.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        return address.hostAddress?.substringBeforeLast('.')
    }

    private fun probe(host: String): Pair<Camera, String>? {
        val api = get("http://$host:$API_PORT/api", connectTimeout = 400)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (!api.has("version")) return null
        val rtspPort = api.optJSONObject("rtsp")?.optString("listen")?.substringAfterLast(':')?.toIntOrNull() ?: 8554
        val streams = get("http://$host:$API_PORT/api/streams", connectTimeout = 1500)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.keys()?.asSequence()?.toSet().orEmpty()
        val main = streams.filterNot { it.endsWith("_sd") }.sorted().firstOrNull() ?: return null
        val light = "${main}_sd".takeIf { it in streams } ?: main
        val base = "rtsp://$host:$rtspPort"
        // No `?video` on the address to leave the sound out: Media3 puts the query after the track too
        // (`/trackID=0?video`), and go2rtc answers that SETUP with 400. The player drops the sound instead.
        return Camera(displayName(main), "$base/$light", "$base/$main") to host
    }

    /** "living_room" reads as "Living room". */
    private fun displayName(stream: String) =
        stream.replace('_', ' ').replace('-', ' ').trim().lowercase().replaceFirstChar { it.uppercase() }

    private fun get(url: String, connectTimeout: Int): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeout
        connection.readTimeout = 3000
        try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
