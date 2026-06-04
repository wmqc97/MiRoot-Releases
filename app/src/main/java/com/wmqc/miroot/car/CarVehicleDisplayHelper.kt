package com.wmqc.miroot.car

import android.content.Context
import com.wmqc.miroot.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 车控设置页展示用：从 [VehicleStatusService] 拉取并解析车辆基础信息与状态，
 * 规则与 [RearScreenCarControlActivity] 初始化车辆信息、[VehicleStatusService.buildVehicleQueryBroadcastJson] 一致。
 */
data class CarNamedDisplayRow(val label: String, val value: String)

data class CarVehicleDisplayUi(
    val plateNo: String,
    val seriesModel: String,
    val colorName: String,
    val vin: String,
    val rangeKmText: String,
    val fuelPercentText: String,
    val fuelPercent: Int,
    val interiorTempText: String,
    val exteriorTempText: String,
    val updateTimeShort: String,
    val vehicleStatusRows: List<CarNamedDisplayRow>,
    val mileageEnergyRows: List<CarNamedDisplayRow>,
    val warningMessage: String?,
    val loginInvalid: Boolean,
    val needBind: Boolean,
)

object CarVehicleDisplayHelper {

    private const val UNKNOWN_ZH = "未知"

    fun load(context: Context): CarVehicleDisplayUi = loadWithStatus(context).first

    /** 拉取车况并返回 UI 与完整状态（供芯片、地图等直接绑定，避免缓存字段缺失）。 */
    fun loadWithStatus(context: Context): Pair<CarVehicleDisplayUi, VehicleStatusService.VehicleStatusInfo> {
        val params = VehicleControlService.extractVehicleParams(context)
        if (!params.isValid) {
            val empty = emptyUi(context, needBind = true)
            return empty to VehicleStatusService.VehicleStatusInfo()
        }

        val basic = VehicleStatusService.getVehicleBasicInfo(context)
        val status = VehicleStatusService.getVehicleStatus(context)
        CarVehicleDisplayCache.save(context, status)
        return buildUi(context, basic, status) to status
    }

    /** 进入车控页时同步读取上次成功刷新的展示数据（不发起网络请求）。 */
    fun loadCached(context: Context): CarVehicleDisplayUi? {
        val status = CarVehicleDisplayCache.loadStatus(context) ?: return null
        val dash = context.getString(R.string.car_control_vehicle_dash)
        val basic = try {
            VehicleStatusService.getVehicleBasicInfo(context)
        } catch (_: Exception) {
            VehicleStatusService.VehicleBasicInfo()
        }
        return buildUi(context, basic, status, loginInvalid = LoginService.isLoginMarkedInvalid(context))
    }

    fun buildUi(
        context: Context,
        basic: VehicleStatusService.VehicleBasicInfo,
        status: VehicleStatusService.VehicleStatusInfo,
        loginInvalid: Boolean = LoginService.isLoginMarkedInvalid(context),
    ): CarVehicleDisplayUi {
        val dash = context.getString(R.string.car_control_vehicle_dash)
        val json = try {
            VehicleStatusService.buildVehicleQueryBroadcastJson(status)
        } catch (e: JSONException) {
            JSONObject()
        }

        val vsRows = json.optJSONArray("vehicleStatusItems").toStatusRows(dash)
        val meRows = json.optJSONArray("mileageEnergyItems").toMileageRows(dash)

        val rangeKm = VehicleStatusService.parseDistanceToEmptyKm(status.distanceToEmpty)
        val fuelPct = VehicleStatusService.parseFuelLevelPercent(status.fuelLevelStatus)
        val rangeText = if (rangeKm >= 0) "${rangeKm} km" else dash
        val fuelText = if (fuelPct >= 0) "${fuelPct}%" else dash

        val warning = when {
            loginInvalid -> context.getString(R.string.car_control_vehicle_login_expired)
            else -> null
        }

        return CarVehicleDisplayUi(
            plateNo = displayBasic(basic.plateNo, dash),
            seriesModel = displaySeriesModel(basic.seriesName, basic.modelName, dash),
            colorName = displayBasic(basic.colorName, dash),
            vin = displayBasic(basic.vin, dash),
            rangeKmText = rangeText,
            fuelPercentText = fuelText,
            fuelPercent = fuelPct,
            interiorTempText = formatTempLabel(context, status.interiorTemp, isInterior = true, dash = dash),
            exteriorTempText = formatTempLabel(context, status.exteriorTemp, isInterior = false, dash = dash),
            updateTimeShort = formatUpdateTimeShort(status.updateDateTime, dash),
            vehicleStatusRows = vsRows,
            mileageEnergyRows = meRows,
            warningMessage = warning,
            loginInvalid = loginInvalid,
            needBind = false,
        )
    }

    fun emptyUi(context: Context, needBind: Boolean): CarVehicleDisplayUi {
        val dash = context.getString(R.string.car_control_vehicle_dash)
        return CarVehicleDisplayUi(
            plateNo = dash,
            seriesModel = dash,
            colorName = dash,
            vin = dash,
            rangeKmText = dash,
            fuelPercentText = dash,
            fuelPercent = -1,
            interiorTempText = dash,
            exteriorTempText = dash,
            updateTimeShort = dash,
            vehicleStatusRows = emptyList(),
            mileageEnergyRows = emptyList(),
            warningMessage = if (needBind) {
                context.getString(R.string.car_control_vehicle_need_bind)
            } else {
                null
            },
            loginInvalid = false,
            needBind = needBind,
        )
    }

    private fun displayBasic(raw: String?, dash: String): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == UNKNOWN_ZH) return dash
        return s
    }

    private fun displaySeriesModel(series: String?, model: String?, dash: String): String {
        val parts = listOfNotNull(series?.trim(), model?.trim())
            .filter { it.isNotEmpty() && it != UNKNOWN_ZH }
        return if (parts.isEmpty()) dash else parts.joinToString(" ")
    }

    private fun JSONArray?.toStatusRows(dash: String): List<CarNamedDisplayRow> {
        if (this == null) return emptyList()
        val out = ArrayList<CarNamedDisplayRow>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) continue
            val state = o.optString("state", "").trim()
            out.add(CarNamedDisplayRow(name, if (state.isEmpty()) dash else state))
        }
        return out
    }

    private fun JSONArray?.toMileageRows(dash: String): List<CarNamedDisplayRow> {
        if (this == null) return emptyList()
        val out = ArrayList<CarNamedDisplayRow>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) continue
            val state = o.optString("state", "").trim()
            out.add(CarNamedDisplayRow(name, if (state.isEmpty()) dash else state))
        }
        return out
    }

    private fun formatTempLabel(context: Context, raw: String?, isInterior: Boolean, dash: String): String {
        val template = if (isInterior) {
            context.getString(R.string.car_control_vehicle_temp_interior_fmt)
        } else {
            context.getString(R.string.car_control_vehicle_temp_exterior_fmt)
        }
        val digits = VehicleStatusService.formatTempCelsiusDigitsOrNull(raw)
        return String.format(template, digits ?: dash)
    }

    /** 与 [VehicleStatusService.formatUpdateTimeShortMmDdHhMm] 一致：展示为 MM-dd HH:mm */
    private fun formatUpdateTimeShort(updateDateTime: String?, dash: String): String {
        return VehicleStatusService.formatUpdateTimeShortMmDdHhMm(updateDateTime ?: "", dash)
    }
}
