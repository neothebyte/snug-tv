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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A movie grid that the server fills a page at a time as it scrolls. */
class MovieList(var fetch: (start: Int, limit: Int) -> MoviePage) {
    var items by mutableStateOf<List<Movie>>(emptyList())
    var total by mutableStateOf(0)
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var generation = 0
    val complete: Boolean get() = loaded && items.size >= total
}

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
    var home by mutableStateOf<HomeFeed?>(null)
        private set
    var libraryEmpty by mutableStateOf(false)
        private set
    var genreStats by mutableStateOf<List<GenreStat>?>(null)
        private set
    var genresFailed by mutableStateOf(false)
        private set
    var searchResults by mutableStateOf<List<Movie>>(emptyList())
        private set
    var searchPending by mutableStateOf(false)
        private set
    var similar by mutableStateOf<Map<String, List<Movie>>>(emptyMap())
        private set
    var shows by mutableStateOf<Map<String, SeriesView>>(emptyMap())
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
        private set
    var sort by mutableStateOf("title")
        private set
    var unwatchedOnly by mutableStateOf(false)
        private set
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
    var matchFrameRate by mutableStateOf(store.matchFrameRate)
    var resumeBackSeconds by mutableStateOf(store.resumeBackSeconds)
    var stillWatchingAfter by mutableStateOf(store.stillWatchingAfter)
    var themeMusic by mutableStateOf(store.themeMusic)
    var screensaverMinutes by mutableStateOf(store.screensaverMinutes)
    /** Shows hidden from Continue watching, shared with the web version through the server. */
    var hiddenShows by mutableStateOf<Set<String>>(emptySet())
        private set
    /** The screensaver is up; the next key press only puts it away. */
    var saverOn by mutableStateOf(false)
    /** When the remote was last pressed, in [android.os.SystemClock.uptimeMillis] time. */
    var lastInput = android.os.SystemClock.uptimeMillis()

    private val backStack = ArrayDeque<Screen>()
    private val focusMemory = mutableMapOf<String, String>()
    private val listStates = mutableMapOf<String, LazyListState>()
    private val gridStates = mutableMapOf<String, LazyGridState>()
    /** Grids stay cached so going back, or re-opening a genre, is instant. The oldest are dropped past a dozen. */
    private val lists = object : LinkedHashMap<String, MovieList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MovieList>?): Boolean =
            size > 12 && eldest?.key != Screen.Movies.key
    }
    private val libraryPrefs = app.getSharedPreferences("livingroom-library", android.content.Context.MODE_PRIVATE)
    private var hoverJob: Job? = null
    private val seen = mutableMapOf<String, Movie>()
    private var genreNames: List<String>? = null
    private var genreLoad: Deferred<List<GenreStat>?>? = null
    private var homeJob: Job? = null
    private var searchJob: Job? = null

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
        this == Screen.Home || this == Screen.Search || this == Screen.Movies || this == Screen.Shows || this == Screen.Genres || this == Screen.Settings

    fun listState(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    fun gridState(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }

    fun rememberFocus(focusKey: String) {
        focusMemory[screen.key] = focusKey
    }

    fun focusFor(screenKey: String): String? = focusMemory[screenKey]

    fun movieFor(id: String): Movie? = details[id] ?: seen[id]

    /** The movie shown big on Home: the focused poster, else the first resume or newest movie. */
    fun featured(): Movie? {
        val feed = home ?: return null
        val id = featuredId ?: return feed.hero
        return feed.shelves.firstNotNullOfOrNull { shelf -> shelf.movies.find { it.id == id } } ?: movieFor(id) ?: feed.hero
    }

    /** The grid behind [screen], created empty on first use. */
    fun listFor(screen: Screen): MovieList? {
        lists[screen.key]?.let { return it }
        val fetch: (Int, Int) -> MoviePage = when (screen) {
            Screen.Movies -> moviesFetch()
            Screen.Shows -> { start, limit -> api.movies(start, limit, types = "Series") }
            is Screen.Genre -> { start, limit -> api.movies(start, limit, genre = screen.name) }
            is Screen.Person -> { start, limit -> api.movies(start, limit, person = screen.name, personType = screen.role) }
            else -> return null
        }
        return MovieList(fetch).also { lists[screen.key] = it }
    }

    private fun moviesFetch(): (Int, Int) -> MoviePage {
        val sortBy = when (sort) {
            "year" -> "ProductionYear,SortName"
            "added" -> "DateCreated,SortName"
            else -> "SortName"
        }
        // Only the first key is reversed, so titles within a year still run A to Z.
        val order = if (sort == "title") "Ascending" else "Descending"
        val filters = if (unwatchedOnly) "IsUnplayed" else null
        return { start, limit -> api.movies(start, limit, sortBy = sortBy, sortOrder = order, filters = filters) }
    }

    fun cycleSort() {
        sort = when (sort) {
            "title" -> "added"
            "added" -> "year"
            else -> "title"
        }
        resetMovies()
    }

    fun toggleUnwatched() {
        unwatchedOnly = !unwatchedOnly
        resetMovies()
    }

    private fun resetMovies() {
        val list = lists[Screen.Movies.key] ?: return
        list.fetch = moviesFetch()
        reload(list, keepSize = false)
    }

    fun loadMore(list: MovieList) {
        if (list.loading || list.complete || list.failed) return
        val generation = list.generation
        list.loading = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { list.fetch(list.items.size, PAGE) }
                if (generation != list.generation) return@launch
                keep(page.items)
                val have = list.items.mapTo(HashSet()) { it.id }
                list.items = list.items + page.items.filter { it.id !in have }
                // A short page means the server has nothing more, whatever the count said.
                list.total = if (page.items.size < PAGE) list.items.size else maxOf(page.total, list.items.size)
                list.loaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is JellyfinException && error.code == 401) {
                    signOut()
                    return@launch
                }
                if (generation == list.generation) list.failed = true
            } finally {
                if (generation == list.generation) list.loading = false
            }
        }
    }

    /**
     * Called when a poster or a link to a grid has held focus for a moment, so the page it opens
     * is usually ready by the time it's pressed.
     */
    fun hover(target: Screen) {
        hoverJob?.cancel()
        hoverJob = viewModelScope.launch {
            delay(350)
            when (target) {
                is Screen.Detail -> prefetchDetail(target.id)
                else -> listFor(target)?.let { if (!it.loaded) loadMore(it) }
            }
        }
    }

    private suspend fun prefetchDetail(id: String) {
        if (id !in details) {
            runCatching { withContext(Dispatchers.IO) { api.item(id) } }.onSuccess { details = details + (id to it) }
        }
        if (id !in similar) {
            runCatching { withContext(Dispatchers.IO) { api.similar(id, 16) } }.onSuccess { found ->
                keep(found)
                similar = similar + (id to found)
            }
        }
    }

    /** Starts a genre grid with the posters its Home row already shows; both are in title order. */
    fun seedGenre(name: String, movies: List<Movie>) {
        val list = listFor(Screen.Genre(name)) ?: return
        if (list.items.isEmpty() && !list.loading) list.items = movies
    }

    fun retry(list: MovieList) {
        list.failed = false
        loadMore(list)
    }

    /** Fetches a list again from the top, keeping what's on screen until the answer arrives. */
    private fun reload(list: MovieList, keepSize: Boolean) {
        val generation = ++list.generation
        val size = if (keepSize) maxOf(list.items.size, PAGE) else PAGE
        list.loading = true
        list.failed = false
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { list.fetch(0, size) }
                if (generation != list.generation) return@launch
                keep(page.items)
                list.items = page.items.distinctBy { it.id }
                list.total = if (page.items.size < size) list.items.size else maxOf(page.total, list.items.size)
                list.loaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == list.generation) list.failed = true
            } finally {
                if (generation == list.generation) list.loading = false
            }
        }
    }

    fun type(next: String) {
        query = next
        searchJob?.cancel()
        val needle = next.trim()
        if (needle.isEmpty()) {
            searchResults = emptyList()
            searchPending = false
            return
        }
        searchPending = true
        searchJob = viewModelScope.launch {
            // Wait for a pause in typing so each key press isn't its own search.
            delay(300)
            try {
                val found = withContext(Dispatchers.IO) { searchMovies(needle) }
                keep(found)
                searchResults = found
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                searchResults = emptyList()
            }
            searchPending = false
        }
    }

    /** Titles first, then films with a matching person, then films in a matching genre. */
    private suspend fun searchMovies(needle: String): List<Movie> = coroutineScope {
        val lower = needle.lowercase()
        val titles = async { api.movies(limit = 48, search = needle, countTotal = false, types = "Movie,Series").items }
        val byPeople = async {
            runCatching {
                val ids = api.findPeople(needle, 3)
                if (ids.isEmpty()) emptyList() else api.movies(limit = 24, personIds = ids, countTotal = false).items
            }.getOrDefault(emptyList())
        }
        val byGenre = async {
            runCatching {
                val names = allGenres().filter { it.lowercase().contains(lower) }.take(2)
                if (names.isEmpty()) emptyList() else api.movies(limit = 24, genre = names.joinToString("|"), countTotal = false).items
            }.getOrDefault(emptyList())
        }
        val ranked = titles.await().sortedWith(
            compareByDescending<Movie> { it.name.lowercase().startsWith(lower) }.thenBy { it.sortLabel.lowercase() }
        )
        (ranked + byPeople.await() + byGenre.await()).distinctBy { it.id }
    }

    private fun allGenres(): List<String> = genreNames ?: api.genres().also { genreNames = it }

    fun loadGenres() {
        viewModelScope.launch { genreStatsOrLoad() }
    }

    /** Every genre with its movie count and a top-rated backdrop, biggest first. Loaded once per sign-in. */
    private suspend fun genreStatsOrLoad(): List<GenreStat>? {
        genreStats?.let { return it }
        val running = genreLoad?.takeIf { it.isActive } ?: viewModelScope.async {
            genresFailed = false
            try {
                val stats = withContext(Dispatchers.IO) {
                    val gate = Semaphore(4)
                    coroutineScope {
                        allGenres().map { name ->
                            async {
                                gate.withPermit {
                                    runCatching {
                                        val page = api.movies(limit = 6, sortBy = "CommunityRating,SortName", sortOrder = "Descending", genre = name)
                                        GenreStat(name, page.total, page.items.firstOrNull { it.backdropTag != null })
                                    }.getOrNull()
                                }
                            }
                        }.awaitAll()
                    }
                }.filterNotNull().filter { it.count > 0 }.sortedByDescending { it.count }
                genreStats = stats
                stats
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                genresFailed = true
                null
            }
        }.also { genreLoad = it }
        return running.await()
    }

    fun surprise(play: (String, Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val pick = withContext(Dispatchers.IO) {
                    api.movies(limit = 1, sortBy = "Random", filters = "IsUnplayed", countTotal = false).items.firstOrNull()
                        ?: api.movies(limit = 1, sortBy = "Random", countTotal = false).items.firstOrNull()
                }
                pick?.let { play(it.id, true) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "Couldn't pick a movie."
            }
        }
    }

    fun go(root: Screen) {
        backStack.clear()
        if (screen == root) return
        restoreKey = focusMemory[root.key]
        screen = root
        if (root == Screen.Settings) loadOpenSubtitles()
    }

    /** The page for [item]: a movie's page, a show's page, or an episode within its show. */
    fun screenFor(item: Movie): Screen {
        val seriesId = item.seriesId
        return when {
            item.isSeries -> Screen.Series(item.id)
            item.isEpisode && seriesId != null -> Screen.Series(seriesId, item.id)
            else -> Screen.Detail(item.id)
        }
    }

    fun openItem(item: Movie) = open(screenFor(item))

    fun open(next: Screen) {
        if (screen == next) return
        backStack.addLast(screen)
        focusMemory.remove(next.key)
        listStates.keys.filter { it == next.key || it.startsWith(next.key + "/") }.forEach { listStates.remove(it) }
        gridStates.remove(next.key)
        restoreKey = null
        screen = next
        if (next is Screen.Detail) loadDetail(next.id)
        if (next is Screen.Series) {
            shows = shows - next.id
            loadSeries(next.id, next.episodeId)
        }
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
        loadHome()
        // Refresh the grids someone can go back to; forget the rest so they load fresh when opened.
        val onStack = (backStack.toList() + screen).map { it.key }.toSet() + Screen.Movies.key
        lists.keys.toList().forEach { key -> if (key !in onStack) lists.remove(key) }
        lists.values.toList().forEach { reload(it, keepSize = true) }
        val open = (backStack.toList() + screen).filterIsInstance<Screen.Detail>().map { it.id }.toSet()
        details = details.filterKeys { it in open }
        similar = similar.filterKeys { it in open }
        open.forEach { loadDetail(it) }
        val openShows = (backStack.toList() + screen).filterIsInstance<Screen.Series>().map { it.id }.toSet()
        shows = shows.filterKeys { it in openShows }
        openShows.forEach { id -> loadSeries(id, null, keepSeason = true) }
    }

    /**
     * Loads a show with its seasons and the episodes of one season: the season of [episodeId] when given,
     * else the season the viewer is up to. [keepSeason] keeps the season already on screen, for refreshes.
     */
    private fun loadSeries(id: String, episodeId: String?, keepSeason: Boolean = false) {
        val shown = shows[id]?.selected?.takeIf { keepSeason }
        viewModelScope.launch {
            try {
                val view = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val series = async { api.item(id) }
                        val seasons = async { api.seasons(id) }
                        val next = async { runCatching { api.nextUp(1, seriesId = id).firstOrNull() }.getOrNull() }
                        val landing = async { episodeId?.let { runCatching { api.item(it) }.getOrNull() } }
                        val list = seasons.await()
                        val upNext = next.await()
                        fun known(season: String?) = season?.takeIf { wanted -> list.any { it.id == wanted } }
                        val season = known(shown)
                            ?: known(landing.await()?.seasonId)
                            ?: known(upNext?.seasonId)
                            ?: list.firstOrNull { (it.season ?: 0) > 0 }?.id
                            ?: list.firstOrNull()?.id
                        val episodes = season?.let { api.episodes(id, it) }.orEmpty()
                        SeriesView(
                            series = series.await(),
                            seasons = list,
                            selected = season,
                            episodes = if (season != null) mapOf(season to episodes) else emptyMap(),
                            next = upNext ?: episodes.firstOrNull()
                        )
                    }
                }
                shows = shows + (id to view)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is JellyfinException && error.code == 401) {
                    signOut()
                    return@launch
                }
                if (id !in shows) message = "That show didn't open."
            }
        }
    }

    fun selectSeason(seriesId: String, seasonId: String) {
        val view = shows[seriesId] ?: return
        if (view.selected == seasonId) return
        shows = shows + (seriesId to view.copy(selected = seasonId))
        if (seasonId in view.episodes) return
        viewModelScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) { api.episodes(seriesId, seasonId) }
                val latest = shows[seriesId] ?: return@launch
                shows = shows + (seriesId to latest.copy(episodes = latest.episodes + (seasonId to episodes)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "That season didn't load."
            }
        }
    }

    /** Each Home row asks the server for just its own posters. */
    private fun loadHome() {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            busy = home == null
            try {
                val fields = JellyfinClient.HOME_FIELDS
                val rows = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val resume = async { api.resume(16, fields) }
                        val nextUp = async { runCatching { api.nextUp(16, fields = fields) }.getOrDefault(emptyList()) }
                        val newShows = async {
                            runCatching {
                                api.movies(limit = 16, sortBy = "DateLastContentAdded,SortName", sortOrder = "Descending", fields = fields, countTotal = false, types = "Series").items
                            }.getOrDefault(emptyList())
                        }
                        val unwatched = async {
                            api.movies(limit = 30, sortBy = "DateCreated,SortName", sortOrder = "Descending", filters = "IsUnplayed", fields = fields, countTotal = false)
                                .items.filter { it.positionTicks <= 0L }.take(18)
                        }
                        val favorites = async { api.movies(limit = 16, filters = "IsFavorite", fields = fields, countTotal = false).items }
                        val recent = async { api.movies(limit = 18, sortBy = "DateCreated,SortName", sortOrder = "Descending", fields = fields) }
                        val hidden = async { runCatching { api.hiddenShows() }.getOrDefault(hiddenShows) }
                        HomeRows(resume.await(), nextUp.await(), unwatched.await(), favorites.await(), recent.await(), newShows.await(), hidden.await())
                    }
                }
                rows.all().forEach { keep(it) }
                hiddenShows = rows.hidden
                libraryEmpty = rows.recent.total == 0 && rows.shows.isEmpty()
                // Genre rows sit mid-page, so fill them in the same pass when we know last time's top genres.
                // Adding them later would push the rows below down while someone is looking.
                val known = home?.shelves.orEmpty().filter { it.genre != null }.ifEmpty {
                    genreShelves(savedTopGenres(), fields)
                }
                home = rows.feed(known)
                message = ""
                busy = false
                warmMovies()
                val top = genreStatsOrLoad()?.take(4)?.map { it.name } ?: return@launch
                saveTopGenres(top)
                val fresh = genreShelves(top, fields)
                if (fresh != known) home = rows.feed(fresh)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is JellyfinException && error.code == 401) {
                    signOut()
                    return@launch
                }
                if (home == null) message = "Can't reach Jellyfin at ${store.server}. Check the server in Settings."
            } finally {
                busy = false
            }
        }
    }

    private suspend fun genreShelves(names: List<String>, fields: String): List<Shelf> {
        if (names.isEmpty()) return emptyList()
        val shelves = withContext(Dispatchers.IO) {
            coroutineScope {
                names.map { name ->
                    async { runCatching { Shelf(name, api.movies(limit = 16, genre = name, fields = fields, countTotal = false).items, name) }.getOrNull() }
                }.awaitAll()
            }
        }.filterNotNull().filter { it.movies.isNotEmpty() }
        shelves.forEach { keep(it.movies) }
        return shelves
    }

    private fun savedTopGenres(): List<String> =
        libraryPrefs.getString("topGenres:${store.userId}", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun saveTopGenres(names: List<String>) {
        libraryPrefs.edit().putString("topGenres:${store.userId}", names.joinToString("\n")).apply()
    }

    /** Loads the first page of Movies in the background so opening it doesn't start from a spinner. */
    private fun warmMovies() {
        val list = listFor(Screen.Movies) ?: return
        if (!list.loaded) loadMore(list)
    }

    private class HomeRows(
        val resume: List<Movie>,
        val nextUp: List<Movie>,
        val unwatched: List<Movie>,
        val favorites: List<Movie>,
        val recent: MoviePage,
        val shows: List<Movie>,
        val hidden: Set<String>
    ) {
        fun all() = listOf(resume, nextUp, unwatched, favorites, recent.items, shows)

        /**
         * Titles stopped part-way and the next episode of each show, in one row, most recently watched
         * first. A next episode that hasn't been started takes the time of the one before it, since the
         * server already lists Next up by when each show was last watched.
         */
        private fun continuing(): List<Movie> {
            val started = resume.mapTo(HashSet()) { it.id }
            var carried = Long.MAX_VALUE
            val episodes = nextUp.filter { it.id !in started && it.seriesId !in hidden }.map { episode ->
                val at = episode.lastPlayed.takeIf { it > 0L } ?: carried
                carried = at
                episode to at
            }
            val stopped = resume.filter { !(it.isEpisode && it.seriesId in hidden) }.map { it to it.lastPlayed }
            return (episodes + stopped).sortedByDescending { it.second }.map { it.first }
        }

        fun feed(genres: List<Shelf>): HomeFeed {
            val continueRow = continuing()
            val shelves = buildList {
                if (continueRow.isNotEmpty()) add(Shelf("Continue watching", continueRow))
                if (unwatched.isNotEmpty()) add(Shelf("Unwatched", unwatched))
                if (favorites.isNotEmpty()) add(Shelf("Favorites", favorites))
                addAll(genres)
                if (recent.items.isNotEmpty()) add(Shelf("Recently added", recent.items))
                if (shows.isNotEmpty()) add(Shelf("TV shows with new episodes", shows))
            }
            return HomeFeed(continueRow.firstOrNull() ?: recent.items.firstOrNull(), continueRow.isNotEmpty(), shelves)
        }
    }

    private fun keep(movies: List<Movie>) {
        for (movie in movies) seen[movie.id] = movie
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
        homeJob?.cancel()
        searchJob?.cancel()
        genreLoad?.cancel()
        genreLoad = null
        home = null
        libraryEmpty = false
        genreStats = null
        genreNames = null
        searchResults = emptyList()
        searchPending = false
        similar = emptyMap()
        shows = emptyMap()
        lists.clear()
        seen.clear()
        details = emptyMap()
        hiddenShows = emptySet()
        saverOn = false
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

    fun setResumeBack(seconds: Int) {
        resumeBackSeconds = seconds
        store.resumeBackSeconds = seconds
    }

    fun setStillWatching(episodes: Int) {
        stillWatchingAfter = episodes
        store.stillWatchingAfter = episodes
    }

    fun toggleThemeMusic() {
        themeMusic = !themeMusic
        store.themeMusic = themeMusic
    }

    fun setScreensaver(minutes: Int) {
        screensaverMinutes = minutes
        store.screensaverMinutes = minutes
    }

    /** True when [seriesId] has an episode in Continue watching, so hiding it would change something. */
    fun inContinue(seriesId: String): Boolean =
        home?.shelves?.firstOrNull { it.title == "Continue watching" }?.movies?.any { it.seriesId == seriesId } == true

    fun toggleHidden(seriesId: String) {
        val hide = seriesId !in hiddenShows
        viewModelScope.launch {
            try {
                hiddenShows = withContext(Dispatchers.IO) { api.setShowHidden(seriesId, hide) }
                loadHome()
            } catch (_: Exception) {
                message = "That change didn't save."
            }
        }
    }

    /** Backdrops the screensaver fades between: whatever Home already has, in a shuffled order. */
    fun saverPictures(): List<Movie> =
        home?.shelves.orEmpty().flatMap { it.movies }.filter { it.backdropTag != null }.distinctBy { it.backdropOwner ?: it.id }.shuffled()

    fun setFrameRateMatching(on: Boolean) {
        matchFrameRate = on
        store.matchFrameRate = on
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
                loadHome()
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
                loadHome()
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
        if (id !in similar) {
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { api.similar(id, 16) } }.onSuccess { found ->
                    keep(found)
                    similar = similar + (id to found)
                }
            }
        }
    }

    private fun patch(id: String, transform: (Movie) -> Movie) {
        val swap: (List<Movie>) -> List<Movie> = { movies -> movies.map { if (it.id == id) transform(it) else it } }
        seen[id]?.let { seen[id] = transform(it) }
        details[id]?.let { details = details + (id to transform(it)) }
        home = home?.let { feed ->
            feed.copy(hero = feed.hero?.let { if (it.id == id) transform(it) else it }, shelves = feed.shelves.map { it.copy(movies = swap(it.movies)) })
        }
        lists.values.forEach { list -> if (list.items.any { it.id == id }) list.items = swap(list.items) }
        searchResults = swap(searchResults)
        similar = similar.mapValues { swap(it.value) }
        shows = shows.mapValues { (_, view) ->
            view.copy(
                series = if (view.series.id == id) transform(view.series) else view.series,
                episodes = view.episodes.mapValues { swap(it.value) }
            )
        }
    }

    companion object {
        private const val PAGE = 96
        private const val QUICK_POLL_MS = 3_000L
    }
}
