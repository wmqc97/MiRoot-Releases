package com.wmqc.miroot.rear.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.lyrics.LogHelper
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HONEYCOMB_DEBUG_TAG = "RearHoneycomb"

private sealed class PanAnimCmd {
    data object Stop : PanAnimCmd()

    /** 橡皮筋越过边界后柔和弹回 min/max。 */
    data class SettleOverscroll(
        val startY: Float,
        val minY: Float,
        val maxY: Float,
    ) : PanAnimCmd()

    data class FlingVelocity(
        val vx: Float,
        val vy: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    ) : PanAnimCmd()
}

/**
 * 背屏桌面「全部应用」模式（[RearDesktopListMode.ALL_BY_FREQUENCY]）的蜂窝网格；用于 [RearScreenDesktopActivity] 与 [RearDesktopHoneycombTestActivity]。
 *
 * 设计基准：主力背屏 **976×596**，左侧 **277px** 摄像头安全区由外层 Activity 预留；蜂窝仅在右侧有效区内绘制。
 * 更小接近尺寸的背屏按有效区域相对基准等比缩小图标与间距，交互（纵向拖动、spline 惯性、橡皮筋回弹、竖直位置缩放）一致。
 *
 * **固定 4 列蜂窝**：行优先编号，奇偶行横向错开半格 + 竖向 √3/2 间距（圆标经典交错，非矩形对齐），单指上下拖动 + spline 惯性（横向不滚动），边界橡皮筋与弹簧回弹。
 * **约 4 行占满视口高度**（按行距反推图标上限，图标可比 5 行目标更大）；**按屏幕 Y 分带**：上下保持**基准 1.0**，中间约 **3 行**视区高度**额外放大**。
 * 蜂窝圆心距按「略大于最大放大」的排布边长计算（见 [HONEYCOMB_LAYOUT_SPACING_REF_SCALE]），与绘制基准分离，避免中间大圆挤叠。
 * 圆形图标**整圆**随缩放改变布局边长。
 * **顶/底渐变条**（总高度 + 条内 colorStops）压暗贴边，减轻硬裁切感；条高与过渡区均可调常量。
 * **仅解码视口附近**图标（宽边距预取），减轻背屏功耗。
 *
 * **Debug 日志**：`adb logcat -s RearHoneycomb`（仅 Debug 包）；关注 `decode batch` 耗时、`viewport counts`、
 * `layout` 中 pan 区间、`gesture`/`anim` 与列表变更。可优化方向：减少 `drawIndices` 每帧全量 sort、
 * 解码批大小与缓存淘汰、Compose 重组频率等。
 */
@Composable
fun RearDesktopHoneycombScreen(
    apps: List<RearDesktopAppEntry>?,
    onLaunchApp: (String) -> Unit,
    emptyHint: String,
) {
    val appContext = LocalContext.current.applicationContext
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    /** 手指拖动中位移（非 suspend，可在 drag{} 内每帧更新，避免列表卡顿）。抬起后在协程里 snapTo 回 [panY]。 */
    val fingerPanY = remember { mutableFloatStateOf(0f) }
    var fingerDragActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val flingJobHolder =
        remember {
            object {
                var job: Job? = null
            }
        }
    val density = LocalDensity.current
    val flingDecay = rememberSplineBasedDecay<Float>()
    val animCmd = remember { Channel<PanAnimCmd>(capacity = Channel.UNLIMITED) }

    LaunchedEffect(flingDecay, scope) {
        while (isActive) {
            when (val cmd = animCmd.receive()) {
                PanAnimCmd.Stop -> {
                    if (BuildConfig.DEBUG) {
                        LogHelper.d(HONEYCOMB_DEBUG_TAG, "anim Stop (cancel fling / reset animatables)")
                    }
                    flingJobHolder.job?.cancel()
                    flingJobHolder.job = null
                    panX.stop()
                    panY.stop()
                }
                is PanAnimCmd.SettleOverscroll -> {
                    if (BuildConfig.DEBUG) {
                        LogHelper.d(
                            HONEYCOMB_DEBUG_TAG,
                            "anim SettleOverscroll startY=${cmd.startY} minY=${cmd.minY} maxY=${cmd.maxY}",
                        )
                    }
                    panX.snapTo(0f)
                    panY.snapTo(cmd.startY)
                    flingJobHolder.job?.cancel()
                    flingJobHolder.job =
                        scope.launch {
                            val target =
                                when {
                                    cmd.startY < cmd.minY -> cmd.minY
                                    cmd.startY > cmd.maxY -> cmd.maxY
                                    else -> cmd.startY
                                }
                            panY.animateTo(
                                target,
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                }
                is PanAnimCmd.FlingVelocity -> {
                    if (BuildConfig.DEBUG) {
                        LogHelper.d(
                            HONEYCOMB_DEBUG_TAG,
                            "anim Fling vx=${cmd.vx} vy=${cmd.vy} bounds y=${cmd.minY}..${cmd.maxY} x=${cmd.minX}..${cmd.maxX}",
                        )
                    }
                    flingJobHolder.job?.cancel()
                    flingJobHolder.job =
                        scope.launch {
                            panX.updateBounds(cmd.minX, cmd.maxX)
                            panY.updateBounds(cmd.minY, cmd.maxY)
                            coroutineScope {
                                val jx = async { panX.animateDecay(cmd.vx, flingDecay) }
                                val jy = async { panY.animateDecay(cmd.vy, flingDecay) }
                                jx.await()
                                jy.await()
                            }
                        }
                }
            }
        }
    }

    // 背屏蜂窝的交互完全由 pointerInput 手势处理，并未提供可操作的无障碍语义；
    // 若保留大量 Image 的 contentDescription，会导致无障碍服务反复拉取巨大的语义树并产生大 Binder 事务。
    // 这里清空语义树以降低 IAccessibilityInteractionConnectionCallback 大事务频率（不影响触摸/滚动/点击功能）。
    Box(
        Modifier
            .fillMaxSize()
            .clip(RectangleShape)
            .clearAndSetSemantics { },
    ) {
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
            var entranceStarted by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                entranceStarted = true
            }
            val entranceAlpha by animateFloatAsState(
                targetValue = if (entranceStarted) 1f else 0f,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                label = "rearHoneycombEntrance",
            )

            val boxWpx = with(density) { maxWidth.toPx() }
            val boxHpx = with(density) { maxHeight.toPx() }
            val edgeVignetteHeight = maxHeight * HONEYCOMB_EDGE_VIGNETTE_HEIGHT_FRAC
            val cx = boxWpx / 2f
            val cy = boxHpx / 2f
            val layoutFitScale =
                remember(boxWpx, boxHpx) {
                    min(1f, min(boxWpx / REAR_REF_CONTENT_W_PX, boxHpx / REAR_REF_CONTENT_H_PX))
                }
            val iconDpLayout = HONEYCOMB_ICON_DP * layoutFitScale
            val iconPxUncapped = with(density) { iconDpLayout.dp.toPx() }
            var iconPx = iconPxUncapped
            val rowPitchK = (1f + GRID_ICON_GAP_H_FRAC) * sqrt(3f) / 2f
            val iconPxMaxForTargetRows =
                (boxHpx * HONEYCOMB_VISIBLE_ROWS_TARGET_HEIGHT_FRAC) /
                    (HONEYCOMB_TARGET_VISIBLE_ROWS * rowPitchK * HONEYCOMB_LAYOUT_SPACING_REF_SCALE)
            if (iconPx > iconPxMaxForTargetRows) {
                if (BuildConfig.DEBUG && iconPxUncapped > iconPxMaxForTargetRows + 0.5f) {
                    LogHelper.d(
                        HONEYCOMB_DEBUG_TAG,
                        "iconPx cap uncapped=$iconPxUncapped -> max=$iconPxMaxForTargetRows " +
                            "boxH=$boxHpx rowPitchK=$rowPitchK targetRows=$HONEYCOMB_TARGET_VISIBLE_ROWS",
                    )
                }
                iconPx = iconPxMaxForTargetRows
            }
            val iconDpVisual = with(density) { iconPx.toDp().value }
            val pkgKeys = appsNonNull.joinToString("|") { it.packageName }
            val (gridCells, gridMetrics) =
                remember(pkgKeys, iconPx, appsNonNull.size) {
                    buildGridCellsAndMetrics(
                        count = appsNonNull.size,
                        iconPx = iconPx,
                    )
                }
            /** 预计算每个格子相对簇几何中心的偏移，避免排序与渲染中反复调用 [gridOffsetFromClusterCenter]。 */
            val cellOffsets =
                remember(pkgKeys, gridCells, gridMetrics) {
                    gridCells.map { (col, row) ->
                        gridOffsetFromClusterCenter(col, row, gridMetrics)
                    }
                }

            LaunchedEffect(boxWpx, boxHpx, iconPx, appsNonNull.size, cellOffsets, pkgKeys) {
                if (!BuildConfig.DEBUG) return@LaunchedEffect
                val iconHitDbg =
                    iconPx / 2f * HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE + with(density) { 6.dp.toPx() }
                val limDbg =
                    panLimitsStrip(
                        count = appsNonNull.size,
                        cellOffsets = cellOffsets,
                        boxW = boxWpx,
                        boxH = boxHpx,
                        iconHitR = iconHitDbg,
                    )
                LogHelper.d(
                    HONEYCOMB_DEBUG_TAG,
                    "layout box=${boxWpx.toInt()}x${boxHpx.toInt()}px fitScale=$layoutFitScale " +
                        "iconPx=$iconPx layoutPx=${gridMetrics.layoutPx} cellW=${gridMetrics.cellWPx} " +
                        "rowPitch=${gridMetrics.rowPitchPx} apps=${appsNonNull.size} " +
                        "panY∈[${limDbg.minY},${limDbg.maxY}] focusEdge=$HONEYCOMB_FOCUS_EDGE_FRAC maxS=$HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE",
                )
            }

            LaunchedEffect(pkgKeys) {
                panX.snapTo(0f)
                if (BuildConfig.DEBUG) {
                    LogHelper.d(
                        HONEYCOMB_DEBUG_TAG,
                        "apps identity changed count=${appsNonNull.size} pkgKeysLen=${pkgKeys.length}",
                    )
                }
            }

            val appsRef by rememberUpdatedState(appsNonNull)
            val boxWpxRef = rememberUpdatedState(boxWpx)
            val boxHpxRef = rememberUpdatedState(boxHpx)
            val iconPxRef = rememberUpdatedState(iconPx)
            val cellOffsetsRef = rememberUpdatedState(cellOffsets)
            /** 缓存的 pan 限制，仅布局变化时重算，避免手势每帧全量遍历。 */
            val cachedPanLimits =
                remember(pkgKeys, boxWpx, boxHpx, iconPx, cellOffsets, appsNonNull.size) {
                    val limIconHitR =
                        iconPx / 2f * HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE + with(density) { 6.dp.toPx() }
                    panLimitsStrip(
                        count = appsNonNull.size,
                        cellOffsets = cellOffsets,
                        boxW = boxWpx,
                        boxH = boxHpx,
                        iconHitR = limIconHitR,
                    )
                }
            val panLimitsRef = rememberUpdatedState(cachedPanLimits)
            /** 供点击命中与手势内读取当前惯性位移（避免闭包读到过期 pan）。 */
            val panYForHitRef = rememberUpdatedState(panY.value)

            val panXs = panX.value
            val panIdleY = panY.value
            val fingerY = fingerPanY.floatValue
            val panYs = if (fingerDragActive) fingerY else panIdleY
            val pxNow = panXs
            val pyNow = panYs

            /** 单次遍历计算绘制与预取视口（合并两次全量 [indicesIntersectingViewport]）。 */
            val viewportResult =
                remember(pxNow, pyNow, pkgKeys, boxWpx, boxHpx, iconPx, cellOffsets) {
                    val vpCx = boxWpx / 2f
                    val vpCy = boxHpx / 2f
                    computeHoneycombViewport(
                        count = appsNonNull.size,
                        cellOffsets = cellOffsets,
                        iconPx = iconPx,
                        cx = vpCx, cy = vpCy,
                        panX = pxNow, panY = pyNow,
                        boxW = boxWpx, boxH = boxHpx,
                    )
                }
            val visibleDrawIndices = viewportResult.draw
            val visibleLoadIndices = viewportResult.load

            val iconBitmaps = remember(pkgKeys) { mutableStateMapOf<Int, ImageBitmap>() }
            val loadSig =
                remember(visibleLoadIndices, pkgKeys) {
                    visibleLoadIndices.joinToString(",")
                }
            LaunchedEffect(loadSig, pkgKeys, layoutFitScale) {
                val list = appsNonNull
                val toDecode = visibleLoadIndices.filter { it !in iconBitmaps }
                if (toDecode.isEmpty()) return@LaunchedEffect
                val decodeSide =
                    (144f * layoutFitScale).roundToInt().coerceIn(MIN_HONEYCOMB_ICON_DECODE_PX, MAX_HONEYCOMB_ICON_DECODE_PX)
                var fail = 0
                var ok = 0
                val tDecode0 = System.currentTimeMillis()
                for (idx in toDecode) {
                    if (idx !in list.indices) continue
                    val bmp =
                        withContext(Dispatchers.Default) {
                            runCatching {
                                val icon =
                                    list[idx].icon ?: RearDesktopRepository.loadAppIcon(appContext, list[idx].packageName)
                                icon
                                    ?: return@runCatching null
                                icon
                                    .toBitmap(width = decodeSide, height = decodeSide)
                                    .asImageBitmap()
                            }.getOrNull()
                        }
                    if (bmp != null) {
                        iconBitmaps[idx] = bmp
                        ok++
                    } else {
                        fail++
                    }
                }
                val decodeMs = System.currentTimeMillis() - tDecode0
                trimHoneycombBitmapCache(
                    iconBitmaps,
                    keep = visibleLoadIndices.toSet(),
                    maxEntries = HONEYCOMB_BITMAP_CACHE_MAX_ENTRIES,
                )
                if (BuildConfig.DEBUG) {
                    LogHelper.d(
                        HONEYCOMB_DEBUG_TAG,
                        "decode batch n=${toDecode.size} ok=$ok side=$decodeSide px ${decodeMs}ms fail=$fail " +
                            "visibleDraw=${visibleDrawIndices.size} visibleLoad=${visibleLoadIndices.size} cache=${iconBitmaps.size}",
                    )
                }
            }

            LaunchedEffect(visibleDrawIndices.size, visibleLoadIndices.size, appsNonNull.size, pkgKeys) {
                if (BuildConfig.DEBUG) {
                    LogHelper.d(
                        HONEYCOMB_DEBUG_TAG,
                        "viewport counts draw=${visibleDrawIndices.size} load=${visibleLoadIndices.size} total=${appsNonNull.size}",
                    )
                }
            }

            DisposableEffect(pkgKeys) {
                onDispose {
                    if (BuildConfig.DEBUG) {
                        LogHelper.d(HONEYCOMB_DEBUG_TAG, "dispose clear bitmap cache (pkgKeys changed)")
                    }
                    flingJobHolder.job?.cancel()
                    flingJobHolder.job = null
                    iconBitmaps.clear()
                }
            }


            /**
             * 仅对可见索引按 Y 距离排序（替代全量排序），利用预计算偏移避免几何计算；
             * 用于组合叠加顺序：远离视口中心的绘制在下层，靠近的在上层。
             */
            val drawIndices =
                remember(visibleDrawIndices, pkgKeys, cellOffsets, pyNow) {
                    visibleDrawIndices.sortedByDescending { idx ->
                        abs(cellOffsets.getOrElse(idx) { Offset.Zero }.y + pyNow)
                    }
                }
            val visibleDrawSet = remember(visibleDrawIndices) { visibleDrawIndices.toSet() }

            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = 0f
                            translationY = panYs
                            alpha = entranceAlpha
                        },
                ) {
                    drawIndices.forEach { idx ->
                        if (idx !in visibleDrawSet) return@forEach
                        val g = cellOffsets.getOrElse(idx) { Offset.Zero }
                        val sx = cx + g.x
                        val sy = cy + g.y
                        val cyScr = cy + g.y + panYs
                        val scaleF =
                            viewportStripFocusScale(
                                iconCenterYViewport = cyScr,
                                boxH = boxHpx,
                            )
                        val sidePx = iconPx * scaleF
                        val leftPx = sx - sidePx / 2f
                        val topPx = sy - sidePx / 2f

                        val bmp = iconBitmaps[idx]
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .graphicsLayer {
                                            translationX = leftPx
                                            translationY = topPx
                                        }
                                        .size((iconDpVisual * scaleF).dp)
                                        .clip(CircleShape),
                            )
                        }
                    }
                }
                val vignetteT = HONEYCOMB_EDGE_VIGNETTE_TRANSITION_FRAC.coerceIn(0.08f, 0.95f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(edgeVignetteHeight)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0f to Color.Black,
                                        vignetteT to Color.Transparent,
                                        1f to Color.Transparent,
                                    ),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(edgeVignetteHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0f to Color.Transparent,
                                        (1f - vignetteT) to Color.Transparent,
                                        1f to Color.Black,
                                    ),
                            ),
                        ),
                )
                // 仅依赖 pkgKeys，避免约束频繁变化导致 pointerInput 重启、手势协程被掐断（表现为「只能滑一下」）。
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(pkgKeys) {
                            awaitEachGesture {
                                if (boxWpxRef.value < 4f || boxHpxRef.value < 4f) return@awaitEachGesture

                                flingJobHolder.job?.cancel()
                                flingJobHolder.job = null
                                // 勿在此处调用 Animatable.stop()（suspend）；取消惯性协程即可接管位移。
                                fingerPanY.floatValue = panY.value
                                fingerDragActive = false

                                val boxW = boxWpxRef.value
                                val boxH = boxHpxRef.value
                                val iconPxCur = iconPxRef.value
                                val iconHitR =
                                    iconPxCur / 2f * HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE + 6.dp.toPx()

                                val dragThresholdPx =
                                    max(
                                        viewConfiguration.touchSlop * 1.65f,
                                        HONEYCOMB_PAN_DRAG_DP.dp.toPx(),
                                    )
                                val tapMovementCapPx =
                                    max(
                                        viewConfiguration.touchSlop * 1.5f,
                                        HONEYCOMB_TAP_MAX_MOVE_DP.dp.toPx(),
                                    )

                                val down = awaitFirstDown(requireUnconsumed = false)
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)

                                var dragged = false
                                var slopAccX = 0f
                                var slopAccY = 0f

                                drag(down.id) { change ->
                                    val d = change.positionChange()
                                    slopAccX += d.x
                                    slopAccY += d.y
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    if (!dragged && hypot(slopAccX, slopAccY) > dragThresholdPx) {
                                        dragged = true
                                        fingerDragActive = true
                                        change.consume()
                                        val lim = panLimitsRef.value
                                        // 阈值前累积的垂直位移一次性补上（本帧不再叠加 d.y，避免重复）。
                                        fingerPanY.floatValue =
                                            coercePanWithRubber(
                                                panY.value + slopAccY,
                                                lim.minY,
                                                lim.maxY,
                                                boxHpxRef.value,
                                            )
                                    } else if (dragged) {
                                        change.consume()
                                        val lim = panLimitsRef.value
                                        fingerPanY.floatValue =
                                            coercePanWithRubber(
                                                fingerPanY.floatValue + d.y,
                                                lim.minY,
                                                lim.maxY,
                                                boxHpxRef.value,
                                            )
                                    }
                                }

                                if (dragged) {
                                    val lim = panLimitsRef.value
                                    val endY = fingerPanY.floatValue
                                    val releaseVel = tracker.calculateVelocity()
                                    scope.launch {
                                        panY.snapTo(endY)
                                        fingerDragActive = false
                                        if (endY < lim.minY || endY > lim.maxY) {
                                            if (BuildConfig.DEBUG) {
                                                LogHelper.d(
                                                    HONEYCOMB_DEBUG_TAG,
                                                    "gesture overscroll endY=$endY lim=${lim.minY}..${lim.maxY} rawVy=${releaseVel.y}",
                                                )
                                            }
                                            animCmd.trySend(
                                                PanAnimCmd.SettleOverscroll(
                                                    endY,
                                                    lim.minY,
                                                    lim.maxY,
                                                ),
                                            )
                                        } else {
                                            val vy = releaseVel.y * HONEYCOMB_FLING_MULTIPLIER
                                            if (abs(vy) > HONEYCOMB_FLING_MIN_START_SPEED) {
                                                if (BuildConfig.DEBUG) {
                                                    LogHelper.d(
                                                        HONEYCOMB_DEBUG_TAG,
                                                        "gesture fling endY=$endY vy=$vy (raw=${releaseVel.y} × $HONEYCOMB_FLING_MULTIPLIER)",
                                                    )
                                                }
                                                animCmd.trySend(
                                                    PanAnimCmd.FlingVelocity(
                                                        0f,
                                                        vy,
                                                        lim.minX,
                                                        lim.maxX,
                                                        lim.minY,
                                                        lim.maxY,
                                                    ),
                                                )
                                            } else if (BuildConfig.DEBUG) {
                                                LogHelper.d(
                                                    HONEYCOMB_DEBUG_TAG,
                                                    "gesture drag end endY=$endY no-fling |vy|=${abs(vy)} thr=$HONEYCOMB_FLING_MIN_START_SPEED raw=${releaseVel.y}",
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    val tapDist = hypot(slopAccX, slopAccY)
                                    val pkg =
                                        if (tapDist <= tapMovementCapPx) {
                                            findHoneycombHit(
                                                tap = down.position,
                                                panX = 0f,
                                                panY = panYForHitRef.value,
                                                apps = appsRef,
                                                cellOffsets = cellOffsetsRef.value,
                                                iconPx = iconPxRef.value,
                                                cx = boxW / 2f,
                                                cy = boxH / 2f,
                                                iconHitR = iconHitR,
                                            )
                                        } else {
                                            ""
                                        }
                                    if (pkg.isNotEmpty()) {
                                        if (BuildConfig.DEBUG) {
                                            LogHelper.d(HONEYCOMB_DEBUG_TAG, "tap launch pkg=$pkg dist=$tapDist")
                                        }
                                        onLaunchApp(pkg)
                                    }
                                }
                            }
                        },
                )
            }
        }
    }
}

/** 默认蜂窝图标（dp）；背屏窄窗下过大会显得「默认就放大、挤」。 */
private const val HONEYCOMB_ICON_DP = 48f
/**
 * 设计基准：全屏 **976×596**；左侧 **277px** 摄像头区由 Activity 预留后，内容有效宽约 **699px**。
 * 更小背屏按有效区域相对该矩形缩小图标与间距（不超过 1 倍放大）。
 */
private const val REAR_REF_CONTENT_W_PX = 699f
private const val REAR_REF_CONTENT_H_PX = 596f
private const val MIN_HONEYCOMB_ICON_DECODE_PX = 48
private const val MAX_HONEYCOMB_ICON_DECODE_PX = 144
/** 蜂窝水平方向：列间距相对「排布用边长」的比例（越大列距与行距 `rowPitch` 越大）。 */
private const val GRID_ICON_GAP_H_FRAC = 0.22f
/** 判定开始平移蜂窝的最小位移（dp）；与系统 touchSlop 取较大值，避免原先 2.5×slop 导致背屏几乎拖不动。 */
private const val HONEYCOMB_PAN_DRAG_DP = 6f
/** 仍视为「点击打开」的最大累积位移（dp）。 */
private const val HONEYCOMB_TAP_MAX_MOVE_DP = 14f
/** 目标：视口内约可见蜂窝行数（用于反推图标上限；改为 4 行可显著增大单格图标）。 */
private const val HONEYCOMB_TARGET_VISIBLE_ROWS = 4f
/** 参与「约 N 行」换算的视口高度比例（留上下边）。 */
private const val HONEYCOMB_VISIBLE_ROWS_TARGET_HEIGHT_FRAC = 0.90f
/** 中间保持最大缩放的行数（相对 [HONEYCOMB_TARGET_VISIBLE_ROWS] 可见行）。 */
private const val HONEYCOMB_FOCUS_MIDDLE_ROWS = 3f
/**
 * 上、下缩小带各占视口高度比例；中间 `1 - 2*edge` 为最大缩放带。
 * 取 `edge = (1 - 可见区高占比×中间行/目标行)/2`，使中间带高度与「目标行里的中间几行」相当。
 */
private val HONEYCOMB_FOCUS_EDGE_FRAC: Float =
    (
        1f -
            HONEYCOMB_VISIBLE_ROWS_TARGET_HEIGHT_FRAC *
                (HONEYCOMB_FOCUS_MIDDLE_ROWS / HONEYCOMB_TARGET_VISIBLE_ROWS)
    ) / 2f
/** 中间带内最大缩放（整圆布局边长 = 基准 × 该系数）；上下带保持 [HONEYCOMB_VIEWPORT_FOCUS_MIN_SCALE] 不缩小。 */
private const val HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE = 1.38f
/**
 * 蜂窝圆心距按 `iconPx * 该系数` 作为排布边长（≥ [HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE] 并略放大），
 * 使中间「放大镜」大圆之间仍保留空隙，不与格点重叠。
 */
private const val HONEYCOMB_LAYOUT_SPACING_REF_SCALE = 1.48f
/** 上下带缩放：保持 1 即不缩小，仅通过中间带放大体现层次。 */
private const val HONEYCOMB_VIEWPORT_FOCUS_MIN_SCALE = 1.0f
/** 视口外扩比例：绘制列表（略大于 1 避免边缘被裁）。 */
private const val HONEYCOMB_VIEWPORT_PAD_FRAC = 1.15f
/** 顶/底渐变条总高度占内容区高度比例（条本身变矮 = 整体压暗带更短）。 */
private const val HONEYCOMB_EDGE_VIGNETTE_HEIGHT_FRAC = 0.12f
/**
 * 在渐变条内部，黑→透明（或透明→黑）在条高的前/后该比例内完成，其余为全透明；
 * 避免整条都是「慢慢变淡」只占黑边变短的问题。
 */
private const val HONEYCOMB_EDGE_VIGNETTE_TRANSITION_FRAC = 0.42f
/** 解码预取边距（大于视口边距，滑动时少闪占位）。 */
private const val HONEYCOMB_PREFETCH_PAD_FRAC = 2.0f
/** 背屏「全部应用」蜂窝固定列数（奇偶行交错）。 */
private const val HONEYCOMB_FIXED_COLUMNS = 4
/** 惯性与手指速度的比例（略小于 1 更易控、更接近 Watch 阻尼感）。 */
private const val HONEYCOMB_FLING_MULTIPLIER = 0.88f
private const val HONEYCOMB_FLING_MIN_START_SPEED = 48f
private const val HONEYCOMB_BITMAP_CACHE_MAX_ENTRIES = 220

private data class HoneycombViewportResult(
    val draw: List<Int>,
    val load: List<Int>,
)

/**
 * 单次遍历计算绘制与预取视口索引，避免 [indicesIntersectingViewport] 两次全量遍历。
 * 使用预计算蜂窝偏移 [cellOffsets] 避免重复几何运算。
 */
private fun computeHoneycombViewport(
    count: Int,
    cellOffsets: List<Offset>,
    iconPx: Float,
    cx: Float,
    cy: Float,
    panX: Float,
    panY: Float,
    boxW: Float,
    boxH: Float,
): HoneycombViewportResult {
    if (count <= 0 || cellOffsets.isEmpty()) return HoneycombViewportResult(emptyList(), emptyList())
    val drawPad = iconPx * HONEYCOMB_VIEWPORT_PAD_FRAC
    val loadPad = iconPx * HONEYCOMB_PREFETCH_PAD_FRAC
    val halfMax = iconPx * HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE / 2f
    val drawOut = ArrayList<Int>()
    val loadOut = ArrayList<Int>()
    val n = min(count, cellOffsets.size)
    for (idx in 0 until n) {
        val g = cellOffsets[idx]
        val left = panX + cx + g.x - halfMax
        val top = panY + cy + g.y - halfMax
        val right = left + halfMax * 2f
        val bottom = top + halfMax * 2f
        // 预取视口（较大）
        if (right + loadPad >= 0f && bottom + loadPad >= 0f && left - loadPad <= boxW && top - loadPad <= boxH) {
            loadOut.add(idx)
            // 绘制视口（较小）
            if (right + drawPad >= 0f && bottom + drawPad >= 0f && left - drawPad <= boxW && top - drawPad <= boxH) {
                drawOut.add(idx)
            }
        }
    }
    return HoneycombViewportResult(drawOut, loadOut)
}

private fun trimHoneycombBitmapCache(
    map: MutableMap<Int, ImageBitmap>,
    keep: Set<Int>,
    maxEntries: Int,
) {
    if (map.size <= maxEntries) return
    for (k in map.keys.toList()) {
        if (map.size <= maxEntries) break
        if (k !in keep) map.remove(k)
    }
}

/**
 * 按图标中心在视口内的竖直位置分带：上下为**基准 1.0**（不缩小），经 smootherstep 过渡到中间带最大缩放；
 * 中间带高度与「约 [HONEYCOMB_TARGET_VISIBLE_ROWS] 行里的 [HONEYCOMB_FOCUS_MIDDLE_ROWS] 行」相当。
 */
private fun viewportStripFocusScale(
    iconCenterYViewport: Float,
    boxH: Float,
): Float {
    if (boxH <= 2f) return HONEYCOMB_VIEWPORT_FOCUS_MIN_SCALE
    val y = iconCenterYViewport.coerceIn(0f, boxH)
    val fy = y / boxH
    val edge = HONEYCOMB_FOCUS_EDGE_FRAC.coerceIn(0.01f, 0.45f)
    val minS = HONEYCOMB_VIEWPORT_FOCUS_MIN_SCALE
    val maxS = HONEYCOMB_VIEWPORT_FOCUS_MAX_SCALE
    val focus01 =
        when {
            fy <= edge -> honeycombSmootherstep01((fy / edge).coerceIn(0f, 1f))
            fy >= 1f - edge -> honeycombSmootherstep01(((1f - fy) / edge).coerceIn(0f, 1f))
            else -> 1f
        }
    return (minS + (maxS - minS) * focus01).coerceIn(minS, maxS)
}

/** Ken Perlin 改进 smoothstep，端点一阶二阶导为 0，缩放过渡更顺。 */
private fun honeycombSmootherstep01(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private fun coercePanWithRubber(
    desiredPanY: Float,
    minY: Float,
    maxY: Float,
    viewportHeight: Float,
): Float {
    return when {
        desiredPanY < minY -> minY + rubberOverscroll(desiredPanY - minY, viewportHeight)
        desiredPanY > maxY -> maxY + rubberOverscroll(desiredPanY - maxY, viewportHeight)
        else -> desiredPanY
    }
}

private fun rubberOverscroll(delta: Float, viewportHeight: Float): Float {
    val h = viewportHeight.coerceAtLeast(1f)
    val sign = if (delta >= 0f) 1f else -1f
    val ad = abs(delta)
    val resisted = (ad * 0.42f * h) / (h + ad * 0.65f)
    return sign * resisted.coerceIn(0f, h * 0.24f)
}

private data class GridLayoutMetrics(
    val columns: Int,
    /** 相邻列圆心水平间距。 */
    val cellWPx: Float,
    /** 相邻行圆心垂直间距（蜂窝交错行距）。 */
    val rowPitchPx: Float,
    /** 所有图标圆心包围盒中心 X（用于将簇置于视口中心）。 */
    val clusterMidX: Float,
    val clusterMidY: Float,
    /** 蜂窝几何用的排布边长（≈ [iconPx]×[HONEYCOMB_LAYOUT_SPACING_REF_SCALE]），与绘制基准 [iconPx] 分离。 */
    val layoutPx: Float,
)

/**
 * 固定 [HONEYCOMB_FIXED_COLUMNS] 列、行优先 (col,row)；row 为偶数左对齐，row 为奇数整体右移 [cellW]/2，
 * 竖向圆心距为 [cellW]*√3/2，形成圆标蜂窝交错（非矩形网格）。
 */
private fun buildGridCellsAndMetrics(
    count: Int,
    iconPx: Float,
): Pair<List<Pair<Int, Int>>, GridLayoutMetrics> {
    val columns = HONEYCOMB_FIXED_COLUMNS
    val layoutPx = iconPx * HONEYCOMB_LAYOUT_SPACING_REF_SCALE
    val hGap = layoutPx * GRID_ICON_GAP_H_FRAC
    val cellW = layoutPx + hGap
    val rowPitch = cellW * sqrt(3f) / 2f
    if (count <= 0) {
        return emptyList<Pair<Int, Int>>() to
            GridLayoutMetrics(
                columns,
                cellW,
                rowPitch,
                0f,
                0f,
                layoutPx,
            )
    }
    val cells = List(count) { i -> (i % columns) to (i / columns) }
    var minCx = Float.MAX_VALUE
    var maxCx = -Float.MAX_VALUE
    var minCy = Float.MAX_VALUE
    var maxCy = -Float.MAX_VALUE
    for ((col, row) in cells) {
        val c = honeycombCellCenter(col, row, layoutPx, cellW, rowPitch)
        minCx = min(minCx, c.x)
        maxCx = max(maxCx, c.x)
        minCy = min(minCy, c.y)
        maxCy = max(maxCy, c.y)
    }
    val clusterMidX = (minCx + maxCx) / 2f
    val clusterMidY = (minCy + maxCy) / 2f
    return cells to GridLayoutMetrics(columns, cellW, rowPitch, clusterMidX, clusterMidY, layoutPx)
}

private fun honeycombCellCenter(
    col: Int,
    row: Int,
    layoutPx: Float,
    cellW: Float,
    rowPitch: Float,
): Offset {
    val stagger = if (row and 1 == 1) cellW * 0.5f else 0f
    return Offset(
        col * cellW + layoutPx * 0.5f + stagger,
        row * rowPitch + layoutPx * 0.5f,
    )
}

/** 图标圆心相对簇几何中心（未加 pan）的偏移。 */
private fun gridOffsetFromClusterCenter(
    col: Int,
    row: Int,
    metrics: GridLayoutMetrics,
): Offset {
    val p = honeycombCellCenter(col, row, metrics.layoutPx, metrics.cellWPx, metrics.rowPitchPx)
    return Offset(
        p.x - metrics.clusterMidX,
        p.y - metrics.clusterMidY,
    )
}

private data class PanLimits(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

/** 纵向列表：锁定横向平移，仅保留竖直方向的 pan 范围（使用预计算偏移）。 */
private fun panLimitsStrip(
    count: Int,
    cellOffsets: List<Offset>,
    boxW: Float,
    boxH: Float,
    iconHitR: Float,
): PanLimits {
    if (count == 0 || cellOffsets.isEmpty()) {
        return PanLimits(0f, 0f, 0f, 0f)
    }
    var minGy = Float.MAX_VALUE
    var maxGy = -Float.MAX_VALUE
    val n = min(count, cellOffsets.size)
    for (i in 0 until n) {
        val y = cellOffsets[i].y
        minGy = min(minGy, y)
        maxGy = max(maxGy, y)
    }
    val cx = boxW / 2f
    val cy = boxH / 2f
    val pad = iconHitR + 6f

    val lowY = pad - cy - minGy
    val highY = boxH - pad - cy - maxGy
    val (minPanY, maxPanY) =
        if (lowY <= highY) {
            lowY to highY
        } else {
            highY to lowY
        }
    return PanLimits(0f, 0f, minPanY, maxPanY)
}

private fun findHoneycombHit(
    tap: Offset,
    panX: Float,
    panY: Float,
    apps: List<RearDesktopAppEntry>,
    cellOffsets: List<Offset>,
    iconPx: Float,
    cx: Float,
    cy: Float,
    iconHitR: Float,
): String {
    if (apps.isEmpty()) return ""
    var bestIdx = -1
    var bestD = Float.MAX_VALUE
    for (idx in apps.indices) {
        val g = cellOffsets.getOrElse(idx) { Offset.Zero }
        val sx = cx + g.x + panX
        val sy = cy + g.y + panY
        val d = hypot(tap.x - sx, tap.y - sy)
        if (d < bestD) {
            bestD = d
            bestIdx = idx
        }
    }
    return if (bestIdx >= 0 && bestD <= iconHitR * 1.08f) {
        apps[bestIdx].packageName
    } else {
        ""
    }
}
