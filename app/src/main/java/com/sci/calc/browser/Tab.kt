package com.sci.calc.browser

import android.graphics.Bitmap
import android.webkit.WebView
import java.util.Locale

data class VideoInfo(val url: String, val kind: String, val pageUrl: String?, val pageTitle: String?) {
    val downloadable get() = kind != "DASH"

    fun suggestedName(): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val base = pageTitle?.takeIf { it.isNotBlank() } ?: fromUrl.ifBlank { "video" }
        val ext = when (kind) { "HLS" -> "ts"; else -> kind.lowercase(Locale.US) }
        return base.take(80) + "." + ext
    }

    companion object {
        private val direct = listOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "flv", "avi", "mp3", "m4a", "aac", "ogg", "opus")

        /** Recognises video/audio URLs seen in network traffic or in <video> tags. */
        fun detect(url: String, pageUrl: String?, title: String?): VideoInfo? {
            if (!url.startsWith("http")) return null
            val lower = url.lowercase(Locale.US)
            val path = lower.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "")
            return when {
                path.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("format=m3u8") ->
                    VideoInfo(url, "HLS", pageUrl, title)
                path.endsWith(".mpd") -> VideoInfo(url, "DASH", pageUrl, title)
                ext in direct && !isSegment(lower) -> VideoInfo(url, ext.uppercase(Locale.US), pageUrl, title)
                else -> null
            }
        }

        // Pieces of an adaptive stream, not whole files.
        private fun isSegment(u: String) =
            u.contains("range=") || u.contains("/seg-") || u.contains("bytestart=") || u.contains("&sq=")
    }
}

class Tab(val webView: WebView, val incognito: Boolean) {
    var title: String = ""
    var favicon: Bitmap? = null
    var desktop = false
    val videos = LinkedHashMap<String, VideoInfo>()

    val url: String get() = webView.url ?: ""
}
