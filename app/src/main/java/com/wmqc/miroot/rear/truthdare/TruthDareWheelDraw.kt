package com.wmqc.miroot.rear.truthdare

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

internal const val TYPE_SEGMENT_COUNT = 8
internal const val TYPE_SEGMENT_DEG = 360f / TYPE_SEGMENT_COUNT
internal const val WHEEL_RADIUS_FRAC = 0.90f
/** 真心话/大冒险标签半径（相对外圈），越大越靠外缘 */
internal const val TYPE_LABEL_RADIUS_FRAC = 0.74f
internal const val PLAYER_LABEL_RADIUS_FRAC = 0.74f

internal val TruthColor = Color(0xFF3B82F6)
internal val TruthColorDark = Color(0xFF1D4ED8)
internal val DareColor = Color(0xFFEF4444)
internal val DareColorDark = Color(0xFFB91C1C)

private val playerWheelColors =
    listOf(
        listOf(Color(0xFFFB7185), Color(0xFFEC4899)),
        listOf(Color(0xFFFB923C), Color(0xFFF59E0B)),
        listOf(Color(0xFFFACC15), Color(0xFF84CC16)),
        listOf(Color(0xFF4ADE80), Color(0xFF10B981)),
        listOf(Color(0xFF2DD4BF), Color(0xFF06B6D4)),
        listOf(Color(0xFF60A5FA), Color(0xFF6366F1)),
        listOf(Color(0xFFA78BFA), Color(0xFF8B5CF6)),
        listOf(Color(0xFFE879F9), Color(0xFFEC4899)),
        listOf(Color(0xFFF87171), Color(0xFFFB7185)),
        listOf(Color(0xFF38BDF8), Color(0xFF3B82F6)),
    )

internal fun discRadius(size: Size): Float = min(size.width, size.height) / 2f * WHEEL_RADIUS_FRAC

internal fun segmentAtTypePointer(rotationDeg: Float): Int {
    val normalized = ((270f - rotationDeg) % 360f + 360f) % 360f
    return (normalized / TYPE_SEGMENT_DEG).toInt().coerceIn(0, TYPE_SEGMENT_COUNT - 1)
}

internal fun segmentAtPlayerPointer(rotationDeg: Float, playerCount: Int): Int {
    if (playerCount <= 0) return 0
    val seg = 360f / playerCount
    val offset = ((-rotationDeg % 360f) + 360f) % 360f
    return (offset / seg).toInt().coerceIn(0, playerCount - 1)
}

/** 使第 [targetIndex] 个玩家扇区中心对准顶部指针所需的 rotation（mod 360）。 */
internal fun playerWheelRotationModForIndex(targetIndex: Int, playerCount: Int): Float {
    if (playerCount <= 0) return 0f
    val seg = 360f / playerCount
    val centerOffset = targetIndex * seg + seg / 2f
    return ((360f - centerOffset) % 360f + 360f) % 360f
}

/** 从 [currentRotation] 顺时针再转若干整圈后，落到目标扇区中心（避免停在分割线上）。 */
internal fun playerWheelSpinTargetRotation(
    currentRotation: Float,
    targetIndex: Int,
    playerCount: Int,
    extraFullTurns: Int = 4 + Random.nextInt(2),
    jitterFraction: Float = (Random.nextFloat() - 0.5f) * 0.1f,
): Float {
    val seg = 360f / playerCount
    val jitter = jitterFraction * seg
    val desiredMod =
        ((playerWheelRotationModForIndex(targetIndex, playerCount) + jitter) % 360f + 360f) % 360f
    val currentMod = ((currentRotation % 360f) + 360f) % 360f
    var delta = desiredMod - currentMod
    if (delta <= 0f) delta += 360f
    return currentRotation + extraFullTurns * 360f + delta
}

internal fun renderTypeWheelSnapshot(sizePx: Int, rotationDeg: Float, density: Density): ImageBitmap =
    renderSnapshot(sizePx, density) { cx, cy, radius ->
        rotate(rotationDeg, pivot = Offset(cx, cy)) {
            drawTypeWheelDisc(cx, cy, radius)
        }
        drawWheelPointer(cx, cy, radius)
    }

internal fun renderPlayerWheelSnapshot(sizePx: Int, rotationDeg: Float, players: List<String>, density: Density): ImageBitmap =
    renderSnapshot(sizePx, density) { cx, cy, radius ->
        rotate(rotationDeg, pivot = Offset(cx, cy)) {
            drawPlayerWheelDisc(cx, cy, radius, players)
        }
        drawWheelPointer(cx, cy, radius)
    }

private fun renderSnapshot(
    sizePx: Int,
    density: Density,
    draw: DrawScope.(cx: Float, cy: Float, radius: Float) -> Unit,
): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val drawScope = CanvasDrawScope()
    drawScope.draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        draw(cx, cy, discRadius(size))
    }
    return bitmap
}

internal fun DrawScope.drawTypeWheelDisc(cx: Float, cy: Float, radius: Float) {
    for (i in 0 until TYPE_SEGMENT_COUNT) {
        val isTruth = i % 2 == 0
        val startAngle = -90f + i * TYPE_SEGMENT_DEG
        drawArc(
            brush =
                Brush.radialGradient(
                    colors = if (isTruth) listOf(TruthColor, TruthColorDark) else listOf(DareColor, DareColorDark),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
            startAngle = startAngle,
            sweepAngle = TYPE_SEGMENT_DEG,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
        )
        val labelAngleRad = Math.toRadians((startAngle + TYPE_SEGMENT_DEG / 2f).toDouble())
        val labelR = radius * TYPE_LABEL_RADIUS_FRAC
        drawWheelLabel(
            x = cx + cos(labelAngleRad).toFloat() * labelR,
            y = cy + sin(labelAngleRad).toFloat() * labelR,
            text = if (isTruth) "真心话" else "大冒险",
            rotationDeg = startAngle + TYPE_SEGMENT_DEG / 2f + 90f,
            textSize = radius * 0.14f,
        )
    }
    drawCircle(color = Color(0xFF0F172A), radius = radius * 0.22f, center = Offset(cx, cy))
}

internal fun DrawScope.drawPlayerWheelDisc(cx: Float, cy: Float, radius: Float, players: List<String>) {
    if (players.isEmpty()) return
    val seg = 360f / players.size
    players.forEachIndexed { index, name ->
        val startAngle = -90f + index * seg
        val colors = playerWheelColors[index % playerWheelColors.size]
        drawArc(
            brush = Brush.radialGradient(colors = colors, center = Offset(cx, cy), radius = radius),
            startAngle = startAngle,
            sweepAngle = seg,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
        )
        val mid = startAngle + seg / 2f
        val labelAngleRad = Math.toRadians(mid.toDouble())
        val labelR = radius * PLAYER_LABEL_RADIUS_FRAC
        drawWheelLabel(
            x = cx + cos(labelAngleRad).toFloat() * labelR,
            y = cy + sin(labelAngleRad).toFloat() * labelR,
            text = name.take(4),
            rotationDeg = mid + 90f,
            textSize = (radius * 0.12f).coerceAtLeast(18f),
        )
    }
    drawCircle(color = Color(0xFF0F172A), radius = radius * 0.22f, center = Offset(cx, cy))
}

internal fun DrawScope.drawWheelPointer(cx: Float, cy: Float, radius: Float) {
    val gap = radius * 0.04f
    val halfWidth = radius * 0.14f
    val pointerHeight = radius * 0.19f
    val topY = cy - radius - gap
    val path =
        Path().apply {
            moveTo(cx, topY + pointerHeight)
            lineTo(cx - halfWidth, topY)
            lineTo(cx + halfWidth, topY)
            close()
        }
    val strokeW = (radius * 0.018f).coerceAtLeast(1.5f)
    drawPath(path, Color(0xFFFFD54F))
    drawPath(path, Color.White.copy(alpha = 0.5f), style = Stroke(width = strokeW))
}

private fun DrawScope.drawWheelLabel(
    x: Float,
    y: Float,
    text: String,
    rotationDeg: Float,
    textSize: Float,
) {
    drawContext.canvas.nativeCanvas.apply {
        save()
        rotate(rotationDeg, x, y)
        val paint =
            Paint().apply {
                color = Color.White.toArgb()
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
        drawText(text, x, y + paint.textSize / 3f, paint)
        restore()
    }
}
