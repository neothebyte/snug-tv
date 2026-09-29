package tv.livingroom.watch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JellyfinException(val code: Int, message: String) : Exception(message)

class JellyfinClient(
    private val store: SessionStore,
    private val http: OkHttpClient,
    private val codecs: () -> DeviceCodecs
) {
    @Volatile private var serverVersion: String? = null
    @Volatile private var versionFor: String? = null

    fun publicUsers(): List<Profile> {
        val text = call("GET", "/Users/Public", auth = false)
        val array = JSONArray(text.ifBlank { "[]" })
        return buildList {
            for (i in 0 until array.length()) {
                val user = array.optJSONObject(i) ?: continue
                add(parseProfile(user))
            }
        }
    }

    fun authenticate(username: String, password: String) {
        val body = JSONObject().put("Username", username).put("Pw", password)
        saveAuth(call("POST", "/Users/AuthenticateByName", body, auth = false))
    }

    /** Asks [address] who it is. Returns null when nothing there answers like a Jellyfin server. */
    fun serverInfo(address: String): FoundServer? {
        val text = runCatching { call("GET", "/System/Info/Public", auth = false, server = address, client = quick) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = json.optString("Id")
        if (id.isBlank()) return null
        return FoundServer(id, json.optString("ServerName").ifBlank { "Jellyfin" }, address)
    }

    fun quickConnectEnabled(): Boolean =
        runCatching { call("GET", "/QuickConnect/Enabled", auth = false).trim().equals("true", true) }.getOrDefault(false)

    /** Starts a Quick Connect request; the user approves [QuickConnect.code] from another signed-in device. */
    fun quickConnectStart(): QuickConnect {
        // Jellyfin 10.9 moved this from GET to POST.
        val text = try {
            call("POST", "/QuickConnect/Initiate", auth = false)
        } catch (error: JellyfinException) {
            if (error.code != 404 && error.code != 405) throw error
            call("GET", "/QuickConnect/Initiate", auth = false)
        }
        val json = JSONObject(text)
        return QuickConnect(json.getString("Secret"), json.getString("Code"))
    }

    fun quickConnectApproved(secret: String): Boolean {
        val text = call("GET", "/QuickConnect/Connect?secret=${URLEncoder.encode(secret, "UTF-8")}", auth = false)
        return JSONObject(text).optBoolean("Authenticated")
    }

    fun authenticateQuickConnect(secret: String) {
        saveAuth(call("POST", "/Users/AuthenticateWithQuickConnect", JSONObject().put("Secret", secret), auth = false))
    }

    private fun saveAuth(text: String) {
        val json = JSONObject(text)
        val user = json.getJSONObject("User")
        store.saveSession(json.getString("AccessToken"), user.getString("Id"), user.optString("Name"))
    }

    /** One page of movies, filtered and sorted by the server. */
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
        fields: String = GRID_FIELDS,
        countTotal: Boolean = true,
        types: String = "Movie"
    ): MoviePage {
        val userId = store.userId ?: error("Sign in again.")
        val query = buildString {
            append("/Users/$userId/Items?IncludeItemTypes=$types&Recursive=true")
            append("&SortBy=$sortBy&SortOrder=$sortOrder&StartIndex=$start&Limit=$limit")
            append("&Fields=$fields&EnableImageTypes=Primary,Backdrop&ImageTypeLimit=1")
            append("&EnableTotalRecordCount=$countTotal")
            if (filters != null) append("&Filters=$filters")
            if (genre != null) append("&Genres=${encode(genre)}")
            if (person != null) append("&Person=${encode(person)}")
            if (!personType.isNullOrBlank()) append("&PersonTypes=${encode(personType)}")
            if (personIds.isNotEmpty()) append("&PersonIds=${personIds.joinToString(",") { encode(it) }}")
            if (!search.isNullOrBlank()) append("&SearchTerm=${encode(search)}")
        }
        val json = JSONObject(call("GET", query))
        val items = parseItems(json)
        return MoviePage(items, if (countTotal) json.optInt("TotalRecordCount", items.size) else items.size)
    }

    fun genres(): List<String> {
        val userId = store.userId ?: error("Sign in again.")
        val json = JSONObject(call("GET", "/Genres?UserId=$userId&IncludeItemTypes=Movie&Recursive=true&SortBy=SortName"))
        val array = json.optJSONArray("Items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString("Name").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
    }

    /** Ids of people whose name matches [search]. */
    fun findPeople(search: String, limit: Int): List<String> {
        val userId = store.userId ?: error("Sign in again.")
        val json = JSONObject(call("GET", "/Persons?UserId=$userId&SearchTerm=${encode(search)}&Limit=$limit"))
        val array = json.optJSONArray("Items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val id = array.optJSONObject(i)?.optString("Id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }
    }

    fun similar(id: String, limit: Int): List<Movie> {
        val userId = store.userId ?: error("Sign in again.")
        val json = JSONObject(call("GET", "/Items/$id/Similar?UserId=$userId&Limit=$limit&Fields=$GRID_FIELDS"))
        val array = json.optJSONArray("Items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("Type") != "Movie" || item.optString("Id").isBlank()) continue
                add(parseMovie(item))
            }
        }
    }

    /** Movies and episodes that were stopped part-way, most recent first. */
    fun resume(limit: Int, fields: String = HOME_FIELDS): List<Movie> {
        val userId = store.userId ?: error("Sign in again.")
        val json = JSONObject(
            call("GET", "/Users/$userId/Items/Resume?Limit=$limit&MediaTypes=Video&IncludeItemTypes=Movie,Episode&Fields=$fields$IMAGES")
        )
        return parseItems(json)
    }

    /** The next unwatched episode of each show being watched, or of one show when [seriesId] is given. */
    fun nextUp(limit: Int, seriesId: String? = null, fields: String = HOME_FIELDS): List<Movie> {
        val userId = store.userId ?: error("Sign in again.")
        val series = seriesId?.let { "&SeriesId=${encode(it)}" }.orEmpty()
        val json = JSONObject(call("GET", "/Shows/NextUp?UserId=$userId&Limit=$limit&Fields=$fields$IMAGES$series"))
        return parseItems(json)
    }

    fun seasons(seriesId: String): List<Movie> {
        val userId = store.userId ?: error("Sign in again.")
        val json = JSONObject(call("GET", "/Shows/${encode(seriesId)}/Seasons?UserId=$userId&Fields=$GRID_FIELDS$IMAGES"))
        return parseItems(json)
    }

    fun episodes(seriesId: String, seasonId: String): List<Movie> {
        val userId = store.userId ?: error("Sign in again.")
        val json = JSONObject(
            call("GET", "/Shows/${encode(seriesId)}/Episodes?UserId=$userId&SeasonId=${encode(seasonId)}&Fields=Overview$IMAGES")
        )
        return parseItems(json)
    }

    /** The episode after [episode] in the show's order, across seasons, or null at the end of the show. */
    fun episodeAfter(episode: Movie): Movie? {
        val userId = store.userId ?: error("Sign in again.")
        val seriesId = episode.seriesId ?: return null
        val json = JSONObject(
            call("GET", "/Shows/${encode(seriesId)}/Episodes?UserId=$userId&StartItemId=${encode(episode.id)}&Limit=2&Fields=Overview$IMAGES")
        )
        return parseItems(json).getOrNull(1)?.takeIf { it.id != episode.id }
    }

    /** A poster for [item]: episodes use their show's, since their own picture is a wide still. */
    fun posterUrl(item: Movie, width: Int = 360): String? =
        if (item.isEpisode && item.seriesId != null && item.seriesPrimaryTag != null) {
            imageUrl(item.seriesId, "Primary", item.seriesPrimaryTag, width)
        } else {
            imageUrl(item.id, "Primary", item.primaryTag, width)
        }

    fun backdropUrl(item: Movie, width: Int): String? = imageUrl(item.backdropOwner ?: item.id, "Backdrop", item.backdropTag, width)

    fun item(id: String): Movie {
        val userId = store.userId ?: error("Sign in again.")
        val fields = "Overview,Taglines,Tags,Genres,People,Studios,ProviderIds,CommunityRating,OfficialRating,MediaStreams,BackdropImageTags,Chapters"
        val text = call("GET", "/Users/$userId/Items/$id?Fields=$fields")
        return parseMovie(JSONObject(text))
    }

    fun imageUrl(itemId: String, kind: String, tag: String?, width: Int): String? {
        if (tag.isNullOrBlank()) return null
        val encoded = URLEncoder.encode(tag, "UTF-8")
        return "${base()}/Items/$itemId/Images/$kind?maxWidth=$width&quality=85&tag=$encoded"
    }

    fun userImageUrl(userId: String): String = "${base()}/Users/$userId/Images/Primary?maxWidth=240&quality=85"

    fun playback(
        itemId: String,
        startTicks: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
        burn: Boolean,
        mode: PlayMode
    ): PlaybackPlan {
        val userId = store.userId ?: error("Sign in again.")
        val effective = if (burn) PlayMode.Transcode else mode
        val directAllowed = effective == PlayMode.Direct
        val copyVideo = effective != PlayMode.Transcode
        val copyAudio = effective == PlayMode.Direct || effective == PlayMode.Remux
        val body = JSONObject()
            .put("UserId", userId)
            .put("MaxStreamingBitrate", MAX_BITRATE)
            .put("StartTimeTicks", startTicks)
            .put("DeviceProfile", deviceProfile(burn, copyVideo, copyAudio))
            .put("AutoOpenLiveStream", true)
            .put("EnableDirectPlay", directAllowed)
            .put("EnableDirectStream", directAllowed)
            .put("EnableTranscoding", true)
            .put("AllowVideoStreamCopy", copyVideo)
            .put("AllowAudioStreamCopy", copyAudio)
        if (audioIndex != null) body.put("AudioStreamIndex", audioIndex)
        if (subtitleIndex != null) body.put("SubtitleStreamIndex", subtitleIndex)
        val json = JSONObject(call("POST", "/Items/$itemId/PlaybackInfo", body))
        val errorCode = json.optString("ErrorCode")
        if (errorCode.isNotBlank()) throw JellyfinException(500, "PlaybackInfo $errorCode")
        val sourceJson = json.optJSONArray("MediaSources")?.optJSONObject(0) ?: throw JellyfinException(404, "No media source")
        val source = parseSource(sourceJson)
        val direct = directAllowed && (source.directPlay || source.directStream)
        if (!direct && source.transcodingUrl.isNullOrBlank()) throw JellyfinException(404, "No media source")
        return PlaybackPlan(json.optString("PlaySessionId"), source, hls = !direct, burn = burn, mode = effective)
    }

    fun streamUrl(itemId: String, plan: PlaybackPlan): String {
        val key = URLEncoder.encode(store.token.orEmpty(), "UTF-8")
        if (plan.hls) {
            val path = plan.source.transcodingUrl ?: throw JellyfinException(404, "No media source")
            val join = if (path.contains("?")) "&" else "?"
            val reencode = plan.burn || plan.mode == PlayMode.Transcode ||
                Regex("TranscodeReasons=[^&]*Video", RegexOption.IGNORE_CASE).containsMatchIn(path)
            if (!reencode) return base() + path + join + "api_key=" + key
            // The Pi can't re-encode above 1080p in real time, even when the TV could show 4K.
            val width = minOf(sizeParam(path, "MaxWidth") ?: 1920, 1920)
            val height = minOf(sizeParam(path, "MaxHeight") ?: 1080, 1080)
            val trimmed = path.replace(Regex("(?<=[?&])Max(Width|Height)=\\d+&?", RegexOption.IGNORE_CASE), "").trimEnd('&')
            val trimmedJoin = if (trimmed.contains("?")) "&" else "?"
            return base() + trimmed + trimmedJoin + "api_key=" + key + "&MaxWidth=$width&MaxHeight=$height"
        }
        val sourceId = URLEncoder.encode(plan.source.id, "UTF-8")
        val session = URLEncoder.encode(plan.playSessionId, "UTF-8")
        return "${base()}/Videos/$itemId/stream?Static=true&MediaSourceId=$sourceId&PlaySessionId=$session&api_key=$key"
    }

    private fun sizeParam(path: String, name: String): Int? =
        Regex("[?&]$name=(\\d+)", RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1)?.toIntOrNull()

    fun subtitleVtt(itemId: String, sourceId: String, index: Int): String {
        val text = call("GET", "/Videos/$itemId/$sourceId/Subtitles/$index/Stream.vtt")
        return cleanVtt(text)
    }

    fun report(path: String, itemId: String, plan: PlaybackPlan, ticks: Long, paused: Boolean, audioIndex: Int?, subtitleIndex: Int?) {
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("MediaSourceId", plan.source.id)
            .put("PlaySessionId", plan.playSessionId)
            .put("PositionTicks", ticks)
            .put("IsPaused", paused)
            .put("CanSeek", true)
            .put("PlayMethod", if (plan.hls) "Transcode" else "DirectPlay")
        if (audioIndex != null) body.put("AudioStreamIndex", audioIndex)
        body.put("SubtitleStreamIndex", subtitleIndex ?: -1)
        call("POST", path, body)
    }

    fun stopEncoding(playSessionId: String) {
        if (playSessionId.isBlank()) return
        runCatching {
            call("DELETE", "/Videos/ActiveEncodings?deviceId=${URLEncoder.encode(store.deviceId, "UTF-8")}&playSessionId=${URLEncoder.encode(playSessionId, "UTF-8")}")
        }
    }

    fun toggleFavorite(itemId: String, on: Boolean) {
        val userId = store.userId ?: return
        call(if (on) "DELETE" else "POST", "/Users/$userId/FavoriteItems/$itemId", if (on) null else JSONObject())
    }

    fun togglePlayed(itemId: String, on: Boolean) {
        val userId = store.userId ?: return
        call(if (on) "DELETE" else "POST", "/Users/$userId/PlayedItems/$itemId", if (on) null else JSONObject())
    }

    fun remoteSubtitles(itemId: String, language: String): List<RemoteSubtitle> {
        val config = pluginConfig()
        if (config != null && config.optString("Username").isBlank()) {
            throw JellyfinException(401, "OpenSubtitles login missing")
        }
        val text = call("GET", "/Items/$itemId/RemoteSearch/Subtitles/$language")
        val array = JSONArray(text.ifBlank { "[]" })
        return buildList {
            for (i in 0 until array.length()) {
                val hit = array.optJSONObject(i) ?: continue
                add(parseRemoteSubtitle(hit))
            }
        }.sortedByDescending { it.rank }
    }

    fun downloadSubtitle(itemId: String, subtitleId: String) {
        val encoded = URLEncoder.encode(subtitleId, "UTF-8")
        call("POST", "/Items/$itemId/RemoteSearch/Subtitles/$encoded", JSONObject())
    }

    fun pluginConfig(): JSONObject? {
        return try {
            JSONObject(call("GET", "/Plugins/$OPEN_SUBTITLES/Configuration"))
        } catch (error: JellyfinException) {
            if (error.code == 404) null else throw error
        }
    }

    fun savePluginConfig(username: String, password: String) {
        val current = pluginConfig() ?: throw JellyfinException(404, "OpenSubtitles is not installed")
        val nextPassword = password.ifBlank { current.optString("Password") }
        current.put("Username", username)
        current.put("Password", nextPassword)
        current.put("CredentialsInvalid", false)
        call("POST", "/Plugins/$OPEN_SUBTITLES/Configuration", current)
    }

    private fun deviceProfile(burn: Boolean, copyVideo: Boolean, copyAudio: Boolean): JSONObject {
        val device = codecs()
        val video = device.video.joinToString(",")
        val audio = device.audio.joinToString(",")
        val direct = JSONArray().put(
            JSONObject()
                .put("Container", "mp4,m4v,mov,mkv,webm,ts,m2ts")
                .put("Type", "Video")
                .put("VideoCodec", video)
                .put("AudioCodec", audio)
        )
        val copyableAudio = if (copyAudio) {
            device.audio.filter { it in setOf("aac", "mp3", "ac3", "eac3", "flac", "opus") }.joinToString(",")
        } else {
            "aac"
        }
        val copyableVideo = if (copyVideo && !burn) {
            device.video.filter { it in setOf("hevc", "h264", "av1", "vp9") }.joinToString(",")
        } else {
            "h264"
        }
        val transcode = JSONArray().put(
            JSONObject()
                .put("Container", "ts")
                .put("Type", "Video")
                .put("VideoCodec", copyableVideo)
                .put("AudioCodec", copyableAudio)
                .put("Context", "Streaming")
                .put("Protocol", "hls")
                .put("MaxAudioChannels", device.maxAudioChannels.toString())
                .put("MinSegments", "1")
                .put("BreakOnNonKeyFrames", false)
        )
        val codecProfiles = JSONArray()
        for (codec in device.video) {
            val conditions = JSONArray()
            // Don't hand the TV a picture bigger than its decoder can handle smoothly.
            device.limits[codec]?.let { limit ->
                conditions.put(condition("LessThanEqual", "Width", limit.width.toString()))
                conditions.put(condition("LessThanEqual", "Height", limit.height.toString()))
            }
            // 10-bit H.264 (Hi10P) has no hardware decoder on TVs; 10-bit HEVC only where the decoder says so.
            if (codec == "h264" || (codec == "hevc" && !device.hevcMain10)) {
                conditions.put(condition("LessThanEqual", "VideoBitDepth", "8"))
            }
            // Dolby Vision without an HDR10 or SDR fallback layer (profile 5) shows purple and green
            // on TVs without a Dolby Vision decoder. Every other kind of HDR plays fine.
            if (!device.dolbyVision && (codec == "hevc" || codec == "av1") && serverTellsDolbyVisionLayers()) {
                conditions.put(condition("EqualsAny", "VideoRangeType", PLAYABLE_WITHOUT_DOLBY_VISION))
            }
            if (conditions.length() > 0) {
                codecProfiles.put(
                    JSONObject()
                        .put("Type", "Video")
                        .put("Codec", codec)
                        .put("Conditions", conditions)
                )
            }
        }
        val subs = JSONArray()
        listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa").forEach {
            subs.put(JSONObject().put("Format", it).put("Method", "External"))
        }
        listOf("srt", "subrip", "ass", "ssa", "pgssub", "dvdsub", "dvbsub", "vtt").forEach {
            subs.put(JSONObject().put("Format", it).put("Method", "Embed"))
        }
        if (burn) {
            listOf("pgssub", "dvdsub", "dvbsub").forEach {
                subs.put(JSONObject().put("Format", it).put("Method", "Encode"))
            }
        }
        return JSONObject()
            .put("Name", "Living Room TV")
            .put("MaxStreamingBitrate", MAX_BITRATE)
            .put("MaxStaticBitrate", MAX_BITRATE)
            .put("DirectPlayProfiles", direct)
            .put("TranscodingProfiles", transcode)
            .put("CodecProfiles", codecProfiles)
            .put("SubtitleProfiles", subs)
    }

    /**
     * Jellyfin 10.9 started telling Dolby Vision files with an HDR10 fallback apart from those without.
     * Older servers call both "DOVI", so filtering there would re-encode every Dolby Vision film.
     */
    private fun serverTellsDolbyVisionLayers(): Boolean {
        val server = base()
        if (versionFor != server) {
            serverVersion = runCatching {
                JSONObject(call("GET", "/System/Info/Public", auth = false)).optString("Version")
            }.getOrNull()
            versionFor = server
        }
        val parts = serverVersion.orEmpty().split(".").mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return false
        val minor = parts.getOrNull(1) ?: 0
        return major > 10 || (major == 10 && minor >= 9)
    }

    private fun condition(kind: String, property: String, value: String): JSONObject =
        JSONObject()
            .put("Condition", kind)
            .put("Property", property)
            .put("Value", value)
            .put("IsRequired", false)

    private fun parseItems(json: JSONObject): List<Movie> {
        val array = json.optJSONArray("Items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("Id").isBlank()) continue
                add(parseMovie(item))
            }
        }
    }

    private fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        server: String = base(),
        client: OkHttpClient = http
    ): String {
        val request = Request.Builder()
            .url(server.trimEnd('/') + path)
            .header("X-Emby-Authorization", authorizationHeader())
        if (auth) store.token?.let { request.header("X-Emby-Token", it) }
        when (method) {
            "GET" -> request.get()
            "DELETE" -> request.delete()
            else -> request.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) throw JellyfinException(401, "Sign in again.")
            if (!response.isSuccessful) throw JellyfinException(response.code, "Request failed (${response.code})")
            return text
        }
    }

    private fun authorizationHeader(): String {
        val token = store.token?.let { ", Token=\"$it\"" }.orEmpty()
        return "MediaBrowser Client=\"LivingRoom\", Device=\"Living Room TV\", DeviceId=\"${store.deviceId}\", Version=\"1.7.0\"$token"
    }

    /** Gives up quickly, so trying a wrong address doesn't leave the TV hanging. */
    private val quick: OkHttpClient by lazy {
        http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun base(): String = store.server.trimEnd('/')

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Enough for a poster and the top of the detail page. Cast and the rest load when a movie opens. */
        const val GRID_FIELDS = "Overview,Genres,BackdropImageTags"
        const val HOME_FIELDS = "Overview,BackdropImageTags"
        private const val IMAGES = "&EnableImageTypes=Primary,Backdrop&ImageTypeLimit=1"
        const val OPEN_SUBTITLES = "4b9ed42f518548b598036ff2989014c4"
        private const val MAX_BITRATE = 150_000_000
        private const val PLAYABLE_WITHOUT_DOLBY_VISION =
            "SDR|HDR10|HDR10Plus|HLG|DOVIWithSDR|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithHLG"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
