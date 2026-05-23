package com.wmqc.miroot.ui.main

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.wmqc.miroot.ui.apps.AppsFragment
import com.wmqc.miroot.ui.features.FeaturesFragment
import com.wmqc.miroot.ui.music.MusicFragment
import com.wmqc.miroot.ui.permission.PermissionFragment
import com.wmqc.miroot.ui.theme.ThemeFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 5

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> PermissionFragment()
        1 -> FeaturesFragment()
        2 -> AppsFragment()
        3 -> MusicFragment()
        4 -> ThemeFragment()
        else -> throw IllegalArgumentException("Invalid page $position")
    }
}
