package app.hachure.model

/**
 * Undo and redo for one [Page]. Every undoable change goes through here.
 *
 * Each call returns the strokes whose area must be repainted, so the canvas can redraw only that
 * region (one partial e-ink refresh instead of a full page).
 */
class History(private val page: Page, private val limit: Int = 500) {

    private sealed interface Edit
    private class Added(val stroke: Stroke) : Edit
    private class Removed(val strokes: List<IndexedValue<Stroke>>) : Edit

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun add(stroke: Stroke): List<Stroke> {
        page.add(stroke)
        push(Added(stroke))
        return listOf(stroke)
    }

    /** Removes the strokes with these ids. Returns the removed strokes, or empty if none matched. */
    fun erase(ids: Set<Long>): List<Stroke> {
        if (ids.isEmpty()) return emptyList()
        val removed = page.remove(ids)
        if (removed.isEmpty()) return emptyList()
        push(Removed(removed))
        return removed.map { it.value }
    }

    fun clear(): List<Stroke> = erase(page.strokes.mapTo(HashSet()) { it.id })

    fun undo(): List<Stroke> {
        val e = undoStack.removeLastOrNull() ?: return emptyList()
        redoStack.addLast(e)
        return when (e) {
            is Added -> listOfNotNull(page.removeLast(e.stroke.id))
            is Removed -> { page.restore(e.strokes); e.strokes.map { it.value } }
        }
    }

    fun redo(): List<Stroke> {
        val e = redoStack.removeLastOrNull() ?: return emptyList()
        undoStack.addLast(e)
        return when (e) {
            is Added -> { page.add(e.stroke); listOf(e.stroke) }
            is Removed -> page.remove(e.strokes.mapTo(HashSet()) { it.value.id }).map { it.value }
        }
    }

    private fun push(e: Edit) {
        undoStack.addLast(e)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }
}
