package com.wmqc.miroot.ui.features

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 充电动画涨水：步进 0–99 映射到内部 25%–300%（与 [ChargingAnimationPrefs] 一致）；功能页展示为满幅时长 ms。
 */
@Composable
fun ChargingFillSpeedSlider(
    stepState: MutableIntState,
    onStepChangeWhileDragging: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val step = stepState.intValue.coerceIn(0, 99)
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        Slider(
            value = step.toFloat(),
            onValueChange = { v ->
                val s = v.roundToInt().coerceIn(0, 99)
                stepState.intValue = s
                onStepChangeWhileDragging(s)
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..99f,
            steps = 0,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
