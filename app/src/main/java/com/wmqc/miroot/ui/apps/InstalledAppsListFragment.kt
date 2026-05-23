package com.wmqc.miroot.ui.apps
import com.wmqc.miroot.display.MainDisplayUi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.FragmentInstalledAppsListBinding
import com.wmqc.miroot.rear.AppProjectionDisplayPrefs
import com.wmqc.miroot.rear.RearAppLaunchService
import com.wmqc.miroot.rear.desktop.RearDesktopListMode
import com.wmqc.miroot.rear.desktop.RearDesktopPrefs
import kotlin.concurrent.thread

/** 「应用」标签：搜索 + 列表；自选应用模式下右侧勾选加入背屏自选列表。 */
class InstalledAppsListFragment : Fragment() {

    private var _binding: FragmentInstalledAppsListBinding? = null
    private val binding get() = _binding!!

    private val allApps = mutableListOf<InstalledAppRow>()
    private lateinit var listAdapter: InstalledAppsAdapter
    @Volatile
    private var latestLoadToken: Long = 0L

    private val rearPrefsReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != RearDesktopPrefs.ACTION_REAR_DESKTOP_PREFS_CHANGED) return
                if (!isAdded || _binding == null) return
                // 轻量刷新：模式切换/勾选变更时只重算当前列表，避免每次都重查包管理器造成抖动。
                refreshFromCacheOrReload()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentInstalledAppsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listAdapter =
            InstalledAppsAdapter(
                onClick = { row -> showProjectionConfigDialog(row) },
                onLongClick = { row -> launchAppOnRearDisplay(row.packageName) },
                onRearDesktopToggle = { row -> toggleRearDesktopMembership(row.packageName) },
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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RearDesktopPrefs.ACTION_REAR_DESKTOP_PREFS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                rearPrefsReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(rearPrefsReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(rearPrefsReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 回到页面时做一次全量重载，兜底外部应用安装/卸载变化。
        loadAppsAsync()
    }

    private fun refreshFromCacheOrReload() {
        if (allApps.isEmpty()) {
            loadAppsAsync()
            return
        }
        filterApps(binding.editAppsSearch.text?.toString().orEmpty())
    }

    private fun toggleRearDesktopMembership(packageName: String) {
        val ctx = requireContext().applicationContext
        val cur = RearDesktopPrefs.manualOrder(ctx).toMutableList()
        if (packageName in cur) {
            cur.remove(packageName)
        } else {
            cur.add(packageName)
        }
        RearDesktopPrefs.setManualOrder(ctx, cur)
        filterApps(binding.editAppsSearch.text?.toString().orEmpty())
    }

    private fun loadAppsAsync() {
        val appContext = requireContext().applicationContext
        val selfPkg = appContext.packageName
        val token = System.nanoTime()
        latestLoadToken = token
        thread {
            val manualMode = RearDesktopPrefs.listMode(appContext) == RearDesktopListMode.MANUAL
            val manualIds =
                if (manualMode) {
                    RearDesktopPrefs.manualOrder(appContext).toSet()
                } else {
                    emptySet()
                }
            val loaded =
                AppsFragment.queryLauncherApps(appContext.packageManager, selfPkg).map { row ->
                    row.copy(
                        projectionConfig = AppProjectionDisplayPrefs.getConfig(appContext, row.packageName),
                        rearDesktopPinned = manualMode && row.packageName in manualIds,
                    )
                }
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                if (latestLoadToken != token) return@runOnUiThread
                allApps.clear()
                allApps.addAll(loaded)
                filterApps(binding.editAppsSearch.text?.toString().orEmpty())
            }
        }
    }

    private fun filterApps(query: String) {
        val ctx = requireContext().applicationContext
        val manualMode = RearDesktopPrefs.listMode(ctx) == RearDesktopListMode.MANUAL
        val manualIds =
            if (manualMode) {
                RearDesktopPrefs.manualOrder(ctx).toSet()
            } else {
                emptySet()
            }

        listAdapter.showRearDesktopToggle = manualMode

        val q = query.trim().lowercase()
        val filtered =
            if (q.isEmpty()) {
                allApps.map {
                    if (manualMode) {
                        it.copy(rearDesktopPinned = it.packageName in manualIds)
                    } else {
                        it.copy(rearDesktopPinned = false)
                    }
                }.sortedForDisplay(manualMode)
            } else {
                allApps
                    .filter { row ->
                        row.label.lowercase().contains(q) || row.packageName.lowercase().contains(q)
                    }.map {
                        if (manualMode) {
                            it.copy(rearDesktopPinned = it.packageName in manualIds)
                        } else {
                            it.copy(rearDesktopPinned = false)
                        }
                    }.sortedForDisplay(manualMode)
            }
        listAdapter.submitList(filtered)
        updateSubtitle(filtered.size)
        binding.textAppsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
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

    private fun showProjectionConfigDialog(row: InstalledAppRow) {
        val activity = activity ?: return
        AppProjectionConfigDialog.show(
            activity = activity,
            row = row,
            onLaunchApp = { launchAppOnMainDisplay(it.packageName) },
            onSave = { config ->
                AppProjectionDisplayPrefs.setConfig(requireContext(), row.packageName, config)
                refreshRowConfig(row.packageName)
                MainDisplayUi.showToast(requireContext(), R.string.apps_projection_saved, Toast.LENGTH_SHORT)
            },
            onClear = {
                AppProjectionDisplayPrefs.clearConfig(requireContext(), row.packageName)
                refreshRowConfig(row.packageName)
                MainDisplayUi.showToast(requireContext(), R.string.apps_projection_cleared, Toast.LENGTH_SHORT)
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

    private fun launchAppOnRearDisplay(packageName: String) {
        try {
            val app = requireContext().applicationContext
            val svc =
                Intent(app, RearAppLaunchService::class.java).apply {
                    action = RearAppLaunchService.ACTION_LAUNCH_APP_ON_REAR
                    putExtra(RearAppLaunchService.EXTRA_PACKAGE_NAME, packageName)
                }
            app.startService(svc)
        } catch (e: Exception) {
            MainDisplayUi.showToast(requireContext(), R.string.apps_launch_failed, Toast.LENGTH_SHORT)
        }
    }

    private fun launchAppOnMainDisplay(packageName: String) {
        val launchIntent =
            requireContext().packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (launchIntent == null) {
            MainDisplayUi.showToast(requireContext(), R.string.apps_launch_failed, Toast.LENGTH_SHORT)
            return
        }
        try {
            startActivity(launchIntent)
        } catch (_: Exception) {
            MainDisplayUi.showToast(requireContext(), R.string.apps_launch_failed, Toast.LENGTH_SHORT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private fun List<InstalledAppRow>.sortedForDisplay(manualMode: Boolean): List<InstalledAppRow> =
    if (manualMode) {
        sortedWith(
            compareByDescending<InstalledAppRow> { it.rearDesktopPinned }
                .thenByDescending { it.projectionConfig != null }
                .thenBy { it.label.lowercase() },
        )
    } else {
        sortedWith(
            compareByDescending<InstalledAppRow> { it.projectionConfig != null }
                .thenBy { it.label.lowercase() },
        )
    }
