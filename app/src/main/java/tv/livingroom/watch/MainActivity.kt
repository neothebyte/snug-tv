package tv.livingroom.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** True from a held arrow's move until the screen has drawn a frame with it. */
    private var moveOnScreen = false
    private val choreographer by lazy { Choreographer.getInstance() }
    /** The same model the screens use; it is scoped to this activity. */
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LivingRoomTheme {
                val model: AppModel = viewModel()
                WatchApp(model)
            }
        }
        // The theme's window colour only covers the moment before the first frame. After that the
        // backdrop paints every pixel, so the window colour would be one more full screen to fill.
        window.setBackgroundDrawable(null)
        openRequested(intent)
        takeTmdbKey(intent)
        // Drive mode: notice the USB drive being plugged in or pulled out.
        val mounts = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, mountWatcher, mounts, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val mountWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            model.checkDrive()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(mountWatcher) }
        super.onDestroy()
    }

    /**
     * A TMDB key for Drive mode handed over with adb, which is easier than typing it with the remote:
     * adb shell am start -n tv.livingroom.watch/.MainActivity --es tmdbKey KEY
     */
    private fun takeTmdbKey(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_TMDB_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(EXTRA_TMDB_KEY)
        model.setTmdbKey(key)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequested(intent)
        takeTmdbKey(intent)
    }

    /** Snug Home asked for one title (its top shelf). Signed out, the sign-in screen stays. */
    private fun openRequested(intent: Intent?) {
        if (intent?.action != OPEN_ITEM) return
        val id = intent.getStringExtra(EXTRA_ITEM_ID)?.takeIf { it.isNotBlank() } ?: return
        intent.action = null
        if (!model.store.signedIn) return
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { runCatching { model.api.item(id) }.getOrNull() } ?: return@launch
            model.openItem(item)
        }
    }

    /**
     * A held arrow repeats about 20 times a second, faster than a TV box can draw a moving grid.
     * The extra presses queue up, so the grid lags behind and keeps moving after the button is
     * let go. Repeats that arrive before the last move has been drawn are dropped instead, so
     * fast TVs scroll at full speed and slow ones stay in step with the remote.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        model.lastInput = SystemClock.uptimeMillis()
        // A press while the screensaver shows only puts it away.
        if (model.saverOn) {
            if (event.action == KeyEvent.ACTION_UP) model.saverOn = false
            return true
        }
        // The remote's search or microphone key: Search, listening straight away.
        if (event.keyCode in VOICE_KEYS && model.store.signedIn) {
            if (event.action == KeyEvent.ACTION_UP) {
                model.voiceRequest++
                model.go(Screen.Search)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in ARROWS && event.repeatCount > 0) {
            if (moveOnScreen) return true
            moveOnScreen = true
            // The first callback runs as the next frame starts; the second once that frame is drawn.
            choreographer.postFrameCallback { choreographer.postFrameCallback { moveOnScreen = false } }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Back from the player: time spent watching isn't time idle on a menu.
        model.lastInput = SystemClock.uptimeMillis()
        model.saverOn = false
        // Back from Android's settings, or the drive went in while Snug was in the background.
        model.checkDrive()
    }

    private companion object {
        const val OPEN_ITEM = "tv.livingroom.watch.OPEN_ITEM"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_TMDB_KEY = "tmdbKey"
        val VOICE_KEYS = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST)
        val ARROWS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }
}
