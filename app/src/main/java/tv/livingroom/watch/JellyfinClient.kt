package tv.livingroom.watch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class JellyfinException(val code: Int, message: String) : Exception(message)

class JellyfinClient(
    private val store: SessionStore,
    private val http: OkHttpClient,
    private val codecs: () -> DeviceCodecs
) {
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
        val text = call("POST", "/Users/AuthenticateByName", body, auth = false)
        val json = JSONObject(text)
        val user = json.getJSONObject("User")
        store.saveSession(json.getString("AccessToken"), user.getString("Id"), user.optString("Name"))
    }

    fun movies(): List<Movie> {
        val userId = store.userId ?: error("Sign in again.")
        val text = call(
            "GET",
            "/Users/$userId/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=SortName&SortOrder=Ascending" +
                "&Fields=Overview,Genres,People,PrimaryImageAspectRatio,BackdropImageTags,DateCreated&Limit=1000"
        )
        return parseItems(text)
    }

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
        forceTranscode: Boolean
    ): PlaybackPlan {
        val userId = store.userId ?: error("Sign in again.")
        val body = JSONObject()
            .put("UserId", userId)
            .put("MaxStreamingBitrate", MAX_BITRATE)
            .put("StartTimeTicks", startTicks)
            .put("DeviceProfile", deviceProfile(burn, forceTranscode))
            .put("AutoOpenLiveStream", true)
            .put("EnableDirectPlay", !forceTranscode && !burn)
            .put("EnableDirectStream", !forceTranscode && !burn)
            .put("EnableTranscoding", true)
            .put("AllowVideoStreamCopy", !forceTranscode && !burn)
            .put("AllowAudioStreamCopy", !forceTranscode)
        if (audioIndex != null) body.put("AudioStreamIndex", audioIndex)
        if (subtitleIndex != null) body.put("SubtitleStreamIndex", subtitleIndex)
        val json = JSONObject(call("POST", "/Items/$itemId/PlaybackInfo", body))
        val errorCode = json.optString("ErrorCode")
        if (errorCode.isNotBlank()) throw JellyfinException(500, "PlaybackInfo $errorCode")
        val sourceJson = json.optJSONArray("MediaSources")?.optJSONObject(0) ?: throw JellyfinException(404, "No media source")
        val source = parseSource(sourceJson)
        val direct = !forceTranscode && !burn && (source.directPlay || source.directStream)
        if (!direct && source.transcodingUrl.isNullOrBlank()) throw JellyfinException(404, "No media source")
        return PlaybackPlan(json.optString("PlaySessionId"), source, hls = !direct, burn = burn)
    }

    fun streamUrl(itemId: String, plan: PlaybackPlan): String {
        val key = URLEncoder.encode(store.token.orEmpty(), "UTF-8")
        if (plan.hls) {
            val path = plan.source.transcodingUrl ?: throw JellyfinException(404, "No media source")
            val join = if (path.contains("?")) "&" else "?"
            val reencode = plan.burn || Regex("TranscodeReasons=[^&]*Video", RegexOption.IGNORE_CASE).containsMatchIn(path)
            val cap = if (reencode && !path.contains("MaxWidth=", ignoreCase = true)) "&MaxWidth=1920&MaxHeight=1080" else ""
            return base() + path + join + "api_key=" + key + cap
        }
        val sourceId = URLEncoder.encode(plan.source.id, "UTF-8")
        val session = URLEncoder.encode(plan.playSessionId, "UTF-8")
        return "${base()}/Videos/$itemId/stream?Static=true&MediaSourceId=$sourceId&PlaySessionId=$session&api_key=$key"
    }

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

    private fun deviceProfile(burn: Boolean, forceTranscode: Boolean): JSONObject {
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
        val copyableAudio = if (forceTranscode) {
            "aac"
        } else {
            device.audio.filter { it in setOf("aac", "mp3", "ac3", "eac3", "flac", "opus") }.joinToString(",")
        }
        val copyableVideo = if (forceTranscode || burn) {
            "h264"
        } else {
            device.video.filter { it in setOf("hevc", "h264", "av1", "vp9") }.joinToString(",")
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
        if (!device.hevcMain10) {
            codecProfiles.put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Codec", "hevc")
                    .put(
                        "Conditions",
                        JSONArray().put(
                            JSONObject()
                                .put("Condition", "LessThanEqual")
                                .put("Property", "VideoBitDepth")
                                .put("Value", "8")
                                .put("IsRequired", false)
                        )
                    )
            )
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

    private fun parseItems(text: String): List<Movie> {
        val array = JSONObject(text).optJSONArray("Items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("Id").isBlank()) continue
                add(parseMovie(item))
            }
        }
    }

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String {
        val request = Request.Builder()
            .url(base() + path)
            .header("X-Emby-Authorization", authorizationHeader())
        if (auth) store.token?.let { request.header("X-Emby-Token", it) }
        when (method) {
            "GET" -> request.get()
            "DELETE" -> request.delete()
            else -> request.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        http.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) throw JellyfinException(401, "Sign in again.")
            if (!response.isSuccessful) throw JellyfinException(response.code, "Request failed (${response.code})")
            return text
        }
    }

    private fun authorizationHeader(): String {
        val token = store.token?.let { ", Token=\"$it\"" }.orEmpty()
        return "MediaBrowser Client=\"LivingRoom\", Device=\"Living Room TV\", DeviceId=\"${store.deviceId}\", Version=\"1.1.0\"$token"
    }

    private fun base(): String = store.server.trimEnd('/')

    companion object {
        const val OPEN_SUBTITLES = "4b9ed42f518548b598036ff2989014c4"
        private const val MAX_BITRATE = 150_000_000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
