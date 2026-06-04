package com.wmqc.miroot.car

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.wmqc.miroot.display.MainDisplayUi
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 车辆数据历史二级页，包含四个界面：历史数据、操作记录、警告记录、其他设置。
 * 由车控页右上角菜单「车辆数据历史」进入。
 */
class VehicleHistoryActivity : ComponentActivity() {

    private val reloadNonce = mutableIntStateOf(0)

    private val pickImportHistoryDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val result = VehicleDataHistoryStore.importFromUri(appCtx, uri)
            val existingCounts = if (result.total <= 0) {
                runCatching { VehicleDataHistoryStore.loadHistoryCounts(appCtx) }.getOrNull()
            } else {
                null
            }
            runOnUiThread {
                when {
                    result.total > 0 -> {
                        reloadNonce.intValue++
                        MainDisplayUi.showToast(
                            this@VehicleHistoryActivity,
                            getString(
                                R.string.car_control_vehicle_history_import_ok_fmt,
                                result.vehicleDataImported,
                                result.operateImported,
                                result.warningImported,
                            ),
                            Toast.LENGTH_LONG,
                        )
                        if (!result.vinMatched) {
                            MainDisplayUi.showToast(
                                this@VehicleHistoryActivity,
                                getString(
                                    R.string.car_control_vehicle_history_import_vin_mismatch_fmt,
                                    result.sourceVin ?: "—",
                                    result.localVin ?: "—",
                                ),
                                Toast.LENGTH_LONG,
                            )
                        }
                    }
                    existingCounts != null &&
                        existingCounts.first + existingCounts.second + existingCounts.third > 0 -> {
                        reloadNonce.intValue++
                        MainDisplayUi.showToast(
                            this@VehicleHistoryActivity,
                            getString(
                                R.string.car_control_vehicle_history_import_existing_fmt,
                                existingCounts.first,
                                existingCounts.second,
                                existingCounts.third,
                            ),
                            Toast.LENGTH_LONG,
                        )
                    }
                    else -> {
                        MainDisplayUi.showToast(
                            this@VehicleHistoryActivity,
                            getString(R.string.car_control_vehicle_history_import_failed),
                            Toast.LENGTH_LONG,
                        )
                    }
                }
            }
        }
    }

    private val exportHistoryDb = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-sqlite3"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = VehicleDataHistoryStore.exportToUri(this@VehicleHistoryActivity, uri)
            runOnUiThread {
                MainDisplayUi.showToast(
                    this@VehicleHistoryActivity,
                    if (ok) getString(R.string.car_control_vehicle_history_export_ok)
                    else getString(R.string.car_control_vehicle_history_export_failed),
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CarControlDeviceGate.isAllowed(this)) {
            MainDisplayUi.showToast(this, "当前设备未授权使用车控", Toast.LENGTH_SHORT)
            finish()
            return
        }
        applyMiRootSecondarySystemBars()
        enableEdgeToEdge()
        setContent {
            val reloadKey by reloadNonce
            val dark = isSystemInDarkTheme()
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                VehicleHistoryScreen(
                    reloadKey = reloadKey,
                    onBack = { finish() },
                    onImport = {
                        pickImportHistoryDb.launch(
                            arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"),
                        )
                    },
                    onExport = { exportHistoryDb.launch(VehicleHistoryDatabase.DB_NAME) },
                    onClearConfirmed = {
                        VehicleDataHistoryStore.clearAll(this@VehicleHistoryActivity)
                        reloadNonce.intValue++
                    },
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            VehicleDataHistoryStore.tryRecordCached(applicationContext)
            withContext(Dispatchers.Main) {
                reloadNonce.intValue++
            }
        }
    }
}
