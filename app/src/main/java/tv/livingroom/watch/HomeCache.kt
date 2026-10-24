package tv.livingroom.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last Home, kept on disk per profile, so the next start shows it at once instead of a loading
 * screen while seven requests go to the server. The fresh Home replaces it as soon as it arrives.
 */
class HomeCache(context: Context) {
    private val dir = File(context.filesDir, "home").apply { mkdirs() }

    private fun file(userId: String) = File(dir, "${userId.filter { it.isLetterOrDigit() }}.json")

    fun load(userId: String): HomeFeed? = runCatching {
        val json = JSONObject(file(userId).readText())
        if (json.optInt("version") != VERSION) return null
        val shelves = json.optJSONArray("shelves") ?: return null
        val list = List(shelves.length()) { i ->
            val shelf = shelves.getJSONObject(i)
            val movies = shelf.getJSONArray("movies")
            Shelf(
                title = shelf.getString("title"),
                movies = List(movies.length()) { parseMovie(movies.getJSONObject(it)) },
                genre = shelf.optString("genre").takeIf { it.isNotBlank() }
            )
        }
        val heroId = json.optString("hero")
        val hero = list.firstNotNullOfOrNull { shelf -> shelf.movies.find { it.id == heroId } }
        HomeFeed(hero, json.optBoolean("resumeFirst"), list)
    }.getOrNull()

    fun save(userId: String, feed: HomeFeed) {
        runCatching {
            val shelves = JSONArray()
            feed.shelves.forEach { shelf ->
                shelves.put(
                    JSONObject()
                        .put("title", shelf.title)
                        .put("genre", shelf.genre.orEmpty())
                        .put("movies", JSONArray().apply { shelf.movies.forEach { put(movieToJson(it)) } })
                )
            }
            val json = JSONObject()
                .put("version", VERSION)
                .put("hero", feed.hero?.id.orEmpty())
                .put("resumeFirst", feed.resumeFirst)
                .put("shelves", shelves)
            // Written aside and moved into place, so a crash mid-write never leaves half a file.
            val target = file(userId)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(json.toString())
            temp.renameTo(target)
        }
    }

    fun clear(userId: String) {
        file(userId).delete()
    }

    private companion object {
        const val VERSION = 1
    }
}
