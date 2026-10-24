package tv.livingroom.watch

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class SubLang(val code: String, val label: String, val hints: List<String>)

val SUB_LANGS = listOf(
    SubLang("eng", "English", listOf("en", "eng", "english")),
    SubLang("vie", "Vietnamese", listOf("vi", "vie", "vietnamese")),
    SubLang("spa", "Spanish", listOf("es", "spa", "spanish")),
    SubLang("fre", "French", listOf("fr", "fre", "fra", "french")),
    SubLang("ger", "German", listOf("de", "ger", "deu", "german")),
    SubLang("por", "Portuguese", listOf("pt", "por", "portuguese")),
    SubLang("ita", "Italian", listOf("it", "ita", "italian")),
    SubLang("jpn", "Japanese", listOf("ja", "jpn", "japanese")),
    SubLang("kor", "Korean", listOf("ko", "kor", "korean")),
    SubLang("chi", "Chinese", listOf("zh", "chi", "zho", "chinese")),
    SubLang("tha", "Thai", listOf("th", "tha", "thai")),
    SubLang("ind", "Indonesian", listOf("id", "ind", "indonesian")),
    SubLang("rus", "Russian", listOf("ru", "rus", "russian"))
)

data class QuickConnect(val secret: String, val code: String)

data class Profile(
    val id: String,
    val name: String,
    val hasPassword: Boolean,
    val hasImage: Boolean
)

data class PersonCredit(val name: String, val type: String)

data class ChapterMark(val name: String, val startTicks: Long)

/** A stretch of an episode such as its "Intro" or "Outro" (credits), in milliseconds. */
data class MediaSegment(val type: String, val startMs: Long, val endMs: Long)

data class MediaStreamInfo(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String,
    val displayLanguage: String,
    val title: String,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean,
    val channels: Int,
    val frameRate: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    /** Jellyfin's name for the picture's range: SDR, HDR10, HDR10Plus, HLG, DOVI, DOVIWithHDR10 and so on. */
    val videoRangeType: String = "",
    val profile: String = "",
    val displayTitle: String = ""
) {
    fun isTextSubtitle(): Boolean {
        if (type != "Subtitle") return false
        return TEXT_SUBS.contains(codec.ifBlank { "subrip" }.lowercase(Locale.US))
    }

    fun languageName(): String {
        val code = language.trim()
        if (code.isNotEmpty() && !code.equals("und", true) && !code.equals("undefined", true)) {
            val name = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH) }.getOrNull().orEmpty()
            if (name.isNotBlank() && !name.equals(code, true)) return name
            return code.uppercase(Locale.US)
        }
        return ""
    }

    /** True for a subtitle track with no language and no real name in the file. */
    fun isUnnamedSubtitle(): Boolean = type == "Subtitle" && languageName().isEmpty() && namedTitle() == null

    private fun namedTitle(): String? = title.trim().takeIf { it.isNotEmpty() && !GENERATED_TITLE.containsMatchIn(it) }

    fun label(): String {
        val language = languageName()
        val named = namedTitle()
        val first = when {
            named != null && language.isNotEmpty() && !named.contains(language, true) -> "$language · $named"
            named != null -> named
            language.isNotEmpty() -> language
            else -> if (type == "Subtitle") "Unknown language" else "Audio track"
        }
        val extras = buildList {
            if (type == "Subtitle") {
                if (isForced && !first.contains("forced", true)) add("Forced")
                add(if (isTextSubtitle()) "Text" else "Image")
            }
        }
        return (listOf(first) + extras).joinToString(" · ")
    }
}

data class Movie(
    val id: String,
    val name: String,
    val sortName: String,
    val year: Int?,
    val overview: String,
    val tagline: String,
    val runtimeTicks: Long,
    val officialRating: String,
    val communityRating: Double?,
    val genres: List<String>,
    val people: List<PersonCredit>,
    val studios: List<String>,
    val dateCreated: String,
    val primaryTag: String?,
    val backdropTag: String?,
    val played: Boolean,
    val favorite: Boolean,
    val positionTicks: Long,
    val lastPlayed: Long,
    val chapters: List<ChapterMark>,
    val streams: List<MediaStreamInfo>,
    /** Jellyfin's item type: Movie, Series, Season or Episode. */
    val type: String = "Movie",
    val seriesId: String? = null,
    val seriesName: String = "",
    val seriesPrimaryTag: String? = null,
    val seasonId: String? = null,
    /** Season number of an episode, or the number of a season. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Where the backdrop comes from when the item has none of its own (an episode borrows its show's). */
    val backdropOwner: String? = null,
    val unplayedCount: Int = 0,
    val endYear: Int? = null,
    val status: String = "",
    /** BlurHash of the item's own Primary image, painted while the real one loads. */
    val primaryHash: String? = null,
    val seriesPrimaryHash: String? = null,
    val backdropHash: String? = null,
    /** The title's logo (a transparent picture of its name), and whose it is: an episode borrows its show's. */
    val logoTag: String? = null,
    val logoOwner: String? = null,
    /** Trailers on YouTube or elsewhere, as links. Local trailer files are looked up separately. */
    val remoteTrailers: List<String> = emptyList(),
    val localTrailerCount: Int = 0,
    /** Jellyfin's trickplay pictures (10.9+), for seeking. Only on the full item. */
    val trickplay: TrickplayInfo? = null
) {
    val isSeries: Boolean get() = type == "Series"
    val isEpisode: Boolean get() = type == "Episode"
    val sortLabel: String get() = sortName.ifBlank { name }

    /** "S2:E5", or blank for anything that isn't a numbered episode. */
    val episodeCode: String
        get() = if (isEpisode && episode != null) "S${season ?: 0}:E$episode" else ""

    /** How the item is named on a poster: episodes lead with their number. */
    val cardTitle: String
        get() = if (isEpisode) listOf(episodeCode, name).filter { it.isNotBlank() }.joinToString(" · ") else name

    /** The name to show in big type: an episode is known by its show. */
    val headline: String
        get() = if (isEpisode && seriesName.isNotBlank()) seriesName else name
    val progress: Float
        get() = if (runtimeTicks > 0L && positionTicks > 0L) (positionTicks.toFloat() / runtimeTicks).coerceIn(0f, 1f) else 0f

    /** The BlurHash for the picture [JellyfinClient.posterUrl] picks. */
    val posterHash: String?
        get() = if (isEpisode && seriesId != null && seriesPrimaryTag != null) seriesPrimaryHash else primaryHash

    /** Short labels for what the file is: 4K, Dolby Vision, HDR10, Atmos, 5.1. Empty until the full item has loaded. */
    val formatBadges: List<String>
        get() {
            val video = streams.firstOrNull { it.type == "Video" }
            val audio = streams.filter { it.type == "Audio" }.maxByOrNull { it.channels }
            return buildList {
                if (video != null) {
                    when {
                        video.width >= 3200 || video.height >= 1800 -> add("4K")
                        video.width >= 1800 || video.height >= 1000 -> add("HD")
                    }
                    val range = video.videoRangeType
                    when {
                        range.startsWith("DOVI", ignoreCase = true) -> add("Dolby Vision")
                        range.equals("HDR10Plus", ignoreCase = true) -> add("HDR10+")
                        range.equals("HDR10", ignoreCase = true) -> add("HDR10")
                        range.equals("HLG", ignoreCase = true) -> add("HLG")
                    }
                }
                if (audio != null) {
                    val atmos = "atmos" in "${audio.profile} ${audio.displayTitle} ${audio.title}".lowercase(Locale.US)
                    if (atmos) add("Atmos")
                    when (audio.channels) {
                        8 -> add("7.1")
                        6 -> add("5.1")
                    }
                }
            }
        }
}

/**
 * One set of Jellyfin trickplay pictures: thumbnails every [intervalMs], packed [tileWidth] x [tileHeight]
 * to a sheet at /Videos/{id}/Trickplay/{width}/{sheet}.jpg.
 */
data class TrickplayInfo(
    val sourceId: String,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val count: Int,
    val intervalMs: Long
) {
    val perSheet: Int get() = (tileWidth * tileHeight).coerceAtLeast(1)
}

data class MediaSourceInfo(
    val id: String,
    val container: String,
    val directPlay: Boolean,
    val directStream: Boolean,
    val transcodingUrl: String?,
    val defaultAudioIndex: Int?,
    val streams: List<MediaStreamInfo>
)

/**
 * How much work the server does, from least to most. Playback starts at Direct and only
 * steps down when the TV can't handle the file as it is.
 */
enum class PlayMode {
    /** The TV reads the file as-is. */
    Direct,
    /** The server repackages the file over HLS without touching picture or sound. */
    Remux,
    /** Picture copied untouched; only the sound is converted. */
    RemuxAudio,
    /** The picture is re-encoded. Slow on the Pi, so it is the last resort. */
    Transcode
}

data class PlaybackPlan(
    val playSessionId: String,
    val source: MediaSourceInfo,
    val hls: Boolean,
    val burn: Boolean,
    val mode: PlayMode
)

data class RemoteSubtitle(
    val id: String,
    val name: String,
    val hearing: Boolean,
    val hashMatch: Boolean,
    val forced: Boolean,
    val downloads: Int
) {
    val rank: Int
        get() = (if (hashMatch) 1_000_000 else 0) + (if (hearing) 0 else 1_000) + downloads

    fun label(): String {
        val bits = mutableListOf(name.ifBlank { "Subtitle" })
        if (hearing) bits += "HI"
        if (hashMatch) bits += "Exact match"
        if (forced) bits += "Forced"
        if (downloads > 0) bits += "$downloads downloads"
        return bits.joinToString(" · ")
    }
}

data class Cue(val startMs: Long, val endMs: Long, val text: String)

data class Shelf(val title: String, val movies: List<Movie>, val genre: String? = null)

data class HomeFeed(val hero: Movie?, val resumeFirst: Boolean, val shelves: List<Shelf>)

data class MoviePage(val items: List<Movie>, val total: Int)

/** Everything a show's page shows. Episodes load one season at a time. */
data class SeriesView(
    val series: Movie,
    val seasons: List<Movie>,
    val selected: String?,
    val episodes: Map<String, List<Movie>>,
    /** The episode Play starts: the next one to watch, else the first. */
    val next: Movie?
)

data class GenreStat(val name: String, val count: Int, val cover: Movie?)

private val GENERATED_TITLE = Regex("^(und|undefined)\\b| - (SUBRIP|SRT|PGSSUB|DVDSUB|ASS|SSA|AAC|AC3|EAC3|DTS|TRUEHD|FLAC|OPUS|MP3)\\b", RegexOption.IGNORE_CASE)

private val TEXT_SUBS = setOf("subrip", "srt", "ass", "ssa", "vtt", "webvtt", "mov_text", "text", "subviewer")

fun parseMovie(json: JSONObject): Movie {
    val user = json.optJSONObject("UserData")
    val imageTags = json.optJSONObject("ImageTags")
    val hashes = json.optJSONObject("ImageBlurHashes").toBlurHashes()
    val backdrops = json.optJSONArray("BackdropImageTags")
    val ownBackdrop = backdrops?.optString(0)?.takeIf { it.isNotBlank() }
    val parentBackdrop = json.optJSONArray("ParentBackdropImageTags")?.optString(0)?.takeIf { it.isNotBlank() }
    val type = json.optString("Type").ifBlank { "Movie" }
    val primaryTag = imageTags?.optString("Primary")?.takeIf { it.isNotBlank() }
    val seriesPrimaryTag = json.optString("SeriesPrimaryImageTag").takeIf { it.isNotBlank() }
    val ownLogo = imageTags?.optString("Logo")?.takeIf { it.isNotBlank() }
    val parentLogo = json.optString("ParentLogoImageTag").takeIf { it.isNotBlank() }
    val backdropTag = ownBackdrop ?: parentBackdrop
    return Movie(
        id = json.optString("Id"),
        name = json.optString("Name"),
        sortName = json.optString("SortName"),
        year = json.optInt("ProductionYear").takeIf { json.has("ProductionYear") && !json.isNull("ProductionYear") && it > 0 },
        overview = json.optString("Overview"),
        tagline = json.optJSONArray("Taglines")?.optString(0).orEmpty(),
        runtimeTicks = json.optLong("RunTimeTicks"),
        officialRating = json.optString("OfficialRating"),
        communityRating = if (json.has("CommunityRating") && !json.isNull("CommunityRating")) json.optDouble("CommunityRating") else null,
        genres = json.optJSONArray("Genres").toStrings(),
        people = json.optJSONArray("People").toPeople(),
        studios = json.optJSONArray("Studios").toNames(),
        dateCreated = json.optString("DateCreated"),
        primaryTag = primaryTag,
        backdropTag = backdropTag,
        played = user?.optBoolean("Played") == true,
        favorite = user?.optBoolean("IsFavorite") == true,
        positionTicks = user?.optLong("PlaybackPositionTicks") ?: 0L,
        lastPlayed = parseServerTime(user?.optString("LastPlayedDate").orEmpty()),
        chapters = json.optJSONArray("Chapters").toChapters(),
        streams = json.optJSONArray("MediaStreams").toStreams(),
        type = type,
        seriesId = json.optString("SeriesId").takeIf { it.isNotBlank() },
        seriesName = json.optString("SeriesName"),
        seriesPrimaryTag = seriesPrimaryTag,
        seasonId = json.optString("SeasonId").takeIf { it.isNotBlank() },
        season = json.optIntOrNull(if (type == "Season") "IndexNumber" else "ParentIndexNumber"),
        episode = if (type == "Episode") json.optIntOrNull("IndexNumber") else null,
        backdropOwner = if (ownBackdrop == null && parentBackdrop != null) json.optString("ParentBackdropItemId").takeIf { it.isNotBlank() } else null,
        unplayedCount = user?.optInt("UnplayedItemCount") ?: 0,
        endYear = parseServerTime(json.optString("EndDate")).takeIf { it > 0L }?.let {
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }.get(java.util.Calendar.YEAR)
        },
        status = json.optString("Status"),
        primaryHash = primaryTag?.let { hashes[it] },
        seriesPrimaryHash = seriesPrimaryTag?.let { hashes[it] },
        backdropHash = backdropTag?.let { hashes[it] },
        logoTag = ownLogo ?: parentLogo,
        logoOwner = if (ownLogo == null && parentLogo != null) json.optString("ParentLogoItemId").takeIf { it.isNotBlank() } else null,
        remoteTrailers = json.optJSONArray("RemoteTrailers").toTrailerUrls(),
        localTrailerCount = json.optInt("LocalTrailerCount"),
        trickplay = json.optJSONObject("Trickplay").toTrickplay()
    )
}

/** Picks the trickplay set nearest 320 pixels wide: sharp enough on a TV, small enough to load while seeking. */
private fun JSONObject?.toTrickplay(): TrickplayInfo? {
    if (this == null) return null
    val options = buildList {
        for (source in keys()) {
            val widths = optJSONObject(source) ?: continue
            for (key in widths.keys()) {
                val info = widths.optJSONObject(key) ?: continue
                val width = info.optInt("Width", key.toIntOrNull() ?: 0)
                val interval = info.optLong("Interval")
                val count = info.optInt("ThumbnailCount")
                if (width <= 0 || interval <= 0L || count <= 0) continue
                add(
                    TrickplayInfo(
                        sourceId = source,
                        width = width,
                        height = info.optInt("Height"),
                        tileWidth = info.optInt("TileWidth", 10).coerceAtLeast(1),
                        tileHeight = info.optInt("TileHeight", 10).coerceAtLeast(1),
                        count = count,
                        intervalMs = interval
                    )
                )
            }
        }
    }
    return options.minByOrNull { kotlin.math.abs(it.width - 320) }
}

/** Every BlurHash in Jellyfin's ImageBlurHashes ({"Primary": {tag: hash}, ...}), by image tag. */
private fun JSONObject?.toBlurHashes(): Map<String, String> {
    if (this == null) return emptyMap()
    val found = HashMap<String, String>()
    for (kind in keys()) {
        val byTag = optJSONObject(kind) ?: continue
        for (tag in byTag.keys()) byTag.optString(tag).takeIf { it.isNotBlank() }?.let { found[tag] = it }
    }
    return found
}

private fun JSONArray?.toTrailerUrls(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val url = optJSONObject(i)?.optString("Url").orEmpty()
            if (url.startsWith("http")) add(url)
        }
    }
}

/**
 * The inverse of [parseMovie] for what lists and Home show, so the last Home can be kept on disk and
 * shown at once on the next start. Cast, chapters and streams are left out: they load with the full item.
 */
fun movieToJson(movie: Movie): JSONObject {
    val json = JSONObject()
        .put("Id", movie.id)
        .put("Name", movie.name)
        .put("SortName", movie.sortName)
        .put("Overview", movie.overview)
        .put("RunTimeTicks", movie.runtimeTicks)
        .put("OfficialRating", movie.officialRating)
        .put("DateCreated", movie.dateCreated)
        .put("Type", movie.type)
        .put("Status", movie.status)
        .put("SeriesName", movie.seriesName)
    movie.year?.let { json.put("ProductionYear", it) }
    movie.communityRating?.let { json.put("CommunityRating", it) }
    if (movie.tagline.isNotBlank()) json.put("Taglines", JSONArray().put(movie.tagline))
    json.put("Genres", JSONArray(movie.genres))
    movie.seriesId?.let { json.put("SeriesId", it) }
    movie.seriesPrimaryTag?.let { json.put("SeriesPrimaryImageTag", it) }
    movie.seasonId?.let { json.put("SeasonId", it) }
    movie.season?.let { json.put(if (movie.type == "Season") "IndexNumber" else "ParentIndexNumber", it) }
    movie.episode?.let { json.put("IndexNumber", it) }
    movie.endYear?.let { json.put("EndDate", "$it-06-01T00:00:00") }
    val tags = JSONObject()
    movie.primaryTag?.let { tags.put("Primary", it) }
    val hashes = JSONObject()
    fun hash(tag: String?, value: String?) { if (tag != null && value != null) hashes.put(tag, value) }
    hash(movie.primaryTag, movie.primaryHash)
    hash(movie.seriesPrimaryTag, movie.seriesPrimaryHash)
    hash(movie.backdropTag, movie.backdropHash)
    if (movie.logoTag != null) {
        if (movie.logoOwner == null) tags.put("Logo", movie.logoTag) else json.put("ParentLogoImageTag", movie.logoTag).put("ParentLogoItemId", movie.logoOwner)
    }
    json.put("ImageTags", tags)
    json.put("ImageBlurHashes", JSONObject().put("Any", hashes))
    if (movie.backdropTag != null) {
        if (movie.backdropOwner == null) {
            json.put("BackdropImageTags", JSONArray().put(movie.backdropTag))
        } else {
            json.put("ParentBackdropImageTags", JSONArray().put(movie.backdropTag)).put("ParentBackdropItemId", movie.backdropOwner)
        }
    }
    val user = JSONObject()
        .put("Played", movie.played)
        .put("IsFavorite", movie.favorite)
        .put("PlaybackPositionTicks", movie.positionTicks)
        .put("UnplayedItemCount", movie.unplayedCount)
    if (movie.lastPlayed > 0L) {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        user.put("LastPlayedDate", format.format(java.util.Date(movie.lastPlayed)))
    }
    json.put("UserData", user)
    return json
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

fun parseProfile(json: JSONObject): Profile {
    val tag = json.optString("PrimaryImageTag")
    return Profile(
        id = json.optString("Id"),
        name = json.optString("Name"),
        hasPassword = json.optBoolean("HasPassword"),
        hasImage = tag.isNotBlank()
    )
}

fun parseSource(json: JSONObject): MediaSourceInfo {
    return MediaSourceInfo(
        id = json.optString("Id"),
        container = json.optString("Container"),
        directPlay = json.optBoolean("SupportsDirectPlay"),
        directStream = json.optBoolean("SupportsDirectStream"),
        transcodingUrl = json.optString("TranscodingUrl").takeIf { it.isNotBlank() },
        defaultAudioIndex = if (json.has("DefaultAudioStreamIndex") && !json.isNull("DefaultAudioStreamIndex")) json.optInt("DefaultAudioStreamIndex") else null,
        streams = json.optJSONArray("MediaStreams").toStreams()
    )
}

fun parseRemoteSubtitle(json: JSONObject): RemoteSubtitle {
    return RemoteSubtitle(
        id = json.optString("Id"),
        name = json.optString("Name").ifBlank { json.optString("ProviderName") },
        hearing = json.optBoolean("IsHearingImpaired"),
        hashMatch = json.optBoolean("IsHashMatch"),
        forced = json.optBoolean("IsForced"),
        downloads = json.optInt("DownloadCount")
    )
}

private fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i)
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun JSONArray?.toNames(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val name = optJSONObject(i)?.optString("Name").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }
}

private fun JSONArray?.toPeople(): List<PersonCredit> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val person = optJSONObject(i) ?: continue
            val name = person.optString("Name")
            if (name.isBlank()) continue
            add(PersonCredit(name, person.optString("Type")))
        }
    }
}

private fun JSONArray?.toChapters(): List<ChapterMark> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val chapter = optJSONObject(i) ?: continue
            if (!chapter.has("StartPositionTicks")) continue
            add(ChapterMark(chapter.optString("Name"), chapter.optLong("StartPositionTicks")))
        }
    }
}

private fun JSONArray?.toStreams(): List<MediaStreamInfo> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val stream = optJSONObject(i) ?: continue
            add(
                MediaStreamInfo(
                    index = stream.optInt("Index"),
                    type = stream.optString("Type"),
                    codec = stream.optString("Codec"),
                    language = stream.optString("Language"),
                    displayLanguage = stream.optString("DisplayLanguage"),
                    title = stream.optString("Title"),
                    isDefault = stream.optBoolean("IsDefault"),
                    isForced = stream.optBoolean("IsForced"),
                    isExternal = stream.optBoolean("IsExternal"),
                    channels = stream.optInt("Channels"),
                    width = stream.optInt("Width"),
                    height = stream.optInt("Height"),
                    videoRangeType = stream.optString("VideoRangeType").ifBlank { stream.optString("VideoRange") },
                    profile = stream.optString("Profile"),
                    displayTitle = stream.optString("DisplayTitle"),
                    frameRate = stream.optDouble("RealFrameRate").takeIf { it > 0.0 }?.toFloat()
                        ?: stream.optDouble("AverageFrameRate").takeIf { it > 0.0 }?.toFloat()
                        ?: 0f
                )
            )
        }
    }
}

fun parseServerTime(value: String): Long {
    if (value.length < 19) return 0L
    return try {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        format.parse(value.take(19))?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

fun ticksToClock(ticks: Long): String {
    val total = (ticks.coerceAtLeast(0L) / 10_000_000L).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

fun runtimeLabel(ticks: Long): String {
    val minutes = (ticks / 600_000_000L).toInt()
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun endsLabel(leftTicks: Long): String {
    val whenMs = System.currentTimeMillis() + leftTicks.coerceAtLeast(0L) / 10_000L
    val format = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
    return format.format(java.util.Date(whenMs))
}

fun playbackError(error: Throwable?, libraryEmpty: Boolean): String {
    var cause: Throwable? = error
    var code = 0
    while (cause != null) {
        if (cause is JellyfinException) code = cause.code
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) code = cause.responseCode
        cause = cause.cause
    }
    val text = error?.message.orEmpty()
    if (libraryEmpty || code == 404 || code >= 500 || Regex("No media|missing", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        return "This can't start. If the movie drive isn't mounted, plug it in and restart Jellyfin."
    }
    if (error is java.io.IOException || error?.cause is java.io.IOException) {
        return "Lost the connection to Jellyfin. Check that the server is on and try again."
    }
    return "This can't start. The file may be damaged or in a format this TV can't play."
}

fun streamMatchesLanguage(stream: MediaStreamInfo, code: String): Boolean {
    val hints = SUB_LANGS.find { it.code == code }?.hints ?: listOf(code)
    val language = stream.language.lowercase(Locale.US)
    val display = "${stream.displayLanguage} ${stream.title}".lowercase(Locale.US)
    return hints.any { hint -> language == hint || Regex("\\b${Regex.escape(hint)}\\b").containsMatchIn(display) }
}

/**
 * Subtitle labels keyed by stream index. Untagged Blu-ray rips often carry dozens of image tracks
 * with no language or name, which would all read the same, so repeated labels get a number.
 */
fun subtitleLabels(streams: List<MediaStreamInfo>): Map<Int, String> {
    val subs = streams.filter { it.type == "Subtitle" }
    val counts = subs.groupingBy { it.label() }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return subs.associate { stream ->
        val label = stream.label()
        if ((counts[label] ?: 0) < 2) return@associate stream.index to label
        val n = (seen[label] ?: 0) + 1
        seen[label] = n
        val (first, rest) = label.substringBefore(" · ") to label.substringAfter(" · ", "")
        stream.index to (listOf("$first $n", rest).filter { it.isNotEmpty() }.joinToString(" · "))
    }
}

/** A run longer than this of unnamed subtitle tracks is folded behind one row in the picker. */
const val FOLD_UNNAMED_SUBS = 3

fun preferredSubtitleIndex(streams: List<MediaStreamInfo>, code: String): Int? {
    val all = streams.filter { it.type == "Subtitle" }
    val inLanguage = all.filter { streamMatchesLanguage(it, code) && !it.isForced }
    val pick = inLanguage.firstOrNull { it.isTextSubtitle() }
        ?: inLanguage.firstOrNull()
        ?: all.firstOrNull { it.isDefault && it.isForced }
    return pick?.index
}

fun cleanVtt(raw: String): String {
    var text = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
    text = Regex("(?m)^REGION[\\s\\S]*?(?=\\n\\n|\\z)").replace(text, "")
    text = Regex("(?m)^NOTE[\\s\\S]*?(?=\\n\\n|\\z)").replace(text, "")
    if (!text.trimStart().startsWith("WEBVTT")) text = "WEBVTT\n\n$text"
    return text
}

private val CUE_TIME = Regex("((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{3})")

fun parseCues(vtt: String): List<Cue> {
    val cues = mutableListOf<Cue>()
    val blocks = vtt.replace("\r\n", "\n").split(Regex("\\n{2,}"))
    for (block in blocks) {
        val lines = block.lines()
        val at = lines.indexOfFirst { CUE_TIME.containsMatchIn(it) }
        if (at < 0) continue
        val match = CUE_TIME.find(lines[at]) ?: continue
        val body = lines.drop(at + 1)
            .joinToString("\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\{\\\\[^}]*\\}"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .trim()
        if (body.isEmpty()) continue
        cues += Cue(clockMs(match.groupValues[1]), clockMs(match.groupValues[2]), body)
    }
    return cues.sortedBy { it.startMs }
}

private fun clockMs(value: String): Long {
    val parts = value.replace(',', '.').split(':')
    val secParts = parts.last().split('.')
    val seconds = secParts[0].toLong()
    val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
    val minutes = parts[parts.size - 2].toLong()
    val hours = if (parts.size > 2) parts[0].toLong() else 0L
    return ((hours * 3600 + minutes * 60 + seconds) * 1000) + millis
}

fun cueAt(cues: List<Cue>, positionMs: Long): String {
    if (cues.isEmpty()) return ""
    var lo = 0
    var hi = cues.size - 1
    var start = cues.size
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].startMs > positionMs) {
            start = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    val lines = mutableListOf<String>()
    var i = start - 1
    var scanned = 0
    while (i >= 0 && scanned < 12) {
        val cue = cues[i]
        if (cue.endMs > positionMs) lines.add(0, cue.text)
        i--
        scanned++
    }
    return lines.joinToString("\n")
}
