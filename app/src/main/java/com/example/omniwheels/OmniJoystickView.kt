package com.example.omniwheels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
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

    private companion object {
        const val DEAD_ZONE = 0.18f
    }

    var listener: ((x: Float, y: Float) -> Unit)? = null
    var releaseListener: (() -> Unit)? = null
    var limitToSquare: Boolean = false
    var resetXOnRelease: Boolean = true
    var resetYOnRelease: Boolean = true

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(180, 255, 255, 255)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var knobX = 0f
    private var knobY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastSentX = 0f
    private var lastSentY = 0f

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
        val radius = min(width, height) * 0.28f
        val knobRadius = radius * 0.38f

        canvas.drawCircle(cx, cy, radius, basePaint)
        if (limitToSquare) {
            canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, ringPaint)
        } else {
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        val hx = cx + knobX * radius
        val hy = cy - knobY * radius
        if (knobX != 0f || knobY != 0f) {
            canvas.drawLine(cx, cy, hx, hy, axisPaint)
        }
        canvas.drawCircle(hx, hy, knobRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                updateKnob(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateKnob(event.getX(pointerIndex), event.getY(pointerIndex))
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                resetKnob()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    resetKnob()
                }
                return true
            }
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            resetKnob()
        }
    }

    override fun onDetachedFromWindow() {
        resetKnob()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateKnob(touchX: Float, touchY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.42f
        val dx = touchX - cx
        val dy = cy - touchY

        if (limitToSquare) {
            val x = (dx / radius).coerceIn(-1f, 1f).let { if (abs(it) < DEAD_ZONE) 0f else it }
            val y = (dy / radius).coerceIn(-1f, 1f).let { if (abs(it) < DEAD_ZONE) 0f else it }
            setKnob(x, y)
            return
        }

        val distance = hypot(dx, dy)
        val clamped = min(distance, radius)
        val angle = atan2(dy, dx)
        val normalizedDistance = clamped / radius

        if (normalizedDistance < DEAD_ZONE) {
            setKnob(0f, 0f)
            return
        }

        setKnob(cos(angle) * normalizedDistance, sin(angle) * normalizedDistance)
    }

    private fun resetKnob() {
        parent?.requestDisallowInterceptTouchEvent(false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
        releaseListener?.invoke()
        setKnob(
            x = if (resetXOnRelease) 0f else knobX,
            y = if (resetYOnRelease) 0f else knobY
        )
        performClick()
    }

    private fun setKnob(x: Float, y: Float) {
        knobX = x
        knobY = y
        if (x != lastSentX || y != lastSentY) {
            lastSentX = x
            lastSentY = y
            listener?.invoke(x, y)
        }
        invalidate()
    }
}
