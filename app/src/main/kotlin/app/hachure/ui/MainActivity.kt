package app.hachure.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
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
        btnLiveStyle.setOnClickListener { cycleLiveStyle() }
        btnCoords.setOnClickListener { toggleCoords() }

        canvas.statsListener = CanvasView.StatsListener { points, penUpMs, renderMs ->
            lastStats = getString(R.string.debug_last_stroke, points, penUpMs, renderMs)
            if (debugVisible()) renderDebug()
        }
        canvas.backendChanged = {
            pushExcludeRects()
            if (debugVisible()) renderDebug()
        }

        canvas.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
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
        val onyxControls = if (show && onyx() != null) View.VISIBLE else View.GONE
        btnLiveStyle.visibility = onyxControls
        btnCoords.visibility = onyxControls
        if (show) renderDebug()
    }

    /** Compare SDK live styles against the committed render (spec: report which looks closest). */
    private fun cycleLiveStyle() {
        val o = onyx() ?: return
        val styles = OnyxInkBackend.LiveStyle.entries
        o.liveStyle = styles[(o.liveStyle.ordinal + 1) % styles.size]
        renderDebug()
    }

    private fun toggleCoords() {
        val o = onyx() ?: return
        o.pointsAreScreenAbsolute = !o.pointsAreScreenAbsolute
        renderDebug()
    }

    private fun renderDebug() {
        onyx()?.let {
            btnLiveStyle.text = getString(R.string.live_style, it.liveStyle.label)
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
