package com.wmqc.miroot.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.util.TypedValue
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppliedRearThemeAdapter(
    private val scope: CoroutineScope,
    private val taskService: () -> ITaskService?,
    private val onItemClick: ((AppliedRearTheme) -> Unit)?,
    private val onItemLongClick: ((AppliedRearTheme) -> Boolean)? = null,
) : ListAdapter<AppliedRearTheme, AppliedRearThemeAdapter.Vh>(Diff) {

    private val thumbJobs = mutableMapOf<String, Job>()

    private val thumbMemoryCache = object : LruCache<String, Bitmap>(THUMB_MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var listGen = 0L

    private fun stableKey(t: AppliedRearTheme) = "${t.id}_${t.mrcPath}"

    private fun memoryCacheKey(item: AppliedRearTheme) = stableKey(item) + "\u0000" + item.snapshotPath

    /** 清除已解码略缩图内存缓存；主题内容变更后调用（如替换 .mrc / 更新 snapshot）。 */
    fun evictThumbnailMemoryCache() {
        thumbMemoryCache.evictAll()
    }

    override fun onCurrentListChanged(previousList: List<AppliedRearTheme>, currentList: List<AppliedRearTheme>) {
        listGen++
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemAppliedRearThemeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = getItem(position)
        val ctx = holder.binding.root.context
        holder.bindMeta(ctx, item)

        val memKey = memoryCacheKey(item)
        val cached = thumbMemoryCache.get(memKey)
        if (cached != null && !cached.isRecycled) {
            holder.binding.imageAppliedThumb.setImageBitmap(cached)
        } else {
            holder.binding.imageAppliedThumb.setImageDrawable(null)
        }

        thumbJobs[stableKey(item)]?.cancel()

        val key = stableKey(item)
        val maxSide = thumbMaxDecodeSidePx(ctx)
        if (cached == null || cached.isRecycled) {
            val capturedGen = listGen
            thumbJobs[key] = scope.launch {
                try {
                    val ts = waitForTaskServiceOrNull() ?: return@launch
                    val bmp =
                        withContext(Dispatchers.IO) {
                            val previewPath =
                                AppliedRearThemeHelper.resolveThemeThumbnailPreviewPath(ts, item)
                                    ?: return@withContext null
                            var bytes: ByteArray? = null
                            repeat(THUMB_LOAD_RETRY_COUNT) { index ->
                                bytes =
                                    AiWallpaperThemeHelper.loadPreviewImageBytes(ctx, ts, previewPath)
                                if (bytes != null) {
                                    return@withContext ThemeDirectoryAdapter.decodeSampledThumbnail(bytes, maxSide)
                                }
                                if (index < THUMB_LOAD_RETRY_COUNT - 1) {
                                    delay(THUMB_LOAD_RETRY_DELAY_MS)
                                }
                            }
                            null
                        }
                    if (bmp == null) return@launch
                    withContext(Dispatchers.Main) {
                        if (capturedGen != listGen) return@withContext
                        val pos = holder.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return@withContext
                        if (getItem(pos).id != item.id || getItem(pos).mrcPath != item.mrcPath) return@withContext
                        thumbMemoryCache.put(memKey, bmp)
                        holder.binding.imageAppliedThumb.setImageBitmap(bmp)
                    }
                } finally {
                    thumbJobs.remove(key)
                }
            }
        }

        bindClicks(holder, item)
    }

    private suspend fun waitForTaskServiceOrNull(): ITaskService? {
        repeat(TASK_SERVICE_WAIT_TRIES) { attempt ->
            taskService()?.let { return it }
            if (attempt < TASK_SERVICE_WAIT_TRIES - 1) {
                delay(TASK_SERVICE_WAIT_MS)
            }
        }
        return taskService()
    }

    private fun bindClicks(holder: Vh, item: AppliedRearTheme) {
        val click = onItemClick
        if (click != null) {
            holder.itemView.isClickable = true
            holder.itemView.isFocusable = true
            if (holder.itemView.foreground == null) {
                val out = TypedValue()
                val ctx = holder.itemView.context
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    holder.itemView.foreground = ContextCompat.getDrawable(ctx, out.resourceId)
                }
            }
            holder.itemView.setOnClickListener { click(item) }
        } else {
            holder.itemView.isClickable = false
            holder.itemView.isFocusable = false
            holder.itemView.foreground = null
            holder.itemView.setOnClickListener(null)
        }
        val longClick = onItemLongClick
        if (longClick != null) {
            holder.itemView.isLongClickable = true
            holder.itemView.setOnLongClickListener { longClick(item) }
        } else {
            holder.itemView.isLongClickable = false
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
            binding.textAppliedBadge.visibility = View.GONE
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

    companion object {
        private const val THUMB_MEMORY_CACHE_KB = 12 * 1024
        private const val THUMB_LOAD_RETRY_COUNT = 3
        private const val THUMB_LOAD_RETRY_DELAY_MS = 120L
        private const val TASK_SERVICE_WAIT_TRIES = 16
        private const val TASK_SERVICE_WAIT_MS = 50L

        private fun thumbMaxDecodeSidePx(ctx: Context): Int {
            val r = ctx.resources
            val w = r.getDimensionPixelSize(R.dimen.theme_applied_rear_thumb_width)
            val h = r.getDimensionPixelSize(R.dimen.theme_applied_rear_thumb_height)
            return maxOf(w, h).coerceIn(128, 512)
        }
    }
}
