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
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Runs downloads in the background. Notifications use neutral wording and open
 * the calculator, so nothing about the browser is shown outside the app.
 */
class DownloadService : Service() {

    private val pool = Executors.newFixedThreadPool(2)
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
        if (task != null) {
            task.status = Status.QUEUED; task.cancelRequested = false; task.error = null
            task.done = 0; task.bytes = 0
            DownloadRegistry.changed(true)
            pool.execute { run(task) }
        } else if (DownloadRegistry.activeCount() == 0) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun run(task: DownloadTask) {
        task.status = Status.RUNNING
        DownloadRegistry.changed(true)
        val encrypt = Prefs(this).encryptDownloads
        var location: String? = null
        try {
            if (task.isHls) {
                val hls = HlsDownloader(task)
                val playlist = hls.load()
                val ext = if (hls.isFragmentedMp4(playlist)) "mp4" else "ts"
                val mime = if (ext == "mp4") "video/mp4" else "video/mp2t"
                task.name = task.name.substringBeforeLast('.') + "." + ext
                val target = Storage.create(this, task.name, mime, encrypt)
                location = target.location
                val out = BufferedOutputStream(if (encrypt) CryptoManager.encrypt(this, target.out) else target.out, 256 * 1024)
                out.use { hls.write(playlist, it) }
                Db.get(this).addFile(task.name, mime, target.location, task.bytes, encrypt)
            } else {
                val conn = Http.open(task.url, task)
                val disposition = conn.getHeaderField("Content-Disposition")
                fileNameFrom(disposition)?.let { task.name = it }
                val mime = (conn.contentType?.substringBefore(';')?.trim()).takeUnless { it.isNullOrEmpty() || it == "application/octet-stream" }
                    ?: task.mime
                if (!task.name.contains('.')) {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { task.name += ".$it" }
                }
                task.total = conn.contentLengthLong
                val target = Storage.create(this, task.name, mime, encrypt)
                location = target.location
                val out = BufferedOutputStream(if (encrypt) CryptoManager.encrypt(this, target.out) else target.out, 256 * 1024)
                conn.inputStream.use { input ->
                    out.use { o ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            if (task.cancelRequested) throw InterruptedException()
                            val r = input.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            task.done += r; task.bytes = task.done
                            DownloadRegistry.changed()
                        }
                    }
                }
                conn.disconnect()
                if (task.total > 0 && task.done < task.total) throw IOException("Connection lost")
                Db.get(this).addFile(task.name, mime, target.location, task.bytes, encrypt)
            }
            task.status = Status.DONE
        } catch (e: InterruptedException) {
            task.status = Status.CANCELLED
            location?.let { deletePartial(it) }
        } catch (e: Exception) {
            task.status = Status.FAILED
            task.error = e.message ?: e.javaClass.simpleName
            location?.let { deletePartial(it) }
        }
        DownloadRegistry.changed(true)
        if (DownloadRegistry.activeCount() == 0) stopSelfSafely()
    }

    private fun deletePartial(location: String) {
        runCatching {
            if (location.startsWith("/")) java.io.File(location).delete()
            else androidx.documentfile.provider.DocumentFile.fromSingleUri(this, android.net.Uri.parse(location))?.delete()
        }
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
        private const val EXTRA_ID = "id"

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
