package tv.livingroom.watch

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val TileColors = listOf(
    Color(0xFFE9A948), Color(0xFFE0705F), Color(0xFF3FA796), Color(0xFF5B84E0),
    Color(0xFF9A77DE), Color(0xFFD9709F), Color(0xFF6AA84F)
)

/** Each profile keeps the same colour everywhere, picked from its name. */
fun tileColor(name: String): Color {
    var hash = 0
    for (ch in name) hash = hash * 31 + ch.code
    return TileColors[Math.floorMod(hash, TileColors.size)]
}

/** Darkens the backdrop behind the login screens so the tiles stand out. */
@Composable
private fun LoginVeil(strong: Boolean) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(Color(if (strong) 0xD907080C else 0x8007080C), Color(0xF207080C)),
                radius = 1400f
            )
        )
    )
}

@Composable
fun ProfileStep(vm: AppModel) {
    val lock = vm.lock
    if (lock != null) {
        LockScreen(vm, lock)
        return
    }
    var passwordFor by remember { mutableStateOf<String?>(null) }
    var askUser by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(vm.profilesLoaded, vm.profiles.size) {
        if (vm.profilesLoaded) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
    LoginVeil(strong = false)
    Box(Modifier.fillMaxSize()) {
        Text(
            "Snug",
            style = TvType.headline.copy(color = Color(0xFFFFCF8A)),
            modifier = Modifier.padding(start = SafeX, top = SafeY)
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = SafeX, vertical = SafeY),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Who's watching?", style = TvType.display.copy(fontSize = 40.sp, lineHeight = 46.sp))
            Spacer(Modifier.height(26.dp))
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp)
            ) {
                items(vm.profiles, key = { it.id }) { profile ->
                    UserTile(
                        name = profile.name,
                        imageUrl = if (profile.hasImage) vm.api.userImageUrl(profile.id) else null,
                        locked = profile.hasPassword,
                        working = vm.entering == profile.id,
                        requester = if (profile == vm.profiles.first()) first else null
                    ) { vm.pickProfile(profile) }
                }
                item {
                    UserTile(
                        name = "Add profile",
                        imageUrl = null,
                        icon = R.drawable.ic_add,
                        requester = if (vm.profiles.isEmpty()) first else null
                    ) { askUser = true }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (vm.busy) {
                    CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else if (vm.message.isNotBlank()) {
                    Text(vm.message, style = TvType.body, color = Cream)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Quick Connect", icon = R.drawable.ic_phone) { vm.startQuickConnect() }
                TvButton("Change server", icon = R.drawable.ic_server) { vm.changeServer() }
            }
            Spacer(Modifier.height(10.dp))
            val address = vm.store.server.substringAfter("://")
            Text(
                if (vm.store.serverName.isBlank()) address else "${vm.store.serverName} · $address",
                style = TvType.small
            )
        }
    }
    passwordFor?.let { name ->
        TextEntryDialog(
            title = name,
            initial = "",
            hint = "Password",
            password = true,
            onDismiss = { passwordFor = null }
        ) { password ->
            passwordFor = null
            vm.signIn(name, password)
        }
    }
    if (askUser) {
        TextEntryDialog(
            "Add a profile",
            manualName,
            "Jellyfin username",
            password = false,
            note = "Sign in with any Jellyfin account. It gets its own tile here afterwards.",
            onDismiss = { askUser = false }
        ) { name ->
            askUser = false
            manualName = name.trim()
            if (manualName.isNotEmpty()) passwordFor = manualName
        }
    }
}

@Composable
private fun UserTile(
    name: String,
    imageUrl: String?,
    icon: Int? = null,
    locked: Boolean = false,
    working: Boolean = false,
    size: Dp = 112.dp,
    focusable: Boolean = true,
    requester: FocusRequester? = null,
    onClick: () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "tile")
    val pulse by animateFloatAsState(if (working) 0.55f else 1f, label = "working")
    val shape = RoundedCornerShape(size * 0.12f)
    Column(
        Modifier
            .width(size + 16.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .then(if (focusable) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = pulse }
                .clip(shape)
                .background(if (icon != null) Color.White.copy(alpha = if (focused) 0.18f else 0.08f) else tileColor(name))
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.12f), shape),
            contentAlignment = Alignment.Center
        ) {
            when {
                imageUrl != null -> AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                icon != null -> Image(
                    painterResource(icon),
                    null,
                    colorFilter = ColorFilter.tint(if (focused) Cream else Muted),
                    modifier = Modifier.size(size * 0.36f)
                )
                else -> Text(
                    name.take(1).uppercase(),
                    style = TvType.display.copy(fontSize = (size.value * 0.44f).sp, lineHeight = (size.value * 0.5f).sp, color = Color.White)
                )
            }
            if (locked) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0x52000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.ic_lock), "Locked", colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(14.dp))
                }
            }
        }
        if (focusable) {
            Spacer(Modifier.height(12.dp))
            Text(name, style = TvType.title, color = if (focused) Cream else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Profile lock is on": a 4-digit PIN (the profile's Jellyfin password), or a longer password. */
@Composable
private fun LockScreen(vm: AppModel, lock: LockState) {
    BackHandler { vm.closeLock() }
    val profile = lock.profile
    val firstKey = remember { FocusRequester() }
    val typeButton = remember { FocusRequester() }
    var typing by remember(lock.password) { mutableStateOf(lock.password) }
    LaunchedEffect(lock.password) {
        delay(60)
        runCatching { if (lock.password) typeButton.requestFocus() else firstKey.requestFocus() }
    }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(lock.shakes) {
        if (lock.shakes == 0) return@LaunchedEffect
        shake.animateTo(0f, keyframes {
            durationMillis = 360
            -14f at 60
            14f at 140
            -10f at 220
            8f at 290
        })
    }
    LoginVeil(strong = true)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SafeX, vertical = SafeY)
            .onPreviewKeyEvent { event ->
                // Number buttons on the remote type straight into the boxes.
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN || lock.password) return@onPreviewKeyEvent false
                when (val code = native.keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        vm.pinDigit('0' + (code - KeyEvent.KEYCODE_0)); true
                    }
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                        vm.pinDigit('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        vm.pinDelete(); true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UserTile(profile.name, if (profile.hasImage) vm.api.userImageUrl(profile.id) else null, size = 64.dp, focusable = false)
        Spacer(Modifier.height(6.dp))
        Text(profile.name, style = TvType.title)
        Spacer(Modifier.height(12.dp))
        Text("Profile lock is on.", style = TvType.small)
        Spacer(Modifier.height(4.dp))
        Text(
            if (lock.password) "Enter the password for this profile." else "Enter your PIN to open this profile.",
            style = TvType.display.copy(fontSize = 28.sp, lineHeight = 34.sp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        if (lock.password) {
            TvButton("Type password", primary = true, icon = R.drawable.ic_keyboard, focusRequester = typeButton) { typing = true }
        } else {
            Row(
                Modifier.offset(x = shake.value.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (i in 0 until 4) {
                    val filled = i < lock.digits.length
                    val current = i == lock.digits.length && !lock.busy
                    val edge = when {
                        lock.error.isNotBlank() && lock.digits.isEmpty() -> Color(0xFFFF8A8A)
                        current -> Color.White
                        else -> Color.White.copy(alpha = 0.35f)
                    }
                    Box(
                        Modifier
                            .size(width = 44.dp, height = 54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x59000000))
                            .border(1.5.dp, edge, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (filled) Box(Modifier.size(12.dp).clip(CircleShape).background(Cream))
                    }
                }
            }
        }
        Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
            if (lock.busy) {
                CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else if (lock.error.isNotBlank()) {
                Text(lock.error, style = TvType.body, color = Color(0xFFFFB4B4))
            }
        }
        if (!lock.password) {
            Keypad(vm, firstKey)
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lock.password) {
                TvButton("Use PIN instead") { vm.lockWithPassword(false) }
                TvButton("Forgot password?") { vm.showLockHelp() }
            } else {
                TvButton("Use password instead") { vm.lockWithPassword(true) }
                TvButton("Forgot PIN?") { vm.showLockHelp() }
            }
        }
        if (lock.help) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Only someone with an admin account can change it: in Jellyfin, open Dashboard, Users, ${profile.name}, Password. A PIN is just a 4-digit password.",
                style = TvType.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp)
            )
        }
    }
    if (typing && lock.password && !lock.busy) {
        TextEntryDialog(
            title = profile.name,
            initial = "",
            hint = "Password",
            password = true,
            onDismiss = { typing = false }
        ) { password ->
            typing = false
            vm.unlock(password)
        }
    }
}

@Composable
private fun Keypad(vm: AppModel, firstKey: FocusRequester) {
    val rows = listOf("123", "456", "789", " 0<")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in row) {
                    when (key) {
                        ' ' -> Spacer(Modifier.size(width = 60.dp, height = 40.dp))
                        '<' -> PadKey("", R.drawable.ic_backspace) { vm.pinDelete() }
                        else -> PadKey(key.toString(), requester = if (key == '1') firstKey else null) { vm.pinDigit(key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, icon: Int? = null, requester: FocusRequester? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val content = if (focused) Color(0xFF111111) else Cream
    Box(
        Modifier
            .size(width = 60.dp, height = 40.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.09f))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(painterResource(icon), "Delete", colorFilter = ColorFilter.tint(content), modifier = Modifier.size(20.dp))
        } else {
            Text(label, style = TvType.headline.copy(fontSize = 18.sp, lineHeight = 22.sp), color = content)
        }
    }
}
