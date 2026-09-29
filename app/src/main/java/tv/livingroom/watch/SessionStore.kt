package tv.livingroom.watch

import android.content.Context
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
        prefs.edit()
            .putString(KEY_TOKEN, accessToken)
            .putString(KEY_USER_ID, id)
            .putString(KEY_USER_NAME, name)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .apply()
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
    }
}
