package com.wmqc.miroot.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.miRootPageTitleTextUnit
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 车辆数据历史二级页的四个界面。 */
private data class VehicleHistoryInitialLoad(
    val page: List<VehicleHistoryDatabase.VehicleDataRecord>,
    val totalCount: Int,
    val ecuAvgFuel: String,
    val fuelInsights: FuelTankAnalytics.FuelHistoryInsights,
    val monthlySummaries: List<VehicleHistoryFuelCache.MonthlyFuelSummary>,
)

private enum class VehicleHistoryPage(val index: Int) {
    HISTORY(0),
    OPERATE(1),
    WARNING(2),
    SETTINGS(3),
    ;

    companion object {
        fun fromIndex(index: Int): VehicleHistoryPage =
            entries.getOrElse(index) { HISTORY }
    }
}

/** 车辆数据历史二级页：历史数据 / 操作记录 / 警告记录 / 其他设置。 */
@Composable
fun VehicleHistoryScreen(
    reloadKey: Int,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onClearConfirmed: () -> Unit,
) {
    val colors = carColors()
    val ctx = LocalContext.current
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    var page by remember { mutableStateOf(VehicleHistoryPage.HISTORY) }
    var visitedPages by remember { mutableStateOf(setOf(VehicleHistoryPage.HISTORY)) }
    var confirmClear by remember { mutableStateOf(false) }
    var listEpoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        listEpoch++
        if (reloadKey > 0) {
            page = VehicleHistoryPage.HISTORY
            visitedPages = visitedPages + VehicleHistoryPage.HISTORY
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { MaterialText(stringResource(R.string.car_control_vehicle_history_clear_title)) },
            text = { MaterialText(stringResource(R.string.car_control_vehicle_history_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearConfirmed()
                    listEpoch++
                }) { MaterialText(stringResource(R.string.car_control_vehicle_history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    MaterialText(stringResource(R.string.car_control_vehicle_history_close))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HistoryScreenTopBar(
            onBack = onBack,
            colors = colors,
            horizontalPadding = scrollPad,
        )
        VehicleHistoryTabRow(
            page = page,
            onPageChange = { next ->
                page = next
                visitedPages = visitedPages + next
            },
            colors = colors,
            horizontalPadding = scrollPad,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VehicleHistoryKeepAlivePage(
                visible = page == VehicleHistoryPage.HISTORY,
                visited = VehicleHistoryPage.HISTORY in visitedPages,
            ) {
                VehicleHistoryDataPage(
                    listEpoch = listEpoch,
                    colors = colors,
                    active = page == VehicleHistoryPage.HISTORY,
                )
            }
            VehicleHistoryKeepAlivePage(
                visible = page == VehicleHistoryPage.OPERATE,
                visited = VehicleHistoryPage.OPERATE in visitedPages,
            ) {
                VehicleHistoryOperatePage(
                    listEpoch = listEpoch,
                    colors = colors,
                    active = page == VehicleHistoryPage.OPERATE,
                )
            }
            VehicleHistoryKeepAlivePage(
                visible = page == VehicleHistoryPage.WARNING,
                visited = VehicleHistoryPage.WARNING in visitedPages,
            ) {
                VehicleHistoryWarningPage(
                    listEpoch = listEpoch,
                    colors = colors,
                    active = page == VehicleHistoryPage.WARNING,
                )
            }
            VehicleHistoryKeepAlivePage(
                visible = page == VehicleHistoryPage.SETTINGS,
                visited = VehicleHistoryPage.SETTINGS in visitedPages,
            ) {
                VehicleHistorySettingsPage(
                    listEpoch = listEpoch,
                    onImport = onImport,
                    onExport = onExport,
                    onClear = { confirmClear = true },
                    onFuelPrefsChanged = { listEpoch++ },
                    colors = colors,
                )
            }
        }
    }
}

/** 切换 Tab 时保留各界面状态（列表位置、已加载分页等）。 */
@Composable
private fun VehicleHistoryKeepAlivePage(
    visible: Boolean,
    visited: Boolean,
    content: @Composable () -> Unit,
) {
    if (!visited) return
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer { alpha = if (visible) 1f else 0f },
    ) {
        content()
    }
}

@Composable
private fun HistoryScreenTopBar(
    onBack: () -> Unit,
    colors: CarColorPalette,
    horizontalPadding: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("←", fontSize = 22.sp, color = colors.accentBlue)
        }
        Text(
            stringResource(R.string.car_control_vehicle_history_title),
            fontSize = miRootPageTitleTextUnit(),
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VehicleHistoryTabRow(
    page: VehicleHistoryPage,
    onPageChange: (VehicleHistoryPage) -> Unit,
    colors: CarColorPalette,
    horizontalPadding: Dp,
) {
    val labels = listOf(
        stringResource(R.string.car_control_vehicle_page_tab_history),
        stringResource(R.string.car_control_vehicle_page_tab_operate),
        stringResource(R.string.car_control_vehicle_page_tab_warning),
        stringResource(R.string.car_control_vehicle_page_tab_settings),
    )
    ScrollableTabRow(
        selectedTabIndex = page.index,
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.bgCard,
        contentColor = colors.accentBlue,
        edgePadding = horizontalPadding,
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = page.index == index,
                onClick = { onPageChange(VehicleHistoryPage.fromIndex(index)) },
                text = { MaterialText(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** 界面一：历史数据（分页列表 + 详情）。 */
@Composable
private fun VehicleHistoryDataPage(
    listEpoch: Int,
    colors: CarColorPalette,
    active: Boolean,
) {
    val modifier = Modifier.fillMaxSize()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSize = VehicleHistoryDatabase.PAGE_SIZE
    var rows by remember(listEpoch) { mutableStateOf<List<VehicleHistoryDatabase.VehicleDataRecord>>(emptyList()) }
    var offset by remember(listEpoch) { mutableIntStateOf(0) }
    var hasMore by remember(listEpoch) { mutableStateOf(true) }
    var loadingInitial by remember(listEpoch) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VehicleHistoryDatabase.VehicleDataRecord?>(null) }
    val listState = rememberLazyListState()
    var totalCount by remember(listEpoch) { mutableIntStateOf(0) }
    var ecuAvgFuel by remember(listEpoch) { mutableStateOf("—") }
    var monthlySummaries by remember(listEpoch) {
        mutableStateOf<List<VehicleHistoryFuelCache.MonthlyFuelSummary>>(emptyList())
    }
    var fuelAnalyticsRefreshing by remember(listEpoch) { mutableStateOf(false) }
    var fuelInsights by remember(listEpoch) {
        mutableStateOf(
            FuelTankAnalytics.FuelHistoryInsights(
                lastTankLitersPer100Km = null,
                lastTankDistanceKm = null,
                lastTankFuelLiters = null,
                lastTankCostYuan = null,
                refuelRecordIds = emptySet(),
                recordBadges = emptyMap(),
                refuelCount = 0,
                monthlySummaries = emptyList(),
            ),
        )
    }

    fun applyLoaded(loaded: VehicleHistoryInitialLoad) {
        rows = loaded.page
        totalCount = loaded.totalCount
        ecuAvgFuel = loaded.ecuAvgFuel
        fuelInsights = loaded.fuelInsights
        monthlySummaries = loaded.monthlySummaries
        offset = loaded.page.size
        hasMore = loaded.page.size >= pageSize
        selected = null
    }

    fun loadInitial() {
        scope.launch {
            loadingInitial = true
            try {
                val fast = withContext(Dispatchers.IO) {
                    VehicleDataHistoryStore.loadHistoryFast(ctx, pageSize)
                }
                applyLoaded(
                    VehicleHistoryInitialLoad(
                        page = fast.page,
                        totalCount = fast.totalCount,
                        ecuAvgFuel = fast.ecuAvgFuel,
                        fuelInsights = fast.fuelInsights,
                        monthlySummaries = fast.monthlySummaries,
                    ),
                )
            } catch (_: Exception) {
                rows = emptyList()
                totalCount = 0
                hasMore = false
            } finally {
                loadingInitial = false
            }
            if (VehicleDataHistoryStore.needsFuelAnalyticsRefresh(ctx)) {
                fuelAnalyticsRefreshing = true
                try {
                    val fresh = withContext(Dispatchers.IO) {
                        VehicleDataHistoryStore.refreshFuelHistoryInsights(ctx)
                    }
                    fuelInsights = fresh
                    monthlySummaries = fresh.monthlySummaries
                    ecuAvgFuel = VehicleDataHistoryStore.resolveEcuAvgFuelDisplay(ctx)
                } catch (_: Exception) {
                } finally {
                    fuelAnalyticsRefreshing = false
                }
            }
        }
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loadingInitial) return
        scope.launch {
            loadingMore = true
            try {
                val page = withContext(Dispatchers.IO) {
                    VehicleDataHistoryStore.loadVehicleDataRecordsPage(ctx, offset, pageSize)
                }
                if (page.isNotEmpty()) {
                    rows = rows + page
                    offset += page.size
                    hasMore = page.size >= pageSize
                } else {
                    hasMore = false
                }
            } finally {
                loadingMore = false
            }
        }
    }

    LaunchedEffect(listEpoch) {
        loadInitial()
    }

    LaunchedEffect(listState, listEpoch, rows.size, hasMore, loadingMore, loadingInitial, active) {
        if (!active || !hasMore || loadingMore || loadingInitial) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 4) {
                loadMore()
            }
        }
    }

    if (selected != null) {
        VehicleRecordDetailPane(
            record = selected!!,
            fuelBadge = fuelInsights.recordBadges[selected!!.id],
            colors = colors,
            onBack = { selected = null },
            modifier = modifier.padding(horizontal = 12.dp),
        )
        return
    }

    PaginatedVehicleDataList(
        rows = rows,
        listEpoch = listEpoch,
        totalCount = totalCount,
        fuelInsights = fuelInsights,
        colors = colors,
        loadingInitial = loadingInitial,
        loadingMore = loadingMore,
        hasMore = hasMore,
        listState = listState,
        onSelect = { row ->
            scope.launch(Dispatchers.IO) {
                val full = VehicleDataHistoryStore.loadVehicleDataRecordById(ctx, row.id) ?: row
                withContext(Dispatchers.Main) { selected = full }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun PaginatedVehicleDataList(
    rows: List<VehicleHistoryDatabase.VehicleDataRecord>,
    listEpoch: Int,
    totalCount: Int,
    fuelInsights: FuelTankAnalytics.FuelHistoryInsights,
    colors: CarColorPalette,
    loadingInitial: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSelect: (VehicleHistoryDatabase.VehicleDataRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (loadingInitial && rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accentBlue)
        }
        return
    }
    if (rows.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.car_control_vehicle_history_empty),
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        return
    }

    val dayGroups = remember(rows) { groupVehicleRecordsByDay(rows) }
    var expandedDayKeys by remember(listEpoch) { mutableStateOf(setOf<String>()) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.car_control_vehicle_history_data_count_fmt, totalCount),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
                Text(
                    stringResource(R.string.car_control_vehicle_history_tab_history_hint),
                    fontSize = 10.sp,
                    color = colors.textSecondary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp),
                    lineHeight = 14.sp,
                )
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.divider.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                HeaderCell(stringResource(R.string.car_control_vehicle_history_col_time), 1.35f, colors)
                HeaderCell(stringResource(R.string.car_control_vehicle_history_col_odometer), 0.85f, colors)
                HeaderCell(stringResource(R.string.car_control_vehicle_history_col_voltage), 0.7f, colors)
                HeaderCell(stringResource(R.string.car_control_vehicle_history_col_fuel), 0.7f, colors)
                HeaderCell(stringResource(R.string.car_control_vehicle_history_col_range), 0.75f, colors)
                HeaderCell(stringResource(R.string.car_control_vehicle_history_col_engine), 0.75f, colors)
            }
        }
        items(dayGroups, key = { it.dayKey }) { group ->
            val expanded = group.dayKey in expandedDayKeys
            val hasMoreSameDay = group.others.isNotEmpty()
            Column(Modifier.fillMaxWidth()) {
                VehicleHistoryDataRow(
                    row = group.latest,
                    badge = fuelInsights.recordBadges[group.latest.id],
                    colors = colors,
                    timeText = shortInsertDate(group.latest.insertDate),
                    onSelect = onSelect,
                    foldControl = if (hasMoreSameDay) {
                        {
                            DayFoldControl(
                                othersCount = group.others.size,
                                expanded = expanded,
                                colors = colors,
                                onToggle = {
                                    expandedDayKeys = if (expanded) {
                                        expandedDayKeys - group.dayKey
                                    } else {
                                        expandedDayKeys + group.dayKey
                                    }
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
                if (expanded) {
                    group.others.forEach { row ->
                        VehicleHistoryDataRow(
                            row = row,
                            badge = fuelInsights.recordBadges[row.id],
                            colors = colors,
                            timeText = shortInsertTime(row.insertDate),
                            onSelect = onSelect,
                            indented = true,
                        )
                        HorizontalDivider(
                            color = colors.divider.copy(alpha = 0.18f),
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider.copy(alpha = 0.25f))
        }
        item {
            ListFooter(loadingMore, hasMore, rows.size, colors)
        }
    }
}

@Composable
private fun DayFoldControl(
    othersCount: Int,
    expanded: Boolean,
    colors: CarColorPalette,
    onToggle: () -> Unit,
) {
    Text(
        text = if (expanded) {
            stringResource(R.string.car_control_vehicle_history_day_collapse)
        } else {
            stringResource(R.string.car_control_vehicle_history_day_fold_fmt, othersCount)
        },
        fontSize = 11.sp,
        color = colors.accentBlue,
        modifier = Modifier
            .padding(start = 4.dp)
            .clickable(onClick = onToggle),
        maxLines = 1,
    )
}

@Composable
private fun VehicleHistoryDataRow(
    row: VehicleHistoryDatabase.VehicleDataRecord,
    badge: FuelTankAnalytics.FuelRecordBadge?,
    colors: CarColorPalette,
    timeText: String,
    onSelect: (VehicleHistoryDatabase.VehicleDataRecord) -> Unit,
    indented: Boolean = false,
    foldControl: (@Composable () -> Unit)? = null,
) {
    val refuelBg = if (badge?.isRefuel == true) {
        colors.accentOrange.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(refuelBg)
            .clickable { onSelect(row) }
            .padding(
                start = if (indented) 14.dp else 10.dp,
                end = 10.dp,
                top = if (indented) 6.dp else 9.dp,
                bottom = if (indented) 6.dp else 9.dp,
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.35f)) {
                Text(
                    timeText,
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                foldControl?.invoke()
            }
            DataCell("${fmt1(row.odometer)}", 0.85f, colors)
            DataCell("${fmt1(row.batteryVoltage)}V", 0.7f, colors)
            DataCell(VehicleHistoryFuelDisplay.formatFuelLevelCell(row), 0.7f, colors)
            DataCell("${fmt0(row.distanceToEmpty)}", 0.75f, colors)
            DataCell(if (row.engineStart == 1) "运行" else "熄火", 0.75f, colors)
        }
        if (badge != null && badge.isRefuel) {
            FuelRecordBadgeRow(badge, colors, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun FuelRecordBadgeRow(
    badge: FuelTankAnalytics.FuelRecordBadge,
    colors: CarColorPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuelTagChip(
            stringResource(R.string.car_control_vehicle_history_refuel_tag),
            colors.accentOrange,
            colors,
        )
        badge.addedFuelText?.let { FuelTagChip(it, colors.textPrimary, colors) }
        badge.refuelCostText?.let { FuelTagChip(it, colors.accentOrange, colors) }
        badge.price95Text?.let {
            val color = if (badge.isReferencePrice) colors.textSecondary else colors.accentBlue
            FuelTagChip(it, color, colors)
        }
        badge.tankAvgText?.let { FuelTagChip(it, colors.accentOrange, colors) }
        badge.tripCostText?.let { FuelTagChip(it, colors.textSecondary, colors) }
    }
}

@Composable
private fun FuelTagChip(
    text: String,
    textColor: Color,
    colors: CarColorPalette,
) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = textColor,
        modifier = Modifier
            .background(colors.divider.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private data class VehicleHistoryDayGroup(
    val dayKey: String,
    val latest: VehicleHistoryDatabase.VehicleDataRecord,
    val others: List<VehicleHistoryDatabase.VehicleDataRecord>,
)

/** 列表按 INSERT_DATE 倒序；同一天只展示最新一条，其余折叠。 */
private fun groupVehicleRecordsByDay(
    rows: List<VehicleHistoryDatabase.VehicleDataRecord>,
): List<VehicleHistoryDayGroup> {
    if (rows.isEmpty()) return emptyList()
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val grouped = LinkedHashMap<String, MutableList<VehicleHistoryDatabase.VehicleDataRecord>>()
    for (row in rows) {
        val key = dayFormat.format(Date(row.insertDate))
        grouped.getOrPut(key) { ArrayList() }.add(row)
    }
    return grouped.map { (key, dayRows) ->
        VehicleHistoryDayGroup(
            dayKey = key,
            latest = dayRows.first(),
            others = dayRows.drop(1),
        )
    }
}

/** 界面二：操作记录。 */
@Composable
private fun VehicleHistoryOperatePage(
    listEpoch: Int,
    colors: CarColorPalette,
    active: Boolean,
) {
    val modifier = Modifier.fillMaxSize()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSize = VehicleHistoryDatabase.PAGE_SIZE
    var rows by remember(listEpoch) { mutableStateOf<List<VehicleHistoryDatabase.OperateRecord>>(emptyList()) }
    var offset by remember(listEpoch) { mutableIntStateOf(0) }
    var hasMore by remember(listEpoch) { mutableStateOf(true) }
    var loadingInitial by remember(listEpoch) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadInitial() {
        scope.launch {
            loadingInitial = true
            try {
                val page = withContext(Dispatchers.IO) {
                    VehicleDataHistoryStore.loadOperateRecordsPage(ctx, 0, pageSize)
                }
                rows = page
                offset = page.size
                hasMore = page.size >= pageSize
            } catch (_: Exception) {
                rows = emptyList()
                hasMore = false
            } finally {
                loadingInitial = false
            }
        }
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loadingInitial) return
        scope.launch {
            loadingMore = true
            try {
                val page = withContext(Dispatchers.IO) {
                    VehicleDataHistoryStore.loadOperateRecordsPage(ctx, offset, pageSize)
                }
                if (page.isNotEmpty()) {
                    rows = rows + page
                    offset += page.size
                    hasMore = page.size >= pageSize
                } else {
                    hasMore = false
                }
            } finally {
                loadingMore = false
            }
        }
    }

    LaunchedEffect(listEpoch) {
        loadInitial()
    }

    LaunchedEffect(listState, listEpoch, rows.size, hasMore, loadingMore, loadingInitial, active) {
        if (!active || !hasMore || loadingMore || loadingInitial) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 3) {
                loadMore()
            }
        }
    }

    if (loadingInitial && rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accentBlue)
        }
        return
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.car_control_vehicle_history_operate_empty), color = colors.textSecondary)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.id }) { row ->
            HistoryRecordCard(colors = colors) {
                Text(
                    row.displayTime,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    row.displayOperateChinese(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                val argsText = row.displayArgsChinese()
                if (argsText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        argsText,
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
        item { ListFooter(loadingMore, hasMore, rows.size, colors) }
    }
}

/** 界面三：警告记录。 */
@Composable
private fun VehicleHistoryWarningPage(
    listEpoch: Int,
    colors: CarColorPalette,
    active: Boolean,
) {
    val modifier = Modifier.fillMaxSize()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSize = VehicleHistoryDatabase.PAGE_SIZE
    var rows by remember(listEpoch) { mutableStateOf<List<VehicleHistoryDatabase.WarningRecord>>(emptyList()) }
    var offset by remember(listEpoch) { mutableIntStateOf(0) }
    var hasMore by remember(listEpoch) { mutableStateOf(true) }
    var loadingInitial by remember(listEpoch) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadInitial() {
        scope.launch {
            loadingInitial = true
            try {
                val page = withContext(Dispatchers.IO) {
                    VehicleDataHistoryStore.loadWarningRecordsPage(ctx, 0, pageSize)
                }
                rows = page
                offset = page.size
                hasMore = page.size >= pageSize
            } catch (_: Exception) {
                rows = emptyList()
                hasMore = false
            } finally {
                loadingInitial = false
            }
        }
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loadingInitial) return
        scope.launch {
            loadingMore = true
            try {
                val page = withContext(Dispatchers.IO) {
                    VehicleDataHistoryStore.loadWarningRecordsPage(ctx, offset, pageSize)
                }
                if (page.isNotEmpty()) {
                    rows = rows + page
                    offset += page.size
                    hasMore = page.size >= pageSize
                } else {
                    hasMore = false
                }
            } finally {
                loadingMore = false
            }
        }
    }

    LaunchedEffect(listEpoch) {
        loadInitial()
    }

    LaunchedEffect(listState, listEpoch, rows.size, hasMore, loadingMore, loadingInitial, active) {
        if (!active || !hasMore || loadingMore || loadingInitial) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 3) {
                loadMore()
            }
        }
    }

    if (loadingInitial && rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accentBlue)
        }
        return
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.car_control_vehicle_history_warning_empty), color = colors.textSecondary)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.id }) { row ->
            HistoryRecordCard(colors = colors) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.displayTime,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.car_control_vehicle_history_warning_tag),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accentOrange,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    row.displayWarningChinese(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    lineHeight = 22.sp,
                )
            }
        }
        item { ListFooter(loadingMore, hasMore, rows.size, colors) }
    }
}

@Composable
private fun ListFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    loadedCount: Int,
    colors: CarColorPalette,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> CircularProgressIndicator(
                modifier = Modifier.height(28.dp),
                color = colors.accentBlue,
                strokeWidth = 2.dp,
            )
            !hasMore && loadedCount > 0 -> Text(
                stringResource(R.string.car_control_vehicle_history_no_more),
                fontSize = 13.sp,
                color = colors.textSecondary,
            )
            else -> Text(
                stringResource(
                    R.string.car_control_vehicle_history_pull_more_hint,
                    VehicleHistoryDatabase.PAGE_SIZE,
                ),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
    }
}

/** 界面四：其他设置（导入 / 导出 / 清空）。 */
@Composable
private fun VehicleHistorySettingsPage(
    listEpoch: Int,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onFuelPrefsChanged: () -> Unit,
    colors: CarColorPalette,
) {
    val ctx = LocalContext.current
    var counts by remember { mutableStateOf(Triple(0, 0, 0)) }
    var monthlySummaries by remember { mutableStateOf<List<VehicleHistoryFuelCache.MonthlyFuelSummary>>(emptyList()) }
    var ecuAvgFuel by remember { mutableStateOf("—") }
    var fuelInsights by remember {
        mutableStateOf(
            FuelTankAnalytics.FuelHistoryInsights(
                lastTankLitersPer100Km = null,
                lastTankDistanceKm = null,
                lastTankFuelLiters = null,
                lastTankCostYuan = null,
                refuelRecordIds = emptySet(),
                recordBadges = emptyMap(),
                refuelCount = 0,
                monthlySummaries = emptyList(),
            ),
        )
    }
    var fuelAnalyticsRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(listEpoch) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val counts = VehicleDataHistoryStore.loadHistoryCounts(ctx)
                val monthly = VehicleHistoryFuelCache.loadMonthlySummaries(ctx)
                val fast = VehicleDataHistoryStore.loadHistoryFast(ctx, 1)
                Triple(counts, monthly, fast)
            }
            counts = loaded.first
            monthlySummaries = loaded.third.monthlySummaries.ifEmpty { loaded.second }
            ecuAvgFuel = loaded.third.ecuAvgFuel
            fuelInsights = loaded.third.fuelInsights
            fuelAnalyticsRefreshing = false
        } catch (_: Exception) {
            counts = Triple(0, 0, 0)
            monthlySummaries = emptyList()
            fuelAnalyticsRefreshing = false
        }
    }

    LaunchedEffect(listEpoch) {
        if (!VehicleDataHistoryStore.needsFuelAnalyticsRefresh(ctx)) return@LaunchedEffect
        fuelAnalyticsRefreshing = true
        try {
            val fresh = withContext(Dispatchers.IO) {
                VehicleDataHistoryStore.refreshFuelHistoryInsights(ctx)
            }
            monthlySummaries = fresh.monthlySummaries
            fuelInsights = fresh
            ecuAvgFuel = VehicleDataHistoryStore.resolveEcuAvgFuelDisplay(ctx)
        } catch (_: Exception) {
        } finally {
            fuelAnalyticsRefreshing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        VehicleInfoProfileHeader(
            profileEpoch = listEpoch,
            colors = colors,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsActionButton(stringResource(R.string.car_control_vehicle_history_import), colors.accentBlue, onImport, Modifier.weight(1f))
            SettingsActionButton(stringResource(R.string.car_control_vehicle_history_export), colors.accentBlue, onExport, Modifier.weight(1f))
            SettingsActionButton(stringResource(R.string.car_control_vehicle_history_clear), colors.accentRed, onClear, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.car_control_vehicle_history_count_fmt, counts.first, counts.second, counts.third),
            fontSize = 11.sp,
            color = colors.textSecondary,
        )
        Text(
            stringResource(R.string.car_control_vehicle_history_ecu_avg_fuel_fmt, ecuAvgFuel),
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        val lastL100 = fuelInsights.lastTankLitersPer100Km
        if (lastL100 != null) {
            Text(
                stringResource(R.string.car_control_vehicle_history_last_tank_fmt, lastL100),
                fontSize = 12.sp,
                color = colors.accentOrange,
                modifier = Modifier.padding(top = 4.dp),
            )
            val km = fuelInsights.lastTankDistanceKm
            val used = fuelInsights.lastTankFuelLiters
            val cost = fuelInsights.lastTankCostYuan
            if (km != null && used != null && cost != null) {
                Text(
                    stringResource(
                        R.string.car_control_vehicle_history_last_tank_sub_fmt,
                        km,
                        used,
                        cost,
                    ),
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (fuelInsights.refuelCount > 0) {
            Text(
                stringResource(
                    R.string.car_control_vehicle_history_refuel_count_fmt,
                    fuelInsights.refuelCount,
                ),
                fontSize = 11.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (fuelAnalyticsRefreshing) {
            Text(
                stringResource(R.string.car_control_vehicle_history_fuel_analytics_refreshing),
                fontSize = 10.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (monthlySummaries.isNotEmpty()) {
            Text(
                stringResource(R.string.car_control_vehicle_history_monthly_fuel_section),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 10.dp),
            )
            monthlySummaries.forEach { month ->
                Text(
                    stringResource(
                        R.string.car_control_vehicle_history_monthly_fuel_fmt,
                        month.monthKey,
                        month.refuelCount,
                        month.totalCostYuan,
                    ),
                    fontSize = 11.sp,
                    color = colors.accentBlue,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        var provinceSource by remember {
            mutableStateOf(FuelPriceRegionPrefs.resolveProvinceSource(ctx))
        }
        var tankL by remember { mutableStateOf(FuelPriceRegionPrefs.tankCapacityLiters(ctx)) }
        LaunchedEffect(listEpoch) {
            try {
                val refreshed = withContext(Dispatchers.IO) {
                    FuelPriceRegionPrefs.refreshAutoProvinceFromVehicle(ctx)
                }
                provinceSource = refreshed
            } catch (_: Exception) {
            }
        }
        Text(
            stringResource(
                if (provinceSource.fromVehicleAuto) {
                    R.string.car_control_vehicle_history_fuel_province_auto_fmt
                } else {
                    R.string.car_control_vehicle_history_fuel_province_manual_fmt
                },
                provinceSource.province,
            ),
            fontSize = 12.sp,
            color = colors.accentBlue,
            modifier = Modifier
                .padding(top = 10.dp)
                .combinedClickable(
                    onClick = {
                        provinceSource = FuelPriceRegionPrefs.onProvinceSettingClick(ctx)
                        onFuelPrefsChanged()
                    },
                    onLongClick = {
                        provinceSource = FuelPriceRegionPrefs.restoreAutoFromVehicle(ctx)
                        onFuelPrefsChanged()
                    },
                ),
        )
        Text(
            stringResource(R.string.car_control_vehicle_history_fuel_province_restore_auto),
            fontSize = 10.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            stringResource(R.string.car_control_vehicle_history_fuel_tank_fmt, tankL),
            fontSize = 12.sp,
            color = colors.accentBlue,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable {
                    tankL = FuelPriceRegionPrefs.cycleTankCapacity(ctx)
                    onFuelPrefsChanged()
                },
        )
        Text(
            stringResource(R.string.car_control_vehicle_history_fuel_price_hint),
            fontSize = 10.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.car_control_vehicle_history_hint),
            fontSize = 10.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(R.string.car_control_vehicle_page_db_path_fmt, VehicleHistoryDatabase.DB_NAME),
            fontSize = 10.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SettingsActionButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(label, fontSize = 12.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun VehicleRecordDetailPane(
    record: VehicleHistoryDatabase.VehicleDataRecord,
    fuelBadge: FuelTankAnalytics.FuelRecordBadge?,
    colors: CarColorPalette,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var vehicleInfo by remember(record.vehicleId) {
        mutableStateOf<VehicleHistoryDatabase.VehicleInfoRecord?>(null)
    }
    LaunchedEffect(record.vehicleId) {
        vehicleInfo = withContext(Dispatchers.IO) {
            VehicleDataHistoryStore.loadVehicleInfo(ctx, record.vehicleId)
                ?: VehicleDataHistoryStore.loadPrimaryVehicleInfo(ctx)
        }
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) {
            Text("← ${stringResource(R.string.car_control_vehicle_history_back)}", color = colors.accentBlue)
        }
        Text(record.displayTime, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)

        if (fuelBadge != null && fuelBadge.isRefuel) {
            DetailSection(colors, stringResource(R.string.car_control_vehicle_history_fuel_section)) {
                fuelBadge.addedFuelText?.let { detailRow(colors, "加油量", it) }
                fuelBadge.refuelCostText?.let { detailRow(colors, "加油金额", it) }
                fuelBadge.price95Text?.let { price ->
                    val suffix = if (fuelBadge.isReferencePrice) {
                        stringResource(R.string.car_control_vehicle_history_refuel_price_ref_suffix)
                    } else {
                        ""
                    }
                    detailRow(colors, "95# 油价", "$price / L$suffix")
                }
                fuelBadge.tankAvgText?.let { detailRow(colors, "上一箱油耗", it) }
                fuelBadge.tripCostText?.let { detailRow(colors, "上一箱油费", it) }
            }
        }

        DetailSection(colors, stringResource(R.string.car_control_vehicle_history_table_title)) {
            detailRow(colors, "总里程", "${fmt1(record.odometer)} km")
            detailRow(colors, "电瓶电压", "${fmt1(record.batteryVoltage)} V")
            detailRow(colors, "油量", VehicleHistoryFuelDisplay.formatFuelLevelCell(record))
            detailRow(colors, "续航", "${fmt0(record.distanceToEmpty)} km")
            detailRow(colors, "发动机", if (record.engineStart == 1) "运行" else "熄火")
            detailRow(colors, "机油/保养标志", record.addOliFlag.toString())
            if (record.latitude != null && record.longitude != null) {
                detailRow(colors, stringResource(R.string.car_control_vehicle_history_record_address), record.address.orEmpty())
                detailRow(colors, "坐标", String.format(java.util.Locale.getDefault(), "%.5f, %.5f", record.latitude, record.longitude))
            } else {
                detailRow(colors, stringResource(R.string.car_control_vehicle_history_record_address), record.address.orEmpty())
            }
            detailRow(colors, "车辆ID", record.vehicleId.toString())
        }

        val snap = record.snapshotJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val basicSnap = snap?.optJSONObject("basicInfo")
        if (vehicleInfo != null || basicSnap != null) {
            DetailSection(colors, stringResource(R.string.car_control_vehicle_history_basic)) {
                val plate = vehicleInfo?.plateNo ?: basicSnap?.optString("plateNo").orEmpty()
                val seriesModel = vehicleInfo?.let { vehicleInfoSeriesModel(it) }
                    ?: basicSnap?.let { seriesModelLine(it) }.orEmpty()
                val vin = vehicleInfo?.vin ?: basicSnap?.optString("vin").orEmpty()
                detailRow(colors, stringResource(R.string.car_control_vehicle_plate), plate)
                detailRow(colors, stringResource(R.string.car_control_vehicle_series_model), seriesModel)
                detailRow(colors, stringResource(R.string.car_control_vehicle_vin), vin)
            }
        }
        if (snap != null) {
            val query = snap.optJSONObject("queryData")
            val vsItems = query?.optJSONArray("vehicleStatusItems")
            val meItems = query?.optJSONArray("mileageEnergyItems")
            if (vsItems != null && vsItems.length() > 0) {
                DetailSection(colors, stringResource(R.string.car_control_vehicle_subsection_status)) {
                    namedItems(vsItems).forEach { (n, s) -> detailRow(colors, n, s) }
                }
            }
            if (meItems != null && meItems.length() > 0) {
                DetailSection(colors, stringResource(R.string.car_control_vehicle_subsection_mileage)) {
                    namedItems(meItems).forEach { (n, s) -> detailRow(colors, n, s) }
                }
            }
            val tires = snap.optJSONArray("tirePressure")
            if (tires != null && tires.length() > 0) {
                DetailSection(colors, stringResource(R.string.car_control_vehicle_history_tire)) {
                    for (i in 0 until tires.length()) {
                        val t = tires.optJSONObject(i) ?: continue
                        val label = t.optString("label", "")
                        val p = t.optString("pressure", "")
                        if (label.isNotEmpty() && p.isNotEmpty() && p != "未知") {
                            detailRow(colors, label, "$p kPa")
                        }
                    }
                }
            }
        }
    }
}

/** 车辆档案表头：VEHICLE_INFO 只写一次，导入前与此核对 VIN / 车牌。 */
@Composable
private fun VehicleInfoProfileHeader(
    profileEpoch: Int,
    colors: CarColorPalette,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var profile by remember { mutableStateOf<VehicleHistoryDatabase.VehicleInfoRecord?>(null) }

    LaunchedEffect(profileEpoch) {
        profile = withContext(Dispatchers.IO) {
            VehicleDataHistoryStore.loadPrimaryVehicleInfo(ctx)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.bgCard),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                stringResource(R.string.car_control_vehicle_history_profile_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            if (profile == null) {
                Text(
                    stringResource(R.string.car_control_vehicle_history_profile_empty),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    lineHeight = 18.sp,
                )
            } else {
                val info = profile!!
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.divider.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    ProfileTableCell(
                        stringResource(R.string.car_control_vehicle_history_profile_plate),
                        1f,
                        colors,
                        header = true,
                    )
                    ProfileTableCell(
                        stringResource(R.string.car_control_vehicle_history_profile_series),
                        1.35f,
                        colors,
                        header = true,
                    )
                    ProfileTableCell(
                        stringResource(R.string.car_control_vehicle_history_profile_color),
                        0.85f,
                        colors,
                        header = true,
                    )
                    ProfileTableCell(
                        stringResource(R.string.car_control_vehicle_vin),
                        1.5f,
                        colors,
                        header = true,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    ProfileTableCell(profileField(info.plateNo), 1f, colors)
                    ProfileTableCell(vehicleInfoSeriesModel(info), 1.35f, colors)
                    ProfileTableCell(profileField(info.colorName), 0.85f, colors)
                    ProfileTableCell(profileField(info.vin), 1.5f, colors, mono = true)
                }
            }
            Text(
                stringResource(R.string.car_control_vehicle_history_profile_import_hint),
                fontSize = 10.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun RowScope.ProfileTableCell(
    text: String,
    weight: Float,
    colors: CarColorPalette,
    header: Boolean = false,
    mono: Boolean = false,
) {
    Text(
        text,
        fontSize = if (header) 11.sp else 12.sp,
        fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
        color = if (header) colors.textSecondary else colors.textPrimary,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier.weight(weight),
        maxLines = if (header) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        lineHeight = if (header) 14.sp else 16.sp,
    )
}

private fun profileField(value: String?): String {
    val v = value?.trim().orEmpty()
    return when {
        v.isEmpty() || v == "未知" -> "—"
        else -> v
    }
}

@Composable
private fun DetailSection(
    colors: CarColorPalette,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.bgCard),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun detailRow(colors: CarColorPalette, label: String, value: String) {
    val v = value.trim()
    if (v.isEmpty() || v == "—" || v == "未知") return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.width(108.dp))
        Text(v, fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = colors.divider.copy(alpha = 0.4f), modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun HistoryRecordCard(
    colors: CarColorPalette,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.bgCard),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            content()
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, colors: CarColorPalette) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier.weight(weight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowScope.DataCell(
    text: String,
    weight: Float,
    colors: CarColorPalette,
    mono: Boolean = false,
) {
    Text(
        text,
        fontSize = 13.sp,
        color = colors.textPrimary,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = Modifier.weight(weight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun namedItems(arr: JSONArray): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name", "").trim()
        val state = o.optString("state", "").trim()
        if (name.isNotEmpty()) out.add(name to state.ifEmpty { "—" })
    }
    return out
}

private fun vehicleInfoSeriesModel(info: VehicleHistoryDatabase.VehicleInfoRecord): String {
    val parts = listOfNotNull(info.seriesName, info.modelName)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "未知" }
    return parts.joinToString(" ").ifEmpty { "—" }
}

private fun seriesModelLine(basic: JSONObject): String {
    val parts = listOf(basic.optString("seriesName", ""), basic.optString("modelName", ""))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "未知" }
    return parts.joinToString(" ").ifEmpty { "—" }
}

private fun fmt1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
private fun fmt0(v: Double) = String.format(Locale.getDefault(), "%.0f", v)

private fun shortInsertDate(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun shortInsertTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
