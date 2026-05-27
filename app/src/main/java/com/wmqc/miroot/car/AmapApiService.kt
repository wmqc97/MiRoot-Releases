package com.wmqc.miroot.car

import com.wmqc.miroot.lyrics.LogHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德地图 Web Service API 封装。
 * 参考文档：https://lbs.amap.com/api/webservice/guide/api
 *
 * 已接入 API：
 * - 逆地理编码（regeo）  —  坐标 → 结构化地址
 * - 静态地图（staticmap）  —  URL 构建
 * - 驾车路径规划（direction/driving） — 距离/时间
 */
object AmapApiService {

    private const val TAG = "AmapApiService"
    const val AMAP_KEY = "d8d772c43a33e003b5c77d12e6a52e09"
    private const val BASE_URL = "https://restapi.amap.com/v3"

    // ── 逆地理编码 ────────────────────────────────────────────────────────────

    /**
     * 逆地理编码结果。
     * @param formattedAddress 结构化地址，如 "广东省广州市天河区临江大道"
     * @param country / province / city / district / township 逐级区划
     * @param nearbyPoi 附近 POI 名称（取最近一条），无则为 null
     */
    data class RegeoResult(
        val formattedAddress: String,
        val country: String,
        val province: String,
        val city: String,
        val district: String,
        val township: String,
        val street: String,
        val nearbyPoi: String?,
    )

    /** 坐标逆地理编码（extensions=base）。失败返回 null。 */
    fun regeo(lng: Double, lat: Double): RegeoResult? {
        return try {
            val loc = "${lng},${lat}"
            val url = "$BASE_URL/geocode/regeo?key=$AMAP_KEY&location=${URLEncoder.encode(loc, "UTF-8")}&extensions=base"
            val resp = httpGet(url) ?: return null
            val root = JSONObject(resp)
            if (root.optInt("status") != 1) {
                LogHelper.w(TAG, "regeo status != 1: $resp")
                return null
            }
            val regeo = root.optJSONObject("regeocode") ?: return null
            val addr = regeo.optJSONObject("addressComponent") ?: JSONObject()
            val pois = regeo.optJSONArray("pois")
            val poi = if (pois != null && pois.length() > 0) pois.optJSONObject(0)?.optString("name") else null
            RegeoResult(
                formattedAddress = regeo.optString("formatted_address", ""),
                country = addr.optString("country", ""),
                province = addr.optString("province", ""),
                city = addr.optString("city", "").ifEmpty { addr.optString("province", "") },
                district = addr.optString("district", ""),
                township = addr.optString("township", ""),
                street = addr.optJSONObject("streetNumber")?.optString("street", "") ?: "",
                nearbyPoi = poi?.takeIf { it.isNotEmpty() },
            )
        } catch (e: Exception) {
            LogHelper.e(TAG, "regeo error", e)
            null
        }
    }

    /** 返回简洁的可展示地址短文本（优先街道+门牌，其次区+街道，最后城市+区）。 */
    fun regeoShortAddress(lng: Double, lat: Double): String? {
        val r = regeo(lng, lat) ?: return null
        return when {
            r.street.isNotEmpty() -> r.street
            r.district.isNotEmpty() && r.township.isNotEmpty() -> "${r.district}${r.township}"
            r.city.isNotEmpty() && r.district.isNotEmpty() -> "${r.city}${r.district}"
            r.province.isNotEmpty() && r.city.isNotEmpty() -> "${r.province}${r.city}"
            else -> r.formattedAddress
        }
    }

    // ── 驾车路径规划 ──────────────────────────────────────────────────────────

    data class DrivingRoute(
        val distanceMeters: Int,
        val durationSeconds: Int,
        val distanceText: String,
        val durationText: String,
    )

    /**
     * 驾车路径规划（策略=最短时间）。
     * @param origin 起点坐标 "lng,lat"
     * @param destination 终点坐标 "lng,lat"
     */
    fun drivingRoute(originLng: Double, originLat: Double, destLng: Double, destLat: Double): DrivingRoute? {
        return try {
            val origin = "${originLng},${originLat}"
            val dest = "${destLng},${destLat}"
            val url = "$BASE_URL/direction/driving?key=$AMAP_KEY" +
                "&origin=${URLEncoder.encode(origin, "UTF-8")}" +
                "&destination=${URLEncoder.encode(dest, "UTF-8")}" +
                "&strategy=0&extensions=base"
            val resp = httpGet(url) ?: return null
            val root = JSONObject(resp)
            if (root.optInt("status") != 1) return null
            val route = root.optJSONObject("route")
                ?.optJSONArray("paths")
                ?.optJSONObject(0) ?: return null
            val dist = route.optInt("distance", 0)
            val dur = route.optInt("duration", 0)
            DrivingRoute(
                distanceMeters = dist,
                durationSeconds = dur,
                distanceText = formatDistance(dist),
                durationText = formatDuration(dur),
            )
        } catch (e: Exception) {
            LogHelper.e(TAG, "drivingRoute error", e)
            null
        }
    }

    private fun formatDistance(meters: Int): String = when {
        meters >= 1000 -> "%.1f km".format(meters / 1000.0)
        meters > 0 -> "${meters}m"
        else -> "—"
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds >= 3600 -> "${seconds / 3600}小时${(seconds % 3600) / 60}分钟"
        seconds >= 60 -> "${seconds / 60}分钟"
        seconds > 0 -> "${seconds}秒"
        else -> "—"
    }

    // ── 静态地图 URL ──────────────────────────────────────────────────────────

    /** 构建静态地图图片 URL（尺寸单位 px）。 */
    fun staticMapUrl(lng: Double, lat: Double, width: Int = 600, height: Int = 300, zoom: Int = 15): String {
        return "$BASE_URL/staticmap?location=$lng,$lat&zoom=$zoom" +
            "&size=${width}*${height}" +
            "&markers=mid,,A:$lng,$lat" +
            "&key=$AMAP_KEY"
    }

    // ── HTTP 工具 ─────────────────────────────────────────────────────────────

    private fun httpGet(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.connect()
            val code = conn.responseCode
            if (code != 200) {
                LogHelper.w(TAG, "HTTP $code for $urlString")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            LogHelper.w(TAG, "httpGet error: ${e.message}")
            null
        }
    }
}
