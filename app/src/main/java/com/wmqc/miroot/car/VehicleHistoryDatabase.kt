package com.wmqc.miroot.car

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.wmqc.miroot.car.VehicleStatusService.VehicleStatusInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 与星瑞车控导出库兼容的 SQLite 结构（参考 {@code temp_export_database..db}）：
 * - [TABLE_VEHICLE_DATA_RECORD]、[TABLE_VEHICLE_INFO]、[TABLE_OPERATE_RECORD]、[TABLE_WARNING_RECORD]
 * - 扩展列 [COL_SNAPSHOT_JSON] 保存完整车辆快照，便于本应用详情展示。
 */
object VehicleHistoryDatabase {

    private const val TAG = "VehicleHistoryDb"

    const val DB_NAME = "car_vehicle_history.db"
    /** 历史列表每页条数（首屏与每次下拉加载）。 */
    const val PAGE_SIZE = 50
    private const val DB_VERSION = 4
    private const val PREFS_KEY_LEGACY_MIGRATED = "vehicle_history_legacy_migrated_v1"

    const val TABLE_VEHICLE_DATA_RECORD = "VEHICLE_DATA_RECORD"
    const val TABLE_VEHICLE_INFO = "VEHICLE_INFO"
    const val TABLE_OPERATE_RECORD = "OPERATE_RECORD"
    const val TABLE_WARNING_RECORD = "WARNING_RECORD"

    const val COL_ID = "_id"
    const val COL_INSERT_DATE = "INSERT_DATE"
    const val COL_BATTERY_VOLTAGE = "BATTERY_VOLTAGE"
    const val COL_ODOMETER = "ODOMETER"
    const val COL_FUEL_LEVEL = "FUEL_LEVEL"
    /** 剩余油量升数；-1 表示未记录。 */
    const val COL_FUEL_LITERS = "FUEL_LITERS"
    /** 历史不写入百分比，恒为 -1（保留列兼容旧库）。 */
    const val COL_FUEL_PERCENT = "FUEL_PERCENT"
    const val COL_DISTANCE_TO_EMPTY = "DISTANCE_TO_EMPTY"
    const val COL_ADD_OLI_FLAG = "ADD_OLI_FLAG"
    const val COL_ENGINE_START = "ENGINE_START"
    const val COL_VEHICLE_ID = "VEHICLE_ID"
    const val COL_SNAPSHOT_JSON = "SNAPSHOT_JSON"
    const val COL_LATITUDE = "LATITUDE"
    const val COL_LONGITUDE = "LONGITUDE"
    const val COL_ADDRESS = "ADDRESS"

    const val TABLE_FUEL_PRICE_DAY = "FUEL_PRICE_DAY"
    const val TABLE_FUEL_REFUEL_META = "FUEL_REFUEL_META"
    const val TABLE_FUEL_ANALYTICS_CACHE = "FUEL_ANALYTICS_CACHE"
    const val TABLE_FUEL_MONTHLY_SUMMARY = "FUEL_MONTHLY_SUMMARY"
    const val COL_DAY_KEY = "DAY_KEY"
    const val COL_PROVINCE = "PROVINCE"
    const val COL_PRICE95 = "PRICE95"
    const val COL_IS_REF_PRICE = "IS_REF_PRICE"
    const val COL_UPDATED_AT = "UPDATED_AT"
    const val COL_RECORD_ID = "RECORD_ID"
    const val COL_IS_REFUEL = "IS_REFUEL"
    const val COL_ADDED_LITERS = "ADDED_LITERS"
    const val COL_TANK_L100 = "TANK_L100"
    const val COL_TRIP_COST = "TRIP_COST"
    const val COL_REFUEL_COST = "REFUEL_COST"
    const val COL_FUEL_USED = "FUEL_USED"
    const val COL_CACHE_KEY = "CACHE_KEY"
    const val COL_FINGERPRINT = "FINGERPRINT"
    const val COL_LAST_TANK_L100 = "LAST_TANK_L100"
    const val COL_LAST_TANK_KM = "LAST_TANK_KM"
    const val COL_LAST_TANK_FUEL = "LAST_TANK_FUEL"
    const val COL_LAST_TANK_COST = "LAST_TANK_COST"
    const val COL_REFUEL_COUNT = "REFUEL_COUNT"
    const val COL_ECU_AVG_FUEL = "ECU_AVG_FUEL"
    const val COL_COMPUTED_AT = "COMPUTED_AT"
    const val COL_MONTH_KEY = "MONTH_KEY"
    const val COL_TOTAL_COST = "TOTAL_COST"
    const val COL_TOTAL_LITERS = "TOTAL_LITERS"
    const val COL_AVG_L100 = "AVG_L100"
    const val CACHE_KEY_MAIN = "main"

    const val COL_VIN = "VIN"
    const val COL_PLATE_NO = "PLATE_NO"
    const val COL_SERIES_NAME = "SERIES_NAME"
    const val COL_MODEL_NAME = "MODEL_NAME"
    const val COL_COLOR_NAME = "COLOR_NAME"
    const val COL_OPERATE = "OPERATE"
    const val COL_ARGS = "ARGS"
    const val COL_WARNING = "WARNING"

    data class VehicleDataRecord(
        val id: Long,
        val insertDate: Long,
        val batteryVoltage: Double,
        val odometer: Double,
        val fuelLevel: Double,
        val fuelLiters: Double = -1.0,
        val fuelPercent: Int = -1,
        val distanceToEmpty: Double,
        val addOliFlag: Int,
        val engineStart: Int,
        val vehicleId: Long,
        val snapshotJson: String?,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val address: String? = null,
    ) {
        val displayTime: String
            get() = formatInsertDate(insertDate)

        val summaryLine: String
            get() = buildString {
                append(String.format(Locale.getDefault(), "%.1fkm", odometer))
                append(" · ")
                append(String.format(Locale.getDefault(), "%.1fV", batteryVoltage))
                append(" · ")
                append(VehicleHistoryFuelDisplay.formatFuelLevelCell(this@VehicleDataRecord))
                append(" · 续航")
                append(String.format(Locale.getDefault(), "%.0fkm", distanceToEmpty))
                if (engineStart == 1) append(" · 发动机运行")
            }
    }

    data class OperateRecord(
        val id: Long,
        val insertDate: Long,
        val operate: String,
        val args: String,
        val vehicleId: Long,
    ) {
        val displayTime: String get() = formatInsertDate(insertDate)
    }

    data class WarningRecord(
        val id: Long,
        val insertDate: Long,
        val warning: String,
    ) {
        val displayTime: String get() = formatInsertDate(insertDate)
    }

    data class VehicleInfoRecord(
        val id: Long,
        val vin: String?,
        val plateNo: String?,
        val seriesName: String?,
        val modelName: String?,
        val colorName: String?,
        val insertDate: Long,
    )

    data class ImportResult(
        val vehicleDataImported: Int,
        val operateImported: Int,
        val warningImported: Int,
        val vehicleInfoImported: Int,
        val sourceVin: String? = null,
        val localVin: String? = null,
        val vinMatched: Boolean = true,
    ) {
        val total: Int
            get() = vehicleDataImported + operateImported + warningImported + vehicleInfoImported
    }

    private var helper: OpenHelper? = null

    fun record(context: Context, status: VehicleStatusInfo): Boolean {
        val appCtx = context.applicationContext
        return try {
            migrateLegacyPrefsIfNeeded(appCtx)
            val vehicleUpdateMillis = VehicleStatusService.resolveVehicleUpdateMillis(status)
            if (vehicleUpdateMillis <= 0L) {
                Log.w(
                    TAG,
                    "record skipped: invalid vehicle update time, updateTime=${status.updateTime}, updateDateTime=${status.updateDateTime}",
                )
                return false
            }
            if (recordExistsForVehicleUpdate(appCtx, vehicleUpdateMillis)) {
                return false
            }
            ensureVehicleProfileOnce(appCtx)
            val snapshot = buildSnapshotJson(appCtx, status, vehicleUpdateMillis)
            val fuelLiters = VehicleFuelParser.parseLiters(status)
            val location = resolveRecordLocation(appCtx, status)
            val cv = ContentValues().apply {
                put(COL_INSERT_DATE, vehicleUpdateMillis)
                put(COL_BATTERY_VOLTAGE, parseVoltage(status.voltage))
                put(COL_ODOMETER, parseOdometer(status.odometer))
                put(COL_FUEL_LITERS, fuelLiters ?: -1.0)
                put(COL_FUEL_PERCENT, -1)
                put(COL_FUEL_LEVEL, fuelLiters ?: 0.0)
                put(COL_DISTANCE_TO_EMPTY, parseDistanceKm(status.distanceToEmpty).toDouble())
                put(COL_ADD_OLI_FLAG, parseAddOilFlag(status.serviceWarningStatus))
                put(COL_ENGINE_START, if (isEngineRunning(status)) 1 else 0)
                put(COL_VEHICLE_ID, currentVehicleId(appCtx))
                put(COL_SNAPSHOT_JSON, snapshot.toString())
                location.latitude?.let { put(COL_LATITUDE, it) }
                location.longitude?.let { put(COL_LONGITUDE, it) }
                location.address?.let { put(COL_ADDRESS, it) }
            }
            val rowId = writable(appCtx).insert(TABLE_VEHICLE_DATA_RECORD, null, cv)
            if (rowId < 0L) {
                Log.e(TAG, "record insert failed rowId=$rowId vehicleUpdateMillis=$vehicleUpdateMillis")
                false
            } else {
                val previous = loadPreviousVehicleDataRecord(appCtx, vehicleUpdateMillis)
                loadVehicleDataRecordById(appCtx, rowId)?.let { current ->
                    FuelTankAnalytics.onRecordInserted(appCtx, previous, current)
                }
                Log.d(TAG, "record ok id=$rowId vehicleUpdateMillis=$vehicleUpdateMillis")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "record failed", e)
            false
        }
    }

    fun loadHistoryCounts(context: Context): Triple<Int, Int, Int> {
        migrateLegacyPrefsIfNeeded(context.applicationContext)
        val db = readable(context.applicationContext)
        return Triple(
            tableCount(db, TABLE_VEHICLE_DATA_RECORD),
            tableCount(db, TABLE_OPERATE_RECORD),
            tableCount(db, TABLE_WARNING_RECORD),
        )
    }

    fun loadVehicleDataRecordsPage(
        context: Context,
        offset: Int,
        limit: Int = PAGE_SIZE,
        includeSnapshot: Boolean = false,
    ): List<VehicleDataRecord> = loadVehicleDataRecordsPaged(context, offset, limit, includeSnapshot)

    private fun loadVehicleDataRecordsPaged(
        context: Context,
        offset: Int,
        limit: Int,
        includeSnapshot: Boolean,
    ): List<VehicleDataRecord> {
        migrateLegacyPrefsIfNeeded(context.applicationContext)
        return try {
            val db = readable(context.applicationContext)
            val columns = if (includeSnapshot) {
                null
            } else {
                arrayOf(
                    COL_ID, COL_INSERT_DATE, COL_BATTERY_VOLTAGE, COL_ODOMETER, COL_FUEL_LEVEL,
                    COL_FUEL_LITERS, COL_FUEL_PERCENT,
                    COL_DISTANCE_TO_EMPTY, COL_ADD_OLI_FLAG, COL_ENGINE_START, COL_VEHICLE_ID,
                )
            }
            val list = ArrayList<VehicleDataRecord>(limit.coerceAtMost(64))
            val limitClause = "$offset,$limit"
            db.query(
                TABLE_VEHICLE_DATA_RECORD,
                columns,
                null,
                null,
                null,
                null,
                "$COL_INSERT_DATE DESC",
                limitClause,
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(cursorToVehicleData(c, includeSnapshot))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "loadVehicleDataRecordsPaged failed offset=$offset", e)
            emptyList()
        }
    }

    fun loadVehicleDataRecordById(context: Context, id: Long): VehicleDataRecord? {
        val db = readable(context.applicationContext)
        db.query(
            TABLE_VEHICLE_DATA_RECORD,
            null,
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { c ->
            if (c.moveToFirst()) return cursorToVehicleData(c, includeSnapshot = true)
        }
        return null
    }

    /** 指定时间点之前最近一条记录（用于判断本次同步是否为加油节点）。 */
    fun loadPreviousVehicleDataRecord(context: Context, beforeInsertDate: Long): VehicleDataRecord? {
        val db = readable(context.applicationContext)
        db.query(
            TABLE_VEHICLE_DATA_RECORD,
            null,
            "$COL_INSERT_DATE < ?",
            arrayOf(beforeInsertDate.toString()),
            null,
            null,
            "$COL_INSERT_DATE DESC",
            "1",
        ).use { c ->
            if (c.moveToFirst()) return cursorToVehicleData(c, includeSnapshot = false)
        }
        return null
    }

    /** 最新一条带快照的车辆数据，用于列表头展示车机平均油耗。 */
    /** 按时间正序加载（油耗分析用），上限 [maxRows] 条。 */
    fun loadVehicleDataRecordsChronological(
        context: Context,
        maxRows: Int = 20_000,
    ): List<VehicleDataRecord> {
        migrateLegacyPrefsIfNeeded(context.applicationContext)
        return try {
            val db = readable(context.applicationContext)
            val list = ArrayList<VehicleDataRecord>()
            db.query(
                TABLE_VEHICLE_DATA_RECORD,
                null,
                null,
                null,
                null,
                null,
                "$COL_INSERT_DATE ASC",
                maxRows.toString(),
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(cursorToVehicleData(c, includeSnapshot = true))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "loadVehicleDataRecordsChronological failed", e)
            emptyList()
        }
    }

    fun loadLatestVehicleDataSnapshot(context: Context): String? {
        migrateLegacyPrefsIfNeeded(context.applicationContext)
        val db = readable(context.applicationContext)
        return try {
            db.query(
                TABLE_VEHICLE_DATA_RECORD,
                arrayOf(COL_SNAPSHOT_JSON),
                "$COL_SNAPSHOT_JSON IS NOT NULL AND LENGTH($COL_SNAPSHOT_JSON) > 2",
                null,
                null,
                null,
                "$COL_INSERT_DATE DESC",
                "1",
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadOperateRecordsPage(context: Context, offset: Int, limit: Int = PAGE_SIZE): List<OperateRecord> {
        migrateLegacyPrefsIfNeeded(context.applicationContext)
        val db = readable(context.applicationContext)
        if (!tableExists(db, TABLE_OPERATE_RECORD)) return emptyList()
        val list = ArrayList<OperateRecord>(limit.coerceAtMost(PAGE_SIZE))
        return try {
            db.query(
                TABLE_OPERATE_RECORD,
                null,
                null,
                null,
                null,
                null,
                "$COL_INSERT_DATE DESC",
                "$offset,$limit",
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        OperateRecord(
                            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                            insertDate = c.getLong(c.getColumnIndexOrThrow(COL_INSERT_DATE)),
                            operate = c.getString(c.getColumnIndexOrThrow(COL_OPERATE)) ?: "",
                            args = c.getString(c.getColumnIndexOrThrow(COL_ARGS)) ?: "",
                            vehicleId = c.getLong(c.getColumnIndexOrThrow(COL_VEHICLE_ID)),
                        ),
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadWarningRecordsPage(context: Context, offset: Int, limit: Int = PAGE_SIZE): List<WarningRecord> {
        migrateLegacyPrefsIfNeeded(context.applicationContext)
        val db = readable(context.applicationContext)
        if (!tableExists(db, TABLE_WARNING_RECORD)) return emptyList()
        val list = ArrayList<WarningRecord>(limit.coerceAtMost(PAGE_SIZE))
        return try {
            db.query(
                TABLE_WARNING_RECORD,
                null,
                null,
                null,
                null,
                null,
                "$COL_INSERT_DATE DESC",
                "$offset,$limit",
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        WarningRecord(
                            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                            insertDate = c.getLong(c.getColumnIndexOrThrow(COL_INSERT_DATE)),
                            warning = c.getString(c.getColumnIndexOrThrow(COL_WARNING)) ?: "",
                        ),
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getVehicleVin(context: Context): String? {
        val db = readable(context.applicationContext)
        db.query(
            TABLE_VEHICLE_INFO,
            arrayOf(COL_VIN),
            null,
            null,
            null,
            null,
            "$COL_ID ASC",
            "1",
        ).use { c ->
            if (c.moveToFirst()) {
                return c.getString(0)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun loadVehicleInfo(context: Context, vehicleId: Long): VehicleInfoRecord? {
        val db = readable(context.applicationContext)
        db.query(
            TABLE_VEHICLE_INFO,
            null,
            "$COL_ID = ?",
            arrayOf(vehicleId.toString()),
            null,
            null,
            null,
            "1",
        ).use { c ->
            if (c.moveToFirst()) return cursorToVehicleInfo(c)
        }
        return null
    }

    fun loadPrimaryVehicleInfo(context: Context): VehicleInfoRecord? {
        val db = readable(context.applicationContext)
        db.query(
            TABLE_VEHICLE_INFO,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID ASC",
            "1",
        ).use { c ->
            if (c.moveToFirst()) return cursorToVehicleInfo(c)
        }
        return null
    }

    fun recordOperate(context: Context, operate: String, args: String = "") {
        val appCtx = context.applicationContext
        val cv = ContentValues().apply {
            put(COL_INSERT_DATE, System.currentTimeMillis())
            put(COL_OPERATE, operate)
            put(COL_ARGS, args)
            put(COL_VEHICLE_ID, currentVehicleId(appCtx))
        }
        writable(appCtx).insert(TABLE_OPERATE_RECORD, null, cv)
    }

    fun clearAll(context: Context) {
        val db = writable(context.applicationContext)
        db.delete(TABLE_VEHICLE_DATA_RECORD, null, null)
        db.delete(TABLE_OPERATE_RECORD, null, null)
        db.delete(TABLE_WARNING_RECORD, null, null)
        db.delete(TABLE_VEHICLE_INFO, null, null)
        VehicleHistoryFuelCache.clearAll(context)
        invalidateHelper()
    }

    fun readableDb(context: Context): SQLiteDatabase = readable(context.applicationContext)

    fun writableDb(context: Context): SQLiteDatabase = writable(context.applicationContext)

    fun tableExistsPublic(db: SQLiteDatabase, table: String): Boolean = tableExists(db, table)

    fun maxVehicleDataRecordId(context: Context): Long {
        val db = readable(context.applicationContext)
        db.query(
            TABLE_VEHICLE_DATA_RECORD,
            arrayOf("MAX($COL_ID)"),
            null,
            null,
            null,
            null,
            null,
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return 0L
    }

    /** 车辆档案只写入 [TABLE_VEHICLE_INFO] 一次；后续同步与导入不再覆盖。 */
    fun ensureVehicleProfileOnce(context: Context) {
        val appCtx = context.applicationContext
        val db = writable(appCtx)
        if (tableCount(db, TABLE_VEHICLE_INFO) > 0) return
        val basic = runCatching { VehicleStatusService.getVehicleBasicInfo(appCtx) }
            .getOrDefault(VehicleStatusService.VehicleBasicInfo())
        val vin = basic.vin?.trim().orEmpty()
        if (vin.isEmpty() || vin == "未知") return
        val cv = ContentValues().apply {
            put(COL_VIN, vin)
            put(COL_PLATE_NO, basic.plateNo?.trim().orEmpty())
            put(COL_SERIES_NAME, basic.seriesName?.trim().orEmpty())
            put(COL_MODEL_NAME, basic.modelName?.trim().orEmpty())
            put(COL_COLOR_NAME, basic.colorName?.trim().orEmpty())
            put(COL_INSERT_DATE, System.currentTimeMillis())
        }
        db.insert(TABLE_VEHICLE_INFO, null, cv)
        Log.d(TAG, "vehicle profile written once vin=$vin")
    }

    fun readSourcePrimaryVin(sourceDb: File): String? {
        if (!isValidSqliteFile(sourceDb)) return null
        val db = SQLiteDatabase.openDatabase(
            sourceDb.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            if (!tableExists(db, TABLE_VEHICLE_INFO)) return null
            db.query(
                TABLE_VEHICLE_INFO,
                arrayOf(COL_VIN),
                null,
                null,
                null,
                null,
                "$COL_ID ASC",
                "1",
            ).use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                else null
            }
        } catch (_: Exception) {
            null
        } finally {
            db.close()
        }
    }

    private fun buildImportResult(
        context: Context,
        sourceDb: File,
        vehicle: Int,
        operate: Int,
        warning: Int,
        info: Int,
    ): ImportResult {
        val localVin = loadPrimaryVehicleInfo(context.applicationContext)?.vin?.trim()?.takeIf { it.isNotEmpty() }
        val sourceVin = readSourcePrimaryVin(sourceDb)?.trim()?.takeIf { it.isNotEmpty() }
        val matched = localVin.isNullOrEmpty() || sourceVin.isNullOrEmpty() ||
            localVin.equals(sourceVin, ignoreCase = true)
        return ImportResult(
            vehicleDataImported = vehicle,
            operateImported = operate,
            warningImported = warning,
            vehicleInfoImported = info,
            sourceVin = sourceVin,
            localVin = localVin,
            vinMatched = matched,
        )
    }

    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val appCtx = context.applicationContext
        val temp = File(appCtx.cacheDir, "vehicle_history_import_${System.currentTimeMillis()}.db")
        if (!copyUriToFile(appCtx, uri, temp) || !isValidSqliteFile(temp)) {
            temp.delete()
            Log.w(TAG, "importFromUri: copy failed or not a sqlite file, uri=$uri size=${temp.length()}")
            return ImportResult(0, 0, 0, 0)
        }
        return try {
            importFromFile(appCtx, temp)
        } finally {
            temp.delete()
        }
    }

    fun databaseFile(context: Context): File =
        context.applicationContext.getDatabasePath(DB_NAME)

    /** 备份前合并 WAL，供云备份/导出复制完整 SQLite 文件。 */
    @JvmStatic
    fun prepareDatabaseFileForCopy(dbFile: File) {
        prepareSourceDatabase(dbFile)
    }

    /**
     * 云备份/导出前：将 WAL 落盘并关闭本应用持有的数据库连接，避免复制到不完整库文件。
     */
    @JvmStatic
    fun flushConnectionsForBackup(context: Context) {
        val appCtx = context.applicationContext
        val dbFile = databaseFile(appCtx)
        try {
            if (dbFile.isFile) {
                prepareSourceDatabase(dbFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "flushConnectionsForBackup checkpoint failed", e)
        } finally {
            invalidateHelper()
        }
    }

    /** 用备份库整体替换本地车辆历史库（云恢复/本地导入 zip 内数据库）。 */
    @JvmStatic
    fun replaceFromBackupFile(context: Context, sourceDb: File): Boolean {
        if (!isValidSqliteFile(sourceDb)) return false
        val appCtx = context.applicationContext
        prepareSourceDatabase(sourceDb)
        return try {
            invalidateHelper()
            val dest = databaseFile(appCtx)
            dest.parentFile?.mkdirs()
            if (dest.exists() && !dest.delete()) {
                Log.e(TAG, "replaceFromBackupFile: cannot delete ${dest.absolutePath}")
                return false
            }
            sourceDb.copyTo(dest, overwrite = true)
            File(dest.absolutePath + "-wal").delete()
            File(dest.absolutePath + "-shm").delete()
            if (!isValidSqliteFile(dest)) {
                Log.e(TAG, "replaceFromBackupFile: copied file invalid")
                return false
            }
            patchImportedDatabaseFile(dest)
            invalidateHelper()
            val counts = loadHistoryCounts(appCtx)
            Log.i(
                TAG,
                "replaceFromBackupFile ok vehicle=${counts.first} operate=${counts.second} warning=${counts.third}",
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "replaceFromBackupFile failed", e)
            invalidateHelper()
            false
        }
    }

    fun exportToUri(context: Context, uri: Uri): Boolean {
        val src = databaseFile(context)
        if (!src.isFile) return false
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    fun importFromFile(context: Context, sourceDb: File): ImportResult {
        if (!isValidSqliteFile(sourceDb)) {
            Log.w(TAG, "importFromFile: invalid sqlite ${sourceDb.absolutePath} size=${sourceDb.length()}")
            return ImportResult(0, 0, 0, 0)
        }
        val appCtx = context.applicationContext
        migrateLegacyPrefsIfNeeded(appCtx)
        prepareSourceDatabase(sourceDb)
        val sourceCounts = readSourceCounts(sourceDb)
        Log.d(TAG, "importFromFile source=${sourceDb.name} vehicle=${sourceCounts.first} operate=${sourceCounts.second} warning=${sourceCounts.third}")

        val attachAttempt = runCatching { importFromFileAttach(appCtx, sourceDb) }
        val attachResult = attachAttempt.getOrNull()
        if (attachResult != null && attachResult.total > 0) {
            Log.i(TAG, "import attach ok total=${attachResult.total}")
            return attachResult
        }
        if (attachAttempt.isFailure) {
            Log.w(TAG, "import attach failed, trying cursor", attachAttempt.exceptionOrNull())
        } else {
            Log.w(TAG, "import attach returned 0, trying cursor")
        }

        val cursorResult = importFromFileCursor(appCtx, sourceDb)
        if (cursorResult.total > 0) {
            Log.i(TAG, "import cursor ok total=${cursorResult.total}")
            return cursorResult
        }

        val sourceTotal = sourceCounts.first + sourceCounts.second + sourceCounts.third
        if (sourceTotal > 0) {
            Log.w(TAG, "merge imported 0 but source has $sourceTotal rows, replacing database file")
            return replaceDatabaseFromFile(appCtx, sourceDb)
        }
        return cursorResult
    }

    private fun readSourceCounts(sourceDb: File): Triple<Int, Int, Int> {
        return runCatching {
            SQLiteDatabase.openDatabase(
                sourceDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                Triple(
                    tableCount(db, TABLE_VEHICLE_DATA_RECORD),
                    tableCount(db, TABLE_OPERATE_RECORD),
                    tableCount(db, TABLE_WARNING_RECORD),
                )
            }
        }.getOrElse {
            Triple(0, 0, 0)
        }
    }

    /** 合并失败时整库替换（适用于导入星瑞导出库）。 */
    private fun replaceDatabaseFromFile(context: Context, sourceDb: File): ImportResult {
        return try {
            invalidateHelper()
            val dest = databaseFile(context)
            dest.parentFile?.mkdirs()
            if (dest.exists() && !dest.delete()) {
                Log.e(TAG, "replaceDatabaseFromFile: cannot delete ${dest.absolutePath}")
                return ImportResult(0, 0, 0, 0)
            }
            sourceDb.copyTo(dest, overwrite = true)
            if (!isValidSqliteFile(dest)) {
                Log.e(TAG, "replaceDatabaseFromFile: copied file invalid")
                return ImportResult(0, 0, 0, 0)
            }
            patchImportedDatabaseFile(dest)
            invalidateHelper()
            val counts = loadHistoryCounts(context)
            val info = if (getVehicleVin(context).isNullOrBlank()) 0 else 1
            Log.i(TAG, "replaceDatabaseFromFile ok vehicle=${counts.first} operate=${counts.second} warning=${counts.third}")
            ImportResult(counts.first, counts.second, counts.third, info)
        } catch (e: Exception) {
            Log.e(TAG, "replaceDatabaseFromFile failed", e)
            invalidateHelper()
            ImportResult(0, 0, 0, 0)
        }
    }

    private fun importFromFileAttach(context: Context, sourceDb: File): ImportResult {
        val dest = writable(context)
        val attachPath = escapeSqlLiteral(sourceDb.absolutePath)
        var attached = false
        try {
            dest.execSQL("ATTACH DATABASE '$attachPath' AS ext")
            attached = true
            val vehicle = importVehicleData(dest)
            val operate = importTableIfExists(
                dest,
                "ext.${TABLE_OPERATE_RECORD}",
                TABLE_OPERATE_RECORD,
                arrayOf(COL_INSERT_DATE, COL_OPERATE, COL_ARGS, COL_VEHICLE_ID),
            )
            val warning = importTableIfExists(
                dest,
                "ext.${TABLE_WARNING_RECORD}",
                TABLE_WARNING_RECORD,
                arrayOf(COL_INSERT_DATE, COL_WARNING),
            )
            val info = importVehicleInfo(dest)
            return buildImportResult(context, sourceDb, vehicle, operate, warning, info)
        } finally {
            if (attached) {
                runCatching { dest.execSQL("DETACH DATABASE ext") }
            }
            invalidateHelper()
        }
    }

    /** 逐行导入：部分机型 ATTACH 失败时仍能合并星瑞导出库。 */
    private fun importFromFileCursor(context: Context, sourceDb: File): ImportResult {
        val src = SQLiteDatabase.openDatabase(
            sourceDb.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        val dest = writable(context)
        return try {
            dest.beginTransaction()
            val vehicle = importVehicleDataFromCursor(src, dest)
            val operate = importOperateFromCursor(src, dest)
            val warning = importWarningFromCursor(src, dest)
            val info = importVehicleInfoFromCursor(src, dest)
            dest.setTransactionSuccessful()
            buildImportResult(context, sourceDb, vehicle, operate, warning, info)
        } catch (e: Exception) {
            Log.e(TAG, "importFromFileCursor failed", e)
            ImportResult(0, 0, 0, 0)
        } finally {
            dest.endTransaction()
            src.close()
            invalidateHelper()
        }
    }

    private fun importVehicleDataFromCursor(src: SQLiteDatabase, dest: SQLiteDatabase): Int {
        if (!tableExists(src, TABLE_VEHICLE_DATA_RECORD)) return 0
        val hasSnapshot = columnExists(src, TABLE_VEHICLE_DATA_RECORD, COL_SNAPSHOT_JSON)
        var imported = 0
        src.query(
            TABLE_VEHICLE_DATA_RECORD,
            null,
            null,
            null,
            null,
            null,
            "$COL_INSERT_DATE ASC",
        ).use { c ->
            val idxDate = c.getColumnIndexOrThrow(COL_INSERT_DATE)
            val idxVolt = c.getColumnIndexOrThrow(COL_BATTERY_VOLTAGE)
            val idxOdo = c.getColumnIndexOrThrow(COL_ODOMETER)
            val idxFuel = c.getColumnIndexOrThrow(COL_FUEL_LEVEL)
            val idxRange = c.getColumnIndexOrThrow(COL_DISTANCE_TO_EMPTY)
            val idxOil = c.getColumnIndexOrThrow(COL_ADD_OLI_FLAG)
            val idxEngine = c.getColumnIndexOrThrow(COL_ENGINE_START)
            val idxVehicleId = c.getColumnIndexOrThrow(COL_VEHICLE_ID)
            val idxSnapshot = if (hasSnapshot) c.getColumnIndex(COL_SNAPSHOT_JSON) else -1
            while (c.moveToNext()) {
                val insertDate = c.getLong(idxDate)
                val battery = c.getDouble(idxVolt)
                val odometer = c.getDouble(idxOdo)
                val fuel = c.getDouble(idxFuel)
                val range = c.getDouble(idxRange)
                val engine = c.getInt(idxEngine)
                if (vehicleDataDuplicateExists(dest, insertDate, battery, odometer, fuel, range, engine)) {
                    continue
                }
                val cv = ContentValues().apply {
                    put(COL_INSERT_DATE, insertDate)
                    put(COL_BATTERY_VOLTAGE, battery)
                    put(COL_ODOMETER, odometer)
                    put(COL_FUEL_LEVEL, fuel)
                    put(COL_DISTANCE_TO_EMPTY, range)
                    put(COL_ADD_OLI_FLAG, c.getInt(idxOil))
                    put(COL_ENGINE_START, engine)
                    put(COL_VEHICLE_ID, c.getLong(idxVehicleId))
                    if (idxSnapshot >= 0 && !c.isNull(idxSnapshot)) {
                        put(COL_SNAPSHOT_JSON, c.getString(idxSnapshot))
                    }
                }
                if (dest.insert(TABLE_VEHICLE_DATA_RECORD, null, cv) >= 0L) {
                    imported++
                }
            }
        }
        return imported
    }

    private fun importOperateFromCursor(src: SQLiteDatabase, dest: SQLiteDatabase): Int {
        if (!tableExists(src, TABLE_OPERATE_RECORD)) return 0
        var imported = 0
        src.query(
            TABLE_OPERATE_RECORD,
            null,
            null,
            null,
            null,
            null,
            "$COL_INSERT_DATE ASC",
        ).use { c ->
            val idxDate = c.getColumnIndexOrThrow(COL_INSERT_DATE)
            val idxOperate = c.getColumnIndexOrThrow(COL_OPERATE)
            val idxArgs = c.getColumnIndexOrThrow(COL_ARGS)
            val idxVehicleId = c.getColumnIndexOrThrow(COL_VEHICLE_ID)
            while (c.moveToNext()) {
                val insertDate = c.getLong(idxDate)
                val operate = c.getString(idxOperate) ?: ""
                val args = c.getString(idxArgs) ?: ""
                if (operateDuplicateExists(dest, insertDate, operate, args)) continue
                val cv = ContentValues().apply {
                    put(COL_INSERT_DATE, insertDate)
                    put(COL_OPERATE, operate)
                    put(COL_ARGS, args)
                    put(COL_VEHICLE_ID, c.getLong(idxVehicleId))
                }
                if (dest.insert(TABLE_OPERATE_RECORD, null, cv) >= 0L) imported++
            }
        }
        return imported
    }

    private fun importWarningFromCursor(src: SQLiteDatabase, dest: SQLiteDatabase): Int {
        if (!tableExists(src, TABLE_WARNING_RECORD)) return 0
        var imported = 0
        src.query(
            TABLE_WARNING_RECORD,
            null,
            null,
            null,
            null,
            null,
            "$COL_INSERT_DATE ASC",
        ).use { c ->
            val idxDate = c.getColumnIndexOrThrow(COL_INSERT_DATE)
            val idxWarning = c.getColumnIndexOrThrow(COL_WARNING)
            while (c.moveToNext()) {
                val insertDate = c.getLong(idxDate)
                val warning = c.getString(idxWarning) ?: ""
                if (warningDuplicateExists(dest, insertDate, warning)) continue
                val cv = ContentValues().apply {
                    put(COL_INSERT_DATE, insertDate)
                    put(COL_WARNING, warning)
                }
                if (dest.insert(TABLE_WARNING_RECORD, null, cv) >= 0L) imported++
            }
        }
        return imported
    }

    private fun importVehicleInfoFromCursor(src: SQLiteDatabase, dest: SQLiteDatabase): Int {
        if (tableCount(dest, TABLE_VEHICLE_INFO) > 0) return 0
        if (!tableExists(src, TABLE_VEHICLE_INFO)) return 0
        var imported = 0
        src.query(
            TABLE_VEHICLE_INFO,
            arrayOf(COL_VIN, COL_INSERT_DATE),
            null,
            null,
            null,
            null,
            "$COL_ID ASC",
        ).use { c ->
            while (c.moveToNext()) {
                val vin = c.getString(0)?.trim().orEmpty()
                if (vin.isEmpty()) continue
                if (vinExists(dest, vin)) continue
                val cv = ContentValues().apply {
                    put(COL_VIN, vin)
                    put(COL_INSERT_DATE, c.getLong(1))
                }
                if (dest.insert(TABLE_VEHICLE_INFO, null, cv) >= 0L) imported++
            }
        }
        return imported
    }

    private fun vehicleDataDuplicateExists(
        db: SQLiteDatabase,
        insertDate: Long,
        battery: Double,
        odometer: Double,
        fuel: Double,
        range: Double,
        engine: Int,
    ): Boolean {
        val selection: String
        val args: Array<String>
        if (insertDate >= 0L) {
            selection = """
                $COL_INSERT_DATE = ? AND $COL_BATTERY_VOLTAGE = ? AND $COL_ODOMETER = ?
                AND $COL_FUEL_LEVEL = ? AND $COL_DISTANCE_TO_EMPTY = ? AND $COL_ENGINE_START = ?
            """.trimIndent()
            args = arrayOf(
                insertDate.toString(),
                battery.toString(),
                odometer.toString(),
                fuel.toString(),
                range.toString(),
                engine.toString(),
            )
        } else {
            selection = """
                $COL_BATTERY_VOLTAGE = ? AND $COL_ODOMETER = ?
                AND $COL_FUEL_LEVEL = ? AND $COL_DISTANCE_TO_EMPTY = ? AND $COL_ENGINE_START = ?
            """.trimIndent()
            args = arrayOf(
                battery.toString(),
                odometer.toString(),
                fuel.toString(),
                range.toString(),
                engine.toString(),
            )
        }
        db.query(
            TABLE_VEHICLE_DATA_RECORD,
            arrayOf(COL_ID),
            selection,
            args,
            null,
            null,
            null,
            "1",
        ).use { return it.moveToFirst() }
    }

    private fun operateDuplicateExists(
        db: SQLiteDatabase,
        insertDate: Long,
        operate: String,
        args: String,
    ): Boolean {
        db.query(
            TABLE_OPERATE_RECORD,
            arrayOf(COL_ID),
            "$COL_INSERT_DATE = ? AND $COL_OPERATE = ? AND IFNULL($COL_ARGS,'') = ?",
            arrayOf(insertDate.toString(), operate, args),
            null,
            null,
            null,
            "1",
        ).use { return it.moveToFirst() }
    }

    private fun warningDuplicateExists(
        db: SQLiteDatabase,
        insertDate: Long,
        warning: String,
    ): Boolean {
        db.query(
            TABLE_WARNING_RECORD,
            arrayOf(COL_ID),
            "$COL_INSERT_DATE = ? AND $COL_WARNING = ?",
            arrayOf(insertDate.toString(), warning),
            null,
            null,
            null,
            "1",
        ).use { return it.moveToFirst() }
    }

    private fun importVehicleData(dest: SQLiteDatabase): Int {
        if (!externalTableExists(dest, "ext.$TABLE_VEHICLE_DATA_RECORD")) return 0
        val hasSnapshot = externalColumnExists(dest, "ext.$TABLE_VEHICLE_DATA_RECORD", COL_SNAPSHOT_JSON)
        val snapshotCol = if (hasSnapshot) ", $COL_SNAPSHOT_JSON" else ""
        val snapshotSel = if (hasSnapshot) ", e.$COL_SNAPSHOT_JSON" else ", NULL"
        val sql = """
            INSERT INTO $TABLE_VEHICLE_DATA_RECORD (
                $COL_INSERT_DATE, $COL_BATTERY_VOLTAGE, $COL_ODOMETER, $COL_FUEL_LEVEL,
                $COL_DISTANCE_TO_EMPTY, $COL_ADD_OLI_FLAG, $COL_ENGINE_START, $COL_VEHICLE_ID$snapshotCol
            )
            SELECT e.$COL_INSERT_DATE, e.$COL_BATTERY_VOLTAGE, e.$COL_ODOMETER, e.$COL_FUEL_LEVEL,
                e.$COL_DISTANCE_TO_EMPTY, e.$COL_ADD_OLI_FLAG, e.$COL_ENGINE_START, e.$COL_VEHICLE_ID$snapshotSel
            FROM ext.$TABLE_VEHICLE_DATA_RECORD e
            WHERE NOT EXISTS (
                SELECT 1 FROM $TABLE_VEHICLE_DATA_RECORD l
                WHERE l.$COL_INSERT_DATE = e.$COL_INSERT_DATE
                  AND l.$COL_BATTERY_VOLTAGE = e.$COL_BATTERY_VOLTAGE
                  AND l.$COL_ODOMETER = e.$COL_ODOMETER
                  AND l.$COL_FUEL_LEVEL = e.$COL_FUEL_LEVEL
                  AND l.$COL_DISTANCE_TO_EMPTY = e.$COL_DISTANCE_TO_EMPTY
                  AND l.$COL_ENGINE_START = e.$COL_ENGINE_START
            )
        """.trimIndent()
        val before = tableCount(dest, TABLE_VEHICLE_DATA_RECORD)
        dest.execSQL(sql)
        return tableCount(dest, TABLE_VEHICLE_DATA_RECORD) - before
    }

    private fun tableCount(db: SQLiteDatabase, table: String): Int {
        if (!tableExists(db, table)) return 0
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { return it.moveToFirst() }
    }

    private fun importVehicleInfo(dest: SQLiteDatabase): Int {
        if (tableCount(dest, TABLE_VEHICLE_INFO) > 0) return 0
        if (!externalTableExists(dest, "ext.$TABLE_VEHICLE_INFO")) return 0
        var n = 0
        dest.rawQuery("SELECT $COL_VIN, $COL_INSERT_DATE FROM ext.$TABLE_VEHICLE_INFO", null).use { c ->
            while (c.moveToNext()) {
                val vin = c.getString(0) ?: continue
                if (vin.isBlank()) continue
                if (vinExists(dest, vin)) continue
                val cv = ContentValues().apply {
                    put(COL_VIN, vin)
                    put(COL_INSERT_DATE, c.getLong(1))
                }
                dest.insert(TABLE_VEHICLE_INFO, null, cv)
                n++
            }
        }
        return n
    }

    private fun importTableIfExists(
        dest: SQLiteDatabase,
        extTable: String,
        localTable: String,
        columns: Array<String>,
    ): Int {
        if (!externalTableExists(dest, extTable)) return 0
        val cols = columns.joinToString(", ")
        val eCols = columns.joinToString(", ") { "e.$it" }
        val where = when (localTable) {
            TABLE_OPERATE_RECORD -> """
                WHERE NOT EXISTS (
                    SELECT 1 FROM $localTable l
                    WHERE l.$COL_INSERT_DATE = e.$COL_INSERT_DATE
                      AND l.$COL_OPERATE = e.$COL_OPERATE
                      AND IFNULL(l.$COL_ARGS,'') = IFNULL(e.$COL_ARGS,'')
                )
            """.trimIndent()
            TABLE_WARNING_RECORD -> """
                WHERE NOT EXISTS (
                    SELECT 1 FROM $localTable l
                    WHERE l.$COL_INSERT_DATE = e.$COL_INSERT_DATE
                      AND l.$COL_WARNING = e.$COL_WARNING
                )
            """.trimIndent()
            else -> ""
        }
        val before = tableCount(dest, localTable)
        dest.execSQL(
            "INSERT INTO $localTable ($cols) SELECT $eCols FROM $extTable e $where",
        )
        return tableCount(dest, localTable) - before
    }

    private fun vinExists(db: SQLiteDatabase, vin: String): Boolean {
        db.query(
            TABLE_VEHICLE_INFO,
            arrayOf(COL_ID),
            "$COL_VIN = ?",
            arrayOf(vin),
            null,
            null,
            null,
            "1",
        ).use { return it.moveToFirst() }
    }

    private fun externalTableExists(db: SQLiteDatabase, table: String): Boolean {
        val (dbName, tableName) = if (table.contains('.')) {
            val p = table.split('.', limit = 2)
            p[0] to p[1]
        } else {
            "main" to table
        }
        db.rawQuery(
            "SELECT name FROM $dbName.sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName),
        ).use { return it.moveToFirst() }
    }

    private fun externalColumnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        val tableName = table.substringAfter('.')
        return try {
            db.rawQuery("PRAGMA ext.table_info(`$tableName`)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                if (nameIdx < 0) return false
                while (c.moveToNext()) {
                    if (column.equals(c.getString(nameIdx), ignoreCase = true)) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File): Boolean {
        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val src = File(path)
                    if (src.isFile) {
                        src.copyTo(dest, overwrite = true)
                        return dest.isFile && dest.length() > 0L
                    }
                }
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                FileOutputStream(dest).use { it.write(bytes) }
                return dest.isFile && dest.length() > 0L
            }
            val copied = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                true
            } ?: false
            copied && dest.isFile && dest.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToFile failed uri=$uri", e)
            false
        }
    }

    private fun isValidSqliteFile(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(16)
                stream.read(header) == 16 && header.decodeToString().startsWith("SQLite format 3")
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 合并前将 WAL 日志落盘，避免只复制主库文件时读不到数据。 */
    private fun prepareSourceDatabase(sourceDb: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                sourceDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
                db.rawQuery("PRAGMA journal_mode=DELETE", null).close()
            }
        }.onFailure { Log.w(TAG, "prepareSourceDatabase skipped for ${sourceDb.name}", it) }
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return false
            while (c.moveToNext()) {
                if (column.equals(c.getString(nameIdx), ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun escapeSqlLiteral(value: String): String = value.replace("'", "''")

    private fun invalidateHelper() {
        helper?.close()
        helper = null
    }

    /**
     * 星瑞导出库 user_version 常为 0；若直接交给 [SQLiteOpenHelper] 会误走 onCreate 并与已有表冲突。
     */
    private fun patchImportedDatabaseFile(dbFile: File) {
        if (!dbFile.isFile) return
        runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                if (tableExists(db, TABLE_VEHICLE_DATA_RECORD) &&
                    !columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_SNAPSHOT_JSON)
                ) {
                    db.execSQL(
                        "ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_SNAPSHOT_JSON TEXT",
                    )
                }
                db.version = DB_VERSION
                Log.d(TAG, "patchImportedDatabaseFile version=${db.version}")
            }
        }.onFailure { Log.e(TAG, "patchImportedDatabaseFile failed", it) }
    }

    private fun isAndroidMetadataConflict(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("android_metadata", ignoreCase = true)
    }

    private fun repairCorruptDatabase(context: Context) {
        Log.w(TAG, "repairCorruptDatabase: removing broken history db")
        invalidateHelper()
        val dest = databaseFile(context.applicationContext)
        dest.delete()
        File(dest.absolutePath + "-wal").delete()
        File(dest.absolutePath + "-shm").delete()
    }

    private fun readable(context: Context): SQLiteDatabase =
        openDatabase(context, readOnly = true)

    private fun writable(context: Context): SQLiteDatabase =
        openDatabase(context, readOnly = false)

    private fun openDatabase(context: Context, readOnly: Boolean): SQLiteDatabase {
        return try {
            if (readOnly) {
                openHelper(context).readableDatabase
            } else {
                openHelper(context).writableDatabase
            }
        } catch (e: Exception) {
            if (isAndroidMetadataConflict(e)) {
                repairCorruptDatabase(context)
                if (readOnly) {
                    openHelper(context).readableDatabase
                } else {
                    openHelper(context).writableDatabase
                }
            } else {
                throw e
            }
        }
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(CarControlPrefsHelper.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREFS_KEY_LEGACY_MIGRATED, false)) return
        val raw = prefs.getString("vehicle_data_history_v1", null) ?: run {
            prefs.edit().putBoolean(PREFS_KEY_LEGACY_MIGRATED, true).apply()
            return
        }
        try {
            val arr = JSONArray(raw)
            val db = writable(context)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val snap = o.optJSONObject("snapshot") ?: continue
                val rangeKm = snap.optInt("rangeKm", -1)
                val fuelPct = snap.optInt("fuelPercent", -1)
                val cv = ContentValues().apply {
                    put(COL_INSERT_DATE, o.optLong("savedAtMillis", snap.optLong("savedAtMillis", System.currentTimeMillis())))
                    put(COL_BATTERY_VOLTAGE, 0.0)
                    put(COL_ODOMETER, 0.0)
                    put(COL_FUEL_LEVEL, if (fuelPct >= 0) fuelPct.toDouble() else 0.0)
                    put(COL_DISTANCE_TO_EMPTY, if (rangeKm >= 0) rangeKm.toDouble() else 0.0)
                    put(COL_ADD_OLI_FLAG, 0)
                    put(COL_ENGINE_START, 0)
                    put(COL_VEHICLE_ID, 1L)
                    put(COL_SNAPSHOT_JSON, snap.toString())
                }
                db.insert(TABLE_VEHICLE_DATA_RECORD, null, cv)
            }
            prefs.edit().remove("vehicle_data_history_v1").putBoolean(PREFS_KEY_LEGACY_MIGRATED, true).apply()
        } catch (_: JSONException) {
            prefs.edit().putBoolean(PREFS_KEY_LEGACY_MIGRATED, true).apply()
        }
    }

    private fun currentVehicleId(context: Context): Long {
        val db = readable(context)
        db.query(
            TABLE_VEHICLE_INFO,
            arrayOf(COL_ID),
            null,
            null,
            null,
            null,
            "$COL_ID ASC",
            "1",
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 1L
    }

    private data class RecordLocation(
        val latitude: Double?,
        val longitude: Double?,
        val address: String?,
    )

    private fun resolveRecordLocation(context: Context, status: VehicleStatusInfo): RecordLocation {
        val coords = VehiclePositionHelper.fromStatus(status) ?: return RecordLocation(null, null, null)
        VehiclePositionHelper.saveCache(context, coords, VehiclePositionHelper.isMarsGcj(status.marsCoordinates))
        val cached = VehiclePositionHelper.loadCache(context)
        val cachedAddr = VehiclePositionHelper.loadAddressCache(context)
        val address = if (
            cached != null &&
            cachedAddr != null &&
            kotlin.math.abs(cached.lng - coords.lng) < 0.002 &&
            kotlin.math.abs(cached.lat - coords.lat) < 0.002
        ) {
            cachedAddr
        } else {
            VehiclePositionHelper.resolveDisplayAddress(context, coords.lng, coords.lat)
        }
        return RecordLocation(latitude = coords.lat, longitude = coords.lng, address = address)
    }

    private fun buildSnapshotJson(
        context: Context,
        status: VehicleStatusInfo,
        vehicleUpdateMillis: Long,
    ): JSONObject {
        val root = JSONObject()
        root.put("updateTime", status.updateTime ?: "")
        root.put("updateDateTime", status.updateDateTime ?: "")
        root.put("vehicleUpdateMillis", vehicleUpdateMillis)
        root.put("savedAtMillis", System.currentTimeMillis())
        root.put("rangeKm", VehicleStatusService.parseDistanceToEmptyKm(status.distanceToEmpty))
        val fuelLiters = VehicleFuelParser.parseLiters(status)
        fuelLiters?.let { root.put("fuelLiters", it) }
        root.put("fuelValueIsLiters", fuelLiters != null)
        root.put(
            "interiorTemp",
            VehicleStatusService.formatTempCelsiusDigitsOrUnknown(status.interiorTemp),
        )
        root.put(
            "exteriorTemp",
            VehicleStatusService.formatTempCelsiusDigitsOrUnknown(status.exteriorTemp),
        )
        try {
            root.put("queryData", VehicleStatusService.buildVehicleQueryBroadcastJson(status))
        } catch (_: JSONException) {
            root.put("queryData", JSONObject())
        }
        val tires = JSONArray()
        addTire(tires, "左前", status.tyreStatusDriver)
        addTire(tires, "右前", status.tyreStatusPassenger)
        addTire(tires, "左后", status.tyreStatusDriverRear)
        addTire(tires, "右后", status.tyreStatusPassengerRear)
        root.put("tirePressure", tires)
        return root
    }

    private fun addTire(arr: JSONArray, label: String, pressure: String?) {
        arr.put(
            JSONObject().apply {
                put("label", label)
                put("pressure", pressure ?: "")
            },
        )
    }

    private fun isEngineRunning(status: VehicleStatusInfo): Boolean {
        val t = VehicleStatusService.translateEngineStatus(status.engineStatus)
        return t == "运行中" || t.contains("运行") || t.contains("启动")
    }

    private fun parseAddOilFlag(serviceWarning: String?): Int {
        val s = serviceWarning?.trim().orEmpty()
        if (s.isEmpty() || s == "未知") return 0
        return if (s.contains("机油") || s.contains("保养") || s == "1") 1 else 0
    }

    private fun parseVoltage(raw: String?): Double =
        parseNumber(raw, 0.0)

    private fun parseOdometer(raw: String?): Double =
        parseNumber(raw, 0.0)

    private fun parseDistanceKm(raw: String?): Int =
        VehicleStatusService.parseDistanceToEmptyKm(raw ?: "")

    private fun parseNumber(raw: String?, default: Double): Double {
        if (raw.isNullOrBlank() || raw == "未知") return default
        val cleaned = raw.replace(Regex("[^0-9.]"), "")
        return cleaned.toDoubleOrNull() ?: default
    }

    /** 车辆云端刷新时间（毫秒），用于 INSERT_DATE 与去重；无效时返回 -1。 */
    internal fun hasVehicleUpdateTime(status: VehicleStatusInfo): Boolean =
        VehicleStatusService.resolveVehicleUpdateMillis(status) > 0L

    private fun recordExistsForVehicleUpdate(context: Context, vehicleUpdateMillis: Long): Boolean {
        val db = readable(context)
        db.query(
            TABLE_VEHICLE_DATA_RECORD,
            arrayOf(COL_ID),
            "$COL_INSERT_DATE = ?",
            arrayOf(vehicleUpdateMillis.toString()),
            null,
            null,
            null,
            "1",
        ).use { return it.moveToFirst() }
    }

    private fun cursorToVehicleData(c: Cursor, includeSnapshot: Boolean): VehicleDataRecord {
        val snapshotIdx = c.getColumnIndex(COL_SNAPSHOT_JSON)
        val snapshot = if (includeSnapshot && snapshotIdx >= 0 && !c.isNull(snapshotIdx)) {
            c.getString(snapshotIdx)
        } else {
            null
        }
        val litersIdx = c.getColumnIndex(COL_FUEL_LITERS)
        val pctIdx = c.getColumnIndex(COL_FUEL_PERCENT)
        val fuelLiters = if (litersIdx >= 0 && !c.isNull(litersIdx)) c.getDouble(litersIdx) else -1.0
        val fuelPercent = if (pctIdx >= 0 && !c.isNull(pctIdx)) c.getInt(pctIdx) else -1
        fun optDouble(col: String): Double? {
            val idx = c.getColumnIndex(col)
            if (idx < 0 || c.isNull(idx)) return null
            return c.getDouble(idx)
        }
        fun optStr(col: String): String? {
            val idx = c.getColumnIndex(col)
            if (idx < 0 || c.isNull(idx)) return null
            return c.getString(idx)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return VehicleDataRecord(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            insertDate = c.getLong(c.getColumnIndexOrThrow(COL_INSERT_DATE)),
            batteryVoltage = c.getDouble(c.getColumnIndexOrThrow(COL_BATTERY_VOLTAGE)),
            odometer = c.getDouble(c.getColumnIndexOrThrow(COL_ODOMETER)),
            fuelLevel = c.getDouble(c.getColumnIndexOrThrow(COL_FUEL_LEVEL)),
            fuelLiters = fuelLiters,
            fuelPercent = fuelPercent,
            distanceToEmpty = c.getDouble(c.getColumnIndexOrThrow(COL_DISTANCE_TO_EMPTY)),
            addOliFlag = c.getInt(c.getColumnIndexOrThrow(COL_ADD_OLI_FLAG)),
            engineStart = c.getInt(c.getColumnIndexOrThrow(COL_ENGINE_START)),
            vehicleId = c.getLong(c.getColumnIndexOrThrow(COL_VEHICLE_ID)),
            snapshotJson = snapshot,
            latitude = optDouble(COL_LATITUDE),
            longitude = optDouble(COL_LONGITUDE),
            address = optStr(COL_ADDRESS),
        )
    }

    private fun cursorToVehicleInfo(c: Cursor): VehicleInfoRecord {
        fun optStr(col: String): String? {
            val idx = c.getColumnIndex(col)
            if (idx < 0 || c.isNull(idx)) return null
            return c.getString(idx)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return VehicleInfoRecord(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            vin = optStr(COL_VIN),
            plateNo = optStr(COL_PLATE_NO),
            seriesName = optStr(COL_SERIES_NAME),
            modelName = optStr(COL_MODEL_NAME),
            colorName = optStr(COL_COLOR_NAME),
            insertDate = c.getLong(c.getColumnIndexOrThrow(COL_INSERT_DATE)),
        )
    }

    fun formatInsertDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun openHelper(context: Context): OpenHelper {
        if (helper == null) {
            helper = OpenHelper(context.applicationContext)
        }
        return helper!!
    }

    private class OpenHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_VEHICLE_INFO (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_VIN TEXT,
                    $COL_PLATE_NO TEXT,
                    $COL_SERIES_NAME TEXT,
                    $COL_MODEL_NAME TEXT,
                    $COL_COLOR_NAME TEXT,
                    $COL_INSERT_DATE INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_VEHICLE_DATA_RECORD (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_INSERT_DATE INTEGER,
                    $COL_BATTERY_VOLTAGE REAL NOT NULL,
                    $COL_ODOMETER REAL NOT NULL,
                    $COL_FUEL_LEVEL REAL NOT NULL,
                    $COL_FUEL_LITERS REAL NOT NULL DEFAULT -1,
                    $COL_FUEL_PERCENT INTEGER NOT NULL DEFAULT -1,
                    $COL_DISTANCE_TO_EMPTY REAL NOT NULL,
                    $COL_ADD_OLI_FLAG INTEGER NOT NULL,
                    $COL_ENGINE_START INTEGER NOT NULL,
                    $COL_VEHICLE_ID INTEGER,
                    $COL_SNAPSHOT_JSON TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_OPERATE_RECORD (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_INSERT_DATE INTEGER,
                    $COL_OPERATE TEXT,
                    $COL_ARGS TEXT,
                    $COL_VEHICLE_ID INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_WARNING_RECORD (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_INSERT_DATE INTEGER,
                    $COL_WARNING TEXT
                )
                """.trimIndent(),
            )
            createFuelCacheTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2 && !columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_SNAPSHOT_JSON)) {
                runCatching {
                    db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_SNAPSHOT_JSON TEXT")
                }
            }
            if (oldVersion < 3) {
                migrateSchemaV3(db)
            }
            if (oldVersion < 4) {
                migrateSchemaV4(db)
            }
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (!columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_SNAPSHOT_JSON)) {
                runCatching {
                    db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_SNAPSHOT_JSON TEXT")
                }
            }
            migrateSchemaV3(db)
            migrateSchemaV4(db)
        }
    }

    private fun createFuelCacheTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FUEL_PRICE_DAY (
                $COL_DAY_KEY TEXT NOT NULL,
                $COL_PROVINCE TEXT NOT NULL,
                $COL_PRICE95 REAL NOT NULL,
                $COL_IS_REF_PRICE INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER,
                PRIMARY KEY ($COL_DAY_KEY, $COL_PROVINCE)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FUEL_REFUEL_META (
                $COL_RECORD_ID INTEGER PRIMARY KEY,
                $COL_DAY_KEY TEXT,
                $COL_IS_REFUEL INTEGER NOT NULL DEFAULT 1,
                $COL_ADDED_LITERS REAL,
                $COL_PRICE95 REAL,
                $COL_IS_REF_PRICE INTEGER NOT NULL DEFAULT 0,
                $COL_TANK_L100 REAL,
                $COL_TRIP_COST REAL,
                $COL_REFUEL_COST REAL,
                $COL_FUEL_USED REAL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FUEL_ANALYTICS_CACHE (
                $COL_CACHE_KEY TEXT PRIMARY KEY,
                $COL_FINGERPRINT TEXT,
                $COL_LAST_TANK_L100 REAL,
                $COL_LAST_TANK_KM REAL,
                $COL_LAST_TANK_FUEL REAL,
                $COL_LAST_TANK_COST REAL,
                $COL_REFUEL_COUNT INTEGER,
                $COL_ECU_AVG_FUEL TEXT,
                $COL_COMPUTED_AT INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FUEL_MONTHLY_SUMMARY (
                $COL_MONTH_KEY TEXT PRIMARY KEY,
                $COL_REFUEL_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_TOTAL_COST REAL NOT NULL DEFAULT 0,
                $COL_TOTAL_LITERS REAL NOT NULL DEFAULT 0,
                $COL_AVG_L100 REAL
            )
            """.trimIndent(),
        )
    }

    private fun migrateSchemaV4(db: SQLiteDatabase) {
        if (!columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_LATITUDE)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_LATITUDE REAL") }
        }
        if (!columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_LONGITUDE)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_LONGITUDE REAL") }
        }
        if (!columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_ADDRESS)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_ADDRESS TEXT") }
        }
        if (tableExists(db, TABLE_FUEL_REFUEL_META) &&
            !columnExists(db, TABLE_FUEL_REFUEL_META, COL_REFUEL_COST)
        ) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_FUEL_REFUEL_META ADD COLUMN $COL_REFUEL_COST REAL") }
        }
        createFuelCacheTables(db)
    }

    private fun migrateSchemaV3(db: SQLiteDatabase) {
        if (!columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_FUEL_LITERS)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_FUEL_LITERS REAL NOT NULL DEFAULT -1") }
        }
        if (!columnExists(db, TABLE_VEHICLE_DATA_RECORD, COL_FUEL_PERCENT)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_DATA_RECORD ADD COLUMN $COL_FUEL_PERCENT INTEGER NOT NULL DEFAULT -1") }
        }
        if (!columnExists(db, TABLE_VEHICLE_INFO, COL_PLATE_NO)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_INFO ADD COLUMN $COL_PLATE_NO TEXT") }
        }
        if (!columnExists(db, TABLE_VEHICLE_INFO, COL_SERIES_NAME)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_INFO ADD COLUMN $COL_SERIES_NAME TEXT") }
        }
        if (!columnExists(db, TABLE_VEHICLE_INFO, COL_MODEL_NAME)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_INFO ADD COLUMN $COL_MODEL_NAME TEXT") }
        }
        if (!columnExists(db, TABLE_VEHICLE_INFO, COL_COLOR_NAME)) {
            runCatching { db.execSQL("ALTER TABLE $TABLE_VEHICLE_INFO ADD COLUMN $COL_COLOR_NAME TEXT") }
        }
    }
}
