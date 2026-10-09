package com.cvapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The background behind Coyotes Vision: the person's style, in their colours, at
 * their frame rate. The same six styles cv-look.js draws behind Home Assistant's
 * pages and the CV Panel, ported line for line, so the app's own screens (loading,
 * settings, sign-in) run on the background the dashboard then opens onto.
 *
 * The numbers are the web copy's. It counts in CSS pixels, which on a phone are
 * dp, so the canvas is scaled by the density and every measure below reads as it
 * does in cv-look.js. Motion is scaled by the time a frame actually took (k), as
 * there, so 30 and 60 fps — and a 120 Hz screen — move at the same speed.
 *
 * The view's own colour is the person's background colour; set the look with
 * [setLook]. Inside a rounded card, give the parent `clipToOutline`.
 */
class BackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class Mode { RUN, STILL, OFF }

    private var styleId = Look.DEFAULT.style
    private var pal: Array<IntArray> = Look.DEFAULT.dots
    private var fps = Look.DEFAULT.fps
    private var animate = Look.DEFAULT.animate
    private var background = Look.DEFAULT.bg
    private var scene: Scene? = null

    private val density = resources.displayMetrics.density
    private val random = Random(System.nanoTime())

    /** Set while something opaque covers this view: a sky nobody can see should not cost a frame. */
    var paused = false
        set(value) { field = value; sync() }

    private var shown = false
    private var running = false
    private var lastFrame = 0L
    private var firstFrame = 0L
    private var steps = 0f

    /** Someone who has turned animations off gets the style standing still, as the web copy does. */
    private val stillOnly: Boolean
        get() = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

    private val mode: Mode
        get() = when {
            !animate -> Mode.OFF
            stillOnly -> Mode.STILL
            else -> Mode.RUN
        }

    fun setLook(look: Look) {
        if (look.style != styleId || !look.dots.contentDeepEquals(pal)) scene = null
        styleId = look.style
        pal = look.dots
        fps = look.fps
        animate = look.animate
        background = look.bg
        sync()
        invalidate()
    }

    // ── Frames ────────────────────────────────────────────────────────────────
    // Choreographer rather than invalidating from onDraw: at 30 fps every other
    // vsync must be skipped, and a View cannot skip a frame it was asked to draw.

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            if (lastFrame != 0L && now - lastFrame < 1_000_000_000L / fps - 2_000_000L) return
            val step = if (lastFrame == 0L) 1f else (now - lastFrame) / 16_666_667f
            if (firstFrame == 0L) firstFrame = now
            lastFrame = now
            steps = min(4f, steps + step)
            invalidate()
        }
    }

    private fun sync() {
        val run = shown && !paused && mode == Mode.RUN
        if (run == running) return
        running = run
        lastFrame = 0L
        if (run) Choreographer.getInstance().postFrameCallback(frame)
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        sync()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shown = false
        sync()
    }

    /**
     * A backdrop asks for no room of its own. Left alone a View takes all it is
     * offered, and inside a card that wraps its content the sky would decide how
     * big the card is; claiming nothing hands that back to the content, and
     * FrameLayout then measures this match_parent child again at the size the
     * content settled on.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun claim(spec: Int) =
            if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(claim(widthMeasureSpec), claim(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scene = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background)
        val mode = mode
        if (mode == Mode.OFF || width == 0 || height == 0) return
        val w = width / density
        val h = height / density
        val current = scene ?: build(w, h).also { scene = it }
        val k = if (mode == Mode.RUN) steps else 0f
        steps = 0f
        val t = if (mode == Mode.RUN && firstFrame != 0L) (lastFrame - firstFrame) / 1_000_000_000f else 0f
        val saved = canvas.save()
        canvas.scale(density, density)
        current.draw(canvas, t, k)
        canvas.restoreToCount(saved)
    }

    private fun build(w: Float, h: Float): Scene = when (styleId) {
        "bubbles" -> Bubbles(w, h)
        "lines" -> Lines(w, h)
        "squares" -> Squares(w, h)
        "stars" -> Stars(w, h)
        "grid" -> Grid(w, h)
        else -> Constellation(w, h)
    }

    // ── Shared bits, named as in cv-look.js ──────────────────────────────────

    private fun rnd(a: Float, b: Float) = random.nextFloat() * (b - a) + a
    private fun sign() = if (random.nextBoolean()) 1f else -1f
    private fun hue() = (random.nextFloat() * 6).toInt()
    private fun clampN(n: Float, lo: Int, hi: Int) = max(lo, min(hi, n.roundToInt()))
    private fun colour(index: Int) = pal[index % pal.size]
    private fun col(c: IntArray, a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), c[0], c[1], c[2])

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private abstract class Scene {
        /** [t]: seconds since the first frame. [k]: frames' worth of motion at 60 fps (0 = stand still). */
        abstract fun draw(c: Canvas, t: Float, k: Float)
    }

    // ── Constellation ─────────────────────────────────────────────────────────

    private inner class Constellation(val w: Float, val h: Float) : Scene() {
        inner class Point(var x: Float, var y: Float, val vx: Float, val vy: Float,
                          val r: Float, val base: Float, val ph: Float, val c: Int) {
            var a = base
            // Built once: it depends on nothing that moves, and a gradient a frame per dot adds up.
            val glow = RadialGradient(0f, 0f, r * 8, col(colour(c), 0.025f), col(colour(c), 0f), Shader.TileMode.CLAMP)
        }

        val link = min(140f, max(90f, sqrt(w * h) / 7))
        val pts = List(clampN(w * h / 17000, 22, 80)) {
            Point(rnd(0f, w), rnd(0f, h), rnd(0.05f, 0.22f) * sign(), rnd(0.05f, 0.22f) * sign(),
                rnd(0.8f, 2.4f), rnd(0.15f, 0.45f), rnd(0f, 6.28f), hue())
        }

        override fun draw(c: Canvas, t: Float, k: Float) {
            for (p in pts) {
                p.x += p.vx * k; p.y += p.vy * k
                if (p.x < -20) p.x = w + 20 else if (p.x > w + 20) p.x = -20f
                if (p.y < -20) p.y = h + 20 else if (p.y > h + 20) p.y = -20f
                p.a = (p.base + sin(p.ph + t * 0.24f) * 0.08f).coerceIn(0.04f, 0.7f)
            }
            for (i in pts.indices) {
                val a = pts[i]
                for (j in i + 1 until pts.size) {
                    val b = pts[j]
                    val d = hypot(a.x - b.x, a.y - b.y)
                    if (d >= link) continue
                    val s = 1 - d / link
                    val ca = colour(a.c)
                    val cb = colour(b.c)
                    stroke.color = col(intArrayOf((ca[0] + cb[0]) shr 1, (ca[1] + cb[1]) shr 1, (ca[2] + cb[2]) shr 1), s * s * 0.14f)
                    stroke.strokeWidth = 0.4f + s * 0.5f
                    c.drawLine(a.x, a.y, b.x, b.y, stroke)
                }
            }
            for (p in pts) {
                val saved = c.save()
                c.translate(p.x, p.y)
                shaded.shader = p.glow
                c.drawCircle(0f, 0f, p.r * 8, shaded)
                c.restoreToCount(saved)
                fill.color = col(colour(p.c), p.a)
                c.drawCircle(p.x, p.y, p.r, fill)
            }
        }
    }

    // ── Bubbles ───────────────────────────────────────────────────────────────

    private inner class Bubbles(val w: Float, val h: Float) : Scene() {
        inner class Bubble(val x: Float, var y: Float, val r: Float, val vy: Float, val sway: Float,
                           val ph: Float, val sp: Float, val a: Float, val c: Int) {
            // A highlight up and to the left fading to the rim: on the web a gradient
            // between two circles, which Android draws from 12 on; before that one
            // circle centred on the highlight looks the same at these strengths.
            val shine: Shader = run {
                val inner = col(colour(c), a * 1.3f)
                val outer = col(colour(c), a * 0.25f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RadialGradient(
                    -r * 0.35f, -r * 0.35f, r * 0.1f, 0f, 0f, r,
                    longArrayOf(Color.pack(inner), Color.pack(outer)), null, Shader.TileMode.CLAMP)
                else RadialGradient(-r * 0.35f, -r * 0.35f, r * 1.2f, inner, outer, Shader.TileMode.CLAMP)
            }
        }

        fun make(y: Float) = Bubble(rnd(0f, w), y, rnd(5f, 30f) * (if (random.nextFloat() < 0.2f) 1.6f else 1f),
            rnd(0.12f, 0.42f), rnd(6f, 22f), rnd(0f, 6.28f), rnd(0.2f, 0.6f), rnd(0.05f, 0.13f), hue())

        val list = MutableList(clampN(w * h / 26000, 12, 44)) { make(rnd(0f, h)) }

        override fun draw(c: Canvas, t: Float, k: Float) {
            for (i in list.indices) {
                val b = list[i]
                b.y -= b.vy * k
                if (b.y < -b.r * 2) { list[i] = make(h + rnd(10f, 60f)); continue }
                val x = b.x + sin(b.ph + t * b.sp) * b.sway
                val fade = min(1f, (b.y + b.r) / (h * 0.25f)).coerceAtLeast(0f)
                val saved = c.save()
                c.translate(x, b.y)
                shaded.shader = b.shine
                shaded.alpha = (fade * 255).roundToInt()
                c.drawCircle(0f, 0f, b.r, shaded)
                c.restoreToCount(saved)
                shaded.alpha = 255
                stroke.color = col(colour(b.c), (b.a + 0.12f) * fade)
                stroke.strokeWidth = 1f
                c.drawCircle(x, b.y, b.r, stroke)
            }
        }
    }

    // ── Lines ─────────────────────────────────────────────────────────────────

    private inner class Lines(val w: Float, val h: Float) : Scene() {
        inner class Wave(val y: Float, val a1: Float, val a2: Float, val k1: Float, val k2: Float, val s1: Float,
                         val s2: Float, val p: Float, val width: Float, val al: Float, val c: Int)

        val n = clampN(h / 90, 6, 11)
        val waves = List(n) { i ->
            Wave(h * (0.12f + 0.8f * (i + rnd(-0.3f, 0.3f)) / n), rnd(18f, 60f), rnd(8f, 28f), rnd(0.003f, 0.009f),
                rnd(0.009f, 0.02f), rnd(0.12f, 0.35f), rnd(0.2f, 0.5f), rnd(0f, 6.28f), rnd(0.8f, 1.6f),
                rnd(0.10f, 0.22f), i % 6)
        }
        val path = Path()

        override fun draw(c: Canvas, t: Float, k: Float) {
            for (l in waves) {
                path.reset()
                var x = -10f
                while (x <= w + 10) {
                    val y = l.y + l.a1 * sin(x * l.k1 + t * l.s1 + l.p) + l.a2 * sin(x * l.k2 - t * l.s2 + l.p * 2)
                    if (x < 0) path.moveTo(x, y) else path.lineTo(x, y)
                    x += 14
                }
                stroke.strokeWidth = l.width * 5
                stroke.color = col(colour(l.c), l.al * 0.18f)
                c.drawPath(path, stroke)
                stroke.strokeWidth = l.width
                stroke.color = col(colour(l.c), l.al)
                c.drawPath(path, stroke)
            }
        }
    }

    // ── Squares ───────────────────────────────────────────────────────────────

    private inner class Squares(val w: Float, val h: Float) : Scene() {
        inner class Square(var x: Float, var y: Float, val s: Float, val vx: Float, val vy: Float, var r: Float,
                           val vr: Float, val filled: Boolean, val a: Float, val c: Int)

        fun make(y: Float) = Square(rnd(-40f, w), y, rnd(7f, 46f), rnd(0.04f, 0.16f), rnd(0.08f, 0.26f),
            rnd(0f, 6.28f), rnd(-0.004f, 0.004f), random.nextFloat() < 0.3f, rnd(0.12f, 0.28f), hue())

        val list = MutableList(clampN(w * h / 30000, 10, 36)) { make(rnd(0f, h)) }
        val box = RectF()

        override fun draw(c: Canvas, t: Float, k: Float) {
            for (i in list.indices) {
                val q = list[i]
                q.x += q.vx * k; q.y -= q.vy * k; q.r += q.vr * k
                if (q.y < -q.s * 1.5f || q.x > w + q.s * 1.5f) { list[i] = make(h + rnd(10f, 80f)); continue }
                val half = q.s / 2
                val corner = min(4f, q.s * 0.12f)
                box.set(-half, -half, half, half)
                val saved = c.save()
                c.translate(q.x, q.y)
                c.rotate(Math.toDegrees(q.r.toDouble()).toFloat())
                if (q.filled) {
                    fill.color = col(colour(q.c), q.a * 0.32f)
                    c.drawRoundRect(box, corner, corner, fill)
                }
                stroke.color = col(colour(q.c), q.a)
                stroke.strokeWidth = 1f
                c.drawRoundRect(box, corner, corner, stroke)
                c.restoreToCount(saved)
            }
        }
    }

    // ── Stars ─────────────────────────────────────────────────────────────────

    private inner class Stars(val w: Float, val h: Float) : Scene() {
        inner class Star(var x: Float, var y: Float, val z: Float, val tw: Float, val ph: Float, val c: Int) {
            val r = 0.35f + z * 1.15f
            val base = 0.18f + z * 0.5f
        }

        inner class Shot(var x: Float, var y: Float, val v: Float, colour: IntArray) {
            var life = 0f
            // Drawn from where the head is, so one gradient serves the whole flight.
            val trail = LinearGradient(0f, 0f, 90f, -40f,
                intArrayOf(col(intArrayOf(255, 255, 255), 0.75f), col(colour, 0.4f), col(colour, 0f)),
                floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        }

        val stars = List(clampN(w * h / 2600, 110, 420)) {
            val z = random.nextFloat()
            Star(rnd(0f, w), rnd(0f, h), z, rnd(0.6f, 2.4f), rnd(0f, 6.28f),
                if (random.nextFloat() < 0.3f) hue() else -1)
        }
        var next = rnd(3f, 7f)
        var shot: Shot? = null

        override fun draw(c: Canvas, t: Float, k: Float) {
            for (s in stars) {
                s.x -= (0.02f + s.z * 0.1f) * k
                if (s.x < -2) { s.x = w + 2; s.y = rnd(0f, h) }
                val a = max(0f, s.base + sin(s.ph + t * s.tw) * 0.22f)
                fill.color = if (s.c < 0) Color.argb((min(1f, a * 0.8f) * 255).roundToInt(), 235, 238, 255)
                else col(colour(s.c), a)
                c.drawRect(s.x, s.y, s.x + s.r, s.y + s.r, fill)
            }
            if (shot == null && t > next) {
                shot = Shot(rnd(w * 0.2f, w * 1.1f), rnd(-20f, h * 0.35f), rnd(9f, 14f), pal[random.nextInt(pal.size)])
            }
            shot?.let { sh ->
                sh.life += 0.016f * k
                sh.x -= sh.v * k
                sh.y += sh.v * 0.45f * k
                val fade = max(0f, 1 - sh.life / 1.1f)
                val saved = c.save()
                c.translate(sh.x, sh.y)
                shadedStroke.shader = sh.trail
                shadedStroke.alpha = (fade * 255).roundToInt()
                shadedStroke.strokeWidth = 1.4f
                c.drawLine(0f, 0f, 90f, -40f, shadedStroke)
                c.restoreToCount(saved)
                if (fade <= 0 || sh.x < -120 || sh.y > h + 60) { shot = null; next = t + rnd(5f, 11f) }
            }
        }
    }

    // ── Grid ──────────────────────────────────────────────────────────────────

    private inner class Grid(val w: Float, val h: Float) : Scene() {
        val hy = h * 0.6f
        val cx = w / 2
        val a = pal[0]
        val b = pal[1 % pal.size]
        val sunR = min(w, h) * 0.16f
        val sky = LinearGradient(0f, hy - h * 0.35f, 0f, hy, col(b, 0f), col(b, 0.10f), Shader.TileMode.CLAMP)
        val sun = LinearGradient(0f, hy - sunR, 0f, hy, col(a, 0.20f), col(b, 0.06f), Shader.TileMode.CLAMP)
        val sunBox = RectF(cx - sunR, hy - sunR, cx + sunR, hy + sunR)
        var off = 0f

        override fun draw(c: Canvas, t: Float, k: Float) {
            off = (off + 0.006f * k) % 1
            shaded.shader = sky
            c.drawRect(0f, hy - h * 0.35f, w, hy, shaded)
            // The upper half of the sun, sitting on the horizon.
            shaded.shader = sun
            c.drawArc(sunBox, 180f, 180f, true, shaded)
            stroke.strokeWidth = 1f
            for (i in 0 until 14) {
                val z = (i + off) / 14
                val y = hy + (h - hy) * z * z
                stroke.color = col(a, 0.04f + 0.16f * z)
                c.drawLine(0f, y, w, y, stroke)
            }
            for (i in -12..12) {
                stroke.color = col(a, 0.12f - abs(i) * 0.005f)
                c.drawLine(cx + i * w * 0.012f, hy, cx + i * w * 0.16f, h, stroke)
            }
            stroke.color = col(b, 0.30f)
            c.drawLine(0f, hy, w, hy, stroke)
        }
    }
}
