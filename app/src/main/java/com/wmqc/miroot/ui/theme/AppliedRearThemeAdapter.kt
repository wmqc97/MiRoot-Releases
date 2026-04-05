package com.wmqc.miroot.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.ItemAppliedRearThemeBinding
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.theme.AiWallpaperThemeHelper
import com.wmqc.miroot.theme.AppliedRearTheme
import com.wmqc.miroot.theme.AppliedRearThemeHelper
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppliedRearThemeAdapter(
    private val scope: CoroutineScope,
    private val taskService: () -> ITaskService?,
    private val onItemClick: ((AppliedRearTheme) -> Unit)?,
    private val onItemLongClick: ((AppliedRearTheme) -> Boolean)? = null,
) : ListAdapter<AppliedRearTheme, AppliedRearThemeAdapter.Vh>(Diff) {

    private val thumbJobs = mutableMapOf<String, Job>()

    private fun stableKey(t: AppliedRearTheme) = "${t.id}_${t.mrcPath}"

    override fun onCurrentListChanged(previousList: List<AppliedRearTheme>, currentList: List<AppliedRearTheme>) {
        thumbJobs.values.forEach { it.cancel() }
        thumbJobs.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemAppliedRearThemeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = getItem(position)
        val ctx = holder.binding.root.context
        holder.bindMeta(ctx, item)

        holder.binding.imageAppliedThumb.setImageDrawable(null)
        thumbJobs[stableKey(item)]?.cancel()

        val ts = taskService()
        val previewPath =
            if (ts != null && item.snapshotPath.isNotEmpty()) {
                AppliedRearThemeHelper.resolveSnapshotPreviewPath(ts, item.snapshotPath)
            } else {
                null
            }

        if (ts != null && previewPath != null) {
            val key = stableKey(item)
            thumbJobs[key] = scope.launch {
                val bytes =
                    withContext(Dispatchers.IO) {
                        AiWallpaperThemeHelper.loadPreviewImageBytes(ctx, ts, previewPath)
                    }
                if (bytes == null) return@launch
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                withContext(Dispatchers.Main) {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@withContext
                    if (getItem(pos).id != item.id || getItem(pos).mrcPath != item.mrcPath) return@withContext
                    holder.binding.imageAppliedThumb.setImageBitmap(bmp)
                }
            }
        }

        val click = onItemClick
        if (click != null) {
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener { click(item) }
        } else {
            holder.itemView.isClickable = false
            holder.itemView.setOnClickListener(null)
        }
        val longClick = onItemLongClick
        if (longClick != null) {
            holder.itemView.setOnLongClickListener { longClick(item) }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }

    override fun onViewRecycled(holder: Vh) {
        super.onViewRecycled(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            thumbJobs.remove(stableKey(getItem(pos)))?.cancel()
        }
    }

    class Vh(val binding: ItemAppliedRearThemeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindMeta(ctx: Context, item: AppliedRearTheme) {
            binding.textAppliedTitle.text = item.resName
            val source =
                if (item.isPrecust) {
                    ctx.getString(R.string.theme_applied_source_precust)
                } else {
                    ctx.getString(R.string.theme_applied_source_user)
                }
            val categoryLabel = categoryDisplayName(ctx, item.title)
            binding.textAppliedMeta.text =
                ctx.getString(
                    R.string.theme_applied_subtitle_fmt,
                    item.id.toString(),
                    categoryLabel,
                    source,
                )
        }

        /** widget.json 里 extra.title：系统侧主题资源分类，常见 signature / nature / watch 等。 */
        private fun categoryDisplayName(ctx: Context, title: String): String {
            val t = title.trim()
            if (t.isEmpty() || t == "—") {
                return ctx.getString(R.string.theme_applied_cat_other)
            }
            return when (t.lowercase(Locale.ROOT)) {
                "signature" -> ctx.getString(R.string.theme_applied_cat_signature)
                "nature" -> ctx.getString(R.string.theme_applied_cat_nature)
                "watch" -> ctx.getString(R.string.theme_applied_cat_watch)
                else -> t
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppliedRearTheme>() {
        override fun areItemsTheSame(a: AppliedRearTheme, b: AppliedRearTheme): Boolean =
            a.id == b.id && a.mrcPath == b.mrcPath

        override fun areContentsTheSame(a: AppliedRearTheme, b: AppliedRearTheme): Boolean = a == b
    }
}
