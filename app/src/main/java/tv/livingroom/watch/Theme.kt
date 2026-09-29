package tv.livingroom.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF07080C)
val Cream = Color(0xFFF7F4EF)
val Muted = Color(0xB3F7F4EF)
val Faint = Color(0x80F7F4EF)
val Glass = Color(0xCC14161D)
val Line = Color(0x33FFFFFF)

// Android TV lays out at 960x540dp; keep content inside the 5% overscan margin.
val SafeX = 48.dp
val SafeY = 27.dp
val RailCollapsed = 64.dp
val RailExpanded = 196.dp

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold)
)

object TvType {
    val display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp, color = Cream)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp, color = Cream)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, color = Cream)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = Muted)
    val button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, color = Muted)
    val kicker = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp, color = Muted)
}

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
