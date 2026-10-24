package dev.netpilot.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button

/**
 * The big round Connect button. State is shown three ways (colour, icon, and the label
 * inside the circle) so it never relies on colour alone. Animations are skipped when the
 * user has turned animations off in Android's accessibility settings.
 */
class ConnectButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var state: ShieldState = ShieldState.OFF
        set(v) {
            if (field == v) return
            field = v
            contentDescription = context.getString(when (v) {
                ShieldState.OFF -> R.string.cd_connect
                ShieldState.CONNECTING -> R.string.cd_connecting
                ShieldState.ON -> R.string.cd_disconnect
                ShieldState.PAUSED -> R.string.cd_paused
            })
            updateAnimation()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * density; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
        // Honours the user's font size, including Android 14's non-linear scaling.
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
    }
    private val icon: Drawable = context.getDrawable(R.drawable.ic_shield)!!.mutate()
    private val box = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.cd_connect)
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = (220 * density).toInt()
        val s = minOf(resolveSize(size, w), resolveSize(size, h))
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) - ring.strokeWidth * 1.5f
        val on = state == ShieldState.ON
        val accent = context.getColor(when (state) {
            ShieldState.ON -> R.color.good
            ShieldState.PAUSED -> R.color.warn
            ShieldState.CONNECTING -> R.color.primary
            ShieldState.OFF -> R.color.outline
        })

        if (on || state == ShieldState.PAUSED) {             // soft pulsing halo
            halo.color = accent; halo.alpha = (40 + 40 * phase).toInt()
            c.drawCircle(cx, cy, r + ring.strokeWidth * (0.5f + phase), halo)
        }
        fill.color = context.getColor(if (on) R.color.good else R.color.surface_raised)
        c.drawCircle(cx, cy, r, fill)

        ring.color = accent
        box.set(cx - r, cy - r, cx + r, cy + r)
        if (state == ShieldState.CONNECTING) c.drawArc(box, phase * 360f - 90f, 90f, false, ring)
        else c.drawCircle(cx, cy, r, ring)

        val iconColor = if (on) context.getColor(R.color.on_good) else accent // accent is already a colour, not a resource id
        val isz = (r * 0.62f).toInt()
        icon.setTint(iconColor)
        icon.setBounds((cx - isz / 2).toInt(), (cy - isz * 0.75f).toInt(), (cx + isz / 2).toInt(), (cy + isz * 0.25f).toInt())
        icon.draw(c)

        text.color = context.getColor(if (on) R.color.on_good else R.color.text_primary)
        val label = context.getString(when (state) {
            ShieldState.OFF -> R.string.btn_connect
            ShieldState.CONNECTING -> R.string.btn_connecting
            ShieldState.ON -> R.string.btn_connected
            ShieldState.PAUSED -> R.string.btn_paused
        })
        c.drawText(label, cx, cy + r * 0.55f, text)
    }

    private fun updateAnimation() {
        animator?.cancel(); animator = null; phase = 0f
        if (!ValueAnimator.areAnimatorsEnabled() || state == ShieldState.OFF) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            if (state == ShieldState.CONNECTING) { duration = 1_000; interpolator = LinearInterpolator(); repeatMode = ValueAnimator.RESTART }
            else { duration = 1_800; interpolator = AccelerateDecelerateInterpolator(); repeatMode = ValueAnimator.REVERSE }
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name // TalkBack announces "button"
    }
}
