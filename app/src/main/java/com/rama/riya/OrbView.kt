package com.rama.riya

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The hero of the app: a marigold core with a mic, wrapped in voice-wave rings.
 * Idle = calm static rings. Listening = rings ripple outward and the sweep shimmers around.
 */
class OrbView(context: Context) : View(context) {

    private val teal = Color.parseColor("#22D3C5")
    private val marigold = Color.parseColor("#FFB02E")
    private val coral = Color.parseColor("#FF7A45")
    private val ink = Color.parseColor("#1B1340")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mic = resources.getDrawable(R.drawable.ic_mic, context.theme).mutate().apply { setTint(ink) }

    private var phase = 0f
    private var active = false
    private var animator: ValueAnimator? = null

    init {
        contentDescription = "Talk now"
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            startAnim()
        } else {
            animator?.cancel()
            animator = null
            phase = 0f
            invalidate()
        }
    }

    private fun startAnim() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active && animator == null) startAnim()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val half = min(w, h) / 2f
        val coreR = half * 0.34f
        ringPaint.shader = SweepGradient(cx, cy, intArrayOf(teal, marigold, teal), null)
        corePaint.shader = LinearGradient(cx - coreR, cy - coreR, cx + coreR, cy + coreR, marigold, coral, Shader.TileMode.CLAMP)
        glowPaint.shader = RadialGradient(
            cx, cy, half,
            intArrayOf(Color.parseColor("#4D22D3C5"), Color.parseColor("#0022D3C5")),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        val coreR = half * 0.34f
        val minR = coreR * 1.38f
        val maxR = half * 0.96f

        glowPaint.alpha = if (active) 255 else 110
        canvas.drawCircle(cx, cy, half, glowPaint)

        ringPaint.strokeWidth = dp(2f)
        if (active) {
            canvas.save()
            canvas.rotate(phase * 360f, cx, cy)
            for (i in 0 until 3) {
                val t = (phase * 2f + i / 3f) % 1f
                ringPaint.alpha = (235 * (1f - t)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, minR + (maxR - minR) * t, ringPaint)
            }
            canvas.restore()
        } else {
            val radii = floatArrayOf(0.15f, 0.55f, 0.95f)
            val alphas = intArrayOf(120, 70, 36)
            for (i in 0 until 3) {
                ringPaint.alpha = alphas[i]
                canvas.drawCircle(cx, cy, minR + (maxR - minR) * radii[i], ringPaint)
            }
        }

        // core, breathing slightly while listening
        val breathe = if (active) 1f + 0.045f * sin(phase * 2f * PI.toFloat() * 2f) else 1f
        canvas.drawCircle(cx, cy, coreR * breathe, corePaint)

        val m = (coreR * breathe * 1.05f).toInt()
        mic.setBounds((cx - m / 2f).toInt(), (cy - m / 2f).toInt(), (cx + m / 2f).toInt(), (cy + m / 2f).toInt())
        mic.draw(canvas)
    }
}
