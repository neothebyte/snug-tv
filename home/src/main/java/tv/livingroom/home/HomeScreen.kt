package tv.livingroom.home

import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Date
import kotlinx.coroutines.delay
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tv.livingroom.ui.FocusScale
import tv.livingroom.ui.Motion
import tv.livingroom.ui.drawFocusGlow
import tv.livingroom.ui.rememberBlurHashPainter

private const val COLUMNS = 5
private val TileGap = 22.dp
// Where the first row of apps sits, under the top shelf.
private val GridTop = 318.dp
// Where a lower row sits once the shelf has slid away.
private val RowParked = 64.dp
private val RowPitch = 132.dp
private val TileShape = RoundedCornerShape(10.dp)
// The camera tile, top right of the shelf under the clock.
private val CameraWidth = 208.dp

/**
 * The home screen: a top shelf for the focused app, and the apps in rows of five underneath, like Apple TV.
 * Long-press OK (or press Menu) on an app to move it; arrows move it, OK puts it down.
 */
@Composable
fun HomeScreen(
    apps: List<TvApp>,
    shelf: List<ShelfItem>,
    weather: Weather?,
    camera: Camera?,
    cameraOnScreen: Boolean,
    resetSignal: Int,
    network: NetworkState,
    ambient: Boolean,
    store: AppStore,
    onLaunch: (TvApp) -> Unit,
    onOpenItem: (ShelfItem) -> Unit,
    onReorder: (List<TvApp>) -> Unit,
    onHide: (TvApp) -> Unit,
    onUnhide: (String) -> Unit,
    onAppInfo: (TvApp) -> Unit
) {
    // Long-pressing OK on a tile offers Move, Hide and App info; the Hidden apps tile lists what's hidden.
    var options by remember { mutableStateOf<TvApp?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home", android.content.Context.MODE_PRIVATE) }
    var hintSeen by remember { mutableStateOf(prefs.getBoolean("optionsHintSeen", false)) }
    var order by remember { mutableStateOf(apps) }
    var moving by remember { mutableStateOf<String?>(null) }
    // While an app is being moved, the box's own list changes (an install, say) must not jump it around.
    LaunchedEffect(apps) { if (moving == null) order = apps }

    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusedRow by remember { mutableStateOf(0) }
    var onShelf by remember { mutableStateOf(false) }
    var heroIndex by remember { mutableStateOf(0) }

    // The shelf belongs to the last app that had focus, and stays while focus is on its cards.
    val owner = order.firstOrNull { it.key == focusedKey } ?: order.firstOrNull()
    val snugShelf = owner?.key == SNUG && shelf.isNotEmpty()

    // Start on the first app, and come back to it when Home is pressed on the home screen.
    LaunchedEffect(resetSignal, order.isNotEmpty()) {
        val first = order.firstOrNull() ?: return@LaunchedEffect
        moving = null
        withFrameNanos { }
        runCatching { requester(first.key).requestFocus() }
    }
    // A moved app is a new tile in its new row: give it focus back.
    LaunchedEffect(order, moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }

    val scroll by animateDpAsState(
        if (focusedRow == 0 || onShelf) 0.dp else -(GridTop - RowParked) - RowPitch * (focusedRow - 1),
        animationSpec = Motion.slide(),
        label = "scroll"
    )
    val shelfAlpha by animateFloatAsState(if (focusedRow == 0 || onShelf) 1f else 0f, Motion.fade(), label = "shelf")

    Box(Modifier.fillMaxSize()) {
        ShelfBackdrop(
            owner = owner,
            item = if (snugShelf) shelf.getOrNull(heroIndex) ?: shelf.first() else null,
            modifier = Modifier.graphicsLayer { alpha = shelfAlpha }
        )

        // Three or more rows of apps are taller than the screen. Measured against the screen's height, the
        // rows past the bottom were squeezed and drawn on top of the row above; let the column be as tall as it is.
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .offset { IntOffset(0, scroll.roundToPx()) }
                .padding(horizontal = SafeX)
        ) {
            Box(Modifier.fillMaxWidth().height(GridTop).graphicsLayer { alpha = shelfAlpha }) {
                ShelfFront(
                    owner = owner,
                    titleEnd = if (camera != null) CameraWidth + 24.dp else 0.dp,
                    shelf = if (snugShelf) shelf else emptyList(),
                    lastOpened = owner?.key?.let { store.lastOpened(it) } ?: 0L,
                    heroIndex = heroIndex,
                    snugRequester = requester(SNUG),
                    onCardFocus = { i -> heroIndex = i; onShelf = true },
                    onOpenItem = onOpenItem
                )
                if (camera != null) {
                    CameraTile(
                        camera = camera,
                        // Only while the shelf is showing and nothing covers it.
                        play = cameraOnScreen && !ambient && !cameraOpen && (focusedRow == 0 || onShelf),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = SafeY + 56.dp).width(CameraWidth),
                        onOpen = { cameraOpen = true }
                    )
                }
            }
            order.chunked(COLUMNS).forEachIndexed { row, rowApps ->
                Row(
                    Modifier.fillMaxWidth().height(RowPitch),
                    horizontalArrangement = Arrangement.spacedBy(TileGap)
                ) {
                    rowApps.forEach { app ->
                        key(app.key) {
                            AppTile(
                                app = app,
                                moving = moving == app.key,
                                requester = requester(app.key),
                                modifier = Modifier.weight(1f),
                                onFocused = {
                                    focusedKey = app.key
                                    focusedRow = row
                                    onShelf = false
                                    if (app.key != SNUG) heroIndex = 0
                                },
                                onClick = { if (app.key == HIDDEN_KEY) showHidden = true else onLaunch(app) },
                                onOptions = {
                                    if (app.key == HIDDEN_KEY) {
                                        showHidden = true
                                    } else {
                                        options = app
                                        if (!hintSeen) {
                                            hintSeen = true
                                            prefs.edit().putBoolean("optionsHintSeen", true).apply()
                                        }
                                    }
                                },
                                onStartMove = { moving = app.key },
                                onMove = { step ->
                                    val from = order.indexOfFirst { it.key == app.key }
                                    val to = (from + step).coerceIn(0, order.lastIndex)
                                    if (to != from) {
                                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                                    }
                                },
                                onEndMove = {
                                    moving = null
                                    onReorder(order)
                                }
                            )
                        }
                    }
                    // Keep the last row's tiles the same size as the others.
                    repeat(COLUMNS - rowApps.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        StatusCorner(
            weather = weather,
            clock = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetworkMark(network)
                    Clock(Modifier)
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = SafeY, end = SafeX)
        )

        // Hints sit top left, across from the clock: at the bottom they covered the second row of apps.
        // Once, until it's been used: how to get to Move and Hide.
        val showHint = !hintSeen && moving == null && apps.size > 1 && !onShelf && focusedRow == 0
        if (showHint) {
            BasicText(
                "Hold OK on an app to move or hide it",
                style = HomeType.hint,
                modifier = HintPill.align(Alignment.TopStart)
            )
        }

        if (moving != null) {
            BasicText(
                "Use the arrows to move it, then press OK",
                style = HomeType.hint,
                modifier = HintPill.align(Alignment.TopStart)
            )
        }

        AnimatedVisibility(ambient, enter = fadeIn(tween(900)), exit = fadeOut(tween(300))) {
            Ambient(shelf)
        }
    }

    if (cameraOpen && camera != null) {
        CameraFullScreen(camera, onClose = { cameraOpen = false })
    }
    options?.let { app ->
        ChoiceDialog(
            title = app.label,
            choices = buildList {
                add("Move")
                if (app.key != SETTINGS_KEY) {
                    add("Hide from home screen")
                    add("App info")
                }
                add("Cancel")
            },
            onDismiss = { options = null }
        ) { choice ->
            options = null
            when (choice) {
                "Move" -> moving = app.key
                "Hide from home screen" -> onHide(app)
                "App info" -> onAppInfo(app)
            }
        }
    }
    if (showHidden) {
        val hidden = remember { store.hiddenApps() }
        ChoiceDialog(
            title = "Hidden apps",
            note = "Pick one to put it back on the home screen.",
            choices = hidden.map { it.second } + "Done",
            onDismiss = { showHidden = false }
        ) { choice ->
            showHidden = false
            hidden.firstOrNull { it.second == choice }?.let { onUnhide(it.first) }
        }
    }
}

/** A short list to pick from, in a panel over the home screen. */
@Composable
private fun ChoiceDialog(title: String, choices: List<String>, note: String = "", onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Column(
            Modifier
                .width(340.dp)
                .heightIn(max = 440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2181A21))
                .border(1.dp, Line, RoundedCornerShape(22.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BasicText(title, style = HomeType.hero.copy(fontSize = HomeType.clock.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotBlank()) BasicText(note, style = HomeType.hint)
            Spacer(Modifier.height(6.dp))
            choices.forEachIndexed { i, choice ->
                ChoiceRow(choice, if (i == 0) first else null) { onPick(choice) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Only a press that started here counts: the release of the long press that opened the list must not pick.
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) pressed = false }
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode in SELECT_KEYS) {
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) pressed = true
                    if (e.type == KeyEventType.KeyUp && pressed) {
                        pressed = false
                        onClick()
                    }
                    true
                } else false
            }
            .focusable()
            .clip(RoundedCornerShape(17.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        BasicText(label, style = HomeType.tileName.copy(color = if (focused) Color(0xFF111111) else Cream), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small mark for how the box is connected; a crossed-out one when it isn't. */
@Composable
private fun NetworkMark(state: NetworkState) {
    val icon = when (state) {
        NetworkState.WiFi -> R.drawable.ic_wifi
        NetworkState.Wired -> R.drawable.ic_wired
        NetworkState.Offline -> R.drawable.ic_offline
        else -> return
    }
    Image(
        painterResource(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(if (state == NetworkState.Offline) Accent else Muted),
        modifier = Modifier.size(18.dp)
    )
}

/**
 * The ambient screen after a few idle minutes: Snug's pictures from the shelf, slowly fading one into the
 * next, with the time. Just the time over the dark page when Snug has nothing to show.
 */
@Composable
private fun Ambient(shelf: List<ShelfItem>) {
    val pictures = remember(shelf) { shelf.mapNotNull { it.image }.distinct().shuffled() }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(pictures) {
        while (pictures.size > 1) {
            delay(12_000)
            index = (index + 1) % pictures.size
        }
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        val context = LocalContext.current
        Crossfade(pictures.getOrNull(index), animationSpec = tween(2_000), label = "ambient") { url ->
            if (url != null) {
                val request = remember(url) { ImageRequest.Builder(context).data(url).allowRgb565(true).build() }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC07080C))))
        Clock(Modifier.align(Alignment.BottomStart).padding(start = SafeX, bottom = SafeY), HomeType.saverClock)
    }
}

/** The full-width picture behind the shelf: Snug's artwork, or a calm wash of the focused app's colour. */
@Composable
private fun ShelfBackdrop(owner: TvApp?, item: ShelfItem?, modifier: Modifier) {
    val tint by animateColorAsState(owner?.tint ?: TileBlank, tween(400), label = "tint")
    Box(modifier.fillMaxWidth().height(400.dp)) {
        if (item?.image != null) {
            val context = LocalContext.current
            val request = remember(item.image) {
                ImageRequest.Builder(context).data(item.image).crossfade(350).allowRgb565(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = rememberBlurHashPainter(item.hash, BlankPainter, width = 32, height = 18),
                modifier = Modifier.fillMaxSize()
            )
            // One gradient does both jobs: keeps the title readable and fades the picture into the page.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0x4007080C), 0.55f to Color(0x9907080C), 1f to Ink)
                )
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint, 1f to Ink)))
        }
    }
}

/** What sits on the shelf: the title, and for Snug a row of things to watch. */
@Composable
private fun ShelfFront(
    owner: TvApp?,
    titleEnd: androidx.compose.ui.unit.Dp,
    shelf: List<ShelfItem>,
    lastOpened: Long,
    heroIndex: Int,
    snugRequester: FocusRequester,
    onCardFocus: (Int) -> Unit,
    onOpenItem: (ShelfItem) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(top = SafeY + 52.dp, bottom = 22.dp), verticalArrangement = Arrangement.Bottom) {
        val hero = shelf.getOrNull(heroIndex)
        if (hero != null) {
            BasicText(
                if (hero.kind == "new") "NEW IN YOUR LIBRARY" else if (hero.progress > 0f) "CONTINUE WATCHING" else "UP NEXT",
                style = HomeType.kicker,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Box(Modifier.padding(end = titleEnd)) { ShelfTitle(hero) }
            if (hero.subtitle.isNotBlank()) {
                BasicText(hero.subtitle, style = HomeType.heroSub, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                shelf.take(6).forEachIndexed { i, item ->
                    ShelfCard(
                        item = item,
                        modifier = Modifier.focusProperties { down = snugRequester },
                        onFocused = { onCardFocus(i) },
                        onClick = { onOpenItem(item) }
                    )
                }
            }
        } else if (owner != null) {
            BasicText(owner.label, style = HomeType.hero, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = titleEnd))
            if (lastOpened > 0L && owner.key != SETTINGS_KEY && owner.key != HIDDEN_KEY) {
                val ago = DateUtils.getRelativeTimeSpanString(lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                BasicText("Last opened ${ago.replaceFirstChar { it.lowercase() }}", style = HomeType.heroSub, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The title's logo when Snug has one for it, else its name in big type. */
@Composable
private fun ShelfTitle(item: ShelfItem) {
    var failed by remember(item.logo) { mutableStateOf(false) }
    if (item.logo == null || failed) {
        BasicText(item.title, style = HomeType.hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    AsyncImage(
        model = item.logo,
        contentDescription = item.title,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        onError = { failed = true },
        modifier = Modifier.height(64.dp).widthIn(max = 420.dp).fillMaxWidth()
    )
}

private val BlankPainter = ColorPainter(TileBlank)

@Composable
private fun ShelfCard(item: ShelfItem, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) FocusScale.Card else 1f, Motion.focus(), label = "card")
    Box(
        modifier
            .width(124.dp)
            .aspectRatio(16f / 9f)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                if (code in SELECT_KEYS) {
                    if (e.type == KeyEventType.KeyUp) onClick()
                    true
                } else false
            }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind { if (focused) drawFocusGlow() }
            .clip(TileShape)
            .border(if (focused) 2.dp else 0.5.dp, if (focused) Color.White else Line, TileShape)
    ) {
        val context = LocalContext.current
        val request = remember(item.image) { ImageRequest.Builder(context).data(item.image).allowRgb565(true).build() }
        AsyncImage(
            model = request,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            placeholder = rememberBlurHashPainter(item.hash, BlankPainter, width = 32, height = 18),
            modifier = Modifier.fillMaxSize()
        )
        if (item.progress > 0f) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color(0x66000000))
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(Accent))
            }
        }
    }
}

@Composable
private fun AppTile(
    app: TvApp,
    moving: Boolean,
    requester: FocusRequester,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    onStartMove: () -> Unit,
    onMove: (Int) -> Unit,
    onEndMove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // OK held down: the release that ends a long press must not also open the app.
    var heldLong by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        when {
            moving -> FocusScale.Lifted
            focused -> FocusScale.Tile
            else -> 1f
        },
        Motion.focus(),
        label = "tile"
    )
    val wiggle = if (moving) {
        val transition = rememberInfiniteTransition(label = "wiggle")
        transition.animateFloat(-1.2f, 1.2f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "wiggle").value
    } else 0f

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { e ->
                    val native = e.nativeKeyEvent
                    val code = native.keyCode
                    when {
                        moving && e.type == KeyEventType.KeyDown && code in ARROW_STEPS -> {
                            onMove(ARROW_STEPS.getValue(code)); true
                        }
                        code in SELECT_KEYS -> {
                            // A fresh press: whatever an earlier long press left behind no longer applies.
                            if (e.type == KeyEventType.KeyDown && native.repeatCount == 0) heldLong = false
                            if (e.type == KeyEventType.KeyDown && native.repeatCount > 0 && !heldLong && !moving) {
                                heldLong = true
                                onOptions()
                            } else if (e.type == KeyEventType.KeyUp) {
                                when {
                                    heldLong -> heldLong = false
                                    moving -> onEndMove()
                                    else -> onClick()
                                }
                            }
                            true
                        }
                        code == AndroidKeyEvent.KEYCODE_MENU -> {
                            if (e.type == KeyEventType.KeyUp) { if (moving) onEndMove() else onStartMove() }
                            true
                        }
                        moving && code == AndroidKeyEvent.KEYCODE_BACK -> {
                            if (e.type == KeyEventType.KeyUp) onEndMove()
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = wiggle }
                // Flat translucent layers instead of a real shadow, which weak TV GPUs redraw every frame of the zoom.
                .drawBehind { if (focused || moving) drawFocusGlow() }
                .clip(TileShape)
                .background(TileBlank)
                .border(if (focused) 2.dp else 0.5.dp, if (focused) Color.White else Line, TileShape),
            contentAlignment = Alignment.Center
        ) {
            when {
                app.banner != null -> Image(app.banner, app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                app.key == SETTINGS_KEY -> Image(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = app.label,
                    colorFilter = ColorFilter.tint(Cream),
                    modifier = Modifier.size(34.dp)
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (app.icon != null) Image(app.icon, null, modifier = Modifier.size(34.dp))
                    BasicText(
                        app.label,
                        style = HomeType.tileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
        }
        // Apple TV names only the focused app.
        val labelAlpha by animateFloatAsState(if (focused && !moving) 1f else 0f, Motion.fade(), label = "label")
        BasicText(
            app.label,
            style = HomeType.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).graphicsLayer { alpha = labelAlpha }
        )
    }
}

@Composable
private fun Clock(modifier: Modifier, style: androidx.compose.ui.text.TextStyle = HomeType.clock) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    BasicText(DateFormat.format(pattern, now).toString(), style = style, modifier = modifier)
}

internal val SELECT_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
    AndroidKeyEvent.KEYCODE_BUTTON_A
)

private val ARROW_STEPS = mapOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT to -1,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT to 1,
    AndroidKeyEvent.KEYCODE_DPAD_UP to -COLUMNS,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN to COLUMNS
)

private val HintPill = Modifier
    .padding(top = SafeY, start = SafeX)
    .clip(RoundedCornerShape(14.dp))
    .background(Color(0xCC14161D))
    .padding(horizontal = 16.dp, vertical = 7.dp)
