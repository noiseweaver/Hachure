package app.hachure.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.hachure.geometry.PageTransform
import app.hachure.geometry.Units
import app.hachure.geometry.Vec2
import app.hachure.ink.InkBackend
import app.hachure.ink.InkListener
import app.hachure.ink.MotionEventInkBackend
import app.hachure.ink.RawPoint
import app.hachure.model.InkPoint
import app.hachure.model.Page
import app.hachure.model.Stroke
import app.hachure.model.Tool
import app.hachure.render.PageRenderer

/**
 * The drawing surface.
 *
 * Layers, bottom to top: paper (white), committed strokes bitmap, live stroke (only when the
 * backend does not render it). Guides and the dot grid join the stack in later milestones.
 *
 * Refresh rule: one invalidate per pen-up for the committed stroke. The live stroke uses small
 * dirty-rect invalidates and only when the backend cannot draw it itself.
 */
class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), InkListener {

    /** Reports backend name, point count and input-to-commit time for the debug overlay. */
    fun interface StatsListener {
        fun onStrokeCommitted(backend: String, points: Int, inputToCommitMs: Long)
    }

    var statsListener: StatsListener? = null

    val backend: InkBackend = MotionEventInkBackend()

    /** Pen width in mm. Fineliner 0.3 mm by default. */
    var penWidthMm = 0.3f

    private val renderer = PageRenderer()
    private val transform = PageTransform.forDpi(panelDpi())
    private var page: Page? = null
    private var committed: Bitmap? = null

    private val livePath = Path()
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var lastLive: RawPoint? = null
    private val dirty = Rect()
    private val tmpRectF = RectF()

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backend.attach(this, this)
    }

    override fun onDetachedFromWindow() {
        backend.detach()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (page == null) {
            page = Page(transform.lengthToPage(w.toFloat()), transform.lengthToPage(h.toFloat()))
        }
        committed?.recycle()
        committed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.eraseColor(Color.WHITE)
            val c = Canvas(bmp)
            page?.strokes?.forEach { renderer.drawStroke(c, it, transform) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        committed?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (!livePath.isEmpty) canvas.drawPath(livePath, livePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        backend.onTouchEvent(event) || super.onTouchEvent(event)

    fun clearPage() {
        page?.clear()
        committed?.eraseColor(Color.WHITE)
        livePath.rewind()
        invalidate()
    }

    // InkListener

    override fun onStrokeBegin(point: RawPoint) {
        if (backend.rendersLiveStroke) return
        livePaint.strokeWidth = transform.lengthToView(penWidthMm).coerceAtLeast(1f)
        livePath.rewind()
        livePath.moveTo(point.x, point.y)
        lastLive = point
        invalidateAround(point, point)
    }

    override fun onStrokeMove(points: List<RawPoint>) {
        if (backend.rendersLiveStroke || points.isEmpty()) return
        var prev = lastLive ?: points.first()
        dirty.setEmpty()
        for (p in points) {
            livePath.lineTo(p.x, p.y)
            unionSegment(prev, p)
            prev = p
        }
        lastLive = prev
        invalidate(dirty)
    }

    override fun onStrokeEnd(points: List<RawPoint>) {
        val pg = page ?: return
        val bmp = committed ?: return
        val t0 = points.first().timeMs
        val stroke = Stroke(
            id = pg.newStrokeId(),
            tool = Tool.PEN,
            widthMm = penWidthMm,
            points = points.map { toPage(it, t0) },
        )
        pg.add(stroke)
        renderer.drawStroke(Canvas(bmp), stroke, transform)
        livePath.rewind()
        lastLive = null

        val b = stroke.bounds()
        tmpRectF.set(
            transform.pageToView(Vec2(b.left, b.top)).x,
            transform.pageToView(Vec2(b.left, b.top)).y,
            transform.pageToView(Vec2(b.right, b.bottom)).x,
            transform.pageToView(Vec2(b.right, b.bottom)).y,
        )
        tmpRectF.roundOut(dirty)
        dirty.inset(-2, -2)
        invalidate(dirty)

        val commitMs = SystemClock.uptimeMillis() - points.last().timeMs
        statsListener?.onStrokeCommitted(backend.name, points.size, commitMs)
    }

    override fun onEraseEnd(points: List<RawPoint>) {
        // Stroke eraser arrives in Milestone 3.
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

    private fun invalidateAround(a: RawPoint, b: RawPoint) {
        dirty.setEmpty()
        unionSegment(a, b)
        invalidate(dirty)
    }

    /**
     * Physical density. On the Note Air 2 this should report about 227 (inferred, not yet
     * confirmed on device; the debug overlay shows it). Falls back to the density bucket when
     * the reported value is implausible, as on some emulators.
     */
    private fun panelDpi(): Float {
        val dm = resources.displayMetrics
        val xdpi = dm.xdpi
        return if (xdpi in 100f..700f) xdpi else dm.densityDpi.toFloat()
    }

    fun reportedDpi(): Float = transform.scale * Units.MM_PER_INCH
}
