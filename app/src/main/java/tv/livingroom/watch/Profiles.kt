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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** A big movie still behind "Who's watching?", darkened at the edges like Netflix's. */
@Composable
private fun PickerBackdrop(vm: AppModel) {
    val pick = vm.pickerShot ?: return
    Box(Modifier.fillMaxSize().background(Ink)) {
        AsyncImage(
            model = pick.first,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(Color(0x1F000000), Color(0xB8000000)), radius = 1500f)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x73000000), 0.22f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xC7000000))
            )
        )
        Text(
            pick.second,
            style = TvType.display.copy(fontSize = 26.sp, lineHeight = 30.sp, color = Color.White),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = SafeX, bottom = SafeY + 8.dp).widthIn(max = 220.dp)
        )
    }
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
    var manage by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(vm.profilesLoaded, vm.profiles.size) {
        if (vm.profilesLoaded) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
    PickerBackdrop(vm)
    Box(Modifier.fillMaxSize()) {
        Text(
            "Snug",
            style = TvType.headline.copy(color = Color(0xFFFFCF8A), fontSize = 26.sp),
            modifier = Modifier.padding(start = SafeX, top = SafeY)
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = SafeX, vertical = SafeY),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Who's watching?", style = TvType.display.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White))
            Spacer(Modifier.height(14.dp))
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
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
                        name = "Add Profile",
                        imageUrl = null,
                        icon = R.drawable.ic_add,
                        requester = if (vm.profiles.isEmpty()) first else null
                    ) { askUser = true }
                }
            }
            Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                if (vm.busy) {
                    CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else if (vm.message.isNotBlank()) {
                    Text(vm.message, style = TvType.body, color = Cream)
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinePill("Manage Profiles", R.drawable.ic_edit) { manage = true }
        }
    }
    if (manage) {
        ManageDialog(
            vm,
            onDismiss = { manage = false },
            onAdd = {
                manage = false
                askUser = true
            }
        )
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
    val shape = RoundedCornerShape(size * 0.07f)
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
                .background(if (icon != null) Color.White.copy(alpha = if (focused) 0.24f else 0.12f) else tileColor(name))
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier),
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
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)),
                    modifier = Modifier.size(size * 0.36f)
                )
                else -> Text(
                    name.take(1).uppercase(),
                    style = TvType.display.copy(fontSize = (size.value * 0.44f).sp, lineHeight = (size.value * 0.5f).sp, color = Color.White)
                )
            }
            if (locked) {
                Image(
                    painterResource(R.drawable.ic_lock),
                    "Locked",
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(13.dp)
                )
            }
        }
        if (focusable) {
            Spacer(Modifier.height(12.dp))
            Text(name, style = TvType.title, color = if (focused) Color.White else Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Profile Lock is currently on": four boxes for the PIN (the profile's Jellyfin password), like Netflix. */
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
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2C1714), Color(0xFF1C0F0D), Color(0xFF120908)), radius = 1500f))
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
            }
    ) {
        CloseButton(Modifier.align(Alignment.TopEnd).padding(end = SafeX - 12.dp, top = SafeY - 6.dp)) { vm.closeLock() }
        Column(
            Modifier.fillMaxSize().padding(horizontal = SafeX, vertical = SafeY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.8f))
            Text("Profile Lock is currently on.", style = TvType.small.copy(color = Color.White.copy(alpha = 0.7f)))
            Spacer(Modifier.height(8.dp))
            Text(
                if (lock.password) "Enter your password to access this profile." else "Enter your pin to access this profile.",
                style = TvType.display.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            if (lock.password) {
                TvButton("Type password", primary = true, icon = R.drawable.ic_keyboard, focusRequester = typeButton) { typing = true }
            } else {
                Row(Modifier.offset(x = shake.value.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until 4) {
                        val filled = i < lock.digits.length
                        val current = i == lock.digits.length && !lock.busy
                        val edge = when {
                            lock.error.isNotBlank() && lock.digits.isEmpty() -> Color(0xFFFF8A8A)
                            current -> Color.White
                            else -> Color.White.copy(alpha = 0.4f)
                        }
                        Box(
                            Modifier
                                .size(width = 46.dp, height = 58.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFF130A09))
                                .border(if (current) 1.5.dp else 1.dp, edge, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (filled) Box(Modifier.size(10.dp).clip(CircleShape).background(Cream))
                        }
                    }
                }
            }
            Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                if (lock.busy) {
                    CircularProgressIndicator(color = Cream, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else if (lock.error.isNotBlank()) {
                    Text(lock.error, style = TvType.body, color = Color(0xFFFFB4B4))
                }
            }
            // Most TV remotes have no number buttons, so the digits are on screen too.
            if (!lock.password) Keypad(vm, firstKey)
            Spacer(Modifier.weight(1f))
            TextLink(if (lock.password) "Forgot password?" else "Forgot Pin?") { vm.showLockHelp() }
            if (lock.help) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ask whoever looks after the Jellyfin server to change it: Dashboard, Users, ${profile.name}, Password. A PIN is just a 4-digit password.",
                    style = TvType.small,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 460.dp)
                )
                Spacer(Modifier.height(4.dp))
                if (lock.password) {
                    TextLink("Use PIN instead") { vm.lockWithPassword(false) }
                } else {
                    TextLink("Use password instead") { vm.lockWithPassword(true) }
                }
            }
            Spacer(Modifier.weight(0.35f))
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

/** Every option that isn't a profile tile: adding people, Quick Connect and the server. */
@Composable
private fun ManageDialog(vm: AppModel, onDismiss: () -> Unit, onAdd: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(60)
            runCatching { first.requestFocus() }
        }
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2181A21))
                .border(1.dp, Line, RoundedCornerShape(22.dp))
                .padding(24.dp)
        ) {
            Text("Manage Profiles", style = TvType.headline)
            Spacer(Modifier.height(8.dp))
            Text(
                "Each tile is a Jellyfin user. To add someone, change a picture or set a PIN (a 4-digit password), open Dashboard, Users in Jellyfin with an admin account.",
                style = TvType.body
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Sign in with another account", Modifier.fillMaxWidth(), icon = R.drawable.ic_add, focusRequester = first) { onAdd() }
                TvButton("Quick Connect", Modifier.fillMaxWidth(), icon = R.drawable.ic_phone) {
                    onDismiss()
                    vm.startQuickConnect()
                }
                TvButton("Change server", Modifier.fillMaxWidth(), icon = R.drawable.ic_server) {
                    onDismiss()
                    vm.changeServer()
                }
                TvButton("Done", Modifier.fillMaxWidth()) { onDismiss() }
            }
            Spacer(Modifier.height(12.dp))
            val address = vm.store.server.substringAfter("://")
            Text(
                "Server  " + if (vm.store.serverName.isBlank()) address else "${vm.store.serverName} · $address",
                style = TvType.small
            )
        }
    }
}

@Composable
private fun OutlinePill(label: String, icon: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val content = if (focused) Color(0xFF111111) else Color.White
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Color(0x26000000))
            .border(1.5.dp, if (focused) Color.White else Color.White.copy(alpha = 0.75f), RoundedCornerShape(50))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(painterResource(icon), null, colorFilter = ColorFilter.tint(content), modifier = Modifier.size(15.dp))
        Text(label, style = TvType.button, color = content)
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label,
        style = TvType.title,
        color = if (focused) Color(0xFF111111) else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun CloseButton(modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_close), "Back to profiles", colorFilter = ColorFilter.tint(if (focused) Color(0xFF111111) else Color.White), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Keypad(vm: AppModel, firstKey: FocusRequester) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (key in "1234567890") {
            PadKey(key.toString(), requester = if (key == '1') firstKey else null) { vm.pinDigit(key) }
        }
        PadKey("", R.drawable.ic_backspace) { vm.pinDelete() }
    }
}

@Composable
private fun PadKey(label: String, icon: Int? = null, requester: FocusRequester? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val content = if (focused) Color(0xFF111111) else Cream
    Box(
        Modifier
            .size(width = 38.dp, height = 38.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(painterResource(icon), "Delete", colorFilter = ColorFilter.tint(content), modifier = Modifier.size(18.dp))
        } else {
            Text(label, style = TvType.headline.copy(fontSize = 16.sp, lineHeight = 20.sp), color = content)
        }
    }
}
