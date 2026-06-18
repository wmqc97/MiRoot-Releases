package com.wmqc.miroot.car

import android.content.Context
import com.wmqc.miroot.R
import kotlin.math.roundToInt

/** 小组件可选信息行文案（与车控页数据字段一致）。 */
object CarControlWidgetDisplayLines {

    fun odometerLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val km = VehicleStatusService.formatOdometerKmDigitsOrNull(status?.odometer) ?: return null
        return context.getString(R.string.car_control_widget_line_odometer, km)
    }

    fun batteryLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val voltageRaw = status?.voltage?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
            ?: return null
        val volts = formatVoltageDigits(voltageRaw) ?: return null
        val charge = status.stateOfCharge?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
        val value = if (!charge.isNullOrEmpty()) {
            context.getString(R.string.car_control_widget_battery_value_volts_soc, volts, charge)
        } else {
            context.getString(R.string.car_control_widget_battery_value_volts, volts)
        }
        return context.getString(R.string.car_control_widget_line_battery, value)
    }

    fun temperatureLine(context: Context, vehicleUi: CarVehicleDisplayUi?, dash: String): String {
        val interior = parseTempDigits(vehicleUi?.interiorTempText)
        val exterior = parseTempDigits(vehicleUi?.exteriorTempText)
        return when {
            interior != null && exterior != null ->
                context.getString(R.string.car_control_widget_line_temp, interior, exterior)
            interior != null ->
                context.getString(R.string.car_control_widget_line_temp_interior, interior)
            exterior != null ->
                context.getString(R.string.car_control_widget_line_temp_exterior, exterior)
            else -> dash
        }
    }

    fun avgConsumptionLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val raw = status?.aveFuelConsumption?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
            ?: return null
        val value = raw.replace(Regex("[^0-9.]"), "").takeIf { it.isNotEmpty() } ?: return null
        return context.getString(R.string.car_control_widget_line_avg_consumption, value)
    }

    fun coolantTempLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val raw = status?.engineCoolantTemperature?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
            ?: return null
        val digits = raw.replace(Regex("[^\\d.-]"), "").trim().takeIf { it.isNotEmpty() } ?: return null
        return context.getString(R.string.car_control_widget_line_coolant_temp, digits)
    }

    fun serviceDistanceLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val raw = status?.distanceToService?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
            ?: return null
        val value = raw.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() } ?: return null
        return context.getString(R.string.car_control_widget_line_service_distance, value)
    }

    fun epbStatusLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val raw = status?.electricParkBrakeStatus?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
            ?: return null
        val isEngaged = raw == "1" || raw.equals("true", ignoreCase = true) ||
            raw.contains("拉起") || raw.contains("engaged") || raw.contains("on")
        return if (isEngaged) {
            context.getString(R.string.car_control_widget_line_epb_on)
        } else {
            context.getString(R.string.car_control_widget_line_epb_off)
        }
    }

    fun engineSpeedLine(context: Context, status: VehicleStatusService.VehicleStatusInfo?): String? {
        val raw = status?.engineSpeed?.trim()?.takeUnless { it.isEmpty() || it == "未知" }
            ?: return null
        val value = raw.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() } ?: return null
        return context.getString(R.string.car_control_widget_line_engine_speed, value)
    }

    data class TirePressureCells(
        val lf: String,
        val rf: String,
        val lr: String,
        val rr: String,
    )

    fun tirePressureCells(
        context: Context,
        status: VehicleStatusService.VehicleStatusInfo?,
        dash: String,
    ): TirePressureCells? {
        if (status == null) return null
        val lf = parseTireKpa(status.tyreStatusDriver)
        val rf = parseTireKpa(status.tyreStatusPassenger)
        val lr = parseTireKpa(status.tyreStatusDriverRear)
        val rr = parseTireKpa(status.tyreStatusPassengerRear)
        if (lf == null && rf == null && lr == null && rr == null) return null
        return TirePressureCells(
            lf = tireCell(context, R.string.car_control_widget_tire_lf, lf, dash),
            rf = tireCell(context, R.string.car_control_widget_tire_rf, rf, dash),
            lr = tireCell(context, R.string.car_control_widget_tire_lr, lr, dash),
            rr = tireCell(context, R.string.car_control_widget_tire_rr, rr, dash),
        )
    }

    private fun tireCell(context: Context, labelRes: Int, kpa: Int?, dash: String): String =
        context.getString(
            R.string.car_control_widget_tire_cell,
            context.getString(labelRes),
            kpa?.toString() ?: dash,
        )

    private fun parseTempDigits(formatted: String?): String? {
        val digits = formatted?.replace(Regex("[^\\d.-]"), "")?.trim().orEmpty()
        return digits.takeIf { it.isNotEmpty() }
    }

    private fun parseTireKpa(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s == "未知") return null
        return s.toFloatOrNull()?.roundToInt()?.takeIf { it > 0 }
    }

    private fun formatVoltageDigits(raw: String): String? {
        val num = raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return null
        val volts = if (num > 100) num / 1000.0 else num
        return if (volts == volts.toLong().toDouble()) {
            volts.toLong().toString()
        } else {
            "%.1f".format(volts)
        }
    }
}
