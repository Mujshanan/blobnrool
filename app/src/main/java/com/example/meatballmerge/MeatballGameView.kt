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

private data class InkTrail(
    val points: MutableList<Pair<Float, Float>>,
    var closed: Boolean = false,
    // A trail can close visually for fading without being a valid capture region.
    var captures: Boolean = false,
    var age: Float = 0f
)

private data class Survivor(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var panic: Float = 0f,
    var mass: Float = 1f,
    var mergePulse: Float = 0f
)

class MeatballGameView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val survivorRadius get() = dp(17f)
    private fun radiusFor(survivor: Survivor) = survivorRadius * sqrt(survivor.mass)
    private val hunterRadius get() = dp(29f)
    private val survivors = mutableListOf<Survivor>()
    private val random = Random(System.nanoTime())
    private var nextId = 0
    private var selectedId: Int? = null
    private var dragging = false
    private var running = false
    private var finished = false
    private var lastFrame = 0L
    private var startTime = 0L
    private var finalTime = 0L
    private var bestTime = Long.MAX_VALUE
    private var hunterX = 0f
    private var hunterY = 0f
    private var hunterVx = 0f
    private var hunterVy = 0f
    private var arena = RectF()
    private val restart = RectF()
    private val walls = mutableListOf<RectF>()
    private var arenaNeedsLayout = true
    private val inkTrails = mutableListOf<InkTrail>()
    private var activeTrail: InkTrail? = null
    private var orbitTargetId: Int? = null
    private var orbitCenterX = 0f
    private var orbitCenterY = 0f
    private var orbitAngle = 0f

    private val background = Color.rgb(250, 249, 246)
    private val ink = Color.rgb(20, 20, 22)
    private val gray = Color.rgb(115, 115, 115)
    private val faint = Color.rgb(231, 229, 224)
    private val wallPaint = Paint(3).apply { color = ink; style = Paint.Style.FILL }
    private val survivorPaint = Paint(3).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val outlinePaint = Paint(3).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val inkPaint = Paint(3).apply { color = ink; style = Paint.Style.FILL }
    private val whitePaint = Paint(3).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val facePaint = Paint(3).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND }
    private val titlePaint = Paint(3).apply { color = ink; textSize = dp(24f); typeface = android.graphics.Typeface.create("sans-serif-black", 0) }
    private val textPaint = Paint(3).apply { color = ink; textSize = dp(14f); typeface = android.graphics.Typeface.create("sans-serif-medium", 0) }
    private val smallPaint = Paint(3).apply { color = gray; textSize = dp(11f) }
    private val buttonPaint = Paint(3).apply { color = ink; style = Paint.Style.FILL }
    private val buttonText = Paint(3).apply { color = Color.WHITE; textSize = dp(12f); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", 0) }
    private val gridPaint = Paint(3).apply { color = faint; strokeWidth = dp(1f) }

    init {
        isFocusable = true
        reset()
    }

    private fun reset() {
        survivors.clear()
        nextId = 0
        selectedId = null
        dragging = false
        running = false
        finished = false
        startTime = 0L
        finalTime = 0L
        lastFrame = 0L
        hunterVx = 0f
        hunterVy = 0f
        arenaNeedsLayout = true
        inkTrails.clear()
        activeTrail = null
        orbitTargetId = null
        // Initial positions are replaced with arena-relative positions on the first layout.
        invalidate()
    }

    private fun elapsed(now: Long = System.currentTimeMillis()): Long {
        if (startTime == 0L) return 0L
        return (if (finished) finalTime else now) - startTime
    }

    private fun formatTime(ms: Long): String =
        "%02d:%02d".format(ms / 60000, (ms / 1000) % 60)

    private fun setupArena() {
        val margin = dp(18f)
        val nextLeft = margin
        val nextTop = dp(92f)
        val nextRight = width - margin
        val nextBottom = height - dp(72f)
        if (nextRight - nextLeft <= dp(100f) || nextBottom - nextTop <= dp(150f)) return

        val changed = arenaNeedsLayout ||
            abs(arena.left - nextLeft) > dp(1f) ||
            abs(arena.top - nextTop) > dp(1f) ||
            abs(arena.right - nextRight) > dp(1f) ||
            abs(arena.bottom - nextBottom) > dp(1f)
        if (!changed) return

        arena.set(nextLeft, nextTop, nextRight, nextBottom)
        walls.clear()
        val w = arena.width()
        val h = arena.height()

        // Build a fresh, varied layout only on first layout, resize, or restart.
        // Keep obstacles separated so the hunter always has room to route around them.
        val desiredCount = 4 + random.nextInt(3)
        var attempts = 0
        while (walls.size < desiredCount && attempts < 100) {
            attempts++
            val obstacleW = w * (.07f + random.nextFloat() * .075f)
            val obstacleH = h * (.07f + random.nextFloat() * .12f)
            val left = arena.left + dp(24f) + random.nextFloat() * (w - obstacleW - dp(48f))
            val top = arena.top + dp(24f) + random.nextFloat() * (h - obstacleH - dp(48f))
            val candidate = RectF(left, top, left + obstacleW, top + obstacleH)
            val expanded = RectF(candidate.left - hunterRadius - dp(12f),
                candidate.top - hunterRadius - dp(12f),
                candidate.right + hunterRadius + dp(12f),
                candidate.bottom + hunterRadius + dp(12f))
            val overlaps = walls.any { existing ->
                val e = RectF(existing.left - hunterRadius, existing.top - hunterRadius,
                    existing.right + hunterRadius, existing.bottom + hunterRadius)
                RectF.intersects(expanded, e)
            }
            // Leave the central area open enough that routes can connect around obstacles.
            val centerKeepout = RectF(arena.centerX() - w * .08f, arena.centerY() - h * .08f,
                arena.centerX() + w * .08f, arena.centerY() + h * .08f)
            if (!overlaps && !RectF.intersects(candidate, centerKeepout)) walls += candidate
        }

        if (survivors.isEmpty() && !running && !finished) {
            repeat(6) {
                val point = findFreePoint(
                    arena.left + dp(28f) + random.nextFloat() * (w - dp(56f)),
                    arena.top + dp(28f) + random.nextFloat() * (h - dp(56f)),
                    survivorRadius
                )
                survivors += Survivor(nextId++, point.first, point.second)
            }
            val hp = findFreePoint(arena.centerX(), arena.top + h * .14f, hunterRadius)
            hunterX = hp.first
            hunterY = hp.second
        } else {
            survivors.forEach { s ->
                val p = collideWithArena(s.x, s.y, radiusFor(s))
                s.x = p.first
                s.y = p.second
            }
            val hp = collideWithArena(hunterX, hunterY, hunterRadius)
            hunterX = hp.first
            hunterY = hp.second
        }
        arenaNeedsLayout = false
    }

    private fun findFreePoint(x: Float, y: Float, radius: Float): Pair<Float, Float> {
        fun isFree(px: Float, py: Float): Boolean {
            if (px < arena.left + radius || px > arena.right - radius ||
                py < arena.top + radius || py > arena.bottom - radius) return false
            return walls.none { rect ->
                val cx = px.coerceIn(rect.left, rect.right)
                val cy = py.coerceIn(rect.top, rect.bottom)
                val dx = px - cx
                val dy = py - cy
                dx * dx + dy * dy < (radius + dp(3f)) * (radius + dp(3f))
            }
        }
        if (isFree(x, y)) return x to y
        repeat(100) {
            val px = arena.left + radius + random.nextFloat() * (arena.width() - radius * 2f)
            val py = arena.top + radius + random.nextFloat() * (arena.height() - radius * 2f)
            if (isFree(px, py)) return px to py
        }
        return arena.centerX() to arena.centerY()
    }

    private fun collideWithArena(x: Float, y: Float, radius: Float): Pair<Float, Float> {
        var px = x.coerceIn(arena.left + radius, arena.right - radius)
        var py = y.coerceIn(arena.top + radius, arena.bottom - radius)
        walls.forEach { r ->
            val cx = px.coerceIn(r.left, r.right)
            val cy = py.coerceIn(r.top, r.bottom)
            val dx = px - cx
            val dy = py - cy
            val d2 = dx * dx + dy * dy
            if (d2 < radius * radius) {
                if (d2 > .01f) {
                    val d = sqrt(d2)
                    val push = radius - d
                    px += dx / d * push
                    py += dy / d * push
                } else {
                    val leftGap = abs(px - r.left)
                    val rightGap = abs(r.right - px)
                    val topGap = abs(py - r.top)
                    val bottomGap = abs(r.bottom - py)
                    val smallest = min(min(leftGap, rightGap), min(topGap, bottomGap))
                    when (smallest) {
                        leftGap -> px = r.left - radius
                        rightGap -> px = r.right + radius
                        topGap -> py = r.top - radius
                        else -> py = r.bottom + radius
                    }
                }
            }
        }
        return px.coerceIn(arena.left + radius, arena.right - radius) to
            py.coerceIn(arena.top + radius, arena.bottom - radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background)
        setupArena()
        canvas.drawText("BLOB'N'ROLL", dp(20f), dp(37f), titlePaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatTime(elapsed()), width - dp(20f), dp(35f), textPaint)
        smallPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("SURVIVAL", width - dp(20f), dp(52f), smallPaint)
        textPaint.textAlign = Paint.Align.LEFT
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("SAVE THE WHITE BLOBS", dp(20f), dp(63f), smallPaint)
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("SURVIVORS  ${survivors.size}/6", dp(20f), dp(81f), smallPaint)

        canvas.drawRoundRect(arena, dp(18f), dp(18f), Paint(3).apply { color = Color.WHITE; style = Paint.Style.FILL })
        canvas.save()
        canvas.clipRect(arena)
        var gx = arena.left + dp(20f)
        while (gx < arena.right) {
            canvas.drawLine(gx, arena.top, gx, arena.bottom, gridPaint)
            gx += dp(28f)
        }
        var gy = arena.top + dp(20f)
        while (gy < arena.bottom) {
            canvas.drawLine(arena.left, gy, arena.right, gy, gridPaint)
            gy += dp(28f)
        }

        walls.forEach { r ->
            canvas.drawRoundRect(r, dp(7f), dp(7f), wallPaint)
        }
        // Ink trails are soft, liquid-looking capture boundaries. Completed loops fade away.
        inkTrails.forEach { trail ->
            if (trail.points.size >= 2) {
                val path = Path()
                trail.points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.first, point.second)
                    else path.lineTo(point.first, point.second)
                }
                if (trail.closed && trail.captures) path.close()
                val alpha = if (trail.closed) (210f * (1f - trail.age / 9f).coerceIn(0f, 1f)).toInt() else 225
                val fill = Paint(3).apply { color = Color.argb((alpha * .16f).toInt(), 15, 15, 18); style = Paint.Style.FILL }
                val stroke = Paint(3).apply { color = Color.argb(alpha, 18, 18, 22); style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                if (trail.closed && trail.captures) canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }
        // Proximity halo warns the player when the hunter is close to a survivor.
        val closestDistance = survivors.minOfOrNull { s ->
            val dx = s.x - hunterX
            val dy = s.y - hunterY
            sqrt(dx * dx + dy * dy)
        } ?: Float.MAX_VALUE
        if (closestDistance < dp(165f)) {
            val warningPaint = Paint(3).apply {
                color = Color.rgb(220, 70, 70)
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                alpha = (90 + 100 * (1f - closestDistance / dp(165f))).toInt().coerceIn(90, 190)
            }
            canvas.drawCircle(hunterX, hunterY, hunterRadius + dp(8f), warningPaint)
        }
        // Draw the ink hunter as an irregular-looking black mass with white eyes.
        canvas.drawCircle(hunterX, hunterY, hunterRadius, inkPaint)
        canvas.drawCircle(hunterX - hunterRadius * .27f, hunterY - hunterRadius * .15f,
            hunterRadius * .10f, whitePaint)
        canvas.drawCircle(hunterX + hunterRadius * .22f, hunterY - hunterRadius * .15f,
            hunterRadius * .10f, whitePaint)
        val angry = Paint(3).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(hunterX - hunterRadius * .42f, hunterY - hunterRadius * .38f,
            hunterX - hunterRadius * .12f, hunterY - hunterRadius * .28f, angry)
        canvas.drawLine(hunterX + hunterRadius * .08f, hunterY - hunterRadius * .28f,
            hunterX + hunterRadius * .38f, hunterY - hunterRadius * .38f, angry)
        canvas.drawArc(RectF(hunterX - hunterRadius * .22f, hunterY + hunterRadius * .05f,
            hunterX + hunterRadius * .22f, hunterY + hunterRadius * .38f), 15f, 150f, false, angry)

        survivors.forEach { survivor ->
            val r = radiusFor(survivor) * (if (survivor.id == selectedId) 1.08f else 1f)
            // A wobbling closed contour makes each blob feel soft instead of like a rigid circle.
            val now = System.currentTimeMillis()
            val points = 28
            val contour = Path()
            for (i in 0 until points) {
                val angle = (i.toFloat() / points) * (Math.PI * 2.0).toFloat()
                val wobble = 1f +
                    .055f * kotlin.math.sin(angle * 3f + now / 170.0 + survivor.id).toFloat() +
                    .035f * kotlin.math.sin(angle * 5f - now / 230.0 + survivor.mass).toFloat()
                val px = survivor.x + kotlin.math.cos(angle.toDouble()).toFloat() * r * wobble
                val py = survivor.y + kotlin.math.sin(angle.toDouble()).toFloat() * r * wobble
                if (i == 0) contour.moveTo(px, py) else contour.lineTo(px, py)
            }
            contour.close()
            canvas.drawPath(contour, survivorPaint)
            canvas.drawPath(contour, outlinePaint)
            if (survivor.mergePulse > 0f) {
                val pulsePaint = Paint(3).apply {
                    color = Color.rgb(105, 185, 225)
                    style = Paint.Style.STROKE
                    strokeWidth = dp(2f)
                    alpha = (survivor.mergePulse * 180f).toInt().coerceIn(0, 180)
                }
                canvas.drawCircle(survivor.x, survivor.y, r + dp(5f + 18f * (1f - survivor.mergePulse)), pulsePaint)
            }
            if (survivor.panic > 0.15f) {
                val panicPaint = Paint(3).apply {
                    color = Color.rgb(220, 70, 70)
                    style = Paint.Style.STROKE
                    strokeWidth = dp(1.5f)
                    alpha = (70 + survivor.panic * 150f).toInt().coerceIn(70, 220)
                }
                canvas.drawCircle(survivor.x, survivor.y, r + dp(4f + 5f * survivor.panic), panicPaint)
            }
            val eyeY = survivor.y - r * .14f
            canvas.drawCircle(survivor.x - r * .27f, eyeY, r * .075f, inkPaint)
            canvas.drawCircle(survivor.x + r * .27f, eyeY, r * .075f, inkPaint)
            val face = Paint(3).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND }
            if (survivor.panic > .5f) {
                canvas.drawCircle(survivor.x, survivor.y + r * .28f, r * .10f, face)
            } else {
                canvas.drawArc(RectF(survivor.x - r * .22f, survivor.y + r * .02f,
                    survivor.x + r * .22f, survivor.y + r * .36f), 15f, 150f, false, face)
            }
            if (survivor.id == selectedId) {
                val ring = Paint(3).apply { color = Color.rgb(90, 145, 105); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
                canvas.drawCircle(survivor.x, survivor.y, r + dp(5f), ring)
            }
        }
        canvas.restore()

        val footerY = height - dp(43f)
        val status = when {
            finished -> "CAUGHT! Tap RESTART to try again"
            running -> "ESCAPE THE INK LOOP — cross the line to break it"
            else -> "Touch a white blob to begin"
        }
        smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(status, width / 2f, footerY, smallPaint)
        smallPaint.textAlign = Paint.Align.LEFT
        restart.set(width - dp(110f), height - dp(32f), width - dp(18f), height - dp(8f))
        canvas.drawRoundRect(restart, dp(12f), dp(12f), buttonPaint)
        canvas.drawText("RESTART", restart.centerX(), restart.centerY() + dp(4f), buttonText)

        if (running && !finished) {
            val now = System.currentTimeMillis()
            if (lastFrame == 0L) lastFrame = now
            val dt = ((now - lastFrame).coerceIn(1L, 32L) / 1000f)
            lastFrame = now
            updateGame(dt, now)
            postInvalidateOnAnimation()
        }
    }


    // Test the hunter's centre line against walls expanded by its radius.
    // Fine sampling includes both endpoints to avoid routes that cut wall corners.
    private fun pathBlocked(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = sqrt(dx * dx + dy * dy)
        val steps = max(2, kotlin.math.ceil(distance / dp(4f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val px = x1 + dx * t
            val py = y1 + dy * t
            if (walls.any { wall ->
                px >= wall.left - hunterRadius && px <= wall.right + hunterRadius &&
                    py >= wall.top - hunterRadius && py <= wall.bottom + hunterRadius
            }) return true
        }
        return false
    }

    private fun routeGoal(targetX: Float, targetY: Float): Pair<Float, Float> {
        var gx = targetX
        var gy = targetY
        // A survivor can stand closer to a wall than the hunter can. Project the
        // destination out of the hunter's clearance zone so the route has a valid end.
        repeat(walls.size + 1) {
            walls.forEach { wall ->
                val left = wall.left - hunterRadius - dp(3f)
                val right = wall.right + hunterRadius + dp(3f)
                val top = wall.top - hunterRadius - dp(3f)
                val bottom = wall.bottom + hunterRadius + dp(3f)
                if (gx >= left && gx <= right && gy >= top && gy <= bottom) {
                    val options = listOf(
                        (abs(gx - left)) to (left to gy),
                        (abs(right - gx)) to (right to gy),
                        (abs(gy - top)) to (gx to top),
                        (abs(bottom - gy)) to (gx to bottom)
                    )
                    val best = options.minByOrNull { it.first }!!.second
                    gx = best.first
                    gy = best.second
                }
            }
        }
        gx = gx.coerceIn(arena.left + hunterRadius, arena.right - hunterRadius)
        gy = gy.coerceIn(arena.top + hunterRadius, arena.bottom - hunterRadius)
        return gx to gy
    }

    private fun hunterSteeringTarget(targetX: Float, targetY: Float): Pair<Float, Float> {
        val goal = routeGoal(targetX, targetY)
        if (!pathBlocked(hunterX, hunterY, goal.first, goal.second)) return goal

        val clearance = hunterRadius + dp(8f)
        val corners = walls.flatMap { wall ->
            listOf(
                (wall.left - clearance) to (wall.top - clearance),
                (wall.right + clearance) to (wall.top - clearance),
                (wall.left - clearance) to (wall.bottom + clearance),
                (wall.right + clearance) to (wall.bottom + clearance)
            )
        }.filter { (x, y) ->
            x >= arena.left + hunterRadius && x <= arena.right - hunterRadius &&
                y >= arena.top + hunterRadius && y <= arena.bottom - hunterRadius &&
                walls.none { wall ->
                    x >= wall.left - hunterRadius && x <= wall.right + hunterRadius &&
                        y >= wall.top - hunterRadius && y <= wall.bottom + hunterRadius
                }
        }

        // Visibility graph + Dijkstra: choose the first waypoint on the shortest
        // collision-free route, not just a corner that happens to look promising.
        val nodes = mutableListOf(hunterX to hunterY, goal)
        corners.forEach { corner ->
            if (nodes.none { abs(it.first - corner.first) < dp(1f) && abs(it.second - corner.second) < dp(1f) }) {
                nodes += corner
            }
        }
        val count = nodes.size
        val distances = FloatArray(count) { Float.POSITIVE_INFINITY }
        val previous = IntArray(count) { -1 }
        val visited = BooleanArray(count)
        distances[0] = 0f
        repeat(count) {
            var current = -1
            var best = Float.POSITIVE_INFINITY
            for (i in 0 until count) {
                if (!visited[i] && distances[i] < best) {
                    best = distances[i]
                    current = i
                }
            }
            if (current < 0) return@repeat
            if (current == 1) return@repeat
            visited[current] = true
            for (next in 1 until count) {
                if (next == current || visited[next]) continue
                val a = nodes[current]
                val b = nodes[next]
                if (pathBlocked(a.first, a.second, b.first, b.second)) continue
                val dx = b.first - a.first
                val dy = b.second - a.second
                val candidate = distances[current] + sqrt(dx * dx + dy * dy)
                if (candidate < distances[next]) {
                    distances[next] = candidate
                    previous[next] = current
                }
            }
        }
        if (!distances[1].isFinite()) {
            // The random generator keeps routes open; if a rare corner case remains,
            // move toward the closest reachable corner rather than pushing into a wall.
            return corners.filterNot { pathBlocked(hunterX, hunterY, it.first, it.second) }
                .minByOrNull { (x, y) ->
                    val tx = goal.first - x
                    val ty = goal.second - y
                    tx * tx + ty * ty
                } ?: (hunterX to hunterY)
        }
        var step = 1
        while (previous[step] > 0) step = previous[step]
        return nodes[step]
    }

    private fun mergeNearbySurvivors() {
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in survivors.indices) {
                for (j in i + 1 until survivors.size) {
                    val a = survivors[i]
                    val b = survivors[j]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val combinedRadius = radiusFor(a) + radiusFor(b)
                    if (dx * dx + dy * dy < combinedRadius * combinedRadius * .72f) {
                        val keep = if (selectedId == b.id) b else a
                        val lose = if (keep === a) b else a
                        val total = a.mass + b.mass
                        keep.x = (a.x * a.mass + b.x * b.mass) / total
                        keep.y = (a.y * a.mass + b.y * b.mass) / total
                        keep.vx = (a.vx * a.mass + b.vx * b.mass) / total
                        keep.vy = (a.vy * a.mass + b.vy * b.mass) / total
                        keep.mass = total
                        keep.mergePulse = 1f
                        if (selectedId == lose.id) selectedId = keep.id
                        survivors.remove(lose)
                        val safe = collideWithArena(keep.x, keep.y, radiusFor(keep))
                        keep.x = safe.first
                        keep.y = safe.second
                        changed = true
                        break@outer
                    }
                }
            }
        }
    }

    private fun updateGame(dt: Float, now: Long) {
        mergeNearbySurvivors()
        inkTrails.forEach { if (it.closed) it.age += dt }
        inkTrails.removeAll { it.closed && it.age >= 9f }
        if (survivors.isEmpty()) {
            running = false
            finished = true
            finalTime = now
            return
        }
        val target = survivors.minByOrNull {
            val dx = it.x - hunterX
            val dy = it.y - hunterY
            dx * dx + dy * dy
        } ?: return

        // The hunter maintains a no-contact buffer and circles a fixed target position,
        // painting an ink boundary as it goes. White blobs can escape before closure.
        if (activeTrail == null || orbitTargetId != target.id) {
            activeTrail?.let { previousTrail ->
                // An abandoned, incomplete stroke fades as a line; never render it
                // as a filled polygon or treat it as a capture.
                previousTrail.closed = true
                previousTrail.captures = false
                previousTrail.age = 0f
            }
            orbitTargetId = target.id
            orbitCenterX = target.x
            orbitCenterY = target.y
            orbitAngle = kotlin.math.atan2(hunterY - orbitCenterY, hunterX - orbitCenterX)
            val initialSafeRadius = hunterRadius + radiusFor(target) + dp(52f)
            val startX = orbitCenterX + kotlin.math.cos(orbitAngle) * initialSafeRadius
            val startY = orbitCenterY + kotlin.math.sin(orbitAngle) * initialSafeRadius
            activeTrail = InkTrail(mutableListOf(startX to startY))
            inkTrails += activeTrail!!
        }
        val safeRadius = hunterRadius + radiusFor(target) + dp(52f)
        orbitAngle += dt * 0.55f
        val orbitX = orbitCenterX + kotlin.math.cos(orbitAngle) * safeRadius
        val orbitY = orbitCenterY + kotlin.math.sin(orbitAngle) * safeRadius
        val dx = orbitX - hunterX
        val dy = orbitY - hunterY
        val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val hunterSpeed = dp(70f)
        hunterVx = dx / distance * hunterSpeed
        hunterVy = dy / distance * hunterSpeed
        val nextHunter = collideWithArena(hunterX + hunterVx * dt, hunterY + hunterVy * dt, hunterRadius)
        hunterX = nextHunter.first
        hunterY = nextHunter.second

        val trail = activeTrail
        if (trail != null) {
            val last = trail.points.lastOrNull()
            if (last == null || distanceBetween(last.first, last.second, hunterX, hunterY) >= dp(5f)) {
                trail.points += hunterX to hunterY
            }
            val start = trail.points.first()
            val travelled = trail.points.zipWithNext().sumOf { (a, b) ->
                distanceBetween(a.first, a.second, b.first, b.second).toDouble()
            }.toFloat()
            val closedByStart = trail.points.size > 24 &&
                travelled > safeRadius * 3.8f &&
                distanceBetween(hunterX, hunterY, start.first, start.second) < dp(22f)
            val connected = trail.points.size > 12 && touchesExistingTrail(trail)
            if (closedByStart || connected) {
                trail.closed = true
                trail.age = 0f
                trail.captures = isValidCapturePolygon(trail.points, dp(18f) * dp(18f))
                if (trail.captures) captureInside(trail, now)
                activeTrail = null
                orbitTargetId = null
            }
        }

        survivors.toList().forEach { s ->
            val tx = s.x - hunterX
            val ty = s.y - hunterY
            val dist = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
            s.panic = (1f - dist / dp(150f)).coerceIn(0f, 1f)
            if (s.id != selectedId || !dragging) {
                val flee = if (dist < dp(135f)) 1f else 0f
                val wanderX = kotlin.math.sin((now / 700.0) + s.id).toFloat() * dp(12f)
                val wanderY = kotlin.math.cos((now / 900.0) + s.id * 2).toFloat() * dp(12f)
                // Nearby blobs attract each other like liquid droplets, making
                // merging a deliberate, visible part of the survival loop.
                var mergeX = 0f
                var mergeY = 0f
                var mergeStrength = 0f
                survivors.forEach { other ->
                    if (other.id != s.id) {
                        val ox = other.x - s.x
                        val oy = other.y - s.y
                        val od = sqrt(ox * ox + oy * oy).coerceAtLeast(1f)
                        val reach = (radiusFor(s) + radiusFor(other)) * 2.5f
                        if (od < reach) {
                            val strength = (1f - od / reach).coerceIn(0f, 1f)
                            mergeX += ox / od * strength
                            mergeY += oy / od * strength
                            mergeStrength = max(mergeStrength, strength)
                        }
                    }
                }
                val desiredX = -tx / dist * dp(46f) * flee + wanderX +
                    mergeX * dp(32f) * (1f - flee * .35f)
                val desiredY = -ty / dist * dp(46f) * flee + wanderY +
                    mergeY * dp(32f) * (1f - flee * .35f)
                s.vx += (desiredX - s.vx) * min(1f, dt * (3f + mergeStrength * 2f))
                s.vy += (desiredY - s.vy) * min(1f, dt * 3f)
                val next = collideWithArena(s.x + s.vx * dt, s.y + s.vy * dt, radiusFor(s))
                s.x = next.first
                s.y = next.second
            }
            s.mergePulse = (s.mergePulse - dt * 1.6f).coerceAtLeast(0f)
        }
        // Crossing a still-forming boundary breaks the entire drawing, giving the
        // player a readable counterplay instead of instant hunter contact kills.
        val drawing = activeTrail
        if (drawing != null && drawing.points.size >= 2) {
            val cut = survivors.any { s ->
                drawing.points.zipWithNext().any { (a, b) ->
                    distanceToSegment(s.x, s.y, a.first, a.second, b.first, b.second) <= radiusFor(s) + dp(4f)
                }
            }
            if (cut) {
                inkTrails.remove(drawing)
                activeTrail = null
                orbitTargetId = null
            }
        }
    }

    private fun distanceBetween(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= .001f) return distanceBetween(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distanceBetween(px, py, ax + t * dx, ay + t * dy)
    }

    private fun touchesExistingTrail(trail: InkTrail): Boolean {
        val point = trail.points.lastOrNull() ?: return false
        val other = inkTrails.firstOrNull { candidate ->
            candidate !== trail && candidate.points.size >= 3 &&
                candidate.points.zipWithNext().any { (a, b) ->
                    distanceToSegment(point.first, point.second, a.first, a.second, b.first, b.second) < dp(14f)
                }
        }
        if (other != null) {
            // Join the new stroke to the closest existing ink contour. Treat the
            // combined outline as one liquid region, rather than two isolated rings.
            val joinIndex = other.points.indices.minByOrNull { index ->
                distanceBetween(point.first, point.second, other.points[index].first, other.points[index].second)
            } ?: return false
            val joined = other.points.take(joinIndex + 1) + trail.points.asReversed() +
                other.points.drop(joinIndex + 1)
            trail.points.clear()
            trail.points.addAll(joined)
            inkTrails.remove(other)
            return true
        }
        return false
    }

    private fun isValidCapturePolygon(points: List<Pair<Float, Float>>, minArea: Float): Boolean {
        if (points.size < 4) return false
        if (polygonArea(points) < minArea) return false

        // Test every edge, including the implicit edge from the final point back
        // to the first. Adjacent edges share a vertex by design and are skipped.
        val edgeCount = points.size
        for (i in 0 until edgeCount) {
            val a = points[i]
            val b = points[(i + 1) % edgeCount]
            for (j in i + 1 until edgeCount) {
                if (j == i + 1 || (i == 0 && j == edgeCount - 1)) continue
                val c = points[j]
                val d = points[(j + 1) % edgeCount]
                if (segmentsIntersect(a.first, a.second, b.first, b.second,
                        c.first, c.second, d.first, d.second)) return false
            }
        }
        return true
    }

    private fun polygonArea(points: List<Pair<Float, Float>>): Float {
        var twiceArea = 0f
        for (i in points.indices) {
            val next = points[(i + 1) % points.size]
            val current = points[i]
            twiceArea += current.first * next.second - next.first * current.second
        }
        return abs(twiceArea) * .5f
    }

    private fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ): Boolean {
        fun orientation(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Float =
            (qx - px) * (ry - py) - (qy - py) * (rx - px)
        fun onSegment(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Boolean =
            rx >= min(px, qx) - .01f && rx <= max(px, qx) + .01f &&
                ry >= min(py, qy) - .01f && ry <= max(py, qy) + .01f

        val o1 = orientation(ax, ay, bx, by, cx, cy)
        val o2 = orientation(ax, ay, bx, by, dx, dy)
        val o3 = orientation(cx, cy, dx, dy, ax, ay)
        val o4 = orientation(cx, cy, dx, dy, bx, by)
        if ((o1 > 0f && o2 < 0f || o1 < 0f && o2 > 0f) &&
            (o3 > 0f && o4 < 0f || o3 < 0f && o4 > 0f)) return true
        if (abs(o1) < .01f && onSegment(ax, ay, bx, by, cx, cy)) return true
        if (abs(o2) < .01f && onSegment(ax, ay, bx, by, dx, dy)) return true
        if (abs(o3) < .01f && onSegment(cx, cy, dx, dy, ax, ay)) return true
        if (abs(o4) < .01f && onSegment(cx, cy, dx, dy, bx, by)) return true
        return false
    }

    private fun captureInside(trail: InkTrail, now: Long) {
        if (trail.points.size < 4 || !trail.captures) return
        // Match Papersseum's enclosure rule: mark the ink as a wall, flood-fill
        // from the arena's outside, and capture only blobs in unreachable cells.
        val captured = floodFilledSurvivors(trail.points)
        captured.forEach { survivor ->
            survivors.remove(survivor)
            if (selectedId == survivor.id) {
                selectedId = null
                dragging = false
            }
        }
        if (survivors.isEmpty()) {
            running = false
            finished = true
            finalTime = now
        }
    }

    private fun floodFilledSurvivors(points: List<Pair<Float, Float>>): List<Survivor> {
        val cellSize = dp(6f).coerceAtLeast(1f)
        val cols = kotlin.math.ceil(arena.width() / cellSize).toInt().coerceAtLeast(1)
        val rows = kotlin.math.ceil(arena.height() / cellSize).toInt().coerceAtLeast(1)
        val blocked = BooleanArray(cols * rows)
        val reachable = BooleanArray(cols * rows)

        fun cellX(x: Float) = ((x - arena.left) / cellSize).toInt().coerceIn(0, cols - 1)
        fun cellY(y: Float) = ((y - arena.top) / cellSize).toInt().coerceIn(0, rows - 1)
        fun markWall(x: Float, y: Float) {
            val cx = cellX(x)
            val cy = cellY(y)
            // Thicken the rasterized line one cell so diagonal strokes cannot
            // leave pinholes in the 4-neighbour flood fill.
            for (dy in -1..1) for (dx in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until cols && ny in 0 until rows) blocked[ny * cols + nx] = true
            }
        }

        // Sample every edge, including the implicit closing edge, densely enough
        // that fast finger movement still creates a continuous digital boundary.
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val distance = sqrt(dx * dx + dy * dy)
            val steps = kotlin.math.ceil(distance / (cellSize * .45f)).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val t = step.toFloat() / steps
                markWall(a.first + dx * t, a.second + dy * t)
            }
        }

        val queue = IntArray(cols * rows)
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

        return survivors.filter { survivor ->
            if (!arena.contains(survivor.x, survivor.y)) return@filter false
            val x = cellX(survivor.x)
            val y = cellY(survivor.y)
            val id = y * cols + x
            !blocked[id] && !reachable[id]
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (restart.contains(x, y)) {
                    reset()
                    setupArena()
                    invalidate()
                    return true
                }
                if (!running && !finished && arena.contains(x, y)) {
                    running = true
                    startTime = System.currentTimeMillis()
                    lastFrame = startTime
                    activeTrail = null
                    orbitTargetId = null
                }
                val target = survivors.minByOrNull {
                    val dx = it.x - x
                    val dy = it.y - y
                    dx * dx + dy * dy
                }
                if (target != null) {
                    val dx = target.x - x
                    val dy = target.y - y
                    if (dx * dx + dy * dy < (radiusFor(target) + dp(25f)).let { it * it }) {
                        selectedId = target.id
                        dragging = true
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val s = survivors.firstOrNull { it.id == selectedId }
                    if (s != null) {
                        val point = collideWithArena(x, y, radiusFor(s))
                        s.x = point.first
                        s.y = point.second
                        s.vx = 0f
                        s.vy = 0f
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                selectedId = null
                invalidate()
                return true
            }
        }
        return true
    }
}
