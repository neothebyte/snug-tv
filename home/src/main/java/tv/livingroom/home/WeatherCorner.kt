package tv.livingroom.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val Sun = Color(0xFFFFC65C)
private val Rain = Color(0xFF8CCBFF)
private val Bolt = Color(0xFFFFD54A)
// Solid, not see-through, so a cloud's overlapping circles don't show their seams.
private val FarCloud = Color(0xFF9E9C99)

/** The top right corner: the weather, a hairline, and the time, with the city and sky in small type underneath. */
@Composable
fun StatusCorner(weather: Weather?, clock: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(weather != null, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
                if (weather != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WeatherIcon(weather.sky, weather.isDay, Modifier.size(24.dp))
                        BasicText("${weather.temperature}°", style = HomeType.clock, modifier = Modifier.padding(start = 8.dp))
                        Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(16.dp).background(Line))
                    }
                }
            }
            clock()
        }
        AnimatedVisibility(weather != null, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
            if (weather != null) {
                val words = listOf(weather.city, weather.sky.words(weather.isDay)).filter { it.isNotBlank() }
                BasicText(words.joinToString("  ·  "), style = HomeType.hint, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun Sky.words(isDay: Boolean) = when (this) {
    Sky.Clear -> if (isDay) "Sunny" else "Clear"
    Sky.PartlyCloudy -> "Partly cloudy"
    Sky.Cloudy -> "Cloudy"
    Sky.Fog -> "Fog"
    Sky.Rain -> "Rain"
    Sky.Snow -> "Snow"
    Sky.Storm -> "Thunderstorms"
}

/** Drawn rather than an emoji or a font, so it matches the rest of the home screen on any box. */
@Composable
fun WeatherIcon(sky: Sky, isDay: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // Drawn on a 24 by 24 grid.
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            when (sky) {
                Sky.Clear -> if (isDay) sun(Offset(12f, 12f), 5f) else moon(Offset(12f, 12f), 8f)
                Sky.PartlyCloudy -> {
                    if (isDay) sun(Offset(9f, 9f), 3.6f) else moon(Offset(9f, 8.5f), 5.6f)
                    translate(2.5f, 3f) { cloud(scaleBy = 0.82f) }
                }
                Sky.Cloudy -> {
                    translate(3.5f, -3f) { cloud(scaleBy = 0.7f, color = FarCloud) }
                    translate(0f, 1.5f) { cloud() }
                }
                Sky.Fog -> {
                    translate(0f, -2.5f) { cloud(scaleBy = 0.9f, color = FarCloud) }
                    listOf(17f to 3f..21f, 20.5f to 6f..18f).forEach { (y, x) ->
                        drawLine(Cream, Offset(x.start, y), Offset(x.endInclusive, y), 1.8f, StrokeCap.Round)
                    }
                }
                Sky.Rain -> {
                    translate(0f, -3f) { cloud() }
                    listOf(8f, 12f, 16f).forEach { x ->
                        drawLine(Rain, Offset(x, 16.5f), Offset(x - 1.5f, 21.5f), 1.8f, StrokeCap.Round)
                    }
                }
                Sky.Snow -> {
                    translate(0f, -3f) { cloud() }
                    listOf(Offset(8f, 18f), Offset(12f, 20.5f), Offset(16f, 18f)).forEach { drawCircle(Cream, 1.3f, it) }
                }
                Sky.Storm -> {
                    translate(0f, -3f) { cloud() }
                    val bolt = Path().apply {
                        moveTo(13f, 14.5f); lineTo(9.5f, 19f); lineTo(12f, 19f)
                        lineTo(10.5f, 23f); lineTo(15f, 17.5f); lineTo(12.5f, 17.5f); close()
                    }
                    drawPath(bolt, Bolt)
                }
            }
        }
    }
}

private fun DrawScope.sun(center: Offset, radius: Float) {
    drawCircle(Sun, radius, center)
    for (i in 0 until 8) {
        val angle = Math.PI / 4 * i
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        drawLine(
            Sun,
            Offset(center.x + dx * radius * 1.45f, center.y + dy * radius * 1.45f),
            Offset(center.x + dx * radius * 1.95f, center.y + dy * radius * 1.95f),
            strokeWidth = radius * 0.36f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.moon(center: Offset, radius: Float) {
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply { addOval(Rect(Offset(center.x + radius * 0.55f, center.y - radius * 0.45f), radius * 0.85f)) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Sun)
}

// A cloud on the 24 grid, roughly 4..20 wide and 9..18 tall; one flat colour, so its overlapping parts read as one shape.
private fun DrawScope.cloud(scaleBy: Float = 1f, color: Color = Cream) {
    scale(scaleBy, pivot = Offset(12f, 14f)) {
        drawCircle(color, 3.6f, Offset(9f, 13.5f))
        drawCircle(color, 4.6f, Offset(13.5f, 12f))
        drawRoundRect(color, Offset(4f, 13f), Size(16f, 5.5f), CornerRadius(2.75f))
    }
}
