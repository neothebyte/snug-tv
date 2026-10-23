package tv.livingroom.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Same palette and type as Snug, so the two feel like one product.
val Ink = Color(0xFF07080C)
val Cream = Color(0xFFF7F4EF)
val Muted = Color(0xB3F7F4EF)
val Line = Color(0x33FFFFFF)
val TileBlank = Color(0xFF1B1D24)

// Android TV lays out at 960x540dp; keep content inside the 5% overscan margin.
val SafeX = 48.dp
val SafeY = 27.dp

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold)
)

object HomeType {
    val hero = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp, color = Cream)
    val heroSub = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp, color = Muted)
    val clock = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp, color = Cream)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, color = Cream)
    val hint = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Muted)
    val tileName = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, color = Cream)
}
