package com.sci.calc.browser

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Ad and tracker blocker.
 *
 * Sources (refreshed weekly in the background, cached on the phone):
 *  - EasyList (the list used by AdBlock Plus / uBlock Origin): ad-server rules and
 *    element-hiding rules that hide ad boxes on pages
 *  - StevenBlack unified hosts: ~100k ad, tracker and malware domains
 *  - a small built-in list so blocking works before the first download
 *
 * Network: a request is blocked when its host or any parent domain is listed
 * (unless an EasyList exception allows it). Cosmetic: CSS that hides ad elements
 * is injected into every page and frame.
 */
object AdBlocker {
    private class Data(
        val hosts: Set<String>,
        val exceptions: Set<String>,
        val genericCss: String,
        val siteSelectors: Map<String, List<String>>,
    )

    @Volatile private var data = Data(emptySet(), emptySet(), "", emptyMap())
    val blockedCount = AtomicInteger(0)

    private val SOURCES = listOf(
        "easylist.txt" to listOf(
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist-downloads.adblockplus.org/easylist.txt",
        ),
        "hosts.txt" to listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        ),
    )
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    // uBlock/ABP procedural selectors that aren't real CSS.
    private val PROCEDURAL = Regex(":-abp-|:has-text|:style\\(|:xpath|:upward|:remove|:matches-|:min-text|:watch-attr|:others|:if\\(|:if-not|\\+js\\(|:matches-path")

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "adblock-load") {
            build(app)
            val stale = SOURCES.any { (name, _) ->
                val f = File(app.filesDir, name)
                !f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK
            }
            if (stale) refresh(app)
        }
    }

    fun refresh(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        thread(name = "adblock-update") {
            var any = false
            for ((name, urls) in SOURCES) {
                for (u in urls) {
                    val ok = runCatching {
                        val c = URL(u).openConnection() as HttpURLConnection
                        c.connectTimeout = 15000; c.readTimeout = 45000
                        val tmp = File(ctx.filesDir, "$name.tmp")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        if (tmp.length() < 50_000) error("too small")
                        tmp.renameTo(File(ctx.filesDir, name))
                    }.isSuccess
                    if (ok) { any = true; break }
                }
            }
            if (any) build(ctx)
            done?.invoke(any)
        }
    }

    private fun build(ctx: Context) {
        val hosts = HashSet<String>(200_000)
        val exceptions = HashSet<String>()
        val generic = ArrayList<String>(20_000)
        val site = HashMap<String, MutableList<String>>()

        runCatching { ctx.assets.open("adhosts.txt").bufferedReader().useLines { parseHosts(it, hosts) } }
        File(ctx.filesDir, "hosts.txt").takeIf { it.exists() }?.let { f ->
            runCatching { f.bufferedReader().useLines { parseHosts(it, hosts) } }
        }
        File(ctx.filesDir, "easylist.txt").takeIf { it.exists() }?.let { f ->
            runCatching { f.bufferedReader().useLines { parseEasyList(it, hosts, exceptions, generic, site) } }
        }

        // Chunk the selectors so one unsupported selector only disables its own small group.
        val css = StringBuilder()
        generic.chunked(25).forEach { css.append(it.joinToString(",")).append("{display:none!important}\n") }
        data = Data(hosts, exceptions, css.toString(), site)
    }

    private fun parseHosts(lines: Sequence<String>, into: MutableSet<String>) {
        for (raw in lines) {
            val l = raw.substringBefore('#').trim()
            if (l.isEmpty()) continue
            val parts = l.split(' ', '\t').filter { it.isNotEmpty() }
            val host = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0]
            if (host == "localhost" || host == "0.0.0.0" || host.startsWith("local") || !host.contains('.')) continue
            into.add(host.lowercase())
        }
    }

    private fun parseEasyList(
        lines: Sequence<String>,
        hosts: MutableSet<String>,
        exceptions: MutableSet<String>,
        generic: MutableList<String>,
        site: MutableMap<String, MutableList<String>>,
    ) {
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("!") || l.startsWith("[")) continue
            when {
                l.contains("#@#") || l.contains("#?#") || l.contains("#$#") || l.contains("#%#") -> continue
                l.contains("##") -> {
                    val domains = l.substringBefore("##")
                    val sel = l.substringAfter("##")
                    if (sel.isEmpty() || PROCEDURAL.containsMatchIn(sel)) continue
                    if (domains.isEmpty()) generic.add(sel)
                    else domains.split(',').filter { it.isNotEmpty() && !it.startsWith("~") }
                        .forEach { site.getOrPut(it.lowercase()) { ArrayList() }.add(sel) }
                }
                l.startsWith("@@||") -> hostOnly(l.substring(4))?.let { exceptions.add(it) }
                l.startsWith("||") -> hostOnly(l.substring(2))?.let { hosts.add(it) }
            }
        }
    }

    /** "example.com^" or "example.com^$third-party" → example.com; rules with a path or domain= are skipped. */
    private fun hostOnly(rule: String): String? {
        val end = rule.indexOfFirst { it == '^' || it == '/' || it == '$' || it == '*' || it == ':' }
        val host = (if (end < 0) rule else rule.substring(0, end)).lowercase()
        if (!host.contains('.') || host.endsWith('.')) return null
        val rest = if (end < 0) "" else rule.substring(end)
        val ok = rest.isEmpty() || rest == "^" || (rest.startsWith("^$") && !rest.contains("domain=")) ||
            (rest.startsWith("$") && !rest.contains("domain="))
        return if (ok) host else null
    }

    val size get() = data.hosts.size

    private fun matches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        val d = data
        return matches(d.hosts, h) && !matches(d.exceptions, h)
    }

    /** CSS hiding ad elements for a page on [host]. */
    fun cssFor(host: String?): String {
        val d = data
        val sb = StringBuilder(d.genericCss)
        var h = host?.lowercase().orEmpty()
        while (h.contains('.')) {
            d.siteSelectors[h]?.let { sels -> sels.chunked(25).forEach { sb.append(it.joinToString(",")).append("{display:none!important}\n") } }
            h = h.substringAfter('.')
        }
        return sb.toString()
    }
}
