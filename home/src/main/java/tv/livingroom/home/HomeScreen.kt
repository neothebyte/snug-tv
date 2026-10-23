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

private const val COLUMNS = 5
private val TileGap = 22.dp
// Where the first row of apps sits, under the top shelf.
private val GridTop = 318.dp
// Where a lower row sits once the shelf has slid away.
private val RowParked = 64.dp
private val RowPitch = 132.dp
private val TileShape = RoundedCornerShape(10.dp)

/**
 * The home screen: a top shelf for the focused app, and the apps in rows of five underneath, like Apple TV.
 * Long-press OK (or press Menu) on an app to move it; arrows move it, OK puts it down.
 */
@Composable
fun HomeScreen(
    apps: List<TvApp>,
    shelf: List<ShelfItem>,
    resetSignal: Int,
    onLaunch: (TvApp) -> Unit,
    onOpenItem: (ShelfItem) -> Unit,
    onReorder: (List<TvApp>) -> Unit
) {
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
        animationSpec = tween(320),
        label = "scroll"
    )
    val shelfAlpha by animateFloatAsState(if (focusedRow == 0 || onShelf) 1f else 0f, tween(260), label = "shelf")

    Box(Modifier.fillMaxSize()) {
        ShelfBackdrop(
            owner = owner,
            item = if (snugShelf) shelf.getOrNull(heroIndex) ?: shelf.first() else null,
            modifier = Modifier.graphicsLayer { alpha = shelfAlpha }
        )

        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, scroll.roundToPx()) }
                .padding(horizontal = SafeX)
        ) {
            Box(Modifier.fillMaxWidth().height(GridTop).graphicsLayer { alpha = shelfAlpha }) {
                ShelfFront(
                    owner = owner,
                    shelf = if (snugShelf) shelf else emptyList(),
                    heroIndex = heroIndex,
                    snugRequester = requester(SNUG),
                    onCardFocus = { i -> heroIndex = i; onShelf = true },
                    onOpenItem = onOpenItem
                )
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
                                onClick = { onLaunch(app) },
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

        Clock(Modifier.align(Alignment.TopEnd).padding(top = SafeY, end = SafeX))

        if (moving != null) {
            BasicText(
                "Use the arrows to move it, then press OK",
                style = HomeType.hint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SafeY)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC14161D))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            )
        }
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
    shelf: List<ShelfItem>,
    heroIndex: Int,
    snugRequester: FocusRequester,
    onCardFocus: (Int) -> Unit,
    onOpenItem: (ShelfItem) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(top = SafeY + 52.dp, bottom = 22.dp), verticalArrangement = Arrangement.Bottom) {
        val hero = shelf.getOrNull(heroIndex)
        if (hero != null) {
            BasicText(hero.title, style = HomeType.hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            BasicText(owner.label, style = HomeType.hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShelfCard(item: ShelfItem, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "card")
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
            .drawBehind { if (focused) drawGlow() }
            .clip(TileShape)
            .border(if (focused) 2.dp else 0.5.dp, if (focused) Color.White else Line, TileShape)
    ) {
        val context = LocalContext.current
        val request = remember(item.image) { ImageRequest.Builder(context).data(item.image).allowRgb565(true).build() }
        AsyncImage(model = request, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.progress > 0f) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(Color.White))
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
    onStartMove: () -> Unit,
    onMove: (Int) -> Unit,
    onEndMove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // OK held down: the release that ends a long press must not also open the app.
    var heldLong by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        when {
            moving -> 1.16f
            focused -> 1.12f
            else -> 1f
        },
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
                            if (e.type == KeyEventType.KeyDown && native.repeatCount > 0 && !heldLong && !moving) {
                                heldLong = true
                                onStartMove()
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
                .drawBehind { if (focused || moving) drawGlow() }
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
        val labelAlpha by animateFloatAsState(if (focused && !moving) 1f else 0f, tween(180), label = "label")
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
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    BasicText(DateFormat.format(pattern, now).toString(), style = HomeType.clock, modifier = modifier)
}

private fun DrawScope.drawGlow() {
    val spread = 10.dp.toPx()
    for (i in 3 downTo 1) {
        val grow = spread * i / 3f
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.16f),
            topLeft = Offset(-grow, -grow + spread / 2f),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(10.dp.toPx() + grow)
        )
    }
}

private val SELECT_KEYS = setOf(
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
