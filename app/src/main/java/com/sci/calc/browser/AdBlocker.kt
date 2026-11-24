package com.sci.calc.browser

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Host-based ad and tracker blocker.
 *
 * Ships with a built-in list (assets/adhosts.txt) and refreshes a large community
 * hosts list (StevenBlack unified hosts, ~100k domains) in the background once a week.
 * A request is blocked when its host or any parent domain is on the list.
 */
object AdBlocker {
    @Volatile private var hosts: Set<String> = emptySet()
    val blockedCount = AtomicInteger(0)

    private const val REMOTE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "adblock-load") {
            val set = HashSet<String>(150_000)
            runCatching { app.assets.open("adhosts.txt").bufferedReader().useLines { parse(it, set) } }
            val cache = File(app.filesDir, "hosts.txt")
            if (cache.exists()) runCatching { cache.bufferedReader().useLines { parse(it, set) } }
            hosts = Collections.unmodifiableSet(set)
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > WEEK) refresh(app)
        }
    }

    fun refresh(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        thread(name = "adblock-update") {
            val ok = runCatching {
                val c = URL(REMOTE).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val tmp = File(ctx.filesDir, "hosts.tmp")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val fresh = HashSet<String>(150_000)
                ctx.assets.open("adhosts.txt").bufferedReader().useLines { parse(it, fresh) }
                tmp.bufferedReader().useLines { parse(it, fresh) }
                if (fresh.size > 1000) {
                    tmp.renameTo(File(ctx.filesDir, "hosts.txt"))
                    hosts = Collections.unmodifiableSet(fresh)
                }
                true
            }.getOrDefault(false)
            done?.invoke(ok)
        }
    }

    val size get() = hosts.size

    private fun parse(lines: Sequence<String>, into: MutableSet<String>) {
        for (raw in lines) {
            val l = raw.substringBefore('#').trim()
            if (l.isEmpty()) continue
            val parts = l.split(Regex("\\s+"))
            val host = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]
            if (host == "localhost" || host == "0.0.0.0" || host.startsWith("local") || !host.contains('.')) continue
            into.add(host.lowercase())
        }
    }

    fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        val set = hosts
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // stop at the registrable domain
            h = h.substring(dot + 1)
        }
    }
}
