package com.wmqc.miroot.car

import android.content.Context
import com.wmqc.miroot.lyrics.LogHelper

/** 历史油耗分析：油价查询省份与油箱容积。 */
object FuelPriceRegionPrefs {
    private const val TAG = "FuelPriceRegion"

    const val KEY_PROVINCE = "fuel_price_province_v1"
    const val KEY_TANK_LITERS = "fuel_tank_capacity_liters_v1"
    const val KEY_MANUAL_OVERRIDE = "fuel_price_province_manual_v1"
    const val KEY_AUTO_PROVINCE = "fuel_price_auto_province_v1"
    const val KEY_AUTO_LNG = "fuel_price_auto_lng_v1"
    const val KEY_AUTO_LAT = "fuel_price_auto_lat_v1"

    const val DEFAULT_PROVINCE = "浙江"
    const val DEFAULT_TANK_LITERS = 50f

    private const val COORD_EPS = 0.02

    val PROVINCES: List<String> = listOf(
        "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
        "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
        "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
        "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆",
    )

    data class ProvinceSource(
        val province: String,
        val fromVehicleAuto: Boolean,
    )

    /** 油耗/油价用省份：默认按车辆坐标自动识别，无坐标时用缓存地址或默认省。 */
    fun province(context: Context): String = resolveProvinceSource(context).province

    fun resolveProvinceSource(context: Context): ProvinceSource {
        if (isManualOverride(context)) {
            return ProvinceSource(manualProvince(context), fromVehicleAuto = false)
        }
        val auto = resolveFromVehicle(context)
        if (auto != null) {
            return ProvinceSource(auto, fromVehicleAuto = true)
        }
        return ProvinceSource(manualProvince(context), fromVehicleAuto = false)
    }

    /** 进入历史页时刷新自动省份（应在后台线程调用）。 */
    fun refreshAutoProvinceFromVehicle(context: Context): ProvinceSource {
        if (isManualOverride(context)) {
            return ProvinceSource(manualProvince(context), fromVehicleAuto = false)
        }
        val auto = resolveFromVehicle(context, forceRegeo = true)
        return ProvinceSource(
            auto ?: manualProvince(context),
            fromVehicleAuto = auto != null,
        )
    }

    fun isManualOverride(context: Context): Boolean =
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
            .getBoolean(KEY_MANUAL_OVERRIDE, false)

    fun clearManualOverride(context: Context) {
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putBoolean(KEY_MANUAL_OVERRIDE, false)
            .apply()
    }

    private fun manualProvince(context: Context): String {
        val raw = CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
            .getString(KEY_PROVINCE, DEFAULT_PROVINCE)
            ?.trim()
            .orEmpty()
        return raw.ifEmpty { DEFAULT_PROVINCE }
    }

    fun setProvince(context: Context, province: String) {
        val normalized = normalizeProvince(province)
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putString(KEY_PROVINCE, normalized)
            .putBoolean(KEY_MANUAL_OVERRIDE, true)
            .apply()
    }

    fun cycleProvince(context: Context): String {
        val list = PROVINCES
        val current = province(context)
        val idx = list.indexOf(current).let { if (it < 0) 0 else (it + 1) % list.size }
        setProvince(context, list[idx])
        return list[idx]
    }

    /** 点按切换：从自动模式进入手动；已在手动时轮换省份。 */
    fun onProvinceSettingClick(context: Context): ProvinceSource {
        val appCtx = context.applicationContext
        if (!isManualOverride(appCtx)) {
            val auto = resolveFromVehicle(appCtx) ?: manualProvince(appCtx)
            setProvince(appCtx, auto)
            return ProvinceSource(manualProvince(appCtx), fromVehicleAuto = false)
        }
        val next = cycleProvince(appCtx)
        return ProvinceSource(next, fromVehicleAuto = false)
    }

    /** 长按设置项：恢复按车辆坐标自动选省。 */
    fun restoreAutoFromVehicle(context: Context): ProvinceSource {
        clearManualOverride(context.applicationContext)
        return refreshAutoProvinceFromVehicle(context)
    }

    private fun resolveFromVehicle(context: Context, forceRegeo: Boolean = false): String? {
        val appCtx = context.applicationContext
        val coords = VehiclePositionHelper.fromStatus(CarVehicleDisplayCache.loadStatus(appCtx))
            ?: VehiclePositionHelper.loadCache(appCtx)
            ?: runCatching {
                VehiclePositionHelper.loadFromVehicle(appCtx, preferCacheOnFailure = true)
            }.getOrNull()
        if (coords == null) {
            return provinceFromCachedAddress(appCtx)
        }
        val prefs = CarControlVehiclePrefsSync.carPrefs(appCtx)
        if (!forceRegeo) {
            val cached = prefs.getString(KEY_AUTO_PROVINCE, null)?.trim().orEmpty()
            val cLng = prefs.getString(KEY_AUTO_LNG, null)?.toDoubleOrNull()
            val cLat = prefs.getString(KEY_AUTO_LAT, null)?.toDoubleOrNull()
            if (cached.isNotEmpty() && cLng != null && cLat != null &&
                kotlin.math.abs(cLng - coords.lng) < COORD_EPS &&
                kotlin.math.abs(cLat - coords.lat) < COORD_EPS
            ) {
                return normalizeProvince(cached)
            }
        }
        val fromRegeo = regeoProvince(coords.lng, coords.lat)
        val fromAddr = if (fromRegeo == null) provinceFromCachedAddress(appCtx) else null
        val resolved = fromRegeo ?: fromAddr ?: return null
        prefs.edit()
            .putString(KEY_AUTO_PROVINCE, resolved)
            .putString(KEY_AUTO_LNG, coords.lng.toString())
            .putString(KEY_AUTO_LAT, coords.lat.toString())
            .apply()
        LogHelper.d(TAG, "auto province=$resolved @ ${coords.lng},${coords.lat}")
        return resolved
    }

    private fun regeoProvince(lng: Double, lat: Double): String? {
        val regeo = AmapApiService.regeo(lng, lat) ?: return null
        val raw = regeo.province.trim().ifEmpty { regeo.city.trim() }
        if (raw.isEmpty()) return null
        return normalizeProvince(raw)
    }

    private fun provinceFromCachedAddress(context: Context): String? {
        val addr = VehiclePositionHelper.loadAddressCache(context) ?: return null
        return provinceFromAddressText(addr)
    }

    fun provinceFromAddressText(address: String): String? {
        val text = address.trim()
        if (text.isEmpty()) return null
        for (p in PROVINCES) {
            if (text.contains(p)) return p
        }
        return null
    }

    /** 单条历史记录所在省份：优先该条记录的坐标逆地理，其次地址，最后全局默认。 */
    fun provinceForRecord(
        context: Context,
        record: VehicleHistoryDatabase.VehicleDataRecord,
    ): String {
        val lat = record.latitude
        val lng = record.longitude
        if (lat != null && lng != null) {
            provinceAtCoordinates(lng, lat)?.let { return it }
        }
        record.address?.let { provinceFromAddressText(it) }?.let { return it }
        return province(context)
    }

    fun provinceAtCoordinates(lng: Double, lat: Double): String? = regeoProvince(lng, lat)

    /** 车控/地图逆地理成功后写入，供历史油价复用，避免重复请求。 */
    fun cacheAutoProvinceAt(
        context: Context,
        lng: Double,
        lat: Double,
        provinceRaw: String,
        cityRaw: String = "",
    ) {
        if (isManualOverride(context)) return
        val raw = provinceRaw.trim().ifEmpty { cityRaw.trim() }
        if (raw.isEmpty()) return
        val resolved = normalizeProvince(raw)
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putString(KEY_AUTO_PROVINCE, resolved)
            .putString(KEY_AUTO_LNG, lng.toString())
            .putString(KEY_AUTO_LAT, lat.toString())
            .apply()
    }

    fun tankCapacityLiters(context: Context): Float {
        val v = CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
            .getFloat(KEY_TANK_LITERS, DEFAULT_TANK_LITERS)
        return if (v in 35f..70f) v else DEFAULT_TANK_LITERS
    }

    fun cycleTankCapacity(context: Context): Float {
        val options = floatArrayOf(45f, 50f, 55f, 60f)
        val current = tankCapacityLiters(context)
        val idx = options.indexOfFirst { kotlin.math.abs(it - current) < 0.5f }
            .let { if (it < 0) 1 else (it + 1) % options.size }
        val next = options[idx]
        CarControlVehiclePrefsSync.carPrefs(context.applicationContext).edit()
            .putFloat(KEY_TANK_LITERS, next)
            .apply()
        return next
    }

    fun normalizeProvince(name: String): String {
        var s = name.trim()
        if (s.isEmpty()) return DEFAULT_PROVINCE
        val suffixes = listOf(
            "壮族自治区", "回族自治区", "维吾尔自治区", "特别行政区", "自治区", "省", "市",
        )
        var changed = true
        while (changed) {
            changed = false
            for (suffix in suffixes) {
                if (s.endsWith(suffix)) {
                    s = s.removeSuffix(suffix)
                    changed = true
                    break
                }
            }
        }
        PROVINCES.firstOrNull { it == s || s.startsWith(it) || it.startsWith(s) }?.let { return it }
        return DEFAULT_PROVINCE
    }
}
