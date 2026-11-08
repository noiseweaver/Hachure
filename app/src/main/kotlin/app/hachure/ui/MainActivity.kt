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
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var canvas: CanvasView
    private lateinit var debugOverlay: TextView
    private var lastStats: String? = null

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { pushExcludeRects() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvas = findViewById(R.id.canvas)
        debugOverlay = findViewById(R.id.debug_overlay)

        findViewById<Button>(R.id.btn_clear).setOnClickListener { canvas.clearPage() }
        findViewById<Button>(R.id.btn_debug).setOnClickListener { toggleDebug() }

        canvas.statsListener = CanvasView.StatsListener { _, points, ms ->
            lastStats = getString(R.string.debug_last_stroke, points, ms)
            if (debugOverlay.visibility == View.VISIBLE) renderDebug()
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

    private fun toggleDebug() {
        val show = debugOverlay.visibility != View.VISIBLE
        debugOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) renderDebug()
    }

    private fun renderDebug() {
        debugOverlay.text = buildString {
            appendLine(getString(R.string.debug_backend, canvas.backend.name))
            appendLine("dpi ${String.format(Locale.US, "%.1f", canvas.reportedDpi())}  v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append(lastStats ?: getString(R.string.debug_no_strokes))
        }
    }

    /**
     * Rects of every overlay that sits on top of the canvas, from getGlobalVisibleRect. These are
     * window coordinates; for a full-screen activity window they equal screen coordinates, which
     * is what the Onyx TouchHelper expects (from the spec; to be checked against the SDK docs
     * and on device in Milestone 2).
     */
    private fun pushExcludeRects() {
        val rects = ArrayList<Rect>()
        if (debugOverlay.visibility == View.VISIBLE) {
            val r = Rect()
            if (debugOverlay.getGlobalVisibleRect(r)) rects += r
        }
        canvas.backend.setExcludeRects(rects)
    }
}
