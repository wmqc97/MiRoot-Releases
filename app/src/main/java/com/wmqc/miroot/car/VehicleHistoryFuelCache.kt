package com.wmqc.miroot.car

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import org.json.JSONObject

/**
 * 油耗/油价/加油统计的 SQLite 缓存，避免每次进入历史页全量重算。
 * 指纹变化（记录数、末条 ID、油箱容积、油价省份）时后台刷新。
 */
object VehicleHistoryFuelCache {

    data class MonthlyFuelSummary(
        val monthKey: String,
        val refuelCount: Int,
        val totalCostYuan: Double,
        val totalLiters: Double,
        val avgLitersPer100Km: Double?,
    )

    data class CachedAnalytics(
        val fingerprint: String,
        val insights: FuelTankAnalytics.FuelHistoryInsights,
        val ecuAvgFuel: String?,
        val monthlySummaries: List<MonthlyFuelSummary>,
        val computedAt: Long,
    )

    fun computeFingerprint(context: Context): String {
        val appCtx = context.applicationContext
        val count = VehicleHistoryDatabase.loadHistoryCounts(appCtx).first
        val maxId = VehicleHistoryDatabase.maxVehicleDataRecordId(appCtx)
        val tank = FuelPriceRegionPrefs.tankCapacityLiters(appCtx)
        val prov = FuelPriceRegionPrefs.province(appCtx)
        return "${count}_${maxId}_${tank}_${prov}"
    }

    fun needsRefresh(context: Context): Boolean {
        val fp = computeFingerprint(context)
        val cached = loadCachedAnalytics(context) ?: return true
        return cached.fingerprint != fp
    }

    fun loadCachedAnalytics(context: Context): CachedAnalytics? {
        val appCtx = context.applicationContext
        val db = VehicleHistoryDatabase.readableDb(appCtx)
        if (!VehicleHistoryDatabase.tableExistsPublic(db, VehicleHistoryDatabase.TABLE_FUEL_ANALYTICS_CACHE)) {
            return null
        }
        var row: JSONObject? = null
        db.query(
            VehicleHistoryDatabase.TABLE_FUEL_ANALYTICS_CACHE,
            null,
            "${VehicleHistoryDatabase.COL_CACHE_KEY} = ?",
            arrayOf(VehicleHistoryDatabase.CACHE_KEY_MAIN),
            null,
            null,
            null,
            "1",
        ).use { c ->
            if (c.moveToFirst()) row = cursorToCacheRow(c)
        }
        val o = row ?: return null
        val fp = o.optString("fingerprint", "")
        if (fp.isEmpty()) return null
        val badges = loadRefuelBadges(appCtx)
        val monthly = loadMonthlySummaries(appCtx)
        val insights = FuelTankAnalytics.FuelHistoryInsights(
            lastTankLitersPer100Km = o.optDouble("lastTankL100").takeIf { !it.isNaN() && it > 0 },
            lastTankDistanceKm = o.optDouble("lastTankKm").takeIf { !it.isNaN() && it > 0 },
            lastTankFuelLiters = o.optDouble("lastTankFuel").takeIf { !it.isNaN() && it > 0 },
            lastTankCostYuan = o.optDouble("lastTankCost").takeIf { !it.isNaN() && it > 0 },
            refuelRecordIds = badges.keys,
            recordBadges = badges,
            refuelCount = o.optInt("refuelCount", badges.size),
            monthlySummaries = monthly,
        )
        return CachedAnalytics(
            fingerprint = fp,
            insights = insights,
            ecuAvgFuel = o.optString("ecuAvgFuel").takeIf { it.isNotBlank() },
            monthlySummaries = monthly,
            computedAt = o.optLong("computedAt", 0L),
        )
    }

    fun loadIfValid(context: Context): FuelTankAnalytics.FuelHistoryInsights? {
        val cached = loadCachedAnalytics(context) ?: return null
        return if (cached.fingerprint == computeFingerprint(context)) cached.insights else null
    }

    fun persistAnalytics(
        context: Context,
        fingerprint: String,
        insights: FuelTankAnalytics.FuelHistoryInsights,
        ecuAvgFuel: String?,
        monthlySummaries: List<MonthlyFuelSummary>,
    ) {
        val appCtx = context.applicationContext
        val db = VehicleHistoryDatabase.writableDb(appCtx)
        db.beginTransaction()
        try {
            db.delete(VehicleHistoryDatabase.TABLE_FUEL_REFUEL_META, null, null)
            insights.recordBadges.forEach { (recordId, badge) ->
                if (!badge.isRefuel) return@forEach
                val cv = ContentValues().apply {
                    put(VehicleHistoryDatabase.COL_RECORD_ID, recordId)
                    put(VehicleHistoryDatabase.COL_DAY_KEY, dayKeyForRecord(appCtx, recordId))
                    put(VehicleHistoryDatabase.COL_IS_REFUEL, 1)
                    put(VehicleHistoryDatabase.COL_ADDED_LITERS, parseAddedLiters(badge.addedFuelText))
                    put(VehicleHistoryDatabase.COL_PRICE95, parsePrice(badge.price95Text))
                    put(VehicleHistoryDatabase.COL_IS_REF_PRICE, if (badge.isReferencePrice) 1 else 0)
                    put(VehicleHistoryDatabase.COL_TANK_L100, parseTankL100(badge.tankAvgText))
                    put(VehicleHistoryDatabase.COL_TRIP_COST, parseTripCost(badge.tripCostText))
                    put(VehicleHistoryDatabase.COL_REFUEL_COST, parseTripCost(badge.refuelCostText))
                }
                db.insert(VehicleHistoryDatabase.TABLE_FUEL_REFUEL_META, null, cv)
            }
            db.delete(VehicleHistoryDatabase.TABLE_FUEL_MONTHLY_SUMMARY, null, null)
            monthlySummaries.forEach { m ->
                val cv = ContentValues().apply {
                    put(VehicleHistoryDatabase.COL_MONTH_KEY, m.monthKey)
                    put(VehicleHistoryDatabase.COL_REFUEL_COUNT, m.refuelCount)
                    put(VehicleHistoryDatabase.COL_TOTAL_COST, m.totalCostYuan)
                    put(VehicleHistoryDatabase.COL_TOTAL_LITERS, m.totalLiters)
                    m.avgLitersPer100Km?.let { put(VehicleHistoryDatabase.COL_AVG_L100, it) }
                }
                db.insert(VehicleHistoryDatabase.TABLE_FUEL_MONTHLY_SUMMARY, null, cv)
            }
            val cacheCv = ContentValues().apply {
                put(VehicleHistoryDatabase.COL_CACHE_KEY, VehicleHistoryDatabase.CACHE_KEY_MAIN)
                put(VehicleHistoryDatabase.COL_FINGERPRINT, fingerprint)
                insights.lastTankLitersPer100Km?.let { put(VehicleHistoryDatabase.COL_LAST_TANK_L100, it) }
                insights.lastTankDistanceKm?.let { put(VehicleHistoryDatabase.COL_LAST_TANK_KM, it) }
                insights.lastTankFuelLiters?.let { put(VehicleHistoryDatabase.COL_LAST_TANK_FUEL, it) }
                insights.lastTankCostYuan?.let { put(VehicleHistoryDatabase.COL_LAST_TANK_COST, it) }
                put(VehicleHistoryDatabase.COL_REFUEL_COUNT, insights.refuelCount)
                ecuAvgFuel?.let { put(VehicleHistoryDatabase.COL_ECU_AVG_FUEL, it) }
                put(VehicleHistoryDatabase.COL_COMPUTED_AT, System.currentTimeMillis())
            }
            db.delete(
                VehicleHistoryDatabase.TABLE_FUEL_ANALYTICS_CACHE,
                "${VehicleHistoryDatabase.COL_CACHE_KEY} = ?",
                arrayOf(VehicleHistoryDatabase.CACHE_KEY_MAIN),
            )
            db.insert(VehicleHistoryDatabase.TABLE_FUEL_ANALYTICS_CACHE, null, cacheCv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveFuelPriceDay(
        context: Context,
        dayKey: String,
        province: String,
        price95: Double,
        isReference: Boolean,
    ) {
        val cv = ContentValues().apply {
            put(VehicleHistoryDatabase.COL_DAY_KEY, dayKey)
            put(VehicleHistoryDatabase.COL_PROVINCE, FuelPriceRegionPrefs.normalizeProvince(province))
            put(VehicleHistoryDatabase.COL_PRICE95, price95)
            put(VehicleHistoryDatabase.COL_IS_REF_PRICE, if (isReference) 1 else 0)
            put(VehicleHistoryDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        VehicleHistoryDatabase.writableDb(context.applicationContext)
            .insertWithOnConflict(
                VehicleHistoryDatabase.TABLE_FUEL_PRICE_DAY,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
    }

    fun loadFuelPriceDay(context: Context, dayKey: String, province: String): FuelPriceService.PriceQuote? {
        val prov = FuelPriceRegionPrefs.normalizeProvince(province)
        val db = VehicleHistoryDatabase.readableDb(context.applicationContext)
        if (!VehicleHistoryDatabase.tableExistsPublic(db, VehicleHistoryDatabase.TABLE_FUEL_PRICE_DAY)) {
            return null
        }
        db.query(
            VehicleHistoryDatabase.TABLE_FUEL_PRICE_DAY,
            null,
            "${VehicleHistoryDatabase.COL_DAY_KEY} = ? AND ${VehicleHistoryDatabase.COL_PROVINCE} = ?",
            arrayOf(dayKey, prov),
            null,
            null,
            null,
            "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            val price = c.getDouble(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_PRICE95))
            val ref = c.getInt(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_IS_REF_PRICE)) == 1
            return FuelPriceService.PriceQuote(price, fromCacheDay = true, isReferenceOnly = ref)
        }
    }

    fun clearAll(context: Context) {
        val db = VehicleHistoryDatabase.writableDb(context.applicationContext)
        listOf(
            VehicleHistoryDatabase.TABLE_FUEL_PRICE_DAY,
            VehicleHistoryDatabase.TABLE_FUEL_REFUEL_META,
            VehicleHistoryDatabase.TABLE_FUEL_ANALYTICS_CACHE,
            VehicleHistoryDatabase.TABLE_FUEL_MONTHLY_SUMMARY,
        ).forEach { table ->
            if (VehicleHistoryDatabase.tableExistsPublic(db, table)) {
                db.delete(table, null, null)
            }
        }
    }

    private fun loadRefuelBadges(context: Context): Map<Long, FuelTankAnalytics.FuelRecordBadge> {
        val db = VehicleHistoryDatabase.readableDb(context)
        if (!VehicleHistoryDatabase.tableExistsPublic(db, VehicleHistoryDatabase.TABLE_FUEL_REFUEL_META)) {
            return emptyMap()
        }
        val out = LinkedHashMap<Long, FuelTankAnalytics.FuelRecordBadge>()
        db.query(
            VehicleHistoryDatabase.TABLE_FUEL_REFUEL_META,
            null,
            "${VehicleHistoryDatabase.COL_IS_REFUEL} = 1",
            null,
            null,
            null,
            "${VehicleHistoryDatabase.COL_RECORD_ID} ASC",
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_RECORD_ID))
                val priceIdx = c.getColumnIndex(VehicleHistoryDatabase.COL_PRICE95)
                val price = if (priceIdx >= 0 && !c.isNull(priceIdx)) c.getDouble(priceIdx) else null
                val ref = c.getInt(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_IS_REF_PRICE)) == 1
                val addedIdx = c.getColumnIndex(VehicleHistoryDatabase.COL_ADDED_LITERS)
                val added = if (addedIdx >= 0 && !c.isNull(addedIdx)) c.getDouble(addedIdx) else null
                val l100Idx = c.getColumnIndex(VehicleHistoryDatabase.COL_TANK_L100)
                val l100 = if (l100Idx >= 0 && !c.isNull(l100Idx)) c.getDouble(l100Idx) else null
                val costIdx = c.getColumnIndex(VehicleHistoryDatabase.COL_TRIP_COST)
                val cost = if (costIdx >= 0 && !c.isNull(costIdx)) c.getDouble(costIdx) else null
                val refuelCostIdx = c.getColumnIndex(VehicleHistoryDatabase.COL_REFUEL_COST)
                val refuelCost = if (refuelCostIdx >= 0 && !c.isNull(refuelCostIdx)) {
                    c.getDouble(refuelCostIdx)
                } else {
                    null
                }
                out[id] = FuelTankAnalytics.FuelRecordBadge(
                    isRefuel = true,
                    price95Text = price?.let { "95# ¥${FuelPriceService.formatPriceYuan(it)}${if (ref) "参考" else ""}" },
                    tankAvgText = l100?.let { String.format(java.util.Locale.getDefault(), "上箱 %.1f", it) },
                    addedFuelText = added?.let { String.format(java.util.Locale.getDefault(), "+%.0fL", it) },
                    refuelCostText = refuelCost?.let { String.format(java.util.Locale.getDefault(), "≈¥%.0f", it) },
                    tripCostText = cost?.let { String.format(java.util.Locale.getDefault(), "≈¥%.0f", it) },
                    isReferencePrice = ref,
                )
            }
        }
        return out
    }

    fun loadMonthlySummaries(context: Context): List<MonthlyFuelSummary> {
        val db = VehicleHistoryDatabase.readableDb(context.applicationContext)
        if (!VehicleHistoryDatabase.tableExistsPublic(db, VehicleHistoryDatabase.TABLE_FUEL_MONTHLY_SUMMARY)) {
            return emptyList()
        }
        val out = ArrayList<MonthlyFuelSummary>()
        db.query(
            VehicleHistoryDatabase.TABLE_FUEL_MONTHLY_SUMMARY,
            null,
            null,
            null,
            null,
            null,
            "${VehicleHistoryDatabase.COL_MONTH_KEY} DESC",
        ).use { c ->
            while (c.moveToNext()) {
                val avgIdx = c.getColumnIndex(VehicleHistoryDatabase.COL_AVG_L100)
                val avg = if (avgIdx >= 0 && !c.isNull(avgIdx)) c.getDouble(avgIdx) else null
                out.add(
                    MonthlyFuelSummary(
                        monthKey = c.getString(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_MONTH_KEY)),
                        refuelCount = c.getInt(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_REFUEL_COUNT)),
                        totalCostYuan = c.getDouble(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_TOTAL_COST)),
                        totalLiters = c.getDouble(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_TOTAL_LITERS)),
                        avgLitersPer100Km = avg,
                    ),
                )
            }
        }
        return out
    }

    private fun cursorToCacheRow(c: Cursor): JSONObject {
        fun d(col: String): Double? {
            val idx = c.getColumnIndex(col)
            if (idx < 0 || c.isNull(idx)) return null
            val v = c.getDouble(idx)
            return if (v.isNaN()) null else v
        }
        return JSONObject().apply {
            put("fingerprint", c.getString(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_FINGERPRINT)))
            d(VehicleHistoryDatabase.COL_LAST_TANK_L100)?.let { put("lastTankL100", it) }
            d(VehicleHistoryDatabase.COL_LAST_TANK_KM)?.let { put("lastTankKm", it) }
            d(VehicleHistoryDatabase.COL_LAST_TANK_FUEL)?.let { put("lastTankFuel", it) }
            d(VehicleHistoryDatabase.COL_LAST_TANK_COST)?.let { put("lastTankCost", it) }
            put("refuelCount", c.getInt(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_REFUEL_COUNT)))
            val ecuIdx = c.getColumnIndex(VehicleHistoryDatabase.COL_ECU_AVG_FUEL)
            if (ecuIdx >= 0 && !c.isNull(ecuIdx)) put("ecuAvgFuel", c.getString(ecuIdx))
            put("computedAt", c.getLong(c.getColumnIndexOrThrow(VehicleHistoryDatabase.COL_COMPUTED_AT)))
        }
    }

    private fun dayKeyForRecord(context: Context, recordId: Long): String {
        val record = VehicleHistoryDatabase.loadVehicleDataRecordById(context, recordId) ?: return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(record.insertDate))
    }

    private fun parseAddedLiters(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return Regex("""\+?(\d+(?:\.\d+)?)L""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun parsePrice(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return Regex("""¥(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun parseTankL100(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return Regex("""上箱\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun parseTripCost(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return Regex("""¥(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
