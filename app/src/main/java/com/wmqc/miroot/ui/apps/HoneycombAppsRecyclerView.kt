package com.wmqc.miroot.ui.apps

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.LayoutManager
import kotlin.math.hypot

/**
 * 背屏蜂窝网格：**双轴平移**拖动 + 惯性衰减（对标 Watch 应用列表），由 [HoneycombPanLayoutManager] 应用 pan。
 */
class HoneycombAppsRecyclerView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : RecyclerView(context, attrs, defStyleAttr) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private val flingDecay = 0.88f
        private val flingMultiplier = 0.85f
        private val minFlingSpeedPxPerSec = 55f

        private var velocityTracker: VelocityTracker? = null
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var slopAccum = 0f

        private var flingVx = 0f
        private var flingVy = 0f
        private var flingActive = false

        /** adapter position */
        var onHoneycombItemClick: ((Int) -> Unit)? = null

        private val flingFrameCallback =
            object : Choreographer.FrameCallback {
                override fun doFrame(_frameTimeNs: Long) {
                    val lm = layoutManager as? HoneycombPanLayoutManager ?: run {
                        stopFling()
                        return
                    }
                    if (!flingActive) return

                    val lim = lm.computePanLimits(this@HoneycombAppsRecyclerView)
                    lm.panX =
                        (lm.panX + flingVx / 60f).coerceIn(lim.minX, lim.maxX)
                    lm.panY =
                        (lm.panY + flingVy / 60f).coerceIn(lim.minY, lim.maxY)

                    flingVx *= flingDecay
                    flingVy *= flingDecay

                    if (hypot(flingVx, flingVy) < 12f) {
                        stopFling()
                        return
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }

        init {
            overScrollMode = OVER_SCROLL_NEVER
            // 蜂窝完全自研 pan，不走 RecyclerView 内置滚动；关闭 nested 以免与手势状态机打架。
            isNestedScrollingEnabled = false
            itemAnimator = null
            setHasFixedSize(true)
            ensureProjectionTouchReady()
        }

        /**
         * 背屏经 shell / 投屏拉起时，部分 ROM 会在层级稳定过程中改写字典窗属性；父节点也可能短暂置灰子 View。
         * 此处固定可触摸、可聚焦，并在窗口附着后重申，避免蜂窝拖动手势完全不进来。
         */
        fun ensureProjectionTouchReady() {
            isEnabled = true
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ensureProjectionTouchReady()
        }

        override fun setLayoutManager(layout: LayoutManager?) {
            (layoutManager as? HoneycombPanLayoutManager)?.attachHost(null)
            super.setLayoutManager(layout)
            (layout as? HoneycombPanLayoutManager)?.attachHost(this)
        }

        private fun ensureVelocityTracker(): VelocityTracker {
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain()
            }
            return velocityTracker!!
        }

        /**
         * 不自派给 RecyclerView 内置纵向/嵌套滚动逻辑；否则第一次拖动后内部 mScrollState 等状态会让后续手势失效（「只能拖一次」）。
         */
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lm = layoutManager as? HoneycombPanLayoutManager
            if (lm == null) return super.onTouchEvent(e)

            ensureVelocityTracker().addMovement(e)

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopFling()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = e.x
                    lastY = e.y
                    downX = e.x
                    downY = e.y
                    slopAccum = 0f
                    dragging = false
                    ensureVelocityTracker().clear()
                    ensureVelocityTracker().addMovement(e)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    slopAccum += hypot(dx, dy)
                    val dragThresholdPx =
                        maxOf(
                            touchSlop * 2.5f,
                            DRAG_THRESHOLD_DP * resources.displayMetrics.density,
                        )
                    if (!dragging && slopAccum > dragThresholdPx) {
                        dragging = true
                    }
                    if (dragging) {
                        val lim = lm.computePanLimits(this)
                        lm.panX = (lm.panX + dx).coerceIn(lim.minX, lim.maxX)
                        lm.panY = (lm.panY + dy).coerceIn(lim.minY, lim.maxY)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    ensureVelocityTracker().computeCurrentVelocity(1000)
                    val vt = ensureVelocityTracker()
                    val vx = vt.xVelocity * flingMultiplier
                    val vy = vt.yVelocity * flingMultiplier
                    vt.clear()

                    if (dragging) {
                        if (hypot(vx, vy) > minFlingSpeedPxPerSec) {
                            flingVx = vx
                            flingVy = vy
                            flingActive = true
                            Choreographer.getInstance().postFrameCallback(flingFrameCallback)
                        }
                    } else {
                        val moved = hypot(e.x - downX, e.y - downY)
                        if (moved <= touchSlop * 1.5f) {
                            val pos = lm.hitAdapterPosition(this, e.x, e.y)
                            if (pos != RecyclerView.NO_POSITION) {
                                onHoneycombItemClick?.invoke(pos)
                            }
                        }
                    }
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.clear()
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun stopFling() {
            flingActive = false
            flingVx = 0f
            flingVy = 0f
            Choreographer.getInstance().removeFrameCallback(flingFrameCallback)
        }

        override fun onDetachedFromWindow() {
            (layoutManager as? HoneycombPanLayoutManager)?.attachHost(null)
            stopFling()
            velocityTracker?.recycle()
            velocityTracker = null
            super.onDetachedFromWindow()
        }

        companion object {
            private const val DRAG_THRESHOLD_DP = 10f
        }
    }
