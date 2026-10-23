package tv.livingroom.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The box's home screen. It never closes: Back does nothing, Home brings focus back to the first app. */
class HomeActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private var apps by mutableStateOf<List<TvApp>>(emptyList())
    private var shelf by mutableStateOf<List<ShelfItem>>(emptyList())
    private var resetSignal by mutableIntStateOf(0)

    // Installs, updates and uninstalls change the tiles straight away.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
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
                resetSignal = resetSignal,
                onLaunch = store::launch,
                onOpenItem = { store.openInSnug(it.id) },
                onReorder = { list -> store.saveOrder(list.map { it.key }) }
            )
        }
        ContextCompat.registerReceiver(this, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        reloadApps()
    }

    override fun onResume() {
        super.onResume()
        // What's on the shelf changes as things get watched in Snug.
        lifecycleScope.launch {
            shelf = withContext(Dispatchers.IO) { SnugShelf.load(applicationContext) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) resetSignal++
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packages) }
        super.onDestroy()
    }

    private fun reloadApps() {
        lifecycleScope.launch {
            apps = withContext(Dispatchers.Default) { runCatching { store.load() }.getOrDefault(apps) }
        }
    }
}
