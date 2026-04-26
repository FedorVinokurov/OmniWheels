package com.example.omniwheels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class OmniJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var listener: ((x: Float, y: Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(30, 41, 59)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(148, 163, 184)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(130, 226, 232, 240)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(34, 197, 94)
    }

    private var knobX = 0f
    private var knobY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requested = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        ).coerceAtLeast(260)
        setMeasuredDimension(requested, requested)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.42f
        val knobRadius = radius * 0.28f

        basePaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(Color.rgb(51, 65, 85), Color.rgb(15, 23, 42)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, basePaint)
        basePaint.shader = null

        canvas.drawCircle(cx, cy, radius * 0.66f, ringPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, axisPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, axisPaint)

        val hx = cx + knobX * radius
        val hy = cy - knobY * radius
        canvas.drawLine(cx, cy, hx, hy, axisPaint)
        canvas.drawCircle(hx, hy, knobRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateKnob(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                knobX = 0f
                knobY = 0f
                listener?.invoke(0f, 0f)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateKnob(touchX: Float, touchY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.42f
        val dx = touchX - cx
        val dy = cy - touchY
        val distance = hypot(dx, dy)
        val clamped = min(distance, radius)
        val angle = atan2(dy, dx)

        knobX = cos(angle) * clamped / radius
        knobY = sin(angle) * clamped / radius
        listener?.invoke(knobX, knobY)
        invalidate()
    }
}
