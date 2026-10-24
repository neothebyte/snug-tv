package tv.livingroom.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tv.livingroom.ui.Palette
import tv.livingroom.ui.TypeScale

// The shared look lives in :core-ui; these names keep the screens short.
val Ink = Palette.Ink
val Cream = Palette.Cream
val Muted = Palette.Muted
val Faint = Palette.Faint
val Glass = Palette.Glass
val Line = Palette.Line
val Accent = Palette.Accent
val OnFocus = Palette.OnFocus

val SafeX = tv.livingroom.ui.SafeX
val SafeY = tv.livingroom.ui.SafeY
val RailCollapsed = 64.dp
val RailExpanded = 196.dp

val TvType = TypeScale

private val Scheme = darkColorScheme(
    background = Ink,
    surface = Ink,
    primary = Cream,
    onPrimary = Color(0xFF111111),
    onBackground = Cream,
    onSurface = Cream
)

@Composable
fun LivingRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
