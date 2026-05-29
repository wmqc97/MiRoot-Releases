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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.ui.music.MusicAutoProjectionPrefs

/** 闁靛棗鑻花鏌ユ偨閵婏絺鍋撳鍡欏灱缂佹稒鎷濈槐浼村箹濠婂懎鍋?+ 闁告帗顨夐妴鍐晬濞戞粌娈伴梺顐㈩槸缁ㄦ煡鎮介妸未浣割嚕韫囧海鐟撻柛娆忓帠閺呭爼宕熸ィ鍐ｅ亾婢跺﹤顫ｉ柛蹇嬪劥閸庢浠﹁箛姘闂侇偄顦崹顏嗘偘閵婏絺鍋?*/
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
                // 閺夌偛顭烽崳娲礆闁垮鐓€闁挎稒纰嶈啯鐎殿喖绻愰崹蹇涘箲?闁告洟绠栭埀顒€顦ぐ澶愬即鐎涙ɑ顦ч柛娆樹邯閸ｅ摜绮诲Δ鈧紞瀣礈瀹ュ懎鐏欓悶娑辩厜缁辨繈鏌嗛崹顔煎赋婵絽绻戦濂告焾娴犲娅㈤柡灞诲劚鐎垫绮婚敍鍕€為柛锝冨姂閳ь剛濮甸崹姘跺箮閺嵮冃楅柕?
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
                onLongClick = { row -> showAppLongPressDialog(row) },
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
        // 闁搞儳鍋涢崺灞俱亜閻㈠憡妗ㄩ柡鍐硾娴犳稒绋夐埀顒€鈻庨垾鍐插伎闂佹彃绻橀崳鍛婃姜閺傘倗绀夐柛蹇旂矊缁ㄨ櫕寰勯弽顓炲姤閹煎瓨姊婚弫銈団偓鐟邦槼椤?闁告鐡曞ù鍥矗濡搫顕ч柕?
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
            val musicBlacklist = MusicAutoProjectionPrefs.blacklist(appContext)
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
                        musicAutoProjectionBlacklisted = row.packageName in musicBlacklist,
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
        val musicBlacklist = MusicAutoProjectionPrefs.blacklist(ctx)

        val q = query.trim().lowercase()
        val filtered =
            if (q.isEmpty()) {
                allApps.map {
                    if (manualMode) {
                        it.copy(rearDesktopPinned = it.packageName in manualIds, musicAutoProjectionBlacklisted = it.packageName in musicBlacklist)
                    } else {
                        it.copy(rearDesktopPinned = false, musicAutoProjectionBlacklisted = it.packageName in musicBlacklist)
                    }
                }.sortedForDisplay(manualMode)
            } else {
                allApps
                    .filter { row ->
                        row.label.lowercase().contains(q) || row.packageName.lowercase().contains(q)
                    }.map {
                        if (manualMode) {
                            it.copy(rearDesktopPinned = it.packageName in manualIds, musicAutoProjectionBlacklisted = it.packageName in musicBlacklist)
                        } else {
                            it.copy(rearDesktopPinned = false, musicAutoProjectionBlacklisted = it.packageName in musicBlacklist)
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


    // -----------------------------------------------------------------
    //  Long-press dialog: back screen launch / music blacklist / desktop blacklist
    // -----------------------------------------------------------------

    private fun showAppLongPressDialog(row: InstalledAppRow) {
        val ctx = requireContext()
        val items = mutableListOf<CharSequence>()

        items.add(getString(R.string.apps_long_press_launch_rear))
        val musicToggleLabel =
            if (MusicAutoProjectionPrefs.isBlacklisted(ctx, row.packageName)) {
                getString(R.string.apps_long_press_music_blacklist_remove)
            } else {
                getString(R.string.apps_long_press_music_blacklist_add)
            }
        items.add(musicToggleLabel)

        val fullBlacklist = MusicAutoProjectionPrefs.blacklist(ctx)
        var manageIndex: Int? = null
        if (fullBlacklist.isNotEmpty()) {
            manageIndex = items.size
            items.add(getString(R.string.apps_long_press_music_blacklist_manage))
        }
        items.add(getString(R.string.apps_long_press_rear_desktop_blacklist))

        MaterialAlertDialogBuilder(ctx)
            .setTitle(row.label)
            .setItems(items.toTypedArray<CharSequence>()) { dialog, which ->
                when (which) {
                    0 -> launchAppOnRearDisplay(row.packageName)
                    1 -> toggleMusicAutoProjectionBlacklist(row.packageName)
                    manageIndex -> showMusicBlacklistManageDialog()
                    else -> addToRearDesktopBlacklist(row.packageName)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleMusicAutoProjectionBlacklist(packageName: String) {
        val ctx = requireContext().applicationContext
        if (MusicAutoProjectionPrefs.isBlacklisted(ctx, packageName)) {
            MusicAutoProjectionPrefs.removeFromBlacklist(ctx, packageName)
            MainDisplayUi.showToast(
                requireContext(),
                getString(R.string.apps_music_blacklist_removed),
                Toast.LENGTH_SHORT,
            )
        } else {
            MusicAutoProjectionPrefs.addToBlacklist(ctx, packageName)
            MainDisplayUi.showToast(
                requireContext(),
                getString(R.string.apps_music_blacklist_added),
                Toast.LENGTH_SHORT,
            )
        }
        
        
        val appIndex = allApps.indexOfFirst { it.packageName == packageName }
        if (appIndex >= 0) {
            val currentBlacklist = MusicAutoProjectionPrefs.blacklist(ctx)
            allApps[appIndex] = allApps[appIndex].copy(
                musicAutoProjectionBlacklisted = packageName in currentBlacklist
            )
        }
        refreshFromCacheOrReload()
    }

    private fun showMusicBlacklistManageDialog() {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        val rows = allApps.filter { MusicAutoProjectionPrefs.isBlacklisted(appCtx, it.packageName) }
        if (rows.isEmpty()) {
            MainDisplayUi.showToast(ctx, R.string.apps_music_blacklist_empty, Toast.LENGTH_SHORT)
            return
        }
        val labels = rows.map { it.label }.toTypedArray()
        val checked = BooleanArray(rows.size) { true }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.apps_music_blacklist_manage_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.apps_music_blacklist_remove_selected) { _, _ ->
                for (i in rows.indices) {
                    if (checked[i]) {
                        MusicAutoProjectionPrefs.removeFromBlacklist(appCtx, rows[i].packageName)
                    }
                }
                refreshFromCacheOrReload()
                MainDisplayUi.showToast(ctx, R.string.apps_music_blacklist_removed, Toast.LENGTH_SHORT)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addToRearDesktopBlacklist(packageName: String) {
        val ctx = requireContext().applicationContext
        val cur = RearDesktopPrefs.blacklist(ctx).toMutableSet()
        if (cur.add(packageName)) {
            RearDesktopPrefs.setBlacklist(ctx, cur)
            RearDesktopPrefs.notifyPrefsChanged(ctx)
            MainDisplayUi.showToast(
                requireContext(),
                getString(R.string.apps_rear_desktop_blacklist_added),
                Toast.LENGTH_SHORT,
            )
        } else {
            MainDisplayUi.showToast(
                requireContext(),
                getString(R.string.apps_rear_desktop_blacklist_already),
                Toast.LENGTH_SHORT,
            )
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