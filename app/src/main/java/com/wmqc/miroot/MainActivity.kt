package com.wmqc.miroot
import com.wmqc.miroot.display.MainDisplayUi

import com.wmqc.miroot.lyrics.LogHelper
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.license.OfflineActivationRepository
import com.wmqc.miroot.capability.PermissionCache
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.charging.ChargingServiceSync
import com.wmqc.miroot.lyrics.MusicProjectionPublicBroadcast
import com.wmqc.miroot.ui.music.MusicAutoProjectionSync
import com.wmqc.miroot.databinding.ActivityMainBinding
import com.wmqc.miroot.ui.main.MainPagerAdapter
import com.wmqc.miroot.license.OfflineActivationDialogFragment
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import android.widget.Toast
import rikka.shizuku.Shizuku

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 8721

class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityMainBinding
    private val permissionViewModel: MainPermissionViewModel by viewModels()

    private var syncingNavFromPager = false

    // Binder 异步就绪：须在收到/断开时重新探测，避免冷启动误判未授权（Shizuku.addBinderReceivedListener）。
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        PermissionCache.onShizukuBinderReceived()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        PermissionCache.onShizukuBinderDead()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WelcomeIntro.migratePostActivationLegacyIfNeeded(this)
        instance = this
        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        PermissionCache.refresh()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mainPager.updatePadding(top = bars.top)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            windowInsets
        }

        binding.mainPager.adapter = MainPagerAdapter(this)
        // 1 = 当前±1 页常驻；设为 5 时五 Tab 全常驻，内存与初始化开销更大。
        binding.mainPager.offscreenPageLimit = 1
        // 防止 ViewPager2 恢复到旧状态的 position（历史上曾有 PAGE_MORE=4）
        if (binding.mainPager.currentItem > 4) {
            binding.mainPager.setCurrentItem(PAGE_STATUS, false)
        }
        syncActivationGate()

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (syncingNavFromPager) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_status -> 0
                R.id.nav_features -> 1
                R.id.nav_apps -> 2
                R.id.nav_music -> 3
                R.id.nav_theme -> 4
                else -> return@setOnItemSelectedListener false
            }
            if (!canOpenPage(target)) {
                openActivationTabWithHint()
                return@setOnItemSelectedListener false
            }
            if (binding.mainPager.currentItem != target) {
                binding.mainPager.setCurrentItem(target, false)
            }
            true
        }

        binding.mainPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    syncingNavFromPager = true
                    val id = when (position) {
                        0 -> R.id.nav_status
                        1 -> R.id.nav_features
                        2 -> R.id.nav_apps
                        3 -> R.id.nav_music
                        4 -> R.id.nav_theme
                        else -> R.id.nav_status
                    }
                    if (binding.bottomNav.selectedItemId != id) {
                        binding.bottomNav.selectedItemId = id
                    }
                    syncingNavFromPager = false
                    maybeShowFirstTabGuide(position)
                }
            },
        )

        permissionViewModel.snapshot.observe(this) { snap ->
            ChargingServiceSync.sync(this@MainActivity, snap.privileged)
        }

        deferPostFirstFrameInit()

        binding.root.post {
            if (!isFinishing) {
                if (OfflineActivationRepository.isActivated(this)) {
                    WelcomeIntro.showIfNeeded(this)
                } else {
                    maybeShowActivationDialog()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        permissionViewModel.refresh()
        syncActivationGate()
        maybeShowActivationDialog()
        if (!OfflineActivationRepository.isActivated(this)) {
            binding.root.post { prefillActivationCodeFromClipboardOnce() }
        }
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            permissionViewModel.refresh()
        }
    }

    /** 供背屏歌词 Activity（Java）在无法连接 TaskService 时回退执行 shell。 */
    fun executeShellCommand(cmd: String) {
        Thread({ PrivilegedShell.execCmd(cmd) }, "miroot-main-shell").start()
    }

    /** 通知主页音乐投屏状态（Java 侧调用）；音乐页可注册 [ACTION_MUSIC_PROJECTION_STATE] 广播同步 UI。 */
    fun notifyMusicProjectionStatusChanged(isRunning: Boolean) {
        sendMusicProjectionStateBroadcast(this, isRunning)
    }

    fun refreshActivationUi() {
        syncActivationGate()
    }

    private fun syncActivationGate() {
        val unlocked = OfflineActivationRepository.isActivated(this)
        binding.mainPager.isUserInputEnabled = unlocked
        if (!unlocked && !canOpenPage(binding.mainPager.currentItem)) {
            binding.mainPager.setCurrentItem(PAGE_STATUS, false)
        }
    }

    private fun canOpenPage(position: Int): Boolean {
        if (OfflineActivationRepository.isActivated(this)) return true
        return position == PAGE_STATUS
    }

    private fun openActivationTabWithHint() {
        MainDisplayUi.showToast(this, R.string.activation_required_to_use, Toast.LENGTH_SHORT)
        if (binding.mainPager.currentItem != PAGE_STATUS) {
            binding.mainPager.setCurrentItem(PAGE_STATUS, false)
        }
    }

    private fun maybeShowActivationDialog() {
        if (OfflineActivationRepository.isActivated(this)) return
        val tag = OfflineActivationDialogFragment.TAG
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        OfflineActivationDialogFragment().show(supportFragmentManager, tag)
    }

    private fun maybeShowFirstTabGuide(position: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (position == PAGE_THEME) {
            if (prefs.getBoolean(KEY_TAB_GUIDE_THEME, false)) return
            prefs.edit().putBoolean(KEY_TAB_GUIDE_THEME, true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme_cover_bind_intro_title)
                .setMessage(R.string.theme_cover_bind_intro_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val (prefKey, messageRes) =
            when (position) {
                PAGE_STATUS -> KEY_TAB_GUIDE_STATUS to R.string.tab_guide_status
                PAGE_FEATURES -> KEY_TAB_GUIDE_FEATURES to R.string.tab_guide_features
                PAGE_APPS -> KEY_TAB_GUIDE_APPS to R.string.tab_guide_apps
                PAGE_MUSIC -> KEY_TAB_GUIDE_MUSIC to R.string.tab_guide_music
                else -> return
            }
        if (prefs.getBoolean(prefKey, false)) return
        prefs.edit().putBoolean(prefKey, true).apply()
        MainDisplayUi.showToast(this, messageRes, android.widget.Toast.LENGTH_LONG)
    }

    private fun prefillActivationCodeFromClipboardOnce() {
        if (OfflineActivationRepository.isActivated(this)) return

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (!cm.hasPrimaryClip()) return
        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (clipText.isBlank()) return

        val deviceCode = OfflineActivationRepository.getOrCreateDeviceCode(this)
        if (!OfflineActivationRepository.verifyActivationCode(deviceCode, clipText)) return

        supportFragmentManager.executePendingTransactions()
        val dialog = supportFragmentManager.findFragmentByTag(OfflineActivationDialogFragment.TAG)
            as? OfflineActivationDialogFragment ?: return
        dialog.prefillActivationCode(clipText)
    }

    /**
     * 首屏优先：把潜在耗时初始化延后到首帧之后，降低冷启动卡顿和安装后首次拉起的“被杀感知”。
     */
    private fun deferPostFirstFrameInit() {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            permissionViewModel.refresh()
            MusicAutoProjectionSync.sync(this)
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        } catch (t: Throwable) {
            LogHelper.e(TAG, "Shizuku removeBinderReceivedListener failed", t)
        }
        try {
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (t: Throwable) {
            LogHelper.e(TAG, "Shizuku removeBinderDeadListener failed", t)
        }
        try {
            Shizuku.removeRequestPermissionResultListener(this)
        } catch (t: Throwable) {
            LogHelper.e(TAG, "Shizuku removeRequestPermissionResultListener failed", t)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "miroot_app_prefs"
        private const val PAGE_STATUS = 0
        private const val PAGE_FEATURES = 1
        private const val PAGE_APPS = 2
        private const val PAGE_MUSIC = 3
        private const val PAGE_THEME = 4
        private const val KEY_TAB_GUIDE_STATUS = "tab_guide_seen_status"
        private const val KEY_TAB_GUIDE_FEATURES = "tab_guide_seen_features"
        private const val KEY_TAB_GUIDE_APPS = "tab_guide_seen_apps"
        private const val KEY_TAB_GUIDE_MUSIC = "tab_guide_seen_music"
        private const val KEY_TAB_GUIDE_THEME = "tab_guide_seen_theme"

        const val ACTION_MUSIC_PROJECTION_STATE: String = "com.wmqc.miroot.internal.MUSIC_PROJECTION_STATE"
        const val EXTRA_MUSIC_PROJECTION_RUNNING: String = "running"

        /** 不依赖 MainActivity 实例存活；背屏结束投屏时主界面可能已销毁，仍须发出广播。 */
        @JvmStatic
        fun sendMusicProjectionStateBroadcast(context: Context, isRunning: Boolean) {
            val app = context.applicationContext
            app.sendBroadcast(
                Intent(ACTION_MUSIC_PROJECTION_STATE).apply {
                    setPackage(app.packageName)
                    putExtra(EXTRA_MUSIC_PROJECTION_RUNNING, isRunning)
                },
            )
            MusicProjectionPublicBroadcast.sendStateChanged(app, isRunning)
        }

        @Volatile
        private var instance: MainActivity? = null

        @JvmStatic
        fun getCurrentInstance(): MainActivity? = instance
    }
}
