package tv.livingroom.watch

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** TMDB said no to the key: wrong, or switched off. */
class TmdbKeyException : IOException("TMDB didn't accept the API key")

/**
 * Looks films up on The Movie Database (themoviedb.org) for Drive mode: names, posters, backdrops,
 * logos, the plot, cast and genres. [key] is a v3 API key, or a v4 read access token (they start "eyJ").
 */
class Tmdb(private val http: OkHttpClient, private val key: () -> String) {
    /** The best match for [title] from [year], with its details, in the short form Drive mode keeps. Null when nothing matches. */
    fun find(title: String, year: Int?): JSONObject? {
        val results = search(title, year).ifEmpty { if (year != null) search(title, null) else emptyList() }
        val pick = results.firstOrNull { year != null && it.optString("release_date").take(4) == year.toString() }
            ?: results.firstOrNull { year != null && it.optString("release_date").take(4).toIntOrNull()?.let { found -> kotlin.math.abs(found - year) <= 1 } == true }
            ?: results.firstOrNull()
            ?: return null
        return compact(details(pick.getInt("id")))
    }

    private fun search(title: String, year: Int?): List<JSONObject> {
        val query = "query=${encode(title)}&include_adult=false&language=en-US" + (year?.let { "&year=$it" } ?: "")
        val array = get("/search/movie", query).optJSONArray("results") ?: JSONArray()
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun details(id: Int): JSONObject =
        get("/movie/$id", "append_to_response=credits,release_dates,images&include_image_language=en,null&language=en-US")

    private fun get(path: String, query: String, retried: Boolean = false): JSONObject {
        val secret = key().trim()
        val bearer = secret.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3$path?$query" + if (bearer) "" else "&api_key=${encode(secret)}"
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (bearer) request.header("Authorization", "Bearer $secret")
        http.newCall(request.build()).execute().use { response ->
            if (response.code == 401) throw TmdbKeyException()
            if (response.code == 429 && !retried) {
                // Too many at once: TMDB asks for a short pause.
                Thread.sleep(1_500)
                return get(path, query, retried = true)
            }
            if (!response.isSuccessful) throw IOException("TMDB answered ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    /** Just what Snug shows, so the copy kept on the box stays small. */
    private fun compact(movie: JSONObject): JSONObject {
        val credits = movie.optJSONObject("credits")
        val castArray = credits?.optJSONArray("cast") ?: JSONArray()
        val crewArray = credits?.optJSONArray("crew") ?: JSONArray()
        val cast = List(castArray.length()) { castArray.getJSONObject(it) }.sortedBy { it.optInt("order") }.take(14).map { it.optString("name") }
        val crew = List(crewArray.length()) { crewArray.getJSONObject(it) }
        val directors = crew.filter { it.optString("job") == "Director" }.map { it.optString("name") }.distinct()
        val writers = crew.filter { it.optString("job") in setOf("Screenplay", "Writer", "Story") }.map { it.optString("name") }.distinct().take(3)
        val genres = movie.optJSONArray("genres").names()
        val studios = movie.optJSONArray("production_companies").names().take(3)
        val votes = movie.optInt("vote_count")
        val rating = movie.optDouble("vote_average", 0.0).takeIf { votes >= 10 && it > 0.0 }
        return JSONObject()
            .put("tmdb", movie.optInt("id"))
            .put("title", movie.optString("title"))
            .put("overview", movie.optString("overview"))
            .put("tagline", movie.optString("tagline"))
            .put("year", movie.optString("release_date").take(4).toIntOrNull() ?: 0)
            .put("runtime", movie.optInt("runtime"))
            .put("rating", rating ?: 0.0)
            .put("cert", certification(movie))
            .put("poster", movie.optString("poster_path").takeIf { it.startsWith("/") }.orEmpty())
            .put("backdrop", movie.optString("backdrop_path").takeIf { it.startsWith("/") }.orEmpty())
            .put("logo", logo(movie))
            .put("genres", JSONArray(genres))
            .put("studios", JSONArray(studios))
            .put("cast", JSONArray(cast))
            .put("directors", JSONArray(directors))
            .put("writers", JSONArray(writers))
            .put("at", System.currentTimeMillis())
    }

    /** The US age rating (PG-13, R), which is what Jellyfin shows by default too. */
    private fun certification(movie: JSONObject): String {
        val countries = movie.optJSONObject("release_dates")?.optJSONArray("results") ?: return ""
        for (i in 0 until countries.length()) {
            val country = countries.optJSONObject(i) ?: continue
            if (country.optString("iso_3166_1") != "US") continue
            val dates = country.optJSONArray("release_dates") ?: continue
            for (j in 0 until dates.length()) {
                val rating = dates.optJSONObject(j)?.optString("certification").orEmpty()
                if (rating.isNotBlank()) return rating
            }
        }
        return ""
    }

    /** The best-voted English (or wordless) title logo. */
    private fun logo(movie: JSONObject): String {
        val logos = movie.optJSONObject("images")?.optJSONArray("logos") ?: return ""
        val all = List(logos.length()) { logos.getJSONObject(it) }.filter { it.optString("file_path").startsWith("/") }
        val english = all.filter { it.optString("iso_639_1") == "en" }.ifEmpty { all }
        return english.maxByOrNull { it.optDouble("vote_average", 0.0) }?.optString("file_path").orEmpty()
    }

    private fun JSONArray?.names(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optJSONObject(it)?.optString("name").orEmpty() }.filter { it.isNotBlank() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
