package com.wmqc.miroot.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wmqc.miroot.databinding.ItemThemeDirectoryBinding
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.theme.AiWallpaperThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThemeDirectoryUi(
    val name: String,
    val previewPath: String,
    /** 每次扫描递增，用于 Diff 判定内容变化以便重新绑定；内存缩略图缓存仅按 [previewPath] 键控。 */
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
    /** 防止列表重绑/短暂服务未就绪时把已显示缩略图清空。 */
    private val thumbMemoryCache = object : LruCache<String, Bitmap>(THUMB_MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var listGen = 0L

    override fun onCurrentListChanged(previousList: List<ThemeDirectoryUi>, currentList: List<ThemeDirectoryUi>) {
        // 增量 listGen 使尚在运行中的旧 generation 协程在完成时能识别结果已过期并丢弃。
        // 不做批量取消：onBindViewHolder 已按 item.name 取消前次 job，且协程体内的陈旧检查
        // (bindingAdapterPosition / getItem(pos) 对比) 可防止设置到错误的 ImageView。
        // 避免因取消导致 ImageView 已清空但无新 job 填充的空白问题。
        listGen++
    }

    /** 与 [AiWallpaperThemeHelper.clearAiWallpaperPreviewThumbnailCaches] 配套，丢弃已解码略缩图。 */
    fun evictThumbnailMemoryCache() {
        thumbMemoryCache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemThemeDirectoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = getItem(position)
        holder.binding.textDirName.text = item.name
        syncPreviewSize(holder)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item) }
        val sel = selectedName()
        holder.binding.overlaySelected.visibility =
            if (sel != null && sel == item.name) View.VISIBLE else View.GONE
        // 仅按预览路径缓存：listRevision 每次扫描都会变，若拼进 key 会导致永不命中、反复清空 ImageView 露出浅色底。
        // 预览文件变更时由 [evictThumbnailMemoryCache] / 刷新流程清磁盘与内存缓存。
        val memCacheKey = item.previewPath
        val cachedBitmap = if (memCacheKey.isNotEmpty()) thumbMemoryCache.get(memCacheKey) else null
        thumbJobs[item.name]?.cancel()
        if (item.previewPath.isEmpty()) {
            holder.binding.imagePreview.setImageDrawable(null)
            return
        }
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            holder.binding.imagePreview.setImageBitmap(cachedBitmap)
            return
        }
        holder.binding.imagePreview.setImageDrawable(null)
        val capturedGen = listGen
        thumbJobs[item.name] = scope.launch(Dispatchers.IO) {
            try {
                val ts = waitForTaskServiceOrNull() ?: return@launch
                val bytes = withContext(Dispatchers.IO) {
                    var loaded: ByteArray? = null
                    repeat(THUMB_LOAD_RETRY_COUNT) { index ->
                        loaded =
                            AiWallpaperThemeHelper.loadPreviewImageBytes(
                                holder.binding.root.context,
                                ts,
                                item.previewPath,
                            )
                        if (loaded != null) return@withContext loaded
                        if (index < THUMB_LOAD_RETRY_COUNT - 1) {
                            delay(THUMB_LOAD_RETRY_DELAY_MS)
                        }
                    }
                    loaded
                }
                if (bytes == null) return@launch
                val bmp =
                    decodeSampledThumbnail(bytes, AI_DIR_THUMB_MAX_SIDE)
                        ?: return@launch
                withContext(Dispatchers.Main) {
                    if (capturedGen != listGen) return@withContext
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@withContext
                    val bound = getItem(pos)
                    if (bound.name != item.name || bound.previewPath != item.previewPath) return@withContext
                    thumbMemoryCache.put(memCacheKey, bmp)
                    holder.binding.imagePreview.setImageBitmap(bmp)
                }
            } finally {
                thumbJobs.remove(item.name)
            }
        }
    }

    private fun syncPreviewSize(holder: Vh) {
        val previewView = holder.binding.imagePreview
        previewView.post {
            val measuredWidth = previewView.width
            if (measuredWidth <= 0) return@post
            val targetHeight = ((measuredWidth * CARD_PREVIEW_ASPECT_H) / CARD_PREVIEW_ASPECT_W).toInt()
            previewView.updateLayoutParams<ViewGroup.LayoutParams> {
                if (height != targetHeight) {
                    height = targetHeight
                }
            }
        }
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

    private suspend fun waitForTaskServiceOrNull(): ITaskService? {
        repeat(TASK_SERVICE_WAIT_TRIES) { attempt ->
            taskService()?.let { return it }
            if (attempt < TASK_SERVICE_WAIT_TRIES - 1) {
                delay(TASK_SERVICE_WAIT_MS)
            }
        }
        return taskService()
    }

    private object Diff : DiffUtil.ItemCallback<ThemeDirectoryUi>() {
        override fun areItemsTheSame(a: ThemeDirectoryUi, b: ThemeDirectoryUi): Boolean = a.name == b.name

        override fun areContentsTheSame(a: ThemeDirectoryUi, b: ThemeDirectoryUi): Boolean =
            a.name == b.name &&
                a.previewPath == b.previewPath &&
                a.listRevision == b.listRevision
    }

    companion object {
        private const val CARD_PREVIEW_ASPECT_W = 976f
        private const val CARD_PREVIEW_ASPECT_H = 596f

        /** 网格略缩图采样边长上限，减轻并行解码时的内存压力。 */
        private const val AI_DIR_THUMB_MAX_SIDE = 512
        private const val THUMB_LOAD_RETRY_COUNT = 3
        private const val THUMB_LOAD_RETRY_DELAY_MS = 120L
        private const val THUMB_MEMORY_CACHE_KB = 12 * 1024
        private const val TASK_SERVICE_WAIT_TRIES = 16
        private const val TASK_SERVICE_WAIT_MS = 50L

        /** 封面绑定等列表项较多时使用，按边长采样解码。 */
        internal fun decodeSampledThumbnail(data: ByteArray, maxSide: Int): Bitmap? {
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
