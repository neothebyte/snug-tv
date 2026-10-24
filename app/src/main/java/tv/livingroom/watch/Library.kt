package tv.livingroom.watch

import org.json.JSONObject

/**
 * Where the movies come from. Snug has two sources: a Jellyfin server ([JellyfinClient]) and
 * Drive mode ([DriveLibrary]), which plays films straight off a USB drive plugged into the box.
 * Every screen and the player talk to whichever one is chosen, through this.
 *
 * The shapes are Jellyfin's (sort keys, filters, image tags), since that is what Snug was built on;
 * the drive answers the same questions from what it has scanned.
 */
interface Library {
    /** True for the USB drive: files play from the box itself, with no server to convert them. */
    val local: Boolean get() = false

    /** One page of movies, filtered and sorted. */
    fun movies(
        start: Int = 0,
        limit: Int,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        filters: String? = null,
        genre: String? = null,
        person: String? = null,
        personType: String? = null,
        personIds: List<String> = emptyList(),
        search: String? = null,
        fields: String = JellyfinClient.GRID_FIELDS,
        countTotal: Boolean = true,
        types: String = "Movie",
        nameStartsWith: String? = null,
        nameLessThan: String? = null
    ): MoviePage

    /** How many titles come before the first one starting with [letter] in A to Z order, or null when none does. */
    fun countBefore(letter: String, types: String, filters: String?): Int?

    fun genres(): List<String>

    /** Ids of people whose name matches [search], for [movies]' personIds. */
    fun findPeople(search: String, limit: Int): List<String>

    fun similar(id: String, limit: Int): List<Movie>

    /** Movies and episodes that were stopped part-way, most recent first. */
    fun resume(limit: Int, fields: String = JellyfinClient.HOME_FIELDS): List<Movie>

    /** The next unwatched episode of each show being watched, or of one show when [seriesId] is given. */
    fun nextUp(limit: Int, seriesId: String? = null, fields: String = JellyfinClient.HOME_FIELDS): List<Movie>

    fun seasons(seriesId: String): List<Movie>

    fun episodes(seriesId: String, seasonId: String): List<Movie>

    fun episodeAfter(episode: Movie): Movie?

    fun segments(itemId: String): List<MediaSegment>

    fun item(id: String): Movie

    fun imageUrl(itemId: String, kind: String, tag: String?, width: Int): String?

    /** A poster for [item]: episodes use their show's, since their own picture is a wide still. */
    fun posterUrl(item: Movie, width: Int = 360): String? =
        if (item.isEpisode && item.seriesId != null && item.seriesPrimaryTag != null) {
            imageUrl(item.seriesId, "Primary", item.seriesPrimaryTag, width)
        } else {
            imageUrl(item.id, "Primary", item.primaryTag, width)
        }

    fun backdropUrl(item: Movie, width: Int): String? = imageUrl(item.backdropOwner ?: item.id, "Backdrop", item.backdropTag, width)

    /** The title's logo, for the big title on Home and the detail pages, or null when it has none. */
    fun logoUrl(item: Movie, width: Int = 640): String? = imageUrl(item.logoOwner ?: item.id, "Logo", item.logoTag, width)

    fun trickplaySheetUrl(itemId: String, info: TrickplayInfo, sheet: Int): String

    fun localTrailers(itemId: String): List<Movie>

    fun playback(itemId: String, startTicks: Long, audioIndex: Int?, subtitleIndex: Int?, burn: Boolean, mode: PlayMode): PlaybackPlan

    fun streamUrl(itemId: String, plan: PlaybackPlan): String

    fun subtitleVtt(itemId: String, sourceId: String, index: Int): String

    fun report(path: String, itemId: String, plan: PlaybackPlan, ticks: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?)

    /** How long the file really is, once the player knows. Only the drive keeps it. */
    fun noteDuration(itemId: String, durationMs: Long) {}

    fun stopEncoding(playSessionId: String)

    fun toggleFavorite(itemId: String, on: Boolean)

    fun togglePlayed(itemId: String, on: Boolean)

    fun hiddenShows(): Set<String>

    fun setShowHidden(seriesId: String, hide: Boolean): Set<String>

    fun themeSongUrl(ownerId: String): String?

    fun remoteSubtitles(itemId: String, language: String): List<RemoteSubtitle>

    fun downloadSubtitle(itemId: String, subtitleId: String)

    fun pluginConfig(): JSONObject?

    fun savePluginConfig(username: String, password: String)
}
