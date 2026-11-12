package app.hachure.model

/**
 * A page of strokes. Sizes and coordinates are in mm. List order is paint order.
 *
 * Mutable on purpose: the canvas appends strokes on every pen-up and copying the list each time
 * would allocate on the hot path. Callers outside the UI thread must take a [snapshot].
 * Edits that should be undoable go through [History], not these methods directly.
 */
class Page(
    val widthMm: Float,
    val heightMm: Float,
) {
    private val _strokes = ArrayList<Stroke>()
    val strokes: List<Stroke> get() = _strokes

    private var nextId = 1L

    private val _rulers = ArrayList<Ruler>()

    /** Guides are part of the page but not of the ink: they are never erased with strokes. */
    val rulers: List<Ruler> get() = _rulers

    private var nextGuideId = 1L

    /** Perspective guides; replaced as a whole on every change. */
    var perspective: Perspective = Perspective.default(widthMm, heightMm)

    fun newGuideId(): Long = nextGuideId++

    fun ruler(id: Long): Ruler? = _rulers.firstOrNull { it.id == id }

    /** Adds the ruler, or replaces the one with the same id (keeping its place in the list). */
    fun putRuler(r: Ruler) {
        val i = _rulers.indexOfFirst { it.id == r.id }
        if (i >= 0) _rulers[i] = r else _rulers += r
        if (r.id >= nextGuideId) nextGuideId = r.id + 1
    }

    fun removeRuler(id: Long): Boolean = _rulers.removeAll { it.id == id }

    init {
        require(widthMm > 0f && heightMm > 0f) { "page size must be positive" }
    }

    fun newStrokeId(): Long = nextId++

    fun add(stroke: Stroke) {
        _strokes += stroke
        if (stroke.id >= nextId) nextId = stroke.id + 1
    }

    /** Removes strokes by id. Returns them with their former indices, lowest index first. */
    fun remove(ids: Set<Long>): List<IndexedValue<Stroke>> {
        val removed = ArrayList<IndexedValue<Stroke>>()
        for (i in _strokes.indices) if (_strokes[i].id in ids) removed += IndexedValue(i, _strokes[i])
        for (k in removed.indices.reversed()) _strokes.removeAt(removed[k].index)
        return removed
    }

    /** Puts back what [remove] returned, at the same paint positions. */
    fun restore(removed: List<IndexedValue<Stroke>>) {
        for (r in removed.sortedBy { it.index }) _strokes.add(r.index.coerceAtMost(_strokes.size), r.value)
    }

    fun removeLast(id: Long): Stroke? {
        val i = _strokes.indexOfLast { it.id == id }
        return if (i >= 0) _strokes.removeAt(i) else null
    }

    fun clear() = _strokes.clear()

    fun snapshot(): List<Stroke> = _strokes.toList()

    companion object {
        /** Note Air 2: 1404 x 1872 px at 227 dpi. */
        const val NOTE_AIR_2_WIDTH_MM = 1404f * 25.4f / 227f
        const val NOTE_AIR_2_HEIGHT_MM = 1872f * 25.4f / 227f
    }
}
