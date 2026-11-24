package app.pulse.news.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Local, private storage: server, session token, appearance, morning brief, offline feed cache. */
object Store {
    private lateinit var sp: SharedPreferences
    private lateinit var cacheDir: File

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("pulse", Context.MODE_PRIVATE)
        cacheDir = ctx.filesDir
        theme = sp.getString("theme", "system") ?: "system"
        textScale = sp.getFloat("text_scale", 1f)
    }

    /** The Pulse server; defaults to the one this build was made for, so new users only need an account. */
    var server: String
        get() = (sp.getString("server", "") ?: "").ifBlank { app.pulse.news.BuildConfig.DEFAULT_SERVER }
        set(v) = sp.edit().putString("server", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var loggedIn: Boolean
        get() = sp.getBoolean("logged_in", false)
        set(v) = sp.edit().putBoolean("logged_in", v).apply()

    // Appearance is observable so the whole UI recomposes when it changes.
    var theme by mutableStateOf("system")
        private set
    var textScale by mutableFloatStateOf(1f)
        private set

    fun changeTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun changeTextScale(v: Float) { textScale = v; sp.edit().putFloat("text_scale", v).apply() }

    var briefEnabled: Boolean
        get() = sp.getBoolean("brief_enabled", false)
        set(v) = sp.edit().putBoolean("brief_enabled", v).apply()

    var briefHour: Int
        get() = sp.getInt("brief_hour", 8)
        set(v) = sp.edit().putInt("brief_hour", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var skippedVersion: Int
        get() = sp.getInt("skipped_version", 0)
        set(v) = sp.edit().putInt("skipped_version", v).apply()

    /** Last feed response, so the app opens instantly and works offline. */
    var cachedFeed: String?
        get() = runCatching { File(cacheDir, "feed.json").readText() }.getOrNull()
        set(v) {
            val f = File(cacheDir, "feed.json")
            if (v == null) f.delete() else f.writeText(v)
        }

    fun logout() {
        token = ""
        loggedIn = false
        cachedFeed = null
    }
}
