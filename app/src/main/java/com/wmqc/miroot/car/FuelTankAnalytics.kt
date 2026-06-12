package com.wmqc.miroot.car

import android.content.Context
import com.wmqc.miroot.lyrics.LogHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 基于历史列表中的里程、油量（升/百分比）识别加油并计算「上一箱油」油耗。
 * 加油节点：相较上一条记录，油量明显上升（如今日油量高于昨日末条）。
 * 每个加油节点写入当日 95# 价；相邻两个节点之间计算平均油耗。
 */
object FuelTankAnalytics {

    /** 相较上一条，油量至少上升这么多才视为加油（过滤传感器抖动）。 */
    private const val MIN_REFUEL_LITERS = 2.5
    private const val MAX_REFUEL_ODO_DELTA_KM = 120.0
    private const val MAX_REFUEL_GAP_HOURS = 96.0

    data class RefuelNode(
        val dayKey: String,
        val monthKey: String,
        val addedLiters: Double?,
        val addedPercent: Int?,
    )

    data class FuelRecordBadge(
        val isRefuel: Boolean,
        val price95Text: String?,
        val tankAvgText: String?,
        val addedFuelText: String?,
        /** 本次加油金额 ≈ 加油升数 × 当日坐标省份 95# 价 */
        val refuelCostText: String?,
        /** 上一箱行驶油费（两节点间消耗 × 油价） */
        val tripCostText: String?,
        val isReferencePrice: Boolean,
    )

    data class FuelHistoryInsights(
        val lastTankLitersPer100Km: Double?,
        val lastTankDistanceKm: Double?,
        val lastTankFuelLiters: Double?,
        val lastTankCostYuan: Double?,
        val refuelRecordIds: Set<Long>,
        val recordBadges: Map<Long, FuelRecordBadge>,
        val refuelCount: Int,
        val monthlySummaries: List<VehicleHistoryFuelCache.MonthlyFuelSummary> = emptyList(),
    )

    private data class FuelReading(
        val liters: Double?,
    )

    private data class RefuelPoint(
        val index: Int,
        val recordId: Long,
        val dayKey: String,
        val monthKey: String,
        val odometer: Double,
        val reading: FuelReading,
        val addedLiters: Double?,
        val addedPercent: Int?,
    )

    private data class TankCycle(
        val startIndex: Int,
        val endIndex: Int,
        val distanceKm: Double,
        val fuelUsedLiters: Double,
        val litersPer100Km: Double,
        val costYuan: Double?,
    )

    /** 新车况写入后：若较上一条为加油节点，立即把当日油价写入数据库。 */
    fun onRecordInserted(
        context: Context,
        previous: VehicleHistoryDatabase.VehicleDataRecord?,
        current: VehicleHistoryDatabase.VehicleDataRecord,
    ) {
        if (previous == null) return
        val tankCap = FuelPriceRegionPrefs.tankCapacityLiters(context)
        val node = detectRefuelBetween(previous, current, tankCap) ?: return
        val prov = FuelPriceRegionPrefs.provinceForRecord(context, current)
        FuelPriceService.writeRefuelNodePrice(context, node.dayKey, prov)
        LogHelper.d(
            "FuelTankAnalytics",
            "refuel node ${node.dayKey} prov=$prov added=${node.addedLiters}L price saved recordId=${current.id}",
        )
    }

    /**
     * 本次加油升数 = 加油后油量 − 加油前剩余（例：50L − 5L = 45L）。
     */
    fun refuelAddedLiters(
        previous: VehicleHistoryDatabase.VehicleDataRecord,
        current: VehicleHistoryDatabase.VehicleDataRecord,
    ): Double? {
        val before = fuelLevelLiters(previous) ?: return null
        val after = fuelLevelLiters(current) ?: return null
        return (after - before).takeIf { it > 0.5 }
    }

    private fun fuelLevelLiters(record: VehicleHistoryDatabase.VehicleDataRecord): Double? {
        if (record.fuelLiters >= 0) return record.fuelLiters
        return VehicleHistoryFuelDisplay.fuelReading(record).liters
    }

    fun detectRefuelBetween(
        previous: VehicleHistoryDatabase.VehicleDataRecord,
        current: VehicleHistoryDatabase.VehicleDataRecord,
        tankCap: Float,
    ): RefuelNode? {
        val r0 = fuelReading(previous)
        val r1 = fuelReading(current)
        val dOdo = current.odometer - previous.odometer
        if (dOdo > MAX_REFUEL_ODO_DELTA_KM) return null
        val hours = (current.insertDate - previous.insertDate) / 3_600_000.0
        if (hours > MAX_REFUEL_GAP_HOURS) return null
        val jumpL = refuelAddedLiters(previous, current)
            ?: (fuelLitersEstimate(r1) - fuelLitersEstimate(r0)).takeIf { it > 0.5 }
        if ((jumpL ?: 0.0) < MIN_REFUEL_LITERS) return null
        if ((jumpL ?: 0.0) <= 0.3) return null
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val date = Date(current.insertDate)
        return RefuelNode(
            dayKey = dayFormat.format(date),
            monthKey = monthFormat.format(date),
            addedLiters = jumpL,
            addedPercent = null,
        )
    }

    fun analyzeAndPersist(context: Context): FuelHistoryInsights {
        val appCtx = context.applicationContext
        FuelPriceRegionPrefs.refreshAutoProvinceFromVehicle(appCtx)
        FuelPriceService.fetchAllProvinces(appCtx)
        val rows = VehicleHistoryDatabase.loadVehicleDataRecordsChronological(appCtx)
        val insights = analyze(appCtx, rows)
        val ecu = VehicleDataHistoryStore.resolveEcuAvgFuelDisplay(appCtx)
        VehicleHistoryFuelCache.persistAnalytics(
            appCtx,
            VehicleHistoryFuelCache.computeFingerprint(appCtx),
            insights,
            ecu,
            insights.monthlySummaries,
        )
        return insights
    }

    fun analyze(
        context: Context,
        recordsAsc: List<VehicleHistoryDatabase.VehicleDataRecord>,
    ): FuelHistoryInsights {
        if (recordsAsc.size < 2) return emptyInsights()
        val tankCap = FuelPriceRegionPrefs.tankCapacityLiters(context)
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val readings = recordsAsc.map { fuelReading(it) }
        val refuels = detectRefuels(recordsAsc, readings, tankCap)
        if (refuels.isEmpty()) return emptyInsights()

        refuels.forEach { refuel ->
            val record = recordsAsc[refuel.index]
            val prov = FuelPriceRegionPrefs.provinceForRecord(context, record)
            FuelPriceService.writeRefuelNodePrice(context, refuel.dayKey, prov)
        }

        val cycles = buildCycles(recordsAsc, readings, refuels, tankCap, context, dayFormat)
        val badges = LinkedHashMap<Long, FuelRecordBadge>()
        val refuelIds = refuels.map { it.recordId }.toSet()

        refuels.forEachIndexed { ri, refuel ->
            val record = recordsAsc[refuel.index]
            val prev = recordsAsc[refuel.index - 1]
            val prov = FuelPriceRegionPrefs.provinceForRecord(context, record)
            val quote = FuelPriceService.price95ForDay(context, prov, refuel.dayKey)
            val refSuffix = if (quote.isReferenceOnly) "参考" else ""
            val priceText = "95# ¥${FuelPriceService.formatPriceYuan(quote.yuanPerLiter)}$refSuffix"
            val addedLiters = refuelAddedLiters(prev, record) ?: refuel.addedLiters
            val added = addedLiters?.let { formatLitersDelta(it) }
            val refuelCost = addedLiters?.let { it * quote.yuanPerLiter }
            val cycle = cycles.getOrNull(ri - 1)
            val tankAvg = cycle?.litersPer100Km?.let {
                String.format(Locale.getDefault(), "上箱 %.1f", it)
            }
            val tripCost = cycle?.costYuan?.let {
                String.format(Locale.getDefault(), "≈¥%.0f", it)
            }
            badges[refuel.recordId] = FuelRecordBadge(
                isRefuel = true,
                price95Text = priceText,
                tankAvgText = tankAvg,
                addedFuelText = added,
                refuelCostText = refuelCost?.let { formatYuan(it) },
                tripCostText = tripCost,
                isReferencePrice = quote.isReferenceOnly,
            )
        }

        val monthlySummaries = buildMonthlySummaries(refuels, recordsAsc, cycles, context)
        val lastCycle = cycles.lastOrNull()
        return FuelHistoryInsights(
            lastTankLitersPer100Km = lastCycle?.litersPer100Km,
            lastTankDistanceKm = lastCycle?.distanceKm,
            lastTankFuelLiters = lastCycle?.fuelUsedLiters,
            lastTankCostYuan = lastCycle?.costYuan,
            refuelRecordIds = refuelIds,
            recordBadges = badges,
            refuelCount = refuels.size,
            monthlySummaries = monthlySummaries,
        )
    }

    private fun emptyInsights() = FuelHistoryInsights(
        lastTankLitersPer100Km = null,
        lastTankDistanceKm = null,
        lastTankFuelLiters = null,
        lastTankCostYuan = null,
        refuelRecordIds = emptySet(),
        recordBadges = emptyMap(),
        refuelCount = 0,
        monthlySummaries = emptyList(),
    )

    private fun buildMonthlySummaries(
        refuels: List<RefuelPoint>,
        recordsAsc: List<VehicleHistoryDatabase.VehicleDataRecord>,
        cycles: List<TankCycle>,
        context: Context,
    ): List<VehicleHistoryFuelCache.MonthlyFuelSummary> {
        val monthMap = LinkedHashMap<String, MonthAccumulator>()
        val tankCap = FuelPriceRegionPrefs.tankCapacityLiters(context)
        refuels.forEach { refuel ->
            val record = recordsAsc[refuel.index]
            val prev = recordsAsc[refuel.index - 1]
            val added = refuelAddedLiters(prev, record)
                ?: refuel.addedLiters?.takeIf { it > 0.5 }
                ?: return@forEach
            val prov = FuelPriceRegionPrefs.provinceForRecord(context, record)
            val quote = FuelPriceService.price95ForDay(context, prov, refuel.dayKey)
            val cost = added * quote.yuanPerLiter
            monthMap.getOrPut(refuel.monthKey) { MonthAccumulator() }.apply {
                refuelCount++
                totalLiters += added
                totalCost += cost
            }
        }
        cycles.forEach { cycle ->
            val endRefuel = refuels.find { it.index == cycle.endIndex } ?: return@forEach
            monthMap[endRefuel.monthKey]?.l100Samples?.add(cycle.litersPer100Km)
        }
        return monthMap.entries
            .sortedByDescending { it.key }
            .map { (monthKey, acc) ->
                VehicleHistoryFuelCache.MonthlyFuelSummary(
                    monthKey = monthKey,
                    refuelCount = acc.refuelCount,
                    totalCostYuan = acc.totalCost,
                    totalLiters = acc.totalLiters,
                    avgLitersPer100Km = acc.l100Samples.takeIf { it.isNotEmpty() }?.average(),
                )
            }
    }

    private class MonthAccumulator {
        var refuelCount = 0
        var totalLiters = 0.0
        var totalCost = 0.0
        val l100Samples = ArrayList<Double>()
    }

    private fun fuelReading(record: VehicleHistoryDatabase.VehicleDataRecord): FuelReading {
        val r = VehicleHistoryFuelDisplay.fuelReading(record)
        return FuelReading(r.liters)
    }

    private fun detectRefuels(
        records: List<VehicleHistoryDatabase.VehicleDataRecord>,
        readings: List<FuelReading>,
        tankCap: Float,
    ): List<RefuelPoint> {
        val out = ArrayList<RefuelPoint>()
        for (i in 1 until records.size) {
            val prev = records[i - 1]
            val curr = records[i]
            val node = detectRefuelBetween(prev, curr, tankCap) ?: continue
            out.add(
                RefuelPoint(
                    index = i,
                    recordId = curr.id,
                    dayKey = node.dayKey,
                    monthKey = node.monthKey,
                    odometer = curr.odometer,
                    reading = readings[i],
                    addedLiters = node.addedLiters,
                    addedPercent = node.addedPercent,
                ),
            )
        }
        return out
    }

    private fun buildCycles(
        records: List<VehicleHistoryDatabase.VehicleDataRecord>,
        readings: List<FuelReading>,
        refuels: List<RefuelPoint>,
        tankCap: Float,
        context: Context,
        dayFormat: SimpleDateFormat,
    ): List<TankCycle> {
        if (refuels.size < 2) return emptyList()
        val cycles = ArrayList<TankCycle>()
        for (k in 1 until refuels.size) {
            val start = refuels[k - 1]
            val end = refuels[k]
            if (end.index <= start.index) continue
            val distance = end.odometer - start.odometer
            if (distance < 30.0) continue
            val startFuel = fuelLevelLiters(records[start.index])
                ?: fuelLitersEstimate(readings[start.index])
            val endBefore = fuelLevelLiters(records[end.index - 1])
                ?: fuelLitersEstimate(readings[end.index - 1])
            var fuelUsed = startFuel - endBefore
            if (fuelUsed <= 1.0) {
                fuelUsed = minFuelDrop(readings, start.index, end.index)
            }
            if (fuelUsed < 3.0) continue
            val l100 = fuelUsed / distance * 100.0
            if (l100 < 3.0 || l100 > 30.0) continue
            val dayKey = dayFormat.format(Date(records[end.index].insertDate))
            val endRecord = records[end.index]
            val prov = FuelPriceRegionPrefs.provinceForRecord(context, endRecord)
            val quote = FuelPriceService.price95ForDay(context, prov, dayKey)
            val cost = fuelUsed * quote.yuanPerLiter
            cycles.add(
                TankCycle(
                    startIndex = start.index,
                    endIndex = end.index,
                    distanceKm = distance,
                    fuelUsedLiters = fuelUsed,
                    litersPer100Km = l100,
                    costYuan = cost,
                ),
            )
        }
        return cycles
    }

    private fun minFuelDrop(
        readings: List<FuelReading>,
        from: Int,
        to: Int,
    ): Double {
        if (to <= from) return 0.0
        val start = fuelLitersEstimate(readings[from])
        var min = start
        for (i in from..to) {
            val v = fuelLitersEstimate(readings[i])
            if (v < min) min = v
        }
        return (start - min).coerceAtLeast(0.0)
    }

    private fun fuelLitersEstimate(reading: FuelReading): Double = reading.liters ?: 0.0

    private fun formatLitersDelta(liters: Double): String =
        String.format(Locale.getDefault(), "+%.0fL", liters)

    private fun formatYuan(yuan: Double): String =
        String.format(Locale.getDefault(), "≈¥%.0f", yuan)
}
