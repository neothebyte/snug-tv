package tv.livingroom.home

import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.delay
import tv.livingroom.ui.FocusScale
import tv.livingroom.ui.Motion
import tv.livingroom.ui.drawFocusGlow

private val CameraShape = RoundedCornerShape(10.dp)
private val LiveRed = Color(0xFFFF5A52)
private const val STALL_MS = 3_000L

private enum class CameraState { Connecting, Live, Offline }

/**
 * The camera's picture, small, in the top right of the shelf: muted, and only playing while it's on screen.
 * OK opens it full screen.
 */
@Composable
fun CameraTile(camera: Camera, play: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val (player, state) = rememberCameraPlayer(camera.tileUrl, play)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) FocusScale.Card else 1f, Motion.focus(), label = "camera")
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode in SELECT_KEYS) {
                    if (e.type == KeyEventType.KeyUp) onOpen()
                    true
                } else false
            }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind { if (focused) drawFocusGlow() }
            .clip(CameraShape)
            .background(TileBlank)
            .border(if (focused) 2.dp else 0.5.dp, if (focused) Color.White else Line, CameraShape)
    ) {
        // A TextureView rather than a SurfaceView, so the picture keeps the tile's rounded corners.
        AndroidView(
            factory = { TextureView(it) },
            update = { player?.setVideoTextureView(it) },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (state == CameraState.Live) 1f else 0f }
        )
        CameraStatus(state, Modifier.align(Alignment.Center))
        CameraLabel(camera.name, state == CameraState.Live, Modifier.align(Alignment.TopStart).padding(7.dp))
    }
}

/** The camera over the whole screen. Back closes it. */
@Composable
fun CameraFullScreen(camera: Camera, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val (player, state) = rememberCameraPlayer(camera.fullUrl, play = true)
        var showHint by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(4_000)
            showHint = false
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { SurfaceView(it) },
                update = { player?.setVideoSurfaceView(it) },
                modifier = Modifier.fillMaxSize()
            )
            CameraStatus(state, Modifier.align(Alignment.Center))
            CameraLabel(camera.name, state == CameraState.Live, Modifier.align(Alignment.TopStart).padding(top = SafeY, start = SafeX))
            AnimatedVisibility(showHint, Modifier.align(Alignment.BottomCenter).padding(bottom = SafeY), exit = fadeOut(tween(600))) {
                BasicText(
                    "Press Back to close",
                    style = HomeType.hint,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC14161D)).padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/** The camera's name with a red dot that pulses while the picture is live, like a recording light. */
@Composable
private fun CameraLabel(name: String, live: Boolean, modifier: Modifier) {
    val pulse = if (live) {
        val transition = rememberInfiniteTransition(label = "live")
        transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "live").value
    } else 1f
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x9907080C))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(if (live) LiveRed else Muted))
        BasicText(
            name,
            style = HomeType.hint.copy(color = Cream, fontSize = 12.sp, lineHeight = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

@Composable
private fun CameraStatus(state: CameraState, modifier: Modifier) {
    val words = when (state) {
        CameraState.Connecting -> "Connecting…"
        CameraState.Offline -> "Camera offline"
        CameraState.Live -> return
    }
    BasicText(words, style = HomeType.hint, modifier = modifier)
}

/**
 * A player for one camera stream, picture only, alive only while [play] is true, so the box's small memory isn't spent on a
 * picture nobody sees. A stream that drops out is tried again every few seconds, and one that stalls is opened
 * afresh: go2rtc then starts it from the camera's latest picture instead of where it stopped.
 */
@OptIn(UnstableApi::class)
@Composable
private fun rememberCameraPlayer(url: String, play: Boolean): Pair<ExoPlayer?, CameraState> {
    val context = LocalContext.current
    var state by remember(url) { mutableStateOf(CameraState.Connecting) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var stalled by remember(url) { mutableStateOf(false) }

    DisposableEffect(url, play, attempt) {
        if (!play) return@DisposableEffect onDispose { }
        // Reopening a stalled stream keeps showing the last picture rather than flashing "Connecting…".
        if (state != CameraState.Live) state = CameraState.Connecting
        stalled = false
        // A live picture: start as soon as there's a moment of it, and stay close behind.
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 250, 250).build()
        val exo = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        // Without the sound track: the tile is silent, and cameras such as Xiaomi's send Opus, which go2rtc's
        // MP4 may or may not carry. Disabled, it can't take the picture down with it.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) state = CameraState.Live
                stalled = playbackState == Player.STATE_BUFFERING && state == CameraState.Live
            }

            override fun onPlayerError(error: PlaybackException) {
                state = CameraState.Offline
            }
        })
        // go2rtc's endless fragmented MP4: one long HTTP response, read as it arrives.
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(5_000).setReadTimeoutMs(10_000)
        exo.setMediaSource(ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(url)))
        exo.playWhenReady = true
        exo.prepare()
        player = exo
        onDispose {
            player = null
            exo.release()
        }
    }
    LaunchedEffect(state, play) {
        if (state == CameraState.Offline && play) {
            delay(10_000)
            attempt++
        }
    }
    // Xiaomi's link to go2rtc drops every few seconds; waiting it out leaves the picture frozen or broken.
    LaunchedEffect(stalled, play) {
        if (stalled && play) {
            delay(STALL_MS)
            attempt++
        }
    }
    // Every stall leaves the picture further behind. Play a little faster while there's more than a moment
    // buffered, so it catches back up to the camera.
    LaunchedEffect(player) {
        val exo = player ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            val ahead = exo.bufferedPosition - exo.currentPosition
            val speed = if (ahead > 1_500) 1.25f else if (ahead < 500) 1f else exo.playbackParameters.speed
            if (speed != exo.playbackParameters.speed) exo.setPlaybackSpeed(speed)
        }
    }
    return player to state
}
