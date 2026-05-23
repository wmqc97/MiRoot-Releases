package com.wmqc.miroot.ui.apps

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.FragmentAppsBinding
import com.wmqc.miroot.rear.desktop.RearDesktopListMode
import com.wmqc.miroot.rear.desktop.RearDesktopPrefs
import com.wmqc.miroot.rear.desktop.RearDesktopSettingsActivity

/**
 * 「应用」标签页：标题栏与设置入口；列表内容见 [InstalledAppsListFragment]。
 */
class AppsFragment : Fragment(R.layout.fragment_apps) {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private val rearPrefsReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action != RearDesktopPrefs.ACTION_REAR_DESKTOP_PREFS_CHANGED) return
                if (!isAdded || _binding == null) return
                updateRearDesktopModeLabel()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAppsBinding.bind(view)

        if (savedInstanceState == null) {
            childFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_installed_apps_host, InstalledAppsListFragment())
                .commit()
        }

        binding.buttonAppsDesktopSettings.setOnClickListener {
            startActivity(Intent(requireContext(), RearDesktopSettingsActivity::class.java))
        }
        updateRearDesktopModeLabel()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RearDesktopPrefs.ACTION_REAR_DESKTOP_PREFS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                rearPrefsReceiver,
                filter,
                android.content.Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(rearPrefsReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        updateRearDesktopModeLabel()
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(rearPrefsReceiver) }
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateRearDesktopModeLabel() {
        if (!isAdded || _binding == null) return
        when (RearDesktopPrefs.listMode(requireContext())) {
            RearDesktopListMode.MANUAL ->
                binding.textRearDesktopModeStatus.text = getString(R.string.apps_rear_desktop_status_manual)
            RearDesktopListMode.ALL_BY_FREQUENCY ->
                binding.textRearDesktopModeStatus.text = getString(R.string.apps_rear_desktop_status_all)
        }
    }

    companion object {
        fun queryLauncherApps(pm: PackageManager, selfPackage: String): List<InstalledAppRow> {
            val launcherIntent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            val seen = mutableSetOf<String>()
            val out = ArrayList<InstalledAppRow>()
            @Suppress("DEPRECATION")
            for (ri in pm.queryIntentActivities(launcherIntent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == selfPackage || !seen.add(pkg)) continue
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    out.add(
                        InstalledAppRow(
                            packageName = pkg,
                            label = label,
                            icon = icon,
                            projectionConfig = null,
                        ),
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    // skip
                }
            }
            out.sortBy { it.label.lowercase() }
            return out
        }
    }
}
