package tv.livingroom.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("livingroom-tv", Context.MODE_PRIVATE)

    /** The chosen server's address, or blank before one has been picked. */
    var server: String
        get() {
            val saved = prefs.getString(KEY_SERVER, null)
            if (saved != null) return saved
            // Older versions assumed this address until another was typed. Keep TVs that are already
            // signed in that way working; everyone else picks a server on first launch.
            return if (prefs.contains(KEY_TOKEN)) LEGACY_SERVER else ""
        }
        set(value) {
            prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()
        }

    var serverName: String
        get() = prefs.getString(KEY_SERVER_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_NAME, value).apply()
        }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_USER_ID, value).apply()
        }

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value).apply()
        }

    var controlsSeconds: Int
        get() = prefs.getInt(KEY_CONTROLS, 3).coerceIn(2, 8)
        set(value) {
            prefs.edit().putInt(KEY_CONTROLS, value).apply()
        }

    var cueScale: Float
        get() = prefs.getFloat(KEY_CUE, 1.35f)
        set(value) {
            prefs.edit().putFloat(KEY_CUE, value).apply()
        }

    var subtitleLanguage: String
        get() = prefs.getString(KEY_LANG, "eng") ?: "eng"
        set(value) {
            prefs.edit().putString(KEY_LANG, value).apply()
        }

    var autoSubtitles: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO, value).apply()
        }

    var matchFrameRate: Boolean
        get() = prefs.getBoolean(KEY_FRAME_RATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FRAME_RATE, value).apply()
        }

    /** How far before the saved spot a resumed title starts, so the line that was cut off is heard again. */
    var resumeBackSeconds: Int
        get() = prefs.getInt(KEY_RESUME_BACK, 5).coerceIn(0, 30)
        set(value) {
            prefs.edit().putInt(KEY_RESUME_BACK, value).apply()
        }

    /** Episodes in a row with nobody touching the remote before asking "Are you still watching?"; 0 never asks. */
    var stillWatchingAfter: Int
        get() = prefs.getInt(KEY_STILL_WATCHING, 3).coerceIn(0, 10)
        set(value) {
            prefs.edit().putInt(KEY_STILL_WATCHING, value).apply()
        }

    var themeMusic: Boolean
        get() = prefs.getBoolean(KEY_THEME_MUSIC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_THEME_MUSIC, value).apply()
        }

    /** Minutes idle on a menu before the Snug screensaver; 0 leaves it to the TV's own. */
    var screensaverMinutes: Int
        get() = prefs.getInt(KEY_SCREENSAVER, 5).coerceIn(0, 60)
        set(value) {
            prefs.edit().putInt(KEY_SCREENSAVER, value).apply()
        }

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE, created).apply()
            return created
        }

    val signedIn: Boolean
        get() = token != null && userId != null

    fun saveSession(accessToken: String, id: String, name: String) {
        val saved = savedSessions().put(id, JSONObject().put("token", accessToken).put("name", name))
        prefs.edit()
            .putString(KEY_TOKEN, accessToken)
            .putString(KEY_USER_ID, id)
            .putString(KEY_USER_NAME, name)
            .putString(sessionsKey(), saved.toString())
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .apply()
    }

    /**
     * Profiles that have signed in on this TV keep their session, per server, so switching between
     * them needs no password. Signing out forgets one; changing server forgets them all.
     */
    private fun savedSessions(): JSONObject =
        runCatching { JSONObject(prefs.getString(sessionsKey(), null) ?: "{}") }.getOrDefault(JSONObject())

    private fun sessionsKey(): String = "$KEY_SESSIONS:$server"

    fun hasSavedSession(userId: String): Boolean = userId.isNotBlank() && savedSessions().has(userId)

    /** Makes [userId]'s saved session the current one. False when there isn't one. */
    fun useSavedSession(userId: String): Boolean {
        val saved = savedSessions().optJSONObject(userId) ?: return false
        val token = saved.optString("token").takeIf { it.isNotBlank() } ?: return false
        saveSession(token, userId, saved.optString("name"))
        return true
    }

    fun forgetSession(userId: String) {
        val saved = savedSessions()
        saved.remove(userId)
        prefs.edit().putString(sessionsKey(), saved.toString()).apply()
    }

    fun forgetAllSessions() {
        prefs.edit().remove(sessionsKey()).apply()
    }

    /** The last few searches that led somewhere, newest first, for this profile. */
    var recentSearches: List<String>
        get() = runCatching {
            val array = JSONArray(prefs.getString("$KEY_RECENT:$userId", null) ?: "[]")
            List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        set(value) {
            prefs.edit().putString("$KEY_RECENT:$userId", JSONArray(value.take(6)).toString()).apply()
        }

    companion object {
        private const val LEGACY_SERVER = "http://192.168.1.42:8096"
        private const val KEY_SERVER = "server"
        private const val KEY_SERVER_NAME = "serverName"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "userId"
        private const val KEY_USER_NAME = "userName"
        private const val KEY_CONTROLS = "controls"
        private const val KEY_CUE = "cue"
        private const val KEY_LANG = "lang"
        private const val KEY_AUTO = "autoSubs"
        private const val KEY_DEVICE = "device"
        private const val KEY_FRAME_RATE = "matchFrameRate"
        private const val KEY_RESUME_BACK = "resumeBack"
        private const val KEY_STILL_WATCHING = "stillWatching"
        private const val KEY_THEME_MUSIC = "themeMusic"
        private const val KEY_SCREENSAVER = "screensaver"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_RECENT = "recentSearches"
    }
}
