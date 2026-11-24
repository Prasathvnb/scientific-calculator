package com.sci.calc.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Typed access to the browser settings (shared with SettingsActivity). */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    val searchEngine get() = sp.getString("search_engine", "google") ?: "google"
    val homePage get() = sp.getString("home_page", "")?.trim().orEmpty()
    val adBlock get() = sp.getBoolean("adblock", true)
    val forceDark get() = sp.getBoolean("force_dark", false)
    val desktopDefault get() = sp.getBoolean("desktop_default", false)
    val blockThirdPartyCookies get() = sp.getBoolean("block_3p_cookies", true)
    val doNotTrack get() = sp.getBoolean("dnt", true)
    val javascript get() = sp.getBoolean("javascript", true)
    val blockPopups get() = sp.getBoolean("block_popups", true)
    val encryptDownloads get() = sp.getBoolean("encrypt_downloads", true)
    val textZoom get() = sp.getString("text_zoom", "100")?.toIntOrNull() ?: 100
    val saveHistory get() = sp.getBoolean("save_history", true)

    var downloadTree: String?
        get() = sp.getString("download_tree", null)
        set(v) = sp.edit().putString("download_tree", v).apply()

    var whitelist: Set<String>
        get() = sp.getStringSet("adblock_whitelist", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("adblock_whitelist", v).apply()

    fun searchUrl(q: String): String {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        return when (searchEngine) {
            "bing" -> "https://www.bing.com/search?q=$enc"
            "duckduckgo" -> "https://duckduckgo.com/?q=$enc"
            "brave" -> "https://search.brave.com/search?q=$enc"
            else -> "https://www.google.com/search?q=$enc"
        }
    }

    fun homeUrl(): String = homePage.ifEmpty {
        when (searchEngine) {
            "bing" -> "https://www.bing.com"
            "duckduckgo" -> "https://duckduckgo.com"
            "brave" -> "https://search.brave.com"
            else -> "https://www.google.com"
        }
    }
}
