package tv.livingroom.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The box's home screen. It never closes: Back does nothing, Home brings focus back to the first app. */
class HomeActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private var apps by mutableStateOf<List<TvApp>>(emptyList())
    private var shelf by mutableStateOf<List<ShelfItem>>(emptyList())
    private var resetSignal by mutableIntStateOf(0)
    private var network by mutableStateOf(NetworkState.Unknown)
    /** The ambient screen (the library's pictures and the time) is up; the next key press only puts it away. */
    private var ambient by mutableStateOf(false)
    private var lastInput = SystemClock.uptimeMillis()
    private var stopWatchingShelf: () -> Unit = {}
    private var shelfJob: Job? = null
    private var weather by mutableStateOf<Weather?>(null)
    private var weatherAt = 0L
    private var camera by mutableStateOf<Camera?>(null)
    private var cameraAt = 0L
    /** The home screen is in front; the camera only plays then. */
    private var onScreen by mutableStateOf(false)

    // Installs, updates and uninstalls change the tiles straight away.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            this@HomeActivity.network = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.Wired
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WiFi
                else -> NetworkState.Other
            }
        }

        override fun onLost(network: Network) {
            this@HomeActivity.network = NetworkState.Offline
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(applicationContext)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            HomeScreen(
                apps = apps,
                shelf = shelf,
                weather = weather,
                camera = camera,
                cameraOnScreen = onScreen,
                resetSignal = resetSignal,
                network = network,
                ambient = ambient,
                store = store,
                onLaunch = store::launch,
                onOpenItem = { store.openInSnug(it.id) },
                onReorder = { list -> store.saveOrder(list.map { it.key }) },
                onHide = { app ->
                    store.hide(app.key)
                    reloadApps()
                },
                onUnhide = { pkg ->
                    store.unhide(pkg)
                    reloadApps()
                },
                onAppInfo = { app -> store.openAppInfo(app.key) }
            )
        }
        ContextCompat.registerReceiver(this, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        // Last time's tiles and shelf first, from disk, so the first frame is the real home screen.
        lifecycleScope.launch {
            val (cachedApps, cachedShelf) = withContext(Dispatchers.IO) { store.cached() to SnugShelf.cached(applicationContext) }
            if (apps.isEmpty()) apps = cachedApps
            if (shelf.isEmpty()) shelf = cachedShelf
            reloadApps()
        }
        stopWatchingShelf = SnugShelf.watch(applicationContext) { reloadShelf() }
        watchIdle()
        weather = WeatherSource.cached(applicationContext)
        // Every half hour while the home screen is showing, and straight away when coming back to it after that.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val wait = WEATHER_EVERY - (System.currentTimeMillis() - weatherAt)
                    if (wait > 0) delay(wait)
                    val fresh = withContext(Dispatchers.IO) { WeatherSource.load(applicationContext) }
                    if (fresh != null) weather = fresh
                    // A failed fetch tries again in five minutes rather than half an hour.
                    weatherAt = System.currentTimeMillis() - if (fresh == null) WEATHER_EVERY - WEATHER_RETRY else 0L
                }
            }
        }
        camera = CameraSource.cached(applicationContext)
        // go2rtc on the home network, looked for again now and then in case cameras were added or renamed.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val wait = CAMERA_EVERY - (System.currentTimeMillis() - cameraAt)
                    if (wait > 0) delay(wait)
                    withContext(Dispatchers.IO) { CameraSource.find(applicationContext) }?.let { camera = it }
                    cameraAt = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        onScreen = true
    }

    override fun onStop() {
        onScreen = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.uptimeMillis()
        ambient = false
        // What's on the shelf changes as things get watched in Snug.
        reloadShelf()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            ambient = false
            resetSignal++
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (ambient) {
            if (event.action == KeyEvent.ACTION_UP) ambient = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        stopWatchingShelf()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(packages) }
        super.onDestroy()
    }

    /** After a few idle minutes on the home screen, the ambient screen comes up, like Apple TV's. */
    private fun watchIdle() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                val idle = SystemClock.uptimeMillis() - lastInput
                if (!ambient && idle > AMBIENT_AFTER_MS && hasWindowFocus()) ambient = true
            }
        }
    }

    private fun reloadShelf() {
        shelfJob?.cancel()
        shelfJob = lifecycleScope.launch {
            shelf = withContext(Dispatchers.IO) { SnugShelf.load(applicationContext) }
        }
    }

    private fun reloadApps() {
        lifecycleScope.launch {
            apps = withContext(Dispatchers.Default) { runCatching { store.load() }.getOrDefault(apps) }
        }
    }

    private companion object {
        const val AMBIENT_AFTER_MS = 5 * 60_000L
        const val WEATHER_EVERY = 30 * 60 * 1000L
        const val WEATHER_RETRY = 5 * 60 * 1000L
        const val CAMERA_EVERY = 30 * 60 * 1000L
    }
}

enum class NetworkState { Unknown, WiFi, Wired, Other, Offline }
