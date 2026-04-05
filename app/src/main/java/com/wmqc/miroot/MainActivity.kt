package com.wmqc.miroot

import com.wmqc.miroot.lyrics.LogHelper
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
import com.wmqc.miroot.RearDisplayInputHelper
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.charging.ChargingServiceSync
import com.wmqc.miroot.ui.music.MusicAutoProjectionSync
import com.wmqc.miroot.databinding.ActivityMainBinding
import com.wmqc.miroot.ui.main.MainPagerAdapter
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import rikka.shizuku.Shizuku

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 8721

class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityMainBinding
    private val permissionViewModel: MainPermissionViewModel by viewModels()

    private var syncingNavFromPager = false

    // Binder 异步就绪：须在收到/断开时重新探测，避免冷启动误判未授权（Shizuku.addBinderReceivedListener）。
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        permissionViewModel.refresh()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        permissionViewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

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
        binding.mainPager.offscreenPageLimit = 4

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (syncingNavFromPager) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_status -> 0
                R.id.nav_features -> 1
                R.id.nav_music -> 2
                R.id.nav_theme -> 3
                else -> return@setOnItemSelectedListener false
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
                        2 -> R.id.nav_music
                        else -> R.id.nav_theme
                    }
                    if (binding.bottomNav.selectedItemId != id) {
                        binding.bottomNav.selectedItemId = id
                    }
                    syncingNavFromPager = false
                }
            },
        )

        permissionViewModel.snapshot.observe(this) { snap ->
            ChargingServiceSync.sync(this@MainActivity, snap.privileged)
        }

        permissionViewModel.refresh()
        MusicAutoProjectionSync.sync(this)

        binding.root.post {
            if (!isFinishing) {
                WelcomeIntro.showIfNeeded(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this)
        permissionViewModel.refresh()
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
        Thread({ PrivilegedShell.runAndWait(cmd) }, "miroot-main-shell").start()
    }

    /** 通知主页音乐投屏状态（Java 侧调用）；音乐页可注册 [ACTION_MUSIC_PROJECTION_STATE] 广播同步 UI。 */
    fun notifyMusicProjectionStatusChanged(isRunning: Boolean) {
        sendMusicProjectionStateBroadcast(this, isRunning)
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
        }

        @Volatile
        private var instance: MainActivity? = null

        @JvmStatic
        fun getCurrentInstance(): MainActivity? = instance
    }
}
