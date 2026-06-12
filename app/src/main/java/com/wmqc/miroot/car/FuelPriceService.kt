package com.wmqc.miroot.car

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 95 号汽油挂牌价：优先网络拉取各省最新价并缓存；加油日写入日缓存供历史展示。
 * 注：免费接口多为「当前挂牌」，历史加油日若无缓存则标注为参考价。
 */
object FuelPriceService {
    private const val TAG = "FuelPriceService"
    private const val PREFS_DAY_CACHE = "fuel_price_95_day_cache_v2"
    private const val PREFS_LATEST = "fuel_price_95_latest_v1"
    private const val API_URL = "https://v2.xxapi.cn/api/oilPrice"
    private const val LATEST_MAX_AGE_MS = 12L * 60 * 60 * 1000

    private val memoryLatest = ConcurrentHashMap<String, Double>()

    /** 兜底 95# 价（元/升），与常见挂牌接近。 */
    private val FALLBACK_95: Map<String, Double> = mapOf(
        "北京" to 8.12, "天津" to 8.05, "河北" to 8.02, "山西" to 7.99, "内蒙古" to 8.01,
        "辽宁" to 8.08, "吉林" to 8.06, "黑龙江" to 8.04, "上海" to 8.08, "江苏" to 8.05,
        "浙江" to 8.05, "安徽" to 8.03, "福建" to 8.06, "江西" to 8.08, "山东" to 8.02,
        "河南" to 8.06, "湖北" to 8.10, "湖南" to 8.02, "广东" to 8.32, "广西" to 8.18,
        "海南" to 9.10, "重庆" to 8.03, "四川" to 8.06, "贵州" to 8.12, "云南" to 8.15,
        "西藏" to 8.80, "陕西" to 8.00, "甘肃" to 8.02, "青海" to 8.05, "宁夏" to 8.00,
        "新疆" to 7.95,
    )

    data class PriceQuote(
        val yuanPerLiter: Double,
        val fromCacheDay: Boolean,
        val isReferenceOnly: Boolean,
    )

    fun formatPriceYuan(yuan: Double): String = String.format(Locale.getDefault(), "%.2f", yuan)

    /** 识别到加油节点时写入该日 95# 价（当天为挂牌价，历史日为参考价）。 */
    fun writeRefuelNodePrice(context: Context, refuelDayKey: String, province: String? = null) {
        val appCtx = context.applicationContext
        val prov = FuelPriceRegionPrefs.normalizeProvince(
            province?.trim()?.takeIf { it.isNotEmpty() }
                ?: FuelPriceRegionPrefs.province(appCtx),
        )
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = dayFormat.format(Date())
        val isToday = refuelDayKey == today
        val latest = resolveLatestYuanPerLiter(appCtx, prov, forceRefresh = isToday)
        persistRefuelDayPrice(appCtx, refuelDayKey, prov, today, latest)
    }

    fun ensurePricesForRefuelDays(
        context: Context,
        province: String,
        dayKeys: Set<String>,
    ) {
        if (dayKeys.isEmpty()) return
        val prov = FuelPriceRegionPrefs.normalizeProvince(province)
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = dayFormat.format(Date())
        val includesToday = dayKeys.contains(today)
        val latest = resolveLatestYuanPerLiter(context, prov, forceRefresh = includesToday)
        for (dayKey in dayKeys) {
            persistRefuelDayPrice(context, dayKey, prov, today, latest)
        }
    }

    /**
     * 加油日油价写入 [VehicleHistoryDatabase.TABLE_FUEL_PRICE_DAY]。
     * 当天加油节点用挂牌价（非参考）；历史加油日无真实历史价时用当前价并标记参考。
     */
    private fun persistRefuelDayPrice(
        context: Context,
        dayKey: String,
        province: String,
        today: String,
        latestYuan: Double,
    ) {
        val isToday = dayKey == today
        val existing = VehicleHistoryFuelCache.loadFuelPriceDay(context, dayKey, province)
        if (existing != null && !isToday) return
        VehicleHistoryFuelCache.saveFuelPriceDay(
            context,
            dayKey,
            province,
            latestYuan,
            isReference = !isToday,
        )
        if (isToday) {
            syncLegacyPrefsDayCache(context, dayKey, province, latestYuan)
        }
    }

    private fun syncLegacyPrefsDayCache(context: Context, dayKey: String, province: String, price: Double) {
        val prefs = CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
        val cache = JSONObject(prefs.getString(PREFS_DAY_CACHE, "{}") ?: "{}")
        val dayObj = cache.optJSONObject(dayKey) ?: JSONObject().also { cache.put(dayKey, it) }
        if (dayObj.has(province)) return
        dayObj.put(province, price)
        prefs.edit().putString(PREFS_DAY_CACHE, cache.toString()).apply()
    }

    fun price95ForDay(context: Context, province: String, dayKey: String): PriceQuote {
        val prov = FuelPriceRegionPrefs.normalizeProvince(province)
        VehicleHistoryFuelCache.loadFuelPriceDay(context, dayKey, prov)?.let { return it }
        val prefs = CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
        val cache = JSONObject(prefs.getString(PREFS_DAY_CACHE, "{}") ?: "{}")
        val dayObj = cache.optJSONObject(dayKey)
        val cached = dayObj?.optDouble(prov)?.takeIf { it > 0 }
        if (cached != null) {
            val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val today = dayFormat.format(Date())
            VehicleHistoryFuelCache.saveFuelPriceDay(
                context,
                dayKey,
                prov,
                cached,
                isReference = dayKey != today,
            )
            return PriceQuote(cached, fromCacheDay = true, isReferenceOnly = dayKey != today)
        }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = dayFormat.format(Date())
        val isToday = dayKey == today
        val latest = resolveLatestYuanPerLiter(context, prov, forceRefresh = isToday)
        val isRef = !isToday
        persistRefuelDayPrice(context, dayKey, prov, today, latest)
        return PriceQuote(
            yuanPerLiter = latest,
            fromCacheDay = false,
            isReferenceOnly = isRef,
        )
    }

    private fun resolveLatestYuanPerLiter(
        context: Context,
        province: String,
        forceRefresh: Boolean,
    ): Double {
        val appCtx = context.applicationContext
        if (forceRefresh) {
            memoryLatest.clear()
            fetchAllProvinces(appCtx)
        } else if (latestPrice(appCtx, province) == null) {
            fetchAllProvinces(appCtx)
        }
        return latestPrice(appCtx, province)?.yuanPerLiter
            ?: FALLBACK_95[province]
            ?: 8.0
    }

    private fun latestPrice(context: Context, province: String): PriceQuote? {
        memoryLatest[province]?.let {
            return PriceQuote(it, fromCacheDay = false, isReferenceOnly = false)
        }
        val prefs = CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
        val raw = prefs.getString(PREFS_LATEST, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val ts = o.optLong("fetchedAt", 0L)
            if (System.currentTimeMillis() - ts > LATEST_MAX_AGE_MS) return@runCatching null
            val p = o.optJSONObject("prices") ?: return@runCatching null
            val v = p.optDouble(province, Double.NaN)
            if (v.isNaN() || v <= 0) null else PriceQuote(v, false, false)
        }.getOrNull()
    }

    private fun refreshLatestIfStale(context: Context, province: String) {
        if (latestPrice(context, province) != null) return
        fetchAllProvinces(context)
    }

    @Synchronized
    fun fetchAllProvinces(context: Context): Boolean {
        return try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "oil API http $code")
                return false
            }
            parseAndStore(context, body)
        } catch (e: Exception) {
            Log.w(TAG, "fetchAllProvinces failed", e)
            false
        }
    }

    private fun parseAndStore(context: Context, body: String): Boolean {
        val root = JSONObject(body)
        if (root.optInt("code", 0) != 200) return false
        val arr = root.optJSONObject("data")?.optJSONArray("data") ?: return false
        val prices = JSONObject()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val name = row.optString("regionName", "").trim()
            val p95 = row.optDouble("n95", Double.NaN)
            if (name.isEmpty() || p95.isNaN() || p95 <= 0) continue
            val prov = FuelPriceRegionPrefs.normalizeProvince(name)
            prices.put(prov, p95)
            memoryLatest[prov] = p95
        }
        if (prices.length() == 0) return false
        val prefs = CarControlVehiclePrefsSync.carPrefs(context.applicationContext)
        prefs.edit()
            .putString(
                PREFS_LATEST,
                JSONObject()
                    .put("fetchedAt", System.currentTimeMillis())
                    .put("prices", prices)
                    .toString(),
            )
            .apply()
        return true
    }
}
