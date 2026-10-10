package com.ravy.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator

/**
 * The small Siri-style orb that appears at the bottom of the screen when
 * "Hey Ravy" is heard, over whatever app you're currently in. A plain
 * custom View (not Compose) — simplest reliable way to draw into a raw
 * WindowManager overlay window that isn't hosted by an Activity.
 */
class OverlayOrbView(context: Context) : View(context) {

    @Volatile var amplitude: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ambientScale = 1f
    private val ambientAnimator = ValueAnimator.ofFloat(0.9f, 1.05f).apply {
        duration = 1400
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            ambientScale = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    init {
        ambientAnimator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height)) / 3f
        val scale = ambientScale * (1f + amplitude.coerceIn(0f, 1f) * 0.6f)
        val radius = baseRadius * scale

        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.parseColor("#7CFFF4"), Color.parseColor("#00B8D9"), Color.parseColor("#012A38")),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }

    fun destroy() {
        ambientAnimator.cancel()
    }
}
