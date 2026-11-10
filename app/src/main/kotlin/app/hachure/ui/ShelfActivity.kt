package app.hachure.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import app.hachure.R
import app.hachure.store.CoverPattern
import app.hachure.store.Notebook
import app.hachure.store.NotebookStore
import app.hachure.store.ShelfFilter
import app.hachure.store.forShelf
import app.hachure.store.relativeAge
import java.io.File
import java.util.Calendar

/**
 * Launch screen: the shelf of notebook covers (concept E). Built from plain views in code; the
 * whole screen is black, white and 2 px rules so every redraw is a clean partial refresh.
 */
class ShelfActivity : Activity() {

    private lateinit var store: NotebookStore
    private lateinit var prefs: Prefs
    private lateinit var grid: GridView
    private lateinit var countLabel: TextView
    private val chips = LinkedHashMap<ShelfFilter, TextView>()
    private var filter = ShelfFilter.RECENT
    private var shown: List<Notebook> = emptyList()
    private var dpmm = 227f / 25.4f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NotebookStore(notebooksDir(this))
        prefs = Prefs(this)
        dpmm = resources.displayMetrics.xdpi.let { if (it in 100f..700f) it else 227f } / 25.4f
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun mm(v: Float) = (v * dpmm).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(mm(5f), mm(4f), mm(3f), mm(2f))
        }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.shelf_title)
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, mm(6f).toFloat())
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings)
                setBackgroundColor(Color.WHITE)
                contentDescription = getString(R.string.shelf_settings)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(mm(2f), mm(2f), mm(2f), mm(2f))
                setOnClickListener { showSettings(it) }
            },
            LinearLayout.LayoutParams(mm(9f), mm(9f)),
        )
        root.addView(header)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(mm(5f), 0, mm(5f), mm(3f))
        }
        for ((f, label) in listOf(
            ShelfFilter.RECENT to R.string.shelf_recent,
            ShelfFilter.A_TO_Z to R.string.shelf_az,
            ShelfFilter.ARCHIVED to R.string.shelf_archived,
        )) {
            val chip = TextView(this).apply {
                text = getString(label)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, mm(3.2f).toFloat())
                setPadding(mm(3.5f), 0, mm(3.5f), 0)
                isClickable = true
                setOnClickListener {
                    filter = f
                    refresh()
                }
            }
            chips[f] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, mm(9f)).apply { rightMargin = mm(2f) })
        }
        root.addView(chipRow)
        root.addView(rule())

        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = mm(5f)
            verticalSpacing = mm(5f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(mm(5f), mm(5f), mm(5f), mm(5f))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = ShelfAdapter()
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(rule())
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(mm(5f), mm(2.5f), mm(5f), mm(2.5f))
        }
        footer.addView(caption(getString(R.string.shelf_hint)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countLabel = caption("")
        footer.addView(countLabel)
        root.addView(footer)
        return root
    }

    private fun rule() = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4)
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, mm(2.8f).toFloat())
    }

    private fun refresh() {
        val all = store.list()
        shown = all.forShelf(filter)
        for ((f, chip) in chips) {
            val on = f == filter
            chip.setTextColor(if (on) Color.WHITE else Color.BLACK)
            chip.background = GradientDrawable().apply {
                cornerRadius = mm(4.5f).toFloat()
                setColor(if (on) Color.BLACK else Color.WHITE)
                setStroke(4, Color.BLACK)
            }
        }
        countLabel.text = resources.getQuantityString(R.plurals.shelf_count, all.count { !it.archived }, all.count { !it.archived })
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    /** The New notebook tile comes first, except in Archived. */
    private fun hasNewTile() = filter != ShelfFilter.ARCHIVED

    private inner class ShelfAdapter : BaseAdapter() {
        override fun getCount() = shown.size + if (hasNewTile()) 1 else 0
        override fun getItem(position: Int): Any? = notebookAt(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? CoverView) ?: CoverView(this@ShelfActivity, dpmm)
            val nb = notebookAt(position)
            v.isNewTile = nb == null
            if (nb == null) {
                v.title = getString(R.string.shelf_new)
                v.setOnClickListener { createAndOpen() }
                v.setOnLongClickListener(null)
            } else {
                v.title = nb.title
                v.pattern = nb.cover
                v.caption = resources.getQuantityString(R.plurals.cover_caption, nb.pageCount, nb.pageCount, age(nb.modifiedMs))
                v.contentDescription = "${nb.title}, ${v.caption}"
                v.setOnClickListener { open(nb) }
                v.setOnLongClickListener { showNotebookMenu(it, nb); true }
            }
            v.invalidate()
            return v
        }
    }

    private fun notebookAt(position: Int): Notebook? {
        val i = if (hasNewTile()) position - 1 else position
        return if (i in shown.indices) shown[i] else null
    }

    private fun age(ms: Long): String {
        val cal = Calendar.getInstance()
        return relativeAge(ms, System.currentTimeMillis()) { t ->
            cal.timeInMillis = t
            cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR)
        }
    }

    private fun createAndOpen() {
        val n = store.list().size + 1
        val cover = CoverPattern.entries[(n - 1) % CoverPattern.entries.size]
        open(store.create(getString(R.string.shelf_new_title, n), cover))
    }

    private fun open(nb: Notebook) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTEBOOK, nb.id))
        overridePendingTransition(0, 0)
    }

    private fun showNotebookMenu(anchor: View, nb: Notebook) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.nb_rename)
        val covers = menu.menu.addSubMenu(0, 2, 1, R.string.nb_cover)
        CoverPattern.entries.forEach { covers.add(1, 100 + it.ordinal, it.ordinal, coverLabel(it)).isCheckable = true }
        covers.setGroupCheckable(1, true, true)
        covers.findItem(100 + nb.cover.ordinal)?.isChecked = true
        menu.menu.add(0, 3, 2, if (nb.archived) R.string.nb_unarchive else R.string.nb_archive)
        menu.menu.add(0, 4, 3, R.string.nb_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> rename(nb)
                3 -> { store.setArchived(nb.id, !nb.archived); refresh() }
                4 -> confirmDelete(nb)
                in 100 until 100 + CoverPattern.entries.size -> {
                    store.setCover(nb.id, CoverPattern.entries[item.itemId - 100])
                    refresh()
                }
            }
            true
        }
        menu.show()
    }

    private fun coverLabel(c: CoverPattern) = when (c) {
        CoverPattern.DIAGONAL -> R.string.cover_diagonal
        CoverPattern.DIAGONAL_BACK -> R.string.cover_diagonal_back
        CoverPattern.CROSS -> R.string.cover_cross
        CoverPattern.HORIZONTAL -> R.string.cover_horizontal
        CoverPattern.DOTS -> R.string.cover_dots
    }

    private fun rename(nb: Notebook) {
        val input = EditText(this).apply {
            setText(nb.title)
            setSelection(nb.title.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nb_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.rename(nb.id, input.text.toString())
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(nb: Notebook) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.nb_delete_confirm, nb.title))
            .setPositiveButton(R.string.nb_delete) { _, _ ->
                store.delete(nb.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSettings(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.menu_labels).setCheckable(true).isChecked = prefs.showLabels
        menu.menu.add(0, 2, 1, R.string.menu_grid).setCheckable(true).isChecked = prefs.dotGrid
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> prefs.showLabels = !prefs.showLabels
                2 -> prefs.dotGrid = !prefs.dotGrid
            }
            true
        }
        menu.show()
    }

    companion object {
        fun notebooksDir(activity: Activity) = File(activity.filesDir, "notebooks")
    }
}
