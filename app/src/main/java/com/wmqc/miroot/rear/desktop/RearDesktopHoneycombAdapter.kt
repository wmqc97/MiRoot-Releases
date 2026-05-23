package com.wmqc.miroot.rear.desktop

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wmqc.miroot.databinding.ItemInstalledAppHoneycombBinding

/** 背屏「全部应用」蜂窝 RecyclerView 条目（仅图标；点击由 [com.wmqc.miroot.ui.apps.HoneycombAppsRecyclerView] 派发）。 */
class RearDesktopHoneycombAdapter :
    ListAdapter<RearDesktopAppEntry, RearDesktopHoneycombAdapter.Vh>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemInstalledAppHoneycombBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(getItem(position))
    }

    class Vh(
        private val binding: ItemInstalledAppHoneycombBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: RearDesktopAppEntry) {
            binding.imageAppIcon.setImageDrawable(entry.icon)
            binding.imageAppIcon.contentDescription = entry.label
            binding.dotRearPinned.visibility = View.GONE
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<RearDesktopAppEntry>() {
                override fun areItemsTheSame(a: RearDesktopAppEntry, b: RearDesktopAppEntry): Boolean =
                    a.packageName == b.packageName

                override fun areContentsTheSame(a: RearDesktopAppEntry, b: RearDesktopAppEntry): Boolean =
                    a.packageName == b.packageName && a.label == b.label
            }
    }
}
