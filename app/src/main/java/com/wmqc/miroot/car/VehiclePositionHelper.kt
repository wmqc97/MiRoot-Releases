package com.wmqc.miroot.car

import android.content.Context
import com.wmqc.miroot.lyrics.LogHelper

/**
 * 从车辆状态解析经纬度（API 毫秒坐标 ÷3600000），并缓存供地图展示。
 * 星瑞 `marsCoordinates=false` 时为 WGS-84，高德地图/逆地理需先转 GCJ-02。
 */
object VehiclePositionHelper {

    private const val TAG = "VehiclePosition"

    const val KEY_VEHICLE_LNG = "vehicle_cache_lng"
    const val KEY_VEHICLE_LAT = "vehicle_cache_lat"
    const val KEY_VEHICLE_ADDRESS = "vehicle_cache_address"
    /** 缓存是否为 GCJ（true=接口已是火星坐标；false=由 WGS 转换得到） */
    const val KEY_MARS_GCJ = "vehicle_cache_mars_gcj"

    data class Coordinates(val lng: Double, val lat: Double)

    /** 解析后的原始度坐标（未做坐标系转换）。 */
    fun parseRawCoordinates(latitudeRaw: String?, longitudeRaw: String?): Coordinates? {
        var latMs = latitudeRaw?.trim()?.toDoubleOrNull() ?: return null
        var lngMs = longitudeRaw?.trim()?.toDoubleOrNull() ?: return null
        if (latMs == 0.0 && lngMs == 0.0) return null
        val msUnits = latMs > 360 || lngMs > 360
        var lat = if (msUnits) latMs / 3_600_000.0 else latMs
        var lng = if (msUnits) lngMs / 3_600_000.0 else lngMs
        if (kotlin.math.abs(lat) > 90.0 && kotlin.math.abs(lng) <= 90.0) {
            val t = lat
            lat = lng
            lng = t
        } else if (lat > 55.0 && lng in 70.0..140.0) {
            val t = lat
            lat = lng
            lng = t
        }
        if (!isValidDegree(lat, lng)) return null
        return Coordinates(lng = lng, lat = lat)
    }

    /** 接口是否声明为火星坐标（GCJ-02）。false / 0 表示 WGS-84。 */
    fun isMarsGcj(marsCoordinates: String?): Boolean {
        when (marsCoordinates?.trim()?.lowercase()) {
            "true", "1", "yes" -> return true
            "false", "0", "no" -> return false
        }
        return false
    }

    /**
     * 供高德地图、逆地理、静态图使用：已是 GCJ 则原样，否则 WGS→GCJ。
     */
    fun toGcjForAmap(raw: Coordinates, marsGcj: Boolean): Coordinates {
        if (marsGcj) return raw
        val (gcjLat, gcjLng) = GcjCoordinateConverter.wgs84ToGcj02(raw.lat, raw.lng)
        LogHelper.d(TAG, "WGS→GCJ: ${raw.lng},${raw.lat} -> $gcjLng,$gcjLat")
        return Coordinates(lng = gcjLng, lat = gcjLat)
    }

    /** 高德导航 URI 的 dev：0=已是 GCJ，1=GPS(WGS) 由高德转换。 */
    fun amapNavigationDev(marsGcj: Boolean): String = if (marsGcj) "0" else "1"

    @JvmStatic
    fun fromStatus(status: VehicleStatusService.VehicleStatusInfo?): Coordinates? {
        if (status == null) return null
        val raw = parseRawCoordinates(status.latitude, status.longitude) ?: return null
        return toGcjForAmap(raw, isMarsGcj(status.marsCoordinates))
    }

    fun rawFromStatus(status: VehicleStatusService.VehicleStatusInfo?): Coordinates? =
        parseRawCoordinates(status?.latitude, status?.longitude)

    fun saveCache(context: Context, coords: Coordinates, marsGcjReported: Boolean) {
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putString(KEY_VEHICLE_LNG, coords.lng.toString())
            .putString(KEY_VEHICLE_LAT, coords.lat.toString())
            .putBoolean(KEY_MARS_GCJ, marsGcjReported)
            .apply()
    }

    fun loadCache(context: Context): Coordinates? {
        val prefs = CarControlVehiclePrefsSync.carPrefs(context)
        val lng = prefs.getString(KEY_VEHICLE_LNG, null)?.toDoubleOrNull()
        val lat = prefs.getString(KEY_VEHICLE_LAT, null)?.toDoubleOrNull()
        if (lng == null || lat == null) {
            if (!prefs.contains(KEY_VEHICLE_LNG) || !prefs.contains(KEY_VEHICLE_LAT)) return null
            val lngF = prefs.getFloat(KEY_VEHICLE_LNG, Float.NaN).toDouble()
            val latF = prefs.getFloat(KEY_VEHICLE_LAT, Float.NaN).toDouble()
            if (!isValidDegree(latF, lngF)) return null
            return Coordinates(lng = lngF, lat = latF)
        }
        if (!isValidDegree(lat, lng)) return null
        return Coordinates(lng = lng, lat = lat)
    }

    fun loadCacheMarsGcj(context: Context): Boolean =
        CarControlVehiclePrefsSync.carPrefs(context).getBoolean(KEY_MARS_GCJ, false)

    /** 拉取云端车辆状态并解析坐标；失败时尝试读缓存。 */
    fun loadFromVehicle(context: Context, preferCacheOnFailure: Boolean = true): Coordinates? {
        return try {
            val status = VehicleStatusService.getVehicleStatus(context.applicationContext)
            val coords = fromStatus(status)
            if (coords != null) {
                saveCache(context, coords, isMarsGcj(status.marsCoordinates))
                LogHelper.d(TAG, "车辆坐标(GCJ展示): ${coords.lng}, ${coords.lat}, mars=${status.marsCoordinates}")
                coords
            } else if (preferCacheOnFailure) {
                loadCache(context)
            } else {
                null
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "loadFromVehicle failed: ${e.message}")
            if (preferCacheOnFailure) loadCache(context) else null
        }
    }

    fun saveAddressCache(context: Context, address: String) {
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putString(KEY_VEHICLE_ADDRESS, address.trim())
            .apply()
    }

    fun loadAddressCache(context: Context): String? =
        CarControlVehiclePrefsSync.carPrefs(context)
            .getString(KEY_VEHICLE_ADDRESS, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * 展示用地址：高德逆地理（GCJ 坐标）→ 缓存。
     */
    fun resolveDisplayAddress(context: Context, lng: Double, lat: Double): String? {
        val appCtx = context.applicationContext
        val regeo = AmapApiService.regeo(lng, lat)
        if (regeo != null) {
            FuelPriceRegionPrefs.cacheAutoProvinceAt(appCtx, lng, lat, regeo.province, regeo.city)
            val addr = AmapApiService.fullDisplayAddress(regeo)?.trim()
            if (!addr.isNullOrBlank()) {
                saveAddressCache(appCtx, addr)
                return addr
            }
        }
        return loadAddressCache(appCtx)
    }

    /**
     * 小组件/地图展示用：有坐标时逆地理，失败则用缓存地址。
     */
    fun resolveLocationText(
        context: Context,
        status: VehicleStatusService.VehicleStatusInfo?,
    ): String {
        val appCtx = context.applicationContext
        val coords = fromStatus(status) ?: loadCache(appCtx)
        if (coords == null) {
            return context.getString(com.wmqc.miroot.R.string.car_control_widget_location_none)
        }
        saveCache(appCtx, coords, status?.let { isMarsGcj(it.marsCoordinates) } ?: loadCacheMarsGcj(appCtx))
        resolveDisplayAddress(context, coords.lng, coords.lat)?.let { return it }
        return context.getString(com.wmqc.miroot.R.string.car_control_widget_location_none)
    }

    /** 小组件刷新：仅用缓存地址，避免逆地理阻塞或拖慢 updateAppWidget。 */
    fun resolveLocationTextForWidget(
        context: Context,
        status: VehicleStatusService.VehicleStatusInfo?,
    ): String {
        loadAddressCache(context)?.let { return it }
        val coords = fromStatus(status) ?: loadCache(context.applicationContext)
        if (coords == null) {
            return context.getString(com.wmqc.miroot.R.string.car_control_widget_location_none)
        }
        return context.getString(com.wmqc.miroot.R.string.car_control_widget_location_none)
    }

    private fun isValidDegree(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 &&
            !(lat == 0.0 && lng == 0.0)
}
