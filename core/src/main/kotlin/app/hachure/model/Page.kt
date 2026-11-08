package app.hachure.model

/**
 * A page of strokes. Sizes and coordinates are in mm.
 *
 * Mutable on purpose: the canvas appends strokes on every pen-up and copying the list each time
 * would allocate on the hot path. Callers outside the UI thread must take a [snapshot].
 */
class Page(
    val widthMm: Float,
    val heightMm: Float,
) {
    private val _strokes = ArrayList<Stroke>()
    val strokes: List<Stroke> get() = _strokes

    private var nextId = 1L

    init {
        require(widthMm > 0f && heightMm > 0f) { "page size must be positive" }
    }

    fun newStrokeId(): Long = nextId++

    fun add(stroke: Stroke) {
        _strokes += stroke
        if (stroke.id >= nextId) nextId = stroke.id + 1
    }

    fun clear() = _strokes.clear()

    fun snapshot(): List<Stroke> = _strokes.toList()

    companion object {
        /** Note Air 2: 1404 x 1872 px at 227 dpi. */
        const val NOTE_AIR_2_WIDTH_MM = 1404f * 25.4f / 227f
        const val NOTE_AIR_2_HEIGHT_MM = 1872f * 25.4f / 227f
    }
}
