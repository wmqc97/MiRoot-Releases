package com.wmqc.miroot.rear.desktop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.R
import com.wmqc.miroot.rear.AppProjectionOfficialGesturePolicy
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate
import com.wmqc.miroot.ui.apps.AppsFragment
import com.wmqc.miroot.ui.MiRootSecondaryToolbar
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import com.wmqc.miroot.ui.miRootPageHorizontalPadding
import com.wmqc.miroot.ui.miRootPageTopPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class RearDesktopSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureSafeWindowSize()
        applyMiRootSecondarySystemBars()
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                val materialScheme = remember(dark) {
                    val p = Color(ContextCompat.getColor(this, R.color.miuix_primary))
                    if (dark) m3DarkColorScheme(primary = p, secondary = p, tertiary = p)
                    else m3LightColorScheme(primary = p, secondary = p, tertiary = p)
                }
                MaterialTheme(colorScheme = materialScheme) {
                    RearDesktopSettingsScreen()
                }
            }
        }
    }

    private fun ensureSafeWindowSize() {
        val lp = window.attributes ?: return
        var changed = false
        if (lp.width == 0) { lp.width = WindowManager.LayoutParams.MATCH_PARENT; changed = true }
        if (lp.height == 0) { lp.height = WindowManager.LayoutParams.MATCH_PARENT; changed = true }
        if (changed) window.attributes = lp
    }
}

@Composable
private fun RearDesktopSettingsScreen() {
    val ctx = LocalContext.current
    val padH = miRootPageHorizontalPadding()
    val padTop = miRootPageTopPadding()
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardSurface = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))
    val accent = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))

    // State
    val currentMode = RearDesktopPrefs.listMode(ctx)
    var listMode by remember { mutableStateOf(currentMode) }
    var showBlacklist by remember { mutableStateOf(false) }

    val masterEnabled = OfficialSubscreenServiceGate.isDisableEnabled(ctx)
    val currentScope = AppProjectionOfficialGesturePolicy.getScope(ctx)
    var gestureScope by remember { mutableStateOf(currentScope) }

    fun persistMode(mode: RearDesktopListMode) {
        listMode = mode
        RearDesktopPrefs.setListMode(ctx, mode)
        RearDesktopPrefs.notifyPrefsChanged(ctx)
    }

    fun persistScope(scope: AppProjectionOfficialGesturePolicy.Scope) {
        gestureScope = scope
        AppProjectionOfficialGesturePolicy.setScope(ctx.applicationContext, scope)
    }

    val activity = ctx as? ComponentActivity
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        MiRootSecondaryToolbar(
            title = stringResource(R.string.rear_desktop_settings_title),
            onNavigateBack = { activity?.finish() },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = padH, top = padTop, end = padH, bottom = padH),
        ) {
            // ── Desktop Mode ──
            SectionHeader("桌面模式")
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.rear_desktop_editor_mode_title),
                fontSize = 12.sp,
                color = onSecondary,
            )

            Spacer(Modifier.height(10.dp))

            // Mode toggle chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(
                    text = stringResource(R.string.rear_desktop_mode_manual),
                    selected = listMode == RearDesktopListMode.MANUAL,
                    onClick = { persistMode(RearDesktopListMode.MANUAL) },
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                ModeChip(
                    text = stringResource(R.string.rear_desktop_mode_all_freq),
                    selected = listMode == RearDesktopListMode.ALL_BY_FREQUENCY,
                    onClick = { persistMode(RearDesktopListMode.ALL_BY_FREQUENCY) },
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Mode-specific hint
            Text(
                text = when (listMode) {
                    RearDesktopListMode.MANUAL -> stringResource(R.string.rear_desktop_settings_hint_apps_tab)
                    RearDesktopListMode.ALL_BY_FREQUENCY -> stringResource(R.string.rear_desktop_all_mode_hint)
                },
                fontSize = 12.sp,
                color = onSecondary.copy(alpha = 0.8f),
            )

            Spacer(Modifier.height(14.dp))

            // Actions
            Button(
                onClick = { RearDesktopLaunchHelper.requestOpenDesktop(ctx) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(stringResource(R.string.rear_desktop_open_on_rear_button)) }

            if (listMode == RearDesktopListMode.ALL_BY_FREQUENCY) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showBlacklist = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(stringResource(R.string.rear_desktop_blacklist_manage)) }
            }

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        ctx.startActivity(Intent(ctx, RearDesktopHoneycombTestActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(stringResource(R.string.rear_desktop_honeycomb_test_button)) }
            }

            Spacer(Modifier.height(24.dp))

            // ── Projection Gestures ──
            SectionHeader("官方手势范围")
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (masterEnabled) stringResource(R.string.apps_official_gesture_master_hint)
                else stringResource(R.string.apps_official_gesture_master_hint),
                fontSize = 12.sp,
                color = if (masterEnabled) onSecondary else onSecondary.copy(alpha = 0.5f),
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeChip(
                    text = stringResource(R.string.apps_official_gesture_scope_all),
                    selected = gestureScope == AppProjectionOfficialGesturePolicy.Scope.ALL,
                    enabled = masterEnabled,
                    onClick = { persistScope(AppProjectionOfficialGesturePolicy.Scope.ALL) },
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                ScopeChip(
                    text = stringResource(R.string.apps_official_gesture_scope_selected),
                    selected = gestureScope == AppProjectionOfficialGesturePolicy.Scope.SELECTED,
                    enabled = masterEnabled,
                    onClick = { persistScope(AppProjectionOfficialGesturePolicy.Scope.SELECTED) },
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
            }

            if (masterEnabled && gestureScope == AppProjectionOfficialGesturePolicy.Scope.SELECTED) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.apps_official_gesture_selected_hint),
                    fontSize = 12.sp,
                    color = onSecondary.copy(alpha = 0.8f),
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        // Blacklist dialog
        if (showBlacklist) {
            BlacklistDialog(
                onDismiss = { showBlacklist = false },
                onSave = { bl ->
                    RearDesktopPrefs.setBlacklist(ctx, bl)
                    RearDesktopPrefs.notifyPrefsChanged(ctx)
                    showBlacklist = false
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val ctx = LocalContext.current
    val accent = Color(ContextCompat.getColor(ctx, R.color.miuix_primary))
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = accent,
    )
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val surface = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))
    val onPrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else surface)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else onSecondary,
        )
    }
}

@Composable
private fun ScopeChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val surface = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))
    val onSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else surface)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) {
                if (selected) accent else onSecondary
            } else {
                onSecondary.copy(alpha = 0.4f)
            },
        )
    }
}

@Composable
private fun BlacklistDialog(
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val selfPkg = ctx.applicationContext.packageName
    val rows = remember { AppsFragment.queryLauncherApps(ctx.packageManager, selfPkg) }
    val initial = remember { RearDesktopPrefs.blacklist(ctx) }
    var selected by remember { mutableStateOf(initial.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rear_desktop_blacklist_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { row ->
                    val checked = row.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selected = if (checked) selected - row.packageName else selected + row.packageName
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✓", fontSize = 14.sp, color = if (checked) Color(0xFF4CAF50) else Color.Transparent, modifier = Modifier.width(22.dp))
                        Text(row.label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(text = stringResource(R.string.rear_desktop_save), onClick = { onSave(selected) })
        },
        dismissButton = {
            TextButton(text = "取消", onClick = onDismiss)
        },
    )
}
