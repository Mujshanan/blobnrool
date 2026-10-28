package com.example.meatballmerge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private data class Blob(val id: Int, var x: Float, var y: Float)

class MeatballGameView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val radius get() = dp(31f)
    private val blobs = mutableListOf<Blob>()
    private val total = 12
    private val interval = 1150L
    private var spawned = 0
    private var nextId = 0
    private var selected: Int? = null
    private var dx = 0f
    private var dy = 0f
    private var startTime = 0L
    private var lastSpawn = 0L
    private var running = false
    private var finished = false
    private var best = Long.MAX_VALUE
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f
    private val restart = RectF()
    private val random = Random(System.nanoTime())

    private val fill = Paint(3).apply { color = Color.rgb(187, 82, 42); style = Paint.Style.FILL }
    private val edge = Paint(3).apply { color = Color.rgb(247, 184, 121); style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val highlight = Paint(3).apply { color = Color.rgb(229, 139, 83) }
    private val panel = Paint(3).apply { color = Color.rgb(34, 39, 53) }
    private val grid = Paint(3).apply { color = Color.argb(28, 220, 225, 245); strokeWidth = dp(1f) }
    private val white = Paint(3).apply { color = Color.WHITE; textSize = dp(17f); typeface = android.graphics.Typeface.create("sans-serif-medium", 0) }
    private val muted = Paint(3).apply { color = Color.rgb(180, 188, 207); textSize = dp(12f) }
    private val title = Paint(3).apply { color = Color.WHITE; textSize = dp(26f); typeface = android.graphics.Typeface.create("sans-serif-black", 0) }
    private val button = Paint(3).apply { color = Color.rgb(232, 117, 58) }
    private val buttonText = Paint(3).apply { color = Color.WHITE; textSize = dp(14f); textAlign = Paint.Align.CENTER }

    private fun reset() {
        blobs.clear(); spawned = 0; nextId = 0; selected = null
        startTime = 0L; lastSpawn = 0L; running = false; finished = false
        invalidate()
    }

    private fun elapsed(now: Long = System.currentTimeMillis()) =
        if (startTime == 0L) 0L else (if (finished) lastSpawn else now) - startTime

    private fun time(ms: Long) = "%02d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(23, 26, 36))
        val w = width.toFloat(); val h = height.toFloat(); val margin = dp(20f)
        c.drawText("BLOB'N'ROLL", margin, dp(44f), title)
        white.textAlign = Paint.Align.RIGHT
        c.drawText(time(elapsed()), w - margin, dp(43f), white)
        muted.textAlign = Paint.Align.RIGHT
        c.drawText("TIME", w - margin, dp(62f), muted)
        white.textAlign = Paint.Align.LEFT; muted.textAlign = Paint.Align.LEFT

        val cardTop = dp(65f)
        c.drawRoundRect(margin, cardTop, w - margin, cardTop + dp(48f), dp(12f), dp(12f), panel)
        c.drawText("BLOBS", margin + dp(13f), cardTop + dp(19f), muted)
        c.drawText("${blobs.size} / $total", margin + dp(13f), cardTop + dp(39f), white)
        val pl = margin + dp(105f); val pr = w - margin - dp(13f); val py = cardTop + dp(26f)
        c.drawRoundRect(pl, py - dp(4f), pr, py + dp(4f), dp(4f), dp(4f), grid)
        if (pr > pl) c.drawRoundRect(pl, py - dp(4f), pl + (pr - pl) * spawned / total, py + dp(4f), dp(4f), dp(4f), button)

        left = margin + dp(2f); right = w - margin - dp(2f)
        top = cardTop + dp(60f); bottom = h - dp(91f)
        c.drawRoundRect(left, top, right, bottom, dp(20f), dp(20f), panel)
        c.save(); c.clipRect(left, top, right, bottom)
        var gx = left + dp(13f)
        while (gx < right) { c.drawLine(gx, top, gx, bottom, grid); gx += dp(26f) }
        var gy = top + dp(13f)
        while (gy < bottom) { c.drawLine(left, gy, right, gy, grid); gy += dp(26f) }

        // Marching Squares over the summed compact-support scalar field.
        val step = dp(6f); val iso = 0.04f; val support = radius * 1.02f
        if (blobs.isNotEmpty()) {
            val x0 = max(left, blobs.minOf { it.x } - support - step)
            val y0 = max(top, blobs.minOf { it.y } - support - step)
            val x1 = min(right, blobs.maxOf { it.x } + support + step)
            val y1 = min(bottom, blobs.maxOf { it.y } + support + step)
            val cols = max(2, ((x1 - x0) / step).toInt() + 1)
            val rows = max(2, ((y1 - y0) / step).toInt() + 1)
            val values = Array(rows) { FloatArray(cols) }
            fun sample(x: Float, y: Float): Float {
                var v = 0f
                for (b in blobs) {
                    val ax = x - b.x; val ay = y - b.y; val d2 = ax * ax + ay * ay
                    if (d2 < support * support) v += 1f - d2 / (support * support)
                }
                return v
            }
            for (j in 0 until rows) for (i in 0 until cols)
                values[j][i] = sample(min(x1, x0 + i * step), min(y1, y0 + j * step))
            val contour = Path()
            fun px(i: Int) = min(x1, x0 + i * step)
            fun py2(j: Int) = min(y1, y0 + j * step)
            fun crossing(ax: Float, ay: Float, bx: Float, by: Float, va: Float, vb: Float): Pair<Float, Float> {
                val t = if (abs(vb - va) < 0.00001f) 0.5f else ((iso - va) / (vb - va)).coerceIn(0f, 1f)
                return Pair(ax + (bx - ax) * t, ay + (by - ay) * t)
            }
            for (j in 0 until rows - 1) for (i in 0 until cols - 1) {
                val xs = floatArrayOf(px(i), px(i + 1), px(i + 1), px(i))
                val ys = floatArrayOf(py2(j), py2(j), py2(j + 1), py2(j + 1))
                val vs = floatArrayOf(values[j][i], values[j][i + 1], values[j + 1][i + 1], values[j + 1][i])
                val points = arrayOfNulls<Pair<Float, Float>>(4)
                val pairs = arrayOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
                for (e in 0..3) {
                    val (a, b) = pairs[e]
                    if ((vs[a] >= iso) != (vs[b] >= iso))
                        points[e] = crossing(xs[a], ys[a], xs[b], ys[b], vs[a], vs[b])
                }
                val active = points.indices.filter { points[it] != null }
                if (active.size >= 2) {
                    for (k in 0 until active.size - 1 step 2) {
                        val a = points[active[k]]!!; val b = points[active[k + 1]]!!
                        contour.moveTo(a.first, a.second); contour.lineTo(b.first, b.second)
                    }
                }
            }
            // The filled disks under the contour strokes make the field read as one organic mass.
            for (b in blobs) c.drawCircle(b.x, b.y, radius * 0.82f, fill)
            c.drawPath(contour, edge)
            for (b in blobs) c.drawCircle(b.x - radius * .28f, b.y - radius * .32f, radius * .12f, highlight)
        }
        c.restore()

        val footer = h - dp(68f)
        muted.textAlign = Paint.Align.CENTER
        if (finished) {
            white.textAlign = Paint.Align.CENTER
            c.drawText("BOARD CLEARED IN ${time(elapsed())}", w / 2f, footer - dp(12f), white)
            c.drawText(if (best < Long.MAX_VALUE) "Best: ${time(best)}" else "Great run!", w / 2f, footer + dp(8f), muted)
            white.textAlign = Paint.Align.LEFT
        } else {
            c.drawText(if (spawned == 0) "Touch to start · drag blobs together" else "Drag blobs together to clear the board", w / 2f, footer, muted)
        }
        muted.textAlign = Paint.Align.LEFT
        restart.set(w - margin - dp(104f), h - dp(48f), w - margin, h - dp(12f))
        c.drawRoundRect(restart, dp(18f), dp(18f), button)
        c.drawText("RESTART ↻", restart.centerX(), restart.centerY() + dp(5f), buttonText)

        if (running && !finished) {
            val now = System.currentTimeMillis()
            if (spawned < total && now - lastSpawn >= interval) spawn(now)
            if (spawned >= total && blobs.isEmpty()) finish(now)
            postInvalidateOnAnimation()
        }
    }

    private fun spawn(now: Long) {
        if (spawned >= total) return
        val safe = radius + dp(8f)
        val minX = left + safe; val maxX = max(minX, right - safe)
        val minY = top + safe; val maxY = max(minY, bottom - safe)
        var x = minX + random.nextFloat() * (maxX - minX).coerceAtLeast(1f)
        var y = minY + random.nextFloat() * (maxY - minY).coerceAtLeast(1f)
        repeat(12) {
            if (blobs.none { b -> (b.x - x) * (b.x - x) + (b.y - y) * (b.y - y) < radius * radius * 2f }) return@repeat
            x = minX + random.nextFloat() * (maxX - minX).coerceAtLeast(1f)
            y = minY + random.nextFloat() * (maxY - minY).coerceAtLeast(1f)
        }
        blobs.add(Blob(nextId++, x, y)); spawned++; lastSpawn = now
        if (startTime == 0L) startTime = now
    }

    private fun finish(now: Long) {
        running = false; finished = true; lastSpawn = now
        best = min(best, now - startTime)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (restart.contains(x, y)) { reset(); return true }
                if (!running && !finished) {
                    running = true
                    if (blobs.isEmpty()) spawn(System.currentTimeMillis())
                }
                val b = blobs.asReversed().firstOrNull {
                    val ax = x - it.x; val ay = y - it.y
                    ax * ax + ay * ay <= radius * radius * 1.8f
                }
                if (b != null) { selected = b.id; dx = b.x - x; dy = b.y - y }
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = selected
                if (id != null) {
                    val b = blobs.firstOrNull { it.id == id }
                    if (b != null) {
                        b.x = (x + dx).coerceIn(left + radius * .7f, right - radius * .7f)
                        b.y = (y + dy).coerceIn(top + radius * .7f, bottom - radius * .7f)
                        val partner = blobs.firstOrNull { other ->
                            if (other.id == b.id) false else {
                                val ax = b.x - other.x; val ay = b.y - other.y
                                sqrt(ax * ax + ay * ay) <= radius * 1.78f
                            }
                        }
                        if (partner != null) {
                            blobs.removeAll { it.id == b.id || it.id == partner.id }
                            selected = null
                            if (spawned >= total && blobs.isEmpty()) finish(System.currentTimeMillis())
                        }
                    }
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { selected = null; invalidate(); return true }
        }
        return true
    }
}
