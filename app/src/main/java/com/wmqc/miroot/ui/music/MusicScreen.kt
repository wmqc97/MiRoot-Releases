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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.AnnotatedString
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
    rootAvailable: Boolean,
    listenerEnabled: Boolean,
    hasPlayer: Boolean,
    projecting: Boolean,
    settings: LyricsUiSettings,
    onSettingsChange: (LyricsUiSettings) -> Unit,
    onOpenListenerSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickProjectionFont: () -> Unit,
    onImportLyricsDict: () -> Unit,
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
        var showLyricsDictFormatDialog by remember { mutableStateOf(false) }
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
                        ToggleRowWithHint(
                            label = stringResource(R.string.music_auto_projection),
                            checked = settings.autoProjection,
                            hint = stringResource(R.string.music_auto_projection_hint),
                        ) {
                            onSettingsChange(settings.copy(autoProjection = it))
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
                        LyricsSourceModeRow(
                            selectedMode = if (rootAvailable) settings.lyricsSourceMode else LyricsSourceMode.NETWORK_ONLY,
                            rootAvailable = rootAvailable,
                            onSelectNetworkOnly = {
                                onSettingsChange(settings.copy(lyricsSourceMode = LyricsSourceMode.NETWORK_ONLY))
                            },
                            onSelectSuperLyricOnly = {
                                if (rootAvailable) {
                                    onSettingsChange(settings.copy(lyricsSourceMode = LyricsSourceMode.SUPER_LYRIC_ONLY))
                                }
                            },
                            onSelectMixed = {
                                if (rootAvailable) {
                                    onSettingsChange(settings.copy(lyricsSourceMode = LyricsSourceMode.MIXED))
                                }
                            },
                        )
                        if (!settings.powerSavingMode) {
                            LyricsDisplayModeRow(
                                settings = settings,
                                onSelectLine = {
                                    onSettingsChange(
                                        settings.copy(
                                            abyssalMirror = false,
                                            shuffleSplitEffect = false,
                                        ),
                                    )
                                },
                                onSelectSplit = {
                                    onSettingsChange(
                                        settings.copy(
                                            shuffleSplitEffect = true,
                                            shuffleSplitOnlyCurrentLine = true,
                                            shuffleSplitMode = "WORD",
                                            abyssalMirror = false,
                                            wordByWord = false,
                                            gestureControl = false,
                                        ),
                                    )
                                },
                                onSelectAbyssal = {
                                    onSettingsChange(settings.withAbyssalMirror(true))
                                },
                            )
                            val lineMode =
                                !settings.abyssalMirror && !settings.shuffleSplitEffect
                            if (lineMode) {
                                ToggleRow(
                                    stringResource(R.string.music_word_by_word),
                                    settings.wordByWord,
                                ) { on ->
                                    onSettingsChange(
                                        settings.copy(
                                            wordByWord = on,
                                            abyssalMirror = if (on) false else settings.abyssalMirror,
                                            shuffleSplitEffect = if (on) false else settings.shuffleSplitEffect,
                                        ),
                                    )
                                }
                                if (settings.wordByWord) {
                                    SliderRow(
                                        stringResource(R.string.music_normal_alpha),
                                        settings.normalLyricsAlpha.toFloat(),
                                        0f..100f,
                                        step = 5f,
                                    ) {
                                        onSettingsChange(settings.copy(normalLyricsAlpha = it.roundToInt()))
                                    }
                                    SliderRow(
                                        stringResource(R.string.music_projection_sync_offset),
                                        settings.projectionSyncOffsetMs.toFloat(),
                                        -5000f..5000f,
                                        step = 100f,
                                    ) {
                                        onSettingsChange(settings.copy(projectionSyncOffsetMs = it.roundToInt()))
                                    }
                                }
                            }
                            LyricsModeDynamicParams(
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                            )
                        }
                    }

                    MusicSettingsCard(
                        title = stringResource(R.string.music_section_effects),
                        helpMessageRes = R.string.help_music_effects,
                        colors = cardColors,
                    ) {
                        ToggleRowWithHint(
                            label = stringResource(R.string.music_power_saving),
                            checked = settings.powerSavingMode,
                            hint = stringResource(R.string.music_power_saving_hint),
                        ) { on ->
                            // 省电模式只切换运行时策略，不改写用户其它偏好；
                            // 关闭省电模式后应恢复原先配置。
                            onSettingsChange(settings.copy(powerSavingMode = on))
                        }
                        if (!settings.abyssalMirror && !settings.powerSavingMode) {
                            ToggleRow(
                                stringResource(R.string.music_album_art_background),
                                settings.albumArtBackground,
                            ) { on ->
                                onSettingsChange(settings.copy(albumArtBackground = on))
                            }
                            if (settings.albumArtBackground) {
                                SliderRow(
                                    stringResource(R.string.music_album_art_alpha),
                                    settings.albumArtAlphaPercent.toFloat(),
                                    0f..100f,
                                    step = 5f,
                                ) {
                                    onSettingsChange(settings.copy(albumArtAlphaPercent = it.roundToInt()))
                                }
                                SliderRow(
                                    stringResource(R.string.music_album_art_blur_radius),
                                    settings.albumArtBlurRadius,
                                    0f..30f,
                                    step = 1f,
                                ) {
                                    onSettingsChange(settings.copy(albumArtBlurRadius = it))
                                }
                            }
                        }
                        if (!settings.abyssalMirror) {
                            if (!settings.powerSavingMode) {
                                ToggleRowWithHint(
                                    label = stringResource(R.string.music_border_display),
                                    checked = settings.neonBorder,
                                    hint = stringResource(R.string.music_border_display_hint),
                                ) { on ->
                                    onSettingsChange(
                                        settings.copy(
                                            neonBorder = on,
                                            abyssalMirror = if (on) false else settings.abyssalMirror,
                                        ),
                                    )
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
                                    SliderRow(
                                        stringResource(R.string.music_rear_debug_marquee_speed),
                                        settings.marqueeLightDurationMs.toFloat(),
                                        1200f..12000f,
                                        step = 200f,
                                        valueText = "${settings.marqueeLightDurationMs} ms/圈",
                                    ) {
                                        onSettingsChange(settings.copy(marqueeLightDurationMs = it.roundToInt()))
                                    }
                                    SliderRow(stringResource(R.string.music_marquee_width), settings.marqueeLightSize, 4f..30f) {
                                        onSettingsChange(settings.copy(marqueeLightSize = it))
                                    }
                                }
                            }
                        }
                        val debugSectionGap = dimensionResource(R.dimen.mi_music_card_row_spacing)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(debugSectionGap),
                        ) {
                            if (!settings.powerSavingMode) {
                                ToggleRowWithHint(
                                    label = stringResource(R.string.music_rear_debug_breathing_enabled),
                                    checked = settings.breathingEnabled,
                                    hint = stringResource(R.string.music_rear_debug_breathing_enabled_hint),
                                ) { enabled ->
                                    onSettingsChange(settings.copy(breathingEnabled = enabled))
                                }
                                if (settings.breathingEnabled) {
                                    SliderRow(
                                        stringResource(R.string.music_rear_debug_breathing_rhythm),
                                        settings.breathingBpm.toFloat(),
                                        1f..100f,
                                        step = 1f,
                                        valueText = "${settings.breathingBpm} 次/分钟",
                                    ) {
                                        onSettingsChange(settings.copy(breathingBpm = it.roundToInt()))
                                    }
                                    SliderRow(
                                        stringResource(R.string.music_rear_debug_breathing_scale_variance),
                                        settings.breathingScaleVariance,
                                        0.01f..0.20f,
                                        step = 0.01f,
                                    ) {
                                        onSettingsChange(settings.copy(breathingScaleVariance = it))
                                    }
                                    SliderRow(
                                        stringResource(R.string.music_rear_debug_breathing_displacement_strength),
                                        settings.breathingDisplacementStrength,
                                        0.5f..3.0f,
                                    ) {
                                        onSettingsChange(settings.copy(breathingDisplacementStrength = it))
                                    }
                                }
                                ToggleRowWithHint(
                                    label = stringResource(R.string.music_rear_debug_random_color_switch),
                                    checked = settings.randomColorSwitchEnabled,
                                    hint = stringResource(R.string.music_rear_debug_random_color_switch_hint),
                                ) { enabled ->
                                    onSettingsChange(settings.copy(randomColorSwitchEnabled = enabled))
                                }
                                if (settings.randomColorSwitchEnabled) {
                                    SliderRow(
                                        stringResource(R.string.music_rear_debug_color_change_interval),
                                        settings.colorChangeIntervalMs.toFloat(),
                                        1000f..10000f,
                                        step = 1000f,
                                        valueText = stringResource(
                                            R.string.music_color_change_interval_value_fmt,
                                            settings.colorChangeIntervalMs / 1000,
                                        ),
                                    ) {
                                        onSettingsChange(settings.copy(colorChangeIntervalMs = it.roundToInt()))
                                    }
                                }
                            }
                            if (settings.shuffleSplitEffect) {
                                ToggleRow(
                                    label = stringResource(R.string.music_shuffle_split_multicolor),
                                    checked = settings.shuffleSplitMulticolor,
                                ) { on ->
                                    onSettingsChange(settings.copy(shuffleSplitMulticolor = on))
                                }
                            }
                        }
                    }

                    MusicSettingsCard(
                        title = stringResource(R.string.music_section_advanced),
                        helpMessageRes = R.string.help_music_advanced,
                        colors = cardColors,
                    ) {
                        LyricsDictImportTitle(
                            text = stringResource(R.string.music_lyrics_dict_import_button),
                            onClick = { showLyricsDictFormatDialog = true },
                        )
                        TextButton(
                            text = stringResource(R.string.music_lyrics_dict_pick_file_button),
                            onClick = onImportLyricsDict,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
        if (showLyricsDictFormatDialog) {
            LyricsDictFormatDialog(onDismiss = { showLyricsDictFormatDialog = false })
        }
        }
    }
}

@Composable
private fun LyricsDictImportTitle(
    text: String,
    onClick: () -> Unit,
) {
    val hintColor = Color(ContextCompat.getColor(LocalContext.current, R.color.mi_text_secondary))
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1,
        color = hintColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun LyricsDictFormatDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val template = stringResource(R.string.music_lyrics_dict_template_text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text(stringResource(R.string.music_lyrics_dict_template_title)) },
        text = {
            M3Text(
                text = stringResource(R.string.music_lyrics_dict_template_desc) + "\n\n$template",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(template))
                    onDismiss()
                },
            ) {
                M3Text(stringResource(R.string.music_lyrics_dict_copy_template))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                M3Text(stringResource(R.string.music_lyrics_dict_close))
            }
        },
    )
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
        abs(s - 30f) < 1e-3f && abs(e - 100f) < 1e-3f -> "${value.roundToInt()} 次/分钟"
        abs(s + 5000f) < 1e-3f && abs(e - 5000f) < 1e-3f -> {
            val v = value.roundToInt()
            (if (v > 0) "+" else "") + "$v ms"
        }
        abs(s - 500f) < 1e-3f && abs(e - 5000f) < 1e-3f -> "${value.roundToInt()} ms"
        abs(s - 1000f) < 1e-3f && abs(e - 10000f) < 1e-3f -> "${value.roundToInt() / 1000} 秒"
        abs(s - 600f) < 1e-3f && abs(e - 5000f) < 1e-3f -> "${value.roundToInt()} ms"
        abs(s) < 1e-3f && abs(e - 300f) < 1e-3f -> "${value.roundToInt()} ms"
        abs(s - 0.01f) < 1e-3f && abs(e - 0.20f) < 1e-3f -> "${"%.1f".format(value * 100f)}%"
        abs(s) < 1e-3f && abs(e - 30f) < 1e-3f -> "${value.roundToInt()}px"
        abs(s - 0.5f) < 1e-3f && abs(e - 3f) < 1e-3f -> "x${"%.2f".format(value)}"
        s >= 40f -> "${value.roundToInt()}px"
        s >= 4f && e <= 30f -> "${value.roundToInt()}px"
        abs(s - 0.5f) < 1e-3f && abs(e - 2f) < 1e-3f -> "%.2f".format(value)
        abs(s - 1f) < 1e-3f && abs(e - 4f) < 1e-3f -> "%.2f".format(value)
        abs(s) < 1e-3f && abs(e - 20f) < 1e-3f -> "${value.roundToInt()}°"
        abs(s) < 1e-3f && abs(e - 0.4f) < 1e-3f -> "${(value * 100f).roundToInt()}%"
        else -> "${value.roundToInt()}%"
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float? = null,
    valueText: String? = null,
    onChange: (Float) -> Unit,
) {
    val onChangeState by rememberUpdatedState(onChange)
    val ctx = LocalContext.current
    val accent = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
    val labelMuted = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val valueInRange = value.coerceIn(range.start, range.endInclusive)
    val snappedValue = if (step != null && step > 0f) {
        val stepsFromStart = ((valueInRange - range.start) / step).roundToInt()
        (range.start + stepsFromStart * step).coerceIn(range.start, range.endInclusive)
    } else {
        valueInRange
    }
    val sliderSteps = if (step != null && step > 0f) {
        ((((range.endInclusive - range.start) / step).roundToInt()) - 1).coerceAtLeast(0)
    } else {
        0
    }
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
                text = valueText ?: formatSliderValue(snappedValue, range),
                style = MiuixTheme.textStyles.body2,
                color = accent,
            )
        }
        Slider(
            value = snappedValue,
            onValueChange = { raw ->
                val newValue = if (step != null && step > 0f) {
                    val stepsFromStart = ((raw - range.start) / step).roundToInt()
                    (range.start + stepsFromStart * step).coerceIn(range.start, range.endInclusive)
                } else {
                    raw.coerceIn(range.start, range.endInclusive)
                }
                onChangeState(newValue)
            },
            valueRange = range,
            steps = sliderSteps,
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

/** 歌词模式按钮组下方：按当前模式仅展示对应参数，其余隐藏。 */
@Composable
private fun LyricsModeDynamicParams(
    settings: LyricsUiSettings,
    onSettingsChange: (LyricsUiSettings) -> Unit,
) {
    val rowGap = dimensionResource(R.dimen.mi_music_card_row_spacing)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        when {
            settings.abyssalMirror -> {
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
                ToggleRow(
                    stringResource(R.string.music_gesture_abyssal),
                    settings.gestureControl,
                ) { on ->
                    onSettingsChange(settings.copy(gestureControl = on))
                }
            }
            settings.shuffleSplitEffect -> {
                SliderRow("倾斜角度", settings.shuffleSplitTiltRatio, 0f..20f) {
                    onSettingsChange(settings.copy(shuffleSplitTiltRatio = it))
                }
                SliderRow("词组大小浮动", settings.shuffleSplitScaleVariance, 0f..0.4f) {
                    onSettingsChange(settings.copy(shuffleSplitScaleVariance = it))
                }
            }
            else -> {
                ToggleRow(stringResource(R.string.music_gesture), settings.gestureControl) { on ->
                    onSettingsChange(
                        settings.copy(
                            gestureControl = on,
                            abyssalMirror = if (on) false else settings.abyssalMirror,
                        ),
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
                        step = 5f,
                    ) {
                        onSettingsChange(settings.copy(backgroundTextureAlpha = it.roundToInt()))
                    }
                }
            }
        }
    }
}

/** 与 [LyricsSourceModeRow] 相同的三等分按钮样式，逐行 / 分词 / 深渊镜互斥单选。 */
@Composable
private fun LyricsDisplayModeRow(
    settings: LyricsUiSettings,
    onSelectLine: () -> Unit,
    onSelectSplit: () -> Unit,
    onSelectAbyssal: () -> Unit,
) {
    val ctx = LocalContext.current
    val labelMuted = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val btnLabelStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp)
    val gap = dimensionResource(R.dimen.mi_music_font_choice_gap)
    val selectedLine = !settings.abyssalMirror && !settings.shuffleSplitEffect
    val selectedSplit = settings.shuffleSplitEffect
    val selectedAbyssal = settings.abyssalMirror
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text(
            text = stringResource(R.string.music_lyrics_display_mode),
            style = MiuixTheme.textStyles.footnote1,
            color = labelMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LyricsFontOptionButton(
                text = stringResource(R.string.music_lyrics_display_mode_line),
                selected = selectedLine,
                onClick = onSelectLine,
                labelStyle = btnLabelStyle,
            )
            LyricsFontOptionButton(
                text = stringResource(R.string.music_lyrics_display_mode_split),
                selected = selectedSplit,
                onClick = onSelectSplit,
                labelStyle = btnLabelStyle,
            )
            LyricsFontOptionButton(
                text = stringResource(R.string.music_lyrics_display_mode_abyssal),
                selected = selectedAbyssal,
                onClick = onSelectAbyssal,
                labelStyle = btnLabelStyle,
            )
        }
    }
}

@Composable
private fun LyricsSourceModeRow(
    selectedMode: LyricsSourceMode,
    rootAvailable: Boolean,
    onSelectNetworkOnly: () -> Unit,
    onSelectSuperLyricOnly: () -> Unit,
    onSelectMixed: () -> Unit,
) {
    var showSourceTipsDialog by remember { mutableStateOf(false) }
    if (showSourceTipsDialog) {
        AlertDialog(
            onDismissRequest = { showSourceTipsDialog = false },
            title = { M3Text(stringResource(R.string.music_lyrics_source_tips_title)) },
            text = { M3Text(stringResource(R.string.music_lyrics_source_tips_desc)) },
            confirmButton = {
                TextButton(onClick = { showSourceTipsDialog = false }) {
                    M3Text(stringResource(R.string.welcome_dialog_confirm))
                }
            },
        )
    }
    val ctx = LocalContext.current
    val labelMuted = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val btnLabelStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp)
    val gap = dimensionResource(R.dimen.mi_music_font_choice_gap)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text(
            text = stringResource(R.string.music_lyrics_source),
            style = MiuixTheme.textStyles.footnote1,
            color = labelMuted,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSourceTipsDialog = true },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LyricsFontOptionButton(
                text = stringResource(R.string.music_lyrics_source_network_only),
                selected = selectedMode == LyricsSourceMode.NETWORK_ONLY,
                onClick = onSelectNetworkOnly,
                labelStyle = btnLabelStyle,
                enabled = true,
            )
            LyricsFontOptionButton(
                text = stringResource(R.string.music_lyrics_source_super_only),
                selected = selectedMode == LyricsSourceMode.SUPER_LYRIC_ONLY,
                onClick = onSelectSuperLyricOnly,
                labelStyle = btnLabelStyle,
                enabled = rootAvailable,
            )
            LyricsFontOptionButton(
                text = stringResource(R.string.music_lyrics_source_mixed),
                selected = selectedMode == LyricsSourceMode.MIXED,
                onClick = onSelectMixed,
                labelStyle = btnLabelStyle,
                enabled = rootAvailable,
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
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        enabled = enabled,
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

@Composable
private fun ToggleRowWithHint(
    label: String,
    checked: Boolean,
    hint: String,
    onChecked: (Boolean) -> Unit,
) {
    val hintColor = Color(ContextCompat.getColor(LocalContext.current, R.color.mi_text_secondary))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToggleRow(label = label, checked = checked, onChecked = onChecked)
        Text(
            text = hint,
            style = MiuixTheme.textStyles.footnote1,
            color = hintColor,
        )
    }
}

