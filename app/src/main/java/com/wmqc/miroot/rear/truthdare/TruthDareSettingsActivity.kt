package com.wmqc.miroot.rear.truthdare

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import com.wmqc.miroot.display.MainDisplayUi
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import com.wmqc.miroot.ui.miRootPageHorizontalPadding
import com.wmqc.miroot.ui.miRootPageTopPadding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class TruthDareSettingsActivity : ComponentActivity() {

    private val reloadNonce = mutableIntStateOf(0)
    private var importReplace = false
    private var pendingExportText = ""

    private val pickTxtLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = readTextFromUri(uri)
            if (text == null) {
                showImportError(getString(R.string.truth_dare_error_read_file))
                return@registerForActivityResult
            }
            handleTxtImport(text, importReplace)
        }

    private val createTxtLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = writeTextToUri(uri, pendingExportText)
            if (!ok) {
                MainDisplayUi.showToast(this, R.string.truth_dare_error_write_file, Toast.LENGTH_SHORT)
            } else {
                MainDisplayUi.showToast(this, R.string.truth_dare_export_ok, Toast.LENGTH_SHORT)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureSafeWindowSize()
        applyMiRootSecondarySystemBars()
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                val materialScheme =
                    remember(dark) {
                        val p = Color(ContextCompat.getColor(this, R.color.miuix_primary))
                        if (dark) m3DarkColorScheme(primary = p, secondary = p, tertiary = p)
                        else m3LightColorScheme(primary = p, secondary = p, tertiary = p)
                    }
                MaterialTheme(colorScheme = materialScheme) {
                    TruthDareSettingsScreen(
                        reloadKey = reloadNonce.intValue,
                        onLaunchImport = { replace ->
                            importReplace = replace
                            pickTxtLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
                        },
                        onLaunchExport = { text ->
                            if (text.isBlank()) {
                                MainDisplayUi.showToast(
                                    this@TruthDareSettingsActivity,
                                    R.string.truth_dare_export_empty,
                                    Toast.LENGTH_SHORT,
                                )
                                return@TruthDareSettingsScreen
                            }
                            pendingExportText = text
                            createTxtLauncher.launch("truth_dare_bank.txt")
                        },
                    )
                }
            }
        }
    }

    private fun handleTxtImport(text: String, replace: Boolean) {
        val result = TruthDareTxtCodec.parse(text)
        if (result.error != null) {
            showImportError(result.error)
            return
        }
        val existing = TruthDareUserBank.load(applicationContext)
        val merged =
            if (replace) {
                result.items to 0
            } else {
                TruthDareUserBank.mergeDedupe(existing, result.items)
            }
        TruthDareUserBank.save(applicationContext, merged.first)
        reloadNonce.intValue++
        MainDisplayUi.showToast(
            this,
            getString(
                R.string.truth_dare_import_txt_done,
                result.items.size,
                merged.second,
            ),
            Toast.LENGTH_LONG,
        )
    }

    private fun showImportError(message: String) {
        MainDisplayUi.showToast(this, message, Toast.LENGTH_LONG)
    }

    private fun readTextFromUri(uri: Uri): String? =
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

    private fun writeTextToUri(uri: Uri, text: String): Boolean =
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)

    private fun ensureSafeWindowSize() {
        val lp = window.attributes ?: return
        var changed = false
        if (lp.width == 0) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (lp.height == 0) {
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (changed) window.attributes = lp
    }
}

@Composable
private fun TruthDareSettingsScreen(
    reloadKey: Int,
    onLaunchImport: (replace: Boolean) -> Unit,
    onLaunchExport: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val padH = miRootPageHorizontalPadding()
    val padTop = miRootPageTopPadding()
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardSurface = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))

    var config by remember { mutableStateOf(TruthDarePrefs.readConfig(ctx)) }
    val customItems =
        remember(reloadKey) {
            mutableStateListOf<TruthDareBankItem>().apply {
                addAll(TruthDareUserBank.load(ctx))
            }
        }
    var hiddenBuiltinIds by remember(reloadKey) { mutableStateOf(TruthDareHiddenBuiltin.load(ctx).toSet()) }
    var errorDialog by remember { mutableStateOf<String?>(null) }
    var importModeDialog by remember { mutableStateOf(false) }

    fun persistCustomBank() {
        TruthDareUserBank.save(ctx, customItems.toList())
    }

    fun hideBuiltinQuestions(ids: Collection<String>) {
        if (ids.isEmpty()) return
        hiddenBuiltinIds = hiddenBuiltinIds + ids
        TruthDareHiddenBuiltin.save(ctx, hiddenBuiltinIds)
    }

    fun saveAll() {
        TruthDarePrefs.writeConfig(ctx, config)
        persistCustomBank()
        TruthDareHiddenBuiltin.save(ctx, hiddenBuiltinIds)
    }

    errorDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorDialog = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_error_title)) },
            text = { androidx.compose.material3.Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorDialog = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.truth_dare_confirm))
                }
            },
        )
    }

    if (importModeDialog) {
        AlertDialog(
            onDismissRequest = { importModeDialog = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_import_txt)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_import_mode_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        importModeDialog = false
                        onLaunchImport(false)
                    },
                ) { androidx.compose.material3.Text(stringResource(R.string.truth_dare_batch_append)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importModeDialog = false
                        onLaunchImport(true)
                    },
                ) { androidx.compose.material3.Text(stringResource(R.string.truth_dare_batch_replace)) }
            },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(pageBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = padH, top = padTop, end = padH, bottom = padH),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.truth_dare_settings_title),
                color = onPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.truth_dare_settings_intro),
                color = onSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            SettingsCard(cardSurface) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionTitle(stringResource(R.string.truth_dare_settings_players), onPrimary)
                    Text(
                        stringResource(R.string.truth_dare_settings_players_count, config.players.size),
                        color = onSecondary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                config.players.forEachIndexed { index, name ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = { v ->
                            val list = config.players.toMutableList()
                            list[index] = v
                            config = config.copy(players = list)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { androidx.compose.material3.Text("玩家 ${index + 1}") },
                    )
                    if (index < config.players.lastIndex) Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (config.players.size < 10) {
                                config = config.copy(players = config.players + "玩家${config.players.size + 1}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.truth_dare_add_player), fontSize = 12.sp) }
                    Button(
                        onClick = {
                            if (config.players.size > 2) {
                                config = config.copy(players = config.players.dropLast(1))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.truth_dare_remove_player), fontSize = 12.sp) }
                }
            }

            SettingsCard(cardSurface) {
                SectionTitle(stringResource(R.string.truth_dare_settings_game), onPrimary)
                Spacer(Modifier.height(12.dp))
                SettingOptionRow(
                    label = stringResource(R.string.rear_truth_dare_pick_player_mode),
                    onSecondary = onSecondary,
                ) {
                    ScrollOptionChips(
                        options = TruthDarePlayerPickMode.entries,
                        selected = config.playerPickMode,
                        labelOf = {
                            when (it) {
                                TruthDarePlayerPickMode.WHEEL -> "转盘"
                                TruthDarePlayerPickMode.ROUND_ROBIN -> "轮询"
                                TruthDarePlayerPickMode.RANDOM -> "随机"
                            }
                        },
                        onPrimary = onPrimary,
                        onSecondary = onSecondary,
                        onSelect = { config = config.copy(playerPickMode = it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingOptionRow(
                    label = stringResource(R.string.rear_truth_dare_challenge_mode),
                    onSecondary = onSecondary,
                ) {
                    ScrollOptionChips(
                        options = TruthDareChallengeMode.entries,
                        selected = config.challengeMode,
                        labelOf = {
                            when (it) {
                                TruthDareChallengeMode.WHEEL -> "转盘"
                                TruthDareChallengeMode.PLAYER_CHOICE -> "自选"
                            }
                        },
                        onPrimary = onPrimary,
                        onSecondary = onSecondary,
                        onSelect = { config = config.copy(challengeMode = it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingOptionRow(label = stringResource(R.string.truth_dare_difficulty), onSecondary = onSecondary) {
                    ScrollOptionChips(
                        options = TruthDareDifficulty.entries,
                        selected = config.difficulty,
                        labelOf = { it.displayLabel() },
                        onPrimary = onPrimary,
                        onSecondary = onSecondary,
                        onSelect = { config = config.copy(difficulty = it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SettingOptionRow(label = stringResource(R.string.truth_dare_theme), onSecondary = onSecondary) {
                    ScrollOptionChips(
                        options = TruthDareTheme.entries,
                        selected = config.theme,
                        labelOf = { it.displayLabel() },
                        onPrimary = onPrimary,
                        onSecondary = onSecondary,
                        onSelect = { config = config.copy(theme = it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SpeakQuestionsToggleRow(
                    checked = config.speakQuestions,
                    onPrimary = onPrimary,
                    onSecondary = onSecondary,
                    onChecked = { config = config.copy(speakQuestions = it) },
                )
            }

            SettingsCard(cardSurface) {
                TruthDareBankManageSection(
                    onPrimary = onPrimary,
                    onSecondary = onSecondary,
                    customItems = customItems,
                    hiddenBuiltinIds = hiddenBuiltinIds,
                    onHideBuiltin = { hideBuiltinQuestions(it) },
                    onPersistCustom = { persistCustomBank() },
                    onRequestImport = { importModeDialog = true },
                    onExportTxt = onLaunchExport,
                    onShowError = { errorDialog = it },
                )
            }

            Button(
                onClick = {
                    saveAll()
                    RearTruthDareWheelLaunchHelper.requestOpenTruthDareWheel(ctx)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) { Text(stringResource(R.string.truth_dare_open_on_rear)) }

            Button(
                onClick = { saveAll() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) { Text(stringResource(R.string.truth_dare_save)) }
        }
    }
}

@Composable
private fun SettingsCard(surface: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardColors(color = surface, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: String, color: Color) {
    Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingOptionRow(
    label: String,
    onSecondary: Color,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = onSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun <T> ScrollOptionChips(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onPrimary: Color,
    onSecondary: Color,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { opt ->
            val picked = opt == selected
            val bg = if (picked) onPrimary.copy(alpha = 0.12f) else Color(0x08000000)
            val fg = if (picked) onPrimary else onSecondary
            Text(
                text = if (picked) "✓ ${labelOf(opt)}" else labelOf(opt),
                color = fg,
                fontSize = 12.sp,
                modifier =
                    Modifier
                        .background(bg, RoundedCornerShape(16.dp))
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SpeakQuestionsToggleRow(
    checked: Boolean,
    onPrimary: Color,
    onSecondary: Color,
    onChecked: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.truth_dare_speak_questions), color = onPrimary, fontSize = 13.sp)
            Switch(checked = checked, onCheckedChange = onChecked)
        }
        Text(
            stringResource(R.string.truth_dare_speak_questions_hint),
            color = onSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}
