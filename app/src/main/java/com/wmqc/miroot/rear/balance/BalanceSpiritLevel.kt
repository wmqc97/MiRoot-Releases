package com.wmqc.miroot.rear.balance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val LEVEL_THRESHOLD_DEG = 1.8f
private const val SPIRIT_MAX_TILT_DEG = 18f
private const val RULER_MAJOR_STEP_DEG = 6
private const val RULER_MINOR_STEP_DEG = 3

private val SpiritGreen = Color(0xFF4ADE80)
private val SpiritRed = Color(0xFFF87171)

internal data class SpiritTiltAngles(
    val rollDeg: Float,
    val pitchDeg: Float,
    val isLevel: Boolean,
) {
    val upDeg: Float get() = (-pitchDeg).coerceAtLeast(0f)
    val downDeg: Float get() = pitchDeg.coerceAtLeast(0f)
    val leftDeg: Float get() = (-rollDeg).coerceAtLeast(0f)
    val rightDeg: Float get() = rollDeg.coerceAtLeast(0f)
}

internal fun computeSpiritTiltAngles(tiltX: Float, tiltY: Float, neutralX: Float, neutralY: Float): SpiritTiltAngles {
    val rollDeg = BalanceTiltMath.deltaToDegrees(tiltX - neutralX)
    val pitchDeg = BalanceTiltMath.deltaToDegrees(tiltY - neutralY)
    val isLevel = hypot(rollDeg.toDouble(), pitchDeg.toDouble()).toFloat() < LEVEL_THRESHOLD_DEG
    return SpiritTiltAngles(rollDeg, pitchDeg, isLevel)
}

@Composable
internal fun SpiritLevelPanel(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") safeStartPx: Float,
    contentStartDp: Dp,
    tiltX: Float,
    tiltY: Float,
    neutralX: Float,
    neutralY: Float,
) {
    val angles = computeSpiritTiltAngles(tiltX, tiltY, neutralX, neutralY)
    val accent = if (angles.isLevel) SpiritGreen else SpiritRed

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .padding(start = contentStartDp, end = 8.dp, top = 34.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val vialSize = minOf(maxWidth, maxHeight)
        Box(
            modifier = Modifier.size(vialSize),
            contentAlignment = Alignment.Center,
        ) {
            SpiritVial(
                angles = angles,
                accent = accent,
                modifier = Modifier.fillMaxSize(),
            )
            SpiritDirectionLabel(
                modifier = Modifier.align(Alignment.TopCenter),
                direction = "上",
                angleDeg = angles.upDeg,
                accent = accent,
            )
            SpiritDirectionLabel(
                modifier = Modifier.align(Alignment.BottomCenter),
                direction = "下",
                angleDeg = angles.downDeg,
                accent = accent,
            )
            SpiritDirectionLabel(
                modifier = Modifier.align(Alignment.CenterStart),
                direction = "左",
                angleDeg = angles.leftDeg,
                accent = accent,
            )
            SpiritDirectionLabel(
                modifier = Modifier.align(Alignment.CenterEnd),
                direction = "右",
                angleDeg = angles.rightDeg,
                accent = accent,
            )
        }
    }
}

@Composable
private fun SpiritDirectionLabel(
    direction: String,
    angleDeg: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = direction,
            color = Color(0x99FFFFFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = String.format("%.1f°", angleDeg),
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SpiritVial(
    angles: SpiritTiltAngles,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = min(size.width, size.height) / 2f
        val vialR = half * 0.88f
        val bubbleMaxR = vialR * 0.58f

        if (angles.isLevel) {
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors = listOf(SpiritGreen.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = vialR * 1.04f,
                    ),
                radius = vialR * 1.04f,
                center = Offset(cx, cy),
            )
        }

        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(Color(0xFF2A3A52), Color(0xFF101820), Color(0xFF080C12)),
                    center = Offset(cx, cy - vialR * 0.28f),
                    radius = vialR,
                ),
            radius = vialR * 0.94f,
            center = Offset(cx, cy),
        )

        drawCircle(
            color = Color(0xFF3D5168),
            radius = vialR,
            center = Offset(cx, cy),
            style = Stroke(width = (vialR * 0.04f).coerceAtMost(half * 0.06f)),
        )

        val levelGuideR = bubbleMaxR * (LEVEL_THRESHOLD_DEG / SPIRIT_MAX_TILT_DEG)
        drawCircle(
            color = if (angles.isLevel) SpiritGreen.copy(alpha = 0.45f) else Color(0x28FFFFFF),
            radius = levelGuideR,
            center = Offset(cx, cy),
            style =
                Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                ),
        )

        drawLine(
            color = Color(0x20FFFFFF),
            start = Offset(cx - vialR * 0.82f, cy),
            end = Offset(cx + vialR * 0.82f, cy),
            strokeWidth = 1f,
        )
        drawLine(
            color = Color(0x20FFFFFF),
            start = Offset(cx, cy - vialR * 0.82f),
            end = Offset(cx, cy + vialR * 0.82f),
            strokeWidth = 1f,
        )

        drawDirectionRuler(cx, cy, vialR, 270f, angles.upDeg, accent)
        drawDirectionRuler(cx, cy, vialR, 90f, angles.downDeg, accent)
        drawDirectionRuler(cx, cy, vialR, 180f, angles.leftDeg, accent)
        drawDirectionRuler(cx, cy, vialR, 0f, angles.rightDeg, accent)

        val bubbleOffsetX = (angles.rollDeg / SPIRIT_MAX_TILT_DEG).coerceIn(-1f, 1f) * bubbleMaxR
        val bubbleOffsetY = (angles.pitchDeg / SPIRIT_MAX_TILT_DEG).coerceIn(-1f, 1f) * bubbleMaxR
        val bubbleR = vialR * 0.1f
        val bubbleCenter = Offset(cx + bubbleOffsetX, cy + bubbleOffsetY)

        drawCircle(
            color = Color(0x38000000),
            radius = bubbleR,
            center = Offset(bubbleCenter.x + bubbleR * 0.1f, bubbleCenter.y + bubbleR * 0.14f),
        )
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFFF9E6), Color(0xFFFFC107), Color(0xFFE68A00)),
                    center = Offset(bubbleCenter.x - bubbleR * 0.3f, bubbleCenter.y - bubbleR * 0.32f),
                    radius = bubbleR,
                ),
            radius = bubbleR,
            center = bubbleCenter,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = bubbleR * 0.24f,
            center = Offset(bubbleCenter.x - bubbleR * 0.38f, bubbleCenter.y - bubbleR * 0.4f),
        )

        drawCircle(
            color = accent.copy(alpha = 0.7f),
            radius = vialR,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDirectionRuler(
    cx: Float,
    cy: Float,
    vialR: Float,
    directionDeg: Float,
    currentDeg: Float,
    accent: Color,
) {
    val dirRad = Math.toRadians(directionDeg.toDouble())
    val dirX = sin(dirRad).toFloat()
    val dirY = (-kotlin.math.cos(dirRad)).toFloat()
    val perpX = kotlin.math.cos(dirRad).toFloat()
    val perpY = sin(dirRad).toFloat()

    val outerR = vialR * 0.88f
    val innerR = vialR * 0.62f

    drawLine(
        color = Color(0x55FFFFFF),
        start = Offset(cx + dirX * outerR, cy + dirY * outerR),
        end = Offset(cx + dirX * innerR, cy + dirY * innerR),
        strokeWidth = 1.2f,
    )

    var tickDeg = 0
    while (tickDeg <= SPIRIT_MAX_TILT_DEG.roundToInt()) {
        val frac = tickDeg.toFloat() / SPIRIT_MAX_TILT_DEG
        val tickR = outerR - (outerR - innerR) * frac
        val tickCenter = Offset(cx + dirX * tickR, cy + dirY * tickR)
        val isMajor = tickDeg % RULER_MAJOR_STEP_DEG == 0
        val tickHalf = if (isMajor) 8f else 4f
        drawLine(
            color = if (isMajor) Color(0x77FFFFFF) else Color(0x33FFFFFF),
            start = Offset(tickCenter.x - perpX * tickHalf, tickCenter.y - perpY * tickHalf),
            end = Offset(tickCenter.x + perpX * tickHalf, tickCenter.y + perpY * tickHalf),
            strokeWidth = if (isMajor) 1.2f else 0.8f,
        )
        tickDeg += RULER_MINOR_STEP_DEG
    }

    if (currentDeg >= 0.05f) {
        val markerFrac = (currentDeg / SPIRIT_MAX_TILT_DEG).coerceIn(0f, 1f)
        val markerR = outerR - (outerR - innerR) * markerFrac
        val marker = Offset(cx + dirX * markerR, cy + dirY * markerR)
        drawCircle(color = accent, radius = 4f, center = marker)
    }
}
