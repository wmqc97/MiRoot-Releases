package com.wmqc.miroot.backup

import android.content.Context
import android.content.SharedPreferences
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.R
import com.wmqc.miroot.car.CarControlPrefsHelper
import com.wmqc.miroot.car.LoginService
import com.wmqc.miroot.car.VehicleHistoryDatabase
import com.wmqc.miroot.charging.ChargingAnimationPrefs
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.rear.truthdare.TruthDarePrefs
import com.wmqc.miroot.ui.music.MusicAutoProjectionPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 应用配置备份/恢复：SharedPreferences、车辆历史库、应用内资源文件。
 * 不包含车控登录凭据（LoginPrefs）、WebDAV 凭据、以及 [LoginService.getLoginInfoFile]（0.json）。
 */
object MiRootConfigBackup {

    private const val TAG = "MiRootConfigBackup"
    const val BACKUP_FORMAT_VERSION = 1
    const val ZIP_ENTRY_MANIFEST = "manifest.json"
    /** 供用户在文件管理器中直接查看的备份说明（导出时间、大小等）。 */
    const val ZIP_ENTRY_LABEL = "backup_label.txt"
    const val ZIP_DIR_PREFS = "prefs/"
    const val ZIP_DIR_DATABASES = "databases/"
    const val ZIP_DIR_FILES = "files/"

    /**
     * 需备份的 SharedPreferences 名称（新增模块时请同步补充）。
     * 车控历史数据在 [VehicleHistoryDatabase.DB_NAME]，不在此列表。
     */
    val BACKUP_PREFS_NAMES: List<String> = listOf(
        CarControlPrefsHelper.PREFS_NAME,
        "CarControlWidgetPrefs",
        "LyricsSettings",
        "FlutterSharedPreferences",
        ChargingAnimationPrefs.PREFS_NAME,
        "miroot_rear_assist",
        "miroot_rear_bottom_gestures",
        "rear_desktop_v1",
        "miroot_app_projection_display",
        "miroot_record",
        "miroot_theme",
        "miroot_directory_cover_bindings",
        "miroot_app_prefs",
        "miroot_update_prefs",
        "fuel_price_95_day_cache_v2",
        "fuel_price_95_latest_v1",
        "music_auto_projection_v1",
        TruthDarePrefs.PREFS_NAME,
    )

    private val EXCLUDED_PREFS = setOf(
        "LoginPrefs",
        WebDavBackupPrefs.PREFS_NAME,
        "miroot_perm_cache",
        "offline_activation_prefs",
        "afdian_unlock_prefs",
    )

    data class VehicleHistoryBackupInfo(
        val included: Boolean,
        val vehicleRecords: Int,
        val operateRecords: Int,
        val warningRecords: Int,
        val fileBytes: Long,
    )

    data class BackupPackageInfo(
        val createdAtMillis: Long,
        val archiveBytes: Long,
        val appVersionName: String,
        val formatVersion: Int,
        val vehicleHistory: VehicleHistoryBackupInfo,
    )

    data class BackupSummary(
        val prefsCount: Int,
        val prefsKeys: Int,
        val vehicleHistory: VehicleHistoryBackupInfo,
        val extraFiles: Int,
        val packageInfo: BackupPackageInfo,
    ) {
        val hasVehicleDb: Boolean get() = vehicleHistory.included
    }

    data class RestoreSummary(
        val prefsRestored: Int,
        val filesRestored: Int,
        val vehicleDbRestored: Boolean,
        val vehicleRecordsAfterRestore: Int,
    )

    fun buildSummary(context: Context): BackupSummary {
        val app = context.applicationContext
        var keys = 0
        for (name in BACKUP_PREFS_NAMES) {
            if (name in EXCLUDED_PREFS) continue
            keys += prefsStore(app, name).all.size
        }
        val vehicle = readVehicleHistoryInfo(app)
        return BackupSummary(
            prefsCount = BACKUP_PREFS_NAMES.size,
            prefsKeys = keys,
            vehicleHistory = vehicle,
            extraFiles = collectExtraFiles(app).size,
            packageInfo = BackupPackageInfo(
                createdAtMillis = 0L,
                archiveBytes = 0L,
                appVersionName = BuildConfig.VERSION_NAME,
                formatVersion = BACKUP_FORMAT_VERSION,
                vehicleHistory = vehicle,
            ),
        )
    }

    fun readPackageInfo(zipFile: File): Result<BackupPackageInfo> = runCatching {
        val zipBytes = zipFile.length().coerceAtLeast(0L)
        val manifest = readManifestFromZip(zipFile)
            ?: error("missing manifest")
        val vehicleJson = manifest.optJSONObject("vehicleHistory")
        val vehicle = VehicleHistoryBackupInfo(
            included = vehicleJson?.optBoolean("included", false) ?: false,
            vehicleRecords = vehicleJson?.optInt("vehicleRecords", 0) ?: 0,
            operateRecords = vehicleJson?.optInt("operateRecords", 0) ?: 0,
            warningRecords = vehicleJson?.optInt("warningRecords", 0) ?: 0,
            fileBytes = vehicleJson?.optLong("fileBytes", 0L) ?: 0L,
        )
        val archiveBytes = manifest.optLong("archiveBytes", 0L).takeIf { it > 0L } ?: zipBytes
        BackupPackageInfo(
            createdAtMillis = manifest.optLong("createdAt", 0L),
            archiveBytes = archiveBytes,
            appVersionName = manifest.optString("appVersionName", "?"),
            formatVersion = manifest.optInt("formatVersion", 0),
            vehicleHistory = vehicle,
        )
    }

    fun formatArchiveSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    fun formatExportTime(context: Context, millis: Long): String {
        if (millis <= 0L) return context.getString(R.string.webdav_backup_time_unknown)
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return Instant.ofEpochMilli(millis).atZone(zone).format(formatter)
    }

    fun formatPackageInfoSummary(context: Context, info: BackupPackageInfo): String {
        val time = formatExportTime(context, info.createdAtMillis)
        val size = formatArchiveSize(info.archiveBytes)
        val version = info.appVersionName.ifBlank { "?" }
        val vehicle = if (info.vehicleHistory.included) {
            context.getString(
                R.string.webdav_backup_package_vehicle_fmt,
                info.vehicleHistory.vehicleRecords,
                info.vehicleHistory.operateRecords,
                info.vehicleHistory.warningRecords,
            )
        } else {
            context.getString(R.string.webdav_backup_package_vehicle_none)
        }
        return context.getString(
            R.string.webdav_backup_package_summary_fmt,
            time,
            size,
            version,
            vehicle,
        )
    }

    fun formatPackageInfoForDialog(context: Context, info: BackupPackageInfo): String {
        val time = formatExportTime(context, info.createdAtMillis)
        val size = formatArchiveSize(info.archiveBytes)
        val version = info.appVersionName.ifBlank { "?" }
        val vehicle = if (info.vehicleHistory.included) {
            context.getString(
                R.string.webdav_backup_package_vehicle_fmt,
                info.vehicleHistory.vehicleRecords,
                info.vehicleHistory.operateRecords,
                info.vehicleHistory.warningRecords,
            )
        } else {
            context.getString(R.string.webdav_backup_package_vehicle_none)
        }
        return context.getString(
            R.string.webdav_backup_restore_confirm_message_fmt,
            time,
            size,
            version,
            vehicle,
        )
    }

    fun createBackupZip(context: Context, destZip: File): Result<BackupSummary> = runCatching {
        val app = context.applicationContext
        destZip.parentFile?.mkdirs()
        if (destZip.exists()) destZip.delete()

        val createdAt = System.currentTimeMillis()
        val vehicleInfoBeforeFlush = readVehicleHistoryInfo(app)

        var prefsKeys = 0
        var extraFileCount = 0
        var vehiclePacked = VehicleHistoryBackupInfo(false, 0, 0, 0, 0L)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(destZip))).use { zos ->
            for (name in BACKUP_PREFS_NAMES) {
                if (name in EXCLUDED_PREFS) continue
                val json = prefsToJson(prefsStore(app, name))
                prefsKeys += json.length()
                putZipEntry(zos, "$ZIP_DIR_PREFS$name.json", json.toString().toByteArray())
            }

            vehiclePacked = packVehicleHistoryDatabase(app, zos, vehicleInfoBeforeFlush)

            for (file in collectExtraFiles(app)) {
                if (!file.isFile) continue
                val rel = fileRelativePath(app, file) ?: continue
                putZipEntry(zos, "$ZIP_DIR_FILES$rel", file.readBytes())
                extraFileCount++
            }

            val manifest = JSONObject()
                .put("formatVersion", BACKUP_FORMAT_VERSION)
                .put("appVersionName", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("createdAt", createdAt)
                .put("packageName", app.packageName)
                .put(
                    "includes",
                    JSONArray()
                        .put("shared_preferences")
                        .put("vehicle_history_database")
                        .put("app_private_files"),
                )
                .put(
                    "excludes",
                    JSONArray()
                        .put("LoginPrefs")
                        .put("0.json")
                        .put(WebDavBackupPrefs.PREFS_NAME),
                )
                .put("prefsNames", JSONArray(BACKUP_PREFS_NAMES))
                .put(
                    "vehicleHistory",
                    JSONObject()
                        .put("dbName", VehicleHistoryDatabase.DB_NAME)
                        .put("included", vehiclePacked.included)
                        .put("vehicleRecords", vehiclePacked.vehicleRecords)
                        .put("operateRecords", vehiclePacked.operateRecords)
                        .put("warningRecords", vehiclePacked.warningRecords)
                        .put("fileBytes", vehiclePacked.fileBytes),
                )
            putZipEntry(zos, ZIP_ENTRY_MANIFEST, manifest.toString(2).toByteArray(Charsets.UTF_8))
        }

        finalizeBackupPackage(app, destZip, createdAt, vehiclePacked)

        val packageInfo = readPackageInfo(destZip).getOrThrow()
        BackupSummary(
            prefsCount = BACKUP_PREFS_NAMES.size,
            prefsKeys = prefsKeys,
            vehicleHistory = vehiclePacked,
            extraFiles = extraFileCount,
            packageInfo = packageInfo,
        )
    }.onFailure { LogHelper.e(TAG, "createBackupZip failed", it) }

    fun restoreFromZip(context: Context, zipFile: File): Result<RestoreSummary> = runCatching {
        val app = context.applicationContext
        val extractRoot = File(app.cacheDir, "miroot_restore_${System.currentTimeMillis()}")
        extractRoot.mkdirs()
        try {
            unzipToDirectory(zipFile, extractRoot)
            val manifestFile = File(extractRoot, ZIP_ENTRY_MANIFEST)
            if (!manifestFile.isFile) error("missing manifest")
            val manifest = JSONObject(manifestFile.readText())
            val version = manifest.optInt("formatVersion", 0)
            if (version > BACKUP_FORMAT_VERSION) {
                error("unsupported backup version $version")
            }

            var filesRestored = 0
            val filesDir = File(extractRoot, "files")
            if (filesDir.isDirectory) {
                filesDir.walkTopDown().filter { it.isFile }.forEach { src ->
                    val rel = src.relativeTo(filesDir).path.replace('\\', '/')
                    val dest = resolveRestoreFile(app, rel)
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                    filesRestored++
                }
            }

            var prefsRestored = 0
            val prefsDir = File(extractRoot, "prefs")
            if (prefsDir.isDirectory) {
                prefsDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
                    val name = f.nameWithoutExtension
                    if (name in EXCLUDED_PREFS || name !in BACKUP_PREFS_NAMES) return@forEach
                    applyPrefsFromJson(
                        prefsStore(app, name),
                        JSONObject(f.readText()),
                    )
                    prefsRestored++
                }
            }

            var vehicleDbRestored = false
            var vehicleRecordsAfter = 0
            val dbSrc = File(extractRoot, "databases/${VehicleHistoryDatabase.DB_NAME}")
            if (dbSrc.isFile) {
                vehicleDbRestored = VehicleHistoryDatabase.replaceFromBackupFile(app, dbSrc)
                if (vehicleDbRestored) {
                    vehicleRecordsAfter = VehicleHistoryDatabase.loadHistoryCounts(app).first
                }
            }

            RestoreSummary(
                prefsRestored = prefsRestored,
                filesRestored = filesRestored,
                vehicleDbRestored = vehicleDbRestored,
                vehicleRecordsAfterRestore = vehicleRecordsAfter,
            )
        } finally {
            extractRoot.deleteRecursively()
        }
    }.onFailure { LogHelper.e(TAG, "restoreFromZip failed", it) }

    /** [ChargingAnimationPrefs] 存于设备保护存储，须用对应 Context 读写。 */
    private fun prefsHostContext(app: Context, prefsName: String): Context =
        if (prefsName == ChargingAnimationPrefs.PREFS_NAME) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }

    private fun prefsStore(app: Context, prefsName: String): SharedPreferences =
        prefsHostContext(app, prefsName).getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun readVehicleHistoryInfo(app: Context): VehicleHistoryBackupInfo {
        val dbFile = VehicleHistoryDatabase.databaseFile(app)
        if (!dbFile.isFile) {
            return VehicleHistoryBackupInfo(false, 0, 0, 0, 0L)
        }
        val counts = runCatching { VehicleHistoryDatabase.loadHistoryCounts(app) }
            .getOrElse { Triple(0, 0, 0) }
        return VehicleHistoryBackupInfo(
            included = true,
            vehicleRecords = counts.first,
            operateRecords = counts.second,
            warningRecords = counts.third,
            fileBytes = dbFile.length(),
        )
    }

    private fun packVehicleHistoryDatabase(
        app: Context,
        zos: ZipOutputStream,
        stats: VehicleHistoryBackupInfo,
    ): VehicleHistoryBackupInfo {
        migrateLegacyVehicleHistoryIfNeeded(app)
        VehicleHistoryDatabase.flushConnectionsForBackup(app)
        val dbFile = VehicleHistoryDatabase.databaseFile(app)
        if (!dbFile.isFile || dbFile.length() <= 0L) {
            LogHelper.w(TAG, "packVehicleHistoryDatabase: no db file to pack")
            return stats.copy(included = false, fileBytes = 0L)
        }
        val staged = stageDatabaseForZip(app, dbFile)
        return try {
            putZipEntry(
                zos,
                "$ZIP_DIR_DATABASES${VehicleHistoryDatabase.DB_NAME}",
                staged.readBytes(),
            )
            stats.copy(included = true, fileBytes = staged.length())
        } finally {
            staged.delete()
        }
    }

    private fun migrateLegacyVehicleHistoryIfNeeded(app: Context) {
        runCatching {
            VehicleHistoryDatabase.loadHistoryCounts(app)
        }
    }

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                null -> out.put(key, JSONObject.NULL)
                is Boolean -> out.put(key, value)
                is Int -> out.put(key, value)
                is Long -> out.put(key, value)
                is Float -> out.put(key, value.toDouble())
                is Double -> out.put(key, value)
                is String -> out.put(key, value)
                is Set<*> -> {
                    val arr = JSONArray()
                    value.filterIsInstance<String>().sorted().forEach { arr.put(it) }
                    out.put(key, arr)
                }
                else -> out.put(key, value.toString())
            }
        }
        return out
    }

    private fun applyPrefsFromJson(prefs: SharedPreferences, json: JSONObject) {
        val editor = prefs.edit().clear()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val v = json.get(key)) {
                JSONObject.NULL -> Unit
                is Boolean -> editor.putBoolean(key, v)
                is String -> editor.putString(key, v)
                is JSONArray -> {
                    val set = linkedSetOf<String>()
                    for (i in 0 until v.length()) {
                        set.add(v.getString(i))
                    }
                    editor.putStringSet(key, set)
                }
                is Number -> putNumberPref(editor, key, v)
                else -> editor.putString(key, v.toString())
            }
        }
        editor.apply()
    }

    private fun collectExtraFiles(context: Context): List<File> {
        val app = context.applicationContext
        val out = linkedSetOf<File>()
        val filesDir = app.filesDir
        val dpFilesDir = app.createDeviceProtectedStorageContext().filesDir
        val loginJson = LoginService.getLoginInfoFile(app)

        fun addIfFile(f: File?) {
            if (f != null && f.isFile && f.length() > 0L &&
                !f.absolutePath.equals(loginJson.absolutePath, ignoreCase = true)
            ) {
                out.add(f)
            }
        }

        addIfFile(ChargingAnimationPrefs.customMascotFile(app))
        addIfFile(ChargingAnimationPrefs.customBackgroundFile(app))
        addIfFile(ChargingAnimationPrefs.customBackgroundVideoFile(app))
        ChargingAnimationPrefs.getCustomFontPath(app)?.let { addIfFile(File(it)) }

        File(filesDir, "lyrics_fonts").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile }
            ?.forEach { out.add(it) }

        File(filesDir, "jieba").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile }
            ?.forEach { out.add(it) }

        addIfFile(File(filesDir, "car_model.png"))

        val carModelPath = prefsStore(app, CarControlPrefsHelper.PREFS_NAME)
            .getString("car_model_path", null)
            ?.trim()
            .orEmpty()
        if (carModelPath.isNotEmpty()) addIfFile(File(carModelPath))

        File(dpFilesDir, "charging").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile }
            ?.forEach { out.add(it) }

        return out.toList()
    }

    private fun fileRelativePath(context: Context, file: File): String? {
        val roots = listOf(
            context.applicationContext.filesDir,
            context.applicationContext.createDeviceProtectedStorageContext().filesDir,
        )
        for (root in roots) {
            val rootPath = root.absolutePath.trimEnd('/', '\\')
            val abs = file.absolutePath
            if (abs.startsWith(rootPath)) {
                return abs.removePrefix(rootPath).trimStart('/', '\\').replace('\\', '/')
            }
        }
        return null
    }

    private fun resolveRestoreFile(context: Context, relativePath: String): File {
        val rel = relativePath.replace('\\', '/')
        return if (rel.startsWith("charging/")) {
            File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, rel)
        } else {
            File(context.applicationContext.filesDir, rel)
        }
    }

    private fun stageDatabaseForZip(context: Context, dbFile: File): File {
        val staged = File(context.cacheDir, "backup_db_${System.currentTimeMillis()}.db")
        dbFile.copyTo(staged, overwrite = true)
        VehicleHistoryDatabase.prepareDatabaseFileForCopy(staged)
        return staged
    }

    private fun putNumberPref(editor: SharedPreferences.Editor, key: String, v: Number) {
        val d = v.toDouble()
        when {
            d == d.toLong().toDouble() && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE ->
                editor.putInt(key, d.toInt())
            d == v.toFloat().toDouble() -> editor.putFloat(key, v.toFloat())
            else -> editor.putLong(key, v.toLong())
        }
    }

    private fun readManifestFromZip(zipFile: File): JSONObject? {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == ZIP_ENTRY_MANIFEST) {
                    return JSONObject(zis.readBytes().toString(StandardCharsets.UTF_8))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun finalizeBackupPackage(
        app: Context,
        destZip: File,
        createdAt: Long,
        vehicle: VehicleHistoryBackupInfo,
    ) {
        val labelBytes = buildBackupLabelBytes(
            app,
            createdAt = createdAt,
            archiveBytes = destZip.length().coerceAtLeast(0L),
            vehicle = vehicle,
        )
        rewriteZipEntries(destZip) { name, bytes ->
            when (name) {
                ZIP_ENTRY_MANIFEST -> {
                    JSONObject(bytes.toString(StandardCharsets.UTF_8))
                        .put("createdAt", createdAt)
                        .toString(2)
                        .toByteArray(StandardCharsets.UTF_8)
                }
                ZIP_ENTRY_LABEL -> labelBytes
                else -> bytes
            }
        }
        val archiveBytes = destZip.length().coerceAtLeast(0L)
        val labelFinal = buildBackupLabelBytes(app, createdAt, archiveBytes, vehicle)
        rewriteZipEntries(destZip) { name, bytes ->
            when (name) {
                ZIP_ENTRY_MANIFEST -> {
                    JSONObject(bytes.toString(StandardCharsets.UTF_8))
                        .put("createdAt", createdAt)
                        .put("archiveBytes", archiveBytes)
                        .toString(2)
                        .toByteArray(StandardCharsets.UTF_8)
                }
                ZIP_ENTRY_LABEL -> labelFinal
                else -> bytes
            }
        }
    }

    private fun buildBackupLabelBytes(
        app: Context,
        createdAt: Long,
        archiveBytes: Long,
        vehicle: VehicleHistoryBackupInfo,
    ): ByteArray {
        val time = formatExportTime(app, createdAt)
        val size = formatArchiveSize(archiveBytes)
        val version = BuildConfig.VERSION_NAME
        val vehicleLine = if (vehicle.included) {
            app.getString(
                R.string.webdav_backup_label_vehicle_fmt,
                vehicle.vehicleRecords,
                vehicle.operateRecords,
                vehicle.warningRecords,
            )
        } else {
            app.getString(R.string.webdav_backup_package_vehicle_none)
        }
        val text = buildString {
            appendLine(app.getString(R.string.webdav_backup_label_title))
            appendLine()
            appendLine(app.getString(R.string.webdav_backup_label_export_time_fmt, time))
            appendLine(app.getString(R.string.webdav_backup_label_size_fmt, size))
            appendLine(app.getString(R.string.webdav_backup_label_app_version_fmt, version))
            appendLine(vehicleLine)
            appendLine()
            appendLine(app.getString(R.string.webdav_backup_label_restore_warning))
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * 逐条复制 ZIP，允许按条目名替换内容；用于写入最终包大小与 [ZIP_ENTRY_LABEL]。
     */
    private fun rewriteZipEntries(
        sourceZip: File,
        transform: (entryName: String, entryBytes: ByteArray) -> ByteArray,
    ) {
        val temp = File(sourceZip.parentFile, "${sourceZip.name}.rewrite")
        if (temp.exists()) temp.delete()
        var labelWritten = false
        ZipInputStream(BufferedInputStream(FileInputStream(sourceZip))).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val raw = zis.readBytes()
                    val out = transform(name, raw)
                    putZipEntry(zos, name, out)
                    if (name == ZIP_ENTRY_LABEL) labelWritten = true
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                if (!labelWritten) {
                    putZipEntry(zos, ZIP_ENTRY_LABEL, transform(ZIP_ENTRY_LABEL, byteArrayOf()))
                }
            }
        }
        if (!sourceZip.delete() || !temp.renameTo(sourceZip)) {
            temp.copyTo(sourceZip, overwrite = true)
            temp.delete()
        }
    }

    private fun putZipEntry(zos: ZipOutputStream, path: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun unzipToDirectory(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
