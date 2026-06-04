package com.wmqc.miroot.ui.features
import com.wmqc.miroot.display.MainDisplayUi

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wmqc.miroot.R
import com.wmqc.miroot.car.CarControlDeviceGate
import com.wmqc.miroot.databinding.ActivityRearGestureConfigBinding
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import com.wmqc.miroot.rear.RearGestureAction
import com.wmqc.miroot.rear.RearGestureInjectSpec
import com.wmqc.miroot.rear.RearGesturePrefs
import com.wmqc.miroot.ui.apps.AppsFragment
import com.wmqc.miroot.ui.apps.LauncherAppPicker

class RearGestureConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRearGestureConfigBinding

    private var selUp: RearGestureAction = RearGestureAction.NONE
    private var selLeft: RearGestureAction = RearGestureAction.NONE
    private var selRight: RearGestureAction = RearGestureAction.NONE
    private var pkgUp: String = ""
    private var pkgLeft: String = ""
    private var pkgRight: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyMiRootSecondarySystemBars()
        binding = ActivityRearGestureConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureSafeWindowSize()
        binding.toolbarRearGesture.setNavigationOnClickListener { finish() }

        loadFromPrefs()
        setupDropdown(binding.dropdownGestureUp, binding.layoutGestureUp, 1) { selUp = it }
        setupDropdown(binding.dropdownGestureLeft, binding.layoutGestureLeft, 2) { selLeft = it }
        setupDropdown(binding.dropdownGestureRight, binding.layoutGestureRight, 3) { selRight = it }

        binding.buttonPickAppUp.setOnClickListener { pickApp(1) }
        binding.buttonPickAppLeft.setOnClickListener { pickApp(2) }
        binding.buttonPickAppRight.setOnClickListener { pickApp(3) }

        binding.buttonSaveRearGesture.setOnClickListener { saveAll() }
        refreshAppRows()
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

    private fun loadFromPrefs() {
        val s = RearGesturePrefs.readInjectSpec(this)
        selUp = s.slot1Up
        selLeft = s.slot2Left
        selRight = s.slot3Right
        pkgUp = s.slot1LaunchPackage
        pkgLeft = s.slot2LaunchPackage
        pkgRight = s.slot3LaunchPackage
    }

    private fun actionLabel(a: RearGestureAction): String =
        when (a) {
            RearGestureAction.NONE -> getString(R.string.rear_gesture_action_none)
            RearGestureAction.REAR_DESKTOP -> getString(R.string.rear_gesture_action_desktop)
            RearGestureAction.MUSIC_LYRICS -> getString(R.string.rear_gesture_action_music)
            RearGestureAction.CAR_CONTROL -> getString(R.string.rear_gesture_action_car)
            RearGestureAction.LAUNCH_APP -> getString(R.string.rear_gesture_action_app)
            RearGestureAction.FOREGROUND_APP_TO_REAR -> getString(R.string.rear_gesture_action_foreground_to_rear)
            RearGestureAction.CHARGING_PREVIEW -> getString(R.string.rear_gesture_action_charging)
        }

    private fun actionChoices(): List<Pair<RearGestureAction, String>> {
        val carOk = CarControlDeviceGate.isAllowed(applicationContext)
        return buildList {
            add(RearGestureAction.NONE to getString(R.string.rear_gesture_action_none))
            add(RearGestureAction.REAR_DESKTOP to getString(R.string.rear_gesture_action_desktop))
            add(RearGestureAction.MUSIC_LYRICS to getString(R.string.rear_gesture_action_music))
            if (carOk) {
                add(RearGestureAction.CAR_CONTROL to getString(R.string.rear_gesture_action_car))
            }
            add(RearGestureAction.LAUNCH_APP to getString(R.string.rear_gesture_action_app))
            add(RearGestureAction.FOREGROUND_APP_TO_REAR to getString(R.string.rear_gesture_action_foreground_to_rear))
            add(RearGestureAction.CHARGING_PREVIEW to getString(R.string.rear_gesture_action_charging))
        }
    }

    private fun actionChoicesForSlot(slot: Int): List<Pair<RearGestureAction, String>> {
        val base = actionChoices()
        val current =
            when (slot) {
                1 -> selUp
                2 -> selLeft
                else -> selRight
            }
        val takenElsewhere = mutableSetOf<RearGestureAction>()
        if (slot != 1 && selUp.isExclusive) takenElsewhere.add(selUp)
        if (slot != 2 && selLeft.isExclusive) takenElsewhere.add(selLeft)
        if (slot != 3 && selRight.isExclusive) takenElsewhere.add(selRight)
        return base.filter { (a, _) ->
            when {
                a == RearGestureAction.NONE -> true
                a == RearGestureAction.LAUNCH_APP -> true
                a.isExclusive -> a !in takenElsewhere || a == current
                else -> true
            }
        }
    }

    private fun setupDropdown(
        dd: AutoCompleteTextView,
        @Suppress("UNUSED_PARAMETER") layout: com.google.android.material.textfield.TextInputLayout,
        slot: Int,
        onPick: (RearGestureAction) -> Unit,
    ) {
        dd.threshold = 0
        val choices = actionChoicesForSlot(slot)
        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                choices.map { it.second },
            )
        dd.setAdapter(adapter)
        dd.setOnItemClickListener { _, _, position, _ ->
            val picked = actionChoicesForSlot(slot)[position]
            onPick(picked.first)
            binding.dropdownGestureUp.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    actionChoicesForSlot(1).map { it.second },
                ),
            )
            binding.dropdownGestureLeft.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    actionChoicesForSlot(2).map { it.second },
                ),
            )
            binding.dropdownGestureRight.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    actionChoicesForSlot(3).map { it.second },
                ),
            )
            refreshAppRows()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.dropdownGestureUp.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                actionChoicesForSlot(1).map { it.second },
            ),
        )
        binding.dropdownGestureLeft.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                actionChoicesForSlot(2).map { it.second },
            ),
        )
        binding.dropdownGestureRight.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                actionChoicesForSlot(3).map { it.second },
            ),
        )
        binding.dropdownGestureUp.setText(actionLabel(selUp), false)
        binding.dropdownGestureLeft.setText(actionLabel(selLeft), false)
        binding.dropdownGestureRight.setText(actionLabel(selRight), false)
        refreshAppRows()
    }

    private fun refreshAppRows() {
        binding.rowPickAppUp.visibility =
            if (selUp == RearGestureAction.LAUNCH_APP) View.VISIBLE else View.GONE
        binding.rowPickAppLeft.visibility =
            if (selLeft == RearGestureAction.LAUNCH_APP) View.VISIBLE else View.GONE
        binding.rowPickAppRight.visibility =
            if (selRight == RearGestureAction.LAUNCH_APP) View.VISIBLE else View.GONE
        bindLaunchAppRow(pkgUp, binding.imageAppPickUp, binding.textAppLabelUp, binding.textAppPackageUp)
        bindLaunchAppRow(pkgLeft, binding.imageAppPickLeft, binding.textAppLabelLeft, binding.textAppPackageLeft)
        bindLaunchAppRow(pkgRight, binding.imageAppPickRight, binding.textAppLabelRight, binding.textAppPackageRight)
    }

    private fun bindLaunchAppRow(
        pkg: String,
        iconView: ImageView,
        labelView: TextView,
        packageView: TextView,
    ) {
        val p = pkg.trim()
        if (p.isEmpty()) {
            iconView.setImageDrawable(null)
            iconView.visibility = View.GONE
            iconView.contentDescription = null
            labelView.text = getString(R.string.rear_gesture_pick_app)
            packageView.text = ""
            packageView.visibility = View.GONE
            return
        }
        val ok =
            runCatching {
                val pm = packageManager
                val ai = pm.getApplicationInfo(p, 0)
                val label = pm.getApplicationLabel(ai).toString()
                iconView.setImageDrawable(pm.getApplicationIcon(ai))
                iconView.visibility = View.VISIBLE
                iconView.contentDescription = label
                labelView.text = label
                packageView.text = p
                packageView.visibility = View.VISIBLE
            }
        if (ok.isFailure) {
            iconView.setImageDrawable(null)
            iconView.visibility = View.VISIBLE
            iconView.contentDescription = null
            labelView.text = getString(R.string.rear_gesture_app_unknown)
            packageView.text = p
            packageView.visibility = View.VISIBLE
        }
    }

    private fun pickApp(slot: Int) {
        val rows = AppsFragment.queryLauncherApps(packageManager, packageName)
        if (rows.isEmpty()) {
            MainDisplayUi.showToast(this, R.string.apps_empty, Toast.LENGTH_SHORT)
            return
        }
        LauncherAppPicker.show(this, rows) { row ->
                val pkg = row.packageName
                when (slot) {
                    1 -> pkgUp = pkg
                    2 -> pkgLeft = pkg
                    else -> pkgRight = pkg
                }
                refreshAppRows()
            }
    }

    private fun hadDuplicateExclusiveStripped(raw: RearGestureInjectSpec): Boolean {
        for (ex in RearGestureAction.entries.filter { it.isExclusive }) {
            val c =
                listOf(raw.slot1Up, raw.slot2Left, raw.slot3Right).count { it == ex }
            if (c > 1) return true
        }
        return false
    }

    private fun hadLaunchWithoutPackageCleared(raw: RearGestureInjectSpec, n: RearGestureInjectSpec): Boolean {
        fun cleared(ru: RearGestureAction, pkg: String, nu: RearGestureAction) =
            ru == RearGestureAction.LAUNCH_APP && pkg.isBlank() && nu == RearGestureAction.NONE
        return cleared(raw.slot1Up, raw.slot1LaunchPackage, n.slot1Up) ||
            cleared(raw.slot2Left, raw.slot2LaunchPackage, n.slot2Left) ||
            cleared(raw.slot3Right, raw.slot3LaunchPackage, n.slot3Right)
    }

    /** 保存结果：仅「已保存」；若有规范化则追加对应一句说明。 */
    private fun buildSaveToastText(raw: RearGestureInjectSpec, n: RearGestureInjectSpec): Pair<String, Int> {
        val saved = getString(R.string.rear_gesture_saved)
        if (raw == n) {
            return saved to Toast.LENGTH_SHORT
        }
        val notes = ArrayList<String>(2)
        if (hadDuplicateExclusiveStripped(raw)) {
            notes.add(getString(R.string.rear_gesture_save_note_mutual))
        }
        if (hadLaunchWithoutPackageCleared(raw, n)) {
            notes.add(getString(R.string.rear_gesture_save_note_launch))
        }
        if (notes.isEmpty()) {
            return saved to Toast.LENGTH_SHORT
        }
        val body =
            buildString {
                append(saved)
                for (line in notes) {
                    append('\n')
                    append(line)
                }
            }
        return body to Toast.LENGTH_LONG
    }

    private fun saveAll() {
        val raw =
            RearGestureInjectSpec(
                slot1Up = selUp,
                slot1LaunchPackage = pkgUp,
                slot2Left = selLeft,
                slot2LaunchPackage = pkgLeft,
                slot3Right = selRight,
                slot3LaunchPackage = pkgRight,
            )
        val normalized = RearGesturePrefs.normalizeExclusive(raw)
        RearGesturePrefs.writeInjectSpec(this, normalized)
        selUp = normalized.slot1Up
        selLeft = normalized.slot2Left
        selRight = normalized.slot3Right
        pkgUp = normalized.slot1LaunchPackage
        pkgLeft = normalized.slot2LaunchPackage
        pkgRight = normalized.slot3LaunchPackage
        binding.dropdownGestureUp.setText(actionLabel(selUp), false)
        binding.dropdownGestureLeft.setText(actionLabel(selLeft), false)
        binding.dropdownGestureRight.setText(actionLabel(selRight), false)
        refreshAppRows()
        val (text, len) = buildSaveToastText(raw, normalized)
        MainDisplayUi.showToast(this, text, len)
    }
}
