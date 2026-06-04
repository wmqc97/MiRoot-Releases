package com.wmqc.miroot.car

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 车辆历史：操作码 / 告警文案 → 中文展示。 */
object VehicleHistoryDisplayLabels {

    private val operateZh = mapOf(
        "unlock" to "解锁",
        "lock" to "锁车",
        "findcar" to "寻车",
        "opentrunk" to "打开后备箱",
        "openwindow" to "开窗",
        "closewindow" to "关窗",
        "ventilate" to "透气",
        "startengine" to "点火",
        "stopengine" to "熄火",
        "openairconditioner" to "打开空调",
        "closeairconditioner" to "关闭空调",
        "openseatheating" to "座椅加热",
        "closeseatheating" to "关闭座椅加热",
        "openseatheatingdriver" to "主驾座椅加热",
        "openseatheatingpassenger" to "副驾座椅加热",
        "navigatetocar" to "导航到车",
        "rdu" to "解锁",
        "rdl" to "锁车",
        "res" to "远程启动",
        "rce" to "空调",
        "rws" to "车窗",
        "rtu" to "后备箱",
        "rsh" to "座椅加热",
        "rhf" to "寻车",
    )

    fun operateToChinese(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "未知操作"
        if (text.any { it.code > 127 }) return text
        val key = text.lowercase(Locale.ROOT)
        operateZh[key]?.let { return it }
        return text
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .split(' ')
            .joinToString("") { part ->
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
                }
            }
            .ifEmpty { text }
    }

    fun formatOperateArgs(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (text.any { it.code > 127 }) return text
        return runCatching {
            val o = JSONObject(text)
            buildList {
                o.optString("serviceId", "").takeIf { it.isNotBlank() }?.let {
                    add("服务 ${serviceIdToChinese(it)}")
                }
                o.optString("command", "").takeIf { it.isNotBlank() }?.let {
                    add("指令 ${commandToChinese(it)}")
                }
                o.optString("seat", "").takeIf { it.isNotBlank() }?.let {
                    add("座位 ${seatToChinese(it)}")
                }
                if (o.has("durationMinutes")) {
                    add("时长 ${o.optInt("durationMinutes")} 分钟")
                } else if (o.has("duration")) {
                    val sec = o.optInt("duration")
                    if (sec > 0) add("时长 ${sec / 60} 分钟")
                }
                if (o.has("temperature")) {
                    add("温度 ${o.optInt("temperature")}℃")
                }
                o.optString("function", "").takeIf { it.isNotBlank() }?.let {
                    add(operateToChinese(it))
                }
                o.keys().forEach { k ->
                    if (k in setOf("serviceId", "command", "seat", "duration", "durationMinutes", "temperature", "function")) {
                        return@forEach
                    }
                    val v = o.opt(k)?.toString().orEmpty()
                    if (v.isNotBlank() && v != "null") add("$k: $v")
                }
            }.joinToString(" · ")
        }.getOrElse { text }
    }

    fun warningToChinese(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "未知告警"
        if (text.any { it.code > 127 }) return text
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("tyre") || lower.contains("tire") -> "胎压异常"
            lower.contains("oil") -> "机油压力警告"
            lower.contains("service") -> "保养提醒"
            lower.contains("battery") || lower.contains("voltage") -> "电瓶告警"
            lower.contains("door") -> "车门告警"
            lower.contains("window") || lower.contains("win") -> "车窗提醒"
            lower.contains("theft") -> "防盗告警"
            lower == "0" -> "正常"
            lower == "1" -> "警告"
            else -> text
        }
    }

    private fun serviceIdToChinese(id: String): String =
        operateZh[id.lowercase(Locale.ROOT)] ?: id

    private fun commandToChinese(cmd: String): String = when (cmd.lowercase(Locale.ROOT)) {
        "start" -> "开启"
        "stop" -> "关闭"
        else -> cmd
    }

    private fun seatToChinese(seat: String): String = when (seat.lowercase(Locale.ROOT)) {
        "driver", "front-left" -> "主驾"
        "passenger", "front-right" -> "副驾"
        "all" -> "全车"
        else -> seat
    }

    private val unknownFuelValues = setOf("", "未知", "—", "-")

    /** 车机 ECU 平均油耗展示（L/100km）。 */
    fun formatEcuAvgFuelConsumption(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.lowercase(Locale.ROOT) in unknownFuelValues) return null
        if (text.contains("L/100", ignoreCase = true)) return text
        return "$text L/100km"
    }

    /** 从历史快照 [queryData] 解析「平均油耗」。 */
    fun ecuAvgFuelFromSnapshot(snapshotJson: String?): String? {
        if (snapshotJson.isNullOrBlank()) return null
        return runCatching {
            val query = JSONObject(snapshotJson).optJSONObject("queryData") ?: return@runCatching null
            fuelFromMileageEnergyItems(query.optJSONArray("mileageEnergyItems"))
                ?: query.optJSONObject("mileageEnergy")?.optString("平均油耗")?.let { formatEcuAvgFuelConsumption(it) }
        }.getOrNull()
    }

    private fun fuelFromMileageEnergyItems(items: JSONArray?): String? {
        if (items == null) return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("name") != "平均油耗") continue
            val state = item.optString("state", "").trim()
            val value = item.optString("value", "").trim()
            return formatEcuAvgFuelConsumption(state.ifEmpty { value })
        }
        return null
    }
}

fun VehicleHistoryDatabase.OperateRecord.displayOperateChinese(): String =
    VehicleHistoryDisplayLabels.operateToChinese(operate)

fun VehicleHistoryDatabase.OperateRecord.displayArgsChinese(): String =
    VehicleHistoryDisplayLabels.formatOperateArgs(args)

fun VehicleHistoryDatabase.WarningRecord.displayWarningChinese(): String =
    VehicleHistoryDisplayLabels.warningToChinese(warning)
