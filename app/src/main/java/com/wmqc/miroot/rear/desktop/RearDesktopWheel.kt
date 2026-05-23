package com.wmqc.miroot.rear.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 小米 17 Pro 背屏左侧摄像头安全区：内容相对窗口左边界的起始像素（固定值）。 */
const val REAR_DESKTOP_SAFE_LEFT_PX = 277

/** 略小于蜂窝页单格，避免底部转盘整圆 + scale 后压到屏幕外。 */
private const val BASE_ICON_DP = 46f

/** 相对位移超过此值视为「在转盘」而非点击，避免慢滑时单帧小于 touchSlop 仍误开应用。 */
private const val SCROLL_DRAG_DP = 10f

/** 拖动时角速度 = dx * 此系数 / r，略大于 1 让同幅度滑动转得稍快、更跟手。 */
private const val SCROLL_SENSITIVITY = 1.18f

private const val FLING_VEL_SMOOTH = 0.35f
private const val FLING_ANGULAR_GAIN = 0.42f
private const val FLING_DECAY = 0.88f
private const val FLING_MIN_RAD_PER_TICK = 0.00055f

private data class WheelGeom(
    val radiusPx: Float,
    val cxPx: Float,
    val cyCircleCenterPx: Float,
    val iconHitRadiusPx: Float,
)

@Composable
fun RearDesktopWheelScreen(
    /** null 表示仍在加载，不展示空列表文案 */
    apps: List<RearDesktopAppEntry>?,
    onLaunchApp: (String) -> Unit,
    emptyHint: String,
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scrollRad = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val flingJobHolder =
        remember {
            object {
                var job: Job? = null
            }
        }
    val density = LocalDensity.current

    // 同蜂窝页：转盘交互由 pointerInput 处理，未提供可操作的无障碍动作；
    // 清空语义树以减少无障碍查询导致的 IAccessibilityInteractionConnectionCallback 大事务。
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        if (apps == null) {
            return@Box
        }
        if (apps.isEmpty()) {
            Text(
                emptyHint,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            return@Box
        }

        val appsNonNull = apps
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val geom =
                remember(maxWidth, maxHeight, density) {
                    with(density) {
                        val boxHpx = maxHeight.toPx()
                        val boxWpx = maxWidth.toPx()
                        val iconPx = BASE_ICON_DP.dp.toPx()
                        // 圆最低点约在 H - bottomMargin；图标以圆心定位，最大 scale≈1 时需留足「半格 + 内边距」。
                        val bottomMarginPx =
                            (iconPx * 0.5f + 14.dp.toPx()).coerceAtLeast(32.dp.toPx())
                        val radiusPx =
                            minOf(boxWpx, boxHpx - bottomMarginPx).coerceAtLeast(1f) * 0.36f
                        val cxPx = boxWpx / 2f
                        val cyCircleCenterPx = boxHpx - bottomMarginPx - radiusPx
                        val iconHitRadiusPx = (BASE_ICON_DP / 2f + 6f).dp.toPx()
                        WheelGeom(radiusPx, cxPx, cyCircleCenterPx, iconHitRadiusPx)
                    }
                }

            val pkgKeys = appsNonNull.joinToString("|") { it.packageName }
            val appsRef by rememberUpdatedState(appsNonNull)
            val geomRef by rememberUpdatedState(geom)

            DisposableEffect(pkgKeys) {
                onDispose {
                    flingJobHolder.job?.cancel()
                    flingJobHolder.job = null
                }
            }

            val bitmaps =
                remember(pkgKeys) {
                    appsNonNull.map { entry ->
                        runCatching {
                            val icon = entry.icon ?: RearDesktopRepository.loadAppIcon(appContext, entry.packageName)
                            icon?.toBitmap(width = 144, height = 144)?.asImageBitmap()
                        }.getOrNull()
                    }
                }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(pkgKeys, geom) {
                        awaitEachGesture {
                            flingJobHolder.job?.cancel()
                            flingJobHolder.job = null

                            val down = awaitFirstDown(requireUnconsumed = false)
                            val dragThresholdPx =
                                max(
                                    viewConfiguration.touchSlop * 2.5f,
                                    SCROLL_DRAG_DP.dp.toPx(),
                                )
                            var dragged = false
                            var accX = 0f
                            var accY = 0f
                            var velSmoothX = 0f
                            val rWheel = geomRef.radiusPx.coerceAtLeast(40f)
                            drag(down.id) { change ->
                                val d = change.positionChange()
                                accX += d.x
                                accY += d.y
                                if (!dragged && hypot(accX, accY) > dragThresholdPx) {
                                    dragged = true
                                }
                                velSmoothX = velSmoothX * (1f - FLING_VEL_SMOOTH) + d.x * FLING_VEL_SMOOTH
                                if (dragged) {
                                    change.consume()
                                    scrollRad.floatValue -= d.x / rWheel * SCROLL_SENSITIVITY
                                }
                            }
                            if (dragged) {
                                val tailVel = velSmoothX
                                if (abs(tailVel) > 2.8f) {
                                    flingJobHolder.job =
                                        scope.launch {
                                            var w =
                                                -(tailVel / rWheel) * SCROLL_SENSITIVITY * FLING_ANGULAR_GAIN
                                            var prevTickMs = System.currentTimeMillis()
                                            while (isActive && abs(w) > FLING_MIN_RAD_PER_TICK) {
                                                val now = System.currentTimeMillis()
                                                val dt = ((now - prevTickMs).coerceIn(4L, 32L)) / 11f
                                                prevTickMs = now
                                                scrollRad.floatValue += w * dt
                                                w *= Math.pow(FLING_DECAY.toDouble(), dt.toDouble()).toFloat()
                                            }
                                        }
                                }
                            } else {
                                val pkg =
                                    findNearestPackage(
                                        appsRef,
                                        down.position,
                                        scrollRad.floatValue,
                                        geomRef,
                                    )
                                if (pkg.isNotEmpty()) onLaunchApp(pkg)
                            }
                        }
                    },
            ) {
                val n = appsNonNull.size
                /** 预计算各应用基准角度，避免每帧重复 [2f*PI*idx/n]。 */
                val baseAngles = remember(pkgKeys) {
                    val twoPi = 2f * PI.toFloat()
                    List(n) { idx -> twoPi * idx / n }
                }
                val indices =
                    remember(scrollRad.floatValue, pkgKeys, baseAngles) {
                        val twoPi = 2f * PI.toFloat()
                        val scroll = scrollRad.floatValue
                        baseAngles.mapIndexed { idx, baseAngle ->
                            val angle = baseAngle + scroll
                            val tw = (angle % twoPi + twoPi) % twoPi
                            val distFromZero = minOf(tw, twoPi - tw)
                            val norm = (distFromZero / PI.toFloat()).coerceIn(0f, 1f)
                            val cosVal = cos(norm * PI.toFloat() / 2f).coerceIn(0f, 1f)
                            val scale = 0.48f + 0.52f * cosVal
                            val alphaFac = 0.28f + 0.72f * cosVal
                            Triple(idx, scale, alphaFac)
                        }.sortedBy { it.second }
                    }

                indices.forEach { (idx, scale, alphaFac) ->
                    val angle = baseAngles.getOrElse(idx) { 0f } + scrollRad.floatValue
                    val xPx = geom.cxPx + geom.radiusPx * sin(angle)
                    val yPx = geom.cyCircleCenterPx + geom.radiusPx * cos(angle)
                    val iconPx = with(density) { BASE_ICON_DP.dp.toPx() }
                    val leftPx = xPx - iconPx / 2f
                    val topPx = yPx - iconPx / 2f

                    val bmp = bitmaps.getOrNull(idx)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .offset {
                                        IntOffset(leftPx.roundToInt(), topPx.roundToInt())
                                    }
                                    .size(BASE_ICON_DP.dp)
                                    .scale(scale)
                                    .alpha(alphaFac),
                        )
                    }
                }
            }
        }
    }
}

private fun findNearestPackage(
    apps: List<RearDesktopAppEntry>,
    tap: Offset,
    scrollRad: Float,
    geom: WheelGeom,
): String {
    if (apps.isEmpty()) return ""
    val n = apps.size
    var bestIdx = 0
    var bestD = Float.MAX_VALUE
    for (idx in apps.indices) {
        val angle = (2f * PI.toFloat() * idx / n) + scrollRad
        val xPx = geom.cxPx + geom.radiusPx * sin(angle)
        val yPx = geom.cyCircleCenterPx + geom.radiusPx * cos(angle)
        val d = hypot(tap.x - xPx, tap.y - yPx)
        if (d < bestD) {
            bestD = d
            bestIdx = idx
        }
    }
    return if (bestD <= geom.iconHitRadiusPx * 1.02f) apps[bestIdx].packageName else ""
}
