package com.wmqc.miroot.rear.heartrate

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmqc.miroot.R

data class HeartRateUiState(
    val bpm: Int? = null,
    val deviceName: String? = null,
    val status: HeartRateMonitorStatus = HeartRateMonitorStatus.IDLE,
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = true,
)

@Composable
fun RearHeartRateDisplay(
    state: HeartRateUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val heartRed = Color(0xFFE53935)
    val glowRed = Color(0xFFFF5252)
    val bpmForBeat = state.bpm?.coerceIn(40, 200) ?: 72
    val beatHalfMs = (30_000 / bpmForBeat).coerceIn(180, 900)

    val transition = rememberInfiniteTransition(label = "heartPulse")
    val scale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.18f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(beatHalfMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "heartScale",
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(beatHalfMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "heartGlow",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = glowRed,
                    modifier =
                        Modifier
                            .size(160.dp)
                            .scale(scale * 1.15f)
                            .alpha(glowAlpha * 0.45f),
                )
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = heartRed,
                    modifier =
                        Modifier
                            .size(120.dp)
                            .scale(scale),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (state.bpm != null) {
                Text(
                    text = state.bpm.toString(),
                    color = Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = context.getString(R.string.rear_heart_rate_bpm_unit),
                    color = Color(0xCCFFFFFF),
                    fontSize = 18.sp,
                )
            } else {
                Text(
                    text = "—",
                    color = Color(0x88FFFFFF),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText(context, state),
                color = Color(0xAAFFFFFF),
                fontSize = 14.sp,
            )

            state.deviceName?.takeIf { it.isNotEmpty() }?.let { name ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = name,
                    color = Color(0x77FFFFFF),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun statusText(context: android.content.Context, state: HeartRateUiState): String {
    if (!state.permissionsGranted) {
        return context.getString(R.string.rear_heart_rate_need_permission)
    }
    if (!state.bluetoothEnabled) {
        return context.getString(R.string.rear_heart_rate_bluetooth_off)
    }
    return when (state.status) {
        HeartRateMonitorStatus.IDLE -> context.getString(R.string.rear_heart_rate_status_idle)
        HeartRateMonitorStatus.SCANNING -> context.getString(R.string.rear_heart_rate_status_scanning)
        HeartRateMonitorStatus.CONNECTING -> context.getString(R.string.rear_heart_rate_status_connecting)
        HeartRateMonitorStatus.CONNECTED -> context.getString(R.string.rear_heart_rate_status_connected)
        HeartRateMonitorStatus.NO_SIGNAL -> context.getString(R.string.rear_heart_rate_status_no_signal)
    }
}
