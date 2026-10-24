package tv.livingroom.watch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Snug's top shelf for the home screen (Snug Home): what to keep watching, then the newest titles.
 * Read-only, and only by apps signed with Snug's own key (see the manifest). Nothing when not signed in.
 *
 * Answers from the copy Snug keeps on disk, which Snug rewrites whenever its own Home loads, so the
 * shelf appears at once. When that copy is more than a minute old it is refreshed in the background
 * and Snug Home is told to read it again.
 */
class ShelfProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val app = context?.applicationContext as? LivingRoomApp ?: return cursor
        if (!app.store.signedIn) return cursor
        val cached = ShelfCache.read(app)
        val rows = if (cached == null) {
            fetch(app).also { ShelfCache.write(app, it) }
        } else {
            if (cached.ageMs > REFRESH_AFTER_MS) {
                app.background.launch {
                    val fresh = runCatching { fetch(app) }.getOrNull() ?: return@launch
                    if (fresh.isNotEmpty() && fresh != cached.rows) ShelfCache.write(app, fresh) else ShelfCache.touch(app)
                }
            }
            cached.rows
        }
        for (row in rows) {
            cursor.addRow(arrayOf(row.id, row.title, row.subtitle, row.image, row.progress, row.kind, row.logo, row.hash))
        }
        return cursor
    }

    /** Asks the server, all three lists at once. */
    private fun fetch(app: LivingRoomApp): List<ShelfRow> = runCatching {
        val api = app.api
        runBlocking(Dispatchers.IO) {
            coroutineScope {
                val resume = async { api.resume(6) }
                val nextUp = async { runCatching { api.nextUp(4) }.getOrDefault(emptyList()) }
                val recent = async {
                    api.movies(limit = 6, sortBy = "DateCreated,SortName", sortOrder = "Descending", fields = JellyfinClient.HOME_FIELDS, countTotal = false).items
                }
                shelfRows(api, resume.await() + nextUp.await(), recent.await())
            }
        }
    }.getOrDefault(emptyList())

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.tv.livingroom.shelf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0

    companion object {
        val ITEMS: Uri = Uri.parse("content://tv.livingroom.watch.shelf/items")
        private val COLUMNS = arrayOf("id", "title", "subtitle", "image", "progress", "kind", "logo", "hash")
        private const val REFRESH_AFTER_MS = 60_000L
    }
}

/** One card on Snug Home's shelf. [kind] is "continue" or "new", which Snug Home shows as the row's name. */
data class ShelfRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val progress: Float,
    val kind: String,
    val logo: String?,
    val hash: String?
)

/** Up to eight cards: what's being watched first, then what's new, without repeats. */
fun shelfRows(api: Library, watching: List<Movie>, recent: List<Movie>): List<ShelfRow> {
    val seen = HashSet<String>()
    val picked = watching.filter { seen.add(it.id) }.map { it to "continue" } + recent.filter { seen.add(it.id) }.map { it to "new" }
    return picked.take(8).map { (item, kind) ->
        val subtitle = when {
            item.isEpisode -> item.cardTitle
            item.year != null -> item.year.toString()
            else -> ""
        }
        ShelfRow(item.id, item.headline, subtitle, api.backdropUrl(item, 1280), item.progress, kind, api.logoUrl(item, 600), item.backdropHash)
    }
}

/** The shelf as last read, on disk, so Snug Home gets an answer without waiting for the server. */
object ShelfCache {
    class Cached(val rows: List<ShelfRow>, val ageMs: Long)

    private fun file(context: Context) = File(context.filesDir, "shelf.json")

    fun read(context: Context): Cached? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val array = JSONArray(f.readText())
        val rows = List(array.length()) { i ->
            val row = array.getJSONObject(i)
            ShelfRow(
                id = row.getString("id"),
                title = row.optString("title"),
                subtitle = row.optString("subtitle"),
                image = row.optString("image").takeIf { it.isNotBlank() },
                progress = row.optDouble("progress", 0.0).toFloat(),
                kind = row.optString("kind", "continue"),
                logo = row.optString("logo").takeIf { it.isNotBlank() },
                hash = row.optString("hash").takeIf { it.isNotBlank() }
            )
        }
        Cached(rows, System.currentTimeMillis() - f.lastModified())
    }.getOrNull()

    /** Saves [rows] and tells Snug Home (if it's watching) to read the shelf again. */
    fun write(context: Context, rows: List<ShelfRow>) {
        runCatching {
            val array = JSONArray()
            rows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("id", row.id)
                        .put("title", row.title)
                        .put("subtitle", row.subtitle)
                        .put("image", row.image.orEmpty())
                        .put("progress", row.progress.toDouble())
                        .put("kind", row.kind)
                        .put("logo", row.logo.orEmpty())
                        .put("hash", row.hash.orEmpty())
                )
            }
            val target = file(context)
            val temp = File(context.filesDir, "shelf.json.tmp")
            temp.writeText(array.toString())
            temp.renameTo(target)
            context.contentResolver.notifyChange(ShelfProvider.ITEMS, null)
        }
    }

    /** Marks the copy on disk as just checked. */
    fun touch(context: Context) {
        runCatching { file(context).setLastModified(System.currentTimeMillis()) }
    }

    fun clear(context: Context) {
        file(context).delete()
        runCatching { context.contentResolver.notifyChange(ShelfProvider.ITEMS, null) }
    }
}
