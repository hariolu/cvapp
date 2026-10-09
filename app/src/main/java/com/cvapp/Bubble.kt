package com.cvapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * Bubble Card's parts, drawn in the person's colours.
 *
 * The CV Panel and the dashboards are built from Bubble Card's cards, icon boxes,
 * sub-buttons and separators, at the measures in the engine's panel.css. The
 * app's own screens use the same parts so the three read as one thing. A layout
 * says what a view *is* with its tag, and [paint] colours it:
 *
 *   card      a Bubble card: 16dp corners, the card colour, a hairline round it
 *   popup     a pop-up: 28dp corners, the frosted pop-up colour
 *   icon      an icon box: 12dp corners, the soft accent under a light icon
 *   icon-on   the same box filled with the person's colour
 *   swatch    an icon box holding the person's colour itself, round
 *   sub       a sub-button, quiet: 16dp corners on the well colour
 *   sub-on    a sub-button filled with the person's colour (a form's main button)
 *   danger    a sub-button that takes something away, in the panel's red;
 *             danger-on the same, asking for the second tap
 *   sep       the bar of a separator
 *   field     a text field: the well colour, the person's colour round it while typing
 *   text, muted, faint, accent   the text colours; an icon given one is tinted with it
 *   spinner, bar, check          a spinner, a progress bar, a check box
 *   bg        the person's background colour
 *
 * A BubbleSwitch and a BackdropView take the whole look. Sizes live in the
 * layouts and styles.xml; only colour is applied here, so a new look is put on a
 * screen by painting it again.
 */
object Bubble {

    private const val CARD_RADIUS = 16f
    private const val ICON_RADIUS = 12f
    private const val SUB_RADIUS = 16f
    private const val POPUP_RADIUS = 28f
    private const val SEP_RADIUS = 3f
    private const val SUB_ICON = 18f
    /** The panel's --er: what removes or forgets. */
    private const val DANGER = 0xFFF48B8B.toInt()

    fun paint(view: View, look: Look) {
        val context = view.context
        when (view.tag as? String) {
            "bg" -> view.setBackgroundColor(look.bg)
            "card" -> view.background = card(context, look, pressable = view.isClickable)
            "popup" -> view.background = popup(context, look)
            "icon" -> {
                view.background = shape(context, look.accentSoft, ICON_RADIUS)
                tint(view, look.text)
            }
            "icon-on" -> {
                view.background = shape(context, look.bubbleAccent, ICON_RADIUS)
                tint(view, look.onBubbleAccent)
            }
            "swatch" -> (view as? ImageView)?.apply {
                background = shape(context, look.accentSoft, ICON_RADIUS)
                imageTintList = null
                setImageDrawable(swatch(look.accent))
            }
            "sub" -> {
                view.background = press(look, shape(context, look.well, SUB_RADIUS))
                tint(view, look.text)
            }
            "sub-on" -> {
                view.background = press(look, shape(context, look.bubbleAccent, SUB_RADIUS))
                tint(view, look.onBubbleAccent)
            }
            "danger" -> {
                view.background = press(look, shape(context, look.well, SUB_RADIUS))
                tint(view, DANGER)
            }
            "danger-on" -> {
                view.background = press(look, shape(context, Look.withAlpha(DANGER, 0.16f), SUB_RADIUS))
                tint(view, DANGER)
            }
            "sep" -> view.background = shape(context, look.accent2Line, SEP_RADIUS)
            "field" -> if (view is EditText) field(view, look)
            "text" -> tint(view, look.text)
            "muted" -> tint(view, look.text2)
            "faint" -> tint(view, look.text3)
            "accent" -> tint(view, look.accent)
            "spinner" -> (view as? ProgressBar)?.indeterminateTintList = ColorStateList.valueOf(look.accent)
            "bar" -> (view as? ProgressBar)?.apply {
                progressTintList = ColorStateList.valueOf(look.bubbleAccent)
                indeterminateTintList = ColorStateList.valueOf(look.bubbleAccent)
                progressBackgroundTintList = ColorStateList.valueOf(Look.withAlpha(look.text, 0.14f))
            }
            "check" -> (view as? CheckBox)?.apply {
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(look.bubbleAccent, look.text2))
                setTextColor(look.text2)
            }
        }
        if (view is BubbleSwitch) view.look = look
        if (view is BackdropView) view.setLook(look)
        if (view is ViewGroup) for (i in 0 until view.childCount) paint(view.getChildAt(i), look)
    }

    /** Bubble's pop-up: the frosted plate the update and link cards sit on. */
    fun popup(context: Context, look: Look): Drawable = shape(context, look.popup, POPUP_RADIUS, look.line)

    /** A Bubble card's plate; [pressable] adds the ripple a tappable card needs. */
    fun card(context: Context, look: Look, pressable: Boolean = false): Drawable {
        val plate = shape(context, look.card, CARD_RADIUS, look.line)
        return if (pressable) press(look, plate) else plate
    }

    fun shape(context: Context, fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            if (stroke != 0) setStroke(maxOf(1, density.toInt()), stroke)
        }
    }

    /** A colour swatch: the round the CV Panel's Appearance card draws for a colour. */
    fun swatch(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun press(look: Look, plate: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(Look.withAlpha(look.text, 0.12f)), plate, null)

    /** Text in [colour], and any icon beside or inside it: an icon wears its label's colour. */
    private fun tint(view: View, colour: Int) {
        val list = ColorStateList.valueOf(colour)
        when (view) {
            is ImageView -> view.imageTintList = list
            is TextView -> {
                view.setTextColor(colour)
                fitIcons(view)
                TextViewCompat.setCompoundDrawableTintList(view, list)
            }
        }
    }

    /** A sub-button's icon is 18dp, as on the panel; vector icons arrive at 24. */
    private fun fitIcons(view: TextView) {
        if (view.tag != "sub" && view.tag != "sub-on") return
        val size = (SUB_ICON * view.resources.displayMetrics.density).toInt()
        val icons = view.compoundDrawablesRelative
        if (icons.all { it == null }) return
        icons.forEach { it?.setBounds(0, 0, size, size) }
        view.setCompoundDrawablesRelative(icons[0], icons[1], icons[2], icons[3])
    }

    private fun field(view: EditText, look: Look) {
        val context = view.context
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(context, look.well, ICON_RADIUS, look.accent))
            addState(intArrayOf(), shape(context, look.well, ICON_RADIUS))
        }
        view.setTextColor(look.text)
        view.setHintTextColor(look.text3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.textCursorDrawable = GradientDrawable().apply {
                setColor(look.accent)
                setSize((2 * context.resources.displayMetrics.density).toInt(), 1)
            }
        }
    }
}

/**
 * Bubble's switch, as the CV Panel draws it: a 40×24 track, an 18dp knob. Off,
 * the track is faint and the knob is the text colour; on, the track takes the
 * person's colour and the knob the colour that reads on it.
 */
class BubbleSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var look: Look = Look.current
        set(value) { field = value; invalidate() }

    var isChecked = false
        private set

    private var onChange: ((Boolean) -> Unit)? = null
    private var position = 0f   // 0 = off, 1 = on; animated between
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!isChecked, fromUser = true) }
    }

    fun setOnCheckedChangeListener(listener: ((Boolean) -> Unit)?) { onChange = listener }

    /** Sets the state; only a change the user made is reported to the listener. */
    fun setChecked(checked: Boolean, fromUser: Boolean = false) {
        if (checked == isChecked && animator == null) return
        isChecked = checked
        animator?.cancel()
        if (!isLaidOut) {
            position = if (checked) 1f else 0f
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(position, if (checked) 1f else 0f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { position = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { animator = null }
                })
                start()
            }
        }
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        if (fromUser) onChange?.invoke(checked)
    }

    fun toggle() = setChecked(!isChecked, fromUser = true)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((40 * density).toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize((24 * density).toInt() + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = 40 * density
        val h = 24 * density
        val left = paddingLeft + (width - paddingLeft - paddingRight - w) / 2
        val top = paddingTop + (height - paddingTop - paddingBottom - h) / 2
        track.set(left, top, left + w, top + h)
        paint.color = blend(Look.withAlpha(look.text, 0.14f), look.bubbleAccent, position)
        canvas.drawRoundRect(track, h / 2, h / 2, paint)
        val knob = 9 * density
        val cx = left + 3 * density + knob + position * 16 * density
        paint.color = blend(look.text, look.onBubbleAccent, position)
        canvas.drawCircle(cx, top + h / 2, knob, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = isChecked
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = ((from shr shift and 0xFF) * (1 - t) + (to shr shift and 0xFF) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
