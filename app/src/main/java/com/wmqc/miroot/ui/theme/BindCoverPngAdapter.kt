package com.wmqc.miroot.ui.theme

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wmqc.miroot.databinding.ItemBindCoverPngBinding
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.theme.AiWallpaperThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BindCoverPngAdapter(
    private val scope: CoroutineScope,
    private val paths: List<String>,
    private val taskService: () -> ITaskService?,
    private val onPicked: (String) -> Unit,
) : RecyclerView.Adapter<BindCoverPngAdapter.Vh>() {

    private val thumbJobs = mutableMapOf<String, Job>()

    override fun getItemCount(): Int = paths.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemBindCoverPngBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val path = paths[position]
        holder.binding.imagePngThumb.setImageDrawable(null)
        thumbJobs[path]?.cancel()
        thumbJobs[path] = scope.launch {
            val ts = taskService() ?: return@launch
            val bytes =
                withContext(Dispatchers.IO) {
                    AiWallpaperThemeHelper.loadPreviewImageBytes(holder.binding.root.context, ts, path)
                }
            if (bytes == null) return@launch
            // 与 AppliedRearThemeAdapter 一致：全图解码，不在此做采样压缩
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
            withContext(Dispatchers.Main) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@withContext
                if (paths.getOrNull(pos) != path) return@withContext
                holder.binding.imagePngThumb.setImageBitmap(bmp)
            }
        }
        holder.itemView.setOnClickListener { onPicked(path) }
    }

    override fun onViewRecycled(holder: Vh) {
        super.onViewRecycled(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            paths.getOrNull(pos)?.let { thumbJobs.remove(it)?.cancel() }
        }
    }

    class Vh(val binding: ItemBindCoverPngBinding) : RecyclerView.ViewHolder(binding.root)
}
