package com.sci.calc.download

import java.util.Locale

/**
 * Reads an HLS master playlist and lists the qualities it offers, with an
 * estimated download size (bitrate × duration).
 */
object HlsQuality {

    data class Variant(
        val url: String,
        val height: Int,        // 0 if unknown
        val bandwidth: Long,    // bits per second
        val estBytes: Long,     // -1 if unknown
        val audioOnly: Boolean,
    ) {
        val label: String
            get() = when {
                audioOnly -> "Audio only"
                height > 0 -> "${height}p"
                else -> "%.1f Mbps".format(Locale.US, bandwidth / 1_000_000.0)
            }
    }

    /** Returns the variants (best first), or null if [url] is not a master playlist. */
    fun probe(url: String, ua: String?, referer: String?, cookies: String?): List<Variant>? {
        val task = DownloadTask(0, url, "", "", true, ua, referer, cookies)
        val text = Http.text(url, task)
        if (!text.contains("#EXT-X-STREAM-INF")) return null

        val lines = text.lines()
        val raw = ArrayList<Triple<String, Int, Long>>()
        var audioOnlyCodecs = HashSet<String>()
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("(?<![-A-Z])BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val uri = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val full = Http.resolve(url, uri)
            val codecs = Regex("CODECS=\"([^\"]*)\"").find(l)?.groupValues?.get(1).orEmpty()
            if (h == 0 && codecs.isNotEmpty() && !codecs.contains("avc") && !codecs.contains("hvc") &&
                !codecs.contains("hev") && !codecs.contains("av01") && !codecs.contains("vp")) audioOnlyCodecs.add(full)
            raw.add(Triple(full, h, bw))
        }
        if (raw.isEmpty()) return null

        // Duration from one media playlist (all variants share the same timeline).
        val duration = runCatching {
            val media = Http.text(raw.minByOrNull { it.third }!!.first, task)
            media.lines().filter { it.startsWith("#EXTINF:") }
                .sumOf { it.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0 }
        }.getOrDefault(0.0)

        return raw
            .distinctBy { it.first }
            .map { (u, h, bw) ->
                Variant(u, h, bw, if (duration > 0 && bw > 0) (bw / 8.0 * duration).toLong() else -1, u in audioOnlyCodecs)
            }
            // Same resolution listed twice (e.g. different codecs): keep the smaller file.
            .groupBy { if (it.height > 0) "h${it.height}" else "b${it.bandwidth}" }
            .map { (_, g) -> g.minByOrNull { it.bandwidth }!! }
            .sortedWith(compareBy<Variant> { it.audioOnly }.thenByDescending { it.height }.thenByDescending { it.bandwidth })
    }
}
