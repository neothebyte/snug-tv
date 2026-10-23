package tv.livingroom.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** One tile on the home screen: an app, or the box's own Settings. */
data class TvApp(
    val key: String,
    val label: String,
    /** The app's 16:9 TV banner, when it has one. */
    val banner: ImageBitmap?,
    /** The square icon, shown on a plain tile when there's no banner. */
    val icon: ImageBitmap?,
    /** The banner's average colour, darkened: tints the top shelf when the app is focused. */
    val tint: Color
)

const val SNUG = "tv.livingroom.watch"
const val SETTINGS_KEY = "settings"

/** Apps that go first the very first time, before the order has ever been changed. */
private val PREFERRED = listOf(
    SNUG,
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "com.google.android.youtube.tvmusic",
    "com.spotify.tv.android"
)

class AppStore(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Every app the TV would offer, in the saved order, with Settings at the end unless moved. Slow: call off the main thread. */
    fun load(): List<TvApp> {
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(launchable, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val banner = runCatching { info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm) }.getOrNull()
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                val bannerBitmap = banner?.let { render(it, 320, 180) }
                val iconBitmap = if (bannerBitmap == null) icon?.let { render(it, 128, 128) } else null
                TvApp(
                    key = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    banner = bannerBitmap?.asImageBitmap(),
                    icon = iconBitmap?.asImageBitmap(),
                    tint = shelfTint(bannerBitmap ?: iconBitmap)
                )
            }
        val settings = TvApp(SETTINGS_KEY, "Settings", null, null, Color(0xFF1B1D24))
        val byKey = (found + settings).associateBy { it.key }
        return ordered(byKey.keys).mapNotNull { byKey[it] }
    }

    /** Saved order first; new apps after it, preferred ones leading the very first time. Settings stays last until moved. */
    private fun ordered(keys: Set<String>): List<String> {
        val saved = order().filter { it in keys }
        val fresh = keys.filter { it !in saved && it != SETTINGS_KEY }
            .sortedWith(compareBy<String>({ PREFERRED.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { label(it) }))
        val withoutSettings = saved.filter { it != SETTINGS_KEY } + fresh
        val settingsAt = saved.indexOf(SETTINGS_KEY)
        return if (settingsAt < 0) withoutSettings + SETTINGS_KEY
        else withoutSettings.toMutableList().apply { add(settingsAt.coerceAtMost(size), SETTINGS_KEY) }
    }

    private fun label(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase() }.getOrDefault(pkg)

    fun order(): List<String> = prefs.getString(KEY_ORDER, "")!!.split(',').filter { it.isNotBlank() }

    fun saveOrder(keys: List<String>) {
        prefs.edit().putString(KEY_ORDER, keys.joinToString(",")).apply()
    }

    fun launch(app: TvApp) {
        val intent = if (app.key == SETTINGS_KEY) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            pm.getLeanbackLaunchIntentForPackage(app.key) ?: pm.getLaunchIntentForPackage(app.key) ?: return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens one title in Snug. Older Snug versions don't know the request yet, so they just open. */
    fun openInSnug(itemId: String) {
        val open = Intent(OPEN_ITEM).setPackage(SNUG).putExtra(EXTRA_ITEM_ID, itemId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handled = pm.resolveActivity(open, PackageManager.MATCH_DEFAULT_ONLY) != null
        if (handled && runCatching { context.startActivity(open) }.isSuccess) return
        pm.getLeanbackLaunchIntentForPackage(SNUG)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun render(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** The picture's average colour, kept dark enough for white text on top. */
    private fun shelfTint(bitmap: Bitmap?): Color {
        if (bitmap == null) return Color(0xFF1B1D24)
        val one = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(one, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.7f)
        hsv[2] = hsv[2].coerceIn(0.16f, 0.32f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    companion object {
        private const val KEY_ORDER = "order"
        const val OPEN_ITEM = "tv.livingroom.watch.OPEN_ITEM"
        const val EXTRA_ITEM_ID = "itemId"
    }
}
