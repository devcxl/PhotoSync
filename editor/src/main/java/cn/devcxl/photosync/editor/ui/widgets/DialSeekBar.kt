package cn.devcxl.photosync.editor.ui.widgets

import kotlin.math.abs
import kotlin.math.roundToInt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R

/**
 * Custom bidirectional dial slider with center zero-snap and tick marks (Variant B design).
 */
class DialSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnDialChangeListener {
        fun onValueChanged(value: Float, fromUser: Boolean)
        fun onStopTrackingTouch()
    }

    var minValue: Float = -5.0f
    var maxValue: Float = 5.0f
    var stepSize: Float = 0.05f
    var zeroSnapThreshold: Float = 0.10f

    var currentValue: Float = 0.0f
        private set

    var listener: OnDialChangeListener? = null

    private val density = resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_text_muted)
        strokeWidth = 1.2f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_accent_blue)
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val zeroPinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_zero_pin)
        strokeWidth = 2.0f * density
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.editor_text_primary)
    }

    fun setValue(value: Float, notify: Boolean = false) {
        val clamped = snapAndQuantize(value.coerceIn(minValue, maxValue), allowSnap = false)
        if (abs(clamped - currentValue) < 1e-4f) return
        currentValue = clamped
        invalidate()
        if (notify) {
            listener?.onValueChanged(currentValue, fromUser = false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padX = 16f * density
        val trackLeft = padX
        val trackRight = (width - padX).coerceAtLeast(trackLeft + 1f)
        val trackWidth = trackRight - trackLeft
        val cy = height / 2f
        val hasNegativeRange = minValue < 0.0f && maxValue > 0.0f
        val anchorFraction = if (hasNegativeRange) {
            (-minValue / (maxValue - minValue)).coerceIn(0f, 1f)
        } else {
            0.0f
        }
        val anchorX = trackLeft + trackWidth * anchorFraction

        // 1. Draw 21 tick marks across the scale
        val tickCount = 20
        for (i in 0..tickCount) {
            val x = trackLeft + trackWidth * (i.toFloat() / tickCount.toFloat())
            val halfH = if (i % 5 == 0) 8f * density else 4.5f * density
            canvas.drawLine(x, cy - halfH, x, cy + halfH, tickPaint)
        }

        // 2. Draw active fill bar from zero/left anchor to thumb X
        val fraction = ((currentValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        val thumbX = trackLeft + trackWidth * fraction
        canvas.drawLine(anchorX, cy, thumbX, cy, fillPaint)

        // 3. Draw zero anchor pin only when zero sits inside the dial range
        if (hasNegativeRange) {
            canvas.drawLine(anchorX, cy - 11f * density, anchorX, cy + 11f * density, zeroPinPaint)
        }

        // 4. Draw thumb circle
        canvas.drawCircle(thumbX, cy, 7.5f * density, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val padX = 16f * density
        val trackLeft = padX
        val trackRight = (width - padX).coerceAtLeast(trackLeft + 1f)
        val fraction = ((event.x - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        val rawValue = minValue + fraction * (maxValue - minValue)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val snapped = snapAndQuantize(rawValue, allowSnap = true)
                if (abs(snapped - currentValue) >= 1e-4f) {
                    currentValue = snapped
                    invalidate()
                    listener?.onValueChanged(currentValue, fromUser = true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listener?.onStopTrackingTouch()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun snapAndQuantize(value: Float, allowSnap: Boolean): Float {
        if (allowSnap && abs(value) <= zeroSnapThreshold) {
            return 0.0f
        }
        val steps = (value / stepSize).roundToInt()
        return (steps * stepSize).coerceIn(minValue, maxValue)
    }
}
