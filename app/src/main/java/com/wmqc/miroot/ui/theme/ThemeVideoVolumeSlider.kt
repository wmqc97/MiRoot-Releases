package com.wmqc.miroot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 与音乐页 [com.wmqc.miroot.ui.music.MusicScreen] 中 Slider 行相同的 Miuix [Slider] 风格（脚注标签 + 强调色数值 + 滑条）。
 */
@Composable
fun ThemeVideoVolumeSlider(
    volumeState: MutableIntState,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val v = volumeState.intValue.coerceIn(0, 100)
    val ctx = LocalContext.current
    val accent = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
    val labelMuted = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        Column(modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.theme_video_volume),
                    style = MiuixTheme.textStyles.footnote1,
                    color = labelMuted,
                )
                Text(
                    text = "${v}%",
                    style = MiuixTheme.textStyles.body2,
                    color = accent,
                )
            }
            Slider(
                value = v.toFloat(),
                onValueChange = { f ->
                    val n = f.roundToInt().coerceIn(0, 100)
                    volumeState.intValue = n
                    onVolumeChange(n)
                },
                valueRange = 0f..100f,
                steps = 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
