package tv.livingroom.watch

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Snug's top shelf for the home screen (Snug Home): what to keep watching, then the newest titles.
 * Read-only, and only by apps signed with Snug's own key (see the manifest). Nothing when not signed in.
 */
class ShelfProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val app = context?.applicationContext as? LivingRoomApp ?: return cursor
        if (!app.store.signedIn) return cursor
        val api = app.api
        val items = runCatching {
            val resume = api.resume(6)
            val nextUp = runCatching { api.nextUp(4) }.getOrDefault(emptyList())
            val recent = api.movies(limit = 6, sortBy = "DateCreated,SortName", sortOrder = "Descending", fields = JellyfinClient.HOME_FIELDS, countTotal = false).items
            (resume + nextUp + recent).distinctBy { it.id }.take(8)
        }.getOrDefault(emptyList())
        for (item in items) {
            val subtitle = when {
                item.isEpisode -> item.cardTitle
                item.year != null -> item.year.toString()
                else -> ""
            }
            cursor.addRow(arrayOf(item.id, item.headline, subtitle, api.backdropUrl(item, 1280), item.progress))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.tv.livingroom.shelf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0

    private companion object {
        val COLUMNS = arrayOf("id", "title", "subtitle", "image", "progress")
    }
}
