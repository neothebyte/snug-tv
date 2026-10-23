package tv.livingroom.home

import android.content.Context
import android.net.Uri

/** A title Snug offers for the top shelf: something to keep watching, or new in the library. */
data class ShelfItem(
    val id: String,
    val title: String,
    val subtitle: String,
    /** A wide backdrop from the Jellyfin server. */
    val image: String?,
    /** How much has been watched, 0 to 1; 0 for titles not started. */
    val progress: Float
)

/**
 * Reads Snug's top shelf (content://tv.livingroom.watch.shelf/items). Only apps signed with Snug's key may read it.
 * Returns nothing while Snug doesn't offer one yet, isn't signed in, or the server can't be reached.
 */
object SnugShelf {
    private val ITEMS: Uri = Uri.parse("content://tv.livingroom.watch.shelf/items")

    fun load(context: Context): List<ShelfItem> = runCatching {
        context.contentResolver.query(ITEMS, null, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val title = cursor.getColumnIndexOrThrow("title")
            val subtitle = cursor.getColumnIndex("subtitle")
            val image = cursor.getColumnIndex("image")
            val progress = cursor.getColumnIndex("progress")
            buildList {
                while (cursor.moveToNext() && size < 8) {
                    add(
                        ShelfItem(
                            id = cursor.getString(id),
                            title = cursor.getString(title).orEmpty(),
                            subtitle = if (subtitle >= 0) cursor.getString(subtitle).orEmpty() else "",
                            image = if (image >= 0) cursor.getString(image) else null,
                            progress = if (progress >= 0) cursor.getFloat(progress).coerceIn(0f, 1f) else 0f
                        )
                    )
                }
            }
        }
    }.getOrNull().orEmpty()
}
