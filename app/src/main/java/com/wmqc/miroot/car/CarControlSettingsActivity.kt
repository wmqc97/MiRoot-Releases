package com.wmqc.miroot.car
import com.wmqc.miroot.display.MainDisplayUi

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.roundToInt

/**
 * 车控设置（界面风格 [Miuix](https://github.com/compose-miuix-ui/miuix)）。
 * 主界面为现代车控仪表盘风格：车模图片 + 车辆信息 + 底部双列按钮 + 二级设置页。
 */
class CarControlSettingsActivity : ComponentActivity() {

    private val carModelBitmapState: MutableState<Bitmap?> = mutableStateOf(null)

    private val pickCarModel = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val uri = result.data!!.data ?: return@registerForActivityResult
        copyCarModelFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CarControlDeviceGate.isAllowed(this)) {
            MainDisplayUi.showToast(this, "当前设备未授权使用车控", Toast.LENGTH_SHORT)
            finish()
            return
        }
        applyMiRootSecondarySystemBars()
        enableEdgeToEdge()
        lifecycleScope.launch(Dispatchers.IO) {
            carModelBitmapState.value = CarModelImageLoader.load(applicationContext)
        }
        setContent {
            val carModelBitmap by carModelBitmapState
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                CarControlSettingsScreen(
                    carModelBitmap = carModelBitmap,
                    onReLogin = {
                        startActivity(
                            Intent(this, CarControlLoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            },
                        )
                        finish()
                    },
                    onPickCarModel = {
                        try {
                            pickCarModel.launch(Intent(Intent.ACTION_PICK).setType("image/*"))
                        } catch (e: Exception) {
                            MainDisplayUi.showToast(this, e.message ?: "", Toast.LENGTH_SHORT)
                        }
                    },
                    onResetCarModel = { resetCarModelImage() },
                    onLogout = { performLogout() },
                )
            }
        }
    }

    private fun copyCarModelFromUri(sourceUri: Uri) {
        Thread {
            var ok = false
            try {
                val input = contentResolver.openInputStream(sourceUri)
                if (input != null) {
                    val carModelFile = File(filesDir, "car_model.png")
                    input.use { ins ->
                        FileOutputStream(carModelFile).use { out -> ins.copyTo(out) }
                    }
                    getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_CAR_MODEL_PATH, carModelFile.absolutePath)
                        .apply()
                    ok = true
                }
            } catch (_: Exception) {
                ok = false
            }
            runOnUiThread {
                MainDisplayUi.showToast(
                    this,
                    if (ok) getString(R.string.car_control_car_model_saved) else getString(R.string.car_control_car_model_failed),
                    Toast.LENGTH_LONG,
                )
                if (ok) {
                    reloadCarModelBitmap()
                    CarControlWidgetUpdater.refreshAll(this@CarControlSettingsActivity)
                }
            }
        }.start()
    }

    private fun resetCarModelImage() {
        getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CAR_MODEL_PATH)
            .apply()
        val f = File(filesDir, "car_model.png")
        if (f.exists()) f.delete()
        reloadCarModelBitmap()
        CarControlWidgetUpdater.refreshAll(this)
        MainDisplayUi.showToast(this, R.string.car_control_car_model_reset, Toast.LENGTH_LONG)
    }

    private fun reloadCarModelBitmap() {
        Thread {
            val bitmap = CarModelImageLoader.load(applicationContext)
            runOnUiThread { carModelBitmapState.value = bitmap }
        }.start()
    }

    private fun performLogout() {
        getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE).edit().clear().apply()
        try {
            LoginService.getLoginInfoFile(this).delete()
        } catch (_: Exception) { }
        startActivity(Intent(this, CarControlLoginActivity::class.java))
        finish()
    }

    private fun startCarControlProjection() {
        Thread {
            try {
                val intent = Intent(this, CarControlProjectionService::class.java).apply {
                    action = CarControlIntents.ACTION_OPEN_CAR_CONTROL_PROJECTION
                    putExtra(
                        CarControlIntents.EXTRA_CAR_PROJECTION_OP,
                        CarControlIntents.VALUE_CAR_PROJECTION_OP_START,
                    )
                }
                startService(intent)
                runOnUiThread {
                    MainDisplayUi.showToast(this, R.string.car_control_projection_started, Toast.LENGTH_SHORT)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    MainDisplayUi.showToast(this, getString(R.string.car_control_projection_failed, e.message ?: ""), Toast.LENGTH_LONG)
                }
            }
        }.start()
    }

    private companion object {
        private const val KEY_CAR_MODEL_PATH = "car_model_path"
    }
}


@Composable
private fun CarControlSettingsScreen(
    carModelBitmap: Bitmap?,
    onReLogin: () -> Unit,
    onPickCarModel: () -> Unit,
    onResetCarModel: () -> Unit,
    onLogout: () -> Unit,
) {
    DashboardScreen(
        carModelBitmap = carModelBitmap,
        onReLogin = onReLogin,
        onPickCarModel = onPickCarModel,
        onResetCarModel = onResetCarModel,
        onLogout = onLogout,
    )
}

// ─── Dashboard ─────────────────────────────────────────────────────────────────

@Composable
private fun DashboardScreen(
    carModelBitmap: Bitmap?,
    onReLogin: () -> Unit,
    onPickCarModel: () -> Unit,
    onResetCarModel: () -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))

    val initialCachedStatus = remember { CarVehicleDisplayCache.loadStatus(appCtx) }
    var vehicleUi by remember {
        mutableStateOf(
            CarVehicleDisplayHelper.loadCached(appCtx)
                ?: if (!VehicleControlService.extractVehicleParams(appCtx).isValid) {
                    CarVehicleDisplayHelper.emptyUi(appCtx, needBind = true)
                } else {
                    null
                },
        )
    }
    var vehicleStatus by remember { mutableStateOf(initialCachedStatus) }

    // 底部按钮配置
    var rearButtons by remember { mutableStateOf(defaultRearButtonsForFirstInstall()) }
    var currentPage by remember { mutableIntStateOf(0) }

    // 空调 & 座椅加热状态与参数
    var acStatus by remember { mutableStateOf(false) }
    var acDuration by remember { mutableStateOf(10) }
    var acTemp by remember { mutableStateOf(22) }
    var seatHeatingStatus by remember { mutableStateOf(false) }
    var seatHeatingDuration by remember { mutableStateOf(10) }
    var seatHeatingLevel by remember { mutableStateOf(1) }

    // 车辆位置（高德地图）
    var vehicleLng by remember {
        mutableStateOf(VehiclePositionHelper.loadCache(appCtx)?.lng ?: Double.NaN)
    }
    var vehicleLat by remember {
        mutableStateOf(VehiclePositionHelper.loadCache(appCtx)?.lat ?: Double.NaN)
    }
    var mapRefreshKey by remember { mutableIntStateOf(0) }
    var showButtonEditDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 先展示缓存，再在后台静默刷新（避免进入页空白）
    LaunchedEffect(Unit) {
        initialCachedStatus?.let { cached ->
            CarControlVehiclePrefsSync.applyFromStatus(appCtx, cached)
            VehiclePositionHelper.fromStatus(cached)?.let { c ->
                vehicleLng = c.lng
                vehicleLat = c.lat
            } ?: VehiclePositionHelper.loadCache(appCtx)?.let { c ->
                vehicleLng = c.lng
                vehicleLat = c.lat
            }
        }
        withContext(Dispatchers.IO) {
            try {
                val (ui, status) = CarVehicleDisplayHelper.loadWithStatus(appCtx)
                withContext(Dispatchers.Main) {
                    vehicleUi = ui
                    vehicleStatus = status
                }
                val coords = VehiclePositionHelper.fromStatus(status)
                    ?: VehiclePositionHelper.loadFromVehicle(appCtx)
                withContext(Dispatchers.Main) {
                    coords?.let {
                        vehicleLng = it.lng
                        vehicleLat = it.lat
                        mapRefreshKey++
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // 加载底部按钮配置
    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        rearButtons = CarRearButtonsConfig.load(ctx)
    }

    // 加载空调 & 座椅加热状态
    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        acStatus = p.getBoolean("ac_status", false)
        acDuration = p.getInt(KEY_AC_DURATION, 10)
        acTemp = p.getInt(KEY_AC_TEMPERATURE, 22)
        seatHeatingStatus = p.getBoolean("seat_heating_status", false)
        seatHeatingDuration = p.getInt(KEY_SEAT_HEATING_DURATION, 10)
        seatHeatingLevel = p.getInt(KEY_SEAT_HEATING_LEVEL, 1)
    }

    // 计算总页数（每页4个按钮，2列2行）
    val totalPages = ((rearButtons.size + 3) / 4).coerceIn(1, 2)

    var isRefreshing by remember { mutableStateOf(false) }
    fun refreshVehicleData() {
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            var ui: CarVehicleDisplayUi? = null
            var vs: VehicleStatusService.VehicleStatusInfo? = null
            try {
                val loaded = CarVehicleDisplayHelper.loadWithStatus(appCtx)
                ui = loaded.first
                vs = loaded.second
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                ui?.let { vehicleUi = it }
                vs?.let { vehicleStatus = it }
                isRefreshing = false
            }
            val coords = VehiclePositionHelper.fromStatus(vs)
                ?: VehiclePositionHelper.loadFromVehicle(appCtx)
            coords?.let { c ->
                withContext(Dispatchers.Main) {
                    vehicleLng = c.lng
                    vehicleLat = c.lat
                    mapRefreshKey++
                }
            }
        }
    }

    fun toggleAc() {
        val newState = !acStatus
        acStatus = newState
        scope.launch(Dispatchers.IO) {
            executeByText(appCtx, "空调")
            ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("ac_status", newState)
                .apply()
        }
    }

    fun persistAcParams(duration: Int, temp: Int) {
        ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AC_DURATION, duration)
            .putInt(KEY_AC_TEMPERATURE, temp)
            .apply()
        acDuration = duration
        acTemp = temp
    }

    fun toggleSeatHeating() {
        val newState = !seatHeatingStatus
        seatHeatingStatus = newState
        scope.launch(Dispatchers.IO) {
            executeByText(appCtx, "座椅加热")
            ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("seat_heating_status", newState)
                .apply()
        }
    }

    fun persistSeatHeatingParams(duration: Int, level: Int) {
        ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SEAT_HEATING_DURATION, duration)
            .putInt(KEY_SEAT_HEATING_LEVEL, level)
            .apply()
        seatHeatingDuration = duration
        seatHeatingLevel = level
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { refreshVehicleData() },
        modifier = Modifier.fillMaxSize().background(pageBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 顶部栏 ──
            TopBar(
                title = "星瑞",
                onLogout = onLogout,
                onLongPressTitle = onReLogin,
                onOpenWidgetSettings = {
                    ctx.startActivity(CarControlWidgetConfigureActivity.intentForSettings(ctx))
                },
                onOpenVehicleHistory = {
                    ctx.startActivity(Intent(ctx, VehicleHistoryActivity::class.java))
                },
            )

            // ── 现代化车控仪表盘 ──
            // 燃油表弧 + 车模 + 续航（合并版）
            CarImageAndGaugesSection(
                vehicleUi = vehicleUi,
                vehicleStatus = vehicleStatus,
                carModelBitmap = carModelBitmap,
                onPickCarModel = onPickCarModel,
                onResetCarModel = onResetCarModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(8.dp))

            // 车速 / 车外温度 / 电瓶 状态芯片
            TempBatteryRow(
                vehicleUi = vehicleUi,
                vehicleStatus = vehicleStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(12.dp))

            // 快捷控制按钮（4x2 网格 + 页面指示器）

            Spacer(Modifier.size(12.dp))

            // -- 页面指示器 --
            RearButtonGrid(
                buttons = rearButtons,
                pageIndex = currentPage,
                onPageChange = { currentPage = it },
                totalPages = totalPages,
                vehicleStatus = vehicleStatus,
                acStatus = acStatus,
                seatHeatingStatus = seatHeatingStatus,
                onEditButtons = { showButtonEditDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(12.dp))

// ── 空调控制 ──
            AcControlCard(
                status = acStatus,
                duration = acDuration,
                temp = acTemp,
                onToggle = { toggleAc() },
                onDurationChange = { d -> persistAcParams(d, acTemp) },
                onTempChange = { t -> persistAcParams(acDuration, t) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(12.dp))

            // ── 座椅加热控制 ──
            SeatHeatingControlCard(
                status = seatHeatingStatus,
                duration = seatHeatingDuration,
                level = seatHeatingLevel,
                onToggle = { toggleSeatHeating() },
                onDurationChange = { d -> persistSeatHeatingParams(d, seatHeatingLevel) },
                onLevelChange = { l -> persistSeatHeatingParams(seatHeatingDuration, l) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )
            Spacer(Modifier.size(12.dp))

            // 车辆状态摘要
            VehicleStatusSummary(
                vehicleUi = vehicleUi,
                vehicleStatus = vehicleStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(12.dp))

            // 胎压监测（有数据才显示）
            TirePressureSection(
                vehicleStatus = vehicleStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(12.dp))

            // 里程能耗（有数据才显示）
            MileageEnergySection(
                vehicleUi = vehicleUi,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            // 高德车辆位置地图（始终显示，无坐标时显示占位）
            Spacer(Modifier.size(12.dp))
            MapSection(
                lng = vehicleLng,
                lat = vehicleLat,
                refreshKey = mapRefreshKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(24.dp))
        }
        // 按钮排列编辑弹窗
        if (showButtonEditDialog) {
            ButtonEditDialog(
                initial = rearButtons,
                onDismiss = { showButtonEditDialog = false },
                onConfirm = { newList ->
                    val normalized = normalizeValidRearButtons(newList)
                    ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(CarRearButtonsConfig.PREFS_KEY, encodeRearButtons(normalized))
                        .apply()
                    rearButtons = normalized
                    CarControlWidgetUpdater.refreshAll(ctx)
                    showButtonEditDialog = false
                },
            )
        }
    }
}

// ─── CarConfirmAction ───────────────────────────────────────────────────────────

// ─── ButtonEditDialog ──────────────────────────────────────────────────────────

@Composable
private fun ButtonEditDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val carColors = carColors()
    var selected by remember { mutableStateOf(initial.toMutableList()) }
    val available = remember(selected) {
        RearButtonConfigDialog.CONTROL_FUNCTIONS.filter { it !in selected }.toMutableList()
    }

    fun moveUp(i: Int) {
        if (i > 0) {
            selected = selected.toMutableList().also { val t = it[i]; it[i] = it[i-1]; it[i-1] = t }
        }
    }
    fun moveDown(i: Int) {
        if (i < selected.size - 1) {
            selected = selected.toMutableList().also { val t = it[i]; it[i] = it[i+1]; it[i+1] = t }
        }
    }
    fun removeSelected(i: Int) {
        if (selected.size > 1) {
            selected = selected.toMutableList().also { it.removeAt(i) }
        }
    }
    fun addFromAvailable(func: String) {
        if (selected.size < 8) {
            selected = selected.toMutableList().also { it.add(func) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("按钮排列编辑", fontWeight = FontWeight.SemiBold)
                Text("${selected.size}/8", fontSize = 13.sp, color = carColors.textSecondary)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // 已选列表（可拖拽排序、点击移除、上移）
                Text("已选功能", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = carColors.accentBlue)
                Spacer(Modifier.height(6.dp))
                selected.forEachIndexed { index, func ->
                    val bg = if (index % 2 == 0) carColors.bgCard.copy(alpha = 0.5f) else Color.Transparent
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = carColors.accentBlue,
                            modifier = Modifier.width(26.dp),
                        )
                        Text(
                            func,
                            fontSize = 14.sp,
                            color = carColors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        // 上移按钮
                        TextButton(
                            onClick = { moveUp(index) },
                            modifier = Modifier.size(36.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(if (index > 0) "↑" else " ", color = carColors.textSecondary) }
                        // 下移按钮
                        TextButton(
                            onClick = { moveDown(index) },
                            modifier = Modifier.size(36.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(if (index < selected.size - 1) "↓" else " ", color = carColors.textSecondary) }
                        // 移除按钮
                        TextButton(
                            onClick = { removeSelected(index) },
                            modifier = Modifier.size(36.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("✕", color = Color(0xFFDC2626)) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 可用列表（点击添加）
                Text("可用功能", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = carColors.accentGreen)
                Spacer(Modifier.height(6.dp))
                available.forEachIndexed { index, func ->
                    val bg = if (index % 2 == 0) carColors.bgCard.copy(alpha = 0.5f) else Color.Transparent
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { addFromAvailable(func) }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+", fontSize = 14.sp, color = carColors.accentGreen, modifier = Modifier.width(24.dp))
                        Text(
                            func,
                            fontSize = 14.sp,
                            color = carColors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.size >= 8) {
                            Text("已满", fontSize = 11.sp, color = Color(0xFFDC2626))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (selected.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "至少需要1个按钮", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(selected.toList())
                }
            }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ─── TopBar ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    title: String,
    onLogout: () -> Unit,
    onLongPressTitle: (() -> Unit)? = null,
    onOpenWidgetSettings: () -> Unit,
    onOpenVehicleHistory: () -> Unit,
) {
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val ctx = LocalContext.current
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = scrollPad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = onPagePrimary,
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            android.widget.Toast.makeText(ctx, "正在重新登录...", android.widget.Toast.LENGTH_SHORT).show()
                            onLongPressTitle?.invoke()
                        },
                    )
                },
        )
        Box {
            IconButton(onClick = { expanded = true }) {
                Text(
                    text = "≡",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = onPagePrimary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.car_control_widget_menu)) },
                    onClick = {
                        expanded = false
                        onOpenWidgetSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.car_control_vehicle_history_menu)) },
                    onClick = {
                        expanded = false
                        onOpenVehicleHistory()
                    },
                )
                DropdownMenuItem(
                    text = { Text("注销登录") },
                    onClick = {
                        expanded = false
                        onLogout()
                    },
                )
            }
        }
    }
}



// ─── AcControlCard ──────────────────────────────────────────────────────────────

/**
 * 空调控制卡片：显示空调状态、温度、时长参数，支持直接开关与参数调整。
 */
@Composable
private fun AcControlCard(
    status: Boolean,
    duration: Int,
    temp: Int,
    onToggle: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onTempChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardColor = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))
    val accentColor = Color(0xFF2196F3)

    Card(
        modifier = modifier,
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = CardColors(color = cardColor, contentColor = onPagePrimary),
    ) {
        var showAcConfirm by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行 + 参数
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.car_control_ac_title),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = onPagePrimary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = "${temp}℃",
                    fontSize = 12.sp,
                    color = onPageSecondary,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = "${duration}分钟",
                    fontSize = 12.sp,
                    color = onPageSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (status) "● 已开启" else "已关闭",
                    fontSize = 13.sp,
                    color = if (status) accentColor else onPageSecondary,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { showAcConfirm = true },
                )
            }

            // 空调确认弹窗
            if (showAcConfirm) {
                val actionName = if (status) "关闭" else "开启"
                AlertDialog(
                    onDismissRequest = { showAcConfirm = false },
                    title = { Text(text = "确认${actionName}空调") },
                    text = { Text(text = "确认${actionName}空调？温度${temp}℃，时长${duration}分钟") },
                    confirmButton = {
                        TextButton(onClick = {
                            showAcConfirm = false; onToggle()
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAcConfirm = false }) { Text("取消") }
                    },
                )
            }

            // 分割线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(onPageSecondary.copy(alpha = 0.2f)),
            )

            // 参数调整按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ParamAdjustGroup(
                    label = "温度",
                    value = "${temp}℃",
                    onDecrement = { onTempChange((temp - 1).coerceAtLeast(17)) },
                    onIncrement = { onTempChange((temp + 1).coerceAtMost(32)) },
                    modifier = Modifier.weight(1f),
                )
                ParamAdjustGroup(
                    label = "时长",
                    value = "${duration}分钟",
                    onDecrement = { onDurationChange((duration - 1).coerceAtLeast(3)) },
                    onIncrement = { onDurationChange((duration + 1).coerceAtMost(10)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 座椅加热控制卡片：显示加热状态、等级、时长参数，支持直接开关与参数调整。
 */
@Composable
private fun SeatHeatingControlCard(
    status: Boolean,
    duration: Int,
    level: Int,
    onToggle: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardColor = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))
    val accentColor = Color(0xFFFF9800)

    Card(
        modifier = modifier,
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = CardColors(color = cardColor, contentColor = onPagePrimary),
    ) {
        var showHeatConfirm by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行 + 参数
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.car_control_seat_heating_title),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = onPagePrimary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = "等级${level}",
                    fontSize = 12.sp,
                    color = onPageSecondary,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = "${duration}分钟",
                    fontSize = 12.sp,
                    color = onPageSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (status) "● 已开启" else "已关闭",
                    fontSize = 13.sp,
                    color = if (status) accentColor else onPageSecondary,
                    modifier = Modifier.clickable { showHeatConfirm = true },
                )
            }

            // 座椅加热确认弹窗
            if (showHeatConfirm) {
                val actionName = if (status) "关闭" else "开启"
                AlertDialog(
                    onDismissRequest = { showHeatConfirm = false },
                    title = { Text(text = "确认${actionName}座椅加热") },
                    text = { Text(text = "确认${actionName}座椅加热？等级${level}，时长${duration}分钟") },
                    confirmButton = {
                        TextButton(onClick = {
                            showHeatConfirm = false; onToggle()
                        }) { Text("确认") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showHeatConfirm = false }) { Text("取消") }
                    },
                )
            }

            // 分割线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(onPageSecondary.copy(alpha = 0.2f)),
            )

            // 参数调整按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ParamAdjustGroup(
                    label = "等级",
                    value = "${level}级",
                    onDecrement = { onLevelChange((level - 1).coerceAtLeast(1)) },
                    onIncrement = { onLevelChange((level + 1).coerceAtMost(3)) },
                    modifier = Modifier.weight(1f),
                )
                ParamAdjustGroup(
                    label = "时长",
                    value = "${duration}分钟",
                    onDecrement = { onDurationChange((duration - 1).coerceAtLeast(3)) },
                    onIncrement = { onDurationChange((duration + 1).coerceAtMost(10)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ─── ParamAdjustGroup (compact +/- control) ────────────────────────────────────

/**
 * 紧凑型参数调整组件：标签 + 当前值 + [－][＋] 按钮。
 */
@Composable
private fun ParamAdjustGroup(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val adjustBg = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface))

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = onPageSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(adjustBg.copy(alpha = 0.6f))
                    .clickable { onDecrement() },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "－", fontSize = 16.sp, color = onPageSecondary)
            }
            Text(
                text = value,
                fontSize = 13.sp,
                color = onPageSecondary,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(adjustBg.copy(alpha = 0.6f))
                    .clickable { onIncrement() },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "＋", fontSize = 16.sp, color = onPageSecondary)
            }
        }
    }
}

/**
 * 底部按钮，每页一行4个大圆按钮（图标 + 下方标题），支持左右滑动切换页面。
 * 下方有统一页面圆点指示器。
 */
@Composable
private fun RearButtonGrid(
    buttons: List<String>,
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    totalPages: Int,
    vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
    onEditButtons: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))

    if (buttons.isEmpty()) {
        Text(
            text = stringResource(R.string.car_control_rear_buttons_desc),
            color = onPageSecondary,
            style = MiuixTheme.textStyles.footnote1,
            modifier = modifier,
        )
        return
    }

    var dragTotal by remember { mutableStateOf(0f) }
    val consumeConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                return available
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(consumeConnection)
            .pointerInput(pageIndex, totalPages) {
                val threshold = 80.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragTotal < -threshold && pageIndex < totalPages - 1 ->
                                onPageChange(pageIndex + 1)
                            dragTotal > threshold && pageIndex > 0 ->
                                onPageChange(pageIndex - 1)
                        }
                        dragTotal = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragTotal += dragAmount
                    },
                )
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // current page: 4 buttons in a row
            val startIndex = pageIndex * 4
            val pageButtons = buttons.drop(startIndex).take(4)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                pageButtons.forEach { func ->
                    RearButtonCell(
                        text = func,
                        vehicleStatus = vehicleStatus,
                        acStatus = acStatus,
                        seatHeatingStatus = seatHeatingStatus,
                        onEditButtons = onEditButtons,
                        modifier = Modifier.weight(1f),
                    )
                }
                for (i in pageButtons.size until 4) {
                    Spacer(Modifier.weight(1f))
                }
            }

            // page indicator dots
            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    for (i in 0 until totalPages) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == pageIndex) onPagePrimary
                                    else onPageSecondary.copy(alpha = 0.35f)
                                )
                                .clickable { onPageChange(i) },
                        )
                    }
                }
            }
        }
    }
}


/**
 * 大圆按钮：图标（与背屏一致）+ 下方标题。
 * 背景色根据车辆状态动态变化：蓝色表示可执行的操作（与当前状态相反），浅灰表示当前状态/不可用。
 */
@Composable
private fun RearButtonCell(
    text: String,
    vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
    onEditButtons: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val displayText = resolveDisplayTextV2(ctx, text, vehicleStatus, acStatus, seatHeatingStatus)
    val isAlert = isButtonAlertStateV2(ctx, text, vehicleStatus, acStatus, seatHeatingStatus)

    val bg = if (isAlert) {
        if (isDark) Color(0xFF5A5A5A) else Color(0xFF81D4FA)
    } else {
        if (isDark) Color(0xFF2E2E2E) else Color.White
    }
    val iconColor = if (isAlert) {
        if (isDark) Color.White else Color(0xFF0D47A1)
    } else {
        if (isDark) Color.White else Color.Black
    }
    val textColor = if (isAlert) {
        if (isDark) Color.White else Color(0xFF0D47A1)
    } else {
        if (isDark) Color.White else Color.Black
    }

    var iconBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    // 读取空调/座椅加热参数用于弹窗展示
    val prefs = ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
    val acDurationPref = prefs.getInt(KEY_AC_DURATION, 10)
    val acTempPref = prefs.getInt(KEY_AC_TEMPERATURE, 22)
    val seatDurationPref = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
    val seatLevelPref = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)

    // 构建参数描述
    val paramDesc = when (text) {
        "空调" -> "温度${acTempPref}℃，时长${acDurationPref}分钟"
        "座椅加热" -> "等级${seatLevelPref}，时长${seatDurationPref}分钟"
        "主驾加热" -> "等级${seatLevelPref}，时长${seatDurationPref}分钟"
        "副驾加热" -> "等级${seatLevelPref}，时长${seatDurationPref}分钟"
        else -> null
    }

    // Reload icon when display text changes (resolved from vehicle status)
    LaunchedEffect(displayText, vehicleStatus) {
        iconBitmap = withContext(Dispatchers.IO) { loadCarControlIcon(ctx, displayText, vehicleStatus) }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 确认弹窗
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(text = "确认操作") },
                text = {
                    Column {
                        Text(text = "确认执行「${displayText}」？")
                        if (paramDesc != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = paramDesc,
                                fontSize = 13.sp,
                                color = carColors().textSecondary,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        scope.launch(Dispatchers.IO) {
                            executeByText(ctx, text)
                        }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) { Text("取消") }
                },
            )
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(bg)
                .pointerInput(text) {
                    detectTapGestures(
                        onTap = { showDialog = true },
                        onLongPress = { onEditButtons?.invoke() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (iconBitmap != null) {
                Image(
                    painter = BitmapPainter(iconBitmap!!.asImageBitmap()),
                    contentDescription = text,
                    modifier = Modifier.size(32.dp),
                    colorFilter = ColorFilter.tint(iconColor),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = displayText,
            fontSize = 12.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 根据车辆状态解析按钮当前可执行的单个状态标题（如"解锁"或"锁车"），
 * 不再显示组合的"锁车/解锁"。
 */
/**
 * 根据车辆状态解析按钮显示的标题（如"解锁"或"锁车"）。
 * 使用 VehicleStatusInfo 直接判断，与背屏按钮同步逻辑一致。
 */
private fun resolveDisplayTextV2(
    context: Context,
    text: String,
    vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
): String {
    val isLocked = when {
        vehicleStatus != null ->
            "已锁" == VehicleStatusService.translateDoorLockStatus(vehicleStatus.doorLockStatusDriver)
        else ->
            CarControlVehiclePrefsSync.carPrefs(context).getBoolean(CarControlVehiclePrefsSync.KEY_IS_LOCKED, false)
    }
    return when (text) {
        "锁车/解锁" -> if (isLocked) "解锁" else "锁车"
        "点火/熄火" -> if (vehicleStatus?.let { "运行中" == VehicleStatusService.translateEngineStatus(it.engineStatus) } == true) "熄火" else "点火"
        "开窗/关窗" -> if (vehicleStatus?.let { "已开" == it.winStatusDriver } == true) "关窗" else "开窗"
        "空调" -> if (acStatus) "关闭空调" else "打开空调"
        "透气" -> "透气"
        "开后备箱" -> if (vehicleStatus?.let { "已开" == VehicleStatusService.translateTrunkStatus(it.trunkOpenStatus) } == true) "关后备箱" else "开后备箱"
        "座椅加热" -> if (seatHeatingStatus) "关闭座椅加热" else "打开座椅加热"
        "主驾加热" -> if (seatHeatingStatus) "关闭主驾加热" else "主驾加热"
        "副驾加热" -> if (seatHeatingStatus) "关闭副驾加热" else "副驾加热"
        "寻车" -> "寻车"
        else -> text
    }
}

/**
 * 根据车辆状态判断按钮是否应显示为"可操作"状态。
 * 与 CarButtonStateManager.extractStateFromStatus 逻辑一致。
 */
private fun isButtonAlertStateV2(
    context: Context,
    text: String,
    vehicleStatus: VehicleStatusService.VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
): Boolean {
    if (vehicleStatus == null) {
        val prefs = CarControlVehiclePrefsSync.carPrefs(context)
        return when (text) {
            "锁车/解锁" -> !prefs.getBoolean(CarControlVehiclePrefsSync.KEY_IS_LOCKED, false)
            "空调" -> acStatus
            "座椅加热", "主驾加热", "副驾加热" -> seatHeatingStatus
            else -> false
        }
    }
    return when (text) {
        "寻车" -> false
        // 与背屏一致：已锁→灰底「解锁」；未锁→蓝底「锁车」
        "锁车/解锁" -> !("已锁" == VehicleStatusService.translateDoorLockStatus(vehicleStatus.doorLockStatusDriver))
        "点火/熄火" -> "运行中" == VehicleStatusService.translateEngineStatus(vehicleStatus.engineStatus)
        "开窗/关窗" -> "已开" == vehicleStatus.winStatusDriver
        "空调" -> acStatus
        "透气" -> {
            val pos = vehicleStatus.winPosDriver
            pos != null && pos != "未知" && try { val v = pos.toInt(); v > 0 && v <= 50 } catch (_: Exception) { false }
        }
        "开后备箱" -> "已开" == VehicleStatusService.translateTrunkStatus(vehicleStatus.trunkOpenStatus)
        "座椅加热", "主驾加热", "副驾加热" -> seatHeatingStatus
        else -> false
    }
}



private const val KEY_CAR_MODEL_PATH = "car_model_path"

// ─── Buttons helpers (same as CarControlSettingsActivity original) ─────────────

private const val KEY_AC_DURATION = "ac_duration"
private const val KEY_AC_TEMPERATURE = "ac_temperature"
private const val KEY_SEAT_HEATING_DURATION = "seat_heating_duration"
private const val KEY_SEAT_HEATING_LEVEL = "seat_heating_level"
private fun encodeRearButtons(buttons: List<String>): String = org.json.JSONArray(buttons).toString()

private fun normalizeValidRearButtons(buttons: List<String>): List<String> {
    val allowed = RearButtonConfigDialog.CONTROL_FUNCTIONS.toSet()
    return buttons.map { it.trim() }.filter { it.isNotEmpty() && it in allowed }.distinct().take(8)
        .ifEmpty { defaultRearButtonsForFirstInstall() }
}

// ─── Button Icon Helpers (same mapping as RearScreenCarControlActivity) ─────────

/** 根据按钮功能文本获取 assets 图标资源名，与 [RearScreenCarControlActivity.getIconResourceName] 一致。 */
private fun getCarControlIconName(
    displayText: String,
    vehicleStatus: VehicleStatusService.VehicleStatusInfo? = null,
): String {
    return when (displayText) {
        "解锁" -> "ic_car_index_lock"
        "锁车" -> "ic_car_index_lock_on"
        "寻车" -> "ic_car_index_find_car"
        "点火" -> "ic_car_index_engine"
        "熄火" -> "ic_car_index_engine_on"
        "打开空调" -> "ic_ac_unit"
        "关闭空调" -> "ic_ac_unit_on"
        "开窗" -> "ic_car_index_open_window"
        "关窗" -> "ic_car_index_open_window_on"
        "透气" -> {
            val pos = vehicleStatus?.winPosDriver
            val isWindowOpen = pos != null && pos != "未知" && try { val v = pos.toInt(); v > 0 && v <= 50 } catch (_: Exception) { false }
            if (isWindowOpen) "ic_car_index_wind_on" else "ic_car_index_wind"
        }
        "开后备箱" -> "ic_car_index_trunk"
        "关后备箱" -> "ic_car_index_trunk_on"
        "打开座椅加热" -> "ic_seat_heating"
        "关闭座椅加热" -> "ic_seat_heating_on"
        "主驾加热" -> "ic_seat_heating_driver"
        "关闭主驾加热" -> "ic_seat_heating_driver_on"
        "副驾加热" -> "ic_seat_heating_passenger"
        "关闭副驾加热" -> "ic_seat_heating_passenger_on"
        else -> "ic_car_index_find_car"
    }
}
/** 从 assets 加载按钮图标位图，与背屏使用的资源一致。 */
private fun loadCarControlIcon(
    context: Context, displayText: String,
    vehicleStatus: VehicleStatusService.VehicleStatusInfo? = null,
): Bitmap? {
    val name = getCarControlIconName(displayText, vehicleStatus)
    val path = CarControlAssets.pngPath(name)
    return CarControlAssets.decodeBitmap(context, path)
}


/**
 * 根据按钮文本执行对应的车控指令，逻辑与 [CarButtonStateManager.executeCommand] 一致。
 * 通过当前 SharedPreferences 及车辆状态判断应执行"开启"还是"关闭"操作。
 */
private fun executeByText(context: Context, text: String) {
    val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
    val appCtx = context.applicationContext
    try {
        val result = when (text) {
            "锁车/解锁" -> {
                val status = VehicleStatusService.getVehicleStatus(appCtx)
                val locked = "已锁" == VehicleStatusService.translateDoorLockStatus(status.doorLockStatusDriver)
                if (locked) VehicleControlService.unlock(appCtx) else VehicleControlService.lock(appCtx)
            }
            "寻车" -> VehicleControlService.findCar(appCtx)
            "点火/熄火" -> {
                val status = VehicleStatusService.getVehicleStatus(appCtx)
                val running = "运行中" == VehicleStatusService.translateEngineStatus(status.engineStatus)
                if (running) VehicleControlService.stopEngine(appCtx) else VehicleControlService.startEngine(appCtx, 10)
            }
            "空调" -> {
                val isOn = prefs.getBoolean("ac_status", false)
                if (isOn) VehicleControlService.closeAirConditioner(appCtx)
                else {
                    val mins = prefs.getInt(KEY_AC_DURATION, 10)
                    val temp = prefs.getInt(KEY_AC_TEMPERATURE, 22)
                    VehicleControlService.openAirConditioner(appCtx, mins, temp)
                }
            }
            "开窗/关窗" -> {
                val status = VehicleStatusService.getVehicleStatus(appCtx)
                val open = "已开" == status.winStatusDriver
                if (open) VehicleControlService.closeWindow(appCtx) else VehicleControlService.openWindow(appCtx)
            }
            "透气" -> VehicleControlService.ventilate(appCtx)
            "开后备箱" -> VehicleControlService.openTrunk(appCtx)
            "座椅加热" -> {
                val isOn = prefs.getBoolean("seat_heating_status", false)
                if (isOn) VehicleControlService.closeSeatHeating(appCtx)
                else {
                    val mins = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
                    val level = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)
                    VehicleControlService.openSeatHeating(appCtx, mins, level)
                }
            }
            "主驾加热" -> {
                val isOn = prefs.getBoolean("seat_heating_status", false)
                if (isOn) VehicleControlService.closeDriverSeatHeating(appCtx)
                else {
                    val mins = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
                    val level = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)
                    VehicleControlService.openDriverSeatHeating(appCtx, mins, level)
                }
            }
            "副驾加热" -> {
                val isOn = prefs.getBoolean("seat_heating_status", false)
                if (isOn) VehicleControlService.closePassengerSeatHeating(appCtx)
                else {
                    val mins = prefs.getInt(KEY_SEAT_HEATING_DURATION, 10)
                    val level = prefs.getInt(KEY_SEAT_HEATING_LEVEL, 1)
                    VehicleControlService.openPassengerSeatHeating(appCtx, mins, level)
                }
            }
            else -> null
        }
        if (result != null && !result.success) {
            MainDisplayUi.showToast(context, result.message.ifEmpty { "执行失败" }, Toast.LENGTH_SHORT)
        }
    } catch (e: Exception) {
        MainDisplayUi.showToast(context, e.message ?: "执行失败", Toast.LENGTH_SHORT)
    }
}

/**
 * 同步车辆状态到 SharedPreferences（车门锁、发动机、车窗、后备箱、透气等）
 */
private fun refreshVehicleStatePrefs(context: Context) {
    CarControlVehiclePrefsSync.refreshFromVehicleStatus(context)
}
