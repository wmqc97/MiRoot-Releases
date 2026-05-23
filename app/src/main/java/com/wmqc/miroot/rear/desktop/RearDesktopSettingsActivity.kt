package com.wmqc.miroot.rear.desktop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.BuildConfig
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.ActivityRearDesktopSettingsBinding
import com.wmqc.miroot.rear.AppProjectionOfficialGesturePolicy
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate
import com.wmqc.miroot.ui.apps.AppsFragment

/**
 * 背屏桌面与应用投屏相关设置：列表模式、黑名单、在背屏打开桌面；第三方投屏时禁用官方背屏手势的全局范围。
 */
class RearDesktopSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRearDesktopSettingsBinding

    private var suppressProjectionRadio = false
    private var insetsBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRearDesktopSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ensureSafeWindowSize()
        val pagePad = resources.getDimensionPixelSize(R.dimen.mi_page_scroll_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.nestedScrollRearDesktopSettings.updatePadding(
                top = bars.top + pagePad,
                bottom = bars.bottom + pagePad,
                left = pagePad,
                right = pagePad,
            )
            windowInsets
        }
        insetsBound = true
        ViewCompat.requestApplyInsets(binding.root)

        binding.radioRearDesktopListMode.setOnCheckedChangeListener { _, _ -> syncModeFromUi() }
        binding.buttonOpenRearDesktop.setOnClickListener {
            RearDesktopLaunchHelper.startDesktopOnRearDisplay(this)
        }
        binding.buttonHoneycombTest.setOnClickListener {
            startActivity(Intent(this, RearDesktopHoneycombTestActivity::class.java))
        }
        binding.buttonHoneycombTest.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.buttonBlacklist.setOnClickListener { showBlacklistDialog() }

        binding.radioGroupProjectionOfficialScope.setOnCheckedChangeListener { _, checkedId ->
            if (suppressProjectionRadio) return@setOnCheckedChangeListener
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val scope =
                if (checkedId == R.id.radio_projection_official_selected) {
                    AppProjectionOfficialGesturePolicy.Scope.SELECTED
                } else {
                    AppProjectionOfficialGesturePolicy.Scope.ALL
                }
            AppProjectionOfficialGesturePolicy.setScope(applicationContext, scope)
            syncProjectionUiFromPrefs()
        }

        syncRadioFromPrefs()
        updateModeVisibility()
        syncProjectionUiFromPrefs()
    }

    override fun onResume() {
        super.onResume()
        syncProjectionUiFromPrefs()
    }

    override fun onDestroy() {
        if (insetsBound) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
            insetsBound = false
        }
        super.onDestroy()
    }

    private fun ensureSafeWindowSize() {
        val lp = window.attributes ?: return
        var changed = false
        if (lp.width == 0) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        // MATCH_PARENT / WRAP_CONTENT are negative; only "0" is invalid here.
        if (lp.height == 0) {
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (changed) {
            window.attributes = lp
        }
    }

    private fun syncRadioFromPrefs() {
        binding.radioRearDesktopListMode.setOnCheckedChangeListener(null)
        when (RearDesktopPrefs.listMode(this)) {
            RearDesktopListMode.MANUAL -> binding.radioModeManual.isChecked = true
            RearDesktopListMode.ALL_BY_FREQUENCY -> binding.radioModeAll.isChecked = true
        }
        binding.radioRearDesktopListMode.setOnCheckedChangeListener { _, _ -> syncModeFromUi() }
    }

    private fun syncModeFromUi() {
        val mode =
            when (binding.radioRearDesktopListMode.checkedRadioButtonId) {
                R.id.radio_mode_all -> RearDesktopListMode.ALL_BY_FREQUENCY
                else -> RearDesktopListMode.MANUAL
            }
        RearDesktopPrefs.setListMode(this, mode)
        RearDesktopPrefs.notifyPrefsChanged(this)
        updateModeVisibility()
    }

    private fun updateModeVisibility() {
        val manual = binding.radioModeManual.isChecked
        binding.textManualHintAppsTab.visibility = if (manual) View.VISIBLE else View.GONE

        binding.textAllModeHint.visibility = if (manual) View.GONE else View.VISIBLE
        binding.buttonBlacklist.visibility = if (manual) View.GONE else View.VISIBLE
    }

    private fun syncProjectionUiFromPrefs() {
        AppProjectionOfficialGesturePolicy.ensureMigrated(this)
        val master = OfficialSubscreenServiceGate.isDisableEnabled(this)
        binding.textProjectionSettingsMasterHint.alpha = if (master) 1f else 0.55f
        binding.radioGroupProjectionOfficialScope.isEnabled = master

        val scope = AppProjectionOfficialGesturePolicy.getScope(this)
        suppressProjectionRadio = true
        when (scope) {
            AppProjectionOfficialGesturePolicy.Scope.SELECTED ->
                binding.radioProjectionOfficialSelected.isChecked = true
            AppProjectionOfficialGesturePolicy.Scope.ALL ->
                binding.radioProjectionOfficialAll.isChecked = true
        }
        suppressProjectionRadio = false

        binding.textProjectionSettingsSelectedHint.visibility =
            if (master && scope == AppProjectionOfficialGesturePolicy.Scope.SELECTED) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showBlacklistDialog() {
        val appContext = applicationContext
        val selfPkg = appContext.packageName
        val rows = AppsFragment.queryLauncherApps(packageManager, selfPkg)
        val bl = RearDesktopPrefs.blacklist(this).toMutableSet()
        val labels = rows.map { it.label }.toTypedArray()
        val checked = rows.map { it.packageName in bl }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rear_desktop_blacklist_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = rows[which].packageName
                if (isChecked) {
                    bl.add(pkg)
                } else {
                    bl.remove(pkg)
                }
            }
            .setPositiveButton(R.string.rear_desktop_save) { _, _ ->
                RearDesktopPrefs.setBlacklist(this, bl)
                RearDesktopPrefs.notifyPrefsChanged(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
