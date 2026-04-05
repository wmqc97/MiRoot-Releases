package com.wmqc.miroot.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wmqc.miroot.databinding.ItemThemeDirectoryBinding
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.theme.AiWallpaperThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThemeDirectoryUi(
    val name: String,
    val previewPath: String,
    /** 每次扫描递增，使同路径预览更新后 Diff 会重新 bind 并重读缩略图。 */
    val listRevision: Long,
)

class ThemeDirectoryAdapter(
    private val scope: CoroutineScope,
    private val taskService: () -> ITaskService?,
    private val selectedName: () -> String?,
    private val onClick: (ThemeDirectoryUi) -> Unit,
    private val onLongClick: (ThemeDirectoryUi) -> Boolean,
) : ListAdapter<ThemeDirectoryUi, ThemeDirectoryAdapter.Vh>(Diff) {

    private val thumbJobs = mutableMapOf<String, Job>()

    override fun onCurrentListChanged(previousList: List<ThemeDirectoryUi>, currentList: List<ThemeDirectoryUi>) {
        thumbJobs.values.forEach { it.cancel() }
        thumbJobs.clear()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemThemeDirectoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = getItem(position)
        holder.binding.textDirName.text = item.name
        val sel = selectedName()
        holder.binding.overlaySelected.visibility =
            if (sel != null && sel == item.name) View.VISIBLE else View.GONE
        holder.binding.imagePreview.setImageDrawable(null)
        thumbJobs[item.name]?.cancel()
        if (item.previewPath.isEmpty()) {
            return
        }
        thumbJobs[item.name] = scope.launch {
            val ts = taskService() ?: return@launch
            val bytes = withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.loadPreviewImageBytes(holder.binding.root.context, ts, item.previewPath)
            }
            if (bytes == null) return@launch
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
            withContext(Dispatchers.Main) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@withContext
                if (getItem(pos).name != item.name) return@withContext
                holder.binding.imagePreview.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item) }
    }

    override fun onViewRecycled(holder: Vh) {
        super.onViewRecycled(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            thumbJobs.remove(getItem(pos).name)?.cancel()
        }
    }

    /** 仅同步选中遮罩，不触发整表 bind（避免打开选视频界面时重载全部略缩图）。 */
    fun syncVisibleSelectionOverlay(recyclerView: RecyclerView) {
        val sel = selectedName()
        val n = recyclerView.childCount
        for (i in 0 until n) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? Vh ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            val item = currentList.getOrNull(pos) ?: continue
            holder.binding.overlaySelected.visibility =
                if (sel != null && sel == item.name) View.VISIBLE else View.GONE
        }
    }

    class Vh(val binding: ItemThemeDirectoryBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<ThemeDirectoryUi>() {
        override fun areItemsTheSame(a: ThemeDirectoryUi, b: ThemeDirectoryUi): Boolean = a.name == b.name

        override fun areContentsTheSame(a: ThemeDirectoryUi, b: ThemeDirectoryUi): Boolean =
            a.name == b.name && a.previewPath == b.previewPath && a.listRevision == b.listRevision
    }

    companion object {
        /** 封面绑定等列表项较多时使用，按边长采样解码。 */
        fun decodeSampledThumbnail(data: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        }
    }
}
