package com.wmqc.miroot.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wmqc.miroot.BuildConfig
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
 *
 * 高德规定 **1 个 Key 只绑定 1 种服务平台**：
 * - [AMAP_KEY]：Android 平台（包名 + SHA1），用于 Manifest / 3D 地图 SDK / 搜索 SDK
 * - [webServiceKey]：Web 服务（HTTP restapi.amap.com），用于逆地理、静态地图
 *
 * 若仅配置 Android Key 却调用 Web 服务，会返回 `USERKEY_PLAT_NOMATCH`（10009）。
 * 请在 `local.properties` 增加 `AMAP_WEB_SERVICE_KEY=`（控制台单独创建 Web 服务 Key）。
 */
object AmapApiService {

    private const val TAG = "AmapApiService"
    /** Android 平台 Key（包名 com.wmqc.miroot + Debug/Release SHA1） */
    const val AMAP_KEY = "55b0d5e5d6ba59b9de9e5945a547deb9"

    /** Web 服务 Key：优先 BuildConfig，未配置时回退 [AMAP_KEY]（通常会平台不匹配） */
    val webServiceKey: String
        get() = BuildConfig.AMAP_WEB_SERVICE_KEY.trim().ifEmpty { AMAP_KEY }

    private const val BASE_URL = "https://restapi.amap.com/v3"

    /** 解析 Web 服务 JSON 错误，便于 Logcat 排查。 */
    fun describeApiError(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        return try {
            val root = JSONObject(responseBody.trim())
            if (root.optInt("status", 1) == 1) return null
            val info = root.optString("info", "UNKNOWN")
            val code = root.optString("infocode", "")
            when (code) {
                "10009" -> "$info ($code)：Key 平台不匹配，请使用 Web 服务 Key，见 docs/高德地图Key配置.md"
                "10001" -> "$info ($code)：Key 无效或未启用对应服务"
                "10003" -> "$info ($code)：访问已超出日配额"
                else -> if (code.isNotEmpty()) "$info ($code)" else info
            }
        } catch (_: Exception) {
            null
        }
    }

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

    /** 返回简洁的可展示地址短文本（优先街道+门牌，其次区+街道，最后城市+区）。 */
    fun shortDisplayAddress(regeo: RegeoResult): String? {
        return when {
            regeo.street.isNotEmpty() -> regeo.street
            regeo.district.isNotEmpty() && regeo.township.isNotEmpty() -> "${regeo.district}${regeo.township}"
            regeo.city.isNotEmpty() && regeo.district.isNotEmpty() -> "${regeo.city}${regeo.district}"
            regeo.province.isNotEmpty() && regeo.city.isNotEmpty() -> "${regeo.province}${regeo.city}"
            regeo.formattedAddress.isNotBlank() -> regeo.formattedAddress
            else -> null
        }
    }

    /** 坐标逆地理编码（extensions=base）。失败返回 null。 */
    fun regeo(lng: Double, lat: Double): RegeoResult? {
        return try {
            val loc = "${lng},${lat}"
            val url = "$BASE_URL/geocode/regeo?key=$webServiceKey&location=${URLEncoder.encode(loc, "UTF-8")}&extensions=base"
            val resp = httpGet(url) ?: return null
            val root = JSONObject(resp)
            if (root.optInt("status") != 1) {
                LogHelper.w(TAG, "regeo failed: ${describeApiError(resp) ?: resp}")
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
        return shortDisplayAddress(r)
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
            val url = "$BASE_URL/direction/driving?key=$webServiceKey" +
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

    /** 构建静态地图图片 URL（尺寸单位 px，需 Web 服务 Key）。 */
    fun staticMapUrl(lng: Double, lat: Double, width: Int = 600, height: Int = 300, zoom: Int = 15): String {
        val lngStr = "%.6f".format(lng)
        val latStr = "%.6f".format(lat)
        val location = "$lngStr,$latStr"
        val size = "${width.coerceIn(1, 1024)}*${height.coerceIn(1, 1024)}"
        val markers = "mid,,A:$location"
        return buildString {
            append(BASE_URL)
            append("/staticmap?location=")
            append(URLEncoder.encode(location, "UTF-8"))
            append("&zoom=").append(zoom.coerceIn(3, 18))
            append("&size=").append(URLEncoder.encode(size, "UTF-8"))
            append("&scale=2")
            append("&markers=").append(URLEncoder.encode(markers, "UTF-8"))
            append("&key=").append(webServiceKey)
        }
    }

    /**
     * 拉取静态地图位图。失败时返回 null（常见：Key 未开通 Web 服务 → USERKEY_PLAT_NOMATCH JSON）。
     */
    fun fetchStaticMapBitmap(
        lng: Double,
        lat: Double,
        width: Int = 480,
        height: Int = 240,
        zoom: Int = 15,
    ): Bitmap? {
        val url = staticMapUrl(lng, lat, width, height, zoom)
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "MiRoot-CarControl/1.0")
            conn.doInput = true
            conn.connect()
            val code = conn.responseCode
            val contentType = conn.contentType?.lowercase().orEmpty()
            val bytes = if (code == 200) {
                conn.inputStream.use { it.readBytes() }
            } else {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            if (bytes.isEmpty()) {
                LogHelper.w(TAG, "staticmap empty body HTTP $code")
                return null
            }
            if (contentType.contains("json") || (bytes.size < 4096 && bytes.firstOrNull() == '{'.code.toByte())) {
                val msg = String(bytes, Charsets.UTF_8)
                LogHelper.w(TAG, "staticmap API error: ${describeApiError(msg) ?: msg}")
                return null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                if (it.width < 32 || it.height < 32) {
                    LogHelper.w(TAG, "staticmap decoded too small: ${it.width}x${it.height}")
                    return null
                }
            }
        } catch (e: Exception) {
            LogHelper.w(TAG, "fetchStaticMapBitmap: ${e.message}")
            null
        }
    }

    // ── HTTP 工具 ─────────────────────────────────────────────────────────────

    private fun httpGet(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "MiRoot-CarControl/1.0")
            conn.doInput = true
            conn.connect()
            val code = conn.responseCode
            val body = if (code == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code != 200) {
                LogHelper.w(TAG, "HTTP $code: ${describeApiError(body) ?: body.take(200)}")
                return null
            }
            body
        } catch (e: Exception) {
            LogHelper.w(TAG, "httpGet error: ${e.message}")
            null
        }
    }
}
