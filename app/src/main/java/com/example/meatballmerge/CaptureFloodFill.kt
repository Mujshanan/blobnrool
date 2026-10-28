package com.example.meatballmerge

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Grid-based enclosure detection inspired by Papersseum's outside flood fill.
 * The polygon's final point is connected back to its first point.
 */
internal object CaptureFloodFill {
    data class Point(val x: Float, val y: Float)
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun enclosedCandidateIndices(
        boundary: List<Point>,
        candidates: List<Point>,
        bounds: Bounds,
        cellSize: Float
    ): Set<Int> {
        if (boundary.size < 3 || candidates.isEmpty()) return emptySet()
        if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
            bounds.right <= bounds.left || bounds.bottom <= bounds.top ||
            !cellSize.isFinite() || cellSize <= 0f
        ) return emptySet()

        val cols = ceil((bounds.right - bounds.left) / cellSize).toInt().coerceAtLeast(1)
        val rows = ceil((bounds.bottom - bounds.top) / cellSize).toInt().coerceAtLeast(1)
        val total = cols * rows
        // Avoid pathological allocations if a malformed input produces an enormous grid.
        if (total <= 0 || total > MAX_GRID_CELLS) return emptySet()

        val blocked = BooleanArray(total)
        val reachable = BooleanArray(total)
        fun cellX(x: Float) = ((x - bounds.left) / cellSize).toInt().coerceIn(0, cols - 1)
        fun cellY(y: Float) = ((y - bounds.top) / cellSize).toInt().coerceIn(0, rows - 1)
        fun markWall(x: Float, y: Float) {
            val cx = cellX(x)
            val cy = cellY(y)
            // Thicken rasterized ink to prevent diagonal pinholes in a 4-neighbour fill.
            for (dy in -1..1) for (dx in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until cols && ny in 0 until rows) blocked[ny * cols + nx] = true
            }
        }

        for (i in boundary.indices) {
            val a = boundary[i]
            val b = boundary[(i + 1) % boundary.size]
            if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return emptySet()
            val dx = b.x - a.x
            val dy = b.y - a.y
            val distance = sqrt(dx * dx + dy * dy)
            val steps = ceil(distance / (cellSize * 0.45f)).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val t = step.toFloat() / steps
                markWall(a.x + dx * t, a.y + dy * t)
            }
        }

        val queue = IntArray(total)
        var head = 0
        var tail = 0
        fun visit(x: Int, y: Int) {
            if (x !in 0 until cols || y !in 0 until rows) return
            val id = y * cols + x
            if (!blocked[id] && !reachable[id]) {
                reachable[id] = true
                queue[tail++] = id
            }
        }
        for (x in 0 until cols) {
            visit(x, 0)
            visit(x, rows - 1)
        }
        for (y in 0 until rows) {
            visit(0, y)
            visit(cols - 1, y)
        }
        while (head < tail) {
            val id = queue[head++]
            val x = id % cols
            val y = id / cols
            visit(x - 1, y)
            visit(x + 1, y)
            visit(x, y - 1)
            visit(x, y + 1)
        }

        return candidates.indices.filterTo(mutableSetOf()) { index ->
            val p = candidates[index]
            if (!p.x.isFinite() || !p.y.isFinite() ||
                p.x < bounds.left || p.x >= bounds.right ||
                p.y < bounds.top || p.y >= bounds.bottom
            ) return@filterTo false
            val id = cellY(p.y) * cols + cellX(p.x)
            !blocked[id] && !reachable[id]
        }
    }

    private const val MAX_GRID_CELLS = 4_000_000
}
