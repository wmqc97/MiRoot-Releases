package com.wmqc.miroot.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import androidx.compose.ui.res.stringResource
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.LyricsFontHelper

/**
 * 音乐投屏设置页：遵循 [Miuix](https://github.com/compose-miuix-ui/miuix) 与 README 中的 `MiuixTheme` 用法，
 * 卡片与页面同底色、无描边，与状态/功能页一致。
 */
@Composable
fun MusicScreen(
    privileged: Boolean,
    listenerEnabled: Boolean,
    hasPlayer: Boolean,
    projecting: Boolean,
    settings: LyricsUiSettings,
    onSettingsChange: (LyricsUiSettings) -> Unit,
    onOpenListenerSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickProjectionFont: () -> Unit,
    onPickAbyssalFont: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val miuixColors = if (dark) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = miuixColors) {
        val ctx = LocalContext.current
        val materialScheme = remember(dark) {
            val p = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
            if (dark) {
                m3DarkColorScheme(primary = p, secondary = p, tertiary = p)
            } else {
                m3LightColorScheme(primary = p, secondary = p, tertiary = p)
            }
        }
        MaterialTheme(colorScheme = materialScheme) {
        val scheme = MiuixTheme.colorScheme
        val padH = dimensionResource(R.dimen.mi_music_page_padding_horizontal)
        val padV = dimensionResource(R.dimen.mi_music_page_padding_vertical)
        val cardGap = dimensionResource(R.dimen.mi_music_card_spacing)
        val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
        val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
        val cardColors = rememberMusicCardColors()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBg),
        ) {
            CompositionLocalProvider(LocalContentColor provides onPagePrimary) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = padH, vertical = padV),
                    verticalArrangement = Arrangement.spacedBy(cardGap),
                ) {
                    Text(
                        text = stringResource(R.string.music_title),
                        fontSize = pageTitleTextUnit(),
                        fontWeight = FontWeight.SemiBold,
                        color = onPagePrimary,
                    )

                    MusicSettingsCard(
                        title = stringResource(R.string.music_section_controls),
                        helpMessageRes = R.string.help_music_controls,
                        colors = cardColors,
                        titleTrailing = {
                            ProjectionControlStateButton(
                                privileged = privileged,
                                listenerEnabled = listenerEnabled,
                                hasPlayer = hasPlayer,
                                projecting = projecting,
                                onStart = onStart,
                                onStop = onStop,
                            )
                        },
                    ) {
                        if (!privileged) {
                            Text(
                                text = stringResource(R.string.music_need_privilege),
                                style = MiuixTheme.textStyles.body1,
                                color = scheme.error,
                            )
                        }
                        if (!listenerEnabled) {
                            Text(
                                text = stringResource(R.string.music_need_notification_listener),
                                style = MiuixTheme.textStyles.body1,
                                color = scheme.error,
                            )
                            TextButton(
                                text = stringResource(R.string.music_open_notification_settings),
                                onClick = onOpenListenerSettings,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }

                    MusicSettingsCard(
                        title = stringResource(R.string.music_section_display),
                        helpMessageRes = R.string.help_music_display,
                        colors = cardColors,
                    ) {
                        SliderRow(stringResource(R.string.music_lyrics_text_size), settings.textSize, 40f..140f) {
                            onSettingsChange(settings.copy(textSize = it))
                        }
                        if (!settings.abyssalMirror) {
                            LyricsFontChoiceRow(
                                label = stringResource(R.string.music_font_projection),
                                selectedId = settings.projectionLyricsFont,
                                onSelectSystem = {
                                    onSettingsChange(
                                        settings.copy(
                                            projectionLyricsFont = LyricsFontHelper.ID_SYSTEM,
                                            projectionLyricsCustomPath = null,
                                        ),
                                    )
                                },
                                onSelectMfgehei = {
                                    onSettingsChange(
                                        settings.copy(
                                            projectionLyricsFont = LyricsFontHelper.ID_MFGEHEI,
                                            projectionLyricsCustomPath = null,
                                        ),
                                    )
                                },
                                onImport = onPickProjectionFont,
                            )
                        }
                        if (settings.abyssalMirror) {
                            LyricsFontChoiceRow(
                                label = stringResource(R.string.music_font_abyssal),
                                selectedId = settings.abyssalLyricsFont,
                                onSelectSystem = {
                                    onSettingsChange(
                                        settings.copy(
                                            abyssalLyricsFont = LyricsFontHelper.ID_SYSTEM,
                                            abyssalLyricsCustomPath = null,
                                        ),
                                    )
                                },
                                onSelectMfgehei = {
                                    onSettingsChange(
                                        settings.copy(
                                            abyssalLyricsFont = LyricsFontHelper.ID_MFGEHEI,
                                            abyssalLyricsCustomPath = null,
                                        ),
                                    )
                                },
                                onImport = onPickAbyssalFont,
                            )
                        }
                        ToggleRow(stringResource(R.string.music_texture_toggle), settings.backgroundTexture) { on ->
                            onSettingsChange(
                                settings.copy(
                                    backgroundTexture = on,
                                    abyssalMirror = if (on) false else settings.abyssalMirror,
                                ),
                            )
                        }
                        if (settings.backgroundTexture) {
                            SliderRow(
                                stringResource(R.string.music_texture_scale),
                                settings.backgroundTextureSize,
                                0.5f..2f,
                            ) {
                                onSettingsChange(settings.copy(backgroundTextureSize = it))
                            }
                            SliderRow(
                                stringResource(R.string.music_texture_alpha),
                                settings.backgroundTextureAlpha.toFloat(),
                                0f..100f,
                            ) {
                                onSettingsChange(settings.copy(backgroundTextureAlpha = it.roundToInt()))
                            }
                        }
                    }

                    MusicSettingsCard(
                        title = stringResource(R.string.music_section_effects),
                        helpMessageRes = R.string.help_music_effects,
                        colors = cardColors,
                    ) {
                        ToggleRow(stringResource(R.string.music_abyssal), settings.abyssalMirror) {
                            onSettingsChange(settings.withAbyssalMirror(it))
                        }
                        if (settings.abyssalMirror) {
                            SliderRow(
                                stringResource(R.string.music_abyssal_gyro),
                                settings.abyssalGyroSensitivity,
                                0.5f..2f,
                            ) {
                                onSettingsChange(settings.copy(abyssalGyroSensitivity = it))
                            }
                            SliderRow(
                                stringResource(R.string.music_abyssal_range),
                                settings.abyssalMovableRange,
                                1f..4f,
                            ) {
                                onSettingsChange(settings.copy(abyssalMovableRange = it))
                            }
                        } else {
                            ToggleRow(stringResource(R.string.music_word_by_word), settings.wordByWord) { on ->
                                onSettingsChange(
                                    settings.copy(
                                        wordByWord = on,
                                        abyssalMirror = if (on) false else settings.abyssalMirror,
                                    ),
                                )
                            }
                            if (settings.wordByWord) {
                                SliderRow(
                                    stringResource(R.string.music_normal_alpha),
                                    settings.normalLyricsAlpha.toFloat(),
                                    0f..100f,
                                ) {
                                    onSettingsChange(settings.copy(normalLyricsAlpha = it.roundToInt()))
                                }
                            }
                            ToggleRow(stringResource(R.string.music_marquee), settings.marqueeLight) { on ->
                                onSettingsChange(
                                    settings.copy(
                                        marqueeLight = on,
                                        abyssalMirror = if (on) false else settings.abyssalMirror,
                                    ),
                                )
                            }
                            if (settings.marqueeLight) {
                                SliderRow(stringResource(R.string.music_marquee_width), settings.marqueeLightSize, 4f..30f) {
                                    onSettingsChange(settings.copy(marqueeLightSize = it))
                                }
                            }
                            ToggleRow(stringResource(R.string.music_neon_border), settings.neonBorder) { on ->
                                onSettingsChange(
                                    settings.copy(
                                        neonBorder = on,
                                        abyssalMirror = if (on) false else settings.abyssalMirror,
                                    ),
                                )
                            }
                            ToggleRow(stringResource(R.string.music_gesture), settings.gestureControl) { on ->
                                onSettingsChange(
                                    settings.copy(
                                        gestureControl = on,
                                        abyssalMirror = if (on) false else settings.abyssalMirror,
                                    ),
                                )
                            }
                        }
                    }

                    MusicSettingsCard(
                        title = stringResource(R.string.music_section_advanced),
                        helpMessageRes = R.string.help_music_advanced,
                        colors = cardColors,
                    ) {
                        ToggleRow(stringResource(R.string.music_auto_projection), settings.autoProjection) {
                            onSettingsChange(settings.copy(autoProjection = it))
                        }
                    }
                }
            }
        }
        }
    }
}

/** 页标题字号：与 `TextAppearance.MiRoot.PageTitle` / `@dimen/mi_page_title_text_size` 一致。 */
@Composable
private fun pageTitleTextUnit(): TextUnit {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val px = ctx.resources.getDimension(R.dimen.mi_page_title_text_size)
    return (px / (d.density * d.fontScale)).sp
}

/**
 * 投屏控制标题行右侧：单按钮表示状态（结束投屏 / 开始投屏 / 未播放音乐）。
 */
@Composable
private fun ProjectionControlStateButton(
    privileged: Boolean,
    listenerEnabled: Boolean,
    hasPlayer: Boolean,
    projecting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val label: String
    val usePrimaryColors: Boolean
    val enabled: Boolean
    val onClick: () -> Unit
    when {
        projecting -> {
            label = stringResource(R.string.music_stop)
            usePrimaryColors = true
            enabled = true
            onClick = onStop
        }
        hasPlayer && privileged && listenerEnabled -> {
            label = stringResource(R.string.music_start)
            usePrimaryColors = true
            enabled = true
            onClick = onStart
        }
        hasPlayer -> {
            label = stringResource(R.string.music_start)
            usePrimaryColors = true
            enabled = false
            onClick = onStart
        }
        else -> {
            label = stringResource(R.string.music_not_playing)
            usePrimaryColors = false
            enabled = false
            onClick = { }
        }
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (usePrimaryColors) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.button.copy(fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberMusicCardColors(): CardColors {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        val flatBg = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))
        CardColors(
            color = flatBg,
            contentColor = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary)),
        )
    }
}

@Composable
private fun MusicSettingsCard(
    title: String,
    helpMessageRes: Int,
    colors: CardColors,
    titleTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { M3Text(title) },
            text = { M3Text(stringResource(helpMessageRes)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    M3Text(stringResource(R.string.welcome_dialog_confirm))
                }
            },
        )
    }
    val cardPad = dimensionResource(R.dimen.mi_music_card_inner_padding)
    val rowGap = dimensionResource(R.dimen.mi_music_card_row_spacing)
    val corner = dimensionResource(R.dimen.mi_music_card_corner_radius)
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = corner,
        insideMargin = PaddingValues(cardPad),
        colors = colors,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
            SectionTitleBar(title, titleTrailing, onTitleClick = { showHelp = true })
            content()
        }
    }
}

@Composable
private fun SectionTitleBar(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    onTitleClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val px = ctx.resources.getDimension(R.dimen.mi_card_section_title_text_size)
    val titleSp = (px / (d.density * d.fontScale)).sp
    val titleStyle = TextStyle(
        fontSize = titleSp,
        fontWeight = FontWeight.SemiBold,
        color = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary)),
    )
    if (trailing == null) {
        Text(
            text = title,
            style = titleStyle,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTitleClick),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = titleStyle,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTitleClick),
            )
            trailing()
        }
    }
}

private fun formatSliderValue(value: Float, range: ClosedFloatingPointRange<Float>): String {
    val s = range.start
    val e = range.endInclusive
    return when {
        s >= 40f -> "${value.roundToInt()}px"
        s >= 4f && e <= 30f -> "${value.roundToInt()}px"
        abs(s - 0.5f) < 1e-3f && abs(e - 2f) < 1e-3f -> "%.2f".format(value)
        abs(s - 1f) < 1e-3f && abs(e - 4f) < 1e-3f -> "%.2f".format(value)
        else -> "${value.roundToInt()}%"
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val onChangeState by rememberUpdatedState(onChange)
    val ctx = LocalContext.current
    val accent = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
    val labelMuted = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = labelMuted,
            )
            Text(
                text = formatSliderValue(value, range),
                style = MiuixTheme.textStyles.body2,
                color = accent,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChangeState(it) },
            valueRange = range,
            steps = 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LyricsFontChoiceRow(
    label: String,
    selectedId: String,
    onSelectSystem: () -> Unit,
    onSelectMfgehei: () -> Unit,
    onImport: () -> Unit,
) {
    val ctx = LocalContext.current
    val labelMuted = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val sys = LyricsFontHelper.ID_SYSTEM
    val mf = LyricsFontHelper.ID_MFGEHEI
    val custom = LyricsFontHelper.ID_CUSTOM
    val btnLabelStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp)
    val fontGap = dimensionResource(R.dimen.mi_music_font_choice_gap)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(fontGap),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = labelMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LyricsFontOptionButton(
                text = stringResource(R.string.music_font_system),
                selected = selectedId == sys,
                onClick = onSelectSystem,
                labelStyle = btnLabelStyle,
            )
            LyricsFontOptionButton(
                text = stringResource(R.string.music_font_mfgehei),
                selected = selectedId == mf,
                onClick = onSelectMfgehei,
                labelStyle = btnLabelStyle,
            )
            LyricsFontOptionButton(
                text = stringResource(R.string.music_font_import),
                selected = selectedId == custom,
                onClick = onImport,
                labelStyle = btnLabelStyle,
            )
        }
    }
}

@Composable
private fun RowScope.LyricsFontOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    labelStyle: TextStyle,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = if (selected) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = text,
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
