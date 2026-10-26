package tv.livingroom.watch

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One film found on the drive. Paths are relative to the Movies folder. */
data class DriveFilm(
    val id: String,
    /** The video file. */
    val video: String,
    val size: Long,
    /** When the file was last changed, which stands in for when it was added. */
    val added: Long,
    /** From the folder or file name: "Title (Year) [tags]" gives "Title" and the year. */
    val title: String,
    val year: Int?,
    /** .srt and .vtt files that go with the film. */
    val subtitles: List<String>,
    /** A poster picture kept next to the film, if there is one. */
    val poster: String?
)

data class ParsedName(val title: String, val year: Int?)

/**
 * Finds the films in the movies folder of the drive. It only ever looks inside that folder: the rest
 * of the drive is someone's own files, so nothing above or beside it is listed or opened.
 *
 * Films are usually in a folder of their own named "Title (Year) [tags]", possibly inside A to Z
 * folders; the biggest video in such a folder is the film and its .srt files (also in a Subs folder)
 * are its subtitles. A video sitting loose is a film of its own, named after its file.
 */
object DriveScanner {
    private const val MAX_DEPTH = 4
    private const val MIN_FEATURE_BYTES = 40L * 1024 * 1024
    private val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm")
    private val SUBTITLE = setOf("srt", "vtt")
    private val PICTURE = setOf("jpg", "jpeg", "png", "webp")
    private val POSTER_NAMES = setOf("poster", "folder", "cover", "movie")
    private val SUB_FOLDERS = setOf("subs", "subtitles", "sub")
    private val EXTRA_FOLDERS = setOf("extras", "featurettes", "behind the scenes", "deleted scenes", "trailers", "samples", "sample")
    private val NOT_FEATURE = Regex("\\b(sample|trailer)\\b", RegexOption.IGNORE_CASE)
    private val TITLE_YEAR = Regex("^(.+?)[\\s.]*[(\\[]((?:19|20)\\d{2})[)\\]]")
    private val DOTTED_YEAR = Regex("^(.+?)\\s+\\(?((?:19|20)\\d{2})\\)?(?:\\s|$)")

    fun scan(root: File): List<DriveFilm> {
        val films = ArrayList<DriveFilm>()
        visit(root, root, 0, films)
        return films.distinctBy { it.id }.sortedBy { it.video.lowercase(Locale.ROOT) }
    }

    /** Whether [root] holds at least one film, looking no further than [scan] would. Stops at the first. */
    fun hasFilm(root: File, depth: Int = 0): Boolean {
        val entries = root.listFiles()?.filter { !it.name.startsWith(".") } ?: return false
        if (entries.any { it.isFile && isFeature(it) }) return true
        if (depth >= MAX_DEPTH) return false
        return entries.any { it.isDirectory && it.name.lowercase(Locale.ROOT) !in EXTRA_FOLDERS && it.name.lowercase(Locale.ROOT) !in SUB_FOLDERS && hasFilm(it, depth + 1) }
    }

    private fun visit(root: File, dir: File, depth: Int, out: MutableList<DriveFilm>) {
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return
        val videos = entries.filter { it.isFile && isFeature(it) }
        val named = parseName(dir.name)
        if (depth > 0 && named.year != null && videos.isNotEmpty()) {
            // A film's own folder. Anything else in it is an extra.
            val main = videos.maxBy { it.length() }
            out += film(root, main, named, subtitlesIn(entries, null), entries.firstOrNull { isPoster(it) })
            return
        }
        for (video in videos) {
            out += film(root, video, parseName(video.nameWithoutExtension), subtitlesIn(entries, video.nameWithoutExtension), null)
        }
        if (depth >= MAX_DEPTH) return
        entries.filter { it.isDirectory && it.name.lowercase(Locale.ROOT) !in EXTRA_FOLDERS && it.name.lowercase(Locale.ROOT) !in SUB_FOLDERS }
            .sortedBy { it.name }
            .forEach { visit(root, it, depth + 1, out) }
    }

    private fun film(root: File, video: File, name: ParsedName, subtitles: List<File>, poster: File?): DriveFilm {
        val relative = video.relativeTo(root).path
        return DriveFilm(
            id = idFor(relative),
            video = relative,
            size = video.length(),
            added = video.lastModified(),
            title = name.title.ifBlank { video.nameWithoutExtension },
            year = name.year,
            subtitles = subtitles.map { it.relativeTo(root).path }.sorted(),
            poster = poster?.relativeTo(root)?.path
        )
    }

    private fun isFeature(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in VIDEO && !NOT_FEATURE.containsMatchIn(file.nameWithoutExtension) && file.length() >= MIN_FEATURE_BYTES

    private fun isPoster(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in PICTURE && file.nameWithoutExtension.lowercase(Locale.ROOT) in POSTER_NAMES

    /**
     * Subtitles beside a film: in its folder and its Subs folder when [prefix] is null, else the ones
     * whose name starts with [prefix] (a loose video's own name).
     */
    private fun subtitlesIn(entries: List<File>, prefix: String?): List<File> {
        fun isSub(file: File) = file.isFile && file.extension.lowercase(Locale.ROOT) in SUBTITLE
        if (prefix != null) return entries.filter { isSub(it) && it.name.startsWith(prefix, ignoreCase = true) }
        val here = entries.filter(::isSub)
        val folders = entries.filter { it.isDirectory && it.name.lowercase(Locale.ROOT) in SUB_FOLDERS }
        val inside = folders.flatMap { folder ->
            val files = folder.listFiles()?.toList().orEmpty()
            files.filter(::isSub) + files.filter { it.isDirectory }.flatMap { inner -> inner.listFiles()?.filter(::isSub).orEmpty() }
        }
        return here + inside
    }

    fun idFor(relative: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(relative.toByteArray(Charsets.UTF_8))
        return "drive" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** "The Matrix (1999) [1080p] [YTS.MX]" or "The.Matrix.1999.1080p.BluRay" → The Matrix, 1999. */
    fun parseName(raw: String): ParsedName {
        TITLE_YEAR.find(raw)?.let { return ParsedName(clean(it.groupValues[1]), it.groupValues[2].toInt()) }
        val spaced = raw.replace('.', ' ').replace('_', ' ')
        DOTTED_YEAR.find(spaced)?.let { return ParsedName(clean(it.groupValues[1]), it.groupValues[2].toInt()) }
        return ParsedName(clean(spaced.replace(Regex("\\[.*?]"), "")), null)
    }

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().trimEnd('-', ' ', ',')

    /**
     * What a subtitle file's name says about it: its language (a code from [SUB_LANGS]), whether it is
     * forced, and whether it is for the hard of hearing. [base] is the part of the name to ignore.
     */
    fun subtitleInfo(fileName: String, base: String?): Triple<String, Boolean, Boolean> {
        var name = fileName.substringBeforeLast('.')
        if (base != null && name.startsWith(base, ignoreCase = true)) name = name.substring(base.length)
        val tokens = name.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        // Two-letter codes are common words ("it", "de"), so they only count as the last word.
        val language = SUB_LANGS.firstOrNull { lang -> tokens.any { it.length > 2 && it in lang.hints } }
            ?: SUB_LANGS.firstOrNull { lang -> tokens.lastOrNull { it != "forced" && it != "sdh" && it != "hi" }?.let { it.length == 2 && it in lang.hints } == true }
        return Triple(language?.code.orEmpty(), "forced" in tokens, "sdh" in tokens || "hi" in tokens || "cc" in tokens)
    }
}
