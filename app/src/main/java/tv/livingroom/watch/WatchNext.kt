package tv.livingroom.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/**
 * Puts Continue watching on the TV's own home screen (Google TV and Android TV's "Play next" row),
 * for anyone who doesn't use Snug Home. Each card opens the title in Snug, like Snug Home's shelf.
 * The box keeps the cards; this only adds, updates and removes Snug's own.
 */
object WatchNext {
    private const val PREFS = "watch-next"
    private const val MAX = 10
    // TvContract's values (PreviewProgramColumns), which Kotlin can't reach through the compat classes.
    private const val TYPE_MOVIE = 0
    private const val TYPE_TV_EPISODE = 3
    private const val ASPECT_RATIO_16_9 = 0

    /** Makes the box's row match [items]: the first ten of Snug's Continue watching. Quietly does nothing where unsupported. */
    fun sync(context: Context, api: Library, items: List<Movie>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val helper = PreviewChannelHelper(context)
            val wanted = items.take(MAX)
            val published = prefs.all.mapNotNull { (id, value) -> (value as? Long)?.let { id to it } }.toMap()
            val edit = prefs.edit()
            // Gone from Continue watching (finished, or hidden): take the card away too.
            published.filterKeys { id -> wanted.none { it.id == id } }.forEach { (id, programId) ->
                remove(context, programId)
                edit.remove(id)
            }
            wanted.forEach { item ->
                val program = program(context, api, item)
                val existing = published[item.id]
                if (existing != null) {
                    runCatching { helper.updateWatchNextProgram(program, existing) }
                } else {
                    val id = helper.publishWatchNextProgram(program)
                    if (id > 0) edit.putLong(item.id, id)
                }
            }
            edit.apply()
        }
    }

    /** Removes every Snug card, for signing out. */
    fun clear(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.all.values.filterIsInstance<Long>().forEach { remove(context, it) }
            prefs.edit().clear().apply()
        }
    }

    @SuppressLint("RestrictedApi")
    private fun remove(context: Context, programId: Long) {
        runCatching { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(programId), null, null) }
    }

    @SuppressLint("RestrictedApi")
    private fun program(context: Context, api: Library, item: Movie): WatchNextProgram {
        val open = Intent(OPEN_ITEM)
            .setPackage(context.packageName)
            .putExtra(EXTRA_ITEM_ID, item.id)
        val started = item.positionTicks > 0L
        val builder = WatchNextProgram.Builder()
            .setType(if (item.isEpisode) TYPE_TV_EPISODE else TYPE_MOVIE)
            .setWatchNextType(
                if (started) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            )
            .setLastEngagementTimeUtcMillis(item.lastPlayed.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setTitle(item.headline)
            .setDescription(item.overview)
            .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
            .setInternalProviderId(item.id)
            .setDurationMillis((item.runtimeTicks / 10_000L).toInt())
            .setLastPlaybackPositionMillis((item.positionTicks / 10_000L).toInt())
        if (item.isEpisode) {
            builder.setEpisodeTitle(item.name)
            item.season?.let { builder.setSeasonNumber(it) }
            item.episode?.let { builder.setEpisodeNumber(it) }
        }
        // The TV's home screen fetches the picture itself, so only a web address will do (not a file on the drive).
        val art = (api.backdropUrl(item, 640) ?: api.imageUrl(item.id, "Primary", item.primaryTag, 640))?.takeIf { it.startsWith("http") }
        if (art != null) {
            builder.setPosterArtUri(Uri.parse(art))
                .setPosterArtAspectRatio(ASPECT_RATIO_16_9)
        }
        return builder.build()
    }

    const val OPEN_ITEM = "tv.livingroom.watch.OPEN_ITEM"
    const val EXTRA_ITEM_ID = "itemId"
}
