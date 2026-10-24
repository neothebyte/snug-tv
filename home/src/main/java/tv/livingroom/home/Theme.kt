package tv.livingroom.home

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import tv.livingroom.ui.Palette
import tv.livingroom.ui.TypeScale

// Same palette and type as Snug (both come from :core-ui), so the two feel like one product.
val Ink = Palette.Ink
val Cream = Palette.Cream
val Muted = Palette.Muted
val Line = Palette.Line
val TileBlank = Palette.Blank
val Accent = Palette.Accent

val SafeX = tv.livingroom.ui.SafeX
val SafeY = tv.livingroom.ui.SafeY

object HomeType {
    val hero: TextStyle = TypeScale.display
    val heroSub: TextStyle = TypeScale.body.copy(fontSize = 15.sp, lineHeight = 20.sp)
    val kicker: TextStyle = TypeScale.kicker
    val clock: TextStyle = TypeScale.title.copy(fontSize = 18.sp, lineHeight = 22.sp)
    val label: TextStyle = TypeScale.button.copy(fontSize = 13.sp, lineHeight = 17.sp, color = Cream)
    val hint: TextStyle = TypeScale.small.copy(fontSize = 13.sp, lineHeight = 17.sp)
    val tileName: TextStyle = TypeScale.button.copy(color = Cream)
    val saverClock: TextStyle = TypeScale.display.copy(fontSize = 64.sp, lineHeight = 70.sp)
}
