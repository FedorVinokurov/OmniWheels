package com.example.omniwheels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TriangleButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Direction {
        UP, DOWN, LEFT, RIGHT
    }

    var direction: Direction = Direction.UP
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val path = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cameraUpBitmap: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ui_camera_up)
    }
    private val cameraDownBitmap: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ui_camera_down)
    }
    private val sideLeftBitmap: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ui_side_left)
    }
    private val sideRightBitmap: Bitmap? by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ui_side_right)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = when (direction) {
            Direction.UP -> cameraUpBitmap
            Direction.DOWN -> cameraDownBitmap
            Direction.LEFT -> sideLeftBitmap
            Direction.RIGHT -> sideRightBitmap
        }
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
            return
        }

        val inset = paint.strokeWidth * 2f
        path.reset()

        when (direction) {
            Direction.UP -> {
                path.moveTo(width / 2f, inset)
                path.lineTo(width - inset, height - inset)
                path.lineTo(inset, height - inset)
            }

            Direction.DOWN -> {
                path.moveTo(inset, inset)
                path.lineTo(width - inset, inset)
                path.lineTo(width / 2f, height - inset)
            }

            Direction.LEFT -> {
                path.moveTo(inset, height / 2f)
                path.lineTo(width - inset, inset)
                path.lineTo(width - inset, height - inset)
            }

            Direction.RIGHT -> {
                path.moveTo(width - inset, height / 2f)
                path.lineTo(inset, inset)
                path.lineTo(inset, height - inset)
            }
        }

        path.close()
        canvas.drawPath(path, paint)
    }
}
