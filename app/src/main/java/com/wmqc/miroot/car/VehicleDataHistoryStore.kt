package com.wmqc.miroot.car

import android.content.Context
import android.net.Uri
import java.io.File

/** 车辆历史门面：SQLite 存储，表结构与星瑞车控导出库兼容。 */
object VehicleDataHistoryStore {

    data class HistoryFastLoad(
        val page: List<VehicleHistoryDatabase.VehicleDataRecord>,
        val totalCount: Int,
        val ecuAvgFuel: String,
        val fuelInsights: FuelTankAnalytics.FuelHistoryInsights,
        val monthlySummaries: List<VehicleHistoryFuelCache.MonthlyFuelSummary>,
        val cacheValid: Boolean,
    )

    fun record(context: Context, status: VehicleStatusService.VehicleStatusInfo): Boolean =
        VehicleHistoryDatabase.record(context, status)

    /** 进入历史页或补写时使用：优先缓存，若缺少车辆更新时间则尝试拉一次最新车况再写入。 */
    fun tryRecordCached(context: Context): Boolean {
        val appCtx = context.applicationContext
        val cached = CarVehicleDisplayCache.loadStatus(appCtx)
        if (cached != null && VehicleHistoryDatabase.hasVehicleUpdateTime(cached)) {
            return VehicleHistoryDatabase.record(appCtx, cached)
        }
        VehicleHistoryDatabase.ensureVehicleProfileOnce(appCtx)
        return try {
            val fresh = VehicleStatusService.getVehicleStatus(appCtx)
            CarVehicleDisplayCache.save(appCtx, fresh)
            true
        } catch (_: Exception) {
            cached?.let { VehicleHistoryDatabase.record(appCtx, it) } ?: false
        }
    }

    fun loadHistoryCounts(context: Context): Triple<Int, Int, Int> =
        VehicleHistoryDatabase.loadHistoryCounts(context)

    /** 先读 SQLite 缓存；无缓存或指纹失效时返回空 insights。 */
    fun loadCachedFuelHistoryInsights(context: Context): FuelTankAnalytics.FuelHistoryInsights? =
        VehicleHistoryFuelCache.loadIfValid(context)

    /** 全量分析并写入 SQLite 缓存（后台线程调用）。 */
    fun refreshFuelHistoryInsights(context: Context): FuelTankAnalytics.FuelHistoryInsights =
        FuelTankAnalytics.analyzeAndPersist(context)

    fun needsFuelAnalyticsRefresh(context: Context): Boolean =
        VehicleHistoryFuelCache.needsRefresh(context)

    /** 进入历史页首屏：只读列表 + 缓存统计，不做全量重算。 */
    fun loadHistoryFast(context: Context, pageSize: Int = VehicleHistoryDatabase.PAGE_SIZE): HistoryFastLoad {
        val appCtx = context.applicationContext
        val page = loadVehicleDataRecordsPage(appCtx, 0, pageSize)
        val totalCount = loadHistoryCounts(appCtx).first
        val cached = VehicleHistoryFuelCache.loadCachedAnalytics(appCtx)
        val cacheValid = cached != null && cached.fingerprint == VehicleHistoryFuelCache.computeFingerprint(appCtx)
        val insights = if (cacheValid) {
            cached!!.insights
        } else {
            FuelTankAnalytics.FuelHistoryInsights(
                lastTankLitersPer100Km = null,
                lastTankDistanceKm = null,
                lastTankFuelLiters = null,
                lastTankCostYuan = null,
                refuelRecordIds = emptySet(),
                recordBadges = emptyMap(),
                refuelCount = 0,
                monthlySummaries = cached?.monthlySummaries ?: VehicleHistoryFuelCache.loadMonthlySummaries(appCtx),
            )
        }
        val ecu = cached?.ecuAvgFuel?.takeIf { cacheValid && !it.isNullOrBlank() }
            ?: resolveEcuAvgFuelDisplay(appCtx)
        val monthly = if (cacheValid) cached!!.monthlySummaries else insights.monthlySummaries
        return HistoryFastLoad(
            page = page,
            totalCount = totalCount,
            ecuAvgFuel = ecu,
            fuelInsights = insights,
            monthlySummaries = monthly,
            cacheValid = cacheValid,
        )
    }

    /** @deprecated 首屏请用 [loadHistoryFast] + [refreshFuelHistoryInsights] */
    fun loadFuelHistoryInsights(context: Context): FuelTankAnalytics.FuelHistoryInsights {
        loadCachedFuelHistoryInsights(context)?.let { return it }
        return refreshFuelHistoryInsights(context)
    }

    fun resolveEcuAvgFuelDisplay(context: Context): String {
        val appCtx = context.applicationContext
        VehicleHistoryFuelCache.loadCachedAnalytics(appCtx)
            ?.ecuAvgFuel
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        VehicleHistoryDatabase.loadLatestVehicleDataSnapshot(appCtx)
            ?.let { VehicleHistoryDisplayLabels.ecuAvgFuelFromSnapshot(it) }
            ?.let { return it }
        val cached = CarVehicleDisplayCache.loadStatus(appCtx)?.aveFuelConsumption
        return VehicleHistoryDisplayLabels.formatEcuAvgFuelConsumption(cached) ?: "—"
    }

    fun loadVehicleDataRecordsPage(
        context: Context,
        offset: Int,
        limit: Int = VehicleHistoryDatabase.PAGE_SIZE,
    ): List<VehicleHistoryDatabase.VehicleDataRecord> =
        VehicleHistoryDatabase.loadVehicleDataRecordsPage(context, offset, limit, includeSnapshot = true)

    fun loadOperateRecordsPage(
        context: Context,
        offset: Int,
        limit: Int = VehicleHistoryDatabase.PAGE_SIZE,
    ): List<VehicleHistoryDatabase.OperateRecord> =
        VehicleHistoryDatabase.loadOperateRecordsPage(context, offset, limit)

    fun loadWarningRecordsPage(
        context: Context,
        offset: Int,
        limit: Int = VehicleHistoryDatabase.PAGE_SIZE,
    ): List<VehicleHistoryDatabase.WarningRecord> =
        VehicleHistoryDatabase.loadWarningRecordsPage(context, offset, limit)

    fun loadVehicleDataRecordById(context: Context, id: Long): VehicleHistoryDatabase.VehicleDataRecord? =
        VehicleHistoryDatabase.loadVehicleDataRecordById(context, id)

    fun getVehicleVin(context: Context): String? =
        VehicleHistoryDatabase.getVehicleVin(context)

    fun loadVehicleInfo(context: Context, vehicleId: Long): VehicleHistoryDatabase.VehicleInfoRecord? =
        VehicleHistoryDatabase.loadVehicleInfo(context, vehicleId)

    fun loadPrimaryVehicleInfo(context: Context): VehicleHistoryDatabase.VehicleInfoRecord? =
        VehicleHistoryDatabase.loadPrimaryVehicleInfo(context)

    fun ensureVehicleProfileOnce(context: Context) {
        VehicleHistoryDatabase.ensureVehicleProfileOnce(context)
    }

    fun clearAll(context: Context) {
        VehicleHistoryDatabase.clearAll(context)
    }

    fun importFromUri(context: Context, uri: Uri): VehicleHistoryDatabase.ImportResult {
        val result = VehicleHistoryDatabase.importFromUri(context, uri)
        if (result.total > 0 || result.vehicleDataImported == 0) {
            // 导入后指纹变化，下次进入会后台重算
        }
        return result
    }

    fun exportToUri(context: Context, uri: Uri): Boolean =
        VehicleHistoryDatabase.exportToUri(context, uri)

    fun databaseFile(context: Context): File =
        VehicleHistoryDatabase.databaseFile(context)
}
