package com.sci.calc.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sci.calc.data.Db
import com.sci.calc.data.Entry
import com.sci.calc.security.SecureActivity

/** Bookmarks or history list. Tapping an entry returns its URL to the browser. */
class LibraryActivity : SecureActivity() {

    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<Entry>
    private val db by lazy { Db.get(this) }
    private val mode by lazy { intent.getIntExtra(EXTRA_MODE, MODE_BOOKMARKS) }
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (mode == MODE_HISTORY) "History" else "Bookmarks"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "Search"; setSingleLine(); setPadding(pad * 2, pad, pad * 2, pad)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter = s.toString(); reload() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        list = ListView(this)
        root.addView(search)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter = object : ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val e = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = e.title.ifBlank { e.url }
                val host = Uri.parse(e.url).host ?: e.url
                v.findViewById<TextView>(android.R.id.text2).text =
                    if (mode == MODE_HISTORY) "$host · " + DateUtils.getRelativeTimeSpanString(e.time) else e.url
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            setResult(Activity.RESULT_OK, Intent().setData(Uri.parse(adapter.getItem(pos)!!.url)))
            finish()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos)!!
            AlertDialog.Builder(this).setTitle(e.title.ifBlank { e.url })
                .setItems(arrayOf("Delete")) { _, _ ->
                    if (mode == MODE_HISTORY) db.deleteHistory(e.id) else db.deleteBookmark(e.id)
                    reload()
                }.show()
            true
        }
        reload()
    }

    private fun reload() {
        adapter.clear()
        val items = if (mode == MODE_HISTORY) db.history(filter)
        else db.bookmarks().filter { filter.isBlank() || it.title.contains(filter, true) || it.url.contains(filter, true) }
        adapter.addAll(items)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (mode == MODE_HISTORY) menu.add(0, 1, 0, "Clear history")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> AlertDialog.Builder(this).setMessage("Clear all browsing history?")
                .setPositiveButton("Clear") { _, _ -> db.clearHistory(); reload() }
                .setNegativeButton("Cancel", null).show()
        }
        return true
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BOOKMARKS = 0
        const val MODE_HISTORY = 1
    }
}
