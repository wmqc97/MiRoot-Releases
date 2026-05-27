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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * 车控设置（界面风格 [Miuix](https://github.com/compose-miuix-ui/miuix)）。
 * 主界面为现代车控仪表盘风格：车模图片 + 车辆信息 + 底部双列按钮 + 二级设置页。
 */
class CarControlSettingsActivity : ComponentActivity() {

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
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                CarControlSettingsScreen(
                    onReLogin = {
                        startActivity(
                            Intent(this, CarControlLoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            },
                        )
                        finish()
                    },
                    onStartProjection = { startCarControlProjection() },
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
            }
        }.start()
    }

    private fun resetCarModelImage() {
        getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CAR_MODEL_PATH)
            .apply()
        val f = File(filesDir, "car_model.png")
        if (f.exists()) f.delete()
        MainDisplayUi.showToast(this, R.string.car_control_car_model_reset, Toast.LENGTH_LONG)
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

/** 屏幕状态 */
private enum class Screen { DASHBOARD, SETTINGS }

@Composable
private fun CarControlSettingsScreen(
    onReLogin: () -> Unit,
    onStartProjection: () -> Unit,
    onPickCarModel: () -> Unit,
    onResetCarModel: () -> Unit,
    onLogout: () -> Unit,
) {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    when (currentScreen) {
        Screen.DASHBOARD -> DashboardScreen(
            onNavigateToSettings = { currentScreen = Screen.SETTINGS },
            onReLogin = onReLogin,
        )
        Screen.SETTINGS -> SettingsSubScreen(
            onBack = { currentScreen = Screen.DASHBOARD },
            onStartProjection = onStartProjection,
            onPickCarModel = onPickCarModel,
            onResetCarModel = onResetCarModel,
            onLogout = onLogout,
        )
    }
}

// ─── Dashboard ─────────────────────────────────────────────────────────────────

@Composable
private fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onReLogin: () -> Unit,
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))

    var vehicleUi by remember { mutableStateOf<CarVehicleDisplayUi?>(null) }
    var vehicleLoading by remember { mutableStateOf(true) }

    // 车模图片 bitmap
    var carModelBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var carModelLoading by remember { mutableStateOf(true) }

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

    // 加载车辆数据
    LaunchedEffect(Unit) {
        vehicleUi = withContext(Dispatchers.IO) { CarVehicleDisplayHelper.load(appCtx) }
        vehicleLoading = false
    }

    // 加载车模图片
    LaunchedEffect(Unit) {
        carModelBitmap = withContext(Dispatchers.IO) { loadCarModelBitmap(appCtx) }
        carModelLoading = false
    }

    // 加载底部按钮配置
    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = loadRearButtonsForDashboard(p.getString(KEY_DASHBOARD_REAR_BUTTONS, null))
        rearButtons = saved
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
    // 初始加载时同步车辆状态到 SharedPreferences
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { refreshVehicleStatePrefs(ctx) }
    }

    // 计算总页数（每页4个按钮，2列2行）
    val totalPages = ((rearButtons.size + 3) / 4).coerceIn(1, 2)

    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    fun refreshVehicleData() {
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            val ui = CarVehicleDisplayHelper.load(appCtx)
            try {
                val status = VehicleStatusService.getVehicleStatus(appCtx)
                refreshVehicleStatePrefs(ctx)
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                vehicleUi = ui
                vehicleLoading = false
                isRefreshing = false
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
                onMenuClick = onNavigateToSettings,
            )

                        // -- 续航/电量 --
            RangeSection(
                ui = vehicleUi,
                loading = vehicleLoading,
                onRefresh = { refreshVehicleData() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(8.dp))

            // -- 车模图片 --
            CarModelSection(
                bitmap = carModelBitmap,
                loading = carModelLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scrollPad),
            )

            Spacer(Modifier.size(12.dp))

            // -- 页面指示器 --
            RearButtonGrid(
                buttons = rearButtons,
                pageIndex = currentPage,
                onPageChange = { currentPage = it },
                totalPages = totalPages,
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
Spacer(Modifier.size(24.dp))
        }
    }
}

// ─── TopBar ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    title: String,
    onMenuClick: () -> Unit,
) {
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val ctx = LocalContext.current
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
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
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMenuClick) {
            Text(
                text = "≡",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = onPagePrimary,
            )
        }
    }
}

// ─── CarModelSection ───────────────────────────────────────────────────────────

/**
 * 车模展示区域：大图居中展示。
 */
@Composable
private fun CarModelSection(
    bitmap: Bitmap?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (loading) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    } else {
        val painter = if (bitmap != null) {
            BitmapPainter(bitmap.asImageBitmap())
        } else {
            painterResource(R.drawable.ic_car_control_refresh)
        }
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painter,
                contentDescription = "车模",
                modifier = Modifier.fillMaxWidth(0.78f),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// --- RangeSection ---
/**
 * Range and battery status section.
 */
@Composable
private fun RangeSection(
    ui: CarVehicleDisplayUi?,
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val LightGray = Color(0xFF999999)
    if (loading || ui == null) return

    val rangeKm = ui.rangeKmText
    val fuelPct = ui.fuelPercent.coerceIn(0, 100)
    val progress = (fuelPct.toFloat() / 100f).coerceIn(0f, 1f)
    val Blue = Color(0xFF1976D2)
    val fuelVolRow = ui.mileageEnergyRows.find { it.label == "油量" }
    val fuelVolText = fuelVolRow?.value ?: "--"
    val updateTime = ui.updateTimeShort.ifEmpty { "--" }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rangeKm.replace(Regex("[a-zA-Z]"), "").trim(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = onPagePrimary,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "km",
                fontSize = 14.sp,
                color = onPagePrimary,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$fuelPct%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Blue,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .width(120.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Blue,
            trackColor = Color(0xFFE0E0E0),
        )
        Spacer(Modifier.size(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = updateTime,
                fontSize = 10.sp,
                color = LightGray,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = fuelVolText,
                fontSize = 10.sp,
                color = LightGray,
                maxLines = 1,
            )
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行 + 参数 + 状态（长按 1000ms 开启/关闭）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            try {
                                withTimeout(CAR_CONTROL_HOLD_TO_EXECUTE_MS) {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                    } while (event.changes.any { it.pressed })
                                }
                            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                scope.launch { onToggle() }
                            }
                        }
                    },
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

// ─── SeatHeatingControlCard ─────────────────────────────────────────────────────

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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行 + 参数 + 状态（长按 1000ms 开启/关闭）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            try {
                                withTimeout(CAR_CONTROL_HOLD_TO_EXECUTE_MS) {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                    } while (event.changes.any { it.pressed })
                                }
                            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                scope.launch { onToggle() }
                            }
                        }
                    },
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
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val displayText = resolveDisplayText(ctx, text)
    val isAlert = isButtonAlertState(ctx, text)

    val bg = if (isAlert) {
        if (isDark) Color(0xFF5A5A5A) else Color(0xFF81D4FA)
    } else {
        if (isDark) Color(0xFF2E2E2E) else Color(0xFFE8E8E8)
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

    LaunchedEffect(text) {
        iconBitmap = withContext(Dispatchers.IO) { loadCarControlIcon(ctx, text) }
    }

    Column(
        modifier = modifier
            .pointerInput(text) {
                detectTapGestures(
                    onDoubleTap = {
                        scope.launch(Dispatchers.IO) {
                            executeByText(ctx, text)
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(bg),
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
private fun resolveDisplayText(context: Context, text: String): String {
    val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
    return when (text) {
        "锁车/解锁" -> if (prefs.getBoolean("is_locked", false)) "解锁" else "锁车"
        "点火/熄火" -> if (prefs.getBoolean("is_engine_on", false)) "熄火" else "点火"
        "开窗/关窗" -> if (prefs.getBoolean("is_window_open", false)) "关窗" else "开窗"
        else -> text
    }
}

/**
 * 判断按钮是否应显示为"可操作"状态（蓝色底）。
 * 蓝色表示可执行的操作（与当前车辆状态相反），浅灰表示当前状态/不可操作。
 * 逻辑与 [RearScreenCarControlActivity.isCarControlAlertSecondaryState] 一致。
 * 从 SharedPreferences 读取车辆各功能状态（背屏通过 WebSocket 实时更新这些状态）。
 */
private fun isButtonAlertState(context: Context, text: String): Boolean {
    val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
    return when (text) {
        "寻车" -> false
        "锁车/解锁" -> prefs.getBoolean("is_locked", false)  // 已锁->蓝色(可解锁)
        "点火/熄火" -> prefs.getBoolean("is_engine_on", false)  // 运行中->蓝色(可熄火)
        "开窗/关窗" -> prefs.getBoolean("is_window_open", false)  // 已开->蓝色(可关窗)
        "空调" -> !prefs.getBoolean("ac_status", false)
        "座椅加热" -> !prefs.getBoolean("seat_heating_status", false)
        "主驾加热" -> !prefs.getBoolean("seat_heating_status", false)
        "副驾加热" -> !prefs.getBoolean("seat_heating_status", false)
        "开后备箱" -> !prefs.getBoolean("is_trunk_open", false)
        "透气" -> !prefs.getBoolean("is_vent_mode", false)
        else -> false
    }
}

// ─── Settings Sub-Screen（二级设置页） ─────────────────────────────────────────

@Composable
private fun SettingsSubScreen(
    onBack: () -> Unit,
    onStartProjection: () -> Unit,
    onPickCarModel: () -> Unit,
    onResetCarModel: () -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = LocalContext.current
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardColors = CardColors(
        color = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface)),
        contentColor = onPagePrimary,
    )

    var acDuration by remember { mutableStateOf(10) }
    var acTemp by remember { mutableStateOf(22) }
    var seatHeatingDuration by remember { mutableStateOf(10) }
    var seatHeatingLevel by remember { mutableStateOf(1) }
    var rearButtons by remember { mutableStateOf(defaultRearButtonsForFirstInstall()) }
    var rearButtonsSummary by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        acDuration = p.getInt(KEY_AC_DURATION, 10)
        acTemp = p.getInt(KEY_AC_TEMPERATURE, 22)
        seatHeatingDuration = p.getInt(KEY_SEAT_HEATING_DURATION, 10)
        seatHeatingLevel = p.getInt(KEY_SEAT_HEATING_LEVEL, 1)
        val saved = loadRearButtonsForDashboard(p.getString(KEY_DASHBOARD_REAR_BUTTONS, null))
        rearButtons = saved
        rearButtonsSummary = saved.joinToString("、")
    }

    fun persistAcConfig(duration: Int, temp: Int) {
        ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AC_DURATION, duration)
            .putInt(KEY_AC_TEMPERATURE, temp)
            .apply()
        acDuration = duration
        acTemp = temp
    }

    fun persistSeatHeatingConfig(duration: Int, level: Int) {
        ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SEAT_HEATING_DURATION, duration)
            .putInt(KEY_SEAT_HEATING_LEVEL, level)
            .apply()
        seatHeatingDuration = duration
        seatHeatingLevel = level
    }

    fun persistRearButtons(buttons: List<String>) {
        val normalized = normalizeValidRearButtons(buttons)
        ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DASHBOARD_REAR_BUTTONS, encodeRearButtons(normalized))
            .apply()
        rearButtons = normalized
        rearButtonsSummary = normalized.joinToString("、")
    }

    Box(modifier = Modifier.fillMaxSize().background(pageBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = scrollPad, vertical = scrollPad),
            verticalArrangement = Arrangement.spacedBy(scrollPad),
        ) {
            // 标题行 + 返回按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    fontSize = 22.sp,
                    color = onPagePrimary,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = 12.dp),
                )
                Text(
                    text = stringResource(R.string.car_control_settings_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onPagePrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── 空调设置 ──
            ControlGroupCard(
                title = stringResource(R.string.car_control_ac_title),
                cardColors = cardColors,
                onPageSecondary = onPageSecondary,
            ) {
                Text(
                    text = "${stringResource(R.string.car_control_ac_duration)}：${acDuration}分钟    ${stringResource(R.string.car_control_ac_temp)}：${acTemp}℃",
                    style = MiuixTheme.textStyles.body2,
                    color = onPageSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { persistAcConfig((acDuration - 1).coerceAtLeast(3), acTemp) }, modifier = Modifier.weight(1f)) { Text("时长-") }
                    Button(onClick = { persistAcConfig((acDuration + 1).coerceAtMost(10), acTemp) }, modifier = Modifier.weight(1f)) { Text("时长+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { persistAcConfig(acDuration, (acTemp - 1).coerceAtLeast(17)) }, modifier = Modifier.weight(1f)) { Text("温度-") }
                    Button(onClick = { persistAcConfig(acDuration, (acTemp + 1).coerceAtMost(32)) }, modifier = Modifier.weight(1f)) { Text("温度+") }
                }
            }

            // ── 座椅加热设置 ──
            ControlGroupCard(
                title = stringResource(R.string.car_control_seat_heating_title),
                cardColors = cardColors,
                onPageSecondary = onPageSecondary,
            ) {
                Text(
                    text = "${stringResource(R.string.car_control_seat_heating_duration)}：${seatHeatingDuration}分钟    ${stringResource(R.string.car_control_seat_heating_level)}：${seatHeatingLevel}级",
                    style = MiuixTheme.textStyles.body2,
                    color = onPageSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { persistSeatHeatingConfig((seatHeatingDuration - 1).coerceAtLeast(3), seatHeatingLevel) }, modifier = Modifier.weight(1f)) { Text("时长-") }
                    Button(onClick = { persistSeatHeatingConfig((seatHeatingDuration + 1).coerceAtMost(10), seatHeatingLevel) }, modifier = Modifier.weight(1f)) { Text("时长+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { persistSeatHeatingConfig(seatHeatingDuration, 1) }, modifier = Modifier.weight(1f)) { Text("1级") }
                    Button(onClick = { persistSeatHeatingConfig(seatHeatingDuration, 2) }, modifier = Modifier.weight(1f)) { Text("2级") }
                }
            }

            // ── 背屏按钮配置 ──
            ControlGroupCard(
                title = stringResource(R.string.car_control_rear_buttons_title),
                cardColors = cardColors,
                onPageSecondary = onPageSecondary,
            ) {
                Text(
                    text = stringResource(R.string.car_control_rear_buttons_desc),
                    style = MiuixTheme.textStyles.body2,
                    color = onPageSecondary,
                )
                Text(
                    text = rearButtonsSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = onPagePrimary,
                )
                Spacer(Modifier.size(6.dp))
                Button(
                    onClick = {
                        val act = ctx as? android.app.Activity ?: return@Button
                        RearButtonConfigDialog.show(act, rearButtons) { newList ->
                            persistRearButtons(newList)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.car_control_rear_buttons_edit)) }
            }

            // ── 启动投屏和车模设置 ──
            ControlGroupCard(
                title = "启动投屏和车模设置",
                cardColors = cardColors,
                onPageSecondary = onPageSecondary,
            ) {
                Button(
                    onClick = onStartProjection,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.car_control_start_projection)) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onPickCarModel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text(stringResource(R.string.car_control_pick_car_model), fontSize = 13.sp) }
                    Button(
                        onClick = onResetCarModel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(),
                    ) { Text(stringResource(R.string.car_control_reset_car_model), fontSize = 13.sp) }
                }
            }

            // ── 退出登录 ──
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) { Text(stringResource(R.string.car_control_logout)) }

            Spacer(Modifier.size(24.dp))
        }
    }
}

// ─── Reusable Components ───────────────────────────────────────────────────────

@Composable
private fun ControlGroupCard(
    title: String,
    cardColors: CardColors,
    onPageSecondary: Color = Color.Unspecified,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(dimensionResource(R.dimen.mi_page_scroll_padding)),
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MiuixTheme.textStyles.subtitle)
            content()
        }
    }
}

// ─── Car Model Image Loading (ported from RearScreenCarControlActivity) ────────

/**
 * 加载车模图片：自定义路径 → assets/car/xingrui.webp → drawable xingrui → null。
 * 与 [RearScreenCarControlActivity.loadCarModelImage] 逻辑一致。
 */
private fun loadCarModelBitmap(context: Context): Bitmap? {
    val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)

    // 1) 自定义车模
    val customPath = prefs.getString(KEY_CAR_MODEL_PATH, null)
    if (customPath != null) {
        val f = File(customPath)
        if (f.exists()) {
            return try {
                BitmapFactory.decodeStream(FileInputStream(f))
            } catch (_: Exception) { null }
        }
    }

    // 2) assets/car/xingrui.webp
    val assetPath = CarControlAssets.webpPath("xingrui")
    if (CarControlAssets.exists(context, assetPath)) {
        return CarControlAssets.decodeBitmap(context, assetPath)
    }

    // 3) drawable xingrui
    val resId = context.resources.getIdentifier("xingrui", "drawable", context.packageName)
    if (resId != 0) {
        return try {
            BitmapFactory.decodeResource(context.resources, resId)
        } catch (_: Exception) { null }
    }

    return null
}

private const val KEY_CAR_MODEL_PATH = "car_model_path"

// ─── Buttons helpers (same as CarControlSettingsActivity original) ─────────────

private const val KEY_AC_DURATION = "ac_duration"
private const val KEY_AC_TEMPERATURE = "ac_temperature"
private const val KEY_SEAT_HEATING_DURATION = "seat_heating_duration"
private const val KEY_SEAT_HEATING_LEVEL = "seat_heating_level"
private const val KEY_DASHBOARD_REAR_BUTTONS = "rear_buttons_order"

private fun loadRearButtonsForDashboard(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return defaultRearButtonsForFirstInstall()
    return runCatching {
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }.getOrElse { defaultRearButtonsForFirstInstall() }.ifEmpty { defaultRearButtonsForFirstInstall() }
}

private fun encodeRearButtons(buttons: List<String>): String = org.json.JSONArray(buttons).toString()

private fun normalizeValidRearButtons(buttons: List<String>): List<String> {
    val allowed = RearButtonConfigDialog.CONTROL_FUNCTIONS.toSet()
    return buttons.map { it.trim() }.filter { it.isNotEmpty() && it in allowed }.distinct().take(8)
        .ifEmpty { defaultRearButtonsForFirstInstall() }
}

// ─── Button Icon Helpers (same mapping as RearScreenCarControlActivity) ─────────

/** 根据按钮功能文本获取 assets 图标资源名，与 [RearScreenCarControlActivity.getIconResourceName] 一致。 */
private fun getCarControlIconName(text: String): String = when (text) {
    "锁车/解锁" -> "ic_car_index_lock"
    "寻车" -> "ic_car_index_find_car"
    "点火/熄火" -> "ic_car_index_engine"
    "空调" -> "ic_ac_unit"
    "开窗/关窗" -> "ic_car_index_open_window"
    "透气" -> "ic_car_index_wind"
    "开后备箱" -> "ic_car_index_trunk"
    "座椅加热" -> "ic_seat_heating"
    "主驾加热" -> "ic_seat_heating_driver"
    "副驾加热" -> "ic_seat_heating_passenger"
    else -> "ic_car_index_find_car"
}

/** 从 assets 加载按钮图标位图，与背屏使用的资源一致。 */
private fun loadCarControlIcon(context: Context, text: String): Bitmap? {
    val name = getCarControlIconName(text)
    val path = CarControlAssets.pngPath(name)
    return CarControlAssets.decodeBitmap(context, path)
}

private const val CAR_CONTROL_HOLD_TO_EXECUTE_MS = 1000L

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
    val appCtx = context.applicationContext
    try {
        val status = VehicleStatusService.getVehicleStatus(appCtx)
        context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("is_locked", "已锁" == VehicleStatusService.translateDoorLockStatus(status.doorLockStatusDriver))
            .putBoolean("is_engine_on", "运行中" == VehicleStatusService.translateEngineStatus(status.engineStatus))
            .putBoolean("is_window_open", "已开" == status.winStatusDriver)
            .putBoolean("is_trunk_open", "已开" == VehicleStatusService.translateTrunkStatus(status.trunkOpenStatus))
            .putBoolean("is_vent_mode", isVentPosition(status.winPosDriver))
            .apply()
    } catch (_: Exception) { }
}

/** 判断车窗位置值是否处于透气模式（一条缝）：0=关闭，1~50=透气，>50=全开 */
private fun isVentPosition(winPosDriver: String?): Boolean {
    if (winPosDriver.isNullOrEmpty() || winPosDriver == "未知") return false
    return try {
        val pos = winPosDriver.toInt()
        pos > 0 && pos <= 50
    } catch (_: NumberFormatException) { false }
}
