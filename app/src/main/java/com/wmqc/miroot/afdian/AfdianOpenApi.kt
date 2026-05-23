package com.wmqc.miroot.afdian

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 爱发电开放接口（与 [ifdian.net 文档](https://ifdian.net/dashboard/dev) 及 niuhuan/afdian-go 一致）。
 * Base: `https://ifdian.net/api/open`
 */
object AfdianOpenApi {

    private const val BASE = "https://ifdian.net/api/open"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val dig = md.digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    private fun sign(token: String, paramsJson: String, ts: Long, userId: String): String {
        val raw = "${token}params${paramsJson}ts${ts}user_id$userId"
        return md5Hex(raw)
    }

    /**
     * @param outTradeNo 爱发电订单号（out_trade_no）
     * @return 解析后的订单；接口成功但无匹配订单时 [AfdianOrderResult.orders] 为空
     */
    suspend fun queryOrderByOutTradeNo(
        userId: String,
        token: String,
        outTradeNo: String,
    ): AfdianOrderResult = withContext(Dispatchers.IO) {
        val trimmed = outTradeNo.trim()
        if (userId.isBlank() || token.isBlank()) {
            return@withContext AfdianOrderResult(ec = -1, em = "未配置 AFDIAN_USER_ID / AFDIAN_TOKEN", orders = emptyList())
        }
        if (trimmed.isEmpty()) {
            return@withContext AfdianOrderResult(ec = -1, em = "订单号为空", orders = emptyList())
        }

        val params = JSONObject().apply {
            put("page", 1)
            put("out_trade_no", trimmed)
        }.toString()
        val ts = System.currentTimeMillis() / 1000
        val sign = sign(token, params, ts, userId)
        val bodyJson = JSONObject().apply {
            put("user_id", userId)
            put("params", params)
            put("ts", ts)
            put("sign", sign)
        }.toString()

        val req = Request.Builder()
            .url("$BASE/query-order")
            .post(bodyJson.toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return@withContext parseQueryOrderResponse(text)
        }
    }

    private fun parseQueryOrderResponse(body: String): AfdianOrderResult {
        return try {
            val root = JSONObject(body)
            val ec = when {
                root.has("ec") -> root.optInt("ec")
                root.has("code") -> root.optInt("code")
                else -> -1
            }
            val em = root.optString("em", root.optString("message", ""))
            val data = root.optJSONObject("data")
            val list = data?.optJSONArray("list") ?: JSONArray()
            val orders = mutableListOf<AfdianOrder>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                orders += parseOrder(o)
            }
            AfdianOrderResult(ec = ec, em = em, orders = orders)
        } catch (e: Exception) {
            AfdianOrderResult(ec = -1, em = e.message ?: "解析失败", orders = emptyList())
        }
    }

    private fun parseOrder(o: JSONObject): AfdianOrder {
        val outNo = o.optString("out_trade_no")
        val status = if (o.has("status") && !o.isNull("status")) {
            when (val v = o.opt("status")) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: -1
                else -> -1
            }
        } else {
            -1
        }
        return AfdianOrder(
            outTradeNo = outNo,
            status = status,
            planId = o.optString("plan_id"),
        )
    }
}

data class AfdianOrderResult(
    val ec: Int,
    val em: String,
    val orders: List<AfdianOrder>,
)

data class AfdianOrder(
    val outTradeNo: String,
    val status: Int,
    val planId: String,
)
