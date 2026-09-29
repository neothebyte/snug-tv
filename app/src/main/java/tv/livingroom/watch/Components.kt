package tv.livingroom.watch

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun FullBackdrop(url: String?, strong: Boolean = false) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url) { ImageRequest.Builder(context).data(url).crossfade(240).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(Color(0x40FFB05C), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(260f, 120f),
                        radius = 1100f
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(Color(0x335C8EFF), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(1700f, 950f),
                        radius = 1000f
                    )
                )
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(if (strong) 0xF207080C else 0xE607080C),
                    0.45f to Color(if (strong) 0xB307080C else 0x8007080C),
                    1f to Color(0x2607080C)
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x4007080C),
                    0.4f to Color(0x0007080C),
                    1f to Color(0xF507080C)
                )
            )
        )
    }
}

@Composable
fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    primary: Boolean = false,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "button")
    val background = when {
        focused -> Color.White
        primary -> Color.White.copy(alpha = 0.22f)
        selected -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.09f)
    }
    val content = if (focused) Color(0xFF111111) else Cream
    Row(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(background)
            .border(1.dp, if (focused) Color.White else Line, RoundedCornerShape(17.dp))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (icon != null) {
            Image(
                painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(content),
                modifier = Modifier.size(16.dp)
            )
        }
        if (selected && icon == null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (focused) Color(0xFF111111) else Cream))
        }
        if (label.isNotEmpty()) {
            Text(label, style = TvType.button, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterCard(
    movie: Movie,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp? = 108.dp,
    showTitle: Boolean = true,
    autoFocus: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "poster")
    val requester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { requester.requestFocus() }
    }
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable(interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                // A few flat translucent layers instead of a real 18dp shadow, which weak TV GPUs
                // redraw on every frame of the zoom. Only redraws, never re-lays out, on focus.
                .drawBehind { if (focused) drawGlow() }
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1B1D24))
                .border(if (focused) 2.dp else 0.5.dp, if (focused) Color.White else Line, RoundedCornerShape(10.dp))
        ) {
            if (imageUrl != null) {
                val context = LocalContext.current
                // Posters have no transparency, so 16-bit pixels halve their memory and upload cost.
                val request = remember(imageUrl) { ImageRequest.Builder(context).data(imageUrl).allowRgb565(true).build() }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    movie.cardTitle,
                    style = TvType.small,
                    color = Cream,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp)
                )
            }
            if (movie.progress > 0.02f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(movie.progress).background(Color.White))
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (movie.favorite) Badge("♥")
                if (movie.played) Badge("✓")
            }
        }
        if (showTitle) {
            // Fixed height, and the title slides down with a layer move, so focusing a poster
            // doesn't make the whole grid row measure itself again.
            Spacer(Modifier.height(6.dp))
            Text(
                movie.cardTitle,
                style = TvType.small,
                color = if (focused) Cream else Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { translationY = if (focused) 4.dp.toPx() else 0f }
            )
        }
    }
}

private val GlowRings = listOf(3.dp to 0.20f, 7.dp to 0.12f, 12.dp to 0.06f)

/** A soft dark halo under a focused poster, a little lower than it like a shadow. */
private fun DrawScope.drawGlow() {
    val drop = 5.dp.toPx()
    GlowRings.forEach { (spread, alpha) ->
        val grow = spread.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = alpha),
            topLeft = Offset(-grow, -grow + drop),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(10.dp.toPx() + grow)
        )
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = TvType.small, color = Cream)
    }
}

@Composable
fun Chip(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = TvType.small,
        color = Cream,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(0.75.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun MetaLine(movie: Movie, showEnds: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val bits = buildList {
            movie.year?.let { add(it.toString()) }
            runtimeLabel(movie.runtimeTicks).takeIf { it.isNotBlank() }?.let { add(it) }
            movie.communityRating?.let { add("★ %.1f".format(it)) }
        }
        Text(bits.joinToString("  ·  "), style = TvType.body, color = Cream)
        if (movie.officialRating.isNotBlank()) Chip(movie.officialRating)
        if (showEnds && movie.runtimeTicks > 0) {
            val left = (movie.runtimeTicks - movie.positionTicks).coerceAtLeast(0L)
            Text("Ends at ${endsLabel(left)}", style = TvType.body)
        }
    }
}

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TvType.kicker, modifier = modifier.padding(bottom = 6.dp))
}

@Composable
fun RailItem(
    label: String,
    icon: Int,
    selected: Boolean,
    expanded: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val tint = when {
        focused -> Color(0xFF111111)
        selected -> Cream
        else -> Faint
    }
    Row(
        Modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(painterResource(icon), contentDescription = label, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(20.dp))
        if (expanded) Text(label, style = TvType.button, color = tint, maxLines = 1)
    }
}

@Composable
fun SettingRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .width(520.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = TvType.button, color = if (focused) Color(0xFF111111) else Cream, modifier = Modifier.weight(1f))
        Text(value, style = TvType.body, color = if (focused) Color(0xFF333333) else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("  ›", style = TvType.button, color = if (focused) Color(0xFF111111) else Faint)
    }
}

@Composable
fun TextEntryDialog(
    title: String,
    initial: String,
    hint: String,
    password: Boolean,
    note: String = "",
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2181A21))
                .border(1.dp, Line, RoundedCornerShape(22.dp))
                .padding(24.dp)
        ) {
            Text(title, style = TvType.headline)
            if (note.isNotBlank()) Text(note, style = TvType.body, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                factory = { context ->
                    EditText(context).apply {
                        setText(initial)
                        setSelection(text.length)
                        this.hint = hint
                        setHintTextColor(android.graphics.Color.parseColor("#80F7F4EF"))
                        setTextColor(android.graphics.Color.parseColor("#F7F4EF"))
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        textSize = 15f
                        isSingleLine = true
                        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        inputType = if (password) {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        }
                        setOnEditorActionListener { view, action, event ->
                            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                            if (action == EditorInfo.IME_ACTION_DONE || enter) {
                                onDone(view.text.toString())
                                true
                            } else false
                        }
                        post {
                            requestFocus()
                            val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            Text("Press Done on the keyboard to continue, or Back to cancel.", style = TvType.small)
        }
    }
}

@Composable
fun PickerDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Column(
            Modifier
                .width(360.dp)
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2181A21))
                .border(1.dp, Line, RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Text(title, style = TvType.headline, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options) { index, option ->
                    TvButton(
                        option,
                        Modifier.fillMaxWidth(),
                        selected = index == selected,
                        focusRequester = if (index == selected.coerceAtLeast(0)) first else null
                    ) { onPick(index) }
                }
            }
        }
    }
}
