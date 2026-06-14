package com.wmqc.miroot.ui.theme
import com.wmqc.miroot.display.MainDisplayUi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import java.io.File
import java.io.FileOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.common.showSectionHelp
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.databinding.FragmentThemeBinding
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.RootTaskService
import com.wmqc.miroot.theme.AiWallpaperThemeHelper
import com.wmqc.miroot.theme.AppliedRearTheme
import com.wmqc.miroot.theme.AppliedRearThemeHelper
import com.wmqc.miroot.theme.AiRearscreenLyricsGestureInjector
import com.wmqc.miroot.theme.GestureInjectOutcome
import com.wmqc.miroot.theme.DirectoryCoverBindingHelper
import com.wmqc.miroot.theme.ThemeMetadataInjector
import com.wmqc.miroot.theme.ThemeWorkspaceCleaner
import com.wmqc.miroot.theme.VideoReplacer
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThemeFragment : Fragment(R.layout.fragment_theme) {

    private var _binding: FragmentThemeBinding? = null

    private val binding get() = _binding!!

    private fun snack(message: Int, longDuration: Boolean = false) {
        MainDisplayUi.showToast(
            requireContext(),
            message,
            if (longDuration) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        )
    }

    private fun snack(message: String) {
        MainDisplayUi.showToast(requireContext(), message, android.widget.Toast.LENGTH_LONG)
    }

    private val permissionViewModel: MainPermissionViewModel by activityViewModels()

    private var taskService: ITaskService? = null
    private var serviceBound = false

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var selectedDirName: String? = null
    private var selectedVideoPath: String? = null
    private var pendingReplaceThemeDir: String? = null

    @Volatile
    private var aiDirectoryRefreshRunning = false

    /**
     * 与 [PermissionSnapshot.privileged] 同步；仅在变化时 [refreshDirectoryList]，避免
     * MainActivity 每次 onResume 触发权限探测导致从系统选择器返回就重扫 AI 目录。
     */
    private var lastPrivilegedForAiDirRefresh: Boolean? = null

    /** 元数据弹窗内选中的效果图 Uri 字符串 */
    private var metaEditEffectUri: String? = null

    private var metadataEditorForm: android.view.View? = null

    private lateinit var directoryAdapter: ThemeDirectoryAdapter
    private lateinit var appliedAdapter: AppliedRearThemeAdapter

    private val videoVolumeState = mutableIntStateOf(100)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                taskService = ITaskService.Stub.asInterface(service)
                view?.post {
                    refreshDirectoryList()
                    refreshAppliedThemes()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                taskService = null
            }
        }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val ref = uri.toString()
            viewLifecycleOwner.lifecycleScope.launch {
                val resolved =
                    withContext(Dispatchers.IO) {
                        AiWallpaperThemeHelper.resolvePickedFilePath(requireContext(), ref)
                    }
                selectedVideoPath = resolved ?: ref
                delay(300)
                runReplaceVideo()
            }
        }

    private val pickThemeLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                pendingReplaceThemeDir = null
                return@registerForActivityResult
            }
            val dir = pendingReplaceThemeDir ?: return@registerForActivityResult
            pendingReplaceThemeDir = null
            val ref = uri.toString()
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = requireContext()
                val resolved = withContext(Dispatchers.IO) {
                    AiWallpaperThemeHelper.resolvePickedFilePath(ctx, ref)
                } ?: ref
                val themeFile = File(resolved)
                if (!themeFile.isFile || themeFile.length() == 0L) {
                    snack(R.string.theme_replace_fail)
                    return@launch
                }
                val config = withContext(Dispatchers.IO) {
                    AiWallpaperThemeHelper.readVarConfigFromZip(resolved)
                }
                if (config == null) {
                    snack(R.string.theme_replace_fail)
                    return@launch
                }
                val previewBytes = withContext(Dispatchers.IO) {
                    AiWallpaperThemeHelper.readThemePreviewImage(ctx, ref)
                }
                val author = config?.get("author") ?: "—"
                val name = config?.get("name") ?: "—"
                val desc = config?.get("description") ?: "—"
                val bmp = previewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(name)
                    .setMessage(
                        buildString {
                            append(getString(R.string.theme_author_fmt, author))
                            append("\n")
                            append(getString(R.string.theme_desc_fmt, desc))
                            append("\n\n")
                            append(getString(R.string.theme_apply_confirm))
                        },
                    )
                    .apply {
                        if (bmp != null) {
                            val iv = android.widget.ImageView(ctx).apply {
                                setImageBitmap(bmp)
                                adjustViewBounds = true
                            }
                            setView(iv)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.theme_apply) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val ts = taskService
                                if (ts == null) {
                                    false
                                } else {
                                    AiWallpaperThemeHelper.replaceThemeFile(ctx, ts, dir, resolved)
                                }
                            }
                            if (ok) {
                                snack(R.string.theme_replace_ok, longDuration = true)
                                AiWallpaperThemeHelper.invalidateAiDirectoryRearscreenPreviewCaches(
                                    ctx.applicationContext,
                                    dir,
                                )
                                directoryAdapter.evictThumbnailMemoryCache()
                                _binding?.switchAutoThemeManager?.let { sw ->
                                    if (sw.isChecked) AiWallpaperThemeHelper.openThemeManager(ctx)
                                }
                                refreshDirectoryList()
                            } else {
                                snack(R.string.theme_replace_fail)
                            }
                        }
                    }
                    .show()
            }
        }

    private val pickThemeForMetaLauncher =
        registerForActivityResult(OpenPersistableDocumentContract(writable = false)) { result ->
            if (result == null) return@registerForActivityResult
            val ctx = requireContext()
            runCatching {
                val flags =
                    result.resultIntent.flags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (flags != 0) {
                    ctx.contentResolver.takePersistableUriPermission(result.uri, flags)
                }
            }
            val ref = result.uri.toString()
            viewLifecycleOwner.lifecycleScope.launch {
                showMetadataEditorDialog(ref)
            }
        }

    private val pickMetaEffectLauncher =
        registerForActivityResult(OpenPersistableDocumentContract(writable = false)) { result ->
            if (result == null) return@registerForActivityResult
            runCatching {
                val flags = result.resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flags != 0) {
                    requireContext().contentResolver.takePersistableUriPermission(result.uri, flags)
                }
            }
            metaEditEffectUri = result.uri.toString()
            metadataEditorForm
                ?.findViewById<ImageView>(R.id.image_effect_preview)
                ?.setImageURI(result.uri)
        }

    private val pickThemeForGestureInjectLauncher =
        registerForActivityResult(OpenPersistableDocumentContract(writable = false)) { result ->
            if (result == null) return@registerForActivityResult
            runCatching {
                val flags = result.resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flags != 0) {
                    requireContext().contentResolver.takePersistableUriPermission(result.uri, flags)
                }
            }
            val ref = result.uri.toString()
            viewLifecycleOwner.lifecycleScope.launch {
                runInjectGestureOnPickedThemeZip(ref)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentThemeBinding.bind(view)

        binding.switchVideoLoop.isChecked = prefs.getBoolean(KEY_LOOP, false)
        binding.switchVideoSound.isChecked = prefs.getBoolean(KEY_SOUND, false)
        binding.switchAutoThemeManager.isChecked = prefs.getBoolean(KEY_AUTO_THEME, true)
        videoVolumeState.intValue = prefs.getInt(KEY_VOLUME, 100).coerceIn(0, 100)
        binding.composeVideoVolume.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeVideoVolume.setContent {
            ThemeVideoVolumeSlider(
                volumeState = videoVolumeState,
                onVolumeChange = { n ->
                    prefs.edit().putInt(KEY_VOLUME, n).apply()
                },
            )
        }
        binding.composeVideoVolume.visibility =
            if (binding.switchVideoSound.isChecked) View.VISIBLE else View.GONE

        binding.switchVideoLoop.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LOOP, checked).apply()
        }
        binding.switchVideoSound.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SOUND, checked).apply()
            binding.composeVideoVolume.visibility =
                if (checked) View.VISIBLE else View.GONE
        }
        binding.switchAutoThemeManager.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_THEME, checked).apply()
        }

        binding.imageAppliedThemesExpand.setOnClickListener {
            val expanded = binding.layoutAppliedThemesBody.visibility != View.VISIBLE
            binding.layoutAppliedThemesBody.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.imageAppliedThemesExpand.rotation = if (expanded) 180f else 0f
        }
        // 默认展开「已应用背屏主题」
        binding.layoutAppliedThemesBody.visibility = View.VISIBLE
        binding.imageAppliedThemesExpand.rotation = 180f
        binding.textSectionThemeVideo.setOnClickListener {
            showSectionHelp(R.string.theme_section_video, R.string.help_theme_video)
        }
        binding.textSectionThemeDirectories.setOnClickListener {
            showSectionHelp(R.string.theme_section_directories, R.string.help_theme_directories)
        }
        binding.textSectionThemeApplied.setOnClickListener {
            showSectionHelp(R.string.theme_section_applied, R.string.help_theme_applied)
        }
        binding.textSectionThemeMetaEdit.setOnClickListener {
            showSectionHelp(R.string.theme_section_meta_edit, R.string.help_theme_meta_edit)
        }
        binding.textSectionThemeCache.setOnClickListener {
            showSectionHelp(R.string.theme_section_cache, R.string.help_theme_cache)
        }

        directoryAdapter =
            ThemeDirectoryAdapter(
                scope = viewLifecycleOwner.lifecycleScope,
                taskService = { taskService },
                selectedName = { selectedDirName },
                onClick = { item -> onDirectoryClicked(item) },
                onLongClick = { item -> onDirectoryLongClick(item) },
            )
        binding.recyclerDirectories.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerDirectories.adapter = directoryAdapter

        binding.textAppliedHint.setText(R.string.theme_applied_hint)
        appliedAdapter =
            AppliedRearThemeAdapter(
                scope = viewLifecycleOwner.lifecycleScope,
                taskService = { taskService },
                onItemClick = null,
                onItemLongClick = { item ->
                    onAppliedThemeLongClick(item)
                    true
                },
            )
        binding.recyclerAppliedThemes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerAppliedThemes.adapter = appliedAdapter

        binding.buttonRefreshDirs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (aiDirectoryRefreshRunning) return@launch
                aiDirectoryRefreshRunning = true
                val btn = _binding?.buttonRefreshDirs ?: run {
                    aiDirectoryRefreshRunning = false
                    return@launch
                }
                val appCtx = requireContext().applicationContext
                btn.isEnabled = false
                btn.alpha = 0.55f
                try {
                    withContext(Dispatchers.IO) {
                        AiWallpaperThemeHelper.clearAiWallpaperPreviewThumbnailCaches(appCtx)
                    }
                    directoryAdapter.evictThumbnailMemoryCache()
                    refreshThemeDirectoriesAndAppliedUnified(reloadAppliedThumbnails = false)
                } finally {
                    aiDirectoryRefreshRunning = false
                    _binding?.buttonRefreshDirs?.let {
                        it.isEnabled = true
                        it.alpha = 1f
                    }
                }
            }
        }

        binding.buttonEditThemeMeta.setOnClickListener {
            pickThemeForMetaLauncher.launch("*/*")
        }

        binding.buttonInjectGestureZip.setOnClickListener {
            pickThemeForGestureInjectLauncher.launch("*/*")
        }

        binding.buttonCleanWorkspaceCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.theme_clean_cache)
                .setMessage(R.string.theme_clean_cache_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.theme_clean_cache) { _, _ ->
                    val snap = permissionViewModel.snapshot.value
                    if (snap?.privileged != true) {
                        snack(R.string.theme_clean_cache_need_privilege)
                        return@setPositiveButton
                    }
                    val ts = taskService
                    if (ts == null) {
                        snack(R.string.theme_clean_cache_need_privilege)
                        return@setPositiveButton
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        val ok =
                            withContext(Dispatchers.IO) {
                                ThemeWorkspaceCleaner.cleanAllManual(ts)
                            }
                        if (ok) {
                            snack(R.string.theme_clean_cache_ok)
                        } else {
                            snack(R.string.theme_clean_cache_fail)
                        }
                    }
                }
                .show()
        }

        permissionViewModel.snapshot.observe(viewLifecycleOwner, ::renderPrivilegeBanner)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(requireContext(), RootTaskService::class.java)
        serviceBound = requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (serviceBound) {
            try {
                requireContext().unbindService(connection)
            } catch (_: Exception) {
            }
            serviceBound = false
        }
        taskService = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 勿在此处 refreshDirectoryList：从「选主题包」等系统选择器返回会走 onResume，不应重扫 AI 目录略缩图。
        refreshAppliedThemes()
    }

    private fun renderPrivilegeBanner(snap: PermissionSnapshot) {
        val ok = snap.privileged
        binding.textThemePrivilege.visibility = if (ok) View.GONE else View.VISIBLE
        refreshAppliedThemes()
        if (lastPrivilegedForAiDirRefresh != ok) {
            lastPrivilegedForAiDirRefresh = ok
            refreshDirectoryList()
        }
    }

    private fun onDirectoryClicked(item: ThemeDirectoryUi) {
        selectedDirName = item.name
        selectedVideoPath = null
        binding.textSelectedDir.text = getString(R.string.theme_selected_dir_fmt, item.name)
        viewLifecycleOwner.lifecycleScope.launch {
            val ts = taskService
            if (ts == null) {
                snack(R.string.theme_need_privilege)
                return@launch
            }
            snack(R.string.theme_preparing)
            val ok =
                withContext(Dispatchers.IO) {
                    val replacer =
                        VideoReplacer(
                            ts,
                            { },
                            requireContext().applicationContext,
                        )
                    replacer.prepareExtractedFiles(item.name)
                }
            if (!ok) {
                snack(R.string.theme_replace_fail)
                return@launch
            }
            pickVideoLauncher.launch("video/*")
        }
        binding.recyclerDirectories.post {
            directoryAdapter.syncVisibleSelectionOverlay(binding.recyclerDirectories)
        }
    }

    private fun onDirectoryLongClick(item: ThemeDirectoryUi): Boolean {
        val ctx = requireContext()
        val coverBound = DirectoryCoverBindingHelper.getBoundCoverPath(ctx, item.name) != null
        val coverAction =
            if (coverBound) {
                getString(R.string.theme_menu_cover_unbind)
            } else {
                getString(R.string.theme_menu_bind_cover)
            }
        val names =
            arrayOf(
                getString(R.string.theme_menu_delete),
                getString(R.string.theme_menu_replace_theme),
                coverAction,
                getString(R.string.theme_menu_inject_lyrics_gesture),
            )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(item.name)
            .setItems(names) { _, which ->
                when (which) {
                    0 -> confirmDeleteDirectory(item.name)
                    1 -> {
                        pendingReplaceThemeDir = item.name
                        pickThemeLauncher.launch("*/*")
                    }
                    2 -> {
                        if (coverBound) {
                            clearCoverBinding(item.name)
                        } else {
                            showBindCoverDialog(item.name)
                        }
                    }
                    3 -> {
                        val snap = permissionViewModel.snapshot.value
                        if (snap?.privileged != true) {
                            snack(R.string.theme_need_privilege)
                            return@setItems
                        }
                        val ts = taskService
                        if (ts == null) {
                            snack(R.string.theme_gesture_inject_fail_service)
                            return@setItems
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            snack(R.string.theme_gesture_inject_working)
                            val workDir =
                                withContext(Dispatchers.IO) {
                                    AiWallpaperThemeHelper.gestureInjectWorkDir(requireContext().applicationContext)
                                }
                            val outcome =
                                withContext(Dispatchers.IO) {
                                    AiRearscreenLyricsGestureInjector.inject(
                                        requireContext().applicationContext,
                                        ts,
                                        item.name,
                                        workDir,
                                    )
                                }
                            if (outcome == GestureInjectOutcome.OK) {
                                selectedDirName = item.name
                                binding.textSelectedDir.text = getString(R.string.theme_selected_dir_fmt, item.name)
                                refreshDirectoryList()
                                binding.recyclerDirectories.post {
                                    directoryAdapter.syncVisibleSelectionOverlay(binding.recyclerDirectories)
                                }
                                showGestureInjectSuccessDialog(
                                    aiDirectoryName = item.name,
                                )
                            } else {
                                snack(gestureInjectFailureMessage(outcome), longDuration = true)
                            }
                        }
                    }
                }
            }
            .show()
        return true
    }

    private fun confirmDeleteDirectory(dirName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme_menu_delete)
            .setMessage(getString(R.string.theme_delete_confirm, dirName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            taskService?.let { AiWallpaperThemeHelper.deleteDirectory(it, dirName) } ?: false
                        }
                    if (ok) {
                        if (selectedDirName == dirName) {
                            selectedDirName = null
                            binding.textSelectedDir.text = getString(R.string.theme_no_dir_selected)
                        }
                        refreshDirectoryList()
                    } else {
                        snack(R.string.theme_replace_fail)
                    }
                }
            }
            .show()
    }

    private fun showBindCoverDialog(dirName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val paths =
                withContext(Dispatchers.IO) {
                    taskService?.let { AiWallpaperThemeHelper.listAiWallpaperRootPngs(it) }.orEmpty()
                }
            val ctx = requireContext()
            val filtered =
                paths.filter { p ->
                    DirectoryCoverBindingHelper.isUsableCoverCandidateForBinding(ctx, dirName, p)
                }
            if (filtered.isEmpty()) {
                snack(
                    if (paths.isEmpty()) R.string.theme_bind_none else R.string.theme_bind_none_filtered,
                    longDuration = true,
                )
                return@launch
            }
            val dialogView = layoutInflater.inflate(R.layout.dialog_bind_cover, null)
            val rv = dialogView.findViewById<RecyclerView>(R.id.recycler_bind_cover)
            rv.layoutManager = GridLayoutManager(ctx, 2)
            var bindCoverDialog: AlertDialog? = null
            val adapter =
                BindCoverPngAdapter(
                    viewLifecycleOwner.lifecycleScope,
                    filtered,
                    { taskService },
                ) { path ->
                    bindCoverDialog?.dismiss()
                    if (!AiWallpaperThemeHelper.isUnderAiWallpaperPng(path)) {
                        snack(R.string.theme_replace_fail)
                        return@BindCoverPngAdapter
                    }
                    DirectoryCoverBindingHelper.setBinding(requireContext(), dirName, path)
                    snack(R.string.theme_bind_saved)
                    refreshDirectoryList()
                }
            rv.adapter = adapter
            bindCoverDialog =
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.theme_bind_pick_title)
                    .setMessage(R.string.theme_bind_pick_message)
                    .setView(dialogView)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            bindCoverDialog?.show()
        }
    }

    private fun clearCoverBinding(dirName: String) {
        DirectoryCoverBindingHelper.removeBinding(requireContext(), dirName)
        snack(R.string.theme_cover_cleared)
        refreshDirectoryList()
    }

    /**
     * 写回目标：能解析到应用缓存外的真实文件路径时直接用路径（与旧版优先返回 path 一致），
     * 否则保留 content Uri（依赖 OPEN_DOCUMENT + 持久读写授权写回）。
     */
    private fun themeMetadataWriteRef(pickedUriString: String, resolvedPath: String, ctx: Context): String {
        val cache = ctx.cacheDir.absolutePath
        val extCache = ctx.externalCacheDir?.absolutePath
        val f = File(resolvedPath)
        if (resolvedPath.startsWith("/") && f.isFile) {
            if (!resolvedPath.startsWith(cache) &&
                (extCache == null || !resolvedPath.startsWith(extCache))
            ) {
                return resolvedPath
            }
        }
        return pickedUriString
    }

    private suspend fun showMetadataEditorDialog(pickedUriString: String) {
        val ctx = requireContext()
        val resolved =
            withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.resolvePickedFilePath(ctx, pickedUriString) ?: pickedUriString
            }
        android.util.Log.e("GestureSave", "resolved=" + resolved)
        val writeRef = themeMetadataWriteRef(pickedUriString, resolved, ctx)
        val themeFile = File(resolved)
        if (!themeFile.isFile || themeFile.length() == 0L) {
            snack(R.string.theme_meta_fail)
            return
        }
        val config =
            withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.readVarConfigFromZip(resolved)
            }
        if (config == null) {
            if (!themeFile.isFile || themeFile.length() == 0L) {
                snack(R.string.theme_meta_fail)
                return
            }
            // 无 var_config 或包内 XML 无法解析时仍允许编辑；保存时会新建 var_config.xml
        }
        val cfg = config ?: emptyMap()
        val previewBytes =
            withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.readThemePreviewImage(ctx, pickedUriString)
            }
        val previewBmp = previewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        metaEditEffectUri = null
        val form = layoutInflater.inflate(R.layout.dialog_theme_metadata_edit, null)
        metadataEditorForm = form
        val editAuthor = form.findViewById<TextInputEditText>(R.id.edit_theme_author)
        val editName = form.findViewById<TextInputEditText>(R.id.edit_theme_name)
        val editDesc = form.findViewById<TextInputEditText>(R.id.edit_theme_description)
        val imageEffect = form.findViewById<ImageView>(R.id.image_effect_preview)
        editAuthor.setText(initialMetaField(cfg["author"], META_PLACEHOLDER_AUTHOR))
        editName.setText(initialMetaField(cfg["name"], META_PLACEHOLDER_NAME))
        editDesc.setText(initialMetaField(cfg["description"], META_PLACEHOLDER_DESC))
        if (previewBmp != null) {
            imageEffect.setImageBitmap(previewBmp)
        } else {
            imageEffect.setImageDrawable(null)
        }
        form.findViewById<MaterialButton>(R.id.button_dialog_pick_effect).setOnClickListener {
            pickMetaEffectLauncher.launch("image/*")
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.theme_meta_dialog_title)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.theme_meta_write) { _, _ ->
                val author =
                    editAuthor.text?.toString()?.trim().orEmpty().ifEmpty { "未知" }
                val name =
                    editName.text?.toString()?.trim().orEmpty().ifEmpty { "未命名主题" }
                val desc =
                    editDesc.text?.toString()?.trim().orEmpty().ifEmpty { "无描述" }
                val effUri = metaEditEffectUri
                val reuseZipEffect = effUri == null
                metadataEditorForm = null
                viewLifecycleOwner.lifecycleScope.launch {
                    runThemeMetadataInject(writeRef, author, name, desc, effUri, reuseZipEffect)
                }
            }
            .setOnDismissListener {
                metadataEditorForm = null
            }
            .show()
    }

    private suspend fun runInjectGestureOnPickedThemeZip(pickedUriString: String) {
        val ctx = requireContext().applicationContext
        snack(R.string.theme_gesture_inject_working)
        withContext(Dispatchers.IO) {
            AiWallpaperThemeHelper.prepareThemeShellWorkDirs(taskService)
        }
        val resolved =
            withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.resolvePickedFilePath(ctx, pickedUriString) ?: pickedUriString
            }
        android.util.Log.e("GestureSave", "resolved=" + resolved)
        var tempIn: File? = null
        val outTemp =
            File(
                AiWallpaperThemeHelper.THEME_TEMP_DIR + "gesture_zip_out_" + System.currentTimeMillis() + ".zip",
            )
        try {
            val inputFile =
                withContext(Dispatchers.IO) {
                    val rf = File(resolved)
                    if (resolved.startsWith("/") &&
                        rf.isFile &&
                        rf.length() > 0L &&
                        isStableStorageThemeZipPath(resolved, ctx)
                    ) {
                        rf
                    } else {
                        val t = runCatching { AiWallpaperThemeHelper.resolveThemeZipForGestureRead(ctx, pickedUriString) }.getOrNull()
                        tempIn = t
                        t
                    }
                }
            if (inputFile == null || !inputFile.isFile || inputFile.length() == 0L) {
                snack(R.string.theme_replace_fail)
                return
            }
            val patched =
                withContext(Dispatchers.IO) {
                    AiRearscreenLyricsGestureInjector.injectGestureIntoLocalZipFile(ctx, inputFile, outTemp)
                }
            if (!patched || !outTemp.isFile || outTemp.length() == 0L) {
                snack(R.string.theme_gesture_inject_fail)
                return
            }
            withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.ensureShellReadable(taskService, outTemp)
            }
            val savedPath =
                withContext(Dispatchers.IO) {
                    saveGestureInjectedZipToDisk(ctx, pickedUriString, resolved, outTemp)
                }
            if (savedPath == null) {
                snack(R.string.theme_replace_fail)
            } else {
                snack(R.string.theme_inject_gesture_ok)
            }
        } finally {
            withContext(Dispatchers.IO) {
                tempIn?.let { AiWallpaperThemeHelper.deleteThemeMetadataTempSourceZipQuietly(it) }
                AiWallpaperThemeHelper.deleteThemeMetadataTempSourceZipQuietly(outTemp)
            }
        }
    }

    /** 非应用缓存、非 MiRoot 主题临时目录下的真实文件路径，便于在原目录旁另存。 */
    private fun isStableStorageThemeZipPath(resolvedPath: String, ctx: Context): Boolean {
        if (!resolvedPath.startsWith("/")) return false
        val f = File(resolvedPath)
        if (!f.isFile || f.length() == 0L) return false
        val cache = ctx.cacheDir.absolutePath
        val ext = ctx.externalCacheDir?.absolutePath
        if (resolvedPath.startsWith(cache)) return false
        if (ext != null && resolvedPath.startsWith(ext)) return false
        if (resolvedPath.startsWith(AiWallpaperThemeHelper.THEME_TEMP_DIR)) return false
        return true
    }

    private fun displayNameForThemeZip(ctx: Context, pickedUriString: String): String {
        if (pickedUriString.startsWith("content://")) {
            val uri = Uri.parse(pickedUriString)
            ctx.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return sanitizeZipDisplayName(n)
                    }
                }
            return "theme_${System.currentTimeMillis()}.zip"
        }
        val path =
            when {
                pickedUriString.startsWith("file://") -> Uri.parse(pickedUriString).path ?: pickedUriString
                pickedUriString.startsWith("/") -> pickedUriString
                else -> pickedUriString
            }
        return sanitizeZipDisplayName(File(path).name.ifEmpty { "theme.zip" })
    }

    private fun sanitizeZipDisplayName(name: String): String =
        name.replace("/", "_").replace("\\", "_").trim().ifEmpty { "theme.zip" }

    private fun buildInjectedZipFileName(originalDisplayName: String): String {
        val base = originalDisplayName.trim().ifEmpty { "theme.zip" }
        val suf = "_已注入手势"
        val dot = base.lastIndexOf('.')
        return if (dot > 0) {
            base.substring(0, dot) + suf + base.substring(dot)
        } else {
            base + suf + ".zip"
        }
    }

    private fun uniquifyZipInDirectory(parent: File, preferredName: String): File {
        var candidate = File(parent, preferredName)
        if (!candidate.exists()) return candidate
        val dot = preferredName.lastIndexOf('.')
        val stem = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val ext = if (dot > 0) preferredName.substring(dot) else ".zip"
        var n = 2
        while (true) {
            candidate = File(parent, "${stem}_$n$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun copyZipFileOrNull(src: File, dst: File): Boolean =
        runCatching {
            dst.parentFile?.mkdirs()
            src.inputStream().use { inp ->
                FileOutputStream(dst).use { out -> inp.copyTo(out) }
            }
            dst.isFile && dst.length() > 0L
        }.getOrDefault(false)

    private fun saveGestureInjectedZipToDisk(
        ctx: Context,
        pickedUriString: String,
        resolvedPath: String,
        patchedTemp: File,
    ): String? {
        val injectedName =
            buildInjectedZipFileName(displayNameForThemeZip(ctx, pickedUriString))
        // 1) 稳定存储路径：在原目录保存带已注入标识的文件，原文件不动
        android.util.Log.e("GestureSave", "save: stable path=" + resolvedPath + " injectedName=" + injectedName)
        if (isStableStorageThemeZipPath(resolvedPath, ctx)) {
            val parent = File(resolvedPath).parentFile ?: return null
            val dest = File(parent, injectedName)
            return if (copyZipFileOrNull(patchedTemp, dest)) dest.absolutePath else null
        }
        // 2) content:// URI：手动解析真实路径（兼容 externalstorage / fileexplorer 文档选择器）
        android.util.Log.e("GestureSave", "save: content:// URI parsing, pickedUriString=" + pickedUriString)
        if (pickedUriString.startsWith("content://")) {
            val realPath = try {
                val uri = Uri.parse(pickedUriString)
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                val parts = docId.split(":")
                if (parts.size >= 2 && "primary".equals(parts[0], ignoreCase = true)) {
                    val subPath = parts.subList(1, parts.size).joinToString(":")
                    val fullPath = if (subPath.startsWith("/")) subPath
                        else android.os.Environment.getExternalStorageDirectory().toString() + "/" + subPath
                    if (File(fullPath).isFile) fullPath else null
                } else null
            } catch (_: Exception) { null }
            android.util.Log.e("GestureSave", "save: content:// parsing failed")
            if (realPath != null) {
                val parent = File(realPath).parentFile ?: return null
                val dest = uniquifyZipInDirectory(parent, injectedName)
                return if (copyZipFileOrNull(patchedTemp, dest)) dest.absolutePath else null
            }
        }
        // 3) 兜底：保存到 MiRoot 临时目录
        android.util.Log.e("GestureSave", "save: falling back to theme_temp")
        val dir = File(AiWallpaperThemeHelper.THEME_TEMP_DIR)
        dir.mkdirs()
        val dest = uniquifyZipInDirectory(dir, sanitizeZipDisplayName(injectedName))
        return if (copyZipFileOrNull(patchedTemp, dest)) dest.absolutePath else null
    }
    private suspend fun runThemeMetadataInject(
        themeRef: String,
        author: String,
        name: String,
        description: String,
        effectUri: String?,
        reuseEffectFromZip: Boolean,
    ) {
        val ctx = requireContext().applicationContext
        try {
            val effectPath =
                if (effectUri != null) {
                    withContext(Dispatchers.IO) {
                        AiWallpaperThemeHelper.resolvePickedFilePath(ctx, effectUri)
                    }
                } else {
                    null
                }
            val result =
                withContext(Dispatchers.IO) {
                    ThemeMetadataInjector.inject(
                        ctx,
                        themeRef,
                        author,
                        name,
                        description,
                        effectUri,
                        effectPath,
                        reuseEffectFromZip,
                    )
                }
            val msg =
                if (result.overwritten) {
                    getString(R.string.theme_meta_ok_overwrite)
                } else if (themeRef.startsWith("content://")) {
                    getString(R.string.theme_meta_ok_uri_copy_fmt, result.resultRef)
                } else {
                    getString(R.string.theme_meta_ok_new_fmt, result.resultRef)
                }
            snack(msg)
        } catch (e: Exception) {
            val t = e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.theme_meta_fail)
            snack(t)
        }
    }

    private fun initialMetaField(raw: String?, placeholders: Set<String>): String {
        val t = raw?.trim().orEmpty()
        return if (t.isEmpty() || t in placeholders) "" else t
    }

    private suspend fun runReplaceVideo() {
        val snap = permissionViewModel.snapshot.value
        if (snap?.privileged != true) {
            snack(R.string.theme_need_privilege)
            return
        }
        val dir = selectedDirName
        val video = selectedVideoPath
        if (dir.isNullOrEmpty() || video.isNullOrEmpty()) {
            snack(R.string.theme_no_video)
            return
        }
        val ts = taskService
        if (ts == null) {
            snack(R.string.theme_replace_fail)
            return
        }
        val b = _binding ?: return
        val loop = b.switchVideoLoop.isChecked
        val sound = b.switchVideoSound.isChecked
        val volume = videoVolumeState.intValue.coerceIn(0, 100)
        val ctx = requireContext().applicationContext
        val ok =
            withContext(Dispatchers.IO) {
                val replacer =
                    VideoReplacer(
                        ts,
                        { },
                        ctx,
                    )
                replacer.replaceVideo(dir, video, loop, sound, volume)
            }
        if (ok) {
            val appCtx = requireContext().applicationContext
            AiWallpaperThemeHelper.invalidateAiDirectoryRearscreenPreviewCaches(appCtx, dir)
            directoryAdapter.evictThumbnailMemoryCache()
            refreshDirectoryList()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(800)
                AiWallpaperThemeHelper.invalidateAiDirectoryRearscreenPreviewCaches(appCtx, dir)
                directoryAdapter.evictThumbnailMemoryCache()
                refreshDirectoryList()
            showAfterVideoReplaceDialog(
                aiDirectoryName = dir,
                sourceVideoPath = video,
            )
            }
        } else {
            snack(R.string.theme_replace_fail)
        }
    }

    /**
     * 用户选定视频且视频替换完成后：弹窗选择「跳转主题壁纸」或「替换已应用背屏主题」（仅个性签名类列表）。
     * 长按目录「替换主题」仍为选包替换，不受此弹窗影响。
     */
    private fun showAfterVideoReplaceDialog(
        aiDirectoryName: String,
        sourceVideoPath: String?,
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        val actions = LayoutInflater.from(ctx).inflate(R.layout.dialog_after_video_replace_actions, null, false)
        val dialog =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.theme_after_video_title)
                .setMessage(R.string.theme_after_video_message)
                .setView(actions)
                .create()
        actions.findViewById<MaterialButton>(R.id.btn_after_video_cancel).setOnClickListener {
            dialog.dismiss()
        }
        actions.findViewById<MaterialButton>(R.id.btn_after_video_replace).setOnClickListener {
            dialog.dismiss()
            showSignatureAppliedReplacePicker(
                aiDirectoryName = aiDirectoryName,
                sourceVideoPath = sourceVideoPath,
            )
        }
        actions.findViewById<MaterialButton>(R.id.btn_after_video_jump).setOnClickListener {
            dialog.dismiss()
            AiWallpaperThemeHelper.openThemeManager(ctx)
        }
        dialog.show()
    }

    /** 手势注入成功后弹窗：跳转主题壁纸 / 替换已应用背屏主题。 */
    private fun showGestureInjectSuccessDialog(
        aiDirectoryName: String,
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        val actions = LayoutInflater.from(ctx).inflate(R.layout.dialog_after_video_replace_actions, null, false)
        val dialog =
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.theme_gesture_inject_applied_title)
                .setMessage(R.string.theme_gesture_inject_applied_message)
                .setView(actions)
                .create()
        actions.findViewById<MaterialButton>(R.id.btn_after_video_cancel).setOnClickListener {
            dialog.dismiss()
        }
        actions.findViewById<MaterialButton>(R.id.btn_after_video_jump).setOnClickListener {
            dialog.dismiss()
            AiWallpaperThemeHelper.openThemeManager(ctx)
        }
        actions.findViewById<MaterialButton>(R.id.btn_after_video_replace).setOnClickListener {
            dialog.dismiss()
            showSignatureAppliedReplacePicker(
                aiDirectoryName = aiDirectoryName,
                sourceVideoPath = null,
            )
        }
        dialog.show()
    }

    private fun showSignatureAppliedReplacePicker(
        aiDirectoryName: String,
        sourceVideoPath: String?,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ts = taskService
            if (ts == null) {
                snack(R.string.theme_replace_fail)
                return@launch
            }
            val filtered =
                withContext(Dispatchers.IO) {
                    AppliedRearThemeHelper.loadAppliedThemes(ts).filter {
                        AppliedRearThemeHelper.isSignatureCategory(it.title)
                    }
                }
            if (filtered.isEmpty()) {
                snack(R.string.theme_after_video_no_signature, longDuration = true)
                return@launch
            }
            val ctx = requireContext()
            if (!isAdded) return@launch
            val content =
                LayoutInflater.from(ctx).inflate(R.layout.dialog_pick_signature_applied, null, false)
            val rv = content.findViewById<RecyclerView>(R.id.recycler_signature_applied_pick)
            rv.layoutManager = LinearLayoutManager(ctx)
            val dialog =
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.theme_after_video_pick_applied_title)
                    .setView(content)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            val adapter =
                AppliedRearThemeAdapter(
                    scope = viewLifecycleOwner.lifecycleScope,
                    taskService = { taskService },
                    onItemClick = { theme ->
                        dialog.dismiss()
                        viewLifecycleOwner.lifecycleScope.launch {
                            runReplaceAiRearscreenIntoAppliedTheme(theme, aiDirectoryName, sourceVideoPath)
                        }
                    },
                )
            rv.adapter = adapter
            adapter.submitList(filtered)
            dialog.show()
        }
    }

    private suspend fun runReplaceAiRearscreenIntoAppliedTheme(
        theme: AppliedRearTheme,
        aiDirectoryName: String,
        sourceVideoPath: String?,
    ) {
        val snap = permissionViewModel.snapshot.value
        if (snap?.privileged != true) {
            snack(R.string.theme_need_privilege)
            return
        }
        val ts = taskService
        if (ts == null) {
            snack(R.string.theme_replace_fail)
            return
        }
        val exists =
            withContext(Dispatchers.IO) {
                AppliedRearThemeHelper.mrcFileExists(ts, theme.mrcPath)
            }
        if (!exists) {
            snack(R.string.theme_applied_mrc_missing)
            return
        }
        val rearPath = AiWallpaperThemeHelper.AI_MAML_BASE + aiDirectoryName + "/rearscreen"
        val rearOk =
            withContext(Dispatchers.IO) {
                ts.executeShellCommandWithResult("test -f \"$rearPath\" && echo ok || echo no")?.trim() == "ok"
            }
        if (!rearOk) {
            snack(R.string.theme_replace_fail)
            return
        }
        val appCtx = requireContext().applicationContext
        val okMrc =
            withContext(Dispatchers.IO) {
                AiWallpaperThemeHelper.replaceRootOwnedFile(ts, theme.mrcPath, rearPath, true)
            }
        if (!okMrc) {
            snack(R.string.theme_replace_fail)
            return
        }
        val snapUpdated =
            if (theme.snapshotPath.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    AiWallpaperThemeHelper.updateRearSnapshotFromAiWallpaperPreviewRearscreen0(
                        appCtx,
                        ts,
                        aiDirectoryName,
                        theme.snapshotPath,
                                    sourceVideoPath?.takeIf { it.isNotBlank() },
                    )
                }
            } else {
                false
            }
        if (theme.snapshotPath.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val snapP = theme.snapshotPath.trim()
                AiWallpaperThemeHelper.invalidatePreviewImageCache(appCtx, snapP)
                val resolved = AppliedRearThemeHelper.resolveThemeThumbnailPreviewPath(ts, theme)
                if (!resolved.isNullOrEmpty() && resolved != snapP) {
                    AiWallpaperThemeHelper.invalidatePreviewImageCache(appCtx, resolved)
                }
            }
        }
        withContext(Dispatchers.IO) {
            AppliedRearThemeHelper.restartOfficialSubscreenCenter(ts)
        }
        val msgRes =
            if (theme.snapshotPath.isNotEmpty() && snapUpdated) {
                R.string.theme_applied_replace_ok_preview_thumb
            } else {
                R.string.theme_applied_replace_ok_video_mrc
            }
        snack(msgRes, longDuration = true)
        refreshAppliedThemes(reloadThumbnails = true)
    }

    private fun refreshDirectoryList() {
        val snap = permissionViewModel.snapshot.value
        if (snap?.privileged != true) {
            directoryAdapter.submitList(emptyList())
            return
        }
        val ts = taskService
        if (ts == null) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val rev = System.currentTimeMillis()
            val list =
                withContext(Dispatchers.IO) {
                    AiWallpaperThemeHelper.scanAiWallpaperDirectories(ts).map {
                        val dirName = it["name"].orEmpty()
                        ThemeDirectoryUi(
                            name = dirName,
                            previewPath = it["previewPath"].orEmpty(),
                            listRevision = rev,
                        )
                    }
                }
            directoryAdapter.submitList(list)
        }
    }

    /**
     * 一次拉取「已应用背屏主题」与 AI 目录列表，并在 IO 上并行扫描，避免刷新按钮串联两次扫描与
     * [refreshAppliedThemes] 末尾再次 [refreshDirectoryList] 的重复开销。
     */
    private suspend fun refreshThemeDirectoriesAndAppliedUnified(reloadAppliedThumbnails: Boolean) {
        if (!isAdded) return
        val snap = permissionViewModel.snapshot.value
        if (snap?.privileged != true) {
            directoryAdapter.submitList(emptyList())
            appliedAdapter.submitList(emptyList())
            binding.textAppliedThemesEmpty.visibility = View.GONE
            return
        }
        val ts = taskService
        if (ts == null) {
            directoryAdapter.submitList(emptyList())
            appliedAdapter.submitList(emptyList())
            binding.textAppliedThemesEmpty.visibility = View.GONE
            return
        }
        val rev = System.currentTimeMillis()
        val (rawDirs, appliedList) =
            coroutineScope {
                val dirsJob = async(Dispatchers.IO) { AiWallpaperThemeHelper.scanAiWallpaperDirectories(ts) }
                val appliedJob = async(Dispatchers.IO) { AppliedRearThemeHelper.loadAppliedThemes(ts) }
                Pair(dirsJob.await(), appliedJob.await())
            }
        val dirUi =
            rawDirs.map {
                val dirName = it["name"].orEmpty()
                ThemeDirectoryUi(
                    name = dirName,
                    previewPath = it["previewPath"].orEmpty(),
                    listRevision = rev,
                )
            }
        directoryAdapter.submitList(dirUi)
        if (reloadAppliedThumbnails) {
            appliedAdapter.evictThumbnailMemoryCache()
            appliedAdapter.submitList(appliedList) {
                val n = appliedAdapter.itemCount
                if (n > 0) {
                    appliedAdapter.notifyItemRangeChanged(0, n)
                }
            }
        } else {
            appliedAdapter.submitList(appliedList)
        }
        binding.textAppliedThemesEmpty.visibility =
            if (appliedList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshAppliedThemes(reloadThumbnails: Boolean = false) {
        val snap = permissionViewModel.snapshot.value
        if (snap?.privileged != true) {
            appliedAdapter.submitList(emptyList())
            binding.textAppliedThemesEmpty.visibility = View.GONE
            return
        }
        val ts = taskService
        if (ts == null) {
            appliedAdapter.submitList(emptyList())
            binding.textAppliedThemesEmpty.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { AppliedRearThemeHelper.loadAppliedThemes(ts) }
            if (reloadThumbnails) {
                appliedAdapter.evictThumbnailMemoryCache()
                appliedAdapter.submitList(list) {
                    val n = appliedAdapter.itemCount
                    if (n > 0) {
                        appliedAdapter.notifyItemRangeChanged(0, n)
                    }
                }
            } else {
                appliedAdapter.submitList(list)
            }
            binding.textAppliedThemesEmpty.visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
            refreshDirectoryList()
        }
    }

    private fun onAppliedThemeLongClick(item: AppliedRearTheme) {
        val snap = permissionViewModel.snapshot.value
        if (snap?.privileged != true) {
            snack(R.string.theme_need_privilege)
            return
        }
        if (taskService == null) {
            snack(R.string.theme_applied_delete_fail)
            return
        }
        val ctx = requireContext()
        val names =
            arrayOf(
                getString(R.string.theme_applied_delete),
            )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(item.resName)
            .setItems(names) { _, which ->
                when (which) {
                    0 -> confirmDeleteAppliedTheme(item)
                }
            }
            .show()
    }

    private fun confirmDeleteAppliedTheme(item: AppliedRearTheme) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.resName)
            .setMessage(getString(R.string.theme_applied_delete_confirm, item.resName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.theme_applied_delete) { _, _ ->
                val ts = taskService ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            AppliedRearThemeHelper.deleteAppliedTheme(ts, item)
                        }
                    if (ok) {
                        snack(R.string.theme_applied_delete_ok, longDuration = true)
                        refreshAppliedThemes()
                    } else {
                        snack(R.string.theme_applied_delete_fail, longDuration = true)
                    }
                }
            }
            .show()
    }

    private fun gestureInjectFailureMessage(outcome: GestureInjectOutcome): Int =
        when (outcome) {
            GestureInjectOutcome.OK -> R.string.theme_inject_gesture_ok
            GestureInjectOutcome.REARSCREEN_NOT_FOUND -> R.string.theme_gesture_inject_fail_no_rearscreen
            GestureInjectOutcome.ZIP_READ_FAILED -> R.string.theme_gesture_inject_fail_write
            GestureInjectOutcome.MANIFEST_MISSING -> R.string.theme_gesture_inject_fail_manifest_missing
            GestureInjectOutcome.MANIFEST_INCOMPATIBLE -> R.string.theme_gesture_inject_fail_manifest_incompatible
            GestureInjectOutcome.WRITE_FAILED -> R.string.theme_gesture_inject_fail_write
        }

    override fun onDestroyView() {
        _binding = null
        lastPrivilegedForAiDirRefresh = null
        super.onDestroyView()
    }

    companion object {
        private const val PREFS_NAME = "miroot_theme"
        private const val KEY_LOOP = "video_loop"
        private const val KEY_SOUND = "video_sound"
        private const val KEY_VOLUME = "video_volume"
        private const val KEY_AUTO_THEME = "auto_open_theme_manager"

        private val META_PLACEHOLDER_AUTHOR =
            setOf("未知", "未知作者", "未填写", "文件未知")
        private val META_PLACEHOLDER_NAME =
            setOf("未知", "未命名", "未命名主题", "未知主题", "文件未知", "未填写")
        private val META_PLACEHOLDER_DESC =
            setOf("无描述", "未知", "暂无描述", "文件未知", "未填写", "-", "—")
    }
}