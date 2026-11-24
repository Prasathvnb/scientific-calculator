package com.sci.calc.browser

import android.app.Dialog
import android.graphics.Color
import android.graphics.Matrix
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.sci.calc.R

/**
 * Chrome-style tab overview: a grid of page previews. Tap to switch,
 * × or swipe sideways to close.
 */
class TabGridDialog(
    private val activity: AppCompatActivity,
    private val tabs: List<Tab>,
    private val current: Tab?,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    private val onNew: (incognito: Boolean) -> Unit,
    private val onCloseAll: () -> Unit,
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    private lateinit var titleView: TextView
    private lateinit var adapter: Adapter

    fun show() {
        val d = activity.resources.displayMetrics.density
        val bg = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onBg = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Top bar: "N tabs" · Close all · Done
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        titleView = TextView(activity).apply { textSize = 18f; setTextColor(onBg) }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(textButton("Close all") { onCloseAll(); dialog.dismiss() })
        bar.addView(textButton("Done") { dialog.dismiss() })
        root.addView(bar)

        val list = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, if (resources.configuration.screenWidthDp >= 600) 3 else 2)
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), (6 * d).toInt())
            clipToPadding = false
        }
        adapter = Adapter()
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos in tabs.indices) closeAt(pos)
            }
        }).attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom bar: New tab · New private tab
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        bottom.addView(MaterialButton(activity).apply {
            text = "+ New tab"
            setOnClickListener { onNew(false); dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * d).toInt() })
        bottom.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+ Private tab"
            setOnClickListener { onNew(true); dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * d).toInt() })
        root.addView(bottom)

        dialog.setContentView(root)
        dialog.window?.apply {
            setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = bg
        }
        updateTitle()
        dialog.show()
        // Scroll to the current tab.
        tabs.indexOf(current).takeIf { it >= 0 }?.let { list.scrollToPosition(it) }
    }

    private fun textButton(label: String, click: () -> Unit) =
        MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setOnClickListener { click() }
        }

    private fun updateTitle() {
        titleView.text = if (tabs.size == 1) "1 tab" else "${tabs.size} tabs"
    }

    private fun closeAt(pos: Int) {
        val t = tabs[pos]
        val wasLast = tabs.size == 1
        onClose(t)
        if (wasLast) { dialog.dismiss(); return }
        adapter.notifyItemRemoved(pos)
        adapter.notifyItemRangeChanged(0, tabs.size)
        updateTitle()
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val header: View = v.findViewById(R.id.header)
        val favicon: ImageView = v.findViewById(R.id.favicon)
        val title: TextView = v.findViewById(R.id.title)
        val close: ImageButton = v.findViewById(R.id.close)
        val thumb: ImageView = v.findViewById(R.id.thumb)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = tabs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tab_grid, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val t = tabs[position]
            val d = activity.resources.displayMetrics.density
            h.title.text = (if (t.incognito) "Private · " else "") + t.title.ifBlank { t.url.ifBlank { "New tab" } }
            if (t.favicon != null) h.favicon.setImageBitmap(t.favicon) else h.favicon.setImageResource(R.drawable.ic_info)
            val accent = MaterialColors.getColor(h.card, androidx.appcompat.R.attr.colorPrimary)
            h.card.strokeWidth = if (t == current) (3 * d).toInt() else 0
            h.card.strokeColor = accent
            if (t.incognito) {
                h.header.setBackgroundColor(0xFF3C3C46.toInt())
                h.title.setTextColor(Color.WHITE)
            } else {
                h.header.background = null
                h.title.setTextColor(MaterialColors.getColor(h.card, com.google.android.material.R.attr.colorOnSurface))
            }
            val bmp = t.thumbnail
            h.thumb.setImageBitmap(bmp)
            // Show the top of the page, scaled to the card's width (like Chrome).
            h.thumb.post {
                if (bmp != null && h.thumb.width > 0) {
                    val s = h.thumb.width.toFloat() / bmp.width
                    h.thumb.imageMatrix = Matrix().apply { setScale(s, s) }
                }
            }
            h.card.setOnClickListener { onSelect(t); dialog.dismiss() }
            h.close.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos in tabs.indices) closeAt(pos)
            }
        }
    }
}
