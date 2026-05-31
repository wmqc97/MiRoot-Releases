package com.wmqc.miroot.ui.features

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * HSV 色盘：外圈色相环 + 内区饱和度/明度。
 */
class HsvColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val hsv = floatArrayOf(145f, 0.85f, 0.95f)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svRect = RectF()

    private var centerX = 0f
    private var centerY = 0f
    private var outerR = 0f
    private var innerR = 0f
    private var ringWidth = 0f
    private var draggingHue = false
    private var draggingSv = false

    var onColorChanged: ((Int) -> Unit)? = null

    init {
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeCap = Paint.Cap.ROUND
        svPaint.style = Paint.Style.FILL
        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = Color.WHITE
        thumbStrokePaint.style = Paint.Style.STROKE
        thumbStrokePaint.color = 0x99000000.toInt()
        thumbStrokePaint.strokeWidth = dp(1.5f)
        gapPaint.style = Paint.Style.STROKE
        gapPaint.color = 0x33FFFFFF
        isClickable = true
    }

    fun getColor(): Int = Color.HSVToColor(hsv)

    fun setColor(argb: Int) {
        Color.colorToHSV(argb, hsv)
        if (hsv[1] < 0.04f) hsv[1] = 0.85f
        if (hsv[2] < 0.08f) hsv[2] = 0.95f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w * 0.5f
        centerY = h * 0.5f
        outerR = min(w, h) * 0.46f
        ringWidth = dp(14f)
        val ringGap = dp(10f)
        innerR = outerR - ringWidth - ringGap
        // 内区不超过内切圆，避免圆角矩形四角压住外圈色相环
        val svHalf = innerR * 0.62f
        svRect.set(centerX - svHalf, centerY - svHalf, centerX + svHalf, centerY + svHalf)
        ringPaint.strokeWidth = ringWidth
        gapPaint.strokeWidth = dp(1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawHueRing(canvas)
        canvas.drawCircle(centerX, centerY, innerR + dp(1f), gapPaint)
        drawSvPanel(canvas)
        drawHueThumb(canvas)
        drawSvThumb(canvas)
    }

    private fun drawHueRing(canvas: Canvas) {
        // 色标须按色相角顺时针均匀排列（0°=红在 3 点钟），与 atan2 / hsv[0] 一致。
        val sweep = SweepGradient(
            centerX,
            centerY,
            intArrayOf(
                Color.RED,
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.BLUE,
                Color.MAGENTA,
                Color.RED,
            ),
            null,
        )
        ringPaint.shader = sweep
        canvas.drawCircle(centerX, centerY, outerR - ringWidth * 0.5f, ringPaint)
        ringPaint.shader = null
    }

    private fun drawSvPanel(canvas: Canvas) {
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val satShader = LinearGradient(
            svRect.left,
            svRect.top,
            svRect.right,
            svRect.top,
            Color.WHITE,
            hueColor,
            Shader.TileMode.CLAMP,
        )
        val valShader = LinearGradient(
            svRect.left,
            svRect.top,
            svRect.left,
            svRect.bottom,
            0x00000000,
            0xFF000000.toInt(),
            Shader.TileMode.CLAMP,
        )
        svPaint.shader = ComposeShader(valShader, satShader, PorterDuff.Mode.SRC_OVER)
        canvas.drawRoundRect(svRect, dp(8f), dp(8f), svPaint)
        svPaint.shader = null
    }

    private fun drawHueThumb(canvas: Canvas) {
        val rad = Math.toRadians(hsv[0].toDouble())
        val r = outerR - ringWidth * 0.5f
        val x = centerX + cos(rad).toFloat() * r
        val y = centerY + sin(rad).toFloat() * r
        val thumbR = ringWidth * 0.36f
        thumbPaint.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        canvas.drawCircle(x, y, thumbR, thumbPaint)
        canvas.drawCircle(x, y, thumbR, thumbStrokePaint)
    }

    private fun drawSvThumb(canvas: Canvas) {
        val x = svRect.left + hsv[1] * svRect.width()
        val y = svRect.top + (1f - hsv[2]) * svRect.height()
        thumbPaint.color = getColor()
        canvas.drawCircle(x, y, dp(7f), thumbPaint)
        canvas.drawCircle(x, y, dp(7f), thumbStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHue = isOnHueRing(event.x, event.y)
                draggingSv = !draggingHue && svRect.contains(event.x, event.y)
                if (draggingHue || draggingSv) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    applyTouch(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHue || draggingSv) {
                    applyTouch(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHue || draggingSv) {
                    draggingHue = false
                    draggingSv = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float, y: Float) {
        if (draggingHue) {
            val angle = Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())).toFloat()
            hsv[0] = (angle + 360f) % 360f
        } else if (draggingSv) {
            hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = (1f - (y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(getColor())
    }

    private fun isOnHueRing(x: Float, y: Float): Boolean {
        val dist = hypot(x - centerX, y - centerY)
        val midR = outerR - ringWidth * 0.5f
        return dist in (midR - ringWidth * 0.65f)..(midR + ringWidth * 0.65f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
