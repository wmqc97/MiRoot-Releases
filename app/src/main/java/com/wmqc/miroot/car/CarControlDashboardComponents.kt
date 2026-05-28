package com.wmqc.miroot.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.wmqc.miroot.R
import com.wmqc.miroot.car.VehicleStatusService.VehicleStatusInfo
import kotlin.math.roundToInt
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modern car dashboard UI components for CarControlSettingsActivity.
 * These composables provide a mainstream car control app look with:
 * - Animated fuel gauge + car image + range display
 * - Temperature and battery status chips
 * - Quick control buttons with state-aware styling
 * - Vehicle status summary
 * - Tire pressure monitoring
 * - Mileage and energy consumption
 * - AMap-based vehicle location map
 */

// ===== Theme-Aware Color Palette =====
data class CarColorPalette(
    val bgPage: Color,
    val bgCard: Color,
    val accentBlue: Color,
    val accentGreen: Color,
    val accentOrange: Color,
    val accentRed: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val gaugeBg: Color,
    val btnActiveBg: Color,
    val btnActiveBo: Color,
    val mapBg: Color,
    val tireNorm: Color,
    val tireWarn: Color,
    val tireDanger: Color,
    val gradientTop: Color,
)

private val DarkCarColors = CarColorPalette(
    bgPage       = Color(0xFF1a1d23),
    bgCard       = Color(0xFF23262d),
    accentBlue   = Color(0xFF3482ff),
    accentGreen  = Color(0xFF34c759),
    accentOrange = Color(0xFFff9500),
    accentRed    = Color(0xFFff3b30),
    textPrimary  = Color(0xFFf0f0f0),
    textSecondary= Color(0xFF8a8f98),
    divider      = Color(0xFF2f323a),
    gaugeBg      = Color(0xFF2a2d35),
    btnActiveBg  = Color(0xFF3482ff).copy(alpha = 0.2f),
    btnActiveBo  = Color(0xFF3482ff),
    mapBg        = Color(0xFF1e2128),
    tireNorm     = Color(0xFF34c759),
    tireWarn     = Color(0xFFff9500),
    tireDanger   = Color(0xFFff3b30),
    gradientTop  = Color(0xFF1e2a3a),
)

private val LightCarColors = CarColorPalette(
    bgPage       = Color(0xFFF2F3F5),
    bgCard       = Color(0xFFFFFFFF),
    accentBlue   = Color(0xFF2563EB),
    accentGreen  = Color(0xFF16A34A),
    accentOrange = Color(0xFFEA580C),
    accentRed    = Color(0xFFDC2626),
    textPrimary  = Color(0xFF1A1D23),
    textSecondary= Color(0xFF6B7280),
    divider      = Color(0xFFE5E7EB),
    gaugeBg      = Color(0xFFE5E7EB),
    btnActiveBg  = Color(0xFF2563EB).copy(alpha = 0.12f),
    btnActiveBo  = Color(0xFF2563EB),
    mapBg        = Color(0xFFE5E7EB),
    tireNorm     = Color(0xFF16A34A),
    tireWarn     = Color(0xFFEA580C),
    tireDanger   = Color(0xFFDC2626),
    gradientTop  = Color(0xFFEEF2FF),
)

/** Returns the appropriate [CarColorPalette] for the current system theme. */
@Composable
fun carColors(): CarColorPalette =
    if (isSystemInDarkTheme()) DarkCarColors else LightCarColors

/** Backwards-compatible alias so existing code can keep using CarUiColors.xxx during transition. */
@Composable
fun CarUiColors(): CarColorPalette = carColors()

private data class TireLabel(val label: String, val pressure: String?, val warning: String?)

// ====================================================================
//  1. Car Image & Gauges — fuel gauge arc + car model + range
// ====================================================================
@Composable
fun CarImageAndGaugesSection(
    vehicleUi: CarVehicleDisplayUi?,
    carModelBitmap: Bitmap?,
    loading: Boolean,
    onPickCarModel: (() -> Unit)? = null,
    onResetCarModel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val CarUiColors = carColors()
    val fuelPct = vehicleUi?.fuelPercent ?: -1
    val rangeText = vehicleUi?.rangeKmText ?: "---"
    val updateTime = vehicleUi?.updateTimeShort ?: ""

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: circular fuel gauge with animated arc
            Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(80.dp)) {
                    val sweep = 270f
                    val start = 135f
                    val strokeStyle = Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(CarUiColors.gaugeBg, start, sweep, useCenter = false, style = strokeStyle)
                    if (fuelPct >= 0) {
                        val pct = (fuelPct / 100f).coerceIn(0f, 1f)
                        val c = when {
                            fuelPct > 50 -> CarUiColors.accentGreen
                            fuelPct > 20 -> CarUiColors.accentOrange
                            else -> CarUiColors.accentRed
                        }
                        drawArc(c, start, sweep * pct, useCenter = false, style = strokeStyle)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (fuelPct >= 0) "${fuelPct}%" else "---",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CarUiColors.textPrimary
                    )
                    Text("油量", fontSize = 9.sp, color = CarUiColors.textSecondary)
                }
            }

            Spacer(Modifier.width(12.dp))

            // Center: car model image (long press to change)
            var showModelDialog by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier.weight(1f).height(80.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { showModelDialog = true })
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (!loading && carModelBitmap != null) {
                    Image(
                        bitmap = carModelBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = CarUiColors.accentBlue, strokeWidth = 2.dp)
                } else {
                    Text("🚗", fontSize = 48.sp)
                }
            }

            // 车模选择弹窗
            if (showModelDialog) {
                AlertDialog(
                    onDismissRequest = { showModelDialog = false },
                    title = { Text("车模图片", fontWeight = FontWeight.SemiBold) },
                    text = { Text("选择自定义图片或恢复默认车模") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showModelDialog = false
                            onPickCarModel?.invoke()
                        }) { Text("选择图片") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showModelDialog = false
                            onResetCarModel?.invoke()
                        }) { Text("恢复默认") }
                    },
                )
            }

            Spacer(Modifier.width(12.dp))

            // Right: range display
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(70.dp)) {
                Text("续航", fontSize = 10.sp, color = CarUiColors.textSecondary)
                Text(
                    text = rangeText,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = CarUiColors.accentGreen,
                    textAlign = TextAlign.Center
                )
                if (updateTime.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("更新 ${updateTime}", fontSize = 8.sp, color = CarUiColors.textSecondary)
                }
            }
        }
    }
}

// ====================================================================
//  2. Temperature & Battery Row
// ====================================================================
@Composable
fun TempBatteryRow(
    vehicleUi: CarVehicleDisplayUi?,
    vehicleStatus: VehicleStatusInfo?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 电瓶电压 + 电量
        val voltage = vehicleStatus?.voltage ?: ""
        val charge = vehicleStatus?.stateOfCharge ?: ""
        val batText = when {
            voltage.isNotEmpty() && voltage != "未知" -> {
                if (charge.isNotEmpty() && charge != "未知") "${voltage}V · ${charge}%"
                else "${voltage}V"
            }
            else -> "---"
        }

        // 平均车速
        val avgRaw = vehicleStatus?.avgSpeed ?: ""
        val avgSpeedTxt = if (avgRaw.isNotEmpty() && avgRaw != "未知") {
            runCatching { avgRaw.toDouble() }.getOrNull()?.let { "%.0f km/h".format(it) } ?: avgRaw
        } else "---"

        // 车内 + 车外温度合并
        val interiorT = vehicleUi?.interiorTempText?.replace(Regex("[^\\d.-]"), "") ?: ""
        val exteriorT = vehicleUi?.exteriorTempText?.replace(Regex("[^\\d.-]"), "") ?: ""
        val tempText = when {
            interiorT.isNotEmpty() && exteriorT.isNotEmpty() -> "${interiorT}° / ${exteriorT}°"
            interiorT.isNotEmpty() -> "${interiorT}°"
            exteriorT.isNotEmpty() -> "${exteriorT}°"
            else -> "---"
        }

        StatusChip("平均车速", avgSpeedTxt, Modifier.weight(1f))
        StatusChip("内/外温", tempText, Modifier.weight(1f))
        StatusChip("电瓶", batText, Modifier.weight(1f))
    }
}

@Composable
private fun StatusChip(label: String, value: String, modifier: Modifier = Modifier) {
    val CarUiColors = carColors()
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = CarUiColors.textPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    label, fontSize = 9.sp, color = CarUiColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ====================================================================
//  3. Quick Control Buttons (2x4 grid with page indicator)
// ====================================================================
@Composable
fun QuickControlButtons(
    buttons: List<String>,
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    totalPages: Int,
    vehicleStatus: VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
    onButtonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val CarUiColors = carColors()
        val start = pageIndex * 4
        val fullPage = buttons.drop(start).take(4) + List(4 - (buttons.drop(start).take(4).size).coerceAtMost(4)) { "" }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fullPage.take(2).forEach { text ->
                ControlButtonCell(text, vehicleStatus, acStatus, seatHeatingStatus,
                    { if (text.isNotEmpty()) onButtonClick(text) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fullPage.drop(2).forEach { text ->
                ControlButtonCell(text, vehicleStatus, acStatus, seatHeatingStatus,
                    { if (text.isNotEmpty()) onButtonClick(text) }, Modifier.weight(1f))
            }
        }

        if (totalPages > 1) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until totalPages) {
                    Box(
                        modifier = Modifier
                            .size(if (i == pageIndex) 16.dp else 6.dp, 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i == pageIndex) CarUiColors.accentBlue else CarUiColors.divider)
                            .clickable { onPageChange(i) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButtonCell(
    text: String,
    vehicleStatus: VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) { Box(modifier = modifier.height(72.dp)); return }

    val CarUiColors = carColors()
    val isActive = isButtonAlertState(text, vehicleStatus, acStatus, seatHeatingStatus)
    val displayText = resolveDisplayText(text, vehicleStatus, acStatus, seatHeatingStatus)

    Card(
        modifier = modifier.height(72.dp).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) CarUiColors.btnActiveBg else CarUiColors.bgCard
        ),
        border = BorderStroke(1.dp, if (isActive) CarUiColors.btnActiveBo else CarUiColors.divider)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(controlButtonIcon(text), fontSize = 20.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = displayText, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (isActive) CarUiColors.accentBlue else CarUiColors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun controlButtonIcon(text: String): String = when {
    text.contains("锁") || text.contains("解锁") -> "🔒"
    text.contains("寻车") -> "🔦"
    text.contains("点火") || text.contains("熄火") -> "⚡"
    text.contains("空调") -> "❄"
    text.contains("窗") -> "🪟"
    text.contains("透气") -> "💨"
    text.contains("后备箱") || text.contains("尾箱") -> "🚪"
    text.contains("座椅加热") || text.contains("加热") -> "🔥"
    text.contains("导航") -> "🗺"
    else -> "⚙"
}

// ====================================================================
//  4. Vehicle Status Summary
// ====================================================================
@Composable
fun VehicleStatusSummary(
    vehicleUi: CarVehicleDisplayUi?,
    vehicleStatus: VehicleStatusInfo?,
    modifier: Modifier = Modifier,
) {
    val CarUiColors = carColors()
    val rows = vehicleUi?.vehicleStatusRows ?: emptyList()
    if (rows.isEmpty() && vehicleStatus == null) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚘", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("车辆状态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarUiColors.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            if (rows.isNotEmpty()) {
                for (chunk in rows.chunked(3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        chunk.forEach { row ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(row.label, fontSize = 10.sp, color = CarUiColors.textSecondary)
                                Text(row.value, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = CarUiColors.textPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

// ====================================================================
//  5. Tire Pressure Section
// ====================================================================
@Composable
fun TirePressureSection(
    vehicleStatus: VehicleStatusInfo?,
    modifier: Modifier = Modifier,
) {
    val CarUiColors = carColors()
    if (vehicleStatus == null) return

    val tires = listOf(
        TireLabel("左前", vehicleStatus.tyreStatusDriver, vehicleStatus.tyrePreWarningDriver),
        TireLabel("右前", vehicleStatus.tyreStatusPassenger, vehicleStatus.tyrePreWarningPassenger),
        TireLabel("左后", vehicleStatus.tyreStatusDriverRear, vehicleStatus.tyrePreWarningDriver),
        TireLabel("右后", vehicleStatus.tyreStatusPassengerRear, vehicleStatus.tyrePreWarningPassenger),
    )
    val hasData = tires.any { it.pressure != null && it.pressure != "未知" && it.pressure.isNotEmpty() }
    if (!hasData) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("胎压监测", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarUiColors.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                tires.forEach { tire ->
                    val pct = try { tire.pressure?.toFloat() ?: 0f } catch (_: Exception) { 0f }
                    val color = when {
                        (tire.warning == "1" || tire.warning == "警告") -> CarUiColors.tireDanger
                        pct > 0 && (pct < 200 || pct > 280) -> CarUiColors.tireWarn
                        pct > 0 -> CarUiColors.tireNorm
                        else -> CarUiColors.textSecondary
                    }
                    val display = if (pct > 0) "${pct.roundToInt()}" else "---"
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) { Text("●", fontSize = 18.sp, color = color) }
                        Spacer(Modifier.height(4.dp))
                        Text(display, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CarUiColors.textPrimary)
                        Text(tire.label, fontSize = 9.sp, color = CarUiColors.textSecondary)
                        Text("kPa", fontSize = 8.sp, color = CarUiColors.textSecondary)
                    }
                }
            }
        }
    }
}

// ====================================================================
//  6. Mileage & Energy Section
// ====================================================================
@Composable
fun MileageEnergySection(
    vehicleUi: CarVehicleDisplayUi?,
    modifier: Modifier = Modifier,
) {
    val CarUiColors = carColors()
    val rows = vehicleUi?.mileageEnergyRows ?: emptyList()
    if (rows.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📊", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("里程能耗", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarUiColors.textPrimary)
                Spacer(Modifier.weight(1f))
                Text("共 ${rows.size} 项", fontSize = 10.sp, color = CarUiColors.textSecondary)
            }
            Spacer(Modifier.height(10.dp))
            for (chunk in rows.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    chunk.forEach { row ->
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.Start) {
                            Text(row.label, fontSize = 10.sp, color = CarUiColors.textSecondary)
                            Text(row.value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = CarUiColors.textPrimary)
                        }
                    }
                    if (chunk.size < 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ====================================================================
//  7. Map Section - AMap WebView (使用 amap_map.html)
// ====================================================================
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapSection(
    lng: Double, lat: Double, refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    val CarUiColors = carColors()
    val ctx = LocalContext.current
    val hasCoords = !lng.isNaN() && !lat.isNaN()

    var addressText by remember { mutableStateOf("") }
    var addressLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }

    // Reverse geocoding for address
    LaunchedEffect(lng, lat) {
        if (!hasCoords) { addressLoading = false; return@LaunchedEffect }
        addressLoading = true
        withContext(Dispatchers.IO) {
            val addr = AmapApiService.regeoShortAddress(lng, lat)
            withContext(Dispatchers.Main) {
                addressText = addr ?: ""
                addressLoading = false
            }
        }
    }

    // Update WebView position when coords change
    LaunchedEffect(lng, lat, refreshKey) {
        if (!hasCoords || webView == null) return@LaunchedEffect
        val js = "updatePosition($lng, $lat)"
        webView?.evaluateJavascript(js, null)
    }

    // Navigation: try Amap app first, fall back to web URL
    val openNavigation = {
        try {
            if (hasCoords) {
                val uri = android.net.Uri.parse("amapuri://route/plan/?dlon=$lng&dlat=$lat&dname=车辆位置&dev=1&t=2")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.autonavi.minimap")
                }
                if (intent.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(intent)
                } else {
                    val navUrl = "https://uri.amap.com/navigation?to=$lng,$lat,车辆位置&mode=car&callnative=1"
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(navUrl)))
                }
            }
        } catch (_: Exception) {
            try {
                val navUrl = "https://uri.amap.com/navigation?to=$lng,$lat,车辆位置&mode=car&callnative=1"
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(navUrl)))
            } catch (_: Exception) { }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("车辆位置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarUiColors.textPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "导航到车",
                    fontSize = 12.sp, color = CarUiColors.accentBlue,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CarUiColors.btnActiveBg)
                        .clickable { openNavigation() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Address line
            if (addressText.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📍", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = addressText,
                        fontSize = 11.sp,
                        color = CarUiColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (hasCoords && addressLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("定位中...", fontSize = 11.sp, color = CarUiColors.textSecondary)
                }
            }

            // Map WebView area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CarUiColors.mapBg),
                contentAlignment = Alignment.Center
            ) {
                if (!hasCoords) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🗺", fontSize = 36.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "暂无位置信息",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = CarUiColors.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "获取车辆状态后自动更新",
                            fontSize = 11.sp,
                            color = CarUiColors.textSecondary
                        )
                    }
                } else {
                    AndroidView(
                        factory = { c ->
                            android.webkit.WebView(c).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                webView = this
                                loadUrl("file:///android_asset/car/amap_map.html")
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
// ====================================================================
//  Helper: isButtonAlertState (supports VehicleStatusInfo)
// ====================================================================
fun isButtonAlertState(
    text: String,
    vehicleStatus: VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
): Boolean {
    if (vehicleStatus == null) return false
    return when {
        text.contains("锁") || text.contains("解锁") ->
            "已锁" == VehicleStatusService.translateDoorLockStatus(vehicleStatus.doorLockStatusDriver)
        text.contains("点火") || text.contains("熄火") ->
            "运行中" == VehicleStatusService.translateEngineStatus(vehicleStatus.engineStatus)
        text.contains("窗") -> "已开" == vehicleStatus.winStatusDriver
        text.contains("后备箱") || text.contains("尾箱") ->
            "已开" == VehicleStatusService.translateTrunkStatus(vehicleStatus.trunkOpenStatus)
        text == "空调" -> acStatus
        text == "座椅加热" -> seatHeatingStatus
        else -> false
    }
}

// ====================================================================
//  Helper: resolveDisplayText (supports VehicleStatusInfo)
// ====================================================================
fun resolveDisplayText(
    text: String,
    vehicleStatus: VehicleStatusInfo?,
    acStatus: Boolean,
    seatHeatingStatus: Boolean,
): String {
    val active = isButtonAlertState(text, vehicleStatus, acStatus, seatHeatingStatus)
    return when {
        text == "锁车/解锁" -> if (active) "解锁" else "锁车"
        text == "点火/熄火" -> if (active) "熄火" else "点火"
        text == "开窗/关窗" -> if (active) "关窗" else "开窗"
        text == "开后备箱" -> if (active) "尾箱已开" else "开尾箱"
        else -> text
    }
}

// ====================================================================
//  8. Vehicle Detail Card — 折叠式全量车辆数据（两列布局 + 值翻译）
// ====================================================================

/** 将原始数值/布尔字符串翻译为中文。 */
private fun translateValue(raw: String?): String {
    if (raw == null || raw == "未知" || raw.isEmpty()) return "—"
    return when (raw) {
        "0" -> "关闭"
        "1" -> "开启"
        "2" -> "已激活"
        "true", "True" -> "开启"
        "false", "False" -> "关闭"
        else -> raw
    }
}

/** 翻译门锁值 */
private fun translateLock(raw: String?): String = when (raw) {
    "0" -> "已锁"
    "1" -> "未锁"
    "2" -> "部分"
    else -> translateValue(raw)
}

/** 翻译门开关值 */
private fun translateDoorOpen(raw: String?): String = when (raw) {
    "0" -> "关"
    "1" -> "开"
    else -> translateValue(raw)
}

/** 翻译手刹值 */
private fun translateBrake(raw: String?): String = when (raw) {
    "0" -> "释放"
    "1" -> "拉起"
    else -> translateValue(raw)
}

/** 翻译安全带值 */
private fun translateSeatbelt(raw: String?): String = when (raw) {
    "true", "True" -> "已系"
    "false", "False" -> "未系"
    else -> translateValue(raw)
}

/** 翻译防盗值 */
private fun translateTheft(raw: String?): String = when (raw) {
    "0" -> "未激活"
    "1" -> "已激活"
    "2" -> "已开启"
    else -> translateValue(raw)
}

/** 颜色判定：异常/警告=橙，正常=绿，其他=灰 */
private fun statusColor(value: String, colors: CarColorPalette): Color = when {
    value.contains("开") && !value.contains("关") -> colors.accentOrange
    value.contains("已锁") || value.contains("关") || value.contains("正常") || value.contains("释放") || value.contains("已系") || value.contains("未激活") -> colors.accentGreen
    value.contains("未锁") || value.contains("警告") || value.contains("拉起") || value.contains("激活") || value.contains("未系") || value.contains("报警") -> colors.accentOrange
    else -> colors.textPrimary
}

@Composable
fun VehicleDetailCard(
    vehicleStatus: VehicleStatusInfo?,
    modifier: Modifier = Modifier,
) {
    val CarUiColors = carColors()
    if (vehicleStatus == null) return

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CarUiColors.bgCard)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📋 车辆详细数据", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CarUiColors.textPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起 ▲" else "展开 ▼",
                    fontSize = 11.sp,
                    color = CarUiColors.accentBlue,
                )
            }

            if (expanded) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(CarUiColors.divider))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // ── 门锁（双列：锁 | 开）──
                    SectionLabel("🔒 门锁")
                    TwoColRow(
                        "主驾", translateLock(vehicleStatus.doorLockStatusDriver), translateDoorOpen(vehicleStatus.doorOpenStatusDriver), CarUiColors,
                        "副驾", translateLock(vehicleStatus.doorLockStatusPassenger), translateDoorOpen(vehicleStatus.doorOpenStatusPassenger), CarUiColors,
                    )
                    TwoColRow(
                        "左后", translateLock(vehicleStatus.doorLockStatusDriverRear), translateDoorOpen(vehicleStatus.doorOpenStatusDriverRear), CarUiColors,
                        "右后", translateLock(vehicleStatus.doorLockStatusPassengerRear), translateDoorOpen(vehicleStatus.doorOpenStatusPassengerRear), CarUiColors,
                    )

                    // ── 车窗（双列）──
                    SectionLabel("🪟 车窗")
                    TwoColSimple("主驾", vehicleStatus.winStatusDriver, CarUiColors,
                        "副驾", vehicleStatus.winStatusPassenger, CarUiColors)
                    TwoColSimple("左后", vehicleStatus.winStatusDriverRear, CarUiColors,
                        "右后", vehicleStatus.winStatusPassengerRear, CarUiColors)
                    TwoColSimple("天窗", vehicleStatus.sunroofOpenStatus, CarUiColors,
                        "后备箱锁", translateValue(vehicleStatus.trunkLockStatus), CarUiColors)

                    // ── 安全（双列）──
                    SectionLabel("🛡 安全")
                    TwoColLabeled("安全带主", translateSeatbelt(vehicleStatus.seatBeltStatusDriver), CarUiColors,
                        "安全带副", translateSeatbelt(vehicleStatus.seatBeltStatusPassenger), CarUiColors)
                    TwoColLabeled("电子手刹", translateBrake(vehicleStatus.electricParkBrakeStatus), CarUiColors,
                        "机械手刹", translateBrake(vehicleStatus.handBrakeStatus), CarUiColors)
                    TwoColLabeled("制动踏板", translateValue(vehicleStatus.brakePedalDepressed), CarUiColors,
                        "引擎盖", translateDoorOpen(vehicleStatus.engineHoodOpenStatus), CarUiColors)
                    TwoColLabeled("防盗状态", translateTheft(vehicleStatus.theftActivated), CarUiColors,
                        "车辆警报", translateValue(vehicleStatus.vehicleAlarm), CarUiColors)

                    // ── 座椅加热（双列）──
                    SectionLabel("🔥 座椅加热")
                    TwoColLabeled("主驾", translateValue(vehicleStatus.drvHeatSts), CarUiColors,
                        "副驾", translateValue(vehicleStatus.passHeatingSts), CarUiColors)
                    TwoColLabeled("左后", translateValue(vehicleStatus.rlHeatingSts), CarUiColors,
                        "右后", translateValue(vehicleStatus.rrHeatingSts), CarUiColors)

                    // ── 驾驶 + 空调（双列混排）──
                    SectionLabel("🚗 驾驶 · 空调")
                    TwoColLabeled("变速箱档位", vehicleStatus.transimissionGearPostion, CarUiColors,
                        "定速巡航", translateValue(vehicleStatus.cruiseControlStatus), CarUiColors)
                    TwoColLabeled("机油压力", translateValue(vehicleStatus.engineOilPressureWarning), CarUiColors,
                        "车窗未关提醒", translateValue(vehicleStatus.winCloseReminder), CarUiColors)
                    TwoColLabeled("空气净化", translateValue(vehicleStatus.airCleanSts), CarUiColors,
                        "预约空调", translateValue(vehicleStatus.preClimateActive), CarUiColors)
                    TwoColLabeled("通风状态", translateValue(vehicleStatus.ventilateStatus), CarUiColors,
                        "", "", CarUiColors)

                    // ── 位置 ──
                    SectionLabel("📍 位置")
                    TwoColLabeled("海拔", fmtNum(vehicleStatus.altitude, "m"), CarUiColors,
                        "方向", fmtNum(vehicleStatus.direction, "°"), CarUiColors)
                    TwoColLabeled("定位可信", translateValue(vehicleStatus.posCanBeTrusted), CarUiColors,
                        "", "", CarUiColors)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun fmtNum(raw: String?, unit: String): String {
    if (raw == null || raw == "未知" || raw.isEmpty()) return "—"
    return "$raw$unit"
}

// -- 紧凑辅助组件 --

@Composable
private fun SectionLabel(title: String) {
    val colors = carColors()
    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.accentBlue)
}

/** 门锁双列：每列显示 [标签] 锁:值 开:值 */
@Composable
private fun TwoColRow(
    l1: String, lock1: String, open1: String, colors: CarColorPalette,
    l2: String, lock2: String, open2: String, colors2: CarColorPalette,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        DoorCell(l1, lock1, open1, colors, Modifier.weight(1f))
        DoorCell(l2, lock2, open2, colors, Modifier.weight(1f))
    }
}

@Composable
private fun DoorCell(label: String, lock: String, open: String, colors: CarColorPalette, mod: Modifier) {
    Row(mod.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = colors.textSecondary, modifier = Modifier.width(28.dp))
        Text(lock, fontSize = 11.sp, color = statusColor(lock, colors))
        Text("/", fontSize = 10.sp, color = colors.textSecondary)
        Text(open, fontSize = 11.sp, color = statusColor(open, colors))
    }
}

/** 简单双列：标签 + 状态值 */
@Composable
private fun TwoColSimple(
    l1: String, v1: String, c: CarColorPalette,
    l2: String, v2: String, c2: CarColorPalette,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        LabeledCell(l1, v1, c, Modifier.weight(1f))
        LabeledCell(l2, v2, c, Modifier.weight(1f))
    }
}

/** 标签:值 双列 */
@Composable
private fun TwoColLabeled(
    l1: String, v1: String, c: CarColorPalette,
    l2: String, v2: String, c2: CarColorPalette,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        LabeledCell(l1, v1, c, Modifier.weight(1f))
        if (l2.isNotEmpty()) LabeledCell(l2, v2, c, Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LabeledCell(label: String, value: String, colors: CarColorPalette, mod: Modifier) {
    val display = if (value.isEmpty() || value == "未知") "—" else value
    Row(mod.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = colors.textSecondary)
        Spacer(Modifier.width(3.dp))
        Text(display, fontSize = 11.sp, color = statusColor(display, colors), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ====================================================================
//  Helper: loadVehiclePosition from VehicleStatusService
// ====================================================================
fun loadVehiclePosition(context: Context, onResult: (lng: Double, lat: Double) -> Unit) {
    try {
        val s = VehicleStatusService.getVehicleStatus(context.applicationContext)
        val latMs = s.latitude.toDoubleOrNull()
        val lngMs = s.longitude.toDoubleOrNull()
        if (latMs != null && lngMs != null) {
            val lng = if (lngMs > 360) lngMs / 3600000.0 else lngMs
            val lat = if (latMs > 360) latMs / 3600000.0 else latMs
            onResult(lng, lat)
        }
    } catch (_: Exception) { }
}
