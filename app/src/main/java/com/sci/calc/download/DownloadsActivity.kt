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
            text = "No downloads in this session.\nFinished files are in the Locked folder."
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
                bar.visibility = if (t.status == Status.RUNNING || t.status == Status.QUEUED) View.VISIBLE else View.GONE
                bar.isIndeterminate = t.percent < 0
                if (t.percent >= 0) bar.progress = t.percent
                val size = Formatter.formatFileSize(context, t.bytes)
                row.findViewWithTag<TextView>("info").text = when (t.status) {
                    Status.QUEUED -> "Waiting…"
                    Status.RUNNING -> if (t.isHls) "${t.done}/${t.total.coerceAtLeast(0)} parts · $size" else
                        "$size" + (if (t.total > 0) " of " + Formatter.formatFileSize(context, t.total) else "") +
                            (if (t.percent >= 0) " · ${t.percent}%" else "")
                    Status.DONE -> "Done · $size · in Locked folder"
                    Status.FAILED -> "Failed: ${t.error} · tap to retry"
                    Status.CANCELLED -> "Cancelled · tap to retry"
                }
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val t = adapter.getItem(pos)!!
            val opts = when (t.status) {
                Status.RUNNING, Status.QUEUED -> arrayOf("Cancel", "Copy link")
                Status.DONE -> arrayOf("Open Locked folder", "Copy link")
                else -> arrayOf("Retry", "Copy link", "Remove from list")
            }
            AlertDialog.Builder(this).setTitle(t.name).setItems(opts) { _, i ->
                when (opts[i]) {
                    "Cancel" -> { t.cancelRequested = true }
                    "Retry" -> DownloadService.start(this, t)
                    "Open Locked folder" -> startActivity(Intent(this, VaultActivity::class.java))
                    "Copy link" -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", t.url))
                    "Remove from list" -> { DownloadRegistry.tasks.remove(t); refresh() }
                }
            }.show()
        }
    }

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
