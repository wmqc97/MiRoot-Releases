package com.wmqc.miroot.ui.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.ItemInstalledAppBinding
import com.wmqc.miroot.rear.AppProjectionOfficialGesturePolicy

class InstalledAppsAdapter(
    private val onClick: (InstalledAppRow) -> Unit,
    private val onLongClick: (InstalledAppRow) -> Unit,
    private val onRearDesktopToggle: (InstalledAppRow) -> Unit,
) : ListAdapter<InstalledAppRow, InstalledAppsAdapter.Vh>(DIFF) {

    /** 手动布局模式下显示右侧「背屏」勾选。*/
    var showRearDesktopToggle: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemInstalledAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding, onClick, onLongClick, onRearDesktopToggle)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(getItem(position), showRearDesktopToggle)
    }

    class Vh(
        private val binding: ItemInstalledAppBinding,
        private val onClick: (InstalledAppRow) -> Unit,
        private val onLongClick: (InstalledAppRow) -> Unit,
        private val onRearDesktopToggle: (InstalledAppRow) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var row: InstalledAppRow? = null

        init {
            binding.root.setOnClickListener {
                row?.let(onClick)
            }
            binding.root.setOnLongClickListener {
                val item = row ?: return@setOnLongClickListener false
                onLongClick(item)
                true
            }
        }

        fun bind(item: InstalledAppRow, showRearToggle: Boolean) {
            row = item
            binding.textAppLabel.text = item.label
            binding.textAppPackage.text = item.packageName
            binding.imageAppIcon.setImageDrawable(item.icon)

            binding.checkRearDesktop.visibility =
                if (showRearToggle) View.VISIBLE else View.GONE
            if (showRearToggle) {
                binding.checkRearDesktop.setOnCheckedChangeListener(null)
                binding.checkRearDesktop.isChecked = item.rearDesktopPinned
                binding.checkRearDesktop.setOnCheckedChangeListener { _, checked ->
                    val r = row ?: return@setOnCheckedChangeListener
                    if (checked != r.rearDesktopPinned) {
                        onRearDesktopToggle(r)
                    }
                }
            }

            val ctx = binding.root.context
            val segments = ArrayList<String>()
            item.projectionConfig?.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { segments.add(it) }
            val officialPerApp =
                AppProjectionOfficialGesturePolicy.getScope(ctx) ==
                    AppProjectionOfficialGesturePolicy.Scope.SELECTED &&
                    item.projectionConfig?.disableOfficialSubscreen == true
            if (officialPerApp) {
                segments.add(ctx.getString(R.string.apps_official_gesture_pill_short))
            }
            if (item.rearDesktopPinned) {
                segments.add(ctx.getString(R.string.apps_rear_desktop_in_layout_short))
            }
            if (item.musicAutoProjectionBlacklisted) {
                segments.add(ctx.getString(R.string.apps_music_blacklist_pill_short))
            }
            val combined = segments.joinToString(" 路 ")
            binding.textAppProjectionConfig.text = combined
            binding.textAppProjectionConfig.visibility = if (combined.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<InstalledAppRow>() {
            override fun areItemsTheSame(oldItem: InstalledAppRow, newItem: InstalledAppRow): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: InstalledAppRow, newItem: InstalledAppRow): Boolean =
                oldItem.label == newItem.label &&
                    oldItem.packageName == newItem.packageName &&
                    oldItem.projectionConfig == newItem.projectionConfig &&
                    oldItem.rearDesktopPinned == newItem.rearDesktopPinned &&
                    oldItem.musicAutoProjectionBlacklisted == newItem.musicAutoProjectionBlacklisted
        }
    }
}
