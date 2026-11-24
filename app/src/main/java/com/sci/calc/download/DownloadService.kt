package com.sci.calc.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sci.calc.App
import com.sci.calc.calc.CalculatorActivity
import com.sci.calc.data.Db
import com.sci.calc.data.Prefs
import com.sci.calc.security.CryptoManager
import com.sci.calc.vault.Storage
import java.io.BufferedOutputStream
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Runs downloads in the background. Notifications use neutral wording and open
 * the calculator, so nothing about the browser is shown outside the app.
 */
class DownloadService : Service() {

    private val pool = Executors.newFixedThreadPool(2)
    private val scheduled = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val listener: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        DownloadRegistry.addListener(listener)
    }

    override fun onDestroy() {
        DownloadRegistry.removeListener(listener)
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        val task = DownloadRegistry.find(id)
        // Start it unless this exact download is already scheduled or running.
        if (task != null && scheduled.add(task.id)) {
            task.status = Status.QUEUED; task.cancelRequested = false; task.pauseRequested = false; task.error = null
            DownloadRegistry.changed(true)
            pool.execute {
                try { run(task) } finally { scheduled.remove(task.id) }
                if (DownloadRegistry.activeCount() == 0) stopSelfSafely()
            }
        } else if (DownloadRegistry.activeCount() == 0) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun run(task: DownloadTask) {
        task.status = Status.RUNNING
        DownloadRegistry.changed(true)
        var attempt = 0
        while (true) {
            try {
                if (task.isHls) runHls(task) else runDirect(task)
                Db.get(this).addFile(task.name, task.mime, task.location!!, task.bytes, task.encrypted)
                task.status = Status.DONE
                break
            } catch (e: InterruptedException) {
                if (task.cancelRequested) {
                    task.location?.let { Storage.deleteLocation(this, it) }
                    resetProgress(task)
                    task.status = Status.CANCELLED
                } else task.status = Status.PAUSED
                break
            } catch (e: java.io.IOException) {
                if (e is HttpStatusException && !e.retryable) {
                    task.status = Status.FAILED; task.error = e.message; break
                }
                if (task.cancelRequested || task.pauseRequested) { task.status = Status.PAUSED; break }
                // No internet: wait for it to come back (doesn't use up a retry).
                if (!isOnline()) {
                    task.error = "Waiting for internet…"
                    DownloadRegistry.changed(true)
                    if (waitForNetwork(task)) continue
                    if (task.cancelRequested || task.pauseRequested) { task.status = Status.PAUSED; break }
                    task.status = Status.FAILED; task.error = "No internet. Tap to resume."; break
                }
                attempt++
                if (attempt <= MAX_RETRIES) {
                    task.error = "Connection dropped, retrying ($attempt/$MAX_RETRIES)…"
                    DownloadRegistry.changed(true)
                    sleepUnlessStopped(task, minOf(5000L * attempt, 30_000L))
                    continue
                }
                task.status = Status.FAILED
                task.error = (e.message ?: "Network error") + ". Tap to resume."
                break
            } catch (e: Exception) {
                task.status = Status.FAILED
                task.error = e.message ?: e.javaClass.simpleName
                break
            }
        }
        if (task.status == Status.DONE) task.error = null
        DownloadRegistry.changed(true)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Waits up to 30 minutes for a working connection. Returns true when back online. */
    private fun waitForNetwork(task: DownloadTask): Boolean {
        val until = System.currentTimeMillis() + 30 * 60_000L
        while (System.currentTimeMillis() < until) {
            if (task.cancelRequested || task.pauseRequested) return false
            if (isOnline()) { task.error = null; return true }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun sleepUnlessStopped(task: DownloadTask, ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !task.cancelRequested && !task.pauseRequested) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
    }

    private fun resetProgress(t: DownloadTask) {
        t.location = null; t.committed = 0; t.segmentsDone = 0; t.done = 0; t.bytes = 0
    }

    /** Opens the output: continues the partial file when possible, otherwise starts a new one. */
    private fun openOutput(task: DownloadTask, resume: Boolean): java.io.OutputStream {
        val loc = task.location
        if (resume && loc != null && task.committed > 0) {
            val header = if (task.encrypted) CryptoManager.HEADER_SIZE.toLong() else 0L
            val len = Storage.length(this, loc)
            if (len >= header + task.committed) {
                val raw = Storage.openAppend(this, loc, header + task.committed)
                if (!task.encrypted) return raw
                val iv = Storage.openPfd(this, loc)!!.let { pfd ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { CryptoManager.readIv(it) }
                }
                return CryptoManager.encryptAppend(this, iv, task.committed, raw)
            }
        }
        // Fresh start.
        loc?.let { Storage.deleteLocation(this, it) }
        resetProgress(task)
        val encrypt = Prefs(this).encryptDownloads
        val target = Storage.create(this, task.name, task.mime, encrypt)
        task.location = target.location
        task.encrypted = encrypt
        DownloadRegistry.changed(true)
        return if (encrypt) CryptoManager.encrypt(this, target.out) else target.out
    }

    private fun runHls(task: DownloadTask) {
        val hls = HlsDownloader(task)
        val playlist = hls.load()
        if (task.location == null) {
            val ext = if (hls.isFragmentedMp4(playlist)) "mp4" else "ts"
            task.mime = if (ext == "mp4") "video/mp4" else "video/mp2t"
            task.name = task.name.substringBeforeLast('.') + "." + ext
        }
        val out = openOutput(task, resume = task.segmentsDone > 0 || task.committed > 0)
        BufferedOutputStream(out, 256 * 1024).use { hls.write(playlist, it) }
    }

    private fun runDirect(task: DownloadTask) {
        val resume = task.resumable
        val conn = Http.open(task.url, task, if (resume) "bytes=${task.committed}-" else null)
        val partial = conn.responseCode == 206
        if (task.location == null) {
            fileNameFrom(conn.getHeaderField("Content-Disposition"))?.let { task.name = it }
            val type = conn.contentType?.substringBefore(';')?.trim()
            if (!type.isNullOrEmpty() && type != "application/octet-stream" && !type.startsWith("text/")) task.mime = type
            if (!task.name.contains('.')) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(task.mime)?.let { task.name += ".$it" }
            }
        }
        // If the server ignored the range request, the file starts again.
        val continuing = resume && partial
        val out = openOutput(task, resume = continuing)
        val length = conn.contentLengthLong
        task.total = when {
            continuing -> conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: if (length > 0) task.committed + length else -1
            else -> length
        }
        task.done = task.committed
        task.bytes = task.committed
        try {
            conn.inputStream.use { input ->
                out.use { o ->
                    val buf = ByteArray(128 * 1024)
                    var sinceFlush = 0L
                    try {
                        while (true) {
                            if (task.cancelRequested || task.pauseRequested) throw InterruptedException()
                            val r = input.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            task.done += r; task.bytes = task.done
                            sinceFlush += r
                            if (sinceFlush >= 1024 * 1024) { o.flush(); task.committed = task.done; sinceFlush = 0 }
                            DownloadRegistry.changed()
                        }
                    } finally {
                        // Whatever reached the file is kept for resuming.
                        runCatching { o.flush(); task.committed = task.done }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (task.total > 0 && task.done < task.total) throw java.io.IOException("Connection lost")
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val running = DownloadRegistry.tasks.filter { it.status == Status.RUNNING }
        val pct = running.firstOrNull()?.percent ?: -1
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CalculatorActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing")
            .setContentText(if (running.size > 1) "${running.size} tasks" else if (pct >= 0) "$pct%" else "Working…")
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification() {
        if (DownloadRegistry.activeCount() > 0) nm.notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val MAX_RETRIES = 10
        private const val EXTRA_ID = "id"

        /** Continues downloads that were interrupted because the app was closed. */
        fun resumeInterrupted(ctx: Context) {
            DownloadRegistry.tasks.filter { it.interrupted }.forEach { it.interrupted = false; start(ctx, it) }
        }

        fun start(ctx: Context, task: DownloadTask) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ID, task.id))
        }

        fun fileNameFrom(disposition: String?): String? {
            if (disposition == null) return null
            Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
                return Storage.safeName(URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8"))
            }
            Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
                return Storage.safeName(it.groupValues[1].trim())
            }
            return null
        }
    }
}
