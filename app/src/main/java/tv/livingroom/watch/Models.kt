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

data class Profile(
    val id: String,
    val name: String,
    val hasPassword: Boolean,
    val hasImage: Boolean
)

data class PersonCredit(val name: String, val type: String)

data class ChapterMark(val name: String, val startTicks: Long)

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
    val frameRate: Float = 0f
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

    fun label(): String {
        val language = languageName()
        val named = title.trim().takeIf { it.isNotEmpty() && !GENERATED_TITLE.containsMatchIn(it) }
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
    val streams: List<MediaStreamInfo>
) {
    val sortLabel: String get() = sortName.ifBlank { name }
    val progress: Float
        get() = if (runtimeTicks > 0L && positionTicks > 0L) (positionTicks.toFloat() / runtimeTicks).coerceIn(0f, 1f) else 0f
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

data class GenreStat(val name: String, val count: Int, val cover: Movie?)

private val GENERATED_TITLE = Regex("^(und|undefined)\\b| - (SUBRIP|SRT|PGSSUB|DVDSUB|ASS|SSA|AAC|AC3|EAC3|DTS|TRUEHD|FLAC|OPUS|MP3)\\b", RegexOption.IGNORE_CASE)

private val TEXT_SUBS = setOf("subrip", "srt", "ass", "ssa", "vtt", "webvtt", "mov_text", "text", "subviewer")

fun parseMovie(json: JSONObject): Movie {
    val user = json.optJSONObject("UserData")
    val imageTags = json.optJSONObject("ImageTags")
    val backdrops = json.optJSONArray("BackdropImageTags")
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
        primaryTag = imageTags?.optString("Primary")?.takeIf { it.isNotBlank() },
        backdropTag = backdrops?.optString(0)?.takeIf { it.isNotBlank() },
        played = user?.optBoolean("Played") == true,
        favorite = user?.optBoolean("IsFavorite") == true,
        positionTicks = user?.optLong("PlaybackPositionTicks") ?: 0L,
        lastPlayed = parseServerTime(user?.optString("LastPlayedDate").orEmpty()),
        chapters = json.optJSONArray("Chapters").toChapters(),
        streams = json.optJSONArray("MediaStreams").toStreams()
    )
}

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
        return "This movie can't start. If the movie drive isn't mounted, plug it in and restart Jellyfin."
    }
    if (error is java.io.IOException || error?.cause is java.io.IOException) {
        return "Lost the connection to Jellyfin. Check that the server is on and try again."
    }
    return "This movie can't start. The file may be damaged or in a format this TV can't play."
}

fun streamMatchesLanguage(stream: MediaStreamInfo, code: String): Boolean {
    val hints = SUB_LANGS.find { it.code == code }?.hints ?: listOf(code)
    val language = stream.language.lowercase(Locale.US)
    val display = "${stream.displayLanguage} ${stream.title}".lowercase(Locale.US)
    return hints.any { hint -> language == hint || Regex("\\b${Regex.escape(hint)}\\b").containsMatchIn(display) }
}

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
