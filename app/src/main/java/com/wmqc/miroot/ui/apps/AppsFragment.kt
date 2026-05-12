package com.wmqc.miroot.ui.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.FragmentAppsBinding
import com.wmqc.miroot.rear.AppProjectionDisplayPrefs
import com.wmqc.miroot.rear.RearAppLaunchService
import kotlin.concurrent.thread

class AppsFragment : Fragment(R.layout.fragment_apps) {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private val allApps = mutableListOf<InstalledAppRow>()
    private lateinit var listAdapter: InstalledAppsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAppsBinding.bind(view)

        listAdapter =
            InstalledAppsAdapter(
                onClick = { row -> showProjectionConfigDialog(row) },
                onLongClick = { row -> launchAppOnRearDisplay(row.packageName) },
            )
        binding.recyclerApps.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerApps.adapter = listAdapter
        binding.recyclerApps.addItemDecoration(
            AppsListItemDecoration(
                spacingPx = resources.getDimensionPixelSize(R.dimen.mi_features_card_spacing),
            ),
        )

        binding.editAppsSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    filterApps(s?.toString().orEmpty())
                }
            },
        )

        loadAppsAsync()
    }

    private fun loadAppsAsync() {
        val appContext = requireContext().applicationContext
        val selfPkg = appContext.packageName
        thread {
            val loaded =
                queryLauncherApps(appContext.packageManager, selfPkg).map { row ->
                    row.copy(
                        projectionConfig = AppProjectionDisplayPrefs.getConfig(appContext, row.packageName),
                    )
                }
            allApps.clear()
            allApps.addAll(loaded)
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                filterApps(binding.editAppsSearch.text?.toString().orEmpty())
            }
        }
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        val filtered =
            if (q.isEmpty()) {
                allApps.sortedForDisplay()
            } else {
                allApps.filter { row ->
                    row.label.lowercase().contains(q) || row.packageName.lowercase().contains(q)
                }.sortedForDisplay()
            }
        listAdapter.submitList(filtered)
        updateSubtitle(filtered.size)
        binding.textAppsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showProjectionConfigDialog(row: InstalledAppRow) {
        val activity = activity ?: return
        AppProjectionConfigDialog.show(
            activity = activity,
            row = row,
            onLaunchApp = { launchAppOnMainDisplay(it.packageName) },
            onSave = { config ->
                AppProjectionDisplayPrefs.setConfig(requireContext(), row.packageName, config)
                refreshRowConfig(row.packageName)
                Toast.makeText(requireContext(), R.string.apps_projection_saved, Toast.LENGTH_SHORT).show()
            },
            onClear = {
                AppProjectionDisplayPrefs.clearConfig(requireContext(), row.packageName)
                refreshRowConfig(row.packageName)
                Toast.makeText(requireContext(), R.string.apps_projection_cleared, Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun refreshRowConfig(packageName: String) {
        val config = AppProjectionDisplayPrefs.getConfig(requireContext(), packageName)
        val index = allApps.indexOfFirst { it.packageName == packageName }
        if (index < 0) return
        allApps[index] = allApps[index].copy(projectionConfig = config)
        filterApps(binding.editAppsSearch.text?.toString().orEmpty())
    }

    private fun updateSubtitle(currentCount: Int) {
        val totalCount = allApps.size
        binding.textAppsSubtitle.text =
            if (totalCount == currentCount) {
                getString(R.string.apps_subtitle_count, totalCount)
            } else {
                getString(R.string.apps_subtitle_filtered, currentCount, totalCount)
            }
    }

    private fun launchAppOnRearDisplay(packageName: String) {
        // 从应用列表启动时：需要跟随列表里的投屏设置（DPI/旋转），并在结束投屏时恢复默认。
        // 统一交给后台 Service：按 Root（优先）/Shizuku 通道执行 am start --display 1，再启动 Keeper 监控恢复。
        try {
            val app = requireContext().applicationContext
            val svc =
                Intent(app, RearAppLaunchService::class.java).apply {
                    action = RearAppLaunchService.ACTION_LAUNCH_APP_ON_REAR
                    putExtra(RearAppLaunchService.EXTRA_PACKAGE_NAME, packageName)
                }
            app.startService(svc)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.apps_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchAppOnMainDisplay(packageName: String) {
        val launchIntent =
            requireContext().packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (launchIntent == null) {
            Toast.makeText(requireContext(), R.string.apps_launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(launchIntent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.apps_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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

private fun List<InstalledAppRow>.sortedForDisplay(): List<InstalledAppRow> =
    sortedWith(
        compareByDescending<InstalledAppRow> { it.projectionConfig != null }
            .thenBy { it.label.lowercase() },
    )
