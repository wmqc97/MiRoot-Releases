package com.wmqc.miroot.rear.balance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmqc.miroot.R
import com.wmqc.miroot.car.VibrationHelper
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 背屏平衡小游戏左侧安全区（避让摄像头），与背屏桌面 277px 独立配置。 */
internal const val BALANCE_GAME_SAFE_LEFT_PX = 270

private const val PREFS = "miroot_rear_balance_game"
private const val KEY_BEST_MS = "best_ms"
private const val KEY_DIFFICULTY = "difficulty"
private const val BALL_RADIUS_FRAC = 0.048f
private const val ARENA_MARGIN_FRAC = 0.04f
/** 中心目标圆半径，相对竞技场外圈半径。 */
private const val CENTER_ZONE_RADIUS_FRAC = 0.13f
private const val ACCEL_LPF = 0.84f
private const val FRAME_MS = 16L
private const val CALIBRATION_SAMPLES = 18
private const val VIBRATE_START_MS = 35L
private const val VIBRATE_GAME_OVER_MS = 100L
private const val VIBRATE_EDGE_WARN_MS = 22L
private const val EDGE_WARN_THROTTLE_MS = 280L
private const val SCORE_SHIFT_LEFT_PX = 42f
private const val SCORE_SHIFT_UP_PX = 10f
private const val BEST_BOTTOM_PADDING_DP = 18
private const val DIFFICULTY_SHIFT_UP_PX = 5f
/** 困难模式 1:1：倾斜加速度（m/s²）直接映射为小球加速度。 */
private const val TILT_SCALE_1_TO_1 = SensorManager.GRAVITY_EARTH

private enum class GamePhase {
    READY,
    CALIBRATING,
    PLAYING,
    GAME_OVER,
}

enum class BalanceDifficulty(
    val arenaRadiusScale: Float,
    /** 相对困难模式 1:1 的倾斜灵敏度倍率。 */
    val tiltSensitivity: Float,
    val friction: Float,
    val ballScale: Float,
) {
    EASY(arenaRadiusScale = 0.94f, tiltSensitivity = 0.36f, friction = 0.972f, ballScale = 0.88f),
    NORMAL(arenaRadiusScale = 0.86f, tiltSensitivity = 0.62f, friction = 0.962f, ballScale = 1f),
    HARD(arenaRadiusScale = 0.76f, tiltSensitivity = 1f, friction = 0.948f, ballScale = 1.12f),
    /** 水平仪：显示四向倾角，无小球游戏。 */
    SPIRIT_LEVEL(arenaRadiusScale = 0.86f, tiltSensitivity = 0f, friction = 0f, ballScale = 0f),
    ;

    val isSpiritLevel: Boolean
        get() = this == SPIRIT_LEVEL

    fun labelRes(): Int =
        when (this) {
            EASY -> R.string.rear_balance_game_difficulty_easy
            NORMAL -> R.string.rear_balance_game_difficulty_normal
            HARD -> R.string.rear_balance_game_difficulty_hard
            SPIRIT_LEVEL -> R.string.rear_balance_game_difficulty_spirit
        }

    fun next(): BalanceDifficulty = entries[(ordinal + 1) % entries.size]
}

private data class BallState(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
)

/** 竞技场在「最短边」单位下的宽高比，使物理碰撞与 Canvas 绘制的正圆一致。 */
private data class ArenaMetrics(
    val aspectX: Float,
    val aspectY: Float,
) {
    companion object {
        val Default = ArenaMetrics(1f, 1f)
    }
}

private fun distInMinUnits(x: Float, y: Float, metrics: ArenaMetrics): Float {
    val dx = (x - 0.5f) * metrics.aspectX
    val dy = (y - 0.5f) * metrics.aspectY
    return hypot(dx.toDouble(), dy.toDouble()).toFloat()
}

private fun arenaMetricsFromLayout(size: IntSize, safeStartPx: Float): ArenaMetrics {
    if (size.width <= 0 || size.height <= 0) return ArenaMetrics.Default
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val margin = min(w, h) * ARENA_MARGIN_FRAC
    val arenaW = (w - safeStartPx - margin * 2f).coerceAtLeast(1f)
    val arenaH = (h - margin * 2f).coerceAtLeast(1f)
    val minDim = min(arenaW, arenaH)
    return ArenaMetrics(arenaW / minDim, arenaH / minDim)
}

@Composable
fun BalanceBallGame(
    modifier: Modifier = Modifier,
    safeStartPx: Float = 0f,
) {
    val context = LocalContext.current
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var neutralX by remember { mutableFloatStateOf(0f) }
    var neutralY by remember { mutableFloatStateOf(0f) }
    var ball by remember { mutableStateOf(BallState(0.5f, 0.5f, 0f, 0f)) }
    var phase by remember { mutableStateOf(GamePhase.READY) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var difficulty by remember { mutableStateOf(readDifficulty(context)) }
    var bestMs by remember(difficulty) { mutableLongStateOf(readBestMs(context, difficulty)) }
    var lastEdgeVibrateMs by remember { mutableLongStateOf(0L) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var ballInCenter by remember { mutableStateOf(false) }
    val arenaMetrics = arenaMetricsFromLayout(layoutSize, safeStartPx)

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var filteredX = 0f
        var filteredY = 0f
        val listener =
            object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

                override fun onSensorChanged(event: SensorEvent?) {
                    if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
                    filteredX = filteredX * ACCEL_LPF + event.values[0] * (1f - ACCEL_LPF)
                    filteredY = filteredY * ACCEL_LPF + event.values[1] * (1f - ACCEL_LPF)
                    tiltX = filteredX
                    tiltY = filteredY
                }
            }
        if (accelerometer != null) {
            sensorManager.registerListener(
                listener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    fun beginRound() {
        phase = GamePhase.CALIBRATING
        elapsedMs = 0L
        ball = BallState(0.5f, 0.5f, 0f, 0f)
        lastEdgeVibrateMs = 0L
        ballInCenter = false
        VibrationHelper.vibrateOneShot(context, VIBRATE_START_MS, "平衡小游戏开始震动失败")
    }

    LaunchedEffect(phase, difficulty) {
        if (phase != GamePhase.CALIBRATING) return@LaunchedEffect
        var sumX = 0f
        var sumY = 0f
        repeat(CALIBRATION_SAMPLES) {
            sumX += tiltX
            sumY += tiltY
            delay(24L)
        }
        neutralX = sumX / CALIBRATION_SAMPLES
        neutralY = sumY / CALIBRATION_SAMPLES
        phase = GamePhase.PLAYING
    }

    LaunchedEffect(difficulty) {
        if (!difficulty.isSpiritLevel) return@LaunchedEffect
        delay(120L)
        var sumX = 0f
        var sumY = 0f
        repeat(CALIBRATION_SAMPLES) {
            sumX += tiltX
            sumY += tiltY
            delay(24L)
        }
        neutralX = sumX / CALIBRATION_SAMPLES
        neutralY = sumY / CALIBRATION_SAMPLES
    }

    LaunchedEffect(phase, difficulty, arenaMetrics) {
        if (difficulty.isSpiritLevel) return@LaunchedEffect
        if (phase != GamePhase.PLAYING) return@LaunchedEffect
        var lastTick = System.nanoTime()
        while (phase == GamePhase.PLAYING) {
            delay(FRAME_MS)
            val now = System.nanoTime()
            val dt = ((now - lastTick) / 1_000_000_000f).coerceIn(0.008f, 0.05f)
            lastTick = now

            val arenaRadiusMin = 0.5f * difficulty.arenaRadiusScale
            val ballRadiusMin = BALL_RADIUS_FRAC * difficulty.ballScale
            val centerZoneRadiusMin = arenaRadiusMin * CENTER_ZONE_RADIUS_FRAC
            val ax = arenaMetrics.aspectX
            val ay = arenaMetrics.aspectY

            val tiltDx = (tiltX - neutralX).coerceIn(-7f, 7f)
            val tiltDy = (tiltY - neutralY).coerceIn(-7f, 7f)

            var bx = (ball.x - 0.5f) * ax
            var by = (ball.y - 0.5f) * ay
            var vbx = ball.vx * ax
            var vby = ball.vy * ay

            val tiltScale = TILT_SCALE_1_TO_1 * difficulty.tiltSensitivity
            vbx += tiltDx * tiltScale * dt
            vby += tiltDy * tiltScale * dt
            vbx *= difficulty.friction
            vby *= difficulty.friction

            bx += vbx * dt
            by += vby * dt

            var dist = hypot(bx.toDouble(), by.toDouble()).toFloat()
            val innerLimit = arenaRadiusMin - ballRadiusMin

            if (dist > innerLimit && innerLimit > 0f && dist > 0f) {
                val nx = bx / dist
                val ny = by / dist
                bx = nx * innerLimit
                by = ny * innerLimit
                val dot = vbx * nx + vby * ny
                if (dot > 0f) {
                    vbx -= 1.65f * dot * nx
                    vby -= 1.65f * dot * ny
                }
                dist = innerLimit
            }

            if (dist + ballRadiusMin >= arenaRadiusMin) {
                ballInCenter = false
                phase = GamePhase.GAME_OVER
                if (elapsedMs > bestMs) {
                    bestMs = elapsedMs
                    writeBestMs(context, bestMs, difficulty)
                }
                VibrationHelper.vibrateOneShot(context, VIBRATE_GAME_OVER_MS, "平衡小游戏结束震动失败")
            } else {
                ball =
                    BallState(
                        x = 0.5f + bx / ax,
                        y = 0.5f + by / ay,
                        vx = vbx / ax,
                        vy = vby / ay,
                    )
                elapsedMs += (dt * 1000f).toLong()
                val inCenter = dist <= centerZoneRadiusMin
                if (inCenter != ballInCenter) {
                    ballInCenter = inCenter
                }

                val dangerRatio =
                    if (arenaRadiusMin > 0f) {
                        (dist + ballRadiusMin) / arenaRadiusMin
                    } else {
                        0f
                    }
                if (dangerRatio > 0.88f && now / 1_000_000L - lastEdgeVibrateMs > EDGE_WARN_THROTTLE_MS) {
                    lastEdgeVibrateMs = now / 1_000_000L
                    VibrationHelper.vibrateOneShot(context, VIBRATE_EDGE_WARN_MS, "平衡小游戏贴边震动失败")
                }
            }
        }
    }

    val scoreText = formatScore(elapsedMs)
    val bestText = formatScore(bestMs)
    val density = LocalDensity.current
    val contentStartDp =
        with(density) {
            max(safeStartPx, BALANCE_GAME_SAFE_LEFT_PX.toFloat()).toDp()
        }
    val scope = rememberCoroutineScope()

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { layoutSize = it }
                .background(Color(0xFF0A0E14))
                .pointerInput(phase, difficulty) {
                    detectTapGestures {
                        when {
                            difficulty.isSpiritLevel ->
                                scope.launch {
                                    var sumX = 0f
                                    var sumY = 0f
                                    repeat(CALIBRATION_SAMPLES) {
                                        sumX += tiltX
                                        sumY += tiltY
                                        delay(24L)
                                    }
                                    neutralX = sumX / CALIBRATION_SAMPLES
                                    neutralY = sumY / CALIBRATION_SAMPLES
                                }
                            phase == GamePhase.READY || phase == GamePhase.GAME_OVER -> beginRound()
                        }
                    }
                },
    ) {
        if (difficulty.isSpiritLevel) {
            drawSpiritBackground(Modifier.fillMaxSize())
            SpiritLevelPanel(
                safeStartPx = safeStartPx,
                contentStartDp = contentStartDp,
                tiltX = tiltX,
                tiltY = tiltY,
                neutralX = neutralX,
                neutralY = neutralY,
            )
        } else {
            drawBallGameCanvas(
                safeStartPx = safeStartPx,
                difficulty = difficulty,
                tiltX = tiltX,
                tiltY = tiltY,
                neutralX = neutralX,
                neutralY = neutralY,
                ball = ball,
                ballInCenter = ballInCenter,
            )
        }

        if (!difficulty.isSpiritLevel) {
            Text(
                text = scoreText,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = contentStartDp + 8.dp, top = 12.dp)
                        .offset {
                            IntOffset(-SCORE_SHIFT_LEFT_PX.toInt(), -SCORE_SHIFT_UP_PX.toInt())
                        },
                color = Color(0xFFE3F2FD),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = context.getString(R.string.rear_balance_game_best, bestText),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = contentStartDp + 8.dp,
                            bottom = BEST_BOTTOM_PADDING_DP.dp,
                        )
                        .offset {
                            IntOffset(-SCORE_SHIFT_LEFT_PX.toInt(), 0)
                        },
                color = Color(0x99B0BEC5),
                fontSize = 13.sp,
            )
        }

        Text(
            text = context.getString(difficulty.labelRes()),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp)
                    .offset {
                        IntOffset(0, -DIFFICULTY_SHIFT_UP_PX.toInt())
                    }
                    .clickable(
                        enabled = difficulty.isSpiritLevel ||
                            (phase != GamePhase.PLAYING && phase != GamePhase.CALIBRATING),
                    ) {
                        val next = difficulty.next()
                        difficulty = next
                        if (next.isSpiritLevel) {
                            phase = GamePhase.PLAYING
                        } else {
                            phase = GamePhase.READY
                            bestMs = readBestMs(context, next)
                        }
                        writeDifficulty(context, next)
                    },
            color = Color(0xCC90CAF9),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )

        if (difficulty.isSpiritLevel) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = contentStartDp),
            ) {
                Text(
                    text = context.getString(R.string.rear_balance_spirit_tap_calibrate),
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp),
                    color = Color(0x66FFFFFF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = contentStartDp),
        ) {
            if (!difficulty.isSpiritLevel) {
                when (phase) {
                    GamePhase.READY ->
                        Text(
                            text = context.getString(R.string.rear_balance_game_tap_start),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    GamePhase.CALIBRATING ->
                        Text(
                            text = context.getString(R.string.rear_balance_game_calibrating),
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xBBFFFFFF),
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                    GamePhase.GAME_OVER ->
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = context.getString(R.string.rear_balance_game_over, scoreText),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = context.getString(R.string.rear_balance_game_tap_restart),
                                modifier = Modifier.padding(top = 12.dp),
                                color = Color(0x88FFFFFF),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    GamePhase.PLAYING -> Unit
                }
            }
        }
    }
}

@Composable
private fun drawSpiritBackground(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D1520), Color(0xFF060A10), Color(0xFF0A0E14)),
                    startY = 0f,
                    endY = size.height,
                ),
            size = Size(size.width, size.height),
        )
    }
}

@Composable
private fun drawBallGameCanvas(
    modifier: Modifier = Modifier.fillMaxSize(),
    safeStartPx: Float,
    difficulty: BalanceDifficulty,
    tiltX: Float,
    tiltY: Float,
    neutralX: Float,
    neutralY: Float,
    ball: BallState,
    ballInCenter: Boolean,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D1520), Color(0xFF060A10), Color(0xFF0A0E14)),
                    startY = 0f,
                    endY = h,
                ),
            size = Size(w, h),
        )

        val margin = min(w, h) * ARENA_MARGIN_FRAC
        val arenaLeft = safeStartPx + margin
        val arenaTop = margin
        val arenaRight = w - margin
        val arenaBottom = h - margin
        val arenaW = (arenaRight - arenaLeft).coerceAtLeast(1f)
        val arenaH = (arenaBottom - arenaTop).coerceAtLeast(1f)
        val cx = arenaLeft + arenaW / 2f
        val cy = arenaTop + arenaH / 2f
        val arenaRadius = min(arenaW, arenaH) / 2f * difficulty.arenaRadiusScale
        val ballR = min(arenaW, arenaH) * BALL_RADIUS_FRAC * difficulty.ballScale

        val tiltAngle = ((tiltX - neutralX) * 2.4f).coerceIn(-16f, 16f)
        rotate(tiltAngle, pivot = Offset(cx, cy)) {
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF243447), Color(0xFF141C28)),
                        center = Offset(cx, cy),
                        radius = arenaRadius,
                    ),
                radius = arenaRadius,
                center = Offset(cx, cy),
            )
            val gridStep = arenaRadius / 4f
            for (i in -4..4) {
                val offset = i * gridStep
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(cx + offset, cy - arenaRadius),
                    end = Offset(cx + offset, cy + arenaRadius),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(cx - arenaRadius, cy + offset),
                    end = Offset(cx + arenaRadius, cy + offset),
                    strokeWidth = 1f,
                )
            }
        }

        drawCircle(
            color = Color(0xFF4FC3F7),
            radius = arenaRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 3f),
        )
        drawCircle(
            color = Color(0x334FC3F7),
            radius = arenaRadius - 5f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f),
        )

        val centerZoneR = arenaRadius * CENTER_ZONE_RADIUS_FRAC
        if (ballInCenter) {
            drawCircle(
                color = Color(0x4466BB6A),
                radius = centerZoneR + 6f,
                center = Offset(cx, cy),
            )
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(Color(0xCC81C784), Color(0xAA43A047)),
                        center = Offset(cx, cy),
                        radius = centerZoneR,
                    ),
                radius = centerZoneR,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color(0xFFE8F5E9),
                radius = centerZoneR,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f),
            )
            drawCircle(
                color = Color(0xFFE8F5E9),
                radius = 3.5f,
                center = Offset(cx, cy),
            )
        } else {
            drawCircle(
                color = Color(0x33B0BEC5),
                radius = centerZoneR,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color(0x88B0BEC5),
                radius = centerZoneR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f),
            )
            drawCircle(
                color = Color(0x99B0BEC5),
                radius = 3f,
                center = Offset(cx, cy),
            )
        }

        val ballX = arenaLeft + ball.x * arenaW
        val ballY = arenaTop + ball.y * arenaH
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFFE082), Color(0xFFFF8F00)),
                    center = Offset(ballX - ballR * 0.3f, ballY - ballR * 0.3f),
                    radius = ballR,
                ),
            radius = ballR,
            center = Offset(ballX, ballY),
        )
        drawCircle(
            color = Color(0x55FFFFFF),
            radius = ballR * 0.35f,
            center = Offset(ballX - ballR * 0.25f, ballY - ballR * 0.25f),
        )
    }
}

private fun formatScore(ms: Long): String {
    val sec = ms / 1000f
    return String.format("%.1fs", sec)
}

private fun readBestMs(context: Context, difficulty: BalanceDifficulty): Long {
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return max(0L, p.getLong("${KEY_BEST_MS}_${difficulty.name}", 0L))
}

private fun writeBestMs(context: Context, ms: Long, difficulty: BalanceDifficulty) {
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val key = "${KEY_BEST_MS}_${difficulty.name}"
    val prev = p.getLong(key, 0L)
    if (ms > prev) {
        p.edit().putLong(key, ms).apply()
    }
}

private fun readDifficulty(context: Context): BalanceDifficulty {
    val o = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_DIFFICULTY, BalanceDifficulty.NORMAL.ordinal)
    return BalanceDifficulty.entries.getOrNull(o) ?: BalanceDifficulty.NORMAL
}

private fun writeDifficulty(context: Context, difficulty: BalanceDifficulty) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_DIFFICULTY, difficulty.ordinal)
        .apply()
}
