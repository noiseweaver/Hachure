package app.hachure.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import app.hachure.BuildConfig
import app.hachure.R
import app.hachure.ink.InkDiagnostics
import app.hachure.ink.OnyxInkBackend
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var canvas: CanvasView
    private lateinit var debugOverlay: TextView
    private lateinit var btnLiveStyle: Button
    private lateinit var btnCoords: Button
    private var lastStats: String? = null

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { pushExcludeRects() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvas = findViewById(R.id.canvas)
        debugOverlay = findViewById(R.id.debug_overlay)
        btnLiveStyle = findViewById(R.id.btn_live_style)
        btnCoords = findViewById(R.id.btn_coords)

        findViewById<Button>(R.id.btn_clear).setOnClickListener { canvas.clearPage() }
        findViewById<Button>(R.id.btn_clean).setOnClickListener { canvas.cleanScreen() }
        findViewById<Button>(R.id.btn_debug).setOnClickListener { toggleDebug() }
        btnLiveStyle.setOnClickListener { pickLiveStyle() }
        btnCoords.setOnClickListener { toggleCoords() }

        canvas.statsListener = CanvasView.StatsListener { points, penUpMs, renderMs ->
            lastStats = getString(R.string.debug_last_stroke, points, penUpMs, renderMs)
            if (debugVisible()) renderDebug()
        }
        canvas.backendChanged = {
            pushExcludeRects()
            updateStyleButton()
            if (debugVisible()) renderDebug()
        }

        canvas.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        updateStyleButton()
    }

    override fun onResume() {
        super.onResume()
        canvas.backend.resume()
    }

    override fun onPause() {
        canvas.backend.pause()
        super.onPause()
    }

    override fun onDestroy() {
        canvas.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        super.onDestroy()
    }

    private fun debugVisible() = debugOverlay.visibility == View.VISIBLE

    private fun onyx(): OnyxInkBackend? = canvas.backend as? OnyxInkBackend

    private fun toggleDebug() {
        val show = !debugVisible()
        debugOverlay.visibility = if (show) View.VISIBLE else View.GONE
        btnCoords.visibility = if (show && onyx() != null) View.VISIBLE else View.GONE
        if (show) renderDebug()
    }

    /** Pick a live A2 style. Inking is paused while the menu covers the canvas. */
    private fun pickLiveStyle() {
        val o = onyx() ?: return
        val menu = PopupMenu(this, btnLiveStyle)
        OnyxInkBackend.LiveStyle.entries.forEach { menu.menu.add(0, it.ordinal, it.ordinal, it.label) }
        canvas.backend.setEnabled(false)
        menu.setOnMenuItemClickListener { item ->
            o.liveStyle = OnyxInkBackend.LiveStyle.entries[item.itemId]
            updateStyleButton()
            if (debugVisible()) renderDebug()
            true
        }
        menu.setOnDismissListener { canvas.backend.setEnabled(true) }
        menu.show()
    }

    private fun updateStyleButton() {
        val o = onyx()
        btnLiveStyle.visibility = if (o != null) View.VISIBLE else View.GONE
        if (o != null) btnLiveStyle.text = getString(R.string.live_style, o.liveStyle.label)
    }

    private fun toggleCoords() {
        val o = onyx() ?: return
        o.pointsAreScreenAbsolute = !o.pointsAreScreenAbsolute
        renderDebug()
    }

    private fun renderDebug() {
        onyx()?.let {
            btnCoords.text = getString(if (it.pointsAreScreenAbsolute) R.string.coords_screen else R.string.coords_view)
        }
        debugOverlay.text = buildString {
            appendLine(getString(R.string.debug_backend, canvas.backend.name))
            InkDiagnostics.backendFallbackReason?.let { appendLine("fallback: $it") }
            appendLine("${InkDiagnostics.device}, bypass ${InkDiagnostics.hiddenApiBypass}")
            appendLine(
                "dpi ${String.format(Locale.US, "%.1f", canvas.reportedDpi())}  " +
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            canvas.backend.debugInfo().forEach { appendLine(it) }
            append(lastStats ?: getString(R.string.debug_no_strokes))
        }
    }

    /**
     * Rects of every overlay that sits on top of the canvas, from getGlobalVisibleRect. These are
     * window coordinates; for a full-screen activity window they equal screen coordinates, which
     * is what the Onyx TouchHelper expects (inferred, see OnyxInkBackend).
     */
    private fun pushExcludeRects() {
        val rects = ArrayList<Rect>()
        if (debugVisible()) {
            val r = Rect()
            if (debugOverlay.getGlobalVisibleRect(r)) rects += r
        }
        canvas.backend.setExcludeRects(rects)
    }
}
