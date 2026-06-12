package com.wmqc.miroot.car

import org.json.JSONObject
import java.util.Locale

/** 历史列表油量列：只展示升数（L），不展示、不换算百分比。 */
object VehicleHistoryFuelDisplay {

    private const val UNKNOWN = "—"

    fun formatFuelLevelCell(record: VehicleHistoryDatabase.VehicleDataRecord): String {
        resolveLiters(record)?.let { return "${fmt1(it)}L" }
        return UNKNOWN
    }

    fun fuelReading(record: VehicleHistoryDatabase.VehicleDataRecord): FuelReading {
        return FuelReading(liters = resolveLiters(record))
    }

    private fun resolveLiters(record: VehicleHistoryDatabase.VehicleDataRecord): Double? {
        if (record.fuelLiters >= 0) return record.fuelLiters
        VehicleFuelParser.parseLitersFromSnapshot(record.snapshotJson)?.let { return it }
        val snap = record.snapshotJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (snap != null) {
            val snapLiters = snap.optDouble("fuelLiters", -1.0)
            if (snapLiters >= 0) {
                val pct = snap.optInt("fuelPercent", -1).takeIf { it in 0..100 }
                if (pct == null || kotlin.math.abs(snapLiters - pct) > 4.0) {
                    return snapLiters
                }
            }
            if (snap.optBoolean("fuelValueIsLiters", false)) {
                val liters = snap.optDouble("fuelLiters", -1.0)
                if (liters >= 0) return liters
            }
        }
        val legacy = record.fuelLevel
        if (legacy in 8.0..65.0) return legacy
        return null
    }

    data class FuelReading(
        val liters: Double?,
    )

    private fun fmt1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
}
