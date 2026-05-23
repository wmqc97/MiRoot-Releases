package com.wmqc.miroot.ui.music
import com.wmqc.miroot.display.MainDisplayUi

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wmqc.miroot.R
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.lyrics.JiebaTokenizerEngine
import com.wmqc.miroot.service.MiRootNotificationListenerService
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import com.wmqc.miroot.lyrics.LogHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method

class MusicFragment : Fragment() {

    private val permissionViewModel: MainPermissionViewModel by activityViewModels()
    private val musicViewModel: MusicViewModel by viewModels()

    private var appContext: Context? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        appContext = context.applicationContext
    }

    override fun onDetach() {
        appContext = null
        super.onDetach()
    }

    private val pickProjectionFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val path = LyricsFontImporter.copyImportedFont(requireContext(), uri, LyricsFontImporter.Slot.PROJECTION)
        if (path != null) {
            musicViewModel.applyProjectionCustomFont(path)
        } else {
            MainDisplayUi.showToast(requireContext(), R.string.music_font_import_failed, Toast.LENGTH_SHORT)
        }
    }

    private val pickLyricsDictLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = runCatching {
            JiebaTokenizerEngine.importUserDictionary(requireContext(), uri)
        }
        result.onSuccess { data ->
            MainDisplayUi.showToast(
                requireContext(),
                getString(
                    R.string.music_lyrics_dict_import_success,
                    data.mergedWords,
                    data.totalWords,
                ),
                Toast.LENGTH_LONG,
            )
        }.onFailure {
            MainDisplayUi.showToast(
                requireContext(),
                getString(R.string.music_lyrics_dict_import_failed, it.message ?: "unknown"),
                Toast.LENGTH_LONG,
            )
        }
    }

    private val projectionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MainActivity.ACTION_MUSIC_PROJECTION_STATE) return
                val running = intent.getBooleanExtra(MainActivity.EXTRA_MUSIC_PROJECTION_RUNNING, false)
                musicViewModel.applyProjectionBroadcast(running)
            }
        }

    /** 系统媒体会话变化时立即刷新，避免停留在「未检测到播放」直到重启应用 */
    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener {
            appContext?.let { musicViewModel.refreshMusicControlProbe(it) }
        }

    private var activeSessionsListenerRegistered = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val snap by permissionViewModel.snapshot.observeAsState(PermissionSnapshot.initial())
                val privileged = snap?.privileged == true
                val ctx = requireContext()
                val listenerOk by musicViewModel.notificationListenerEnabled.collectAsState()
                val hasPlayer by musicViewModel.hasActiveMediaSession.collectAsState()
                val projecting by musicViewModel.projecting.collectAsState()
                val settings by musicViewModel.settings.collectAsState()

                MusicScreen(
                    privileged = privileged,
                    rootAvailable = snap?.root == true,
                    listenerEnabled = listenerOk,
                    hasPlayer = hasPlayer,
                    projecting = projecting,
                    settings = settings,
                    onSettingsChange = { musicViewModel.setSettings(it) },
                    onOpenListenerSettings = { MusicProjectionController.openNotificationListenerSettings(ctx) },
                    onStart = {
                        musicViewModel.refreshPrivilege()
                        MusicProjectionController.start(ctx, directRearOnly = true)
                        view?.postDelayed({ musicViewModel.refreshProjectionState() }, 900)
                    },
                    onStop = {
                        MusicProjectionController.stop(ctx)
                        view?.postDelayed({ musicViewModel.refreshProjectionState() }, 400)
                    },
                    onPickProjectionFont = {
                        pickProjectionFontLauncher.launch(FONT_PICKER_MIME_TYPES)
                    },
                    onImportLyricsDict = {
                        pickLyricsDictLauncher.launch(DICT_PICKER_MIME_TYPES)
                    },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        musicViewModel.refreshProjectionState()
        musicViewModel.refreshMusicControlProbe(requireContext().applicationContext)
        permissionViewModel.snapshot.observe(viewLifecycleOwner) {
            musicViewModel.refreshPrivilege()
        }
        // 部分机型 OnActiveSessionsChanged 偶发不回调：RESUMED 期间短周期补探测
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                musicViewModel.refreshMusicControlProbe(requireContext().applicationContext)
                while (isActive) {
                    delay(ACTIVE_SESSION_POLL_MS)
                    musicViewModel.refreshMusicControlProbe(requireContext().applicationContext)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter(MainActivity.ACTION_MUSIC_PROJECTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                requireContext(),
                projectionReceiver,
                f,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            requireContext().registerReceiver(projectionReceiver, f)
        }
        registerActiveSessionsListener()
        musicViewModel.refreshMusicControlProbe(requireContext().applicationContext)
    }

    override fun onStop() {
        unregisterActiveSessionsListener()
        requireContext().unregisterReceiver(projectionReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        musicViewModel.refreshProjectionState()
        musicViewModel.reloadSettings()
        musicViewModel.refreshMusicControlProbe(requireContext().applicationContext)
    }

    private fun registerActiveSessionsListener() {
        if (activeSessionsListenerRegistered) return
        try {
            val msm = requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(requireContext(), MiRootNotificationListenerService::class.java)
            val handler = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 36) {
                // compileSdk 36 起三参签名为 (listener, component, handler)
                msm.addOnActiveSessionsChangedListener(activeSessionsChangedListener, cn, handler)
            } else {
                // API 28～35 框架仍为 (component, listener, handler)，已从 API 36 源码存根移除，需反射
                val m: Method = MediaSessionManager::class.java.getMethod(
                    "addOnActiveSessionsChangedListener",
                    ComponentName::class.java,
                    MediaSessionManager.OnActiveSessionsChangedListener::class.java,
                    Handler::class.java,
                )
                m.invoke(msm, cn, activeSessionsChangedListener, handler)
            }
            activeSessionsListenerRegistered = true
        } catch (_: SecurityException) {
            // 未开通知使用权时 getActiveSessions 也会失败，轮询/onResume 仍会更新为 false
        } catch (e: Exception) {
            LogHelper.w("MusicFragment", "注册媒体会话监听失败（将依赖定时探测）: ${e.message}")
        }
    }

    private fun unregisterActiveSessionsListener() {
        if (!activeSessionsListenerRegistered) return
        activeSessionsListenerRegistered = false
        runCatching {
            val msm = requireContext().getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
        }
    }

    private companion object {
        private const val ACTIVE_SESSION_POLL_MS = 2_000L

        private val FONT_PICKER_MIME_TYPES = arrayOf(
            "font/*",
            "application/x-font-ttf",
            "application/x-font-otf",
            "application/x-font-truetype",
            "application/x-font-opentype",
            "application/octet-stream",
            "*/*",
        )
        private val DICT_PICKER_MIME_TYPES = arrayOf(
            "text/plain",
            "text/*",
            "application/octet-stream",
            "*/*",
        )
    }
}
