package com.wmqc.miroot.car
import com.wmqc.miroot.display.MainDisplayUi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import androidx.compose.ui.unit.dp

/**
 * 车控设置（界面风格 [Miuix](https://github.com/compose-miuix-ui/miuix)）。
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

@Composable
private fun CarControlSettingsScreen(
    onReLogin: () -> Unit,
    onStartProjection: () -> Unit,
    onPickCarModel: () -> Unit,
    onResetCarModel: () -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    val pageBg = Color(ContextCompat.getColor(ctx, R.color.mi_page_bg))
    val onPagePrimary = Color(ContextCompat.getColor(ctx, R.color.mi_text_primary))
    val onPageSecondary = Color(ContextCompat.getColor(ctx, R.color.mi_text_secondary))
    val cardColors = CardColors(
        color = Color(ContextCompat.getColor(ctx, R.color.mi_card_surface)),
        contentColor = onPagePrimary,
    )
    val scheme = MiuixTheme.colorScheme

    var vehicleUi by remember { mutableStateOf<CarVehicleDisplayUi?>(null) }
    var vehicleLoading by remember { mutableStateOf(true) }
    var acDuration by remember { mutableStateOf(10) }
    var acTemp by remember { mutableStateOf(22) }
    var seatHeatingDuration by remember { mutableStateOf(10) }
    var seatHeatingLevel by remember { mutableStateOf(1) }
    var rearButtonsSummary by remember { mutableStateOf(defaultRearButtons().joinToString("、")) }
    var rearButtons by remember { mutableStateOf(defaultRearButtons()) }

    LaunchedEffect(Unit) {
        vehicleUi = withContext(Dispatchers.IO) { CarVehicleDisplayHelper.load(appCtx) }
        vehicleLoading = false
        val p = ctx.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        acDuration = p.getInt(KEY_AC_DURATION, 10)
        acTemp = p.getInt(KEY_AC_TEMPERATURE, 22)
        seatHeatingDuration = p.getInt(KEY_SEAT_HEATING_DURATION, 10)
        seatHeatingLevel = p.getInt(KEY_SEAT_HEATING_LEVEL, 1)
        rearButtons = normalizeValidRearButtons(loadRearButtons(p.getString(KEY_REAR_BUTTONS_ORDER, null)))
        rearButtonsSummary = rearButtons.joinToString("、")
    }

    fun refreshVehicleData() {
        scope.launch {
            vehicleLoading = true
            vehicleUi = withContext(Dispatchers.IO) { CarVehicleDisplayHelper.load(appCtx) }
            vehicleLoading = false
        }
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
            .putString(KEY_REAR_BUTTONS_ORDER, encodeRearButtons(normalized))
            .apply()
        rearButtons = normalized
        rearButtonsSummary = normalized.joinToString("、")
    }

    fun runControl(label: String, action: () -> VehicleControlService.ControlResult) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { action() }.getOrNull()
            withContext(Dispatchers.Main) {
                MainDisplayUi.showToast(
                    ctx,
                    when {
                        result == null -> "$label 失败"
                        result.success -> "✅ ${label}成功"
                        else -> "❌ ${label}失败: ${result.message}"
                    },
                    Toast.LENGTH_LONG,
                )
                refreshVehicleData()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg),
    ) {
        CompositionLocalProvider(LocalContentColor provides onPagePrimary) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = scrollPad, vertical = scrollPad),
                verticalArrangement = Arrangement.spacedBy(scrollPad),
            ) {
                Text(
                    text = stringResource(R.string.car_control_settings_title),
                    fontSize = pageTitleTextUnit(),
                    fontWeight = FontWeight.SemiBold,
                    color = onPagePrimary,
                )

                VehicleDataCard(
                    ui = vehicleUi,
                    loading = vehicleLoading,
                    onRefresh = { refreshVehicleData() },
                    onReLogin = onReLogin,
                    cardColors = cardColors,
                    onPagePrimary = onPagePrimary,
                    onPageSecondary = onPageSecondary,
                    errorColor = scheme.error,
                )

                ControlGroupCard(
                    title = stringResource(R.string.car_control_actions_title),
                    cardColors = cardColors,
                    collapsible = true,
                    onPageSecondary = onPageSecondary,
                ) {
                    ControlButtonRow("解锁/锁车") {
                        Button(onClick = { runControl("解锁") { VehicleControlService.unlock(ctx) } }, modifier = Modifier.weight(1f)) { Text("解锁") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("锁车") { VehicleControlService.lock(ctx) } }, modifier = Modifier.weight(1f)) { Text("锁车") }
                    }
                    ControlButtonRow("寻车/导航") {
                        Button(onClick = { runControl("寻车") { VehicleControlService.findCar(ctx) } }, modifier = Modifier.weight(1f)) { Text("寻车") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("导航到车") { VehicleControlService.navigateToCar(ctx) } }, modifier = Modifier.weight(1f)) { Text("导航到车") }
                    }
                    ControlButtonRow("点火/熄火") {
                        Button(onClick = { runControl("点火") { VehicleControlService.startEngine(ctx, 10) } }, modifier = Modifier.weight(1f)) { Text("点火") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("熄火") { VehicleControlService.stopEngine(ctx) } }, modifier = Modifier.weight(1f)) { Text("熄火") }
                    }
                    ControlButtonRow("空调") {
                        Button(onClick = { runControl("打开空调") { VehicleControlService.openAirConditioner(ctx, acDuration, acTemp) } }, modifier = Modifier.weight(1f)) { Text("开空调") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("关闭空调") { VehicleControlService.closeAirConditioner(ctx) } }, modifier = Modifier.weight(1f)) { Text("关空调") }
                    }
                    ControlButtonRow("车窗/后备箱") {
                        Button(onClick = { runControl("开窗") { VehicleControlService.openWindow(ctx) } }, modifier = Modifier.weight(1f)) { Text("开窗") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("关窗") { VehicleControlService.closeWindow(ctx) } }, modifier = Modifier.weight(1f)) { Text("关窗") }
                    }
                    ControlButtonRow("透气/后备箱") {
                        Button(onClick = { runControl("透气") { VehicleControlService.ventilate(ctx) } }, modifier = Modifier.weight(1f)) { Text("透气") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("开后备箱") { VehicleControlService.openTrunk(ctx) } }, modifier = Modifier.weight(1f)) { Text("开后备箱") }
                    }
                    ControlButtonRow("座椅加热") {
                        Button(onClick = { runControl("座椅加热") { VehicleControlService.openSeatHeating(ctx, seatHeatingDuration, seatHeatingLevel) } }, modifier = Modifier.weight(1f)) { Text("座椅加热") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("关闭座椅加热") { VehicleControlService.closeSeatHeating(ctx) } }, modifier = Modifier.weight(1f)) { Text("关闭加热") }
                    }
                    ControlButtonRow("主副驾") {
                        Button(onClick = { runControl("主驾加热") { VehicleControlService.openDriverSeatHeating(ctx, seatHeatingDuration, seatHeatingLevel) } }, modifier = Modifier.weight(1f)) { Text("主驾加热") }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = { runControl("副驾加热") { VehicleControlService.openPassengerSeatHeating(ctx, seatHeatingDuration, seatHeatingLevel) } }, modifier = Modifier.weight(1f)) { Text("副驾加热") }
                    }
                }

                ControlGroupCard(title = stringResource(R.string.car_control_ac_title), cardColors = cardColors) {
                    Text(text = "时长：${acDuration}分钟   温度：${acTemp}℃", style = MiuixTheme.textStyles.body2, color = onPageSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { persistAcConfig((acDuration - 1).coerceAtLeast(3), acTemp) }, modifier = Modifier.weight(1f)) { Text("时长-") }
                        Button(onClick = { persistAcConfig((acDuration + 1).coerceAtMost(10), acTemp) }, modifier = Modifier.weight(1f)) { Text("时长+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { persistAcConfig(acDuration, (acTemp - 1).coerceAtLeast(17)) }, modifier = Modifier.weight(1f)) { Text("温度-") }
                        Button(onClick = { persistAcConfig(acDuration, (acTemp + 1).coerceAtMost(32)) }, modifier = Modifier.weight(1f)) { Text("温度+") }
                    }
                }

                ControlGroupCard(title = stringResource(R.string.car_control_seat_heating_title), cardColors = cardColors) {
                    Text(text = "时长：${seatHeatingDuration}分钟   等级：${seatHeatingLevel}级", style = MiuixTheme.textStyles.body2, color = onPageSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { persistSeatHeatingConfig((seatHeatingDuration - 1).coerceAtLeast(3), seatHeatingLevel) }, modifier = Modifier.weight(1f)) { Text("时长-") }
                        Button(onClick = { persistSeatHeatingConfig((seatHeatingDuration + 1).coerceAtMost(10), seatHeatingLevel) }, modifier = Modifier.weight(1f)) { Text("时长+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { persistSeatHeatingConfig(seatHeatingDuration, 1) }, modifier = Modifier.weight(1f)) { Text("1级") }
                        Button(onClick = { persistSeatHeatingConfig(seatHeatingDuration, 2) }, modifier = Modifier.weight(1f)) { Text("2级") }
                    }
                }

                ControlGroupCard(title = stringResource(R.string.car_control_rear_buttons_title), cardColors = cardColors) {
                    Text(text = stringResource(R.string.car_control_rear_buttons_desc), style = MiuixTheme.textStyles.body2, color = onPageSecondary)
                    Text(text = rearButtonsSummary, style = MiuixTheme.textStyles.footnote1, color = onPagePrimary)
                    Spacer(modifier = Modifier.size(6.dp))
                    Button(
                        onClick = {
                            val act = ctx as? android.app.Activity ?: return@Button
                            RearButtonConfigDialog.show(act, rearButtons) { newList ->
                                persistRearButtons(newList)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(stringResource(R.string.car_control_rear_buttons_edit))
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, insideMargin = PaddingValues(scrollPad), colors = cardColors) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onStartProjection, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.car_control_start_projection)) }
                        Button(onClick = onPickCarModel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) { Text(stringResource(R.string.car_control_pick_car_model)) }
                        Button(onClick = onResetCarModel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) { Text(stringResource(R.string.car_control_reset_car_model)) }
                        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) { Text(stringResource(R.string.car_control_logout)) }
                    }
                }

            }
        }
    }

}

@Composable
private fun VehicleDataCard(
    ui: CarVehicleDisplayUi?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onReLogin: () -> Unit,
    cardColors: CardColors,
    onPagePrimary: Color,
    onPageSecondary: Color,
    errorColor: Color,
) {
    val scrollPad = dimensionResource(R.dimen.mi_page_scroll_padding)
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(
            horizontal = scrollPad,
            vertical = 10.dp,
        ),
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.car_control_vehicle_section_title),
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(
                    text = stringResource(R.string.car_control_vehicle_refresh),
                    onClick = onRefresh,
                    enabled = !loading,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            if (ui == null && loading) {
                Text(
                    text = stringResource(R.string.car_control_vehicle_loading),
                    style = MiuixTheme.textStyles.footnote1,
                    color = onPageSecondary,
                )
                return@Column
            }
            if (ui == null) return@Column

            ui.warningMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MiuixTheme.textStyles.footnote1,
                    color = errorColor,
                )
                if (ui.loginInvalid) {
                    TextButton(
                        text = stringResource(R.string.car_control_vehicle_relogin),
                        onClick = onReLogin,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }

            VehicleCompactCoreBlock(
                ui = ui,
                onPagePrimary = onPagePrimary,
                onPageSecondary = onPageSecondary,
            )

            if (ui.vehicleStatusRows.isNotEmpty()) {
                VehicleExpandableNamedRows(
                    title = stringResource(R.string.car_control_vehicle_subsection_status),
                    rows = ui.vehicleStatusRows,
                    onPageSecondary = onPageSecondary,
                    valueColorForRow = { onPagePrimary },
                )
            }

            if (ui.mileageEnergyRows.isNotEmpty()) {
                VehicleExpandableNamedRows(
                    title = stringResource(R.string.car_control_vehicle_subsection_mileage),
                    rows = ui.mileageEnergyRows,
                    onPageSecondary = onPageSecondary,
                    valueColorForRow = { row ->
                        if (row.label == "油量%") {
                            fuelPercentTextColor(ui.fuelPercent, onPagePrimary)
                        } else {
                            onPagePrimary
                        }
                    },
                )
            }
        }
    }
}

/** 占位或与占位同义的「无数据」不掩码，避免满屏星号。 */
private fun maskedPrivacyValue(raw: String, hideSecret: Boolean, dash: String): String {
    if (!hideSecret) return raw
    if (raw.isBlank() || raw == dash) return raw
    return "*".repeat(raw.length.coerceAtLeast(1))
}

@Composable
private fun VehicleCompactCoreBlock(
    ui: CarVehicleDisplayUi,
    onPagePrimary: Color,
    onPageSecondary: Color,
) {
    val style = MiuixTheme.textStyles.footnote1
    val dash = stringResource(R.string.car_control_vehicle_dash)
    val plateL = stringResource(R.string.car_control_vehicle_plate)
    val colorL = stringResource(R.string.car_control_vehicle_color)
    val seriesL = stringResource(R.string.car_control_vehicle_series_model)
    val vinL = stringResource(R.string.car_control_vehicle_vin)
    val rangeL = stringResource(R.string.car_control_vehicle_range)
    val fuelL = stringResource(R.string.car_control_vehicle_fuel_pct)
    val updatedL = stringResource(R.string.car_control_vehicle_updated)

    var plateHidden by remember { mutableStateOf(false) }
    var vinHidden by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { plateHidden = !plateHidden },
            text = "$plateL ${maskedPrivacyValue(ui.plateNo, plateHidden, dash)}  ·  $colorL ${ui.colorName}",
            style = style,
            color = onPagePrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$seriesL ${ui.seriesModel}",
            style = style,
            color = onPagePrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vinHidden = !vinHidden },
            text = "$vinL ${maskedPrivacyValue(ui.vin, vinHidden, dash)}",
            style = style,
            color = onPagePrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rangeL ${ui.rangeKmText}",
                style = style,
                color = onPagePrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 6.dp),
            ) {
                Text(text = "$fuelL ", style = style, color = onPageSecondary)
                Text(
                    text = ui.fuelPercentText,
                    style = style,
                    color = fuelPercentTextColor(ui.fuelPercent, onPagePrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${ui.interiorTempText}  ·  ${ui.exteriorTempText}  ·  $updatedL ${ui.updateTimeShort}",
            style = style,
            color = onPageSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VehicleExpandableNamedRows(
    title: String,
    rows: List<CarNamedDisplayRow>,
    onPageSecondary: Color,
    valueColorForRow: (CarNamedDisplayRow) -> Color,
) {
    val collapseByDefault = rows.size > 3
    var expanded by remember(rows.size) { mutableStateOf(!collapseByDefault) }

    val headerStyle = MiuixTheme.textStyles.footnote1
    val countStr = stringResource(R.string.car_control_vehicle_item_count_fmt, rows.size)
    val toggleStr = stringResource(
        if (expanded) {
            R.string.car_control_vehicle_collapse
        } else {
            R.string.car_control_vehicle_expand
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append(title)
                append(" · ")
                append(countStr)
                append(" · ")
                append(toggleStr)
            },
            style = headerStyle,
            color = onPageSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (expanded) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            rows.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { row ->
                        VehicleNamedRowMiniCell(
                            row = row,
                            valueColor = valueColorForRow(row),
                            onPageSecondary = onPageSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleNamedRowMiniCell(
    row: CarNamedDisplayRow,
    valueColor: Color,
    onPageSecondary: Color,
    modifier: Modifier = Modifier,
) {
    val style = MiuixTheme.textStyles.footnote1
    Column(modifier = modifier) {
        Text(
            text = row.label,
            style = style,
            color = onPageSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.value,
            style = style,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun fuelPercentTextColor(fuelPercent: Int, default: Color): Color {
    if (fuelPercent < 0) return default
    return when {
        fuelPercent < 10 -> Color(0xFFFF0000)
        fuelPercent < 30 -> Color(0xFF2196F3)
        else -> Color(0xFFFF9800)
    }
}

/** 与音乐页、[TextAppearance.MiRoot.PageTitle]（`mi_page_title_text_size`）一致的大标题字号。 */
@Composable
private fun pageTitleTextUnit(): TextUnit {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val px = ctx.resources.getDimension(R.dimen.mi_page_title_text_size)
    return (px / (d.density * d.fontScale)).sp
}

private const val KEY_AC_DURATION = "ac_duration"
private const val KEY_AC_TEMPERATURE = "ac_temperature"
private const val KEY_SEAT_HEATING_DURATION = "seat_heating_duration"
private const val KEY_SEAT_HEATING_LEVEL = "seat_heating_level"
private const val KEY_REAR_BUTTONS_ORDER = "rear_buttons_order"

private fun defaultRearButtons(): List<String> = defaultRearButtonsForFirstInstall()

private fun loadRearButtons(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return defaultRearButtons()
    return runCatching {
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }.getOrElse { defaultRearButtons() }.ifEmpty { defaultRearButtons() }
}

private fun encodeRearButtons(buttons: List<String>): String = org.json.JSONArray(buttons).toString()

private fun normalizeValidRearButtons(buttons: List<String>): List<String> {
    val allowed = RearButtonConfigDialog.CONTROL_FUNCTIONS.toSet()
    return buttons.map { it.trim() }.filter { it.isNotEmpty() && it in allowed }.distinct().take(8)
        .ifEmpty { defaultRearButtons() }
}

@Composable
private fun ControlGroupCard(
    title: String,
    cardColors: CardColors,
    collapsible: Boolean = false,
    onPageSecondary: Color = Color.Unspecified,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
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
            if (collapsible) {
                var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
                val toggleLabel = stringResource(
                    if (expanded) R.string.car_control_vehicle_collapse else R.string.car_control_vehicle_expand,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = toggleLabel,
                        style = MiuixTheme.textStyles.footnote1,
                        color = onPageSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (expanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            } else {
                Text(text = title, style = MiuixTheme.textStyles.subtitle)
                content()
            }
        }
    }
}

@Composable
private fun ControlButtonRow(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MiuixTheme.textStyles.footnote1, color = Color.Unspecified)
        Row(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val onPageSecondary = Color(
        ContextCompat.getColor(
            androidx.compose.ui.platform.LocalContext.current,
            R.color.mi_text_secondary,
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, style = MiuixTheme.textStyles.body1)
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote1,
                color = onPageSecondary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
