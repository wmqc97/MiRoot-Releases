package com.wmqc.miroot.car

import com.wmqc.miroot.car.VehicleStatusService.VehicleStatusInfo

/** 从车况 API 解析剩余油量（升）；历史只写入升数，不解析、不换算百分比。 */
object VehicleFuelParser {

    fun parseLiters(status: VehicleStatusInfo): Double? {
        val percentHint = VehicleStatusService.parseFuelLevelPercent(status.fuelLevelStatus)
            .takeIf { it in 0..100 }
        return parseLitersFromRaw(
            status.fuelLevel,
            percentHint,
            hasSeparatePercentField = percentHint != null,
        )
    }

    /** 从快照 queryData 恢复升数（旧记录误存百分比时的兜底）。 */
    fun parseLitersFromSnapshot(snapshotJson: String?): Double? {
        val snap = snapshotJson?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return null
        val query = snap.optJSONObject("queryData") ?: return null
        val me = query.optJSONObject("mileageEnergy") ?: return null
        val rawLiters = me.optString("油量", "").trim()
        if (rawLiters.isEmpty()) return null
        val pct = VehicleStatusService.parseFuelLevelPercent(me.optString("油量%", ""))
            .takeIf { it in 0..100 }
        return parseLitersFromRaw(rawLiters, pct, hasSeparatePercentField = pct != null)
    }

    /**
     * fuelLevel 为剩余升数；fuelLevelStatus 仅作辅助判断。
     * 与 fuelLevelStatus 数值接近时视为重复的 %，不按升解析（如 15 与 15%）。
     */
    private fun parseLitersFromRaw(
        raw: String?,
        percentHint: Int?,
        hasSeparatePercentField: Boolean,
    ): Double? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "未知") return null
        if (text.contains('%')) return null
        val hasLiterUnit = text.contains('L', ignoreCase = true) || text.contains('升')
        val number = parseNumber(text)
        if (number < 0) return null
        if (hasLiterUnit) return number
        if (hasSeparatePercentField) {
            if (percentHint != null && kotlin.math.abs(number - percentHint) <= 4.0) return null
            return number.takeIf { it > 0 }
        }
        if (percentHint != null && kotlin.math.abs(number - percentHint) <= 4.0) return null
        if (number in 8.0..70.0) return number
        if (number > 70.0) return number
        return null
    }

    private fun parseNumber(raw: String): Double {
        val cleaned = raw.replace(Regex("[^0-9.]"), "")
        if (cleaned.isEmpty()) return -1.0
        return cleaned.toDoubleOrNull() ?: -1.0
    }
}
