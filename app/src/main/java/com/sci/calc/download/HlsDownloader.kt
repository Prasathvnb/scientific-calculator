package com.sci.calc.download

import java.io.IOException
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS (.m3u8) stream by fetching every segment and joining them.
 * Plain and AES-128 segments are supported. DRM (SAMPLE-AES / Widevine / FairPlay)
 * streams are refused. Output is MPEG-TS, or fragmented MP4 when the playlist uses EXT-X-MAP.
 */
class HlsDownloader(private val task: DownloadTask) {

    class Segment(val url: String, val seq: Long, val key: KeyInfo?, val range: String?)
    class KeyInfo(val method: String, val uri: String?, val iv: ByteArray?)
    class MediaPlaylist(val segments: List<Segment>, val initUrl: String?, val initRange: String?)

    private val keyCache = HashMap<String, ByteArray>()

    /** Resolves master → best variant and returns the media playlist. */
    fun load(): MediaPlaylist {
        var url = task.url
        var text = Http.text(url, task)
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("Not a valid stream playlist")
        if (text.contains("#EXT-X-STREAM-INF")) {
            url = bestVariant(url, text)
            text = Http.text(url, task)
        }
        if (!text.contains("#EXT-X-ENDLIST")) throw IOException("Live streams can't be downloaded")
        return parseMedia(url, text)
    }

    fun isFragmentedMp4(p: MediaPlaylist) = p.initUrl != null

    private fun bestVariant(base: String, text: String): String {
        val lines = text.lines()
        var best: String? = null
        var bestBw = -1L
        for (i in lines.indices) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > bestBw) { bestBw = bw; best = uri }
            }
        }
        return Http.resolve(base, best ?: throw IOException("No playable variant"))
    }

    private fun attr(line: String, name: String): String? =
        Regex("$name=(\"([^\"]*)\"|([^,]*))").find(line)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun parseMedia(base: String, text: String): MediaPlaylist {
        val segs = ArrayList<Segment>()
        var seq = 0L
        var key: KeyInfo? = null
        var initUrl: String? = null
        var initRange: String? = null
        var pendingRange: String? = null
        var lastRangeEnd = 0L
        var expectUri = false
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(l, "METHOD") ?: "NONE"
                    key = if (method == "NONE") null else {
                        if (method != "AES-128") throw IOException("This video is DRM-protected and can't be downloaded")
                        KeyInfo(method, attr(l, "URI")?.let { Http.resolve(base, it) }, attr(l, "IV")?.let { hexIv(it) })
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> {
                    initUrl = attr(l, "URI")?.let { Http.resolve(base, it) }
                    initRange = attr(l, "BYTERANGE")?.let { toRange(it, 0L).first }
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> {
                    val (r, end) = toRange(l.substringAfter(':'), lastRangeEnd)
                    pendingRange = r; lastRangeEnd = end
                }
                l.startsWith("#EXTINF") -> expectUri = true
                l.isNotEmpty() && !l.startsWith("#") && expectUri -> {
                    segs.add(Segment(Http.resolve(base, l), seq++, key, pendingRange))
                    pendingRange = null; expectUri = false
                }
            }
        }
        if (segs.isEmpty()) throw IOException("Empty playlist")
        return MediaPlaylist(segs, initUrl, initRange)
    }

    /** "len@offset" → HTTP Range header and the end offset. */
    private fun toRange(spec: String, prevEnd: Long): Pair<String, Long> {
        val parts = spec.trim().split('@')
        val len = parts[0].toLong()
        val start = if (parts.size > 1) parts[1].toLong() else prevEnd
        return "bytes=$start-${start + len - 1}" to (start + len)
    }

    private fun hexIv(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Writes segments from [DownloadTask.segmentsDone] onwards, so a retry continues
     * where it stopped. After every segment the data is flushed and recorded as committed.
     */
    fun write(p: MediaPlaylist, out: OutputStream) {
        task.total = p.segments.size.toLong()
        if (task.segmentsDone == 0 && task.committed == 0L) {
            p.initUrl?.let {
                val init = retry { Http.bytes(it, task, p.initRange) }
                out.write(init); out.flush()
                task.bytes = init.size.toLong(); task.committed = task.bytes
            }
        }
        if (task.segmentsDone > p.segments.size) throw IOException("Stream changed, please restart")
        task.done = task.segmentsDone.toLong()
        for (idx in task.segmentsDone until p.segments.size) {
            if (task.cancelRequested || task.pauseRequested) throw InterruptedException()
            val seg = p.segments[idx]
            var data = retry { Http.bytes(seg.url, task, seg.range) }
            seg.key?.let { k -> data = decrypt(data, k, seg.seq) }
            out.write(data)
            out.flush()
            task.bytes += data.size
            task.committed = task.bytes
            task.segmentsDone = idx + 1
            task.done = task.segmentsDone.toLong()
            DownloadRegistry.changed()
        }
    }

    private fun <T> retry(block: () -> T): T {
        var last: Exception? = null
        repeat(4) { attempt ->
            try { return block() } catch (e: IOException) {
                if (e is HttpStatusException && !e.retryable) throw e
                last = e; Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Failed")
    }

    private fun decrypt(data: ByteArray, k: KeyInfo, seq: Long): ByteArray {
        val keyUrl = k.uri ?: throw IOException("Missing key")
        val keyBytes = keyCache.getOrPut(keyUrl) { Http.bytes(keyUrl, task) }
        val iv = k.iv ?: ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (seq shr (8 * i)).toByte() }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
