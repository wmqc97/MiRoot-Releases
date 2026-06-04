package com.wmqc.miroot.car

import org.json.JSONObject
import java.util.Locale

/** 历史列表油量列：优先读库表 [fuelLiters]/[fuelPercent]，再回退快照与旧字段。 */
object VehicleHistoryFuelDisplay {

    fun formatFuelLevelCell(record: VehicleHistoryDatabase.VehicleDataRecord): String {
        if (record.fuelLiters >= 0) return "${fmt1(record.fuelLiters)}L"
        if (record.fuelPercent in 0..100) return "${record.fuelPercent}%"
        val snap = record.snapshotJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (snap != null) {
            if (snap.optBoolean("fuelValueIsLiters", false)) {
                val liters = snap.optDouble("fuelLiters", record.fuelLevel)
                return "${fmt1(liters)}L"
            }
            val snapLiters = snap.optDouble("fuelLiters", -1.0)
            val pct = snap.optInt("fuelPercent", -1).takeIf { it in 0..100 }
            if (snapLiters in 0.0..70.0) {
                if (pct == null || kotlin.math.abs(snapLiters - pct) > 4.0) {
                    return "${fmt1(snapLiters)}L"
                }
            }
            if (pct != null) return "$pct%"
        }
        return formatFuelLevelFromLegacyColumn(record.fuelLevel)
    }

    fun formatFuelLevelFromLegacyColumn(fuelLevel: Double): String {
        if (fuelLevel in 8.0..65.0) return "${fmt1(fuelLevel)}L"
        if (fuelLevel in 0.0..100.0 && fuelLevel == fuelLevel.toInt().toDouble()) {
            return "${fuelLevel.toInt()}%"
        }
        return "${fmt1(fuelLevel)}L"
    }

    fun fuelReading(record: VehicleHistoryDatabase.VehicleDataRecord): FuelReading {
        if (record.fuelLiters >= 0) {
            val pct = record.fuelPercent.takeIf { it in 0..100 }
            return FuelReading(liters = record.fuelLiters, percent = pct)
        }
        if (record.fuelPercent in 0..100) {
            return FuelReading(liters = null, percent = record.fuelPercent)
        }
        val snap = record.snapshotJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val pct = snap?.optInt("fuelPercent", -1)?.takeIf { it in 0..100 }
        if (snap?.optBoolean("fuelValueIsLiters", false) == true) {
            val liters = snap.optDouble("fuelLiters", record.fuelLevel)
            return FuelReading(liters = liters, percent = pct)
        }
        if (pct != null) return FuelReading(liters = null, percent = pct)
        val fl = record.fuelLevel
        if (fl in 8.0..65.0) return FuelReading(liters = fl, percent = pct)
        if (fl in 0.0..100.0) return FuelReading(liters = null, percent = fl.toInt())
        return FuelReading(liters = fl, percent = pct)
    }

    data class FuelReading(
        val liters: Double?,
        val percent: Int?,
    )

    private fun fmt1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
}
