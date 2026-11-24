package com.sci.calc.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

class DownloadTask(
    val id: Long,
    var url: String,
    var name: String,
    var mime: String,
    val isHls: Boolean,
    val userAgent: String?,
    val referer: String?,
    val cookies: String?,
) {
    @Volatile var status = Status.QUEUED
    @Volatile var done = 0L        // bytes, or finished segments for HLS
    @Volatile var total = -1L      // bytes, or number of segments for HLS
    @Volatile var bytes = 0L       // bytes written so far
    @Volatile var error: String? = null
    @Volatile var cancelRequested = false
    @Volatile var pauseRequested = false

    // Resume state: the partly written file and how much of it is safely on disk.
    @Volatile var location: String? = null
    @Volatile var encrypted = false
    @Volatile var committed = 0L   // plain bytes safely written
    @Volatile var segmentsDone = 0 // HLS: segments fully written

    val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    val resumable: Boolean get() = location != null && committed > 0

    fun toJson() = JSONObject().apply {
        put("id", id); put("url", url); put("name", name); put("mime", mime); put("hls", isHls)
        put("ua", userAgent); put("ref", referer); put("ck", cookies)
        put("status", status.name); put("done", done); put("total", total); put("bytes", bytes)
        put("err", error); put("loc", location); put("enc", encrypted)
        put("committed", committed); put("seg", segmentsDone)
    }

    companion object {
        fun fromJson(o: JSONObject) = DownloadTask(
            o.getLong("id"), o.getString("url"), o.getString("name"), o.getString("mime"), o.getBoolean("hls"),
            o.optString("ua").ifEmpty { null }, o.optString("ref").ifEmpty { null }, o.optString("ck").ifEmpty { null },
        ).apply {
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.FAILED)
            done = o.optLong("done"); total = o.optLong("total", -1); bytes = o.optLong("bytes")
            error = o.optString("err").ifEmpty { null }
            location = o.optString("loc").ifEmpty { null }
            encrypted = o.optBoolean("enc"); committed = o.optLong("committed"); segmentsDone = o.optInt("seg")
            // Anything that was running when the app closed can be resumed.
            if (status == Status.RUNNING || status == Status.QUEUED) { status = Status.PAUSED; error = null }
        }
    }
}

/** Downloads list, saved to disk so unfinished downloads can be resumed after the app closes. */
object DownloadRegistry {
    private val ids = AtomicLong(1)
    val tasks = CopyOnWriteArrayList<DownloadTask>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var lastNotify = 0L
    private var lastSave = 0L
    private var file: File? = null

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "downloads.json")
        file = f
        runCatching {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) tasks.add(DownloadTask.fromJson(arr.getJSONObject(i)))
                ids.set((tasks.maxOfOrNull { it.id } ?: 0) + 1)
            }
        }
    }

    fun add(url: String, name: String, mime: String, isHls: Boolean, ua: String?, referer: String?, cookies: String?): DownloadTask {
        val t = DownloadTask(ids.getAndIncrement(), url, name, mime, isHls, ua, referer, cookies)
        tasks.add(0, t)
        changed(force = true)
        return t
    }

    fun find(id: Long) = tasks.firstOrNull { it.id == id }

    fun activeCount() = tasks.count { it.status == Status.RUNNING || it.status == Status.QUEUED }

    fun clearFinished() {
        tasks.removeAll { it.status == Status.DONE || it.status == Status.CANCELLED }
        changed(force = true)
    }

    fun remove(t: DownloadTask) {
        tasks.remove(t)
        changed(force = true)
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastSave > 3000) { lastSave = now; save() }
        if (!force && now - lastNotify < 400) return
        lastNotify = now
        main.post { listeners.forEach { it() } }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            tasks.take(200).forEach { arr.put(it.toJson()) }
            val tmp = File(f.parentFile, "downloads.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }
}
