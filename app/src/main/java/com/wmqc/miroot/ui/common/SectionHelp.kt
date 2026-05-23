package com.wmqc.miroot.ui.common

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.R

fun Fragment.showSectionHelp(@StringRes titleRes: Int, @StringRes messageRes: Int) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(titleRes)
        .setMessage(messageRes)
        .setPositiveButton(R.string.welcome_dialog_confirm, null)
        .show()
}

fun Fragment.showSectionHelp(title: CharSequence, @StringRes messageRes: Int) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(messageRes)
        .setPositiveButton(R.string.welcome_dialog_confirm, null)
        .show()
}
