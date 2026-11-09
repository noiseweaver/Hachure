package app.hachure.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.hachure.geometry.Hit
import app.hachure.geometry.PageTransform
import app.hachure.geometry.Units
import app.hachure.geometry.Vec2
import app.hachure.ink.InkBackend
import app.hachure.ink.InkBackends
import app.hachure.ink.InkDiagnostics
import app.hachure.ink.InkListener
import app.hachure.ink.MotionEventInkBackend
import app.hachure.ink.OnyxInkBackend
import app.hachure.ink.RawPoint
import app.hachure.model.DotGrid
import app.hachure.model.History
import app.hachure.model.InkPoint
import app.hachure.model.Page
import app.hachure.model.Stroke
import app.hachure.model.Tool
import app.hachure.model.WidthPreset
import app.hachure.model.straightLinePoints
import app.hachure.render.OnyxStrokeRenderer
import app.hachure.render.PageRenderer
import app.hachure.render.StrokeRenderer

/**
 * The drawing surface.
 *
 * Layers, bottom to top: paper (white) and dot grid, committed strokes (both in one bitmap), app
 * live layer (live stroke when the backend does not ink it, line preview).
 *
 * Refresh rule: one partial invalidate per pen-up, undo, redo or erase, covering only the
 * strokes that changed. The live layer uses small dirty-rect invalidates.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), InkListener {

    enum class Mode { DRAW, LINE, ERASE }

    /** Reports point count and pen-up-to-commit time for the debug overlay. */
    fun interface StatsListener {
        fun onStrokeCommitted(points: Int, penUpToCommitMs: Long, renderMs: Long)
    }

    var statsListener: StatsListener? = null

    /** Called after any change to undo or redo availability. */
    var historyChanged: (() -> Unit)? = null

    /** Called after every change to the page content (save trigger). */
    var pageEdited: (() -> Unit)? = null

    /** Onyx on Boox devices, plain MotionEvent elsewhere or if the SDK fails to start. */
    var backend: InkBackend = InkBackends.create()
        private set

    var tool = Tool.PENCIL
        set(value) { field = value; pushBrush() }

    var widthPreset = WidthPreset.MEDIUM
        set(value) { field = value; pushBrush() }

    var mode = Mode.DRAW
        set(value) { field = value; pushBrush() }

    var dotGrid: DotGrid? = DotGrid()
        set(value) {
            field = value
            rebuild(null)
        }

    private val transform = PageTransform.forDpi(panelDpi())
    private val appRenderer = PageRenderer()
    private var strokeRenderer: StrokeRenderer = appRenderer
    private var page: Page? = null
    private var history: History? = null
    private var committed: Bitmap? = null
    private var committedCanvas: Canvas? = null

    private val livePath = Path()
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var liveActive = false
    private var lineStart: RawPoint? = null
    private var lineEnd: RawPoint? = null
    private var lastLive: RawPoint? = null
    private val dirty = Rect()

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = false
    }

    /**
     * Shows a loaded page. Call before or after layout; undo history starts empty. A view whose
     * size differs from the saved page still works because strokes are stored in mm.
     */
    fun setPage(p: Page) {
        page = p
        history = History(p)
        rebuild(null)
        historyChanged?.invoke()
    }

    /** Copy of the page for saving off the UI thread. */
    fun pageSnapshot(): Page? = page?.let { src ->
        Page(src.widthMm, src.heightMm).also { copy -> src.snapshot().forEach { copy.add(it) } }
    }

    val canUndo get() = history?.canUndo == true
    val canRedo get() = history?.canRedo == true

    private fun widthPx() = transform.lengthToView(tool.widthMm(widthPreset)).coerceAtLeast(1f)

    private fun pushBrush() {
        backend.setBrush(tool, widthPx(), liveInk = mode == Mode.DRAW, wantMoves = mode == Mode.LINE)
    }

    /** The app draws feedback while the pen is down (no backend ink, or line preview). */
    private fun appDrawsLive() = !backend.rendersLiveStroke || mode == Mode.LINE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            pushBrush()
            backend.attach(this, this)
        } catch (t: Throwable) {
            fallBackToMotionEvent(t)
        }
        updateRenderer()
    }

    override fun onBackendFailed(error: Throwable) {
        if (backend is MotionEventInkBackend) return
        fallBackToMotionEvent(error)
        updateRenderer()
        backendChanged?.invoke()
    }

    /** Called when the backend switches at runtime, so exclude rects can be re-sent. */
    var backendChanged: (() -> Unit)? = null

    private fun fallBackToMotionEvent(t: Throwable) {
        InkDiagnostics.backendFallbackReason = "${backend.name} failed: ${t.javaClass.simpleName}: ${t.message}"
        runCatching { backend.detach() }
        backend = MotionEventInkBackend().also { it.attach(this, this) }
        pushBrush()
    }

    /** SDK commit renderers only make sense when the SDK inks live. */
    private fun updateRenderer() {
        val onyx = backend as? OnyxInkBackend
        strokeRenderer = if (onyx != null) {
            OnyxStrokeRenderer(context, onyx.maxTouchPressure, appRenderer)
        } else {
            appRenderer
        }
        rebuild(null)
    }

    fun rendererError(): String? = (strokeRenderer as? OnyxStrokeRenderer)?.lastError

    override fun onDetachedFromWindow() {
        backend.detach()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (page == null) {
            page = Page(transform.lengthToPage(w.toFloat()), transform.lengthToPage(h.toFloat())).also {
                history = History(it)
            }
        }
        committed?.recycle()
        committed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            committedCanvas = Canvas(it)
        }
        rebuild(null)
    }

    /**
     * Repaints [region] (view px) of the committed bitmap from the model, or all of it when null,
     * and invalidates that region.
     */
    private fun rebuild(region: Rect?) {
        val bmp = committed ?: return
        val c = committedCanvas ?: return
        val r = region?.let { Rect(it).apply { intersect(0, 0, bmp.width, bmp.height) } }
            ?: Rect(0, 0, bmp.width, bmp.height)
        if (r.isEmpty) return
        c.save()
        c.clipRect(r)
        c.drawColor(Color.WHITE)
        dotGrid?.let {
            appRenderer.drawGrid(c, it, transform, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
        val tmp = Rect()
        page?.strokes?.forEach { s ->
            viewBounds(s, tmp)
            if (Rect.intersects(tmp, r)) strokeRenderer.drawStroke(c, s, transform)
        }
        c.restore()
        invalidate(r)
    }

    private fun viewBounds(s: Stroke, out: Rect) {
        val b = s.bounds()
        val a = transform.pageToView(Vec2(b.left, b.top))
        val z = transform.pageToView(Vec2(b.right, b.bottom))
        // Pressure renderers (brush, charcoal) can spread past the nominal width.
        val pad = (transform.lengthToView(s.widthMm) + 3f).toInt()
        out.set(a.x.toInt() - pad, a.y.toInt() - pad, z.x.toInt() + pad, z.y.toInt() + pad)
    }

    private fun regionOf(strokes: List<Stroke>): Rect? {
        if (strokes.isEmpty()) return null
        val out = Rect()
        val tmp = Rect()
        for (s in strokes) {
            viewBounds(s, tmp)
            out.union(tmp)
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        committed?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (!liveActive) return
        val s = lineStart
        val e = lineEnd
        if (mode == Mode.LINE && s != null && e != null) {
            canvas.drawLine(s.x, s.y, e.x, e.y, livePaint)
        } else if (!livePath.isEmpty) {
            canvas.drawPath(livePath, livePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) liveErase(event)
        return backend.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // Live erasing with the stylus eraser end. The SDK's erase callbacks can arrive seconds
    // late on the Note Air 2, but Android still delivers TOOL_TYPE_ERASER MotionEvents to the
    // view ([G] confirmed only through this build's debug line). Strokes go as the eraser
    // touches them, in batches every ERASE_FRAME_MS, and one rub is one undo step. The SDK path
    // still runs afterwards and finds nothing left to erase.

    private val eraseBatch = ArrayList<RawPoint>()
    private var lastEraseFlush = 0L
    private var eraseEvents = 0
    private var eraseUpAt = 0L
    private var eraseStatus = "eraser: no use yet"

    private fun liveErase(e: MotionEvent) {
        val h = history ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                h.beginGroup()
                eraseUpAt = 0L
                eraseBatch.clear()
                eraseEvents = 0
                lastEraseFlush = 0L
            }
        }
        for (i in 0 until e.historySize) {
            eraseBatch += RawPoint(e.getHistoricalX(i), e.getHistoricalY(i), 1f, e.getHistoricalEventTime(i))
        }
        eraseBatch += RawPoint(e.x, e.y, 1f, e.eventTime)
        eraseEvents++
        val now = SystemClock.uptimeMillis()
        val ending = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        if (ending || now - lastEraseFlush >= ERASE_FRAME_MS) {
            lastEraseFlush = now
            val last = eraseBatch.last()
            eraseLive(ArrayList(eraseBatch))
            eraseBatch.clear()
            eraseBatch += last
        }
        if (ending) {
            h.endGroup()
            eraseUpAt = now
            eraseStatus = "eraser: live, $eraseEvents events"
        }
    }

    private fun eraseLive(points: List<RawPoint>) {
        val pg = page ?: return
        val h = history ?: return
        val path = points.map { toPage(it, points.first().timeMs) }
        applyChange(h.erase(Hit.strokesTouched(pg.strokes, path, ERASER_END_RADIUS_MM)))
    }

    fun eraseDebug(): String = eraseStatus

    override fun onHoverEvent(event: MotionEvent): Boolean {
        backend.onHoverEvent(event)
        return super.onHoverEvent(event)
    }

    fun cleanScreen() = backend.cleanScreen(this)

    fun clearPage() = applyChange(history?.clear().orEmpty())

    fun undo() = applyChange(history?.undo().orEmpty())

    fun redo() = applyChange(history?.redo().orEmpty())

    private fun applyChange(changed: List<Stroke>) {
        if (changed.isEmpty()) return
        regionOf(changed)?.let { rebuild(it) }
        historyChanged?.invoke()
        pageEdited?.invoke()
    }

    // InkListener

    override fun onStrokeBegin(point: RawPoint) {
        if (!appDrawsLive() || mode == Mode.ERASE) return
        livePaint.strokeWidth = widthPx()
        liveActive = true
        livePath.rewind()
        livePath.moveTo(point.x, point.y)
        lineStart = point
        lineEnd = point
        lastLive = point
        invalidateSegment(point, point)
    }

    override fun onStrokeMove(points: List<RawPoint>) {
        if (!liveActive || points.isEmpty()) return
        dirty.setEmpty()
        if (mode == Mode.LINE) {
            val s = lineStart ?: return
            lineEnd?.let { unionSegment(s, it) }
            val e = points.last()
            lineEnd = e
            unionSegment(s, e)
        } else {
            var prev = lastLive ?: points.first()
            for (p in points) {
                livePath.lineTo(p.x, p.y)
                unionSegment(prev, p)
                prev = p
            }
            lastLive = prev
        }
        invalidate(dirty)
    }

    override fun onStrokeEnd(points: List<RawPoint>, penUpUptime: Long) {
        when (mode) {
            Mode.ERASE -> erase(points, ERASER_TOOL_RADIUS_MM)
            Mode.LINE -> commit(points, penUpUptime, straight = true)
            Mode.DRAW -> commit(points, penUpUptime, straight = false)
        }
        endLive()
    }

    override fun onEraseEnd(points: List<RawPoint>, penUpUptime: Long) {
        if (eraseUpAt > 0) {
            eraseStatus += ", SDK erase +${SystemClock.uptimeMillis() - eraseUpAt} ms after"
        } else {
            eraseStatus = "eraser: SDK only (no MotionEvents)"
        }
        erase(points, ERASER_END_RADIUS_MM)
        endLive()
    }

    private fun endLive() {
        if (!liveActive) return
        dirty.setEmpty()
        val s = lineStart
        val e = lineEnd
        if (s != null && e != null) unionSegment(s, e)
        val pathBounds = android.graphics.RectF()
        livePath.computeBounds(pathBounds, true)
        if (!pathBounds.isEmpty) {
            val pad = (livePaint.strokeWidth + 4f).toInt()
            dirty.union(pathBounds.left.toInt() - pad, pathBounds.top.toInt() - pad, pathBounds.right.toInt() + pad, pathBounds.bottom.toInt() + pad)
        }
        liveActive = false
        livePath.rewind()
        lineStart = null
        lineEnd = null
        lastLive = null
        if (!dirty.isEmpty) invalidate(dirty)
    }

    private fun commit(points: List<RawPoint>, penUpUptime: Long, straight: Boolean) {
        val pg = page ?: return
        val h = history ?: return
        if (points.isEmpty()) return
        val renderStart = SystemClock.uptimeMillis()
        val t0 = points.first().timeMs
        val inkPoints = if (straight && points.size > 1) {
            val a = transform.viewToPage(Vec2(points.first().x, points.first().y))
            val b = transform.viewToPage(Vec2(points.last().x, points.last().y))
            val pressure = points.map { it.pressure }.sorted()[points.size / 2]
            straightLinePoints(a.x, a.y, b.x, b.y, pressure, (points.last().timeMs - t0).coerceAtLeast(0))
        } else {
            points.map { toPage(it, t0) }
        }
        val stroke = Stroke(pg.newStrokeId(), tool, tool.widthMm(widthPreset), inkPoints)
        h.add(stroke)
        val c = committedCanvas ?: return
        strokeRenderer.drawStroke(c, stroke, transform)
        regionOf(listOf(stroke))?.let { invalidate(it) }
        historyChanged?.invoke()
        pageEdited?.invoke()

        val now = SystemClock.uptimeMillis()
        statsListener?.onStrokeCommitted(points.size, now - penUpUptime, now - renderStart)
    }

    private fun erase(points: List<RawPoint>, radiusMm: Float) {
        val pg = page ?: return
        val h = history ?: return
        if (points.isEmpty()) return
        val t0 = points.first().timeMs
        val path = points.map { toPage(it, t0) }
        val ids = Hit.strokesTouched(pg.strokes, path, radiusMm)
        applyChange(h.erase(ids))
    }

    private fun toPage(p: RawPoint, t0: Long): InkPoint {
        val v = transform.viewToPage(Vec2(p.x, p.y))
        return InkPoint(v.x, v.y, p.pressure.coerceIn(0f, 1f), (p.timeMs - t0).coerceAtLeast(0))
    }

    private fun unionSegment(a: RawPoint, b: RawPoint) {
        val pad = (livePaint.strokeWidth / 2f + 2f).toInt() + 1
        val l = minOf(a.x, b.x).toInt() - pad
        val t = minOf(a.y, b.y).toInt() - pad
        val r = maxOf(a.x, b.x).toInt() + pad
        val btm = maxOf(a.y, b.y).toInt() + pad
        if (dirty.isEmpty) dirty.set(l, t, r, btm) else dirty.union(l, t, r, btm)
    }

    private fun invalidateSegment(a: RawPoint, b: RawPoint) {
        dirty.setEmpty()
        unionSegment(a, b)
        invalidate(dirty)
    }

    /**
     * Physical density. On the Note Air 2 this reports 227.1 (verified on device 2026-11-08).
     * Falls back to the density bucket when the reported value is implausible.
     */
    private fun panelDpi(): Float {
        val dm = resources.displayMetrics
        val xdpi = dm.xdpi
        return if (xdpi in 100f..700f) xdpi else dm.densityDpi.toFloat()
    }

    fun reportedDpi(): Float = transform.scale * Units.MM_PER_INCH

    /** Pixels for a length in mm on this panel, for sizing UI in physical units. */
    fun mmToPx(mm: Float): Float = transform.lengthToView(mm)

    private companion object {
        /** Eraser tool on the rail: a fine eraser for picking out single hatch lines. */
        const val ERASER_TOOL_RADIUS_MM = 1.0f

        /** Eraser end of the stylus: broader, like rubbing out. */
        const val ERASER_END_RADIUS_MM = 2.5f

        /** Live erase batch interval: each batch is one partial refresh. */
        const val ERASE_FRAME_MS = 80L
    }
}
