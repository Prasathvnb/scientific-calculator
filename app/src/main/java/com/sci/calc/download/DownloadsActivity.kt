package com.sci.calc.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sci.calc.security.SecureActivity
import com.sci.calc.vault.VaultActivity

/** Live list of this session's downloads, with cancel / retry. */
class DownloadsActivity : SecureActivity() {

    private lateinit var adapter: ArrayAdapter<DownloadTask>
    private lateinit var empty: TextView
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Downloads"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = TextView(this).apply {
            text = "No downloads yet.\nFinished files are in the Locked folder."
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        val list = ListView(this)
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter = object : ArrayAdapter<DownloadTask>(this, 0, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = getItem(position)!!
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
                    addView(TextView(context).apply { textSize = 15f; maxLines = 2; tag = "name" })
                    addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; tag = "bar" })
                    addView(TextView(context).apply { textSize = 12f; alpha = 0.7f; tag = "info" })
                }
                (row.findViewWithTag<TextView>("name")).text = t.name
                val bar = row.findViewWithTag<ProgressBar>("bar")
                bar.visibility = if (t.status == Status.RUNNING || t.status == Status.QUEUED || t.status == Status.PAUSED || (t.status == Status.FAILED && t.resumable)) View.VISIBLE else View.GONE
                bar.isIndeterminate = t.percent < 0
                if (t.percent >= 0) bar.progress = t.percent
                val size = Formatter.formatFileSize(context, t.bytes)
                row.findViewWithTag<TextView>("info").text = when (t.status) {
                    Status.QUEUED -> "Waiting…"
                    Status.RUNNING -> (t.error?.let { "$it · " } ?: "") + progressText(t, size)
                    Status.PAUSED -> "Paused · " + progressText(t, size) + " · tap to resume"
                    Status.DONE -> "Done · $size · in Locked folder"
                    Status.FAILED -> "Failed: ${t.error} · " + (if (t.resumable) "tap to resume (${t.percent.coerceAtLeast(0)}% saved)" else "tap to retry")
                    Status.CANCELLED -> "Cancelled · tap to restart"
                }
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val t = adapter.getItem(pos)!!
            val opts = when (t.status) {
                Status.RUNNING, Status.QUEUED -> arrayOf("Pause", "Cancel", "Copy link")
                Status.PAUSED, Status.FAILED -> arrayOf(if (t.resumable) "Resume" else "Retry", "Cancel", "Copy link")
                Status.DONE -> arrayOf("Open Locked folder", "Copy link", "Remove from list")
                Status.CANCELLED -> arrayOf("Restart", "Copy link", "Remove from list")
            }
            AlertDialog.Builder(this).setTitle(t.name).setItems(opts) { _, i ->
                when (opts[i]) {
                    "Pause" -> { t.pauseRequested = true }
                    "Cancel" -> AlertDialog.Builder(this).setMessage("Cancel and delete the partly downloaded file?")
                        .setPositiveButton("Cancel download") { _, _ ->
                            if (t.status == Status.RUNNING || t.status == Status.QUEUED) t.cancelRequested = true
                            else {
                                t.location?.let { com.sci.calc.vault.Storage.deleteLocation(this, it) }
                                t.location = null; t.committed = 0; t.segmentsDone = 0; t.done = 0; t.bytes = 0
                                t.status = Status.CANCELLED; DownloadRegistry.changed(true)
                            }
                        }.setNegativeButton("Keep", null).show()
                    "Resume", "Retry", "Restart" -> DownloadService.start(this, t)
                    "Open Locked folder" -> startActivity(Intent(this, VaultActivity::class.java))
                    "Copy link" -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", t.url))
                    "Remove from list" -> DownloadRegistry.remove(t)
                }
            }.show()
        }
    }

    private fun progressText(t: DownloadTask, size: String): String =
        if (t.isHls) "${t.done}/${t.total.coerceAtLeast(0)} parts · $size" + (if (t.percent >= 0) " · ${t.percent}%" else "")
        else "$size" + (if (t.total > 0) " of " + Formatter.formatFileSize(this, t.total) else "") +
            (if (t.percent >= 0) " · ${t.percent}%" else "")

    override fun onStart() {
        super.onStart()
        DownloadRegistry.addListener(listener)
        refresh()
    }

    override fun onStop() {
        DownloadRegistry.removeListener(listener)
        super.onStop()
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(DownloadRegistry.tasks)
        empty.visibility = if (DownloadRegistry.tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Locked folder")
        menu.add(0, 2, 0, "Clear finished")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> startActivity(Intent(this, VaultActivity::class.java))
            2 -> DownloadRegistry.clearFinished()
        }
        return true
    }
}
