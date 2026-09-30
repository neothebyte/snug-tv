package tv.livingroom.watch

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Date

private const val THEME_VOLUME = 0.25f
private const val SAVER_HOLD_MS = 12_000L

/**
 * Plays [ownerId]'s theme song softly while its page is on screen, when the setting is on and the
 * library has one. It fades out when the page closes, the player opens or the screensaver comes up.
 */
@Composable
fun ThemeMusic(vm: AppModel, ownerId: String) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = vm.themeMusic && !vm.saverOn && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    if (!active) return
    LaunchedEffect(ownerId) {
        val url = withContext(Dispatchers.IO) { runCatching { vm.api.themeSongUrl(ownerId) }.getOrNull() } ?: return@LaunchedEffect
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setVolume(0f, 0f)
            val ready = CompletableDeferred<Boolean>()
            player.setOnPreparedListener { ready.complete(true) }
            player.setOnErrorListener { _, _, _ ->
                ready.complete(false)
                true
            }
            player.setDataSource(url)
            player.prepareAsync()
            if (!ready.await()) return@LaunchedEffect
            player.start()
            fadeVolume(player, 0f, THEME_VOLUME, 1_500)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                runCatching { if (player.isPlaying) fadeVolume(player, THEME_VOLUME, 0f, 400) }
                player.release()
            }
        }
    }
}

private suspend fun fadeVolume(player: MediaPlayer, from: Float, to: Float, ms: Long) {
    val steps = (ms / 50L).coerceAtLeast(1L)
    for (step in 1..steps) {
        val volume = from + (to - from) * step / steps
        player.setVolume(volume, volume)
        delay(50)
    }
}

/**
 * Snug's own screensaver: after a while with nobody pressing the remote on a menu, the library's
 * backdrops fade slowly one into the next, with the time and the title. Any button puts it away
 * (see MainActivity). The TV's own screensaver and sleep timer still take over later as usual.
 */
@Composable
fun Screensaver(vm: AppModel) {
    val minutes = vm.screensaverMinutes
    val signedIn = vm.screen !is Screen.Login
    LaunchedEffect(minutes, signedIn) {
        if (minutes <= 0 || !signedIn) return@LaunchedEffect
        while (isActive) {
            delay(10_000)
            if (!vm.saverOn && SystemClock.uptimeMillis() - vm.lastInput >= minutes * 60_000L) vm.saverOn = true
        }
    }
    if (vm.saverOn) SaverPictures(vm)
}

@Composable
private fun SaverPictures(vm: AppModel) {
    val context = LocalContext.current
    val pictures = remember { vm.saverPictures() }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(pictures) {
        if (pictures.size < 2) return@LaunchedEffect
        while (isActive) {
            delay(SAVER_HOLD_MS)
            val next = (index + 1) % pictures.size
            // Load the next picture before fading to it, so it never pops in half-way.
            vm.api.backdropUrl(pictures[next], 1280)?.let { url ->
                runCatching { context.imageLoader.execute(ImageRequest.Builder(context).data(url).build()) }
            }
            index = next
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = index, animationSpec = tween(2_000), label = "saver") { shown ->
            val item = pictures.getOrNull(shown)
            Box(Modifier.fillMaxSize()) {
                item?.let { vm.api.backdropUrl(it, 1280) }?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD907080C))))
                // Alternates corners so nothing sits in one spot on the screen for long.
                val left = shown % 2 == 0
                val time = remember(shown) { android.text.format.DateFormat.getTimeFormat(context).format(Date()) }
                Column(
                    Modifier
                        .align(if (left) Alignment.BottomStart else Alignment.BottomEnd)
                        .fillMaxWidth(0.6f)
                        .padding(horizontal = SafeX, vertical = SafeY),
                    horizontalAlignment = if (left) Alignment.Start else Alignment.End
                ) {
                    Text(time, style = TvType.title, color = Cream)
                    Text(item?.headline ?: "Snug", style = TvType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
