package com.sci.calc.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException(
    when (code) {
        403 -> "Link expired or access denied (403). Open the video page again and re-download."
        404, 410 -> "File no longer available ($code)"
        else -> "Server error $code"
    }
) {
    /** Worth retrying? Server-side hiccups yes; expired / forbidden / missing links no. */
    val retryable get() = code == 408 || code == 429 || code >= 500
}

object Http {
    /** Opens a GET connection, following redirects across http/https (up to 8 hops). */
    fun open(url: String, task: DownloadTask, range: String? = null): HttpURLConnection {
        var current = url
        repeat(8) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            task.userAgent?.let { c.setRequestProperty("User-Agent", it) }
            task.referer?.let { c.setRequestProperty("Referer", it) }
            task.cookies?.let { c.setRequestProperty("Cookie", it) }
            c.setRequestProperty("Accept", "*/*")
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
            } else {
                if (code >= 400) {
                    c.disconnect()
                    throw HttpStatusException(code)
                }
                return c
            }
        }
        throw IOException("Too many redirects")
    }

    fun text(url: String, task: DownloadTask): String =
        open(url, task).let { c -> c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { c.disconnect() } }

    fun bytes(url: String, task: DownloadTask, range: String? = null): ByteArray =
        open(url, task, range).let { c -> c.inputStream.use { it.readBytes() }.also { c.disconnect() } }

    fun resolve(base: String, ref: String): String = URL(URL(base), ref.trim()).toString()
}
