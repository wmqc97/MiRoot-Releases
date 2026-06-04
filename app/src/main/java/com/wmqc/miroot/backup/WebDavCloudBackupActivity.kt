package com.wmqc.miroot.backup

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.ActivityWebdavCloudBackupBinding
import com.wmqc.miroot.display.MainDisplayUi
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class WebDavCloudBackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebdavCloudBackupBinding
    private var fieldsLoaded = false

    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        saveWebDavFieldsSilently()
        runBackupTask(getString(R.string.webdav_backup_exporting)) {
            val (zip, packageInfo) = createTempZipWithInfo()
            val ok = contentResolver.openOutputStream(uri)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } != null
            zip.delete()
            if (!ok) throw IllegalStateException("write failed")
            withContext(Dispatchers.Main) {
                showExportDoneDialog(packageInfo)
            }
            formatExportOkMessage(packageInfo)
        }
    }

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        saveWebDavFieldsSilently()
        prepareZipFromUri(uri) { zip ->
            confirmRestoreFromZip(zip) {
                runRestoreTask(
                    getString(R.string.webdav_backup_importing),
                    zip,
                    R.string.webdav_backup_import_ok,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyMiRootSecondarySystemBars()
        binding = ActivityWebdavCloudBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureSafeWindowSize()
        binding.toolbarWebdavBackup.setNavigationOnClickListener { saveAndFinish() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = saveAndFinish()
            },
        )

        if (savedInstanceState == null) {
            loadWebDavFieldsFromPrefs()
        }
        fieldsLoaded = true

        binding.buttonSaveWebdav.setOnClickListener { saveWebDavFields(showToast = true) }
        binding.buttonUploadWebdav.setOnClickListener { uploadToWebDav() }
        binding.buttonDownloadWebdav.setOnClickListener { downloadFromWebDav() }
        binding.buttonExportLocal.setOnClickListener {
            saveWebDavFieldsSilently()
            exportDocumentLauncher.launch(suggestLocalExportFileName())
        }
        binding.buttonImportLocal.setOnClickListener {
            saveWebDavFieldsSilently()
            importDocumentLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    }

    override fun onPause() {
        if (fieldsLoaded) {
            saveWebDavFieldsSilently()
        }
        super.onPause()
    }

    private fun saveAndFinish() {
        saveWebDavFieldsSilently()
        finish()
    }

    private fun ensureSafeWindowSize() {
        val lp = window.attributes ?: return
        var changed = false
        if (lp.width == 0) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (lp.height == 0) {
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (changed) {
            window.attributes = lp
        }
    }

    private fun loadWebDavFieldsFromPrefs() {
        binding.editWebdavServer.setText(WebDavBackupPrefs.serverUrl(this))
        binding.editWebdavUsername.setText(WebDavBackupPrefs.username(this))
        binding.editWebdavPassword.setText(WebDavBackupPrefs.password(this))
    }

    private fun saveWebDavFields(showToast: Boolean) {
        WebDavBackupPrefs.save(
            this,
            binding.editWebdavServer.text?.toString().orEmpty(),
            binding.editWebdavUsername.text?.toString().orEmpty(),
            binding.editWebdavPassword.text?.toString().orEmpty(),
        )
        if (showToast) {
            MainDisplayUi.showToast(this, R.string.webdav_backup_saved, Toast.LENGTH_SHORT)
        }
    }

    private fun saveWebDavFieldsSilently() {
        if (!fieldsLoaded) return
        saveWebDavFields(showToast = false)
    }

    private fun readWebDavConfig(): Triple<String, String, String>? {
        saveWebDavFieldsSilently()
        val url = binding.editWebdavServer.text?.toString()?.trim().orEmpty()
        val user = binding.editWebdavUsername.text?.toString()?.trim().orEmpty()
        val pass = binding.editWebdavPassword.text?.toString().orEmpty()
        if (url.isEmpty()) {
            MainDisplayUi.showToast(this, R.string.webdav_backup_need_server, Toast.LENGTH_LONG)
            return null
        }
        return Triple(url, user, pass)
    }

    private fun uploadToWebDav() {
        val cfg = readWebDavConfig() ?: return
        runBackupTask(getString(R.string.webdav_backup_uploading)) {
            val (zip, packageInfo) = createTempZipWithInfo()
            WebDavClient.uploadFile(cfg.first, cfg.second, cfg.third, zip).getOrThrow()
            zip.delete()
            formatUploadOkMessage(packageInfo)
        }
    }

    private fun downloadFromWebDav() {
        val cfg = readWebDavConfig() ?: return
        setBusy(true, getString(R.string.webdav_backup_downloading))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val zip = File(cacheDir, "miroot_webdav_dl_${System.currentTimeMillis()}.zip")
                    WebDavClient.downloadFile(cfg.first, cfg.second, cfg.third, zip).getOrThrow()
                    zip
                }
            }
            setBusy(false, null)
            result.fold(
                onSuccess = { zip ->
                    confirmRestoreFromZip(zip) {
                        runRestoreTask(
                            getString(R.string.webdav_backup_downloading),
                            zip,
                            R.string.webdav_backup_download_ok,
                        )
                    }
                },
                onFailure = {
                    MainDisplayUi.showToast(
                        this@WebDavCloudBackupActivity,
                        getString(
                            R.string.webdav_backup_failed_fmt,
                            WebDavClient.formatErrorMessage(it),
                        ),
                        Toast.LENGTH_LONG,
                    )
                },
            )
        }
    }

    private fun prepareZipFromUri(uri: Uri, onReady: (File) -> Unit) {
        setBusy(true, getString(R.string.webdav_backup_reading_package))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val temp = File(cacheDir, "miroot_import_${System.currentTimeMillis()}.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("read failed")
                    temp
                }
            }
            setBusy(false, null)
            result.fold(
                onSuccess = { onReady(it) },
                onFailure = {
                    MainDisplayUi.showToast(
                        this@WebDavCloudBackupActivity,
                        getString(
                            R.string.webdav_backup_failed_fmt,
                            WebDavClient.formatErrorMessage(it),
                        ),
                        Toast.LENGTH_LONG,
                    )
                },
            )
        }
    }

    private fun confirmRestoreFromZip(zipFile: File, onConfirm: () -> Unit) {
        saveWebDavFieldsSilently()
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                MiRootConfigBackup.readPackageInfo(zipFile)
                    .map { MiRootConfigBackup.formatPackageInfoForDialog(this@WebDavCloudBackupActivity, it) }
                    .getOrElse { getString(R.string.webdav_backup_read_package_failed) }
            }
            MaterialAlertDialogBuilder(this@WebDavCloudBackupActivity)
                .setTitle(R.string.webdav_backup_restore_confirm_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    zipFile.delete()
                }
                .setPositiveButton(R.string.webdav_backup_restore_confirm_ok) { _, _ -> onConfirm() }
                .setOnCancelListener { zipFile.delete() }
                .show()
        }
    }

    private fun createTempZipWithInfo(): Pair<File, MiRootConfigBackup.BackupPackageInfo> {
        val zip = File(cacheDir, "miroot_backup_${System.currentTimeMillis()}.zip")
        val summary = MiRootConfigBackup.createBackupZip(this, zip).getOrThrow()
        return zip to summary.packageInfo
    }

    private fun suggestLocalExportFileName(): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "miroot_backup_$stamp.zip"
    }

    private fun showExportDoneDialog(info: MiRootConfigBackup.BackupPackageInfo) {
        val summary = MiRootConfigBackup.formatPackageInfoSummary(this, info)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.webdav_backup_export_done_title)
            .setMessage(getString(R.string.webdav_backup_export_done_message_fmt, summary))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatExportOkMessage(info: MiRootConfigBackup.BackupPackageInfo): String {
        val time = MiRootConfigBackup.formatExportTime(this, info.createdAtMillis)
        val size = MiRootConfigBackup.formatArchiveSize(info.archiveBytes)
        return getString(R.string.webdav_backup_export_ok_fmt, time, size)
    }

    private fun formatUploadOkMessage(info: MiRootConfigBackup.BackupPackageInfo): String {
        val time = MiRootConfigBackup.formatExportTime(this, info.createdAtMillis)
        val size = MiRootConfigBackup.formatArchiveSize(info.archiveBytes)
        return getString(R.string.webdav_backup_upload_ok_fmt, time, size)
    }

    private fun runBackupTask(progressText: String, block: suspend () -> String) {
        saveWebDavFieldsSilently()
        setBusy(true, progressText)
        lifecycleScope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) { block() }
            }
            setBusy(false, null)
            message.fold(
                onSuccess = {
                    MainDisplayUi.showToast(this@WebDavCloudBackupActivity, it, Toast.LENGTH_LONG)
                },
                onFailure = {
                    MainDisplayUi.showToast(
                        this@WebDavCloudBackupActivity,
                        getString(
                            R.string.webdav_backup_failed_fmt,
                            WebDavClient.formatErrorMessage(it),
                        ),
                        Toast.LENGTH_LONG,
                    )
                },
            )
        }
    }

    private fun runRestoreTask(
        progressText: String,
        zipFile: File,
        successMessageRes: Int,
    ) {
        saveWebDavFieldsSilently()
        setBusy(true, progressText)
        lifecycleScope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) {
                    MiRootConfigBackup.restoreFromZip(this@WebDavCloudBackupActivity, zipFile).getOrThrow()
                    zipFile.delete()
                    getString(successMessageRes)
                }
            }
            setBusy(false, null)
            message.fold(
                onSuccess = {
                    MainDisplayUi.showToast(this@WebDavCloudBackupActivity, it, Toast.LENGTH_SHORT)
                },
                onFailure = {
                    zipFile.delete()
                    MainDisplayUi.showToast(
                        this@WebDavCloudBackupActivity,
                        getString(
                            R.string.webdav_backup_failed_fmt,
                            WebDavClient.formatErrorMessage(it),
                        ),
                        Toast.LENGTH_LONG,
                    )
                },
            )
        }
    }

    private fun setBusy(busy: Boolean, status: String?) {
        binding.progressWebdav.visibility = if (busy) View.VISIBLE else View.GONE
        binding.textWebdavStatus.visibility = if (status != null) View.VISIBLE else View.GONE
        binding.textWebdavStatus.text = status
        val enabled = !busy
        binding.buttonSaveWebdav.isEnabled = enabled
        binding.buttonUploadWebdav.isEnabled = enabled
        binding.buttonDownloadWebdav.isEnabled = enabled
        binding.buttonExportLocal.isEnabled = enabled
        binding.buttonImportLocal.isEnabled = enabled
        binding.editWebdavServer.isEnabled = enabled
        binding.editWebdavUsername.isEnabled = enabled
        binding.editWebdavPassword.isEnabled = enabled
    }
}
