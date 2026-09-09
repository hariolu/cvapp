package com.cvapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The drifting gold constellation — the same one behind coyotes.vision and the
 * dashboard, drawn natively so the app's own screens share the house look
 * instead of ending at the edge of the WebView.
 *
 * Ported from bg-gold.js rather than reinvented: same palette, same linking
 * distance, same slow pulse. Two things had to change on the way across. The
 * web copy counts in CSS pixels, which are only accidentally the same size as
 * a dp, so every distance here is stated in dp and multiplied by the display
 * density. And it advances by one unit per animation frame, which on a 120 Hz
 * phone would run the whole sky at double speed — so motion is scaled by the
 * time a frame actually took.
 *
 * The background is an ordinary View background: set `android:background` in
 * the layout and this draws the stars over whatever that is. Inside a rounded
 * card, give the parent `clipToOutline`.
 */
class ConstellationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        // Gold, dark gold, bright amber, bronze, pale gold, deep bronze.
        val PALETTE = arrayOf(
            intArrayOf(200, 160, 50),
            intArrayOf(180, 120, 40),
            intArrayOf(210, 140, 60),
            intArrayOf(160, 110, 50),
            intArrayOf(190, 150, 70),
            intArrayOf(140, 90, 30),
        )

        const val CONNECT_DIST_DP = 135f
        const val LINE_ALPHA      = 0.11f
        const val DOT_MIN_DP      = 0.8f
        const val DOT_MAX_DP      = 2.4f
        const val SPEED_MIN_DP    = 0.04f     // per 60 Hz frame, as on the web
        const val SPEED_MAX_DP    = 0.18f
        const val ALPHA_MIN       = 0.13f
        const val ALPHA_MAX       = 0.42f
        const val PULSE_SPEED     = 0.0035f
        const val GLOW_RADIUS     = 9f        // multiples of the dot's own radius
        const val GLOW_ALPHA      = 0.025f
        const val FRAME_MS        = 16.667f

        // Linking every dot to every other is O(n²) a frame, so the count is
        // area-derived rather than fixed: one per 100dp square, which is what
        // the web copy works out to on a phone-sized viewport. The bounds keep
        // a dialog card and a tablet equally sensible.
        const val DOT_SPACING_DP  = 100f
        const val DOTS_MIN        = 12
        const val DOTS_MAX        = 56
    }

    private class Dot(
        var x: Float, var y: Float,
        val vx: Float, val vy: Float,
        val r: Float,
        val colour: IntArray,
        val base: Float,
        val phase: Float,
    ) {
        var alpha = base
        /** Built once: it only depends on the radius, and allocating a gradient a frame is how a background starts costing something. */
        var glow: RadialGradient? = null
    }

    private val density = resources.displayMetrics.density
    private val random  = Random(System.nanoTime())
    private val dotPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var dots: Array<Dot> = emptyArray()
    private var frames = 0f
    private var lastFrameAt = 0L
    private var running = false

    /** Someone who has turned animations off is asking for a still sky, not a bare one. */
    private val stillOnly: Boolean
        get() = runCatching {
            Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

    private fun dp(value: Float) = value * density

    private fun rnd(from: Float, to: Float) = random.nextFloat() * (to - from) + from

    private fun rebuild(width: Int, height: Int) {
        val cells = (width / dp(DOT_SPACING_DP)) * (height / dp(DOT_SPACING_DP))
        val count = min(DOTS_MAX, max(DOTS_MIN, cells.toInt()))
        dots = Array(count) {
            Dot(
                x = rnd(0f, width.toFloat()),
                y = rnd(0f, height.toFloat()),
                vx = dp(rnd(SPEED_MIN_DP, SPEED_MAX_DP)) * if (random.nextBoolean()) 1f else -1f,
                vy = dp(rnd(SPEED_MIN_DP, SPEED_MAX_DP)) * if (random.nextBoolean()) 1f else -1f,
                r = dp(rnd(DOT_MIN_DP, DOT_MAX_DP)),
                colour = PALETTE[random.nextInt(PALETTE.size)],
                base = rnd(ALPHA_MIN, ALPHA_MAX),
                phase = rnd(0f, (Math.PI * 2).toFloat()),
            )
        }
    }

    /**
     * A backdrop asks for nothing.
     *
     * Left alone a plain View takes whatever it is offered, and inside a card
     * that wraps its content that means the card grows to the whole screen —
     * the sky ends up deciding how big the sign-in dialog is. Claiming no space
     * hands that decision back to the content; FrameLayout then measures a
     * match_parent child a second time against the size the content settled on,
     * which is how this still covers the card exactly.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun claim(spec: Int) =
            if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(claim(widthMeasureSpec), claim(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) rebuild(w, h)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // A sky nobody is looking at has no business burning a frame budget.
        running = isVisible && !stillOnly
        lastFrameAt = 0L
        if (running) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dots.isEmpty()) return

        if (running) {
            val now = System.nanoTime()
            // The first frame after a pause has no previous one to measure against;
            // calling it a single frame keeps the sky from lurching on resume.
            val elapsed = if (lastFrameAt == 0L) FRAME_MS
            else min(80f, (now - lastFrameAt) / 1_000_000f)
            lastFrameAt = now
            advance(elapsed / FRAME_MS)
        }

        drawLinks(canvas)
        drawDots(canvas)

        if (running) postInvalidateOnAnimation()
    }

    private fun advance(steps: Float) {
        frames += steps
        val margin = dp(20f)
        val w = width.toFloat()
        val h = height.toFloat()
        for (dot in dots) {
            dot.x += dot.vx * steps
            dot.y += dot.vy * steps
            if (dot.x < -margin) dot.x = w + margin else if (dot.x > w + margin) dot.x = -margin
            if (dot.y < -margin) dot.y = h + margin else if (dot.y > h + margin) dot.y = -margin
            dot.alpha = max(0.04f, min(0.7f,
                dot.base + sin(dot.phase + frames * PULSE_SPEED) * 0.09f))
        }
    }

    private fun drawLinks(canvas: Canvas) {
        val reach = dp(CONNECT_DIST_DP)
        for (i in dots.indices) {
            val a = dots[i]
            for (j in i + 1 until dots.size) {
                val b = dots[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance >= reach) continue
                val nearness = 1f - distance / reach
                linePaint.color = rgba(
                    (a.colour[0] + b.colour[0]) shr 1,
                    (a.colour[1] + b.colour[1]) shr 1,
                    (a.colour[2] + b.colour[2]) shr 1,
                    nearness * nearness * LINE_ALPHA,
                )
                linePaint.strokeWidth = dp(0.4f + nearness * 0.5f)
                canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
            }
        }
    }

    private fun drawDots(canvas: Canvas) {
        for (dot in dots) {
            val (r, g, b) = Triple(dot.colour[0], dot.colour[1], dot.colour[2])
            val glowRadius = dot.r * GLOW_RADIUS
            val glow = dot.glow ?: RadialGradient(
                0f, 0f, glowRadius,
                rgba(r, g, b, GLOW_ALPHA), rgba(r, g, b, 0f),
                Shader.TileMode.CLAMP,
            ).also { dot.glow = it }

            val saved = canvas.save()
            canvas.translate(dot.x, dot.y)
            glowPaint.shader = glow
            canvas.drawCircle(0f, 0f, glowRadius, glowPaint)
            canvas.restoreToCount(saved)

            dotPaint.color = rgba(r, g, b, dot.alpha)
            canvas.drawCircle(dot.x, dot.y, dot.r, dotPaint)
        }
    }

    private fun rgba(r: Int, g: Int, b: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
}
