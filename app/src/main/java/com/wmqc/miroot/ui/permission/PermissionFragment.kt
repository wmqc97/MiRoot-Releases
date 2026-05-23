package com.wmqc.miroot.ui.permission
import com.wmqc.miroot.display.MainDisplayUi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.R
import com.wmqc.miroot.AboutCopy
import com.wmqc.miroot.WelcomeIntro
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.capability.PrivilegeChannel
import com.wmqc.miroot.capability.RuntimePermissionGate
import com.wmqc.miroot.ui.common.showSectionHelp
import com.wmqc.miroot.databinding.FragmentPermissionBinding
import com.wmqc.miroot.update.GitHubUpdateChecker
import com.wmqc.miroot.update.GitHubRelease
import com.wmqc.miroot.update.UpdateCheckResult
import com.wmqc.miroot.update.DownloadResult
import com.wmqc.miroot.update.DownloadErrorReason
import com.wmqc.miroot.update.ErrorReason
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PermissionFragment : Fragment(R.layout.fragment_permission) {

    private var _binding: FragmentPermissionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainPermissionViewModel by activityViewModels()

    private var downloadJob: Job? = null
    private var isDownloading = false

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (granted) {
            scheduleBindRuntimeAuthRows()
        } else {
            MainDisplayUi.showToast(requireContext(), R.string.runtime_auth_hint_post_notifications, Toast.LENGTH_LONG)
            openRuntimeSetting { RuntimePermissionGate.intentAppNotificationSettings(it) }
        }
    }

    /** Activity 从设置返回时，非当前 ViewPager 页的 Fragment 不会收到 [onResume]，在此随 Activity 刷新特权与运行时状态。 */
    private val activityResumeRefreshObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            refreshStatusPage()
        }
    }

    /** 从系统设置返回时窗口 regain focus 往往晚于 [onResume]，此时再读省电等状态更可靠。 */
    private val windowFocusRefreshListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (!hasFocus || !isAdded || _binding == null) return@OnWindowFocusChangeListener
        refreshStatusPage()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPermissionBinding.bind(view)

        viewModel.snapshot.observe(viewLifecycleOwner, ::render)
        viewModel.snapshot.value?.let(::render)

        binding.buttonRefresh.setOnClickListener {
            bindOsVersion()
            bindSubscreenVersion()
            refreshStatusPage()
        }

        binding.textPermTitle.setOnClickListener {
            showSectionHelp(binding.textPermTitle.text, R.string.help_perm_status_card)
        }

        binding.textPermVersion.setOnClickListener {
            WelcomeIntro.showReadmeDialog(requireContext())
        }

        binding.textSectionRuntimeAuth.setOnClickListener {
            showSectionHelp(R.string.runtime_auth_section, R.string.help_runtime_auth)
        }
        binding.textSectionSysInfo.setOnClickListener {
            showSectionHelp(R.string.sys_info_section, R.string.help_sys_info)
        }

        binding.rowCoolapk.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/570778"))
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                MainDisplayUi.showToast(requireContext(), R.string.settings_coolapk_open_failed, Toast.LENGTH_SHORT)
            }
        }

        binding.rowSponsorIfdian.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ifdian.net/a/MiRoot"))
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                MainDisplayUi.showToast(requireContext(), R.string.settings_coolapk_open_failed, Toast.LENGTH_SHORT)
            }
        }

        binding.rowAbout.setOnClickListener {
            val ctx = requireContext()
            val content = LayoutInflater.from(ctx).inflate(R.layout.dialog_about, null)
            content.findViewById<TextView>(R.id.about_app_name).text = getString(R.string.app_name)
            content.findViewById<TextView>(R.id.about_version).text =
                getString(R.string.settings_version, readVersionName())
            content.findViewById<TextView>(R.id.about_body).text = AboutCopy.buildBodyText(ctx)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_about_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.buttonCheckUpdate.setOnClickListener {
            if (isDownloading) {
                downloadJob?.cancel()
                downloadJob = null
            } else {
                checkForUpdate()
            }
        }
        bindUpdateVersion()
        checkForUpdateSilently()

        binding.rowStorage.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.hasAllFilesAccess(ctx)) return@setOnClickListener
            openRuntimeSetting { RuntimePermissionGate.intentAllFilesAccess(it) }
        }
        binding.rowOverlay.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.hasOverlay(ctx)) return@setOnClickListener
            val pm = ctx.packageManager
            val intent = RuntimePermissionGate.firstResolvableOverlayIntent(ctx, pm)
            if (intent != null) {
                if (intent.data == null && intent.action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                    MainDisplayUi.showToast(ctx, R.string.runtime_auth_hint_overlay_find_app, Toast.LENGTH_LONG)
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
                }
            } else {
                openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
            }
        }
        binding.rowBattery.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.hasBatteryUnrestricted(ctx)) return@setOnClickListener
            val intent = RuntimePermissionGate.firstResolvableBatteryIntent(ctx, ctx.packageManager)
            if (intent != null) {
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
                }
            } else {
                openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
            }
        }
        binding.rowPostNotifications.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.canPostNotifications(ctx)) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                postNotificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openRuntimeSetting { RuntimePermissionGate.intentAppNotificationSettings(it) }
            }
        }
        binding.rowAppList.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.canQueryAllPackages(ctx)) return@setOnClickListener
            val intent = RuntimePermissionGate.firstResolvableQueryAllPackagesIntent(ctx, ctx.packageManager)
            if (intent != null) {
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
                }
            } else {
                MainDisplayUi.showToast(ctx, R.string.runtime_auth_hint_app_list, Toast.LENGTH_LONG)
                openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
            }
        }
        binding.rowNotification.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.isNotificationListenerEnabled(ctx)) return@setOnClickListener
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.runtime_auth_nls_miui_guide_title)
                .setMessage(R.string.runtime_auth_nls_miui_guide_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.runtime_auth_nls_miui_guide_open_miui) { _, _ ->
                    val intent =
                        RuntimePermissionGate.firstResolvableMiuiAutostartIntent(
                            ctx,
                            ctx.packageManager,
                        )
                    if (intent != null) {
                        openRuntimeSetting { intent }
                    } else {
                        MainDisplayUi.showToast(ctx, R.string.runtime_auth_open_settings_failed, Toast.LENGTH_SHORT)
                        openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
                    }
                }
                .setPositiveButton(R.string.runtime_auth_nls_miui_guide_open_nls) { _, _ ->
                    MainDisplayUi.showToast(ctx, R.string.runtime_auth_hint_notification, Toast.LENGTH_LONG)
                    openRuntimeSetting { RuntimePermissionGate.intentNotificationListenerSettings() }
                }
                .show()
        }

        binding.cardActionForceStop.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_force_stop_title)
                .setMessage(R.string.action_force_stop_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_force_stop_restart_official_subscreen) { _, _ ->
                    restartOfficialSubscreenService()
                }
                .setPositiveButton(R.string.action_force_stop_confirm) { _, _ ->
                    activity?.finishAffinity()
                    Process.killProcess(Process.myPid())
                }
                .show()
        }

        binding.cardActionRoot.setOnClickListener {
            val snap = viewModel.snapshot.value ?: return@setOnClickListener
            if (snap.root) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.root_manage_title)
                .setMessage(R.string.root_manage_desc)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.root_manage_probe) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ok = EnvironmentProbe.probeRoot()
                        viewModel.refresh()
                        MainDisplayUi.showToast(
                            requireContext(),
                            if (ok) getString(R.string.root_manage_result_ok) else getString(R.string.root_manage_result_no),
                            Toast.LENGTH_SHORT,
                        )
                    }
                }
                .show()
        }

        binding.cardActionShizuku.setOnClickListener {
            val snap = viewModel.snapshot.value ?: return@setOnClickListener
            if (snap.shizukuReady) return@setOnClickListener
            val ctx = requireContext()
            when {
                !EnvironmentProbe.shizukuServiceRunning() -> {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.shizuku_manage_title)
                        .setMessage(R.string.shizuku_manage_desc)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.shizuku_manage_open) { _, _ -> openShizukuApp() }
                        .show()
                }
                !EnvironmentProbe.shizukuPermissionGranted() -> {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.shizuku_manage_title)
                        .setMessage(R.string.status_shizuku_need_perm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.shizuku_manage_request) { _, _ ->
                            (activity as? MainActivity)?.requestShizukuPermission()
                        }
                        .show()
                }
            }
        }

        bindOsVersion()
        bindSubscreenVersion()
        bindRuntimeAuthRows()

        requireActivity().lifecycle.addObserver(activityResumeRefreshObserver)
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusRefreshListener)
    }

    override fun onStart() {
        super.onStart()
        refreshStatusPage()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusPage()
    }

    /** 特权（Root/Shizuku）与运行时权限（省电策略等）统一刷新入口。 */
    private fun refreshStatusPage() {
        viewModel.refresh()
        scheduleBindRuntimeAuthRows()
        scheduleDeferredPrivilegeRefresh()
    }

    /**
     * Magisk / KernelSU 弹窗授权或从 Shizuku 返回后，首次 [refresh] 时 su 可能尚未就绪；
     * 延迟再探测一轮，避免状态页长期停在「未授权」。
     */
    private fun scheduleDeferredPrivilegeRefresh() {
        val b = _binding ?: return
        b.root.removeCallbacks(deferredPrivilegeRefreshRunnable)
        b.root.postDelayed(deferredPrivilegeRefreshRunnable, 500L)
        b.root.postDelayed(deferredPrivilegeRefreshRunnable, 1500L)
    }

    private val deferredPrivilegeRefreshRunnable = Runnable {
        if (isAdded && _binding != null) viewModel.refresh()
    }

    /**
     * 从「忽略电池优化 / 省电策略」等系统页返回时，部分 ROM 在同一消息内读取权限 API 仍为旧值；
     * 单帧 [View.post] 仍可能偏早（尤其 HyperOS 应用详情里的省电策略），再延迟多轮直至系统落库。
     */
    private fun scheduleBindRuntimeAuthRows() {
        val b = _binding ?: return
        b.root.removeCallbacks(bindRuntimeAuthRowsRunnable)
        for (delayMs in RUNTIME_AUTH_REFRESH_DELAYS_MS) {
            if (delayMs == 0L) {
                b.root.post(bindRuntimeAuthRowsRunnable)
            } else {
                b.root.postDelayed(bindRuntimeAuthRowsRunnable, delayMs)
            }
        }
    }

    private val bindRuntimeAuthRowsRunnable = Runnable {
        if (_binding != null) bindRuntimeAuthRows()
    }

    private fun openRuntimeSetting(intentFor: (Context) -> Intent) {
        val ctx = requireContext()
        try {
            startActivity(intentFor(ctx))
        } catch (_: Exception) {
            try {
                startActivity(RuntimePermissionGate.intentAppDetails(ctx))
            } catch (_: Exception) {
                MainDisplayUi.showToast(ctx, R.string.runtime_auth_open_settings_failed, Toast.LENGTH_SHORT)
            }
        }
    }

    private fun bindRuntimeAuthRows() {
        val ctx = requireContext()
        val red = ContextCompat.getColor(ctx, R.color.perm_red)
        val needStorage = !RuntimePermissionGate.hasAllFilesAccess(ctx)
        val needOverlay = !RuntimePermissionGate.hasOverlay(ctx)
        val needBattery = !RuntimePermissionGate.hasBatteryUnrestricted(ctx)
        val needPostNotifications = !RuntimePermissionGate.canPostNotifications(ctx)
        val needAppList = !RuntimePermissionGate.canQueryAllPackages(ctx)
        val needNotification = !RuntimePermissionGate.isNotificationListenerEnabled(ctx)

        fun paintDenied(tv: TextView) {
            tv.visibility = View.VISIBLE
            tv.text = getString(R.string.runtime_auth_denied)
            tv.setTextColor(red)
        }

        if (needStorage) {
            binding.rowStorage.visibility = View.VISIBLE
            paintDenied(binding.statusStorage)
        } else {
            binding.rowStorage.visibility = View.GONE
        }
        if (needOverlay) {
            binding.rowOverlay.visibility = View.VISIBLE
            paintDenied(binding.statusOverlay)
        } else {
            binding.rowOverlay.visibility = View.GONE
        }
        if (needBattery) {
            binding.rowBattery.visibility = View.VISIBLE
            paintDenied(binding.statusBattery)
        } else {
            binding.rowBattery.visibility = View.GONE
        }
        if (needPostNotifications) {
            binding.rowPostNotifications.visibility = View.VISIBLE
            paintDenied(binding.statusPostNotifications)
        } else {
            binding.rowPostNotifications.visibility = View.GONE
        }
        if (needAppList) {
            binding.rowAppList.visibility = View.VISIBLE
            paintDenied(binding.statusAppList)
        } else {
            binding.rowAppList.visibility = View.GONE
        }
        if (needNotification) {
            binding.rowNotification.visibility = View.VISIBLE
            paintDenied(binding.statusNotification)
        } else {
            binding.rowNotification.visibility = View.GONE
        }

        binding.dividerAfterStorage.visibility =
            if (needStorage && needOverlay) View.VISIBLE else View.GONE
        binding.dividerAfterOverlay.visibility =
            if (needOverlay && needBattery) View.VISIBLE else View.GONE
        binding.dividerAfterBattery.visibility =
            if (needBattery && needPostNotifications) View.VISIBLE else View.GONE
        binding.dividerAfterPostNotifications.visibility =
            if (needPostNotifications && needAppList) View.VISIBLE else View.GONE
        binding.dividerAfterAppList.visibility =
            if (needAppList && needNotification) View.VISIBLE else View.GONE

        val anyNeeds =
            needStorage || needOverlay || needBattery || needPostNotifications || needAppList || needNotification
        binding.cardRuntimeAuth.visibility = if (anyNeeds) View.VISIBLE else View.GONE
    }

    private fun readVersionName(): String =
        try {
            val pi = requireContext().packageManager.getPackageInfo(
                requireContext().packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            pi.versionName ?: "—"
        } catch (_: Exception) {
            "—"
        }

    private fun bindOsVersion() {
        val incremental = EnvironmentProbe.miOsVersionIncremental()
        binding.valueOsVersion.text = incremental
            ?: getString(
                R.string.sys_os_value_fmt,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
            )
    }

    private fun openShizukuApp() {
        val packages = listOf("moe.shizuku.privileged.api", "rikka.shizuku")
        for (pkg in packages) {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
        }
        MainDisplayUi.showToast(requireContext(), R.string.shizuku_manage_no_app, Toast.LENGTH_SHORT)
    }

    private fun restartOfficialSubscreenService() {
        val snap = viewModel.snapshot.value
        if (snap?.privileged != true) {
            MainDisplayUi.showToast(requireContext(), R.string.privilege_shell_required, Toast.LENGTH_LONG)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                PrivilegedShell.execCmd(
                    "pm enable com.xiaomi.subscreencenter 2>/dev/null; " +
                        "pm enable com.xiaomi.subscreencenter/.SubScreenLauncher 2>/dev/null; " +
                        "am force-stop com.xiaomi.subscreencenter; " +
                        "am start --display 1 -n com.xiaomi.subscreencenter/.subscreenlauncher.SubScreenLauncherActivity " +
                        "|| am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher",
                )
            }
            if (!isAdded || _binding == null) return@launch
            MainDisplayUi.showToast(
                requireContext(),
                if (ok) R.string.action_force_stop_restart_official_subscreen_ok else R.string.action_force_stop_restart_official_subscreen_fail,
                if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            )
        }
    }

    private fun bindSubscreenVersion() {
        binding.valueAppVersion.text = readSubscreenVersion()
    }

    /** 背屏（com.xiaomi.subscreencenter）版本号。 */
    private fun readSubscreenVersion(): String {
        val ctx = requireContext()
        return try {
            val pi = ctx.packageManager.getPackageInfo(
                SUBSCREEN_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
            pi.versionName?.takeIf { it.isNotEmpty() } ?: "—"
        } catch (_: PackageManager.NameNotFoundException) {
            getString(R.string.subscreen_pkg_not_installed)
        } catch (_: Exception) {
            "—"
        }
    }

    /** MiRoot 自身版本，用于状态卡片。 */
    private fun readMiRootVersion(): String {
        return try {
            val ctx = requireContext()
            val pi = ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            pi.versionName ?: "—"
        } catch (_: Exception) {
            "—"
        }
    }

    private fun bindUpdateVersion() {
        binding.textUpdateVersion.text = getString(R.string.update_current_version, readMiRootVersion())
    }

    private fun checkForUpdate() {
        // 手动检测节流：进程内 30 秒内不重复请求
        if (GitHubUpdateChecker.isManualCheckThrottled()) {
            MainDisplayUi.showToast(requireContext(), R.string.update_too_frequent, Toast.LENGTH_SHORT)
            return
        }

        val btn = binding.buttonCheckUpdate
        btn.isEnabled = false
        btn.text = getString(R.string.update_checking)
        binding.textUpdateStatus.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GitHubUpdateChecker.fetchLatestRelease()
            }
            if (!isAdded || _binding == null) return@launch

            btn.isEnabled = true
            btn.text = getString(R.string.update_check_button)

            when (result) {
                is UpdateCheckResult.Success -> {
                    val currentVer = readMiRootVersion()
                    if (!GitHubUpdateChecker.hasUpdate(currentVer, result.release.versionName)) {
                        MainDisplayUi.showToast(requireContext(), R.string.update_no_update, Toast.LENGTH_SHORT)
                        return@launch
                    }
                    showUpdateDialog(result.release)
                }
                is UpdateCheckResult.Error -> {
                    val msg = when (result.reason) {
                        ErrorReason.RATE_LIMITED -> R.string.update_error_rate_limited
                        ErrorReason.NOT_FOUND -> R.string.update_error_not_found
                        ErrorReason.NO_RELEASE -> R.string.update_error_no_release
                        ErrorReason.NO_APK_ASSET -> R.string.update_error_no_apk
                        ErrorReason.PARSE_ERROR -> R.string.update_error_parse
                        ErrorReason.NETWORK_ERROR, ErrorReason.UNKNOWN -> R.string.update_check_failed
                    }
                    MainDisplayUi.showToast(requireContext(), msg, Toast.LENGTH_SHORT)
                }
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        val ctx = requireContext()
        val currentVer = readMiRootVersion()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.dialog_update_current_version).text = currentVer
        view.findViewById<TextView>(R.id.dialog_update_latest_version).text = release.versionName
        view.findViewById<TextView>(R.id.dialog_update_release_notes).text = release.body

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.update_available_title, release.versionName))
            .setView(view)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_download) { _, _ ->
                downloadAndInstall(release)
            }
            .show()
    }

    private fun downloadAndInstall(release: GitHubRelease) {
        // Skip download if already cached
        val cached = GitHubUpdateChecker.getCachedApk(requireContext(), release.versionName)
        if (cached != null) {
            GitHubUpdateChecker.installApk(requireContext(), cached)
            return
        }

        isDownloading = true
        binding.progressUpdate.visibility = View.VISIBLE
        binding.progressUpdate.progress = 0
        binding.textUpdateStatus.apply {
            visibility = View.VISIBLE
            text = getString(R.string.update_downloading, 0)
        }
        binding.buttonCheckUpdate.apply {
            isEnabled = true
            text = getString(R.string.update_cancel)
        }

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GitHubUpdateChecker.downloadApk(
                    context = requireContext(),
                    url = release.downloadUrl,
                    versionName = release.versionName,
                    onProgress = { bytesRead, totalBytes ->
                        val percent = ((bytesRead * 100) / totalBytes).toInt()
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (!isAdded || _binding == null) return@launch
                            binding.progressUpdate.progress = percent
                            binding.textUpdateStatus.text = getString(R.string.update_downloading, percent)
                        }
                    },
                )
            }
            if (!isAdded || _binding == null) return@launch
            handleDownloadResult(result, release)
        }
    }

    private fun handleDownloadResult(result: DownloadResult, release: GitHubRelease) {
        when (result) {
            is DownloadResult.Success -> {
                binding.progressUpdate.progress = 100
                binding.textUpdateStatus.text = getString(R.string.update_install_hint)
                binding.buttonCheckUpdate.text = getString(R.string.update_install_hint)
                val installed = GitHubUpdateChecker.installApk(requireContext(), result.file)
                if (!installed) {
                    MainDisplayUi.showToast(requireContext(), R.string.update_download_failed, Toast.LENGTH_SHORT)
                }
                resetUpdateUi()
            }
            is DownloadResult.Cancelled -> {
                MainDisplayUi.showToast(requireContext(), R.string.update_cancelled, Toast.LENGTH_SHORT)
                resetUpdateUi()
            }
            is DownloadResult.Error -> {
                val msg = when (result.reason) {
                    DownloadErrorReason.HTTP_ERROR,
                    DownloadErrorReason.NETWORK_ERROR -> R.string.update_error_network
                    DownloadErrorReason.NO_CONTENT,
                    DownloadErrorReason.FILE_WRITE_ERROR,
                    DownloadErrorReason.UNKNOWN -> R.string.update_download_failed
                    DownloadErrorReason.DISK_FULL -> R.string.update_error_disk_full
                }
                MainDisplayUi.showToast(requireContext(), msg, Toast.LENGTH_SHORT)
                resetUpdateUi()
            }
        }
    }

    private fun resetUpdateUi() {
        binding.progressUpdate.visibility = View.GONE
        binding.progressUpdate.progress = 0
        binding.textUpdateStatus.visibility = View.GONE
        binding.buttonCheckUpdate.apply {
            isEnabled = true
            text = getString(R.string.update_check_button)
        }
        isDownloading = false
        downloadJob = null
    }

    private fun checkForUpdateSilently() {
        if (autoCheckPerformed) return
        autoCheckPerformed = true

        // 持久化冷却：每小时最多静默检测一次
        val prefs = requireContext().getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSilent = prefs.getLong(PREFS_LAST_SILENT_CHECK, 0L)
        if (now - lastSilent < SILENT_COOLDOWN_MS) return
        prefs.edit().putLong(PREFS_LAST_SILENT_CHECK, now).apply()

        // Clean leftover .tmp files from a previous process death
        try {
            requireContext().cacheDir.listFiles { f -> f.name.endsWith(".apk.tmp") }
                ?.forEach { it.delete() }
        } catch (_: Exception) { }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GitHubUpdateChecker.fetchLatestRelease()
            }
            if (!isAdded || _binding == null) return@launch

            if (result is UpdateCheckResult.Success) {
                val currentVer = readMiRootVersion()
                if (GitHubUpdateChecker.hasUpdate(currentVer, result.release.versionName)) {
                    binding.textUpdateStatus.apply {
                        text = getString(R.string.update_available, result.release.versionName)
                        visibility = View.VISIBLE
                    }
                    binding.badgeUpdateDot.visibility = View.VISIBLE
                }
            }
            // Errors during silent check are intentionally ignored
        }
    }

    private fun render(snap: PermissionSnapshot) {
        val ctx = requireContext()
        val green = ContextCompat.getColor(ctx, R.color.perm_green)
        val red = ContextCompat.getColor(ctx, R.color.perm_red)
        val muted = ContextCompat.getColor(ctx, R.color.mi_text_secondary)
        val miRootVer = readMiRootVersion()
        binding.valueAppVersion.text = readSubscreenVersion()

        if (snap.privileged) {
            binding.cardPermissionStatus.setCardBackgroundColor(
                ContextCompat.getColor(ctx, R.color.perm_green_container),
            )
            binding.imagePermState.setImageResource(R.drawable.ic_perm_status_tick_large)
            binding.textPermTitle.text = if (snap.channel == PrivilegeChannel.ROOT) {
                getString(R.string.perm_title_work_root)
            } else {
                getString(R.string.perm_title_work_shizuku)
            }
            binding.textPermTitle.setTextColor(ContextCompat.getColor(ctx, R.color.mi_text_primary))
            binding.textPermVersion.text = getString(R.string.perm_version_fmt, miRootVer)
            binding.textPermVersion.setTextColor(muted)
        } else {
            binding.cardPermissionStatus.setCardBackgroundColor(
                ContextCompat.getColor(ctx, R.color.perm_red_container),
            )
            binding.imagePermState.setImageResource(R.drawable.ic_perm_status_cross_large)
            binding.textPermTitle.text = getString(R.string.perm_title_channels)
            binding.textPermTitle.setTextColor(red)
            binding.textPermVersion.text = getString(R.string.perm_version_fmt, miRootVer)
            binding.textPermVersion.setTextColor(muted)
        }

        binding.textActionRoot.setTextColor(if (snap.root) green else red)

        val shizukuColor = when {
            snap.shizukuReady -> green
            snap.shizukuRunning -> red
            else -> muted
        }
        binding.textActionShizuku.setTextColor(shizukuColor)

        // 任一通道已就绪则隐藏两个入口；均未授权时才显示 Root / Shizuku 按钮
        val hidePrivilegeButtons = snap.root || snap.shizukuReady
        val btnVis = if (hidePrivilegeButtons) View.GONE else View.VISIBLE
        binding.cardActionRoot.visibility = btnVis
        binding.cardActionShizuku.visibility = btnVis

        bindRuntimeAuthRows()
    }

    override fun onDestroyView() {
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        _binding?.root?.removeCallbacks(bindRuntimeAuthRowsRunnable)
        _binding?.root?.removeCallbacks(deferredPrivilegeRefreshRunnable)
        _binding?.root?.viewTreeObserver?.removeOnWindowFocusChangeListener(windowFocusRefreshListener)
        activity?.lifecycle?.removeObserver(activityResumeRefreshObserver)
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val SUBSCREEN_PACKAGE = "com.xiaomi.subscreencenter"
        private var autoCheckPerformed = false

        /** 静默检测冷却时间：1 小时。 */
        private const val SILENT_COOLDOWN_MS = 3600_000L
        private const val PREFS_UPDATE = "miroot_update_prefs"
        private const val PREFS_LAST_SILENT_CHECK = "last_silent_check_time"

        /** 从系统设置返回后分批重读运行时权限（含 HyperOS 省电策略落库延迟）。 */
        private val RUNTIME_AUTH_REFRESH_DELAYS_MS = longArrayOf(0L, 120L, 350L, 900L, 1500L, 2500L)
    }
}
