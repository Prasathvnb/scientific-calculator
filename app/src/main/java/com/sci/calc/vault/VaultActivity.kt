package com.sci.calc.vault

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.sci.calc.data.Db
import com.sci.calc.data.VaultFile
import com.sci.calc.player.PlayerActivity
import com.sci.calc.security.CryptoManager
import com.sci.calc.security.SecureActivity
import java.io.InputStream
import javax.crypto.CipherInputStream
import kotlin.concurrent.thread

/**
 * The locked folder: every downloaded file, encrypted on the memory card.
 * Files open only here (built-in player / image viewer). "Export" writes a normal,
 * decrypted copy to a place the user chooses.
 */
class VaultActivity : SecureActivity() {

    private lateinit var adapter: ArrayAdapter<VaultFile>
    private lateinit var info: TextView
    private val db by lazy { Db.get(this) }
    private var exporting: VaultFile? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = exporting ?: return@registerForActivityResult
        exporting = null
        if (uri == null) return@registerForActivityResult
        thread {
            val ok = runCatching {
                openPlain(this, f).use { i -> contentResolver.openOutputStream(uri)!!.use { o -> i.copyTo(o, 256 * 1024) } }
            }.isSuccess
            runOnUiThread { toast(if (ok) "Exported" else "Export failed") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Locked folder"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info = TextView(this).apply {
            textSize = 12f; alpha = 0.7f
            setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        }
        val list = ListView(this)
        root.addView(info)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter = object : ArrayAdapter<VaultFile>(this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val f = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = (if (f.encrypted) "🔒 " else "") + f.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    Formatter.formatFileSize(context, f.size) + " · " + DateUtils.getRelativeTimeSpanString(f.time)
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> open(adapter.getItem(pos)!!) }
        list.setOnItemLongClickListener { _, _, pos, _ -> actions(adapter.getItem(pos)!!); true }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        adapter.clear()
        adapter.addAll(db.files())
        info.text = (if (adapter.count == 0) "No files yet. Downloads appear here.\n" else "Tap to open · long-press for more\n") +
            "Location: " + Storage.describeTarget(this)
    }

    private fun open(f: VaultFile) {
        when {
            f.mime.startsWith("video") || f.mime.startsWith("audio") || f.name.endsWith(".ts") || f.name.endsWith(".mp4") ->
                startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_FILE_ID, f.id))
            f.mime.startsWith("image") -> showImage(f)
            else -> actions(f)
        }
    }

    private fun showImage(f: VaultFile) {
        thread {
            val bmp = runCatching { openPlain(this, f).use { BitmapFactory.decodeStream(it) } }.getOrNull()
            runOnUiThread {
                if (bmp == null) { toast("Can't open image"); return@runOnUiThread }
                val iv = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }
                AlertDialog.Builder(this).setView(FrameLayout(this).apply { addView(iv) }).setPositiveButton("Close", null).show()
            }
        }
    }

    private fun actions(f: VaultFile) {
        val opts = arrayOf("Open", "Rename", "Export decrypted copy", "Delete")
        AlertDialog.Builder(this).setTitle(f.name).setItems(opts) { _, i ->
            when (i) {
                0 -> if (f.mime.startsWith("image")) showImage(f)
                else startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_FILE_ID, f.id))
                1 -> rename(f)
                2 -> { exporting = f; goingExternal(); exportLauncher.launch(f.name) }
                3 -> AlertDialog.Builder(this).setMessage("Delete \"${f.name}\" permanently?")
                    .setPositiveButton("Delete") { _, _ ->
                        Storage.delete(this, f); db.deleteFile(f.id); reload()
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun rename(f: VaultFile) {
        val input = EditText(this).apply { setText(f.name) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this).setTitle("Rename")
            .setView(FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) })
            .setPositiveButton("Save") { _, _ ->
                db.renameFile(f.id, Storage.safeName(input.text.toString())); reload()
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Storage settings")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> startActivity(Intent(this, com.sci.calc.settings.SettingsActivity::class.java))
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** Opens a file as plain bytes, decrypting if needed. */
        fun openPlain(ctx: Context, f: VaultFile): InputStream {
            val pfd = Storage.openPfd(ctx, f.location) ?: error("missing")
            val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            if (!f.encrypted) return input
            val header = ByteArray(CryptoManager.HEADER_SIZE)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (!CryptoManager.isEncryptedHeader(header)) error("bad header")
            return CipherInputStream(input, CryptoManager.decryptCipherAt(ctx, header.copyOfRange(4, 20), 0))
        }
    }
}
