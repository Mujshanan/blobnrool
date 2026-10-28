package com.example.meatballmerge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private data class Survivor(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var panic: Float = 0f
)

class MeatballGameView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val survivorRadius get() = dp(17f)
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
        val hudBottom = dp(92f)
        arena.set(margin, hudBottom, width - margin, height - dp(72f))
        walls.clear()
        val w = arena.width()
        val h = arena.height()
        if (w <= dp(100f) || h <= dp(150f)) return
        // Rounded solid obstacles create corners, cover, and alternative routes.
        walls += RectF(arena.left + w * .28f, arena.top + h * .19f,
            arena.left + w * .37f, arena.top + h * .48f)
        walls += RectF(arena.left + w * .62f, arena.top + h * .50f,
            arena.left + w * .72f, arena.top + h * .79f)
        walls += RectF(arena.left + w * .43f, arena.top + h * .68f,
            arena.left + w * .57f, arena.top + h * .74f)
        if (survivors.isEmpty() && !running && !finished) {
            val positions = listOf(
                .13f to .22f, .76f to .18f, .85f to .40f,
                .16f to .58f, .47f to .35f, .82f to .86f
            )
            positions.forEach { (px, py) ->
                val point = findFreePoint(arena.left + w * px, arena.top + h * py, survivorRadius)
                survivors += Survivor(nextId++, point.first, point.second)
            }
            val hp = findFreePoint(arena.left + w * .50f, arena.top + h * .16f, hunterRadius)
            hunterX = hp.first
            hunterY = hp.second
        }
    }

    private fun findFreePoint(x: Float, y: Float, radius: Float): Pair<Float, Float> {
        var px = x.coerceIn(arena.left + radius, arena.right - radius)
        var py = y.coerceIn(arena.top + radius, arena.bottom - radius)
        repeat(8) {
            val obstacle = walls.firstOrNull { rect ->
                val cx = px.coerceIn(rect.left, rect.right)
                val cy = py.coerceIn(rect.top, rect.bottom)
                val dx = px - cx
                val dy = py - cy
                dx * dx + dy * dy < radius * radius
            }
            if (obstacle != null) {
                px = (obstacle.right + radius + dp(8f)).coerceAtMost(arena.right - radius)
                py = (obstacle.bottom + radius + dp(8f)).coerceAtMost(arena.bottom - radius)
            }
        }
        return px to py
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
            val r = survivorRadius * (if (survivor.id == selectedId) 1.08f else 1f)
            canvas.drawCircle(survivor.x, survivor.y, r, survivorPaint)
            canvas.drawCircle(survivor.x, survivor.y, r, outlinePaint)
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
            running -> "Drag a white blob away from the ink"
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

    private fun updateGame(dt: Float, now: Long) {
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
        val dx = target.x - hunterX
        val dy = target.y - hunterY
        val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val hunterSpeed = dp(54f)
        hunterVx = dx / distance * hunterSpeed
        hunterVy = dy / distance * hunterSpeed
        val nextHunter = collideWithArena(hunterX + hunterVx * dt, hunterY + hunterVy * dt, hunterRadius)
        hunterX = nextHunter.first
        hunterY = nextHunter.second

        survivors.toList().forEach { s ->
            val tx = s.x - hunterX
            val ty = s.y - hunterY
            val dist = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
            s.panic = (1f - dist / dp(150f)).coerceIn(0f, 1f)
            if (s.id != selectedId || !dragging) {
                val flee = if (dist < dp(135f)) 1f else 0f
                val wanderX = kotlin.math.sin((now / 700.0) + s.id).toFloat() * dp(12f)
                val wanderY = kotlin.math.cos((now / 900.0) + s.id * 2).toFloat() * dp(12f)
                val desiredX = -tx / dist * dp(46f) * flee + wanderX
                val desiredY = -ty / dist * dp(46f) * flee + wanderY
                s.vx += (desiredX - s.vx) * min(1f, dt * 3f)
                s.vy += (desiredY - s.vy) * min(1f, dt * 3f)
                val next = collideWithArena(s.x + s.vx * dt, s.y + s.vy * dt, survivorRadius)
                s.x = next.first
                s.y = next.second
            }
            val cx = s.x - hunterX
            val cy = s.y - hunterY
            if (cx * cx + cy * cy < (hunterRadius + survivorRadius * .62f).let { it * it }) {
                survivors.remove(s)
                if (selectedId == s.id) {
                    selectedId = null
                    dragging = false
                }
            }
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
                }
                val target = survivors.minByOrNull {
                    val dx = it.x - x
                    val dy = it.y - y
                    dx * dx + dy * dy
                }
                if (target != null) {
                    val dx = target.x - x
                    val dy = target.y - y
                    if (dx * dx + dy * dy < dp(42f) * dp(42f)) {
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
                        val point = collideWithArena(x, y, survivorRadius)
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
