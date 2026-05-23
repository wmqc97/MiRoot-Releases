package com.wmqc.miroot.ui.apps

import android.graphics.Rect
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 背屏「全部应用」蜂窝：**二维平移**（对标 Watch 网格拖动），非绕轴旋转。
 * 图标位置 = 视口中心 + 轴向格偏移 + [panX]/[panY]。默认不再按距中心远近缩放（避免中间一格视觉上偏大、显得挤）。
 */
class HoneycombPanLayoutManager : RecyclerView.LayoutManager() {

    private var attachedRv: RecyclerView? = null

    fun attachHost(recyclerView: RecyclerView?) {
        attachedRv = recyclerView
    }

    /** 表盘平面平移（与旧 Compose [RearDesktopHoneycombScreen] 一致）。 */
    var panX: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            attachedRv?.requestLayout()
        }

    var panY: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            attachedRv?.requestLayout()
        }

    private var axialCache: List<Pair<Int, Int>> = emptyList()
    private var axialCacheCount: Int = -1
    private var hexSizeCachePx: Float = -1f

    /** 缓存的 pan 边界，仅在布局/数据变化时重算，避免手势每帧全量遍历。 */
    private var cachedPanLimits: PanLimits? = null
    private var cachedPanLimitsGeneration: Int = -1
    private var panLimitsGeneration: Int = 0
        set(value) {
            field = value
            cachedPanLimits = null
        }

    fun invalidatePanLimits() {
        panLimitsGeneration++
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun isAutoMeasureEnabled(): Boolean = true

    override fun canScrollHorizontally(): Boolean = false

    override fun canScrollVertically(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val rv = attachedRv ?: return
        if (state.itemCount == 0) {
            removeAndRecycleAllViews(recycler)
            return
        }
        detachAndScrapAttachedViews(recycler)
        invalidatePanLimits()
        val pl = rv.paddingLeft
        val pt = rv.paddingTop
        val pr = rv.paddingRight
        val pb = rv.paddingBottom
        val pw = (rv.width - pl - pr).coerceAtLeast(1)
        val ph = (rv.height - pt - pb).coerceAtLeast(1)
        val cx = pl + pw / 2f
        val cy = pt + ph / 2f

        val iconPx = (ICON_DP * rv.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val hexSizePx = iconPx * HEX_REL

        ensureAxialCache(state.itemCount, hexSizePx)

        val axial = axialCache

        val drawOrder = (0 until state.itemCount).sortedByDescending { idx ->
            val (q, r) = axial.getOrNull(idx) ?: (0 to 0)
            axialCubeRing(q, r)
        }

        val viewport = Rect(pl, pt, rv.width - pr, rv.height - pb)

        for (idx in drawOrder) {
            if (idx >= axial.size) break
            val (q, r) = axial[idx]
            val g = axialToPixelFlat(q, r, hexSizePx)
            val rx = g.first
            val ry = g.second
            val left = cx + rx + panX - iconPx / 2f
            val top = cy + ry + panY - iconPx / 2f
            val scaleExt = iconPx * 0.18f
            val scratch =
                Rect(
                    (left - scaleExt).roundToInt(),
                    (top - scaleExt).roundToInt(),
                    (left + iconPx + scaleExt).roundToInt(),
                    (top + iconPx + scaleExt).roundToInt(),
                )
            if (!scratch.intersect(viewport)) continue

            val view = recycler.getViewForPosition(idx)
            addView(view)
            measureChildWithMargins(view, 0, 0)
            layoutDecoratedWithMargins(
                view,
                left.roundToInt(),
                top.roundToInt(),
                (left + iconPx).roundToInt(),
                (top + iconPx).roundToInt(),
            )

            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            view.pivotX = iconPx / 2f
            view.pivotY = iconPx / 2f
        }
    }

    private fun ensureAxialCache(count: Int, hexSizePx: Float) {
        if (count == axialCacheCount && abs(hexSizePx - hexSizeCachePx) < 0.5f) return
        axialCache = honeycombCellsForCount(count, hexSizePx)
        axialCacheCount = count
        hexSizeCachePx = hexSizePx
    }

    fun computePanLimits(rv: RecyclerView): PanLimits {
        if (cachedPanLimits != null && cachedPanLimitsGeneration == panLimitsGeneration) {
            return cachedPanLimits!!
        }
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0 || axialCache.isEmpty()) {
            val lim = PanLimits(0f, 0f, 0f, 0f)
            cachedPanLimits = lim
            cachedPanLimitsGeneration = panLimitsGeneration
            return lim
        }
        val iconPx = (ICON_DP * rv.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val hexSizePx = iconPx * HEX_REL
        ensureAxialCache(count, hexSizePx)
        val pl = rv.paddingLeft
        val pt = rv.paddingTop
        val pr = rv.paddingRight
        val pb = rv.paddingBottom
        val boxW = (rv.width - pl - pr).toFloat().coerceAtLeast(1f)
        val boxH = (rv.height - pt - pb).toFloat().coerceAtLeast(1f)
        val iconHitR = iconPx / 2f + 6f * rv.resources.displayMetrics.density
        val lim = panLimitsFor(count, axialCache, hexSizePx, boxW, boxH, iconHitR)
        cachedPanLimits = lim
        cachedPanLimitsGeneration = panLimitsGeneration
        return lim
    }

    fun hitAdapterPosition(rv: RecyclerView, screenX: Float, screenY: Float): Int {
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0 || axialCache.isEmpty()) return RecyclerView.NO_POSITION
        val iconPx = (ICON_DP * rv.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val hexSizePx = iconPx * HEX_REL
        ensureAxialCache(count, hexSizePx)
        val pl = rv.paddingLeft
        val pt = rv.paddingTop
        val pr = rv.paddingRight
        val pb = rv.paddingBottom
        val pw = (rv.width - pl - pr).coerceAtLeast(1)
        val ph = (rv.height - pt - pb).coerceAtLeast(1)
        val cx = pl + pw / 2f
        val cy = pt + ph / 2f
        val iconHitR = iconPx / 2f + 6f * rv.resources.displayMetrics.density

        var bestIdx = RecyclerView.NO_POSITION
        var bestD = Float.MAX_VALUE
        for (idx in 0 until minOf(count, axialCache.size)) {
            val (q, r) = axialCache[idx]
            val g = axialToPixelFlat(q, r, hexSizePx)
            val sx = cx + g.first + panX
            val sy = cy + g.second + panY
            val d = hypot(screenX - sx, screenY - sy)
            if (d < bestD) {
                bestD = d
                bestIdx = idx
            }
        }
        return if (bestIdx >= 0 && bestD <= iconHitR * 1.08f) bestIdx else RecyclerView.NO_POSITION
    }

    companion object {
        private const val ICON_DP = 44f
        private const val HEX_REL = 0.66f
    }
}

data class PanLimits(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

private fun axialCubeRing(q: Int, r: Int): Int = maxOf(abs(q), abs(r), abs(-q - r))

private fun axialToPixelFlat(q: Int, r: Int, hexRadius: Float): Pair<Float, Float> {
    val x = hexRadius * (3f / 2f * q)
    val y = hexRadius * (sqrt(3f) * (r + q / 2f))
    return x to y
}

private fun hexRingDistance(ring: Int): List<Pair<Int, Int>> {
    if (ring == 0) return listOf(0 to 0)
    val res = ArrayList<Pair<Int, Int>>()
    for (q in -ring..ring) {
        for (r in -ring..ring) {
            val s = -q - r
            if (maxOf(abs(q), abs(r), abs(s)) == ring) {
                res.add(q to r)
            }
        }
    }
    return res
}

private fun honeycombCellsForCount(count: Int, hexSizePx: Float): List<Pair<Int, Int>> {
    if (count <= 0) return emptyList()
    val out = ArrayList<Pair<Int, Int>>(count)
    var ring = 0
    while (out.size < count) {
        val cells =
            if (ring == 0) {
                listOf(0 to 0)
            } else {
                hexRingDistance(ring).sortedBy { (q, r) ->
                    val p = axialToPixelFlat(q, r, hexSizePx)
                    atan2(p.second, p.first)
                }
            }
        for (c in cells) {
            out.add(c)
            if (out.size >= count) break
        }
        ring++
    }
    return out
}

private fun panLimitsFor(
    count: Int,
    axial: List<Pair<Int, Int>>,
    hexSizePx: Float,
    boxW: Float,
    boxH: Float,
    iconHitR: Float,
): PanLimits {
    if (count == 0 || axial.isEmpty()) {
        return PanLimits(0f, 0f, 0f, 0f)
    }
    var minGx = Float.MAX_VALUE
    var maxGx = -Float.MAX_VALUE
    var minGy = Float.MAX_VALUE
    var maxGy = -Float.MAX_VALUE
    for (i in 0 until minOf(count, axial.size)) {
        val (q, r) = axial[i]
        val p = axialToPixelFlat(q, r, hexSizePx)
        minGx = min(minGx, p.first)
        maxGx = max(maxGx, p.first)
        minGy = min(minGy, p.second)
        maxGy = max(maxGy, p.second)
    }
    val cx = boxW / 2f
    val cy = boxH / 2f
    val pad = iconHitR + 6f

    val lowX = pad - cx - minGx
    val highX = boxW - pad - cx - maxGx
    val (minPanX, maxPanX) =
        if (lowX <= highX) {
            lowX to highX
        } else {
            highX to lowX
        }

    val lowY = pad - cy - minGy
    val highY = boxH - pad - cy - maxGy
    val (minPanY, maxPanY) =
        if (lowY <= highY) {
            lowY to highY
        } else {
            highY to lowY
        }

    return PanLimits(minPanX, maxPanX, minPanY, maxPanY)
}
