package tv.livingroom.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Snug's colours. Snug Home uses the same ones, so the two feel like one product. */
object Palette {
    val Ink = Color(0xFF07080C)
    val Cream = Color(0xFFF7F4EF)
    val Muted = Color(0xB3F7F4EF)
    val Faint = Color(0x80F7F4EF)
    val Glass = Color(0xCC14161D)
    val Line = Color(0x33FFFFFF)
    /** What a poster or tile shows before its picture arrives. */
    val Blank = Color(0xFF1B1D24)
    /** The warm amber of the couch logo: progress bars, selected marks and the main button. */
    val Accent = Color(0xFFF4AE63)
    /** Text and icons on a focused, white surface. */
    val OnFocus = Color(0xFF111111)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold)
)

/**
 * The type scale, for a screen read from the couch. Android TV lays out at 960x540dp, so these
 * are about two sizes up from a phone's: nothing smaller than 12sp.
 */
object TypeScale {
    val display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp, color = Palette.Cream)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp, color = Palette.Cream)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, color = Palette.Cream)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Muted)
    val button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Palette.Muted)
    val kicker = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.5.sp, color = Palette.Muted)
}

/** How much a focused thing grows. One value per kind of thing, so the whole interface moves alike. */
object FocusScale {
    /** Posters, stills, genre and shelf cards. */
    const val Card = 1.08f
    /** Pill buttons and keys. */
    const val Button = 1.05f
    /** App tiles on Snug Home, which sit further apart. */
    const val Tile = 1.10f
    /** An app tile picked up to be moved. */
    const val Lifted = 1.14f
}

/** Timings. Focus is quick so a held arrow never feels behind; bigger changes take a little longer. */
object Motion {
    const val FOCUS_MS = 150
    const val FADE_MS = 220
    const val SLIDE_MS = 320

    fun <T> focus(): AnimationSpec<T> = tween(FOCUS_MS, easing = FastOutSlowInEasing)
    fun <T> fade(): AnimationSpec<T> = tween(FADE_MS, easing = LinearOutSlowInEasing)
    fun <T> slide(): AnimationSpec<T> = tween(SLIDE_MS, easing = FastOutSlowInEasing)
}

// Android TV lays out at 960x540dp; keep content inside the 5% overscan margin.
val SafeX = 48.dp
val SafeY = 27.dp

private val GlowRings = listOf(3.dp to 0.20f, 7.dp to 0.12f, 12.dp to 0.06f)

/**
 * A soft dark halo under a focused card, a little lower than it like a shadow. A few flat translucent
 * layers instead of a real shadow, which weak TV GPUs redraw on every frame of the zoom.
 */
fun DrawScope.drawFocusGlow(corner: Dp = 10.dp) {
    val drop = 5.dp.toPx()
    GlowRings.forEach { (spread, alpha) ->
        val grow = spread.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = alpha),
            topLeft = Offset(-grow, -grow + drop),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(corner.toPx() + grow)
        )
    }
}
