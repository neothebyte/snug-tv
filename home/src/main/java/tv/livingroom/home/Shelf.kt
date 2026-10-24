package tv.livingroom.home

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A title Snug offers for the top shelf: something to keep watching, or new in the library. */
data class ShelfItem(
    val id: String,
    val title: String,
    val subtitle: String,
    /** A wide backdrop: from the Jellyfin server, or from TMDB for films on Snug's USB drive. */
    val image: String?,
    /** How much has been watched, 0 to 1; 0 for titles not started. */
    val progress: Float,
    /** "continue" or "new"; older Snug versions don't say, and everything counts as "continue". */
    val kind: String = "continue",
    /** The title's logo, when the library has one. */
    val logo: String? = null,
    /** BlurHash of [image], painted while it loads. */
    val hash: String? = null
)

/**
 * Reads Snug's top shelf (content://tv.livingroom.watch.shelf/items). Only apps signed with Snug's key may read it.
 * Returns nothing while Snug doesn't offer one yet, isn't signed in, or the server can't be reached.
 */
object SnugShelf {
    private val ITEMS: Uri = Uri.parse("content://tv.livingroom.watch.shelf/items")

    fun load(context: Context): List<ShelfItem> = runCatching {
        context.contentResolver.query(ITEMS, null, null, null, null)?.use { cursor -> read(cursor) }
    }.getOrNull().orEmpty().also { save(context, it) }

    private fun read(cursor: Cursor): List<ShelfItem> {
        val id = cursor.getColumnIndexOrThrow("id")
        val title = cursor.getColumnIndexOrThrow("title")
        val subtitle = cursor.getColumnIndex("subtitle")
        val image = cursor.getColumnIndex("image")
        val progress = cursor.getColumnIndex("progress")
        val kind = cursor.getColumnIndex("kind")
        val logo = cursor.getColumnIndex("logo")
        val hash = cursor.getColumnIndex("hash")
        fun Cursor.text(column: Int): String? = if (column >= 0) getString(column)?.takeIf { it.isNotBlank() } else null
        return buildList {
            while (cursor.moveToNext() && size < 8) {
                add(
                    ShelfItem(
                        id = cursor.getString(id),
                        title = cursor.getString(title).orEmpty(),
                        subtitle = cursor.text(subtitle).orEmpty(),
                        image = cursor.text(image),
                        progress = if (progress >= 0) cursor.getFloat(progress).coerceIn(0f, 1f) else 0f,
                        kind = cursor.text(kind) ?: "continue",
                        logo = cursor.text(logo),
                        hash = cursor.text(hash)
                    )
                )
            }
        }
    }

    /** Calls [onChange] whenever Snug says its shelf has changed, until the returned stop function runs. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val registered = runCatching { context.contentResolver.registerContentObserver(ITEMS, false, observer) }.isSuccess
        return { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    // The last shelf read, so the box's first frame after a restart already has it.
    private fun file(context: Context) = File(context.filesDir, "shelf.json")

    fun cached(context: Context): List<ShelfItem> = runCatching {
        val array = JSONArray(file(context).readText())
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ShelfItem(
                id = item.getString("id"),
                title = item.optString("title"),
                subtitle = item.optString("subtitle"),
                image = item.optString("image").takeIf { it.isNotBlank() },
                progress = item.optDouble("progress", 0.0).toFloat(),
                kind = item.optString("kind", "continue"),
                logo = item.optString("logo").takeIf { it.isNotBlank() },
                hash = item.optString("hash").takeIf { it.isNotBlank() }
            )
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<ShelfItem>) {
        runCatching {
            val array = JSONArray()
            items.forEach { item ->
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("subtitle", item.subtitle)
                        .put("image", item.image.orEmpty())
                        .put("progress", item.progress.toDouble())
                        .put("kind", item.kind)
                        .put("logo", item.logo.orEmpty())
                        .put("hash", item.hash.orEmpty())
                )
            }
            file(context).writeText(array.toString())
        }
    }
}
