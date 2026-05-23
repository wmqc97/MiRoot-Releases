package com.wmqc.miroot.ui.apps
import com.wmqc.miroot.display.MainDisplayUi

import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.DialogAppProjectionDisplayConfigBinding
import com.wmqc.miroot.rear.AppProjectionDisplayPrefs.AppDisplayConfig
import com.wmqc.miroot.rear.AppProjectionOfficialGesturePolicy

object AppProjectionConfigDialog {
    fun show(
        activity: FragmentActivity,
        row: InstalledAppRow,
        onLaunchApp: (InstalledAppRow) -> Unit,
        onSave: (AppDisplayConfig) -> Unit,
        onClear: () -> Unit,
    ) {
        val binding = DialogAppProjectionDisplayConfigBinding.inflate(LayoutInflater.from(activity))
        val existing = row.projectionConfig
        if (existing != null) {
            binding.editProjectionDpi.setText(existing.dpi?.toString().orEmpty())
            binding.toggleProjectionRotation.check(rotationButtonId(existing.rotation))
        } else {
            binding.toggleProjectionRotation.check(R.id.button_projection_rotation_0)
        }
        binding.layoutProjectionDpi.helperText = row.packageName

        applyOfficialSectionUi(activity, binding, existing)

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.apps_projection_dialog_title, row.label))
                .setView(binding.root)
                .setPositiveButton(R.string.apps_projection_save, null)
                .setNeutralButton(R.string.apps_projection_launch, null)
                .setNegativeButton(
                    if (existing != null) R.string.apps_projection_clear else android.R.string.cancel,
                    null,
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dpiText = binding.editProjectionDpi.text?.toString()?.trim().orEmpty()
                val dpi =
                    if (dpiText.isEmpty()) {
                        null
                    } else {
                        dpiText.toIntOrNull()
                    }
                if (dpi != null && (dpi <= 0 || dpi > 9999)) {
                    MainDisplayUi.showToast(activity, R.string.apps_projection_invalid_dpi, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }
                val rotation = rotationFromButtonId(binding.toggleProjectionRotation.checkedButtonId)
                if (rotation !in 0..3) {
                    MainDisplayUi.showToast(activity, R.string.apps_projection_invalid_rotation, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }

                val scope = AppProjectionOfficialGesturePolicy.getScope(activity)
                val disableOfficial =
                    when (scope) {
                        AppProjectionOfficialGesturePolicy.Scope.ALL ->
                            existing?.disableOfficialSubscreen ?: true
                        AppProjectionOfficialGesturePolicy.Scope.SELECTED ->
                            binding.switchProjectionDisableOfficial.isChecked
                    }

                onSave(
                    AppDisplayConfig(
                        dpi = dpi,
                        rotation = rotation,
                        disableOfficialSubscreen = disableOfficial,
                    ),
                )
                dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                onLaunchApp(row)
            }
            if (existing != null) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    onClear()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun applyOfficialSectionUi(
        activity: FragmentActivity,
        binding: DialogAppProjectionDisplayConfigBinding,
        existing: AppDisplayConfig?,
    ) {
        AppProjectionOfficialGesturePolicy.ensureMigrated(activity)
        val scope = AppProjectionOfficialGesturePolicy.getScope(activity)

        val showPerAppSwitch = scope == AppProjectionOfficialGesturePolicy.Scope.SELECTED
        val sectionVisibility = if (showPerAppSwitch) View.VISIBLE else View.GONE
        binding.textProjectionOfficialSection.visibility = sectionVisibility
        binding.switchProjectionDisableOfficial.visibility = sectionVisibility
        binding.textProjectionOfficialSwitchHint.visibility = sectionVisibility

        if (showPerAppSwitch) {
            binding.switchProjectionDisableOfficial.isChecked =
                existing?.disableOfficialSubscreen ?: true
            binding.textProjectionOfficialSwitchHint.text =
                activity.getString(R.string.apps_projection_dialog_official_hint_scope_selected)
        }
    }

    private fun rotationButtonId(rotation: Int): Int =
        when (rotation) {
            0 -> R.id.button_projection_rotation_0
            1 -> R.id.button_projection_rotation_90
            2 -> R.id.button_projection_rotation_180
            3 -> R.id.button_projection_rotation_270
            else -> R.id.button_projection_rotation_0
        }

    private fun rotationFromButtonId(buttonId: Int): Int =
        when (buttonId) {
            R.id.button_projection_rotation_0 -> 0
            R.id.button_projection_rotation_90 -> 1
            R.id.button_projection_rotation_180 -> 2
            R.id.button_projection_rotation_270 -> 3
            else -> -1
        }
}
