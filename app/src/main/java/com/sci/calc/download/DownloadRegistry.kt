package com.sci.calc.download

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

class DownloadTask(
    val id: Long,
    val url: String,
    var name: String,
    val mime: String,
    val isHls: Boolean,
    val userAgent: String?,
    val referer: String?,
    val cookies: String?,
) {
    @Volatile var status = Status.QUEUED
    @Volatile var done = 0L        // bytes, or segments for HLS
    @Volatile var total = -1L      // bytes, or segments for HLS
    @Volatile var bytes = 0L
    @Volatile var error: String? = null
    @Volatile var cancelRequested = false

    val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
}

/** In-memory list of this session's downloads, observed by the Downloads screen. */
object DownloadRegistry {
    private val ids = AtomicLong(1)
    val tasks = CopyOnWriteArrayList<DownloadTask>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var lastNotify = 0L

    fun add(url: String, name: String, mime: String, isHls: Boolean, ua: String?, referer: String?, cookies: String?): DownloadTask {
        val t = DownloadTask(ids.getAndIncrement(), url, name, mime, isHls, ua, referer, cookies)
        tasks.add(0, t)
        changed(force = true)
        return t
    }

    fun find(id: Long) = tasks.firstOrNull { it.id == id }

    fun activeCount() = tasks.count { it.status == Status.RUNNING || it.status == Status.QUEUED }

    fun clearFinished() {
        tasks.removeAll { it.status == Status.DONE || it.status == Status.CANCELLED || it.status == Status.FAILED }
        changed(force = true)
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 400) return
        lastNotify = now
        main.post { listeners.forEach { it() } }
    }
}
