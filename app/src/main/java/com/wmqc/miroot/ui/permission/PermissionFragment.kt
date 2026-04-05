package com.wmqc.miroot.ui.permission

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
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.R
import com.wmqc.miroot.WelcomeIntro
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.capability.PrivilegeChannel
import com.wmqc.miroot.capability.RuntimePermissionGate
import com.wmqc.miroot.ui.common.showSectionHelp
import com.wmqc.miroot.databinding.FragmentPermissionBinding
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import kotlinx.coroutines.launch

class PermissionFragment : Fragment(R.layout.fragment_permission) {

    private var _binding: FragmentPermissionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainPermissionViewModel by activityViewModels()

    /** Activity 从设置返回时，非当前 ViewPager 页的 Fragment 不会收到 [onResume]，在此随 Activity 刷新省电等运行时状态。 */
    private val activityResumeRefreshObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (_binding != null) bindRuntimeAuthRows()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPermissionBinding.bind(view)

        viewModel.snapshot.observe(viewLifecycleOwner, ::render)
        viewModel.snapshot.value?.let(::render)

        binding.buttonRefresh.setOnClickListener {
            viewModel.refresh()
            bindOsVersion()
            bindSubscreenVersion()
            bindRuntimeAuthRows()
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
                Toast.makeText(requireContext(), R.string.settings_coolapk_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.rowAbout.setOnClickListener {
            val ctx = requireContext()
            val content = LayoutInflater.from(ctx).inflate(R.layout.dialog_about, null)
            content.findViewById<TextView>(R.id.about_app_name).text = getString(R.string.app_name)
            content.findViewById<TextView>(R.id.about_version).text =
                getString(R.string.settings_version, readVersionName())
            content.findViewById<TextView>(R.id.about_body).text = buildAboutBodyText()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_about_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

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
                    Toast.makeText(ctx, R.string.runtime_auth_hint_overlay_find_app, Toast.LENGTH_LONG).show()
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
            if (RuntimePermissionGate.isIgnoringBatteryOptimizations(ctx)) return@setOnClickListener
            try {
                startActivity(RuntimePermissionGate.intentIgnoreBatteryOptimizations(ctx))
            } catch (_: Exception) {
                try {
                    startActivity(RuntimePermissionGate.intentBatteryOptimizationList())
                } catch (_: Exception) {
                    openRuntimeSetting { RuntimePermissionGate.intentAppDetails(it) }
                }
            }
        }
        binding.rowNotification.setOnClickListener {
            val ctx = requireContext()
            if (RuntimePermissionGate.isNotificationListenerEnabled(ctx)) return@setOnClickListener
            Toast.makeText(ctx, R.string.runtime_auth_hint_notification, Toast.LENGTH_LONG).show()
            openRuntimeSetting { RuntimePermissionGate.intentNotificationListenerSettings() }
        }

        binding.cardActionForceStop.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_force_stop_title)
                .setMessage(R.string.action_force_stop_message)
                .setNegativeButton(android.R.string.cancel, null)
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
                        Toast.makeText(
                            requireContext(),
                            if (ok) getString(R.string.root_manage_result_ok) else getString(R.string.root_manage_result_no),
                            Toast.LENGTH_SHORT,
                        ).show()
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
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            bindRuntimeAuthRows()
        }
    }

    private fun openRuntimeSetting(intentFor: (Context) -> Intent) {
        val ctx = requireContext()
        try {
            startActivity(intentFor(ctx))
        } catch (_: Exception) {
            try {
                startActivity(RuntimePermissionGate.intentAppDetails(ctx))
            } catch (_: Exception) {
                Toast.makeText(ctx, R.string.runtime_auth_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindRuntimeAuthRows() {
        val ctx = requireContext()
        val red = ContextCompat.getColor(ctx, R.color.perm_red)
        val needStorage = !RuntimePermissionGate.hasAllFilesAccess(ctx)
        val needOverlay = !RuntimePermissionGate.hasOverlay(ctx)
        val needBattery = !RuntimePermissionGate.isIgnoringBatteryOptimizations(ctx)
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
            if (needBattery && needNotification) View.VISIBLE else View.GONE

        val anyNeeds = needStorage || needOverlay || needBattery || needNotification
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

    private fun buildAboutBodyText(): String =
        buildString {
            append(getString(R.string.settings_about_section_intro))
            append("\n")
            append(getString(R.string.settings_about_app_desc))
            append("\n\n")
            append(getString(R.string.settings_about_section_thanks))
            append("\n")
            append(getString(R.string.settings_about_thanks))
            append("\n\n")
            append(getString(R.string.settings_about_section_software))
            append("\n")
            append(getString(R.string.settings_about_software))
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
        Toast.makeText(requireContext(), R.string.shizuku_manage_no_app, Toast.LENGTH_SHORT).show()
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
    }

    override fun onDestroyView() {
        activity?.lifecycle?.removeObserver(activityResumeRefreshObserver)
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val SUBSCREEN_PACKAGE = "com.xiaomi.subscreencenter"
    }
}
