package app.hachure.geometry

import app.hachure.model.Rect
import app.hachure.model.Ruler
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Axis-aligned rectangles that cover the snap band of ruler edges (Milestone 6, option B: the
 * ink SDK is told to leave these alone). An edge along x or y gets one exact rectangle. A slanted
 * edge gets a row of small squares, because its bounding box would swallow half the page.
 */
object SnapBands {
    /** Half-side of the squares along a slanted edge, as a multiple of the band. */
    private const val TILE_FACTOR = 1.25f

    fun tiles(edge: Edge, bandMm: Float): List<Rect> {
        val a = edge.a
        val b = edge.b
        if (abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f) {
            return listOf(
                Rect(minOf(a.x, b.x) - bandMm, minOf(a.y, b.y) - bandMm, maxOf(a.x, b.x) + bandMm, maxOf(a.y, b.y) + bandMm),
            )
        }
        // A square of half-side h holds the disc of radius h round its centre. Discs spaced s
        // apart along the edge cover everything within sqrt(h^2 - s^2 / 4) of it, so choose s
        // to make that the band.
        val h = bandMm * TILE_FACTOR
        val maxStep = 2f * sqrt(h * h - bandMm * bandMm)
        // The band also reaches one band past each end of the edge (see Snap.nearestEdge).
        val from = -bandMm
        val to = edge.length + bandMm
        val n = ceil((to - from) / maxStep).toInt().coerceAtLeast(1)
        val step = (to - from) / n
        return List(n + 1) { i ->
            val c = edge.pointAt(from + i * step)
            Rect(c.x - h, c.y - h, c.x + h, c.y + h)
        }
    }

    /** Tiles for both edges of every ruler. */
    fun tiles(rulers: List<Ruler>, bandMm: Float): List<Rect> =
        rulers.flatMap { r -> tiles(RulerGeometry.edge(r, 1), bandMm) + tiles(RulerGeometry.edge(r, -1), bandMm) }

    /** True when [p] is inside the snap band of any edge: such a stroke will snap. */
    fun contains(rulers: List<Ruler>, p: Vec2, bandMm: Float): Boolean = Snap.nearestEdge(rulers, p, bandMm) != null
}
