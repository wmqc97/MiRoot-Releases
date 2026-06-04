package com.wmqc.miroot.car

import android.content.Context
import android.util.Log
import kotlin.jvm.JvmStatic
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 上次成功刷新的车辆状态缓存，供车控页进入时立即展示，避免等待网络时空白。
 */
object CarVehicleDisplayCache {

    private const val TAG = "CarVehicleDisplayCache"
    private val historyExecutor = Executors.newSingleThreadExecutor()

    const val KEY_CACHE_VALID = "vehicle_display_cache_valid"
    const val KEY_RANGE_KM = "vehicle_cache_range_km"
    const val KEY_FUEL_PCT = "vehicle_cache_fuel_pct"
    const val KEY_ODOMETER = "vehicle_cache_odometer"
    const val KEY_INTERIOR_TEMP = "vehicle_cache_interior_temp"
    const val KEY_EXTERIOR_TEMP = "vehicle_cache_exterior_temp"
    const val KEY_UPDATE_TIME = "vehicle_cache_update_time"
    const val KEY_LOCK_STATUS = "vehicle_cache_lock_status"
    const val KEY_WINDOW_STATUS = "vehicle_cache_window_status"
    const val KEY_STATUS_JSON = "vehicle_cache_status_json"

    @JvmStatic
    fun hasCache(context: Context): Boolean =
        CarControlVehiclePrefsSync.carPrefs(context).getBoolean(KEY_CACHE_VALID, false)

    @JvmStatic
    fun save(context: Context, status: VehicleStatusService.VehicleStatusInfo) {
        val rangeKm = VehicleStatusService.parseDistanceToEmptyKm(status.distanceToEmpty)
        val fuelPct = VehicleStatusService.parseFuelLevelPercent(status.fuelLevelStatus)
        val lockStatus = VehicleStatusService.translateDoorLockStatus(status.doorLockStatusDriver)
        val windowStatus = status.winStatusDriver ?: "未知"
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putBoolean(KEY_CACHE_VALID, true)
            .putInt(KEY_RANGE_KM, rangeKm)
            .putInt(KEY_FUEL_PCT, fuelPct)
            .putString(KEY_ODOMETER, VehicleStatusService.formatOdometerKmDisplayOrUnknown(status.odometer))
            .putString(KEY_INTERIOR_TEMP, VehicleStatusService.formatTempCelsiusDigitsOrUnknown(status.interiorTemp))
            .putString(KEY_EXTERIOR_TEMP, VehicleStatusService.formatTempCelsiusDigitsOrUnknown(status.exteriorTemp))
            .putString(
                KEY_UPDATE_TIME,
                VehicleStatusService.formatUpdateTimeShortHhMm(status.updateDateTime, "未知"),
            )
            .putString(KEY_LOCK_STATUS, lockStatus)
            .putString(KEY_WINDOW_STATUS, windowStatus)
            .putString(KEY_STATUS_JSON, statusToJson(status).toString())
            .apply()
        CarControlVehiclePrefsSync.applyFromStatus(context, status)
        VehiclePositionHelper.fromStatus(status)?.let { gcj ->
            VehiclePositionHelper.saveCache(
                context,
                gcj,
                VehiclePositionHelper.isMarsGcj(status.marsCoordinates),
            )
        }
        val appCtx = context.applicationContext
        historyExecutor.execute {
            runCatching { VehicleDataHistoryStore.record(appCtx, status) }
                .onFailure { Log.w(TAG, "history record failed", it) }
        }
    }

    fun loadStatus(context: Context): VehicleStatusService.VehicleStatusInfo? {
        if (!hasCache(context)) return null
        val raw = CarControlVehiclePrefsSync.carPrefs(context).getString(KEY_STATUS_JSON, null)
            ?: return null
        return try {
            statusFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    data class HudSnapshot(
        val rangeKm: Int,
        val fuelPercent: Int,
        val odometer: String,
        val interiorTemp: String,
        val exteriorTemp: String,
        val updateTime: String,
        val lockStatus: String,
        val windowStatus: String,
    )

    @JvmStatic
    fun loadHudSnapshot(context: Context): HudSnapshot? {
        if (!hasCache(context)) return null
        val p = CarControlVehiclePrefsSync.carPrefs(context)
        return HudSnapshot(
            rangeKm = p.getInt(KEY_RANGE_KM, -1).coerceAtLeast(0),
            fuelPercent = p.getInt(KEY_FUEL_PCT, -1).coerceAtLeast(0),
            odometer = p.getString(KEY_ODOMETER, "未知") ?: "未知",
            interiorTemp = p.getString(KEY_INTERIOR_TEMP, "未知") ?: "未知",
            exteriorTemp = p.getString(KEY_EXTERIOR_TEMP, "未知") ?: "未知",
            updateTime = p.getString(KEY_UPDATE_TIME, "未知") ?: "未知",
            lockStatus = p.getString(KEY_LOCK_STATUS, "未知") ?: "未知",
            windowStatus = p.getString(KEY_WINDOW_STATUS, "未知") ?: "未知",
        )
    }

    private fun statusToJson(s: VehicleStatusService.VehicleStatusInfo): JSONObject =
        JSONObject().apply {
            put("engineStatus", s.engineStatus)
            put("distanceToEmpty", s.distanceToEmpty)
            put("updateTime", s.updateTime)
            put("updateDateTime", s.updateDateTime)
            put("odometer", s.odometer)
            put("fuelLevel", s.fuelLevel)
            put("fuelLevelStatus", s.fuelLevelStatus)
            put("interiorTemp", s.interiorTemp)
            put("exteriorTemp", s.exteriorTemp)
            put("latitude", s.latitude)
            put("longitude", s.longitude)
            put("marsCoordinates", s.marsCoordinates)
            put("doorLockStatusDriver", s.doorLockStatusDriver)
            put("winStatusDriver", s.winStatusDriver)
            put("winPosDriver", s.winPosDriver)
            put("trunkOpenStatus", s.trunkOpenStatus)
            put("sunroofOpenStatus", s.sunroofOpenStatus)
            put("sunroofPos", s.sunroofPos)
            put("voltage", s.voltage)
            put("stateOfCharge", s.stateOfCharge)
            put("avgSpeed", s.avgSpeed)
        }

    private fun statusFromJson(o: JSONObject): VehicleStatusService.VehicleStatusInfo {
        val s = VehicleStatusService.VehicleStatusInfo()
        s.engineStatus = o.optString("engineStatus", "未知")
        s.distanceToEmpty = o.optString("distanceToEmpty", "未知")
        s.updateTime = readUpdateTimeRaw(o)
        s.updateDateTime = o.optString("updateDateTime", "未知")
        normalizeUpdateTimeFields(s)
        s.odometer = o.optString("odometer", "未知")
        s.fuelLevel = o.optString("fuelLevel", "未知")
        s.fuelLevelStatus = o.optString("fuelLevelStatus", "未知")
        s.interiorTemp = o.optString("interiorTemp", "未知")
        s.exteriorTemp = o.optString("exteriorTemp", "未知")
        s.latitude = o.optString("latitude", "未知")
        s.longitude = o.optString("longitude", "未知")
        s.marsCoordinates = o.optString("marsCoordinates", "未知")
        s.doorLockStatusDriver = o.optString("doorLockStatusDriver", "未知")
        s.winStatusDriver = o.optString("winStatusDriver", "未知")
        s.winPosDriver = o.optString("winPosDriver", "未知")
        s.trunkOpenStatus = o.optString("trunkOpenStatus", "未知")
        s.sunroofOpenStatus = o.optString("sunroofOpenStatus", "未知")
        s.sunroofPos = o.optString("sunroofPos", "未知")
        s.voltage = o.optString("voltage", "未知")
        s.stateOfCharge = o.optString("stateOfCharge", "未知")
        s.avgSpeed = o.optString("avgSpeed", "未知")
        return s
    }

    private fun readUpdateTimeRaw(o: JSONObject): String {
        if (!o.has("updateTime") || o.isNull("updateTime")) return "未知"
        return when (val raw = o.opt("updateTime")) {
            is Number -> raw.toLong().toString()
            else -> raw?.toString()?.trim().orEmpty().ifEmpty { "未知" }
        }
    }

    private fun normalizeUpdateTimeFields(status: VehicleStatusService.VehicleStatusInfo) {
        val millis = VehicleStatusService.resolveVehicleUpdateMillis(status)
        if (millis <= 0L) return
        status.updateTime = millis.toString()
        if (status.updateDateTime.isNullOrBlank()
            || status.updateDateTime == "未知"
            || status.updateDateTime == "时间格式错误"
        ) {
            status.updateDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(millis))
        }
    }
}
