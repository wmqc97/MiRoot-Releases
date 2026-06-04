package com.wmqc.miroot.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.wmqc.miroot.lyrics.LogHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * 高德 Web 服务 / JS 地图不可用时的回退：OSM 瓦片底图 + Nominatim 逆地理。
 * 需网络；瓦片使用需保留 © OpenStreetMap 署名（界面已标注）。
 */
object OsmMapHelper {

    private const val TAG = "OsmMapHelper"
    private const val USER_AGENT = "MiRootCarControl/2.1 (com.wmqc.miroot)"
    private const val TILE_SIZE = 256

    /** 逆地理：返回中文优先的 display_name。 */
    fun reverseAddress(lat: Double, lng: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=jsonv2&lat=$lat&lon=$lng&accept-language=zh-CN,zh,en"
        return try {
            val body = httpGet(url) ?: return null
            val root = JSONObject(body)
            val name = root.optString("display_name", "").trim()
            if (name.isEmpty()) null else name
        } catch (e: Exception) {
            LogHelper.w(TAG, "reverseAddress: ${e.message}")
            null
        }
    }

    /** 拼接瓦片为位图并在车辆位置绘制标记。 */
    fun fetchMapBitmap(lat: Double, lng: Double, width: Int, height: Int): Bitmap? {
        val w = width.coerceIn(128, 1024)
        val h = height.coerceIn(128, 512)
        val zoom = when {
            w >= 640 -> 15
            w >= 400 -> 14
            else -> 13
        }
        return try {
            val centerPxX = lngToPixelX(lng, zoom)
            val centerPxY = latToPixelY(lat, zoom)
            val tilesX = (kotlin.math.ceil(w / TILE_SIZE.toDouble()).toInt() + 1).coerceIn(2, 4)
            val tilesY = (kotlin.math.ceil(h / TILE_SIZE.toDouble()).toInt() + 1).coerceIn(2, 4)
            val startTileX = floor(centerPxX / TILE_SIZE).toInt() - tilesX / 2
            val startTileY = floor(centerPxY / TILE_SIZE).toInt() - tilesY / 2
            val canvasW = tilesX * TILE_SIZE
            val canvasH = tilesY * TILE_SIZE
            val stitched = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitched)
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    val tile = fetchTile(zoom, startTileX + tx, startTileY + ty) ?: return null
                    canvas.drawBitmap(tile, (tx * TILE_SIZE).toFloat(), (ty * TILE_SIZE).toFloat(), null)
                }
            }
            val markerX = (centerPxX - startTileX * TILE_SIZE).toFloat()
            val markerY = (centerPxY - startTileY * TILE_SIZE).toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF3482FF.toInt()
                style = Paint.Style.FILL
            }
            canvas.drawCircle(markerX, markerY, 10f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(markerX, markerY, 4f, paint)

            val left = (markerX - w / 2f).toInt().coerceIn(0, canvasW - w)
            val top = (markerY - h / 2f).toInt().coerceIn(0, canvasH - h)
            Bitmap.createBitmap(stitched, left, top, w.coerceAtMost(canvasW - left), h.coerceAtMost(canvasH - top))
        } catch (e: Exception) {
            LogHelper.w(TAG, "fetchMapBitmap: ${e.message}")
            null
        }
    }

    private fun fetchTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl zoom
        val wrappedX = ((x % n) + n) % n
        val wrappedY = y.coerceIn(0, n - 1)
        val url = "https://tile.openstreetmap.org/$zoom/$wrappedX/$wrappedY.png"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.doInput = true
            conn.connect()
            if (conn.responseCode != 200) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            LogHelper.w(TAG, "tile $zoom/$wrappedX/$wrappedY: ${e.message}")
            null
        }
    }

    private fun lngToPixelX(lng: Double, zoom: Int): Double =
        (lng + 180.0) / 360.0 * (TILE_SIZE shl zoom)

    private fun latToPixelY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat)
        val n = TILE_SIZE shl zoom
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
    }

    private fun httpGet(urlString: String): String? {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.doInput = true
        conn.connect()
        if (conn.responseCode != 200) {
            LogHelper.w(TAG, "HTTP ${conn.responseCode} $urlString")
            return null
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
