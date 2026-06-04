package com.wmqc.miroot.car

import com.wmqc.miroot.car.VehicleStatusService.VehicleStatusInfo

/** 从车况 API 拆分油量升（L）与百分比（%），避免把同一数值既当升又当%。 */
object VehicleFuelParser {

    data class ParsedFuel(
        val liters: Double?,
        val percent: Int?,
    )

    fun parse(status: VehicleStatusInfo): ParsedFuel {
        val percent = VehicleStatusService.parseFuelLevelPercent(status.fuelLevelStatus)
            .takeIf { it in 0..100 }
        val liters = parseLitersFromRaw(status.fuelLevel, percent)
        return ParsedFuel(liters = liters, percent = percent)
    }

    /**
     * 星瑞等车：fuelLevel 常为剩余升数；若与 fuelLevelStatus 数值接近则视为仅有百分比。
     * 含 L/升 后缀时一律按升解析。
     */
    private fun parseLitersFromRaw(raw: String?, percent: Int?): Double? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "未知") return null
        val hasLiterUnit = text.contains('L', ignoreCase = true) || text.contains('升')
        val number = parseNumber(text)
        if (number < 0) return null
        if (hasLiterUnit) return number
        if (percent != null && kotlin.math.abs(number - percent) <= 4.0) return null
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
