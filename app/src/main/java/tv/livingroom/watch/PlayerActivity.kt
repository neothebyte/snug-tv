package tv.livingroom.watch

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.OptIn as AndroidOptIn

private enum class Panel { Subtitles, Online, Audio, Chapters, Speed, Info }

@AndroidOptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {
    private val app by lazy { application as LivingRoomApp }
    private val api get() = app.api
    private val store get() = app.store

    private var exo: ExoPlayer? = null
    private var movie: Movie? = null
    private var plan: PlaybackPlan? = null
    private var subtitleIndex: Int? = null
    private var audioIndex: Int? = null
    private var subsChosen = false
    private var sentStart = false
    private var reportedStop = false
    /** The least server work this title has played with; a failure only ever moves it down the list. */
    private var floor = PlayMode.Direct
    private var retriedNetwork = false
    private var displaySwitched = false
    private var libraryEmpty = false
    private var pendingTextTrack: MediaStreamInfo? = null
    private var pendingAudioTrack: MediaStreamInfo? = null
    private var prepareJob: Job? = null
    private var subtitleJob: Job? = null
    private var seekJob: Job? = null
    private var pendingSeekMs: Long? = null
    private val consumedUps = mutableSetOf<Int>()

    private var title by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var loadingNote by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var playing by mutableStateOf(false)
    private var controls by mutableStateOf(true)
    private var panel by mutableStateOf<Panel?>(null)
    private var lastPanel by mutableStateOf<Panel?>(null)
    private var stamp by mutableIntStateOf(0)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private var seekPreviewMs by mutableStateOf<Long?>(null)
    private var cues by mutableStateOf<List<Cue>>(emptyList())
    private var cueDelayMs by mutableLongStateOf(0L)
    private var subNote by mutableStateOf("")
    private var speed by mutableFloatStateOf(1f)
    private var chapters by mutableStateOf<List<ChapterMark>>(emptyList())
    private var audioStreams by mutableStateOf<List<MediaStreamInfo>>(emptyList())
    private var subStreams by mutableStateOf<List<MediaStreamInfo>>(emptyList())
    private var selectedSub by mutableStateOf<Int?>(null)
    private var selectedAudio by mutableStateOf<Int?>(null)
    private var showAllSubs by mutableStateOf(false)
    private var remoteHits by mutableStateOf<List<RemoteSubtitle>>(emptyList())
    private var remoteNote by mutableStateOf("")
    /** The episode offered in the Up next card; null while the card isn't showing. */
    private var upNext by mutableStateOf<Movie?>(null)
    private var nextJob: Job? = null
    /** False until this title's first picture, while the artwork start screen shows. */
    private var started by mutableStateOf(false)
    /** The title has played to its end. */
    private var ended by mutableStateOf(false)
    private var art by mutableStateOf<String?>(null)
    private var headline by mutableStateOf("")
    private var kicker by mutableStateOf("")
    private var overview by mutableStateOf("")
    /** Intro and credits times, when the server has them. */
    private var segments: List<MediaSegment> = emptyList()
    /** The episode after this one, looked up as soon as an episode starts. */
    private var nextEpisode: Movie? = null
    private var extrasJob: Job? = null
    private var upNextDismissed = false
    /** Start times of the intros, recaps and previews already skipped in this title. */
    private val skipped = mutableSetOf<Long>()
    /** Where the part being offered for skipping ends while the Skip button shows; null the rest of the time. */
    private var skipEndMs by mutableStateOf<Long?>(null)
    private var skipLabel by mutableStateOf("Skip intro")
    /** Episodes started by the Up next countdown since anyone last pressed the remote. */
    private var autoChain = 0
    /** The Up next card is asking "Are you still watching?" instead of counting down. */
    private var asking by mutableStateOf(false)
    /** The video decoder in use, for the Playback info panel. */
    private var videoDecoder = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        libraryEmpty = intent.getBooleanExtra(EXTRA_LIBRARY_EMPTY, false)
        val itemId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        art = intent.getStringExtra(EXTRA_ART)
        headline = intent.getStringExtra(EXTRA_HEADLINE).orEmpty()
        exo = buildPlayer()
        setContent { LivingRoomTheme { PlayerScreen() } }
        lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (exo?.isPlaying == true) report()
            }
        }
        if (itemId.isBlank()) {
            showError(IllegalStateException("missing"))
        } else {
            start(itemId, fromStart)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("SnugTV/1.10.0")
            .setDefaultRequestProperties(mapOf("X-Emby-Token" to store.token.orEmpty()))
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(this)
        selector.parameters = selector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setTunnelingEnabled(false)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl())
            // Holds a Wi-Fi lock while playing so the box doesn't drop into Wi-Fi power saving mid-film.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        videoDecoder = decoderName
                    }
                })
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        ended = state == Player.STATE_ENDED
                        loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && error == null && plan == null
                        if (state == Player.STATE_READY) {
                            retriedNetwork = false
                            loading = false
                            loadingNote = ""
                            started = true
                            durationMs = player.duration.takeIf { it > 0 } ?: durationMs
                            if (!sentStart) report()
                        }
                        if (state == Player.STATE_ENDED) {
                            reportStop()
                            val finished = movie
                            // Nobody answered "Are you still watching?", so stop here rather than play on.
                            if (finished?.isEpisode == true && !stillWatchingDue()) offerNext(finished) else finish()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                        if (sentStart) report()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        applyPendingTracks(tracks)
                    }

                    override fun onPlayerError(failure: PlaybackException) {
                        val current = plan
                        if (current != null && isNetworkError(failure) && !retriedNetwork) {
                            // A Wi-Fi blip: try the same stream again before changing anything.
                            retriedNetwork = true
                            loadingNote = "Reconnecting…"
                            prepare(currentTicks(), current.mode)
                            return
                        }
                        val next = current?.let { fallbackAfter(failure, it) }
                        if (next == null) {
                            showError(failure)
                            return
                        }
                        floor = next
                        loadingNote = if (next == PlayMode.Transcode) "Converting for this TV…" else "Repackaging for this TV…"
                        prepare(currentTicks(), next)
                    }
                })
            }
    }

    override fun onPause() {
        if (isFinishing) {
            reportStop()
        } else {
            exo?.pause()
            report()
        }
        super.onPause()
    }

    override fun onDestroy() {
        reportStop()
        exo?.release()
        exo = null
        super.onDestroy()
    }

    private fun start(itemId: String, fromStart: Boolean) {
        lifecycleScope.launch {
            try {
                loading = true
                var loaded = withContext(Dispatchers.IO) { api.item(itemId) }
                movie = loaded
                title = if (loaded.isEpisode) "${loaded.seriesName} · ${loaded.cardTitle}" else loaded.name
                showInfo(loaded)
                loadExtras(loaded)
                chapters = loaded.chapters
                durationMs = loaded.runtimeTicks / 10_000L
                subtitleIndex = preferredSubtitleIndex(loaded.streams, store.subtitleLanguage)
                if (store.autoSubtitles && subtitleIndex == null) {
                    val language = SUB_LANGS.find { it.code == store.subtitleLanguage }?.label ?: "English"
                    loadingNote = "Looking for $language subtitles…"
                    runCatching {
                        val hits = withContext(Dispatchers.IO) { api.remoteSubtitles(loaded.id, store.subtitleLanguage) }
                        val best = hits.firstOrNull() ?: return@runCatching
                        withContext(Dispatchers.IO) { api.downloadSubtitle(loaded.id, best.id) }
                        loaded = withContext(Dispatchers.IO) { api.item(loaded.id) }
                        movie = loaded
                        subtitleIndex = preferredSubtitleIndex(loaded.streams, store.subtitleLanguage)
                    }
                    loadingNote = ""
                }
                // A show being watched again comes back to Continue watching if it was hidden.
                loaded.seriesId?.let { series -> app.background.launch { runCatching { api.setShowHidden(series, false) } } }
                // Resuming a few seconds early catches the line that was cut off.
                val back = store.resumeBackSeconds * 10_000_000L
                prepare(if (fromStart || loaded.positionTicks <= 0L) 0L else (loaded.positionTicks - back).coerceAtLeast(0L))
            } catch (failure: Exception) {
                showError(failure)
            }
        }
    }

    private fun prepare(atTicks: Long, mode: PlayMode = floor) {
        val current = movie ?: return
        prepareJob?.cancel()
        prepareJob = lifecycleScope.launch {
            error = null
            loading = true
            val previous = plan
            try {
                val chosen = current.streams.find { it.type == "Subtitle" && it.index == subtitleIndex }
                val bitmap = chosen != null && !chosen.isTextSubtitle()
                var next = withContext(Dispatchers.IO) {
                    previous?.let { api.stopEncoding(it.playSessionId) }
                    api.playback(current.id, atTicks, audioIndex, chosen?.takeIf { !bitmap }?.index, burn = false, mode = mode)
                }
                if (bitmap && next.hls) {
                    if (subsChosen) {
                        loadingNote = "Adding subtitles to the picture…"
                        next = withContext(Dispatchers.IO) {
                            api.stopEncoding(next.playSessionId)
                            api.playback(current.id, atTicks, audioIndex, chosen!!.index, burn = true, mode = PlayMode.Transcode)
                        }
                    } else {
                        subtitleIndex = null
                    }
                }
                if (previous != null && previous.playSessionId != next.playSessionId) reportStopFor(previous)
                plan = next
                sentStart = false
                reportedStop = false
                val streams = next.source.streams.ifEmpty { current.streams }
                audioStreams = streams.filter { it.type == "Audio" }
                subStreams = streams.filter { it.type == "Subtitle" }
                selectedAudio = audioIndex ?: next.source.defaultAudioIndex ?: audioStreams.firstOrNull { it.isDefault }?.index
                selectedSub = subtitleIndex
                if (!displaySwitched) {
                    displaySwitched = true
                    val fps = streams.firstOrNull { it.type == "Video" }?.frameRate?.takeIf { it > 0f }
                        ?: current.streams.firstOrNull { it.type == "Video" }?.frameRate
                        ?: 0f
                    if (store.matchFrameRate && fps > 0f) DisplayRate.match(this@PlayerActivity, fps)
                }
                val player = exo ?: return@launch
                pendingTextTrack = if (bitmap && !next.hls) chosen else null
                pendingAudioTrack = if (!next.hls) audioStreams.find { it.index == audioIndex } else null
                setTextTrackEnabled(pendingTextTrack != null)
                player.setMediaItem(mediaItem(current.id, next), atTicks / 10_000L)
                player.prepare()
                player.playWhenReady = true
                loadCues(current.id, next, chosen?.takeIf { !bitmap })
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                showError(failure)
            }
        }
    }

    private fun loadCues(itemId: String, plan: PlaybackPlan, stream: MediaStreamInfo?) {
        subtitleJob?.cancel()
        cues = emptyList()
        subNote = ""
        if (stream == null) return
        subtitleJob = lifecycleScope.launch {
            subNote = "Loading subtitles…"
            try {
                val text = withContext(Dispatchers.IO) { api.subtitleVtt(itemId, plan.source.id, stream.index) }
                cues = withContext(Dispatchers.Default) { parseCues(text) }
                subNote = if (cues.isEmpty()) "These subtitles are empty." else ""
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                subNote = "Couldn't load these subtitles."
            }
            if (subNote.isNotBlank() && cues.isEmpty()) {
                delay(4_000)
                subNote = ""
            }
        }
    }

    private fun mediaItem(itemId: String, plan: PlaybackPlan): MediaItem {
        val url = api.streamUrl(itemId, plan)
        val container = plan.source.container.lowercase()
        val mime = when {
            plan.hls -> MimeTypes.APPLICATION_M3U8
            container.contains("mkv") || container.contains("matroska") || container.contains("webm") -> MimeTypes.VIDEO_MATROSKA
            container.contains("ts") -> MimeTypes.VIDEO_MP2T
            else -> MimeTypes.VIDEO_MP4
        }
        return MediaItem.Builder().setUri(url).setMimeType(mime).build()
    }

    private fun setTextTrackEnabled(on: Boolean) {
        val player = exo ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .apply { if (!on) clearOverridesOfType(C.TRACK_TYPE_TEXT) }
            .build()
    }

    private fun applyPendingTracks(tracks: Tracks) {
        val player = exo ?: return
        val params = player.trackSelectionParameters.buildUpon()
        var changed = false
        pendingTextTrack?.let { stream ->
            val group = matchGroup(tracks, C.TRACK_TYPE_TEXT, subStreams.filter { !it.isExternal }, stream)
            if (group != null) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                pendingTextTrack = null
                changed = true
            }
        }
        pendingAudioTrack?.let { stream ->
            val group = matchGroup(tracks, C.TRACK_TYPE_AUDIO, audioStreams.filter { !it.isExternal }, stream)
            if (group != null) {
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                pendingAudioTrack = null
                changed = true
            }
        }
        if (changed) player.trackSelectionParameters = params.build()
    }

    private fun matchGroup(tracks: Tracks, type: Int, embedded: List<MediaStreamInfo>, wanted: MediaStreamInfo): Tracks.Group? {
        val groups = tracks.groups.filter { it.type == type }
        if (groups.isEmpty()) return null
        val order = embedded.sortedBy { it.index }.indexOfFirst { it.index == wanted.index }
        if (groups.size == embedded.size && order in groups.indices) return groups[order]
        val language = wanted.language.lowercase()
        return groups.firstOrNull { group ->
            val format = group.getTrackFormat(0)
            language.isNotBlank() && format.language?.lowercase()?.let { it == language || it.take(2) == language.take(2) } == true
        } ?: groups.getOrNull(order)
    }

    private fun isNetworkError(failure: PlaybackException): Boolean =
        failure.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            failure.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    /**
     * Picks the next, heavier way to play after a failure, or null when there is nothing left to try.
     * Only a picture the TV can't decode justifies re-encoding; container and sound problems are fixed
     * by repackaging, which costs the Pi almost nothing.
     */
    private fun fallbackAfter(failure: PlaybackException, current: PlaybackPlan): PlayMode? {
        if (current.burn || current.mode == PlayMode.Transcode) return null
        val mime = (failure as? ExoPlaybackException)
            ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            ?.rendererFormat?.sampleMimeType
        // 5xxx codes are the audio output refusing the sound format (for example DTS the TV can't take).
        val audioOutputFailed = failure.errorCode / 1000 == 5
        val wanted = when {
            mime != null && MimeTypes.isVideo(mime) -> PlayMode.Transcode
            audioOutputFailed || (mime != null && MimeTypes.isAudio(mime)) -> PlayMode.RemuxAudio
            current.mode == PlayMode.Direct -> PlayMode.Remux
            else -> PlayMode.Transcode
        }
        return if (wanted.ordinal > current.mode.ordinal) wanted else PlayMode.Transcode
    }

    private fun loadControl(): DefaultLoadControl {
        // Buffer further ahead so a Wi-Fi dip doesn't stall a high-bitrate file. The buffer lives in
        // the app's memory, so its size follows how much memory this TV gives the app.
        val budget = (Runtime.getRuntime().maxMemory() / 2).coerceIn(64L * MB, 200L * MB).toInt()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 120_000, 2_500, 5_000)
            .setTargetBufferBytes(budget)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    private fun currentTicks(): Long {
        val player = exo ?: return 0L
        return player.currentPosition.coerceAtLeast(0L) * 10_000L
    }

    private fun report() {
        val currentPlan = plan ?: return
        val current = movie ?: return
        if (reportedStop) return
        val path = if (!sentStart) "/Sessions/Playing" else "/Sessions/Playing/Progress"
        sentStart = true
        val ticks = currentTicks()
        val paused = exo?.isPlaying != true
        val audio = selectedAudio
        val sub = selectedSub
        app.background.launch {
            runCatching { api.report(path, current.id, currentPlan, ticks, paused, audio, sub) }
        }
    }

    private fun reportStop() {
        val currentPlan = plan ?: return
        if (reportedStop) return
        reportedStop = true
        reportStopFor(currentPlan)
    }

    private fun reportStopFor(target: PlaybackPlan) {
        val current = movie ?: return
        val player = exo
        val ticks = if (player?.playbackState == Player.STATE_ENDED) current.runtimeTicks else currentTicks()
        val audio = selectedAudio
        val sub = selectedSub
        app.background.launch {
            runCatching { api.report("/Sessions/Playing/Stopped", current.id, target, ticks, true, audio, sub) }
            if (target.hls) api.stopEncoding(target.playSessionId)
        }
    }

    /** What the start and pause screens say about [item]. */
    private fun showInfo(item: Movie) {
        headline = item.name
        kicker = if (item.isEpisode) listOf(item.seriesName, item.episodeCode).filter { it.isNotBlank() }.joinToString("  ·  ") else item.year?.toString().orEmpty()
        overview = item.overview
        if (art == null) art = api.backdropUrl(item, 1280) ?: api.imageUrl(item.id, "Primary", item.primaryTag, 1280)
    }

    /** Fetches the intro and credits times, and for an episode the one after it, in the background. */
    private fun loadExtras(item: Movie) {
        extrasJob?.cancel()
        segments = emptyList()
        nextEpisode = null
        extrasJob = lifecycleScope.launch {
            segments = runCatching { withContext(Dispatchers.IO) { api.segments(item.id) } }.getOrDefault(emptyList())
            if (item.isEpisode) {
                nextEpisode = runCatching { withContext(Dispatchers.IO) { api.episodeAfter(item) } }.getOrNull()
            }
        }
    }

    /** When the Up next card comes up: as the credits start, else 30 seconds before the end. */
    private fun upNextAtMs(): Long {
        val duration = durationMs
        val outro = segments.firstOrNull { it.type == "Outro" }
        if (outro != null && duration - outro.startMs > 5_000) return outro.startMs
        return if (duration > 300_000) duration - 30_000 else Long.MAX_VALUE
    }

    /** Shows or hides the Skip button and the Up next card as the episode plays. */
    private fun checkMoments(nowMs: Long) {
        if (!started || error != null) return
        val part = segments.firstOrNull { it.type in SKIPPABLE && nowMs >= it.startMs && nowMs < it.endMs - 1_000 && it.startMs !in skipped }
        if (part != null) skipLabel = SKIPPABLE.getValue(part.type)
        skipEndMs = part?.endMs
        val next = nextEpisode ?: return
        val at = upNextAtMs()
        if (nowMs < at - 1_000) {
            upNextDismissed = false
            if (upNext != null && !ended) upNext = null
        } else if (nowMs >= at && upNext == null && !upNextDismissed && panel == null) {
            showUpNext(next)
        }
    }

    /** Skips the intro, recap or preview the Skip button is showing for. */
    private fun skipIntro() {
        val end = skipEndMs ?: return
        segments.firstOrNull { it.type in SKIPPABLE && it.endMs == end }?.let { skipped += it.startMs }
        skipEndMs = null
        seekTo(end)
    }

    /** After a few episodes in a row with nobody touching the remote, ask before carrying on. */
    private fun stillWatchingDue(): Boolean {
        val limit = store.stillWatchingAfter
        return limit > 0 && autoChain >= limit - 1
    }

    private fun showUpNext(next: Movie) {
        panel = null
        controls = false
        asking = stillWatchingDue()
        upNext = next
    }

    private fun keepWatching() {
        upNextDismissed = true
        upNext = null
    }

    /** Offers the episode after [episode], or closes the player at the end of the show. */
    private fun offerNext(episode: Movie) {
        nextEpisode?.let {
            showUpNext(it)
            return
        }
        nextJob?.cancel()
        nextJob = lifecycleScope.launch {
            val next = runCatching { withContext(Dispatchers.IO) { api.episodeAfter(episode) } }.getOrNull()
            if (next == null) {
                finish()
                return@launch
            }
            showUpNext(next)
        }
    }

    /** Starts the offered episode in this same player, forgetting everything about the one that ended. */
    private fun playNext(auto: Boolean = false) {
        val next = upNext ?: return
        if (auto) autoChain++
        upNext = null
        asking = false
        // Leaving during the credits: tell the server where this one stopped first.
        reportStop()
        exo?.stop()
        nextJob?.cancel()
        extrasJob?.cancel()
        prepareJob?.cancel()
        segments = emptyList()
        nextEpisode = null
        upNextDismissed = false
        skipped.clear()
        skipEndMs = null
        started = false
        videoDecoder = ""
        art = api.backdropUrl(next, 1280)
        showInfo(next)
        title = "${next.seriesName} · ${next.cardTitle}"
        plan = null
        sentStart = false
        reportedStop = false
        floor = PlayMode.Direct
        retriedNetwork = false
        subtitleIndex = null
        audioIndex = null
        subsChosen = false
        pendingTextTrack = null
        pendingAudioTrack = null
        subtitleJob?.cancel()
        cues = emptyList()
        subNote = ""
        remoteHits = emptyList()
        remoteNote = ""
        positionMs = 0L
        durationMs = 0L
        intent.putExtra(EXTRA_ID, next.id)
        start(next.id, false)
    }

    private fun showError(failure: Throwable) {
        loading = false
        loadingNote = ""
        panel = null
        error = playbackError(failure, libraryEmpty)
    }

    private fun retry() {
        floor = PlayMode.Direct
        retriedNetwork = false
        error = null
        if (movie == null) {
            start(intent.getStringExtra(EXTRA_ID).orEmpty(), false)
        } else {
            prepare(currentTicks().takeIf { it > 0 } ?: (movie?.positionTicks ?: 0L))
        }
    }

    private fun togglePlay() {
        val player = exo ?: return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val player = exo ?: return
        val duration = (player.duration.takeIf { it > 0 } ?: durationMs).coerceAtLeast(0L)
        val base = pendingSeekMs ?: player.currentPosition
        val target = (base + deltaMs).coerceIn(0L, if (duration > 0) duration - 1_000L else Long.MAX_VALUE)
        pendingSeekMs = target
        seekPreviewMs = target
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            delay(if (plan?.hls == true) 650 else 350)
            exo?.seekTo(target)
            pendingSeekMs = null
            delay(900)
            seekPreviewMs = null
        }
    }

    private fun seekTo(ms: Long) {
        pendingSeekMs = null
        seekJob?.cancel()
        seekPreviewMs = null
        exo?.seekTo(ms.coerceAtLeast(0L))
    }

    private fun showControls() {
        controls = true
        stamp++
    }

    private fun openPanel(next: Panel) {
        lastPanel = next
        panel = next
        stamp++
    }

    private fun closePanel() {
        panel = null
        showControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Someone is there: the "still watching" count starts again.
        if (event.action == KeyEvent.ACTION_DOWN) autoChain = 0
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goBack()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (consumedUps.remove(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (handleKey(code, event.repeatCount)) {
            consumedUps.add(code)
            return true
        }
        if (controls) stamp++
        return super.dispatchKeyEvent(event)
    }

    private fun goBack() {
        when {
            upNext != null && !ended -> keepWatching()
            upNext != null -> finish()
            error != null -> finish()
            panel == Panel.Online -> openPanel(Panel.Subtitles)
            panel != null -> closePanel()
            controls && !loading && exo?.isPlaying == true -> controls = false
            else -> finish()
        }
    }

    private fun handleKey(code: Int, repeat: Int): Boolean {
        // The up-next card's buttons take the remote while it shows.
        if (upNext != null) return false
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlay()
                if (exo?.isPlaying != true) showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exo?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exo?.pause()
                showControls()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                seekBy(30_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                seekBy(-10_000)
                return true
            }
        }
        if (error != null || panel != null || controls) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(if (repeat > 2) -30_000 else -10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(if (repeat > 2) 30_000 else 10_000)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (skipEndMs != null && started) {
                    skipIntro()
                } else {
                    togglePlay()
                    showControls()
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> showControls()
            KeyEvent.KEYCODE_INFO -> {
                showControls()
                openPanel(Panel.Info)
            }
            KeyEvent.KEYCODE_CAPTIONS -> {
                showControls()
                openPanel(Panel.Subtitles)
            }
            else -> return false
        }
        return true
    }

    private fun chooseSubtitle(index: Int?) {
        subsChosen = true
        panel = null
        showControls()
        val current = movie ?: return
        val stream = current.streams.find { it.type == "Subtitle" && it.index == index }
        val currentPlan = plan
        subtitleIndex = index
        selectedSub = index
        cueDelayMs = 0L
        when {
            currentPlan == null -> Unit
            stream == null -> {
                loadCues(current.id, currentPlan, null)
                setTextTrackEnabled(false)
                if (currentPlan.burn) prepare(currentTicks())
                else report()
            }
            stream.isTextSubtitle() -> {
                setTextTrackEnabled(false)
                if (currentPlan.burn) {
                    prepare(currentTicks())
                } else {
                    loadCues(current.id, currentPlan, stream)
                    report()
                }
            }
            !currentPlan.hls -> {
                loadCues(current.id, currentPlan, null)
                pendingTextTrack = stream
                exo?.currentTracks?.let { applyPendingTracks(it) }
                report()
            }
            else -> prepare(currentTicks())
        }
    }

    private fun chooseAudio(index: Int) {
        panel = null
        showControls()
        audioIndex = index
        selectedAudio = index
        val currentPlan = plan ?: return
        if (!currentPlan.hls) {
            pendingAudioTrack = audioStreams.find { it.index == index }
            exo?.currentTracks?.let { applyPendingTracks(it) }
            report()
        } else {
            prepare(currentTicks())
        }
    }

    private fun searchOnline() {
        val current = movie ?: return
        remoteHits = emptyList()
        remoteNote = "Searching…"
        openPanel(Panel.Online)
        lifecycleScope.launch {
            try {
                val hits = withContext(Dispatchers.IO) { api.remoteSubtitles(current.id, store.subtitleLanguage) }
                remoteHits = hits
                remoteNote = if (hits.isEmpty()) "No subtitles found." else ""
            } catch (failure: Exception) {
                remoteNote = when {
                    failure is JellyfinException && failure.code == 401 -> "Add an OpenSubtitles account in Settings first."
                    failure is JellyfinException && failure.code == 404 -> "OpenSubtitles isn't installed on the server."
                    else -> "Couldn't search for subtitles."
                }
            }
        }
    }

    private fun useRemote(hit: RemoteSubtitle) {
        val current = movie ?: return
        lifecycleScope.launch {
            remoteNote = "Downloading…"
            try {
                val before = current.streams.filter { it.type == "Subtitle" }.map { it.index }.toSet()
                withContext(Dispatchers.IO) { api.downloadSubtitle(current.id, hit.id) }
                val fresh = withContext(Dispatchers.IO) { api.item(current.id) }
                movie = fresh
                subStreams = fresh.streams.filter { it.type == "Subtitle" }
                val added = fresh.streams.lastOrNull { it.type == "Subtitle" && it.index !in before }?.index
                    ?: preferredSubtitleIndex(fresh.streams, store.subtitleLanguage)
                subsChosen = true
                subtitleIndex = added
                selectedSub = added
                panel = null
                showControls()
                prepare(currentTicks())
            } catch (_: Exception) {
                remoteNote = "That subtitle didn't download."
            }
        }
    }

    private fun changeSpeed(next: Float) {
        speed = next
        exo?.setPlaybackSpeed(next)
        panel = null
        showControls()
    }

    @Composable
    private fun PlayerScreen() {
        LaunchedEffect(Unit) {
            while (isActive) {
                exo?.let { player ->
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                    player.duration.takeIf { it > 0 }?.let { durationMs = it }
                    checkMoments(positionMs)
                }
                // Nothing on screen moves with the clock while the controls are hidden.
                delay(if (controls || seekPreviewMs != null) 250 else 500)
            }
        }
        LaunchedEffect(loading, plan) {
            if (!loading || plan?.hls != true) return@LaunchedEffect
            delay(12_000)
            if (loading && loadingNote.isBlank()) {
                loadingNote = "Still starting. The server is converting this movie for this TV, which can be slow for 4K."
            }
        }
        LaunchedEffect(controls, panel, stamp, playing, loading) {
            if (!controls || panel != null || !playing || loading) return@LaunchedEffect
            delay(store.controlsSeconds * 1000L)
            controls = false
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            exo?.let { player ->
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            isFocusable = false
                            isFocusableInTouchMode = false
                            this.player = player
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            setKeepContentOnPlayerReset(true)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Subtitles()
            AnimatedVisibility(!started && error == null, enter = fadeIn(), exit = fadeOut(tween(450))) {
                StartCover()
            }
            PauseInfo()
            if (loading && started && error == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cream, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                    if (loadingNote.isNotBlank()) Text(loadingNote, style = TvType.body, color = Cream, modifier = Modifier.padding(top = 14.dp))
                }
            }
            val preview = seekPreviewMs
            if (preview != null && !controls && error == null) {
                SeekStrip(preview)
            }
            AnimatedVisibility(started && controls && panel == null && error == null && upNext == null, enter = fadeIn(), exit = fadeOut()) {
                Controls()
            }
            AnimatedVisibility(
                started && skipEndMs != null && !controls && seekPreviewMs == null && panel == null && error == null && upNext == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = SafeX, vertical = SafeY)
            ) {
                SkipIntroButton()
            }
            panel?.let { PanelSheet(it) }
            error?.let { ErrorOverlay(it) }
            upNext?.let { UpNextCard(it) }
        }
    }

    /** Artwork and title while a title starts, instead of a black screen. */
    @Composable
    private fun StartCover() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            art?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0x6607080C), 0.5f to Color(0x9907080C), 1f to Color(0xF207080C))
                )
            )
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.62f).padding(horizontal = SafeX, vertical = SafeY + 12.dp)) {
                if (kicker.isNotBlank()) Kicker(kicker)
                if (headline.isNotBlank()) Text(headline, style = TvType.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(loadingNote.ifBlank { "Starting…" }, style = TvType.body, color = Cream, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }

    /** The title and summary over a dimmed picture after a moment on pause. */
    @Composable
    private fun PauseInfo() {
        val paused = started && !playing && !loading && panel == null && error == null && upNext == null
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(paused) {
            if (!paused) {
                show = false
                return@LaunchedEffect
            }
            delay(1_200)
            show = true
        }
        AnimatedVisibility(show && paused, enter = fadeIn(tween(400)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.6f).padding(start = SafeX, bottom = 60.dp)) {
                    Kicker(kicker.takeIf { movie?.isEpisode == true } ?: "You're watching")
                    Text(headline, style = TvType.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (overview.isNotBlank()) {
                        Text(overview, style = TvType.body, color = Cream, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }

    /** Looks focused on purpose: OK presses it while it shows (see handleKey). */
    @Composable
    private fun SkipIntroButton() {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$skipLabel  ›", style = TvType.button, color = Color(0xFF111111))
        }
    }

    /** A small card in the corner as the credits roll, counting down to the next episode. */
    @Composable
    private fun UpNextCard(next: Movie) {
        val playFocus = remember { FocusRequester() }
        var left by remember(next.id) { mutableIntStateOf(UP_NEXT_SECONDS) }
        val ask = asking
        LaunchedEffect(next.id) {
            delay(30)
            runCatching { playFocus.requestFocus() }
            if (ask) return@LaunchedEffect
            while (left > 0) {
                delay(1_000)
                // Waits while paused, but keeps counting once the episode has ended.
                val running = ended || exo?.isPlaying != false
                if (running && panel == null) left--
            }
            playNext(auto = true)
        }
        Box(Modifier.fillMaxSize().padding(horizontal = SafeX, vertical = SafeY), contentAlignment = Alignment.BottomEnd) {
            Row(
                Modifier.width(520.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF014161D))
                    .border(1.dp, Line, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val still = api.imageUrl(next.id, "Primary", next.primaryTag, 420)
                    ?: api.backdropUrl(next, 420)
                Box(Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1D24))) {
                    if (still != null) AsyncImage(model = still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Kicker(if (ask) "Are you still watching?" else "Up next")
                    Text(next.cardTitle, style = TvType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (ask) {
                        Text("Playback stops after the credits unless you carry on.", style = TvType.small, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Text("Starts in $left", style = TvType.small, modifier = Modifier.padding(top = 4.dp))
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((UP_NEXT_SECONDS - left) / UP_NEXT_SECONDS.toFloat()).background(Color.White))
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(if (ask) "Keep watching" else "Play now", icon = R.drawable.ic_play, primary = true, focusRequester = playFocus) { playNext() }
                        if (ask) {
                            TvButton("Stop") { finish() }
                        } else {
                            TvButton(if (ended) "Back" else "Keep watching") {
                                if (ended) finish() else keepWatching()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Subtitles() {
        var line by remember { mutableStateOf("") }
        LaunchedEffect(cues, cueDelayMs) {
            if (cues.isEmpty()) {
                line = ""
                return@LaunchedEffect
            }
            while (isActive) {
                val at = (exo?.currentPosition ?: 0L) - cueDelayMs
                line = cueAt(cues, at)
                delay(100)
            }
        }
        val lift = if (controls && panel == null) 150.dp else 36.dp
        Box(Modifier.fillMaxSize().padding(bottom = lift, start = 80.dp, end = 80.dp), contentAlignment = Alignment.BottomCenter) {
            if (line.isNotBlank()) {
                Text(
                    line,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (19f * store.cueScale).sp,
                        lineHeight = (25f * store.cueScale).sp,
                        color = Color.White,
                        shadow = Shadow(Color.Black, blurRadius = 6f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x8C000000))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            if (subNote.isNotBlank() && line.isBlank()) {
                Text(subNote, style = TvType.small, color = Cream, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Glass).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }

    @Composable
    private fun SeekStrip(targetMs: Long) {
        Box(Modifier.fillMaxSize().padding(horizontal = SafeX, vertical = SafeY), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Glass).padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val delta = targetMs - positionMs
                    Text(clock(targetMs), style = TvType.title, color = Cream)
                    Text(
                        "   ${if (delta >= 0) "+" else "−"}${clock(kotlin.math.abs(delta))}",
                        style = TvType.body
                    )
                    Spacer(Modifier.weight(1f))
                    Text(clock(durationMs), style = TvType.body)
                }
                Spacer(Modifier.height(8.dp))
                ProgressTrack(positionMs, targetMs, focused = true)
            }
        }
    }

    @Composable
    private fun ProgressTrack(current: Long, marker: Long?, focused: Boolean) {
        val total = durationMs.coerceAtLeast(1L)
        val played = (current.toFloat() / total).coerceIn(0f, 1f)
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
            val trackHeight = if (focused) 6.dp else 4.dp
            Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(Color.White))
            }
            chapters.forEach { chapter ->
                val at = (chapter.startTicks / 10_000f / total).coerceIn(0f, 1f)
                if (at > 0.005f) Box(Modifier.offset(x = maxWidth * at).width(2.dp).height(trackHeight).background(Color.Black.copy(alpha = 0.55f)))
            }
            if (marker != null || focused) {
                val at = ((marker ?: current).toFloat() / total).coerceIn(0f, 1f)
                Box(Modifier.offset(x = maxWidth * at - 7.dp).size(14.dp).clip(CircleShape).background(Color.White))
            }
        }
    }

    @Composable
    private fun Controls() {
        val playFocus = remember { FocusRequester() }
        val subsFocus = remember { FocusRequester() }
        val audioFocus = remember { FocusRequester() }
        val chaptersFocus = remember { FocusRequester() }
        val speedFocus = remember { FocusRequester() }
        val infoFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            val target = when (lastPanel) {
                Panel.Subtitles, Panel.Online -> subsFocus
                Panel.Audio -> audioFocus
                Panel.Chapters -> chaptersFocus
                Panel.Speed -> speedFocus
                Panel.Info -> infoFocus
                null -> playFocus
            }
            lastPanel = null
            delay(30)
            runCatching { target.requestFocus() }.onFailure { runCatching { playFocus.requestFocus() } }
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(230.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            )
            Column(Modifier.padding(horizontal = SafeX, vertical = SafeY)) {
                Text(title, style = TvType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val left = (durationMs - positionMs).coerceAtLeast(0L)
                if (durationMs > 0) Text("Ends at ${endsLabel(left * 10_000L)}", style = TvType.body, modifier = Modifier.padding(top = 4.dp))
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = SafeX, vertical = SafeY)
            ) {
                Scrubber()
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(clock(positionMs), style = TvType.body, color = Cream)
                    Spacer(Modifier.weight(1f))
                    Text("−" + clock((durationMs - positionMs).coerceAtLeast(0L)), style = TvType.body)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("10", icon = R.drawable.ic_replay) { seekBy(-10_000) }
                    TvButton(
                        if (playing) "Pause" else "Play",
                        Modifier.width(96.dp),
                        icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        primary = true,
                        focusRequester = playFocus
                    ) { togglePlay() }
                    TvButton("10", icon = R.drawable.ic_forward) { seekBy(10_000) }
                    Spacer(Modifier.weight(1f))
                    val subLabel = subStreams.find { it.index == selectedSub }?.let { shortLabel(it) } ?: "Off"
                    TvButton("Subtitles · $subLabel", icon = R.drawable.ic_subs, focusRequester = subsFocus) { openPanel(Panel.Subtitles) }
                    if (audioStreams.size > 1) {
                        val audioLabel = audioStreams.find { it.index == selectedAudio }?.let { shortLabel(it) } ?: "Audio"
                        TvButton(audioLabel, icon = R.drawable.ic_audio, focusRequester = audioFocus) { openPanel(Panel.Audio) }
                    }
                    if (chapters.size > 1) TvButton("Chapters", icon = R.drawable.ic_chapters, focusRequester = chaptersFocus) { openPanel(Panel.Chapters) }
                    TvButton(if (speed == 1f) "1×" else "${speed}×", icon = R.drawable.ic_speed, focusRequester = speedFocus) { openPanel(Panel.Speed) }
                    TvButton("Info", icon = R.drawable.ic_info, focusRequester = infoFocus) { openPanel(Panel.Info) }
                }
            }
        }
    }

    @Composable
    private fun Scrubber() {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Column(
            Modifier
                .fillMaxWidth()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            seekBy(if (event.nativeKeyEvent.repeatCount > 2) -30_000 else -10_000)
                            stamp++
                            true
                        }
                        Key.DirectionRight -> {
                            seekBy(if (event.nativeKeyEvent.repeatCount > 2) 30_000 else 10_000)
                            stamp++
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            togglePlay()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction)
        ) {
            if (focused) {
                val preview = seekPreviewMs
                Text(
                    if (preview != null) clock(preview) else "◀  ▶  to skip",
                    style = TvType.small,
                    color = Cream,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            ProgressTrack(positionMs, seekPreviewMs, focused)
        }
    }

    @Composable
    private fun PanelSheet(which: Panel) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(340.dp)
                    .background(Color(0xF014161D))
                    .padding(horizontal = 22.dp, vertical = SafeY)
            ) {
                val heading = when (which) {
                    Panel.Subtitles -> "Subtitles"
                    Panel.Online -> "Find subtitles online"
                    Panel.Audio -> "Audio"
                    Panel.Chapters -> "Chapters"
                    Panel.Speed -> "Playback speed"
                    Panel.Info -> "Playback info"
                }
                Text(heading, style = TvType.headline, modifier = Modifier.padding(bottom = 12.dp))
                val rows = panelRows(which)
                val start = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
                val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
                val focus = remember { FocusRequester() }
                LaunchedEffect(which, rows.size) {
                    delay(30)
                    runCatching { focus.requestFocus() }
                }
                if (which == Panel.Online && remoteNote.isNotBlank()) {
                    Text(remoteNote, style = TvType.body, color = Cream, modifier = Modifier.padding(bottom = 10.dp))
                }
                LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    itemsIndexed(rows) { index, row ->
                        PanelRow(row, if (index == start) focus else null)
                    }
                }
            }
        }
    }

    private class PanelItem(val label: String, val detail: String = "", val selected: Boolean = false, val action: () -> Unit)

    private fun panelRows(which: Panel): List<PanelItem> = when (which) {
        Panel.Subtitles -> buildList {
            add(PanelItem("Off", selected = selectedSub == null) { chooseSubtitle(null) })
            val labels = subtitleLabels(subStreams)
            val unnamed = subStreams.filter { it.isUnnamedSubtitle() }
            val fold = !showAllSubs && unnamed.size > FOLD_UNNAMED_SUBS && unnamed.none { it.index == selectedSub }
            subStreams.filter { !fold || !it.isUnnamedSubtitle() }.forEach { stream ->
                val detail = when {
                    stream.isTextSubtitle() -> ""
                    plan?.hls == true -> "Image subtitles · slower to start"
                    else -> "Image subtitles"
                }
                add(PanelItem(labels[stream.index] ?: stream.label(), detail, selectedSub == stream.index) { chooseSubtitle(stream.index) })
            }
            if (fold) add(PanelItem("Show ${unnamed.size} more tracks", "No language set in the file") { showAllSubs = true })
            if (cues.isNotEmpty()) {
                add(PanelItem("Show earlier", "Now ${delayText(cueDelayMs)}") { cueDelayMs -= 250; stamp++ })
                add(PanelItem("Show later", "Now ${delayText(cueDelayMs)}") { cueDelayMs += 250; stamp++ })
            }
            add(PanelItem("Find more online", SUB_LANGS.find { it.code == store.subtitleLanguage }?.label ?: "") { searchOnline() })
        }
        Panel.Online -> remoteHits.take(30).map { hit -> PanelItem(hit.name.ifBlank { "Subtitle" }, hit.label().substringAfter(" · ", "")) { useRemote(hit) } }
        Panel.Audio -> audioStreams.map { stream ->
            PanelItem(stream.label(), stream.codec.uppercase() + if (stream.channels > 0) " · ${channelText(stream.channels)}" else "", selectedAudio == stream.index) {
                chooseAudio(stream.index)
            }
        }
        Panel.Chapters -> {
            val nowTicks = positionMs * 10_000L
            val current = chapters.indexOfLast { it.startTicks <= nowTicks + 20_000_000L }
            chapters.mapIndexed { index, chapter ->
                PanelItem(chapter.name.ifBlank { "Chapter ${index + 1}" }, ticksToClock(chapter.startTicks), index == current) {
                    seekTo(chapter.startTicks / 10_000L)
                    panel = null
                    showControls()
                }
            }
        }
        Panel.Speed -> listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { value ->
            PanelItem(if (value == 1f) "Normal" else "${value}×", selected = speed == value) { changeSpeed(value) }
        }
        Panel.Info -> infoRows()
    }

    /** What the Playback info panel says: how the server is sending the video and how well the TV is keeping up. */
    private fun infoRows(): List<PanelItem> {
        val close = { closePanel() }
        val current = plan ?: return listOf(PanelItem("Starting…", action = close))
        val player = exo
        val url = current.source.transcodingUrl.orEmpty()
        val codes = Regex("TranscodeReasons=([^&]*)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val reasons = buildList<String> {
            codes.mapTo(this) { code -> TRANSCODE_REASONS[code] ?: code.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase() }
            if (current.burn && "SubtitleCodecNotSupported" !in codes) add(TRANSCODE_REASONS.getValue("SubtitleCodecNotSupported"))
            if (isEmpty() && current.hls) add("playing the file directly didn't work on this TV")
        }
        val because = if (reasons.isEmpty()) "" else " Because ${reasons.joinToString(", and ")}."
        val reencoding = current.burn || current.mode == PlayMode.Transcode ||
            Regex("TranscodeReasons=[^&]*Video", RegexOption.IGNORE_CASE).containsMatchIn(url)
        val how = when {
            !current.hls -> PanelItem("Straight from the drive", "The server isn't converting anything.", action = close)
            reencoding -> PanelItem("Converted by the server", "Hard work for the Pi, capped at 1080p.$because", action = close)
            current.mode == PlayMode.RemuxAudio -> PanelItem("Sound converted by the server", "The picture is untouched; converting sound is light work.$because", action = close)
            else -> PanelItem("Repackaged by the server", "Nothing is re-encoded, so this is light work for the Pi.$because", action = close)
        }
        return buildList {
            add(how)
            player?.videoFormat?.let { format ->
                val codec = when (format.sampleMimeType) {
                    MimeTypes.VIDEO_H265 -> "HEVC"
                    MimeTypes.VIDEO_H264 -> "H.264"
                    MimeTypes.VIDEO_AV1 -> "AV1"
                    MimeTypes.VIDEO_VP9 -> "VP9"
                    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                    else -> format.sampleMimeType?.substringAfter('/')?.uppercase().orEmpty()
                }
                val fps = format.frameRate.takeIf { it > 0f }?.let { "${hertz(it)} fps" }
                val decoder = when {
                    videoDecoder.isBlank() -> ""
                    SOFTWARE_DECODERS.any { videoDecoder.startsWith(it, ignoreCase = true) } -> "software decoder (slow)"
                    else -> "hardware decoder"
                }
                add(PanelItem("Picture", listOf("${format.width}×${format.height}", codec, fps.orEmpty(), decoder).filter { it.isNotBlank() }.joinToString(" · "), action = close))
            }
            @Suppress("DEPRECATION")
            val mode = runCatching { windowManager.defaultDisplay.mode }.getOrNull()
            if (mode != null) add(PanelItem("Screen", "${mode.physicalWidth}×${mode.physicalHeight} at ${hertz(mode.refreshRate)} Hz", action = close))
            audioStreams.find { it.index == selectedAudio }?.let { stream ->
                add(PanelItem("Sound", stream.label() + if (stream.channels > 0) " · ${channelText(stream.channels)}" else "", action = close))
            }
            player?.videoDecoderCounters?.let { counters ->
                counters.ensureUpdated()
                val dropped = counters.droppedBufferCount
                val shown = counters.renderedOutputBufferCount
                val verdict = if (shown > 250 && dropped * 100 > shown) "This TV is struggling with this file." else "Playing smoothly."
                add(PanelItem("Smoothness", "$dropped of ${shown + dropped} frames dropped. $verdict", action = close))
            }
            player?.let { add(PanelItem("Loaded ahead", "${it.totalBufferedDuration / 1000} seconds", action = close)) }
        }
    }

    @Composable
    private fun PanelRow(row: PanelItem, requester: FocusRequester?) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) Color.White else Color.Transparent)
                .border(if (row.selected && !focused) 1.dp else 0.dp, if (row.selected && !focused) Line else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(interaction, indication = null, onClick = row.action)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(18.dp)) {
                if (row.selected) Text("✓", style = TvType.button, color = if (focused) Color(0xFF111111) else Cream)
            }
            Column(Modifier.weight(1f)) {
                Text(row.label, style = TvType.button, color = if (focused) Color(0xFF111111) else Cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.detail.isNotBlank()) {
                    Text(row.detail, style = TvType.small, color = if (focused) Color(0xFF444444) else Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun ErrorOverlay(message: String) {
        val tryFocus = remember { FocusRequester() }
        LaunchedEffect(message) {
            delay(30)
            runCatching { tryFocus.requestFocus() }
        }
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (title.isNotBlank()) Kicker(title)
                Text(message, style = TvType.title, color = Cream, textAlign = TextAlign.Center)
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Try again", primary = true, focusRequester = tryFocus) { retry() }
                    TvButton("Back") { finish() }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_FROM_START = "fromStart"
        const val EXTRA_LIBRARY_EMPTY = "libraryEmpty"
        const val EXTRA_ART = "art"
        const val EXTRA_HEADLINE = "headline"
        private const val MB = 1024L * 1024L
        private const val UP_NEXT_SECONDS = 10
        /** Parts of an episode the Skip button offers to jump over, with its label for each. */
        private val SKIPPABLE = mapOf("Intro" to "Skip intro", "Recap" to "Skip recap", "Preview" to "Skip preview")
        /** Android's own software decoders; anything else on a TV box is the chip's hardware decoder. */
        private val SOFTWARE_DECODERS = listOf("OMX.google.", "c2.android.", "c2.google.")
        /** Plain-language reasons for the TranscodeReasons Jellyfin puts in a stream address. */
        private val TRANSCODE_REASONS = mapOf(
            "ContainerNotSupported" to "this TV can't open the file type",
            "VideoCodecNotSupported" to "this TV can't decode the picture format",
            "AudioCodecNotSupported" to "this TV can't play the sound format",
            "SubtitleCodecNotSupported" to "the subtitles have to be drawn into the picture",
            "VideoRangeTypeNotSupported" to "this TV can't show this kind of HDR",
            "VideoBitDepthNotSupported" to "this TV can't show 10-bit colour here",
            "VideoProfileNotSupported" to "the picture uses a profile this TV can't decode",
            "VideoLevelNotSupported" to "the picture is too demanding for this TV",
            "VideoResolutionNotSupported" to "the picture is bigger than this TV allows",
            "AudioChannelsNotSupported" to "this TV can't play that many sound channels",
            "ContainerBitrateExceedsLimit" to "the file is bigger than the streaming limit",
            "VideoBitrateNotSupported" to "the picture is bigger than the streaming limit",
            "AudioBitrateNotSupported" to "the sound is bigger than the streaming limit",
            "DirectPlayError" to "playing the file directly failed"
        )
    }
}

private fun clock(ms: Long): String = ticksToClock(ms * 10_000L)

/** 23.976 stays 23.976, 60.0 becomes 60. */
private fun hertz(rate: Float): String = "%.3f".format(java.util.Locale.US, rate).trimEnd('0').trimEnd('.')

private fun delayText(ms: Long): String = when {
    ms == 0L -> "on time"
    ms < 0 -> "%.2fs earlier".format(-ms / 1000f)
    else -> "%.2fs later".format(ms / 1000f)
}

private fun channelText(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

private fun shortLabel(stream: MediaStreamInfo): String {
    val base = stream.languageName().ifBlank { if (stream.type == "Subtitle") "On" else "Audio" }
    return if (base.length > 14) base.take(13) + "…" else base
}
