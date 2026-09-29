package tv.livingroom.watch

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val ContentStart = 20.dp + RailCollapsed + 24.dp

@Composable
fun WatchApp(vm: AppModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }
    val play: (String, Boolean) -> Unit = { id, fromStart ->
        launcher.launch(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, id)
                .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
                .putExtra(PlayerActivity.EXTRA_LIBRARY_EMPTY, vm.movies.isEmpty())
        )
    }
    val api = vm.api
    val screen = vm.screen
    val backdropMovie = when (screen) {
        Screen.Home -> vm.featuredId?.let(vm::movieFor) ?: homeFeed(vm.movies).hero
        is Screen.Detail -> vm.movieFor(screen.id)
        else -> null
    }
    val shot = backdropMovie?.let { api.imageUrl(it.id, "Backdrop", it.backdropTag, 1920) }
    Box(Modifier.fillMaxSize().background(Ink)) {
        FullBackdrop(shot, strong = screen is Screen.Detail)
        if (screen is Screen.Login) {
            LoginScreen(vm)
            return@Box
        }
        BackHandler(enabled = vm.canBack) { vm.back() }
        var railFocused by remember { mutableStateOf(false) }
        val scrim by animateDpAsState(if (railFocused) RailExpanded + 160.dp else 0.dp, label = "scrim")
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = ContentStart, end = SafeX)
                .onFocusChanged { vm.contentFocused = it.hasFocus }
                .focusGroup()
        ) {
            when (screen) {
                Screen.Home -> HomeScreen(vm, play)
                Screen.Search -> SearchScreen(vm)
                Screen.Movies -> GridScreen(vm, "Movies", tools = true)
                is Screen.Genre -> GridScreen(vm, screen.name)
                is Screen.Person -> GridScreen(vm, screen.name, kicker = screen.role.ifBlank { "Credits" })
                Screen.Genres -> GenresScreen(vm)
                is Screen.Detail -> DetailScreen(vm, screen.id, play)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Login -> Unit
            }
        }
        if (scrim > 0.dp) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(scrim)
                    .background(Brush.horizontalGradient(listOf(Color(0xF007080C), Color(0xCC07080C), Color.Transparent)))
            )
        }
        Rail(vm, onFocus = { railFocused = it })
        if (vm.message.isNotBlank() && screen !is Screen.Detail) {
            Text(
                vm.message,
                style = TvType.body,
                color = Cream,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SafeY)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Glass)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(vm: AppModel, onFocus: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) RailExpanded else RailCollapsed, label = "rail")
    val section = vm.section()
    val entries = listOf(
        Triple(Screen.Search, "Search", R.drawable.ic_search),
        Triple(Screen.Home, "Home", R.drawable.ic_home),
        Triple(Screen.Movies, "Movies", R.drawable.ic_movies),
        Triple(Screen.Genres, "Genres", R.drawable.ic_genres),
        Triple(Screen.Settings, "Settings", R.drawable.ic_settings)
    )
    val requesters = remember { entries.map { FocusRequester() } }
    val selectedIndex = entries.indexOfFirst { it.first == section }.coerceAtLeast(1)
    Column(
        Modifier
            .padding(start = 20.dp, top = SafeY, bottom = SafeY)
            .width(width)
            .fillMaxHeight()
            .onFocusChanged {
                expanded = it.hasFocus
                onFocus(it.hasFocus)
            }
            .focusProperties { enter = { requesters[selectedIndex] } }
            .focusGroup(),
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(if (expanded) Color(0xE6181A21) else Color(0x80181A21))
                .border(1.dp, Line, RoundedCornerShape(28.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            entries.forEachIndexed { index, (target, label, icon) ->
                RailItem(label, icon, section == target, expanded, requesters[index]) { vm.go(target) }
            }
        }
    }
}

@Composable
private fun rememberRestore(vm: AppModel): String? {
    return remember(vm.screen.key) { vm.restoreKey.also { vm.restoreKey = null } }
}

@Composable
private fun DefaultFocus(vm: AppModel, restore: String?, requester: FocusRequester) {
    LaunchedEffect(vm.screen.key) {
        if (restore != null) {
            delay(400)
            if (vm.contentFocused) return@LaunchedEffect
        } else {
            delay(60)
        }
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun LoginScreen(vm: AppModel) {
    when (vm.loginStep) {
        LoginStep.Server -> ServerStep(vm)
        LoginStep.Profiles -> ProfileStep(vm)
    }
    QuickConnectDialog(vm)
}

@Composable
private fun ServerStep(vm: AppModel) {
    var askAddress by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler(enabled = vm.store.server.isNotBlank()) { vm.keepServer() }
    LaunchedEffect(vm.searching, vm.servers.size) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = SafeX + 24.dp, vertical = SafeY),
        verticalArrangement = Arrangement.Center
    ) {
        Kicker("Living Room")
        Text("Choose your server", style = TvType.display)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                vm.searching -> "Looking for Jellyfin on your network…"
                vm.servers.isEmpty() && vm.searched -> "No servers found on this network. Enter the address instead."
                vm.servers.isEmpty() -> "Enter your Jellyfin server's address."
                else -> "Found on your network. Pick one, or enter an address."
            },
            style = TvType.body
        )
        Spacer(Modifier.height(24.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)
        ) {
            items(vm.servers, key = { it.id }) { server ->
                ProfileTile(
                    name = server.name,
                    imageUrl = null,
                    icon = R.drawable.ic_server,
                    note = server.address.substringAfter("://"),
                    width = 170.dp,
                    requester = if (server == vm.servers.first()) first else null
                ) { vm.chooseServer(server) }
            }
            item {
                ProfileTile(
                    name = "Enter address",
                    imageUrl = null,
                    icon = R.drawable.ic_keyboard,
                    width = 170.dp,
                    requester = if (vm.servers.isEmpty()) first else null
                ) { askAddress = true }
            }
            item {
                ProfileTile(
                    name = if (vm.searching) "Searching…" else "Search again",
                    imageUrl = null,
                    icon = R.drawable.ic_search,
                    width = 170.dp
                ) { vm.findServers() }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (vm.busy) {
            CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else if (vm.message.isNotBlank()) {
            Text(vm.message, style = TvType.body, color = Cream)
        }
    }
    if (askAddress) {
        TextEntryDialog(
            "Server address",
            vm.store.server,
            "192.168.1.10:8096",
            password = false,
            note = "The address you open Jellyfin with in a browser. http:// and :8096 are added if you leave them out.",
            onDismiss = { askAddress = false }
        ) { typed ->
            askAddress = false
            vm.connectTo(typed)
        }
    }
}

@Composable
private fun ProfileStep(vm: AppModel) {
    var passwordFor by remember { mutableStateOf<Profile?>(null) }
    var askUser by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(vm.profilesLoaded, vm.profiles.size) {
        if (vm.profilesLoaded) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = SafeX + 24.dp, vertical = SafeY),
        verticalArrangement = Arrangement.Center
    ) {
        Kicker("Living Room")
        Text("Who's watching?", style = TvType.display)
        Spacer(Modifier.height(28.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)
        ) {
            items(vm.profiles, key = { it.id }) { profile ->
                ProfileTile(
                    name = profile.name,
                    imageUrl = if (profile.hasImage) vm.api.userImageUrl(profile.id) else null,
                    requester = if (profile == vm.profiles.first()) first else null
                ) {
                    if (profile.hasPassword) passwordFor = profile else vm.signIn(profile.name, "")
                }
            }
            item {
                ProfileTile(
                    name = "Other user",
                    imageUrl = null,
                    icon = R.drawable.ic_add,
                    requester = if (vm.profiles.isEmpty()) first else null
                ) { askUser = true }
            }
            item {
                ProfileTile(name = "Quick Connect", imageUrl = null, icon = R.drawable.ic_phone) { vm.startQuickConnect() }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (vm.busy) {
            CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else if (vm.message.isNotBlank()) {
            Text(vm.message, style = TvType.body, color = Cream)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val address = vm.store.server.substringAfter("://")
            Text(
                "Server  " + if (vm.store.serverName.isBlank()) address else "${vm.store.serverName} · $address",
                style = TvType.small
            )
            TvButton("Change server") { vm.changeServer() }
        }
    }
    passwordFor?.let { profile ->
        TextEntryDialog(
            title = profile.name,
            initial = "",
            hint = "Password",
            password = true,
            onDismiss = { passwordFor = null }
        ) { password ->
            passwordFor = null
            vm.signIn(profile.name, password)
        }
    }
    if (askUser) {
        TextEntryDialog("Username", manualName, "Username", password = false, onDismiss = { askUser = false }) { name ->
            askUser = false
            manualName = name.trim()
            if (manualName.isNotEmpty()) passwordFor = Profile("", manualName, hasPassword = true, hasImage = false)
        }
    }
}

@Composable
private fun QuickConnectDialog(vm: AppModel) {
    val state = vm.quick
    if (state == QuickState.Closed) return
    Dialog(onDismissRequest = { vm.closeQuickConnect() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val action = remember { FocusRequester() }
        LaunchedEffect(state::class) {
            delay(60)
            runCatching { action.requestFocus() }
        }
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2181A21))
                .border(1.dp, Line, RoundedCornerShape(22.dp))
                .padding(28.dp)
        ) {
            Text("Quick Connect", style = TvType.headline)
            Spacer(Modifier.height(12.dp))
            when (state) {
                QuickState.Starting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Getting a code…", style = TvType.body)
                }
                is QuickState.Waiting -> {
                    Text(
                        "On your phone or computer, open Jellyfin while signed in, go to your profile, choose Quick Connect and enter this code.",
                        style = TvType.body
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        state.code.chunked(3).joinToString(" "),
                        style = TvType.display.copy(fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = 6.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = Muted, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Text("Waiting for you to approve it. This TV signs in by itself.", style = TvType.small)
                    }
                }
                QuickState.Off -> Text(
                    "Quick Connect is switched off on this server. Someone with an admin account can turn it on in Jellyfin under Dashboard, General. Until then, sign in with a password.",
                    style = TvType.body
                )
                is QuickState.Failed -> Text("${state.note} Try again for a new code, or sign in with a password.", style = TvType.body)
                QuickState.Closed -> Unit
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is QuickState.Failed) {
                    TvButton("Try again", primary = true, focusRequester = action) { vm.startQuickConnect() }
                }
                TvButton(
                    if (state == QuickState.Off) "OK" else "Cancel",
                    focusRequester = if (state is QuickState.Failed) null else action
                ) { vm.closeQuickConnect() }
            }
        }
    }
}

@Composable
private fun ProfileTile(
    name: String,
    imageUrl: String?,
    icon: Int? = null,
    note: String = "",
    width: Dp = 120.dp,
    requester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val size by animateDpAsState(if (focused) 108.dp else 96.dp, label = "profile")
    Column(
        Modifier
            .width(width)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Line, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    imageUrl != null -> AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                    icon != null -> Image(painterResource(icon), null, colorFilter = ColorFilter.tint(Cream), modifier = Modifier.size(32.dp))
                    else -> Text(name.take(1).uppercase(), style = TvType.display)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = TvType.title, color = if (focused) Cream else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note.isNotBlank()) {
            Text(note, style = TvType.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(vm: AppModel, play: (String, Boolean) -> Unit) {
    val feed = remember(vm.movies) { homeFeed(vm.movies) }
    val hero = feed.hero
    if (hero == null) {
        EmptyLibrary(vm)
        return
    }
    val restore = rememberRestore(vm)
    val playFocus = remember { FocusRequester() }
    DefaultFocus(vm, restore, playFocus)
    var focusedId by remember { mutableStateOf<String?>(null) }
    var focusedShelf by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedId) {
        delay(240)
        vm.featuredId = focusedId
    }
    val featured = vm.featuredId?.let(vm::movieFor) ?: hero
    Column(Modifier.fillMaxSize().padding(top = SafeY)) {
        Column(Modifier.fillMaxWidth(0.62f).height(196.dp), verticalArrangement = Arrangement.Bottom) {
            val kicker = when {
                focusedShelf != null -> focusedShelf!!
                feed.resumeFirst -> "Continue watching"
                else -> "Recently added"
            }
            Kicker(kicker)
            Text(featured.name, style = TvType.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            MetaLine(featured, showEnds = featured.positionTicks > 0)
            if (featured.overview.isNotBlank()) {
                Text(
                    featured.overview,
                    style = TvType.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        val rowSpec = LocalBringIntoViewSpec.current
        val headroom = with(LocalDensity.current) { 34.dp.toPx() }
        val shelfSpec = remember(headroom) { ShelfTop(headroom) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides shelfSpec) {
            LazyColumn(
                state = vm.listState("home"),
                contentPadding = PaddingValues(top = 14.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "actions") {
                    Row(
                        Modifier.padding(bottom = 10.dp).focusRestorer().focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val resume = feed.resumeFirst && hero.positionTicks > 0
                        val backToHero = {
                            focusedId = null
                            focusedShelf = null
                        }
                        TvButton(
                            if (resume) "Resume from ${ticksToClock(hero.positionTicks)}" else "Play",
                            Modifier.width(if (resume) 200.dp else 120.dp),
                            icon = R.drawable.ic_play,
                            primary = true,
                            focusRequester = playFocus,
                            onFocus = backToHero
                        ) { play(hero.id, false) }
                        TvButton("Details", onFocus = backToHero) {
                            vm.rememberFocus("actions")
                            vm.open(Screen.Detail(hero.id))
                        }
                        TvButton("Surprise me", onFocus = backToHero) {
                            val pool = vm.movies.filter { !it.played }.ifEmpty { vm.movies }
                            pool.randomOrNull()?.let { play(it.id, true) }
                        }
                    }
                }
                items(feed.shelves, key = { it.title }) { shelf ->
                    Column {
                        Text(
                            if (shelf.genre != null) "${shelf.title}  ›" else shelf.title,
                            style = TvType.title,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                        )
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                            LazyRow(
                                state = vm.listState("home/row:${shelf.title}"),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(start = 2.dp, end = 40.dp, top = 10.dp, bottom = 8.dp),
                                modifier = Modifier.focusRestorer().focusGroup()
                            ) {
                                items(shelf.movies, key = { it.id }) { movie ->
                                    val key = "home/${shelf.title}/${movie.id}"
                                    PosterCard(
                                        movie,
                                        vm.api.imageUrl(movie.id, "Primary", movie.primaryTag, 360),
                                        autoFocus = restore == key,
                                        onFocus = {
                                            focusedId = movie.id
                                            focusedShelf = shelf.title
                                        }
                                    ) {
                                        vm.rememberFocus(key)
                                        vm.open(Screen.Detail(movie.id))
                                    }
                                }
                                if (shelf.genre != null) {
                                    item(key = "more") {
                                        TvButton("See all", Modifier.padding(top = 60.dp)) {
                                            vm.rememberFocus("home/${shelf.title}/more")
                                            vm.open(Screen.Genre(shelf.genre))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(vm: AppModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        if (vm.busy) {
            CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(14.dp))
            Text("Loading your movies…", style = TvType.title)
            return
        }
        Text("No movies yet", style = TvType.headline)
        Text(
            vm.message.ifBlank { "If the movie drive isn't mounted, plug it in and restart Jellyfin." },
            style = TvType.body,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        val retry = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
        TvButton("Try again", primary = true, focusRequester = retry) { vm.refresh() }
    }
}

@Composable
private fun GridScreen(vm: AppModel, title: String, kicker: String = "", tools: Boolean = false) {
    val restore = rememberRestore(vm)
    val items = vm.shown()
    val first = remember { FocusRequester() }
    DefaultFocus(vm, restore, first)
    val screenKey = vm.screen.key
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        state = vm.gridState(screenKey),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = SafeY, bottom = 48.dp, start = 2.dp, end = 2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column(Modifier.padding(bottom = 10.dp)) {
                if (kicker.isNotBlank()) Kicker(kicker)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(title, style = TvType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("   ${items.size} ${if (items.size == 1) "movie" else "movies"}", style = TvType.body)
                }
                if (tools) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sortName = when (vm.sort) {
                            "year" -> "Newest"
                            "added" -> "Recently added"
                            else -> "A to Z"
                        }
                        TvButton("Sort: $sortName") {
                            vm.sort = when (vm.sort) {
                                "title" -> "added"
                                "added" -> "year"
                                else -> "title"
                            }
                        }
                        TvButton("Unwatched only", selected = vm.unwatchedOnly) { vm.unwatchedOnly = !vm.unwatchedOnly }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Text(
                    if (vm.movies.isEmpty()) "No movies are in the library." else "Nothing here.",
                    style = TvType.title
                )
            }
        }
        gridItems(items, key = { it.id }) { movie ->
            val key = "$screenKey/${movie.id}"
            PosterCard(
                movie,
                vm.api.imageUrl(movie.id, "Primary", movie.primaryTag, 360),
                modifier = if (movie == items.first()) Modifier.focusRequester(first) else Modifier,
                width = null,
                autoFocus = restore == key
            ) {
                vm.rememberFocus(key)
                vm.open(Screen.Detail(movie.id))
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: AppModel) {
    val restore = rememberRestore(vm)
    val firstKey = remember { FocusRequester() }
    DefaultFocus(vm, restore, firstKey)
    val results = vm.shown()
    Row(Modifier.fillMaxSize().padding(top = SafeY)) {
        Column(Modifier.width(250.dp)) {
            Text("Search", style = TvType.headline)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    if (vm.query.isEmpty()) "Titles, people, genres" else vm.query + "▏",
                    style = TvType.title,
                    color = if (vm.query.isEmpty()) Faint else Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(14.dp))
            Keyboard(firstKey) { key ->
                vm.query = when (key) {
                    "⌫" -> vm.query.dropLast(1)
                    "Clear" -> ""
                    "Space" -> if (vm.query.isEmpty() || vm.query.endsWith(" ")) vm.query else "${vm.query} "
                    else -> vm.query + key
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        Box(Modifier.weight(1f)) {
            if (vm.query.isBlank()) {
                Text("Type with the remote. Results appear here.", style = TvType.body, modifier = Modifier.padding(top = 44.dp))
            } else if (results.isEmpty()) {
                Text("No matches for “${vm.query.trim()}”.", style = TvType.body, modifier = Modifier.padding(top = 44.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = vm.gridState("search"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 36.dp, bottom = 48.dp, start = 2.dp, end = 2.dp)
                ) {
                    gridItems(results, key = { it.id }) { movie ->
                        val key = "search/${movie.id}"
                        PosterCard(movie, vm.api.imageUrl(movie.id, "Primary", movie.primaryTag, 360), width = null, autoFocus = restore == key) {
                            vm.rememberFocus(key)
                            vm.open(Screen.Detail(movie.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Keyboard(first: FocusRequester, onKey: (String) -> Unit) {
    val letters = ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        letters.chunked(6).forEachIndexed { row, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                keys.forEachIndexed { col, key ->
                    KeyCap(key, Modifier.width(37.dp), if (row == 0 && col == 0) first else null) { onKey(key) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyCap("Space", Modifier.width(79.dp)) { onKey("Space") }
            KeyCap("⌫", Modifier.width(79.dp)) { onKey("⌫") }
            KeyCap("Clear", Modifier.width(79.dp)) { onKey("Clear") }
        }
    }
}

@Composable
private fun KeyCap(label: String, modifier: Modifier, requester: FocusRequester? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = TvType.button, color = if (focused) Color(0xFF111111) else Cream)
    }
}

@Composable
private fun GenresScreen(vm: AppModel) {
    val restore = rememberRestore(vm)
    val first = remember { FocusRequester() }
    DefaultFocus(vm, restore, first)
    val groups = remember(vm.movies) {
        vm.movies.flatMap { movie -> movie.genres.map { it to movie } }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedByDescending { it.value.size }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = vm.gridState("genres"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = SafeY, bottom = 48.dp, start = 2.dp, end = 2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Genres", style = TvType.headline, modifier = Modifier.padding(bottom = 6.dp))
        }
        gridItems(groups, key = { it.key }) { entry ->
            val cover = entry.value.filter { it.backdropTag != null }.maxByOrNull { it.communityRating ?: 0.0 }
            val key = "genres/${entry.key}"
            GenreTile(
                entry.key,
                entry.value.size,
                cover?.let { vm.api.imageUrl(it.id, "Backdrop", it.backdropTag, 640) },
                requester = if (entry == groups.first()) first else null,
                autoFocus = restore == key
            ) {
                vm.rememberFocus(key)
                vm.open(Screen.Genre(entry.key))
            }
        }
    }
}

@Composable
private fun GenreTile(name: String, count: Int, imageUrl: String?, requester: FocusRequester?, autoFocus: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val own = remember { FocusRequester() }
    val target = requester ?: own
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { target.requestFocus() } }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "genre")
    Box(
        Modifier
            .focusRequester(target)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1D24))
            .border(if (focused) 2.dp else 0.5.dp, if (focused) Color.White else Line, RoundedCornerShape(12.dp))
            .clickable(interaction, indication = null, onClick = onClick)
    ) {
        if (imageUrl != null) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x1A000000), Color(0xD9000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(name, style = TvType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count movies", style = TvType.small)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailScreen(vm: AppModel, id: String, play: (String, Boolean) -> Unit) {
    val movie = vm.movieFor(id)
    if (movie == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        return
    }
    val restore = rememberRestore(vm)
    val playFocus = remember { FocusRequester() }
    DefaultFocus(vm, restore, playFocus)
    val similar = remember(movie.id, vm.movies) { vm.moreLike(movie) }
    val crew = remember(movie) {
        movie.people.filter { it.type == "Director" } +
            movie.people.filter { it.type == "Actor" }.take(14) +
            movie.people.filter { it.type == "Writer" }.take(3)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        DetailBody(vm, movie, restore, playFocus, similar, crew, play)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            size > containerSize || offset < 0f -> offset
            else -> trailing - containerSize
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class ShelfTop(private val headroom: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - headroom
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DetailBody(
    vm: AppModel,
    movie: Movie,
    restore: String?,
    playFocus: FocusRequester,
    similar: List<Movie>,
    crew: List<PersonCredit>,
    play: (String, Boolean) -> Unit
) {
    LazyColumn(
        state = vm.listState(vm.screen.key),
        contentPadding = PaddingValues(top = 72.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(0.66f)) {
                    if (movie.genres.isNotEmpty()) Kicker(movie.genres.take(3).joinToString("  ·  "))
                    Text(movie.name, style = TvType.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    MetaLine(movie)
                    if (movie.progress > 0.02f) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.width(160.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))
                            ) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(movie.progress).background(Color.White))
                            }
                            Text(
                                "   ${runtimeLabel((movie.runtimeTicks - movie.positionTicks).coerceAtLeast(0L))} left",
                                style = TvType.small
                            )
                        }
                    }
                    if (movie.tagline.isNotBlank()) {
                        Text(movie.tagline, style = TvType.body.copy(fontStyle = FontStyle.Italic), color = Cream, modifier = Modifier.padding(top = 12.dp))
                    }
                    if (movie.overview.isNotBlank()) {
                        Text(movie.overview, style = TvType.body, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.focusRestorer().focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val resume = movie.positionTicks > 0
                    TvButton(
                        if (resume) "Resume from ${ticksToClock(movie.positionTicks)}" else "Play",
                        Modifier.width(if (resume) 210.dp else 120.dp),
                        icon = R.drawable.ic_play,
                        primary = true,
                        focusRequester = playFocus
                    ) { play(movie.id, false) }
                    if (resume) TvButton("From the start", icon = R.drawable.ic_restart) { play(movie.id, true) }
                    TvButton(if (movie.favorite) "In favorites" else "Favorite", icon = R.drawable.ic_heart, selected = movie.favorite) {
                        vm.toggleFavorite(movie)
                    }
                    TvButton(if (movie.played) "Watched" else "Mark watched", icon = R.drawable.ic_check, selected = movie.played) {
                        vm.togglePlayed(movie)
                    }
                }
                if (vm.message.isNotBlank()) Text(vm.message, style = TvType.body, color = Cream, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (crew.isNotEmpty()) {
            item(key = "crew") {
                Column {
                    Text("Cast & crew", style = TvType.title, modifier = Modifier.padding(bottom = 8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                        modifier = Modifier.focusRestorer().focusGroup()
                    ) {
                        items(crew, key = { it.type + it.name }) { person ->
                            val label = if (person.type == "Director") "Director · ${person.name}" else person.name
                            TvButton(label) {
                                vm.rememberFocus("person/${person.name}")
                                vm.open(Screen.Person(person.name, person.type))
                            }
                        }
                    }
                }
            }
        }
        if (similar.isNotEmpty()) {
            item(key = "similar") {
                Column {
                    Text("More like this", style = TvType.title)
                    LazyRow(
                        state = vm.listState(vm.screen.key + "/similar"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(start = 2.dp, end = 40.dp, top = 10.dp, bottom = 8.dp),
                        modifier = Modifier.focusRestorer().focusGroup()
                    ) {
                        items(similar, key = { it.id }) { other ->
                            val key = "${vm.screen.key}/similar/${other.id}"
                            PosterCard(other, vm.api.imageUrl(other.id, "Primary", other.primaryTag, 360), autoFocus = restore == key) {
                                vm.rememberFocus(key)
                                vm.open(Screen.Detail(other.id))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppModel) {
    val restore = rememberRestore(vm)
    val first = remember { FocusRequester() }
    DefaultFocus(vm, restore, first)
    var picker by remember { mutableStateOf("") }
    var askOsUser by remember { mutableStateOf(false) }
    var askOsPass by remember { mutableStateOf(false) }
    var pendingOsUser by remember { mutableStateOf("") }
    var askServer by remember { mutableStateOf(false) }
    val cueNames = listOf(1f to "Small", 1.35f to "Medium", 1.7f to "Large", 2.1f to "Extra large")
    LazyColumn(
        contentPadding = PaddingValues(top = SafeY, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { Text("Settings", style = TvType.headline, modifier = Modifier.padding(bottom = 8.dp)) }
        item { Kicker("Playback", Modifier.padding(top = 4.dp)) }
        item {
            SettingRow("Hide controls after", "${vm.controlsSeconds} seconds", focusRequester = first) {
                val options = listOf(2, 3, 5, 8)
                vm.setControls(options[(options.indexOf(vm.controlsSeconds) + 1) % options.size])
            }
        }
        item {
            SettingRow("Match frame rate", if (vm.matchFrameRate) "On" else "Off") { vm.setFrameRateMatching(!vm.matchFrameRate) }
        }
        item {
            val current = cueNames.minByOrNull { kotlin.math.abs(it.first - vm.cueScale) }?.second ?: "Medium"
            SettingRow("Subtitle size", current) {
                val at = cueNames.indexOfFirst { it.second == current }
                vm.setCue(cueNames[(at + 1) % cueNames.size].first)
            }
        }
        item {
            SettingRow("Subtitle language", SUB_LANGS.find { it.code == vm.subtitleLanguage }?.label ?: "English") { picker = "lang" }
        }
        item {
            SettingRow("Find missing subtitles automatically", if (vm.autoSubtitles) "On" else "Off") { vm.setAutoSubs(!vm.autoSubtitles) }
        }
        item { Kicker("OpenSubtitles", Modifier.padding(top = 10.dp)) }
        item {
            SettingRow("Account", vm.osUser.ifBlank { "Not set" }) {
                pendingOsUser = vm.osUser
                askOsUser = true
            }
        }
        if (vm.osNote.isNotBlank()) item { Text(vm.osNote, style = TvType.small, modifier = Modifier.padding(start = 4.dp)) }
        item { Kicker("Account", Modifier.padding(top = 10.dp)) }
        item { SettingRow("Server", vm.store.serverName.ifBlank { vm.store.server.substringAfter("://") }) { askServer = true } }
        item { SettingRow("Sign out", vm.store.userName) { vm.signOut() } }
        item { Text("Living Room TV 1.3 · ${vm.api.let { "tv.livingroom.watch" }}", style = TvType.small, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
    }
    if (picker == "lang") {
        PickerDialog(
            "Subtitle language",
            SUB_LANGS.map { it.label },
            SUB_LANGS.indexOfFirst { it.code == vm.subtitleLanguage },
            onDismiss = { picker = "" }
        ) {
            vm.setLanguage(SUB_LANGS[it].code)
            picker = ""
        }
    }
    if (askOsUser) {
        TextEntryDialog("OpenSubtitles username", pendingOsUser, "Username", password = false, onDismiss = { askOsUser = false }) {
            pendingOsUser = it.trim()
            askOsUser = false
            askOsPass = true
        }
    }
    if (askOsPass) {
        TextEntryDialog("OpenSubtitles password", "", vm.osHint, password = true, onDismiss = { askOsPass = false }) {
            askOsPass = false
            vm.saveOpenSubtitles(pendingOsUser, it)
        }
    }
    if (askServer) {
        PickerDialog(
            "Change server? You'll be signed out.",
            listOf("Choose another server", "Cancel"),
            selected = -1,
            onDismiss = { askServer = false }
        ) {
            askServer = false
            if (it == 0) vm.changeServer()
        }
    }

}
