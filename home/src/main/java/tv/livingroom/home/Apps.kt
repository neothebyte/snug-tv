package tv.livingroom.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One tile on the home screen: an app, the box's own Settings, or the way back to hidden apps. */
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
const val HIDDEN_KEY = "hidden"

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
    // Each app's banner as drawn last time, so the next start doesn't have to open every app's resources.
    private val pictures = File(context.cacheDir, "tiles").apply { mkdirs() }
    private val snapshot = File(context.filesDir, "apps.json")

    /**
     * The tiles as they were last time, from disk: shown on the box's first frame while [load] checks
     * what's installed. Empty on the very first start.
     */
    fun cached(): List<TvApp> = runCatching {
        val array = JSONArray(snapshot.readText())
        List(array.length()) { i ->
            val entry = array.getJSONObject(i)
            val key = entry.getString("key")
            tile(key, entry.optString("label"), Color(entry.optInt("tint", FALLBACK_TINT.toArgb())))
        }
    }.getOrDefault(emptyList())

    /** Every app the TV would offer, in the saved order, with Settings at the end unless moved. Slow: call off the main thread. */
    fun load(): List<TvApp> {
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val hidden = hidden()
        // Some boxes list their Settings app as a TV app too; it's already the Settings tile.
        val settingsApp = runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName }.getOrNull()
        val found = pm.queryIntentActivities(launchable, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName && it.packageName != settingsApp }
            .distinctBy { it.packageName }
            .filter { it.packageName !in hidden }
            .map { info ->
                val label = info.loadLabel(pm).toString()
                val version = runCatching { pm.getPackageInfo(info.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                // Drawn once per app version; after that it's a small picture read back from disk.
                cachedTile(info.packageName, label, version) ?: run {
                    val banner = runCatching { info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm) }.getOrNull()
                    val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                    val bannerBitmap = banner?.let { render(it, 320, 180) }
                    val iconBitmap = if (bannerBitmap == null) icon?.let { render(it, 128, 128) } else null
                    val tint = shelfTint(bannerBitmap ?: iconBitmap)
                    keepTile(info.packageName, version, tint, bannerBitmap, iconBitmap)
                    TvApp(info.packageName, label, bannerBitmap?.asImageBitmap(), iconBitmap?.asImageBitmap(), tint)
                }
            }
        val extras = buildList {
            add(TvApp(SETTINGS_KEY, "Settings", null, null, FALLBACK_TINT))
            if (hidden.isNotEmpty()) add(TvApp(HIDDEN_KEY, "Hidden apps", null, null, FALLBACK_TINT))
        }
        val byKey = (found + extras).associateBy { it.key }
        val apps = ordered(byKey.keys).mapNotNull { byKey[it] }
        saveSnapshot(apps)
        return apps
    }

    private fun tileFile(pkg: String, kind: String) = File(pictures, "$pkg.$kind.png")

    private fun cachedTile(pkg: String, label: String, version: Long): TvApp? {
        if (prefs.getLong("v:$pkg", -1L) != version) return null
        val tint = Color(prefs.getInt("tint:$pkg", FALLBACK_TINT.toArgb()))
        val banner = tileFile(pkg, "banner").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val icon = if (banner == null) tileFile(pkg, "icon").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } else null
        if (banner == null && icon == null) return null
        return TvApp(pkg, label, banner?.asImageBitmap(), icon?.asImageBitmap(), tint)
    }

    private fun keepTile(pkg: String, version: Long, tint: Color, banner: Bitmap?, icon: Bitmap?) {
        runCatching {
            banner?.let { tileFile(pkg, "banner").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) } }
            icon?.let { tileFile(pkg, "icon").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) } }
            prefs.edit().putLong("v:$pkg", version).putInt("tint:$pkg", tint.toArgb()).apply()
        }
    }

    /** A tile from what's on disk, for [cached]: no package manager calls at all. */
    private fun tile(key: String, label: String, tint: Color): TvApp {
        val banner = tileFile(key, "banner").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val icon = if (banner == null) tileFile(key, "icon").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } else null
        return TvApp(key, label, banner?.asImageBitmap(), icon?.asImageBitmap(), tint)
    }

    private fun saveSnapshot(apps: List<TvApp>) {
        runCatching {
            val array = JSONArray()
            apps.forEach { array.put(JSONObject().put("key", it.key).put("label", it.label).put("tint", it.tint.toArgb())) }
            snapshot.writeText(array.toString())
        }
    }

    /** Saved order first; new apps after it, preferred ones leading the very first time. Settings stays last until moved. */
    private fun ordered(keys: Set<String>): List<String> {
        val saved = order().filter { it in keys }
        val fresh = keys.filter { it !in saved && it != SETTINGS_KEY && it != HIDDEN_KEY }
            .sortedWith(compareBy<String>({ PREFERRED.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { label(it) }))
        val withoutSettings = saved.filter { it != SETTINGS_KEY && it != HIDDEN_KEY } + fresh
        val settingsAt = saved.indexOf(SETTINGS_KEY)
        val withSettings = if (settingsAt < 0) withoutSettings + SETTINGS_KEY
        else withoutSettings.toMutableList().apply { add(settingsAt.coerceAtMost(size), SETTINGS_KEY) }
        // The way back to hidden apps always sits at the very end.
        return if (HIDDEN_KEY in keys) withSettings + HIDDEN_KEY else withSettings
    }

    private fun label(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase() }.getOrDefault(pkg)

    fun order(): List<String> = prefs.getString(KEY_ORDER, "")!!.split(',').filter { it.isNotBlank() }

    fun saveOrder(keys: List<String>) {
        prefs.edit().putString(KEY_ORDER, keys.filter { it != HIDDEN_KEY }.joinToString(",")).apply()
    }

    fun hidden(): Set<String> = prefs.getString(KEY_HIDDEN, "")!!.split(',').filter { it.isNotBlank() }.toSet()

    fun hide(pkg: String) {
        if (pkg == SETTINGS_KEY || pkg == HIDDEN_KEY) return
        prefs.edit().putString(KEY_HIDDEN, (hidden() + pkg).joinToString(",")).apply()
    }

    fun unhide(pkg: String) {
        prefs.edit().putString(KEY_HIDDEN, (hidden() - pkg).joinToString(",")).apply()
    }

    /** Hidden apps with their names, for the list that brings them back. */
    fun hiddenApps(): List<Pair<String, String>> =
        hidden().map { pkg -> pkg to runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            .sortedBy { it.second.lowercase() }

    /** When [pkg] was last opened from here, or 0. Shown on the shelf of apps that don't offer one. */
    fun lastOpened(pkg: String): Long = prefs.getLong("opened:$pkg", 0L)

    fun launch(app: TvApp) {
        val intent = if (app.key == SETTINGS_KEY) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            pm.getLeanbackLaunchIntentForPackage(app.key) ?: pm.getLaunchIntentForPackage(app.key) ?: return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) {
            prefs.edit().putLong("opened:${app.key}", System.currentTimeMillis()).apply()
        }
    }

    /** Opens the box's own page for [pkg], where it can be updated, cleared or uninstalled. */
    fun openAppInfo(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        if (bitmap == null) return FALLBACK_TINT
        val one = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(one, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.7f)
        hsv[2] = hsv[2].coerceIn(0.16f, 0.32f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    companion object {
        private const val KEY_ORDER = "order"
        private const val KEY_HIDDEN = "hidden"
        private val FALLBACK_TINT = Color(0xFF1B1D24)
        const val OPEN_ITEM = "tv.livingroom.watch.OPEN_ITEM"
        const val EXTRA_ITEM_ID = "itemId"
    }
}
