package com.wmqc.miroot.rear.truthdare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.wmqc.miroot.R
import com.wmqc.miroot.display.MainDisplayUi
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

private const val SEARCH_RESULT_LIMIT = 100

@Composable
fun TruthDareBankManageSection(
    onPrimary: Color,
    onSecondary: Color,
    customItems: MutableList<TruthDareBankItem>,
    hiddenBuiltinIds: Set<String>,
    onHideBuiltin: (Collection<String>) -> Unit,
    onPersistCustom: () -> Unit,
    onRequestImport: () -> Unit,
    onExportTxt: (String) -> Unit,
    onShowError: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var filtersExpanded by remember { mutableStateOf(false) }
    var addExpanded by remember { mutableStateOf(false) }

    var filterSource by remember { mutableStateOf<TruthDareBankSource?>(null) }
    var filterType by remember { mutableStateOf<TruthDareType?>(null) }
    var filterDifficulty by remember { mutableStateOf<TruthDareDifficulty?>(null) }
    var filterTheme by remember { mutableStateOf<TruthDareTheme?>(null) }

    var editItem by remember { mutableStateOf<TruthDareBankItem?>(null) }
    var confirmDeleteSearch by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    var newContent by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(TruthDareType.TRUTH) }
    var newTheme by remember { mutableStateOf(TruthDareTheme.EMOTION) }
    var newDifficulty by remember { mutableStateOf(TruthDareDifficulty.STANDARD) }

    val builtinItems =
        remember(hiddenBuiltinIds, customItems.size) {
            TruthDareBank.loadBuiltinBankItems(ctx, hiddenBuiltinIds)
        }
    val displayPool =
        remember(builtinItems, customItems.size, filterSource) {
            when (filterSource) {
                TruthDareBankSource.BUILTIN -> builtinItems
                TruthDareBankSource.CUSTOM -> customItems.toList()
                null -> builtinItems + customItems
            }
        }
    val filter =
        TruthDareBankFilter(
            type = filterType,
            difficulty = filterDifficulty,
            theme = filterTheme,
        )
    val filtered =
        remember(displayPool, filterType, filterDifficulty, filterTheme) {
            TruthDareUserBank.filter(displayPool, filter)
        }
    val searchResults =
        remember(filtered, searchQuery) {
            TruthDareUserBank.search(filtered, searchQuery).take(SEARCH_RESULT_LIMIT)
        }
    val totalCount = builtinItems.size + customItems.size
    val hasSearch = searchQuery.trim().isNotEmpty()

    SectionTitle(stringResource(R.string.truth_dare_bank_manage), onPrimary)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.truth_dare_bank_manage_hint), color = onSecondary, fontSize = 11.sp, lineHeight = 15.sp)
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.truth_dare_bank_count_fmt, totalCount, filtered.size),
        color = onSecondary,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(8.dp))
    ExpandableSectionBar(
        title =
            stringResource(
                if (filtersExpanded) R.string.truth_dare_filter_collapse else R.string.truth_dare_filter_expand,
            ),
        expanded = filtersExpanded,
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        onClick = { filtersExpanded = !filtersExpanded },
    )

    if (filtersExpanded) {
        Spacer(Modifier.height(4.dp))
        FilterChipRow(
            stringResource(R.string.truth_dare_filter_source),
            listOf(null to stringResource(R.string.truth_dare_filter_all)) +
                TruthDareBankSource.entries.map {
                    it to
                        when (it) {
                            TruthDareBankSource.BUILTIN -> stringResource(R.string.truth_dare_bank_builtin)
                            TruthDareBankSource.CUSTOM -> stringResource(R.string.truth_dare_bank_custom)
                        }
                },
            filterSource,
            onPrimary,
            onSecondary,
        ) { filterSource = it }
        Spacer(Modifier.height(6.dp))
        FilterChipRow(
            stringResource(R.string.truth_dare_question_type),
            listOf(null to stringResource(R.string.truth_dare_filter_all)) +
                TruthDareType.entries.map { it to it.displayLabel() },
            filterType,
            onPrimary,
            onSecondary,
        ) { filterType = it }
        Spacer(Modifier.height(6.dp))
        FilterChipRow(
            stringResource(R.string.truth_dare_difficulty),
            listOf(null to stringResource(R.string.truth_dare_filter_all)) +
                TruthDareDifficulty.bankDifficulties.map { it to it.displayLabel() },
            filterDifficulty,
            onPrimary,
            onSecondary,
        ) { filterDifficulty = it }
        Spacer(Modifier.height(6.dp))
        FilterChipRow(
            stringResource(R.string.truth_dare_theme),
            listOf(null to stringResource(R.string.truth_dare_filter_all)) +
                TruthDareTheme.bankThemes.map { it to it.displayLabel() },
            filterTheme,
            onPrimary,
            onSecondary,
        ) { filterTheme = it }
    }

    Spacer(Modifier.height(10.dp))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_bank_search_hint)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text(stringResource(R.string.truth_dare_cancel), fontSize = 12.sp)
                    }
                }
            },
        )
        when {
            !hasSearch -> {
                Text(
                    stringResource(R.string.truth_dare_bank_search_prompt),
                    color = onSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0x0A000000), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                )
            }
            searchResults.isEmpty() -> {
                Text(stringResource(R.string.truth_dare_bank_search_no_match), color = onSecondary, fontSize = 12.sp)
            }
            else -> {
                Text(
                    stringResource(R.string.truth_dare_bank_search_match_fmt, searchResults.size, filtered.size),
                    color = onSecondary,
                    fontSize = 11.sp,
                )
                searchResults.forEach { item ->
                    BankItemRow(
                        item = item,
                        onPrimary = onPrimary,
                        onSecondary = onSecondary,
                        onEdit = { editItem = item },
                        onRemove = {
                            if (item.isBuiltin) {
                                onHideBuiltin(listOf(item.id))
                            } else {
                                val index = customItems.indexOfFirst { it.id == item.id }
                                if (index >= 0) {
                                    customItems.removeAt(index)
                                    onPersistCustom()
                                }
                            }
                        },
                    )
                }
                Button(
                    onClick = { confirmDeleteSearch = true },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.truth_dare_delete_search_matches) + " (${searchResults.size})",
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    ExpandableSectionBar(
        title =
            stringResource(
                if (addExpanded) R.string.truth_dare_add_single_collapse else R.string.truth_dare_add_single_expand,
            ),
        expanded = addExpanded,
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        onClick = { addExpanded = !addExpanded },
    )

    if (addExpanded) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newContent,
            onValueChange = { newContent = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_custom_hint)) },
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        EnumChipRow(
            stringResource(R.string.truth_dare_question_type),
            TruthDareType.entries,
            newType,
            { it.displayLabel() },
            onPrimary,
            onSecondary,
        ) { newType = it }
        Spacer(Modifier.height(6.dp))
        EnumChipRow(
            stringResource(R.string.truth_dare_difficulty),
            TruthDareDifficulty.bankDifficulties,
            newDifficulty,
            { it.displayLabel() },
            onPrimary,
            onSecondary,
        ) { newDifficulty = it }
        Spacer(Modifier.height(6.dp))
        EnumChipRow(
            stringResource(R.string.truth_dare_theme),
            TruthDareTheme.bankThemes,
            newTheme,
            { it.displayLabel() },
            onPrimary,
            onSecondary,
        ) { newTheme = it }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val content = newContent.trim()
                if (content.isEmpty()) {
                    onShowError(ctx.getString(R.string.truth_dare_error_empty_content))
                    return@Button
                }
                if (customItems.any { it.content.trim() == content }) {
                    onShowError(ctx.getString(R.string.truth_dare_error_duplicate))
                    return@Button
                }
                customItems.add(
                    TruthDareBankItem(
                        id = "bank_${System.currentTimeMillis()}",
                        type = newType,
                        content = content,
                        theme = newTheme,
                        difficulty = newDifficulty,
                    ),
                )
                newContent = ""
                onPersistCustom()
            },
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.truth_dare_add_question)) }
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { confirmDeleteAll = true },
        colors = ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.truth_dare_delete_all), fontSize = 12.sp) }

    Spacer(Modifier.height(8.dp))
    ImportFormatCopyBlock(onPrimary = onPrimary, onSecondary = onSecondary)

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            copyTextToClipboard(
                ctx = ctx,
                text = TruthDareTxtCodec.aiGenerationExampleText(),
                toastRes = R.string.truth_dare_import_ai_example_copied,
            )
        },
        colors = ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.truth_dare_import_ai_example_button), fontSize = 12.sp) }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onRequestImport, colors = ButtonDefaults.buttonColors(), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.truth_dare_import_txt), fontSize = 12.sp)
        }
        Button(
            onClick = { onExportTxt(TruthDareTxtCodec.export(customItems.filter(filter::matches))) },
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.truth_dare_export_txt), fontSize = 12.sp)
        }
    }

    editItem?.let { item ->
        EditItemDialog(
            item = item,
            onDismiss = { editItem = null },
            onSave = { updated ->
                val idx = customItems.indexOfFirst { it.id == item.id }
                if (idx >= 0) {
                    if (customItems.any { it.id != item.id && it.content.trim() == updated.content.trim() }) {
                        onShowError(ctx.getString(R.string.truth_dare_error_duplicate))
                        return@EditItemDialog
                    }
                    customItems[idx] = updated
                    onPersistCustom()
                }
                editItem = null
            },
        )
    }

    if (confirmDeleteSearch) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSearch = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_delete_search_matches)) },
            text = {
                androidx.compose.material3.Text(
                    stringResource(R.string.truth_dare_delete_search_confirm, searchResults.size),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val builtinIds = searchResults.filter { it.isBuiltin }.map { it.id }
                        val customIds = searchResults.filterNot { it.isBuiltin }.map { it.id }.toSet()
                        if (builtinIds.isNotEmpty()) onHideBuiltin(builtinIds)
                        if (customIds.isNotEmpty()) {
                            customItems.removeAll { it.id in customIds }
                            onPersistCustom()
                        }
                        confirmDeleteSearch = false
                    },
                ) { androidx.compose.material3.Text(stringResource(R.string.truth_dare_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSearch = false }) {
                    androidx.compose.material3.Text(stringResource(R.string.truth_dare_cancel))
                }
            },
        )
    }

    if (confirmDeleteAll) {
        val deleteAllMessage =
            when (filterSource) {
                TruthDareBankSource.BUILTIN -> stringResource(R.string.truth_dare_delete_all_builtin_confirm)
                TruthDareBankSource.CUSTOM -> stringResource(R.string.truth_dare_delete_all_custom_confirm)
                null -> stringResource(R.string.truth_dare_delete_all_confirm)
            }
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_delete_all)) },
            text = { androidx.compose.material3.Text(deleteAllMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (filterSource) {
                            TruthDareBankSource.BUILTIN -> onHideBuiltin(builtinItems.map { it.id })
                            TruthDareBankSource.CUSTOM -> {
                                customItems.clear()
                                onPersistCustom()
                            }
                            null -> {
                                onHideBuiltin(TruthDareBank.loadBuiltinBankItems(ctx, hiddenIds = emptySet()).map { it.id })
                                customItems.clear()
                                onPersistCustom()
                            }
                        }
                        confirmDeleteAll = false
                    },
                ) { androidx.compose.material3.Text(stringResource(R.string.truth_dare_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    androidx.compose.material3.Text(stringResource(R.string.truth_dare_cancel))
                }
            },
        )
    }
}

private fun copyTextToClipboard(
    ctx: Context,
    text: String,
    @StringRes toastRes: Int,
) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("truth_dare", text))
    MainDisplayUi.showToast(ctx, toastRes, Toast.LENGTH_SHORT)
}

@Composable
private fun SectionTitle(title: String, color: Color) {
    Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ImportFormatCopyBlock(
    onPrimary: Color,
    onSecondary: Color,
) {
    val ctx = LocalContext.current
    val exampleText = remember { TruthDareTxtCodec.exampleText() }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    copyTextToClipboard(ctx, exampleText, R.string.truth_dare_import_format_copied)
                }
                .background(onPrimary.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.truth_dare_import_format_title), color = onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.truth_dare_custom_bank_hint),
            color = onSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Text(
            text = exampleText,
            color = onPrimary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 13.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x14000000), RoundedCornerShape(8.dp))
                    .padding(8.dp),
        )
        Text(stringResource(R.string.truth_dare_import_format_tap_copy), color = onSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun ExpandableSectionBar(
    title: String,
    expanded: Boolean,
    onPrimary: Color,
    onSecondary: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(onPrimary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            text = if (expanded) "▲" else "▼",
            color = onSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BankItemRow(
    item: TruthDareBankItem,
    onPrimary: Color,
    onSecondary: Color,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(Color(0x08000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(item.metaLabel(), color = onSecondary, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(item.content, color = onPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!item.isBuiltin) {
                Button(onClick = onEdit, colors = ButtonDefaults.buttonColors()) {
                    Text(stringResource(R.string.truth_dare_edit_question), fontSize = 11.sp)
                }
            }
            Button(onClick = onRemove, colors = ButtonDefaults.buttonColors()) {
                Text(stringResource(R.string.truth_dare_remove_question), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EditItemDialog(
    item: TruthDareBankItem,
    onDismiss: () -> Unit,
    onSave: (TruthDareBankItem) -> Unit,
) {
    var content by remember(item.id) { mutableStateOf(item.content) }
    var type by remember(item.id) { mutableStateOf(item.type) }
    var theme by remember(item.id) { mutableStateOf(item.theme) }
    var difficulty by remember(item.id) { mutableStateOf(item.difficulty) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_edit_question)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { androidx.compose.material3.Text(stringResource(R.string.truth_dare_custom_hint)) },
                    minLines = 2,
                )
                EnumChipRow(
                    stringResource(R.string.truth_dare_question_type),
                    TruthDareType.entries,
                    type,
                    { it.displayLabel() },
                    Color.Black,
                    Color.Gray,
                ) { type = it }
                EnumChipRow(
                    stringResource(R.string.truth_dare_difficulty),
                    TruthDareDifficulty.bankDifficulties,
                    difficulty,
                    { it.displayLabel() },
                    Color.Black,
                    Color.Gray,
                ) { difficulty = it }
                EnumChipRow(
                    stringResource(R.string.truth_dare_theme),
                    TruthDareTheme.bankThemes,
                    theme,
                    { it.displayLabel() },
                    Color.Black,
                    Color.Gray,
                ) { theme = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = content.trim()
                    if (trimmed.isEmpty()) return@TextButton
                    onSave(item.copy(content = trimmed, type = type, theme = theme, difficulty = difficulty))
                },
            ) { androidx.compose.material3.Text(stringResource(R.string.truth_dare_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(stringResource(R.string.truth_dare_cancel))
            }
        },
    )
}

@Composable
private fun <T> FilterChipRow(
    label: String,
    options: List<Pair<T?, String>>,
    selected: T?,
    onPrimary: Color,
    onSecondary: Color,
    onSelect: (T?) -> Unit,
) {
    Column {
        Text(label, color = onSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                val picked = selected == value
                OptionChip(text = text, picked = picked, onPrimary = onPrimary, onSecondary = onSecondary) {
                    onSelect(value)
                }
            }
        }
    }
}

@Composable
private fun <T> EnumChipRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onPrimary: Color,
    onSecondary: Color,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, color = onSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { opt ->
                val picked = opt == selected
                OptionChip(text = labelOf(opt), picked = picked, onPrimary = onPrimary, onSecondary = onSecondary) {
                    onSelect(opt)
                }
            }
        }
    }
}

@Composable
private fun OptionChip(
    text: String,
    picked: Boolean,
    onPrimary: Color,
    onSecondary: Color,
    onClick: () -> Unit,
) {
    val bg = if (picked) onPrimary.copy(alpha = 0.12f) else Color(0x08000000)
    val fg = if (picked) onPrimary else onSecondary
    Text(
        text = if (picked) "✓ $text" else text,
        color = fg,
        fontSize = 11.sp,
        modifier =
            Modifier
                .background(bg, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
