package tv.livingroom.watch

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LoginStep { Server, Profiles }

/** Where a Quick Connect sign-in has got to. */
sealed interface QuickState {
    data object Closed : QuickState
    data object Starting : QuickState
    data class Waiting(val code: String) : QuickState
    /** The server has Quick Connect switched off. */
    data object Off : QuickState
    data class Failed(val note: String) : QuickState
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val livingRoom = app as LivingRoomApp
    val store: SessionStore get() = livingRoom.store
    val api: JellyfinClient get() = livingRoom.api

    var screen by mutableStateOf<Screen>(Screen.Login)
        private set
    var movies by mutableStateOf<List<Movie>>(emptyList())
        private set
    var details by mutableStateOf<Map<String, Movie>>(emptyMap())
        private set
    var profiles by mutableStateOf<List<Profile>>(emptyList())
        private set
    var profilesLoaded by mutableStateOf(false)
        private set
    var loginStep by mutableStateOf(if (store.server.isBlank()) LoginStep.Server else LoginStep.Profiles)
        private set
    var servers by mutableStateOf<List<FoundServer>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searched by mutableStateOf(false)
        private set
    var quick by mutableStateOf<QuickState>(QuickState.Closed)
        private set
    private var quickJob: Job? = null
    var query by mutableStateOf("")
    var sort by mutableStateOf("title")
    var unwatchedOnly by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var featuredId by mutableStateOf<String?>(null)
    var restoreKey: String? = null
    var contentFocused = false
    var osUser by mutableStateOf("")
    var osHint by mutableStateOf("Password")
    var osNote by mutableStateOf("")
    var controlsSeconds by mutableStateOf(store.controlsSeconds)
    var cueScale by mutableStateOf(store.cueScale)
    var subtitleLanguage by mutableStateOf(store.subtitleLanguage)
    var autoSubtitles by mutableStateOf(store.autoSubtitles)

    private val backStack = ArrayDeque<Screen>()
    private val focusMemory = mutableMapOf<String, String>()
    private val listStates = mutableMapOf<String, LazyListState>()
    private val gridStates = mutableMapOf<String, LazyGridState>()

    val canBack: Boolean get() = backStack.isNotEmpty() || (screen != Screen.Home && screen != Screen.Login)

    init {
        when {
            store.signedIn -> {
                screen = Screen.Home
                refresh()
            }
            loginStep == LoginStep.Server -> findServers()
            else -> loadProfiles()
        }
    }

    fun section(): Screen {
        val chain = backStack.toList() + screen
        return chain.asReversed().firstOrNull { it.isRoot() } ?: Screen.Home
    }

    private fun Screen.isRoot(): Boolean =
        this == Screen.Home || this == Screen.Search || this == Screen.Movies || this == Screen.Genres || this == Screen.Settings

    fun listState(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    fun gridState(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }

    fun rememberFocus(focusKey: String) {
        focusMemory[screen.key] = focusKey
    }

    fun focusFor(screenKey: String): String? = focusMemory[screenKey]

    fun movieFor(id: String): Movie? = details[id] ?: movies.find { it.id == id }

    fun shown(): List<Movie> {
        var items = movies
        when (val current = screen) {
            is Screen.Person -> items = items.filter { movie ->
                movie.people.any { it.name == current.name && (current.role.isBlank() || it.type == current.role) }
            }
            is Screen.Genre -> items = items.filter { current.name in it.genres }
            Screen.Search -> {
                val needle = query.trim().lowercase()
                if (needle.isEmpty()) return emptyList()
                items = items.filter { it.haystack.contains(needle) }
                return items.sortedWith(compareByDescending<Movie> { it.name.lowercase().startsWith(needle) }.thenBy { it.sortLabel.lowercase() })
            }
            else -> Unit
        }
        if (screen == Screen.Movies && unwatchedOnly) items = items.filter { !it.played }
        return sortMovies(items, if (screen == Screen.Movies) sort else "title")
    }

    fun moreLike(movie: Movie): List<Movie> {
        val genres = movie.genres.toSet()
        if (genres.isEmpty()) return emptyList()
        return movies
            .filter { it.id != movie.id && it.genres.any { g -> g in genres } }
            .sortedByDescending { other -> other.genres.count { it in genres } * 10 + (other.communityRating ?: 0.0) }
            .take(16)
    }

    fun go(root: Screen) {
        backStack.clear()
        if (screen == root) return
        restoreKey = focusMemory[root.key]
        screen = root
        if (root == Screen.Settings) loadOpenSubtitles()
    }

    fun open(next: Screen) {
        if (screen == next) return
        backStack.addLast(screen)
        focusMemory.remove(next.key)
        listStates.keys.filter { it == next.key || it.startsWith(next.key + "/") }.forEach { listStates.remove(it) }
        gridStates.remove(next.key)
        restoreKey = null
        screen = next
        if (next is Screen.Detail) loadDetail(next.id)
    }

    fun back(): Boolean {
        val previous = backStack.removeLastOrNull()
            ?: if (screen != Screen.Home && screen != Screen.Login) Screen.Home else null
            ?: return false
        restoreKey = focusMemory[previous.key]
        screen = previous
        return true
    }

    fun refresh() {
        if (!store.signedIn) return
        viewModelScope.launch {
            busy = movies.isEmpty()
            try {
                movies = withContext(Dispatchers.IO) { api.movies() }
                val open = (backStack.toList() + screen).filterIsInstance<Screen.Detail>().map { it.id }.toSet()
                details = details.filterKeys { it in open }.mapValues { (id, old) ->
                    movies.find { it.id == id }?.let { listed ->
                        old.copy(played = listed.played, favorite = listed.favorite, positionTicks = listed.positionTicks, lastPlayed = listed.lastPlayed)
                    } ?: old
                }
                message = ""
            } catch (error: Exception) {
                if (error is JellyfinException && error.code == 401) {
                    signOut()
                    return@launch
                }
                if (movies.isEmpty()) message = "Can't reach Jellyfin at ${store.server}. Check the server in Settings."
            } finally {
                busy = false
            }
        }
    }

    fun loadProfiles() {
        viewModelScope.launch {
            busy = true
            message = ""
            try {
                profiles = withContext(Dispatchers.IO) { api.publicUsers() }
            } catch (_: Exception) {
                profiles = emptyList()
                message = "Can't reach Jellyfin at ${store.server}."
            } finally {
                busy = false
                profilesLoaded = true
            }
        }
    }

    fun signIn(username: String, password: String) {
        if (username.isBlank()) {
            message = "Enter a username."
            return
        }
        viewModelScope.launch {
            busy = true
            message = ""
            try {
                withContext(Dispatchers.IO) { api.authenticate(username.trim(), password) }
                busy = false
                enterHome()
            } catch (error: Exception) {
                busy = false
                message = if (error is JellyfinException && error.code == 401) {
                    "That password didn't work."
                } else {
                    "Couldn't sign in. Check the server address."
                }
            }
        }
    }

    private fun enterHome() {
        backStack.clear()
        screen = Screen.Home
        refresh()
    }

    /** Looks for Jellyfin servers on the home network. */
    fun findServers() {
        if (searching) return
        viewModelScope.launch {
            searching = true
            message = ""
            servers = try {
                withContext(Dispatchers.IO) { ServerDiscovery.find(getApplication()) }
            } catch (_: Exception) {
                emptyList()
            }
            searching = false
            searched = true
        }
    }

    fun chooseServer(server: FoundServer) = connect(listOf(server.address))

    /** Tries what the user typed, filling in http:// and Jellyfin's usual port when they're left out. */
    fun connectTo(typed: String) {
        val candidates = ServerDiscovery.candidates(typed)
        if (candidates.isEmpty()) {
            message = "Enter the server's address."
            return
        }
        connect(candidates, typed.trim())
    }

    private fun connect(candidates: List<String>, shown: String = candidates.first()) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            message = ""
            val found = withContext(Dispatchers.IO) { candidates.firstNotNullOfOrNull { api.serverInfo(it) } }
            busy = false
            if (found == null) {
                message = "No Jellyfin server answered at $shown. Check the address and that the server is on."
                return@launch
            }
            store.clearSession()
            store.server = found.address
            store.serverName = found.name
            profiles = emptyList()
            profilesLoaded = false
            loginStep = LoginStep.Profiles
            loadProfiles()
        }
    }

    /** Signs out and goes back to picking a server. */
    fun changeServer() {
        clearSignedIn()
        message = ""
        loginStep = LoginStep.Server
        findServers()
    }

    /** Leaves the server picker without changing anything, when a server is already chosen. */
    fun keepServer(): Boolean {
        if (store.server.isBlank()) return false
        message = ""
        loginStep = LoginStep.Profiles
        if (!profilesLoaded) loadProfiles()
        return true
    }

    fun startQuickConnect() {
        quickJob?.cancel()
        quick = QuickState.Starting
        quickJob = viewModelScope.launch {
            try {
                if (!withContext(Dispatchers.IO) { api.quickConnectEnabled() }) {
                    quick = QuickState.Off
                    return@launch
                }
                val request = withContext(Dispatchers.IO) { api.quickConnectStart() }
                quick = QuickState.Waiting(request.code)
                // The server forgets a request after about ten minutes; checking then fails and says so below.
                while (!withContext(Dispatchers.IO) { api.quickConnectApproved(request.secret) }) {
                    delay(QUICK_POLL_MS)
                }
                withContext(Dispatchers.IO) { api.authenticateQuickConnect(request.secret) }
                quick = QuickState.Closed
                enterHome()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                quick = QuickState.Failed(
                    if (error is JellyfinException && (error.code == 404 || error.code == 401)) {
                        "That code has expired."
                    } else {
                        "Couldn't reach the server."
                    }
                )
            }
        }
    }

    fun closeQuickConnect() {
        quickJob?.cancel()
        quickJob = null
        quick = QuickState.Closed
    }

    fun signOut() {
        clearSignedIn()
        if (store.server.isBlank()) {
            loginStep = LoginStep.Server
            findServers()
        } else {
            loginStep = LoginStep.Profiles
            loadProfiles()
        }
    }

    private fun clearSignedIn() {
        closeQuickConnect()
        store.clearSession()
        movies = emptyList()
        details = emptyMap()
        query = ""
        backStack.clear()
        focusMemory.clear()
        listStates.clear()
        gridStates.clear()
        screen = Screen.Login
    }

    fun setControls(seconds: Int) {
        controlsSeconds = seconds
        store.controlsSeconds = seconds
    }

    fun setCue(scale: Float) {
        cueScale = scale
        store.cueScale = scale
    }

    fun setLanguage(code: String) {
        subtitleLanguage = code
        store.subtitleLanguage = code
    }

    fun setAutoSubs(on: Boolean) {
        autoSubtitles = on
        store.autoSubtitles = on
    }

    fun loadOpenSubtitles() {
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { api.pluginConfig() }
                if (config == null) {
                    osNote = "OpenSubtitles isn't installed on the server."
                    return@launch
                }
                osUser = config.optString("Username")
                osHint = if (config.optString("Password").isNotBlank()) "Saved. Leave blank to keep it" else "Password"
                osNote = if (osUser.isBlank()) "Add your opensubtitles.com account to search for subtitles." else "Signed in as $osUser."
            } catch (_: Exception) {
                osNote = "Only an admin account can change the OpenSubtitles login."
            }
        }
    }

    fun saveOpenSubtitles(username: String, password: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.savePluginConfig(username.trim(), password) }
                osUser = username.trim()
                osHint = "Saved. Leave blank to keep it"
                osNote = if (username.isBlank()) "Login cleared." else "Saved."
            } catch (_: Exception) {
                osNote = "Couldn't save. Only an admin account can change this."
            }
        }
    }

    fun toggleFavorite(movie: Movie) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.toggleFavorite(movie.id, movie.favorite) }
                patch(movie.id) { it.copy(favorite = !movie.favorite) }
            } catch (_: Exception) {
                message = "That change didn't save."
            }
        }
    }

    fun togglePlayed(movie: Movie) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.togglePlayed(movie.id, movie.played) }
                patch(movie.id) {
                    it.copy(played = !movie.played, positionTicks = if (!movie.played) 0L else it.positionTicks)
                }
            } catch (_: Exception) {
                message = "That change didn't save."
            }
        }
    }

    private fun loadDetail(id: String) {
        viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) { api.item(id) }
                details = details + (id to full)
            } catch (_: Exception) {
                if (movieFor(id) == null) message = "That title didn't open."
            }
        }
    }

    private fun patch(id: String, transform: (Movie) -> Movie) {
        movies = movies.map { if (it.id == id) transform(it) else it }
        details[id]?.let { details = details + (id to transform(it)) }
    }

    companion object {
        private const val QUICK_POLL_MS = 3_000L
    }
}
