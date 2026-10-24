package tv.livingroom.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * BlurHash (blurha.sh): Jellyfin sends a short string per picture that decodes to a soft, blurred
 * version of it. Painted while the real picture loads, a grid fills with the right colours at once
 * instead of grey boxes.
 */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    // A blurred picture is tiny, so a couple of hundred of them cost well under a megabyte.
    private val cache = LruCache<String, Bitmap>(240)

    /** Decodes [hash] into a [width] x [height] picture, or null when it isn't a valid BlurHash. */
    fun decode(hash: String, width: Int, height: Int): Bitmap? {
        val key = "$hash@${width}x$height"
        cache.get(key)?.let { return it }
        val bitmap = runCatching { decodeUncached(hash, width, height) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodeUncached(hash: String, width: Int, height: Int): Bitmap? {
        if (hash.length < 6) return null
        val size = decode83(hash, 0, 1)
        val numY = size / 9 + 1
        val numX = size % 9 + 1
        if (hash.length != 4 + 2 * numX * numY) return null
        val maxAc = (decode83(hash, 1, 2) + 1) / 166f
        val colors = Array(numX * numY) { i ->
            if (i == 0) decodeDc(decode83(hash, 2, 6)) else decodeAc(decode83(hash, 4 + i * 2, 6 + i * 2), maxAc)
        }
        val cosX = Array(numX) { i -> FloatArray(width) { x -> cos(PI * x * i / width).toFloat() } }
        val cosY = Array(numY) { j -> FloatArray(height) { y -> cos(PI * y * j / height).toFloat() } }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        val basis = cosX[i][x] * cosY[j][y]
                        val color = colors[j * numX + i]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decode83(text: String, from: Int, to: Int): Int {
        var value = 0
        for (i in from until to) {
            val digit = CHARS.indexOf(text[i])
            require(digit >= 0)
            value = value * 83 + digit
        }
        return value
    }

    private fun decodeDc(value: Int): FloatArray =
        floatArrayOf(srgbToLinear(value shr 16), srgbToLinear((value shr 8) and 255), srgbToLinear(value and 255))

    private fun decodeAc(value: Int, maxAc: Float): FloatArray {
        val r = value / (19 * 19)
        val g = (value / 19) % 19
        val b = value % 19
        return floatArrayOf(signedSquare((r - 9) / 9f) * maxAc, signedSquare((g - 9) / 9f) * maxAc, signedSquare((b - 9) / 9f) * maxAc)
    }

    private fun signedSquare(value: Float): Float = (value * value).withSign(value)

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}

/**
 * A painter for [hash] decoded small (it's a blur, so it stretches without harm), falling back to
 * [fallback] when there is no hash. Decoded once per hash and size, then reused.
 */
@Composable
fun rememberBlurHashPainter(hash: String?, fallback: ColorPainter, width: Int = 20, height: Int = 30): Painter =
    remember(hash, width, height) {
        hash?.let { BlurHash.decode(it, width, height) }?.let { BitmapPainter(it.asImageBitmap()) } ?: fallback
    }
