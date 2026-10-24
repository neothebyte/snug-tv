package tv.livingroom.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Something about the drive that the viewer should be told, in words they can act on. */
class DriveException(message: String) : Exception(message)

/** How Drive mode stands, for Settings and Home. */
data class DriveStatus(
    /** Snug may read the drive (Android's "All files access"). */
    val access: Boolean = false,
    /** The movies folder was found on a plugged-in drive. */
    val plugged: Boolean = false,
    /** Some drive is plugged in, whether or not the movies folder is on it. */
    val driveIn: Boolean = false,
    /** The movies folder as shown in Settings: the one chosen, else what was found by itself. */
    val folder: String = "",
    val films: Int = 0,
    /** Films still being looked up on TMDB. */
    val matching: Int = 0,
    val hasKey: Boolean = false,
    /** TMDB turned the key down. */
    val keyRejected: Boolean = false
)

/**
 * Drive mode: films straight off a USB drive plugged into the box, with no Jellyfin server. The drive is
 * scanned for films (only inside the movies folder, see [folder]), TMDB fills in posters and plots, and
 * everything Jellyfin would keep per viewer (where each film was stopped, watched, favourite) is kept
 * here on the box. The last scan is kept too, so the library still shows while the drive is unplugged.
 */
@OptIn(markerClass = [UnstableApi::class])
class DriveLibrary(private val context: Context, http: OkHttpClient) : Library {
    override val local: Boolean = true

    private val dir = File(context.filesDir, "drive").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("snug-drive", Context.MODE_PRIVATE)
    private val tmdb = Tmdb(http) { tmdbKey }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val matchingNow = AtomicBoolean(false)

    private var films: List<DriveFilm> = loadFilms()
    private var byId: Map<String, DriveFilm> = films.associateBy { it.id }
    /** TMDB's details per film id, or {"none": true} where nothing matched. */
    private val meta: MutableMap<String, JSONObject> = loadMap(META)
    /** Per film id: p position (ticks), w watched, f favourite, t last played (ms), d length (ticks). */
    private val state: MutableMap<String, JSONObject> = loadMap(STATE)
    /** The tracks inside each file, found the first time it opens. */
    private val probes: MutableMap<String, JSONObject> = loadMap(PROBES)
    @Volatile private var root: File? = null
    @Volatile private var scannedAt = 0L
    private var keyRejected = false

    private val _status = MutableStateFlow(DriveStatus())
    val status: StateFlow<DriveStatus> get() = _status

    /** Bumped when the films or their details change, so the screens reload. */
    val changes = MutableStateFlow(0)

    /** The TMDB key typed in Settings, else the one the app was built with. */
    var tmdbKey: String
        get() = prefs.getString(KEY_TMDB, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_API_KEY
        set(value) {
            prefs.edit().putString(KEY_TMDB, value.trim()).apply()
            keyRejected = false
            synchronized(lock) { meta.entries.removeAll { it.value.optBoolean("none") } }
            publish()
            match()
        }

    /**
     * The movies folder, from the top of the drive, as chosen in Settings. Blank means Snug looks for
     * one by itself (see [AUTO_NAMES]).
     */
    var folder: String
        get() = prefs.getString(KEY_FOLDER, null).orEmpty()
        private set(value) {
            prefs.edit().putString(KEY_FOLDER, value.trim('/')).apply()
        }

    init {
        publish()
    }

    // ---- The drive itself ----

    /** Android lets Snug read the drive. From Android 11 on that is "All files access". */
    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Looks for the drive and, when it is there, lists its films again (at most once a minute unless
     * [force]), then looks up on TMDB whatever is new. Blocking: call it off the main thread.
     */
    fun scan(force: Boolean = false) {
        val found = if (hasAccess()) findRoot() else null
        val moved = found?.path != root?.path
        root = found
        if (found != null && (force || moved || System.currentTimeMillis() - scannedAt > RESCAN_MS)) {
            val list = DriveScanner.scan(found)
            scannedAt = System.currentTimeMillis()
            val changed = synchronized(lock) {
                if (list == films) {
                    false
                } else {
                    films = list
                    byId = list.associateBy { it.id }
                    true
                }
            }
            if (changed) {
                saveFilms(list)
                changes.value++
            }
        }
        publish()
        if (found != null) match()
    }

    /**
     * The movies folder on whichever plugged-in drive has it: the chosen one, else a top-level folder
     * named one of [AUTO_NAMES] (any case), else [OLD_FOLDER]. Only the names of the folders at the top
     * of each drive are read; nothing inside them is opened.
     */
    private fun findRoot(): File? {
        val chosen = folder
        val tops = drives().map { it.second }
        if (chosen.isNotBlank()) return tops.map { File(it, chosen) }.firstOrNull { it.isDirectory }
        for (top in tops) {
            val dirs = top.listFiles()?.filter { it.isDirectory }.orEmpty()
            AUTO_NAMES.firstNotNullOfOrNull { name -> dirs.firstOrNull { it.name.trim().equals(name, ignoreCase = true) } }?.let { return it }
        }
        return tops.map { File(it, OLD_FOLDER) }.firstOrNull { it.isDirectory }
    }

    /** Plugged-in drives, with the name the box gives each, for choosing the movies folder. */
    fun drives(): List<Pair<String, File>> {
        val found = LinkedHashMap<String, Pair<String, File>>()
        val storage = context.getSystemService(StorageManager::class.java)
        storage?.storageVolumes?.forEach { volume ->
            if (volume.isPrimary) return@forEach
            if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
            val dir = volumeDir(volume) ?: return@forEach
            found[dir.path] = (volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: dir.name) to dir
        }
        // Some boxes leave USB drives out of the list above; they are still mounted under /storage.
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }?.forEach { dir ->
            if (dir.path !in found) found[dir.path] = dir.name to dir
        }
        return found.values.toList()
    }

    /** The folders inside [dir], for choosing the movies folder. Only listed when someone opens [dir] to look. */
    fun subfolders(dir: File): List<File> =
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()

    /** Makes [dir] the movies folder and scans it. Null goes back to finding one by itself. Blocking. */
    fun useFolder(dir: File?) {
        folder = if (dir == null) "" else drives().map { it.second }.firstOrNull { dir.isInside(it) }?.let { dir.relativeTo(it).path.ifEmpty { "." } }.orEmpty()
        root = null
        scan(force = true)
    }

    private fun volumeDir(volume: android.os.storage.StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            runCatching { volume.javaClass.getMethod("getPathFile").invoke(volume) as? File }.getOrNull()
        }

    private fun fileFor(relative: String): File {
        val top = root ?: throw DriveException(UNPLUGGED)
        val file = File(top, relative)
        if (!file.exists()) throw DriveException(if (findRoot() == null) UNPLUGGED else "That film isn't on the drive any more.")
        return file
    }

    /** Scans again from scratch and retries TMDB for films it couldn't match before. */
    fun rescan() {
        synchronized(lock) { meta.entries.removeAll { it.value.optBoolean("none") } }
        keyRejected = false
        scan(force = true)
    }

    private fun publish() {
        val (count, missing) = synchronized(lock) { films.size to films.count { needsMeta(it) } }
        val access = hasAccess()
        val top = root
        _status.value = DriveStatus(
            access = access,
            plugged = top != null,
            driveIn = top != null || (access && drives().isNotEmpty()),
            folder = folder.ifBlank { top?.let { found -> drives().map { it.second }.firstOrNull { found.isInside(it) }?.let { found.relativeTo(it).path } }.orEmpty() },
            films = count,
            matching = if (matchingNow.get()) missing else 0,
            hasKey = tmdbKey.isNotBlank(),
            keyRejected = keyRejected
        )
    }

    // ---- TMDB ----

    private fun needsMeta(film: DriveFilm): Boolean {
        val found = meta[film.id] ?: return true
        return found.optBoolean("none") && System.currentTimeMillis() - found.optLong("at") > RETRY_UNMATCHED_MS
    }

    /** Looks up films with no details yet, four at a time, saving as it goes. */
    private fun match() {
        if (tmdbKey.isBlank() || keyRejected || !matchingNow.compareAndSet(false, true)) return
        scope.launch {
            try {
                val todo = synchronized(lock) { films.filter { needsMeta(it) } }
                if (todo.isEmpty()) return@launch
                publish()
                var since = 0
                for (batch in todo.chunked(4)) {
                    val results = coroutineScope {
                        batch.map { film -> async { film to runCatching { tmdb.find(film.title, film.year) } } }.awaitAll()
                    }
                    if (results.any { it.second.exceptionOrNull() is TmdbKeyException }) {
                        keyRejected = true
                        break
                    }
                    synchronized(lock) {
                        for ((film, result) in results) {
                            // A network failure is tried again next time; only "no match" is remembered.
                            if (result.isFailure) continue
                            meta[film.id] = result.getOrNull() ?: JSONObject().put("none", true).put("at", System.currentTimeMillis())
                        }
                    }
                    since += batch.size
                    if (since >= 12) {
                        since = 0
                        saveMap(META, meta)
                        changes.value++
                    }
                    publish()
                }
                saveMap(META, meta)
                changes.value++
            } finally {
                matchingNow.set(false)
                publish()
            }
        }
    }

    // ---- Films as Snug sees them ----

    /** Call with [lock] held. */
    private fun movie(film: DriveFilm, full: Boolean): Movie {
        val details = meta[film.id]?.takeIf { !it.optBoolean("none") }
        val mine = state[film.id]
        val name = details?.optString("title")?.takeIf { it.isNotBlank() } ?: film.title
        val runtime = mine?.optLong("d")?.takeIf { it > 0L } ?: ((details?.optInt("runtime") ?: 0) * 600_000_000L)
        val people = buildList {
            details?.optJSONArray("directors").strings().forEach { add(PersonCredit(it, "Director")) }
            details?.optJSONArray("cast").strings().forEach { add(PersonCredit(it, "Actor")) }
            details?.optJSONArray("writers").strings().forEach { add(PersonCredit(it, "Writer")) }
        }
        val localPoster = film.poster?.let { relative -> root?.let { "file:" + File(it, relative).path } }
        return Movie(
            id = film.id,
            name = name,
            sortName = sortName(name),
            year = details?.optInt("year")?.takeIf { it > 0 } ?: film.year,
            overview = details?.optString("overview").orEmpty(),
            tagline = details?.optString("tagline").orEmpty(),
            runtimeTicks = runtime,
            officialRating = details?.optString("cert").orEmpty(),
            communityRating = details?.optDouble("rating", 0.0)?.takeIf { it > 0.0 }?.let { Math.round(it * 10) / 10.0 },
            genres = details?.optJSONArray("genres").strings(),
            people = people,
            studios = details?.optJSONArray("studios").strings(),
            dateCreated = isoTime(film.added),
            primaryTag = details?.optString("poster")?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" } ?: localPoster,
            backdropTag = details?.optString("backdrop")?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" },
            played = mine?.optBoolean("w") == true,
            favorite = mine?.optBoolean("f") == true,
            positionTicks = mine?.optLong("p") ?: 0L,
            lastPlayed = mine?.optLong("t") ?: 0L,
            chapters = emptyList(),
            streams = if (full) streams(film) else emptyList(),
            logoTag = details?.optString("logo")?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
        )
    }

    private fun all(): List<Movie> = synchronized(lock) { films.map { movie(it, full = false) } }

    private fun filmFor(id: String): DriveFilm = synchronized(lock) { byId[id] } ?: throw DriveException("That film isn't on the drive any more.")

    override fun movies(
        start: Int,
        limit: Int,
        sortBy: String,
        sortOrder: String,
        filters: String?,
        genre: String?,
        person: String?,
        personType: String?,
        personIds: List<String>,
        search: String?,
        fields: String,
        countTotal: Boolean,
        types: String,
        nameStartsWith: String?,
        nameLessThan: String?
    ): MoviePage {
        // There are only films on the drive, no TV shows.
        if (types.split(',').none { it.trim() == "Movie" }) return MoviePage(emptyList(), 0)
        var list = all().asSequence()
        filters?.split(',')?.forEach { filter ->
            list = when (filter.trim()) {
                "IsUnplayed" -> list.filter { !it.played }
                "IsPlayed" -> list.filter { it.played }
                "IsFavorite" -> list.filter { it.favorite }
                "IsResumable" -> list.filter { it.positionTicks > 0L }
                else -> list
            }
        }
        if (genre != null) {
            val wanted = genre.split('|').map { it.trim().lowercase(Locale.ROOT) }.toSet()
            list = list.filter { movie -> movie.genres.any { it.lowercase(Locale.ROOT) in wanted } }
        }
        if (person != null) {
            list = list.filter { movie -> movie.people.any { it.name.equals(person, true) && (personType.isNullOrBlank() || it.type == personType) } }
        }
        if (personIds.isNotEmpty()) {
            val names = personIds.map { it.lowercase(Locale.ROOT) }.toSet()
            list = list.filter { movie -> movie.people.any { it.name.lowercase(Locale.ROOT) in names } }
        }
        if (!search.isNullOrBlank()) {
            val needle = key(search)
            list = list.filter { key(it.name).contains(needle) }
        }
        if (nameStartsWith != null) {
            val prefix = key(nameStartsWith)
            list = list.filter { key(it.sortLabel).startsWith(prefix) }
        }
        if (nameLessThan != null) {
            val limitKey = key(nameLessThan)
            list = list.filter { key(it.sortLabel) < limitKey }
        }
        val byTitle = compareBy<Movie> { key(it.sortLabel) }
        val first = sortBy.substringBefore(',')
        val sorted = if (first == "Random") {
            list.toList().shuffled()
        } else {
            val primary: Comparator<Movie> = when (first) {
                "ProductionYear", "PremiereDate" -> compareBy<Movie> { it.year ?: 0 }
                "DateCreated", "DateLastContentAdded" -> compareBy<Movie> { it.dateCreated }
                "CommunityRating" -> compareBy<Movie> { it.communityRating ?: 0.0 }
                "DatePlayed" -> compareBy<Movie> { it.lastPlayed }
                else -> byTitle
            }
            // Only the first key turns round, so titles within a year still run A to Z.
            list.sortedWith((if (sortOrder == "Descending") primary.reversed() else primary).then(byTitle)).toList()
        }
        return MoviePage(sorted.drop(start).take(limit), sorted.size)
    }

    override fun countBefore(letter: String, types: String, filters: String?): Int? {
        val prefix = key(letter)
        val index = movies(limit = Int.MAX_VALUE, filters = filters, types = types).items.indexOfFirst { key(it.sortLabel).startsWith(prefix) }
        return index.takeIf { it >= 0 }
    }

    override fun genres(): List<String> = all().flatMap { it.genres }.distinct().sortedBy { it.lowercase(Locale.ROOT) }

    /** People are known by name here, so a name is also their id. */
    override fun findPeople(search: String, limit: Int): List<String> {
        val needle = key(search)
        return all().asSequence().flatMap { it.people }.map { it.name }.distinct().filter { key(it).contains(needle) }.take(limit).toList()
    }

    /** Films sharing the most genres with [id], best rated first. */
    override fun similar(id: String, limit: Int): List<Movie> {
        val movies = all()
        val target = movies.find { it.id == id } ?: return emptyList()
        if (target.genres.isEmpty()) return emptyList()
        return movies.asSequence()
            .filter { it.id != id }
            .map { it to it.genres.count { genre -> genre in target.genres } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Movie, Int>> { it.second }.thenByDescending { it.first.communityRating ?: 0.0 })
            .take(limit)
            .map { it.first }
            .toList()
    }

    override fun resume(limit: Int, fields: String): List<Movie> =
        all().filter { it.positionTicks > 0L && !it.played }.sortedByDescending { it.lastPlayed }.take(limit)

    override fun nextUp(limit: Int, seriesId: String?, fields: String): List<Movie> = emptyList()

    override fun seasons(seriesId: String): List<Movie> = emptyList()

    override fun episodes(seriesId: String, seasonId: String): List<Movie> = emptyList()

    override fun episodeAfter(episode: Movie): Movie? = null

    override fun segments(itemId: String): List<MediaSegment> = emptyList()

    override fun item(id: String): Movie {
        val film = filmFor(id)
        // Reading the file's tracks can take a moment, so it happens outside the lock.
        val basic = synchronized(lock) { movie(film, full = false) }
        return basic.copy(streams = streams(film))
    }

    /** Tags here are where the picture is: "tmdb:/abc.jpg" on TMDB, or "file:/storage/…" next to the film. */
    override fun imageUrl(itemId: String, kind: String, tag: String?, width: Int): String? {
        if (tag.isNullOrBlank()) return null
        if (tag.startsWith("file:")) return Uri.fromFile(File(tag.removePrefix("file:"))).toString()
        if (!tag.startsWith("tmdb:")) return null
        val size = when (kind) {
            "Backdrop" -> when {
                width <= 300 -> "w300"
                width <= 780 -> "w780"
                else -> "w1280"
            }
            "Logo" -> if (width <= 300) "w300" else "w500"
            else -> when {
                width <= 185 -> "w185"
                width <= 342 -> "w342"
                width <= 500 -> "w500"
                else -> "w780"
            }
        }
        return "https://image.tmdb.org/t/p/$size${tag.removePrefix("tmdb:")}"
    }

    override fun trickplaySheetUrl(itemId: String, info: TrickplayInfo, sheet: Int): String = ""

    override fun localTrailers(itemId: String): List<Movie> = emptyList()

    // ---- Playing ----

    override fun playback(itemId: String, startTicks: Long, audioIndex: Int?, subtitleIndex: Int?, burn: Boolean, mode: PlayMode): PlaybackPlan {
        // There's no server here to repackage or convert a file the TV can't play as it is.
        if (burn || mode != PlayMode.Direct) {
            throw DriveException("This TV can't play this file as it is, and with the drive there's no server to convert it.")
        }
        val film = filmFor(itemId)
        val file = fileFor(film.video)
        val streams = streams(film)
        val source = MediaSourceInfo(
            id = film.id,
            container = file.extension.lowercase(Locale.ROOT),
            directPlay = true,
            directStream = true,
            transcodingUrl = null,
            defaultAudioIndex = streams.firstOrNull { it.type == "Audio" && it.isDefault }?.index ?: streams.firstOrNull { it.type == "Audio" }?.index,
            streams = streams
        )
        return PlaybackPlan("", source, hls = false, burn = false, mode = PlayMode.Direct)
    }

    override fun streamUrl(itemId: String, plan: PlaybackPlan): String = Uri.fromFile(fileFor(filmFor(itemId).video)).toString()

    override fun subtitleVtt(itemId: String, sourceId: String, index: Int): String {
        val film = filmFor(itemId)
        val relative = film.subtitles.getOrNull(index - EXTERNAL_BASE) ?: throw DriveException("Those subtitles aren't on the drive any more.")
        return cleanVtt(decode(fileFor(relative).readBytes()))
    }

    override fun report(path: String, itemId: String, plan: PlaybackPlan, ticks: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) {
        synchronized(lock) {
            val mine = state.getOrPut(itemId) { JSONObject() }
            val length = mine.optLong("d").takeIf { it > 0L }
                ?: ((meta[itemId]?.optInt("runtime") ?: 0) * 600_000_000L)
            mine.put("t", System.currentTimeMillis())
            val stopped = path.endsWith("/Stopped")
            when {
                // Like Jellyfin: past 90% counts as watched, and the start isn't worth resuming from.
                length > 0L && ticks >= length * 9 / 10 -> mine.put("w", true).put("p", 0L)
                ticks < MIN_RESUME_TICKS -> if (stopped) mine.put("p", 0L)
                else -> mine.put("p", ticks)
            }
        }
        saveMap(STATE, state)
    }

    override fun noteDuration(itemId: String, durationMs: Long) {
        if (durationMs <= 0L) return
        val changed = synchronized(lock) {
            val mine = state.getOrPut(itemId) { JSONObject() }
            val ticks = durationMs * 10_000L
            (mine.optLong("d") != ticks).also { if (it) mine.put("d", ticks) }
        }
        if (changed) saveMap(STATE, state)
    }

    override fun stopEncoding(playSessionId: String) {}

    override fun toggleFavorite(itemId: String, on: Boolean) {
        synchronized(lock) { state.getOrPut(itemId) { JSONObject() }.put("f", !on) }
        saveMap(STATE, state)
    }

    override fun togglePlayed(itemId: String, on: Boolean) {
        synchronized(lock) {
            val mine = state.getOrPut(itemId) { JSONObject() }
            mine.put("w", !on).put("p", 0L)
            if (!on) mine.put("t", System.currentTimeMillis())
        }
        saveMap(STATE, state)
    }

    override fun hiddenShows(): Set<String> = emptySet()

    override fun setShowHidden(seriesId: String, hide: Boolean): Set<String> = emptySet()

    override fun themeSongUrl(ownerId: String): String? = null

    override fun remoteSubtitles(itemId: String, language: String): List<RemoteSubtitle> =
        throw JellyfinException(404, "Online subtitles need Jellyfin")

    override fun downloadSubtitle(itemId: String, subtitleId: String) {
        throw JellyfinException(404, "Online subtitles need Jellyfin")
    }

    override fun pluginConfig(): JSONObject? = null

    override fun savePluginConfig(username: String, password: String) {
        throw JellyfinException(404, "OpenSubtitles needs Jellyfin")
    }

    // ---- Tracks ----

    /** The file's own tracks, then the .srt files beside it. */
    private fun streams(film: DriveFilm): List<MediaStreamInfo> {
        val external = film.subtitles.mapIndexed { i, relative ->
            val file = File(relative)
            val base = File(film.video).nameWithoutExtension
            val (language, forced, hearing) = DriveScanner.subtitleInfo(file.name, base)
            MediaStreamInfo(
                index = EXTERNAL_BASE + i,
                type = "Subtitle",
                codec = if (file.extension.equals("vtt", true)) "webvtt" else "subrip",
                language = language,
                displayLanguage = "",
                title = when {
                    language.isEmpty() -> file.nameWithoutExtension
                    hearing -> "SDH"
                    else -> ""
                },
                isDefault = false,
                isForced = forced,
                isExternal = true,
                channels = 0
            )
        }
        return probe(film) + external
    }

    private fun probe(film: DriveFilm): List<MediaStreamInfo> {
        synchronized(lock) {
            probes[film.id]?.takeIf { it.optLong("size") == film.size }?.let { cached ->
                return parseStreams(cached.optJSONArray("streams")).map { it.copy(playerDraws = it.type == "Subtitle") }
            }
        }
        val file = runCatching { fileFor(film.video) }.getOrNull() ?: return emptyList()
        val groups = runCatching {
            MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(Uri.fromFile(file))).get(PROBE_SECONDS, TimeUnit.SECONDS)
        }.getOrNull() ?: return emptyList()
        val found = JSONArray()
        for (i in 0 until groups.length) {
            val format = groups[i].getFormat(0)
            streamJson(i, format)?.let { found.put(it) }
        }
        synchronized(lock) { probes[film.id] = JSONObject().put("size", film.size).put("streams", found) }
        saveMap(PROBES, probes)
        return parseStreams(found).map { it.copy(playerDraws = it.type == "Subtitle") }
    }

    /** One track as Jellyfin would describe it in MediaStreams. */
    private fun streamJson(index: Int, format: Format): JSONObject? {
        // Text tracks may already be parsed into the player's own cue format; the original is in codecs.
        val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
        val kind = when (MimeTypes.getTrackType(mime)) {
            C.TRACK_TYPE_VIDEO -> "Video"
            C.TRACK_TYPE_AUDIO -> "Audio"
            C.TRACK_TYPE_TEXT -> "Subtitle"
            else -> if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) "Subtitle" else return null
        }
        val range = when {
            mime == MimeTypes.VIDEO_DOLBY_VISION -> "DOVI"
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            kind == "Video" -> "SDR"
            else -> ""
        }
        return JSONObject()
            .put("Index", index)
            .put("Type", kind)
            .put("Codec", codecName(mime))
            .put("Language", format.language.orEmpty())
            .put("Title", format.label.orEmpty())
            .put("IsDefault", (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0)
            .put("IsForced", (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0)
            .put("IsExternal", false)
            .put("Channels", format.channelCount.coerceAtLeast(0))
            .put("Width", format.width.coerceAtLeast(0))
            .put("Height", format.height.coerceAtLeast(0))
            .put("VideoRangeType", range)
            .put("Profile", if (mime == MimeTypes.AUDIO_E_AC3_JOC) "Dolby Atmos" else "")
            .put("RealFrameRate", format.frameRate.takeIf { it > 0f }?.toDouble() ?: 0.0)
    }

    private fun codecName(mime: String?): String = when (mime) {
        MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION -> "hevc"
        MimeTypes.VIDEO_H264 -> "h264"
        MimeTypes.VIDEO_AV1 -> "av1"
        MimeTypes.VIDEO_VP9 -> "vp9"
        MimeTypes.VIDEO_MPEG2 -> "mpeg2video"
        MimeTypes.VIDEO_MP4V -> "mpeg4"
        MimeTypes.AUDIO_AAC -> "aac"
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "dts"
        MimeTypes.AUDIO_TRUEHD -> "truehd"
        MimeTypes.AUDIO_OPUS -> "opus"
        MimeTypes.AUDIO_FLAC -> "flac"
        MimeTypes.AUDIO_MPEG -> "mp3"
        MimeTypes.AUDIO_VORBIS -> "vorbis"
        MimeTypes.APPLICATION_SUBRIP -> "subrip"
        MimeTypes.TEXT_SSA -> "ass"
        MimeTypes.TEXT_VTT -> "webvtt"
        MimeTypes.APPLICATION_PGS -> "pgssub"
        MimeTypes.APPLICATION_VOBSUB -> "dvdsub"
        MimeTypes.APPLICATION_DVBSUBS -> "dvbsub"
        MimeTypes.APPLICATION_TX3G -> "mov_text"
        else -> mime?.substringAfter('/').orEmpty()
    }

    // ---- Keeping things on the box ----

    private fun loadFilms(): List<DriveFilm> = runCatching {
        val array = JSONObject(File(dir, FILMS).readText()).getJSONArray("films")
        List(array.length()) { i ->
            val json = array.getJSONObject(i)
            DriveFilm(
                id = json.getString("id"),
                video = json.getString("video"),
                size = json.optLong("size"),
                added = json.optLong("added"),
                title = json.optString("title"),
                year = json.optInt("year").takeIf { it > 0 },
                subtitles = json.optJSONArray("subs").strings(),
                poster = json.optString("poster").takeIf { it.isNotBlank() }
            )
        }
    }.getOrDefault(emptyList())

    private fun saveFilms(list: List<DriveFilm>) {
        val array = JSONArray()
        list.forEach { film ->
            array.put(
                JSONObject()
                    .put("id", film.id)
                    .put("video", film.video)
                    .put("size", film.size)
                    .put("added", film.added)
                    .put("title", film.title)
                    .put("year", film.year ?: 0)
                    .put("subs", JSONArray(film.subtitles))
                    .put("poster", film.poster.orEmpty())
            )
        }
        write(FILMS, JSONObject().put("films", array).toString())
    }

    private fun loadMap(name: String): MutableMap<String, JSONObject> = runCatching {
        val json = JSONObject(File(dir, name).readText())
        val map = HashMap<String, JSONObject>()
        json.keys().forEach { key -> json.optJSONObject(key)?.let { map[key] = it } }
        map
    }.getOrDefault(HashMap())

    private fun saveMap(name: String, map: Map<String, JSONObject>) {
        val text = synchronized(lock) { JSONObject(map as Map<*, *>).toString() }
        write(name, text)
    }

    /** Written aside and moved into place, so a crash mid-write never leaves half a file. */
    private fun write(name: String, text: String) {
        synchronized(dir) {
            runCatching {
                val temp = File(dir, "$name.tmp")
                temp.writeText(text)
                temp.renameTo(File(dir, name))
            }
        }
    }

    private companion object {
        const val FILMS = "films.json"
        const val META = "tmdb.json"
        const val STATE = "watched.json"
        const val PROBES = "tracks.json"
        const val KEY_TMDB = "tmdbKey"
        const val KEY_FOLDER = "moviesFolder"
        /** Where Snug looks for films until a folder is chosen: a folder at the top of the drive with one of these names, in any case. */
        val AUTO_NAMES = listOf("Movies", "Movie", "Films", "Film")
        /** Then the layout Snug 1.15.0 was made for. */
        const val OLD_FOLDER = "001. Personal/02. Media/02. Videos/01. Movies"
        /** Subtitle files beside a film are numbered from here, after the file's own tracks. */
        const val EXTERNAL_BASE = 1000
        const val RESCAN_MS = 60_000L
        const val RETRY_UNMATCHED_MS = 7L * 24 * 3600 * 1000
        const val MIN_RESUME_TICKS = 60L * 10_000_000L
        const val PROBE_SECONDS = 20L
        const val UNPLUGGED = "The movie drive isn't plugged in. Plug it in (with USB and Wireless debugging off), then try again."
    }
}

private fun File.isInside(top: File): Boolean = path == top.path || path.startsWith(top.path.trimEnd('/') + "/")

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { optString(it) }.filter { it.isNotBlank() }
}

/** Sorts "The Matrix" under M, as Jellyfin does. */
private fun sortName(name: String): String {
    val lower = name.lowercase(Locale.ROOT)
    for (article in listOf("the ", "a ", "an ")) {
        if (lower.startsWith(article) && name.length > article.length) return name.substring(article.length)
    }
    return name
}

/** For comparing names: lower case, accents dropped. */
private fun key(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

private fun isoTime(ms: Long): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(java.util.Date(ms))
}

/** Subtitle files are UTF-8 these days, but older ones are often Windows-1252. */
private fun decode(bytes: ByteArray): String {
    val strict = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        strict.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, charset("windows-1252"))
    }
}
