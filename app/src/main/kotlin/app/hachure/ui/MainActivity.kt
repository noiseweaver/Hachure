package app.hachure.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.hachure.BuildConfig
import app.hachure.R
import app.hachure.ink.InkDiagnostics
import app.hachure.ink.OnyxInkBackend
import app.hachure.ink.SnapPreview
import app.hachure.geometry.Guides
import app.hachure.model.DotGrid
import app.hachure.model.Ruler
import app.hachure.model.Tool
import app.hachure.model.WidthPreset
import app.hachure.store.NotebookStore
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var canvas: CanvasView
    private lateinit var dock: ToolDock
    private lateinit var strip: GuideStrip
    private lateinit var debugOverlay: TextView
    private lateinit var prefs: Prefs
    private var lastStats: String? = null
    private lateinit var systemUi: SystemUiWatcher

    private lateinit var store: NotebookStore
    private var notebookId: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private val saveSoon = Runnable { saveNow() }
    private var dirty = false
    private var saveQueued = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { pushExcludeRects() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        canvas = findViewById(R.id.canvas)
        dock = findViewById(R.id.dock)
        strip = findViewById(R.id.guide_strip)
        debugOverlay = findViewById(R.id.debug_overlay)

        store = NotebookStore(ShelfActivity.notebooksDir(this))
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK)
        loadNotebook()

        canvas.tool = prefs.tool
        canvas.widthPreset = prefs.width
        canvas.dotGrid = if (prefs.dotGrid) DotGrid() else null
        canvas.eraserTrail = prefs.eraserTrail

        canvas.guides.snapEnabled = prefs.snap
        canvas.snapPreview = prefs.snapPreview
        canvas.guides.lockAll = prefs.lockAll
        canvas.guides.angleSnap = prefs.angleSnap
        canvas.guides.onSelectionChanged = { updateStrip() }
        canvas.guides.onAngleEntry = { askAngle(it) }
        strip.showLabels = prefs.showLabels
        strip.onAction = { onStripAction(it) }

        dock.showLabels = prefs.showLabels
        dock.restore(prefs.dock, prefs.puckFx, prefs.puckFy)
        dock.onDockChanged = { d, fx, fy ->
            prefs.dock = d
            prefs.puckFx = fx
            prefs.puckFy = fy
        }
        dock.onItem = { item, anchor -> onDockItem(item, anchor) }

        canvas.statsListener = CanvasView.StatsListener { points, penUpMs, renderMs ->
            lastStats = getString(R.string.debug_last_stroke, points, penUpMs, renderMs)
            if (debugVisible()) renderDebug()
        }
        canvas.historyChanged = { updateDock() }
        canvas.pageEdited = {
            dirty = true
            // Not a debounce: steady hatching (a stroke every few hundred ms) must not keep
            // pushing the save back, so one is scheduled only when none is waiting.
            if (!saveQueued) {
                saveQueued = true
                main.postDelayed(saveSoon, SAVE_DELAY_MS)
            }
        }
        canvas.backendChanged = {
            pushExcludeRects()
            if (debugVisible()) renderDebug()
        }

        systemUi = SystemUiWatcher(this) { reason ->
            canvas.backend.setSystemUiOpen(reason)
            if (debugVisible()) renderDebug()
        }
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        updateDock()
    }

    override fun onResume() {
        super.onResume()
        systemUi.start()
        canvas.backend.resume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        canvas.backend.setWindowFocused(hasFocus)
        // Way out if a "closed" broadcast was missed: opening and closing any of our menus
        // (or coming back to the app) returns focus and clears the block.
        if (hasFocus && ::systemUi.isInitialized) systemUi.reset()
        if (debugVisible()) renderDebug()
    }

    override fun onPause() {
        systemUi.stop()
        canvas.backend.pause()
        // Boox freezes or kills background apps quickly, so the save must be on disk before
        // this returns. A page is small (14 bytes a point); this takes a few ms.
        saveNow(wait = true)
        super.onPause()
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        main.removeCallbacks(saveSoon)
        // Lets a pending save finish; nothing new is queued after this.
        saver.shutdown()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun loadNotebook() {
        val id = notebookId ?: return
        try {
            store.loadPage(id)?.let { canvas.setPage(it) }
        } catch (t: Throwable) {
            // Keep the file untouched: a failed load must never be followed by a save over it.
            notebookId = null
            Toast.makeText(this, getString(R.string.load_failed, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Snapshot on the UI thread, write on the saver thread. Saves are serialised. With [wait]
     * the call returns only once every queued save has been written (or after 3 s).
     */
    private fun saveNow(wait: Boolean = false) {
        main.removeCallbacks(saveSoon)
        saveQueued = false
        val id = notebookId ?: return
        if (dirty) {
            val snapshot = canvas.pageSnapshot()
            if (snapshot != null) {
                dirty = false
                saver.execute {
                    try {
                        store.savePage(id, snapshot)
                    } catch (t: Throwable) {
                        main.post {
                            dirty = true
                            Toast.makeText(this, getString(R.string.save_failed, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        if (wait && !saver.isShutdown) {
            // An empty task behind the queued saves: when it has run, they are on disk.
            runCatching { saver.submit {}.get(3, TimeUnit.SECONDS) }
        }
    }

    private fun onDockItem(item: ToolDock.Item, anchor: View) {
        when (item) {
            ToolDock.Item.SHELF -> {
                saveNow(wait = true)
                finish()
                return
            }
            ToolDock.Item.PENCIL -> selectBrush(Tool.PENCIL)
            ToolDock.Item.FOUNTAIN -> selectBrush(Tool.FOUNTAIN)
            ToolDock.Item.STYLE -> pickBrush(anchor)
            ToolDock.Item.LINE -> toggleMode(CanvasView.Mode.LINE)
            ToolDock.Item.ERASER -> toggleMode(CanvasView.Mode.ERASE)
            ToolDock.Item.WIDTH -> {
                val next = WidthPreset.entries[(canvas.widthPreset.ordinal + 1) % WidthPreset.entries.size]
                canvas.widthPreset = next
                prefs.width = next
            }
            ToolDock.Item.RULER -> {
                if (!canvas.guides.addRuler()) {
                    Toast.makeText(this, getString(R.string.ruler_limit, Guides.MAX_RULERS), Toast.LENGTH_SHORT).show()
                }
            }
            ToolDock.Item.PERSPECTIVE -> canvas.guides.togglePerspectiveStrip()
            ToolDock.Item.SNAP -> {
                prefs.snap = !prefs.snap
                canvas.guides.snapEnabled = prefs.snap
                canvas.syncSnapBands()
            }
            ToolDock.Item.LOCK -> {
                prefs.lockAll = !prefs.lockAll
                canvas.guides.lockAll = prefs.lockAll
            }
            ToolDock.Item.UNDO -> canvas.undo()
            ToolDock.Item.REDO -> canvas.redo()
            ToolDock.Item.CLEAN -> canvas.cleanScreen()
            ToolDock.Item.MORE -> showMore(anchor)
        }
        updateDock()
    }

    private fun selectBrush(t: Tool) {
        canvas.tool = t
        canvas.mode = CanvasView.Mode.DRAW
        prefs.tool = t
        if (t != Tool.PENCIL && t != Tool.FOUNTAIN) prefs.extraTool = t
    }

    /** Line and eraser draw with the current brush settings; tapping again goes back to drawing. */
    private fun toggleMode(m: CanvasView.Mode) {
        canvas.mode = if (canvas.mode == m) CanvasView.Mode.DRAW else m
    }

    private fun updateDock() {
        dock.setState(
            ToolDock.State(
                tool = canvas.tool,
                mode = canvas.mode,
                width = canvas.widthPreset,
                extraTool = prefs.extraTool,
                canUndo = canvas.canUndo,
                canRedo = canvas.canRedo,
                snap = prefs.snap,
                lockAll = prefs.lockAll,
                perspective = (canvas.guides.perspective?.points ?: 0) > 0,
            ),
        )
    }

    private fun updateStrip() {
        val g = canvas.guides
        val ruler = g.selected
        val item = g.perspectiveItem
        val ps = g.perspective
        when {
            item != null && ps != null -> strip.showPerspective(ps, item)
            ruler != null -> strip.showRuler(ruler, prefs.angleSnap, ps)
            else -> strip.hide()
        }
        // The rail's Perspective button shows whether perspective is on.
        updateDock()
    }

    private fun onStripAction(a: GuideStrip.Action) {
        val g = canvas.guides
        when (a) {
            GuideStrip.Action.ANGLE -> g.selected?.let { askAngle(it) }
            GuideStrip.Action.ANGLE_SNAP -> {
                prefs.angleSnap = !prefs.angleSnap
                g.angleSnap = prefs.angleSnap
                updateStrip()
            }
            GuideStrip.Action.HATCH -> g.cycleHatch()
            GuideStrip.Action.SPACING_DOWN -> g.changeSpacing(-1)
            GuideStrip.Action.SPACING_UP -> g.changeSpacing(1)
            GuideStrip.Action.SPACING -> {}
            GuideStrip.Action.AIM -> g.cycleAim()
            GuideStrip.Action.ITEM -> g.cyclePerspectiveItem()
            GuideStrip.Action.POINTS -> g.cyclePoints()
            GuideStrip.Action.LINES -> g.toggleLines()
            GuideStrip.Action.FEWER -> g.changeRays(-1)
            GuideStrip.Action.MORE -> g.changeRays(1)
            GuideStrip.Action.DENSITY -> {}
            GuideStrip.Action.LOCK -> if (g.perspectiveItem != null) g.togglePerspectiveLock() else g.toggleLock()
            GuideStrip.Action.DELETE -> g.deleteSelected()
            GuideStrip.Action.DONE -> g.deselect()
        }
    }

    /** Numeric angle entry. The dialog takes window focus, which pauses inking by itself. */
    private fun askAngle(r: Ruler) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(GuideController.angleLabel(r).removeSuffix("°"))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.angle_title)
            .setMessage(R.string.angle_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deg = input.text.toString().replace(',', '.').toFloatOrNull()
                if (deg != null && deg in 0f..360f) canvas.guides.setDisplayAngle(r.id, deg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Inking is paused while a menu covers the canvas. */
    private fun withInkPaused(menu: PopupMenu) {
        canvas.backend.setEnabled(false)
        menu.setOnDismissListener { canvas.backend.setEnabled(true) }
        menu.show()
    }

    private fun pickBrush(anchor: View) {
        val menu = PopupMenu(this, anchor)
        Tool.entries.forEach { menu.menu.add(0, it.ordinal, it.ordinal, getString(brushLabel(it))) }
        menu.setOnMenuItemClickListener { item ->
            selectBrush(Tool.entries[item.itemId])
            updateDock()
            true
        }
        withInkPaused(menu)
    }

    private fun brushLabel(t: Tool) = when (t) {
        Tool.PENCIL -> R.string.brush_pencil
        Tool.FOUNTAIN -> R.string.brush_fountain
        Tool.MARKER -> R.string.brush_marker
        Tool.BRUSH -> R.string.brush_brush
        Tool.CHARCOAL -> R.string.brush_charcoal
        Tool.DASH -> R.string.brush_dash
        Tool.CHARCOAL2 -> R.string.brush_charcoal2
    }

    private fun showMore(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val m = menu.menu
        m.add(0, M_LABELS, 0, R.string.menu_labels).setCheckable(true).isChecked = prefs.showLabels
        m.add(0, M_GRID, 1, R.string.menu_grid).setCheckable(true).isChecked = prefs.dotGrid
        m.add(0, M_TRAIL, 2, R.string.menu_trail).setCheckable(true).isChecked = prefs.eraserTrail
        val preview = m.addSubMenu(0, 0, 2, R.string.menu_snap_preview)
        for ((i, mode) in SnapPreview.entries.withIndex()) {
            preview.add(1, M_PREVIEW + i, i, previewLabel(mode)).setCheckable(true).isChecked = prefs.snapPreview == mode
        }
        m.add(0, M_CLEAR, 2, R.string.menu_clear)
        m.add(0, M_DEBUG, 3, R.string.menu_debug).setCheckable(true).isChecked = debugVisible()
        onyx()?.let { o ->
            if (debugVisible()) {
                m.add(0, M_RELEASE, 4, R.string.menu_release).setCheckable(true).isChecked = o.releaseWhenPenAway
                m.add(0, M_COORDS, 5, R.string.menu_coords).setCheckable(true).isChecked = o.pointsAreScreenAbsolute
            }
        }
        menu.setOnMenuItemClickListener { item ->
            // The Snap preview entry only opens its submenu.
            if (item.hasSubMenu()) return@setOnMenuItemClickListener false
            when (item.itemId) {
                M_LABELS -> {
                    prefs.showLabels = !prefs.showLabels
                    dock.showLabels = prefs.showLabels
                    strip.showLabels = prefs.showLabels
                }
                M_GRID -> {
                    prefs.dotGrid = !prefs.dotGrid
                    canvas.dotGrid = if (prefs.dotGrid) DotGrid() else null
                }
                M_TRAIL -> {
                    prefs.eraserTrail = !prefs.eraserTrail
                    canvas.eraserTrail = prefs.eraserTrail
                }
                in M_PREVIEW until M_PREVIEW + SnapPreview.entries.size -> {
                    prefs.snapPreview = SnapPreview.entries[item.itemId - M_PREVIEW]
                    canvas.snapPreview = prefs.snapPreview
                }
                M_CLEAR -> canvas.clearPage()
                M_DEBUG -> toggleDebug()
                M_RELEASE -> onyx()?.let { it.releaseWhenPenAway = !it.releaseWhenPenAway }
                M_COORDS -> onyx()?.let { it.pointsAreScreenAbsolute = !it.pointsAreScreenAbsolute }
            }
            if (debugVisible()) renderDebug()
            updateDock()
            true
        }
        withInkPaused(menu)
    }

    private fun previewLabel(m: SnapPreview) = when (m) {
        SnapPreview.AT_PEN_UP -> R.string.preview_a
        SnapPreview.TOUCH_PATH -> R.string.preview_b
        SnapPreview.RENDER_SWITCH -> R.string.preview_c
    }

    private fun debugVisible() = debugOverlay.visibility == View.VISIBLE

    private fun onyx(): OnyxInkBackend? = canvas.backend as? OnyxInkBackend

    private fun toggleDebug() {
        val show = !debugVisible()
        debugOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) renderDebug()
    }

    private fun renderDebug() {
        debugOverlay.text = buildString {
            appendLine(getString(R.string.debug_backend, canvas.backend.name))
            InkDiagnostics.backendFallbackReason?.let { appendLine("fallback: $it") }
            appendLine("${InkDiagnostics.device}, bypass ${InkDiagnostics.hiddenApiBypass}")
            appendLine(
                "dpi ${String.format(Locale.US, "%.1f", canvas.reportedDpi())}  " +
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            canvas.backend.debugInfo().forEach { appendLine(it) }
            appendLine(canvas.eraseDebug())
            appendLine(canvas.guideDebug())
            canvas.rendererError()?.let { appendLine("render fallback: $it") }
            append(lastStats ?: getString(R.string.debug_no_strokes))
        }
    }

    /**
     * Screen rects of everything on top of the canvas. Window coordinates equal screen
     * coordinates for this full-screen activity, which is what the Onyx TouchHelper expects.
     */
    private fun pushExcludeRects() {
        val rects = ArrayList<Rect>(dock.excludeRects())
        if (debugVisible()) {
            val r = Rect()
            if (debugOverlay.getGlobalVisibleRect(r)) rects += r
        }
        if (strip.visibility == View.VISIBLE) {
            val r = Rect()
            if (strip.getGlobalVisibleRect(r)) rects += r
        }
        canvas.backend.setExcludeRects(rects)
    }

    companion object {
        const val EXTRA_NOTEBOOK = "notebook"

        /** Save at most this long after an edit, and always (blocking) on leaving the screen. */
        private const val SAVE_DELAY_MS = 800L

        private const val M_LABELS = 1
        private const val M_GRID = 2
        private const val M_CLEAR = 3
        private const val M_DEBUG = 4
        private const val M_RELEASE = 5
        private const val M_COORDS = 6
        private const val M_TRAIL = 7
        private const val M_PREVIEW = 20
    }
}
