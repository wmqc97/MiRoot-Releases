package com.wmqc.miroot.record
import com.wmqc.miroot.display.MainDisplayUi

import com.wmqc.miroot.lyrics.LogHelper
import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.color.MaterialColors
import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.R
import com.wmqc.miroot.license.OfflineActivationRepository
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.rear.RearAssistPrefs
import com.wmqc.miroot.capability.PermissionCache
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.capability.RuntimePermissionGate
import com.wmqc.miroot.shell.DeviceGeometry
import com.wmqc.miroot.shell.PublicMediaExport
import com.wmqc.miroot.shell.ShellMedia3Export
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 背屏录屏：前台服务 + 悬浮窗，[PrivilegedShell] 调 `screenrecord`（**仅画面**，不带 `--audio`）。
 * [MediaProjectionRequestActivity] 授权 → 启动 `screenrecord` 并确认进程/文件
 * → **再** 在已处于「录制中」状态下启动 [AudioCaptureHelper]（Playback Capture → PCM）。
 * 原始视频经特权 shell 写入 [RecordPaths.SHELL_CAPTURE_DIR]；PID 在 [RecordPaths.SHELL_WORK_DIR]。
 *
 * **合成**：音轨合并与带壳均使用 **AndroidX Media3**（[ShellMedia3Export]），不使用 FFmpeg。
 */
class RearScreenRecordService : Service() {

    private lateinit var wm: WindowManager
    private var floatRoot: View? = null
    private var recordBtn: View? = null
    private var closeBtn: View? = null
    private var switchAudio: SwitchCompat? = null
    private var switchComposite: SwitchCompat? = null
    private var switchSticker: SwitchCompat? = null

    private val stateLock = Any()
    @Volatile
    private var isRecording = false
    @Volatile
    private var isStarting = false
    @Volatile
    private var isStopping = false
    private var currentVideoPath: String? = null
    @Volatile
    private var currentCaptureTs: String? = null
    @Volatile
    private var recordPid = -1
    private var currentPcmPath: String? = null

    /** 开始录制成功时快照；停止后带壳/保存路径依赖此值 */
    @Volatile
    private var sessionCompositeEnabled: Boolean = true
    /** 本会话是否实际在录 PCM（投影 + AudioCaptureHelper 成功启动） */
    @Volatile
    private var sessionPcmCapture: Boolean = false
    /** 本次点击录制时是否期望内录（以悬浮窗实时开关为准）。 */
    @Volatile
    private var sessionAudioWanted: Boolean = false
    private var audioCaptureHelper: AudioCaptureHelper? = null
    private var currentMediaProjection: MediaProjection? = null

    @Volatile
    private var projectionPending: Boolean = false
    private var projectionTimeoutRunnable: Runnable? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var stopWorker: Thread? = null
    @Volatile
    private var lastCopyUsedContentResolver = false

    /**
     * 录屏专属常亮：仅读取「录屏/截图常亮」开关；与投屏常亮设置逻辑分离。
     * 仅当 [RearScreenWakeService] 未在跑投屏唤醒循环时由本服务定时唤醒（避免与投屏重复）。
     * 须在后台线程执行 [PrivilegedShell.runAndWait]，不可阻塞主线程。
     */
    private var wakeHandlerThread: HandlerThread? = null
    private var wakeHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            val fg = buildNotification()
            // API 34+：未持有 MediaProjection 时不得对 startForeground 声明 mediaProjection 类型，否则会
            // SecurityException → stopSelf()，功能页/磁贴「打开悬浮窗」表现为完全无反应。带内录时在
            // [startRecordingInternal] 取得投影后再升级为 SPECIAL_USE|MEDIA_PROJECTION|MICROPHONE（内录走
            // AudioRecord，系统要求 microphone 前台类型）；停止后见 [stopRecordingInternal]。
            val fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                fg,
                fgsTypes,
            )
        } catch (t: Throwable) {
            LogHelper.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }
        // 兜底：未激活时不要拉起悬浮窗（startForeground 已满足系统要求，但功能页仍然需要激活门禁）。
        if (!OfflineActivationRepository.isActivated(this)) {
            stopSelf()
            return
        }
        try {
            showFloat()
        } catch (t: Throwable) {
            LogHelper.e(TAG, "showFloat failed", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兜底：若外部直接发起“启动录屏”指令且未激活，则拒绝执行。
        if (intent != null &&
            (intent.action == ACTION_START_WITH_PROJECTION || intent.action == ACTION_START_VIDEO_ONLY)
        ) {
            if (!OfflineActivationRepository.isActivated(this)) {
                toastMain(getString(R.string.activation_required_to_use))
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (intent.action) {
                ACTION_START_WITH_PROJECTION -> {
                    cancelProjectionTimeout()
                    synchronized(stateLock) { projectionPending = false }
                    val code = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)
                    }
                    // API 34+：系统要求先以 MEDIA_PROJECTION（及内录所需 microphone）类型 startForeground，再
                    // getMediaProjection；否则 SecurityException（见 log: Media projections require a foreground
                    // service of type ...MEDIA_PROJECTION）。
                    val projection = if (code == Activity.RESULT_OK && data != null) {
                        if (Build.VERSION.SDK_INT >= 34) {
                            try {
                                ensureForegroundBeforeProjection()
                            } catch (t: Throwable) {
                                RecordSynthDebugLog.diagW(
                                    "onStartCommand: FGS before getMediaProjection failed: ${t.message}",
                                    t,
                                )
                                mainHandler.post {
                                    toastMain(
                                        getString(
                                            R.string.record_float_fail,
                                            t.message ?: "",
                                        ),
                                    )
                                }
                                restoreForegroundSpecialUseOnly()
                                return START_STICKY
                            }
                        }
                        try {
                            val mgr =
                                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mgr.getMediaProjection(code, data).also { p ->
                                if (p == null) {
                                    RecordSynthDebugLog.diagW(
                                        "onStartCommand: getMediaProjection returned null (resultOk, data ok)",
                                    )
                                }
                            }
                        } catch (e: SecurityException) {
                            RecordSynthDebugLog.diagW(
                                "onStartCommand: getMediaProjection SecurityException: ${e.message}",
                                e,
                            )
                            restoreForegroundSpecialUseOnly()
                            null
                        }
                    } else {
                        null
                    }
                    val r = recordBtn ?: return START_STICKY
                    val c = closeBtn ?: return START_STICKY
                    if (projection == null && sessionAudioWanted()) {
                        restoreForegroundSpecialUseOnly()
                        mainHandler.post {
                            toastMain(getString(R.string.record_projection_failed))
                        }
                        return START_STICKY
                    }
                    startRecordingInternal(r, c, projection)
                }
                ACTION_PROJECTION_CANCELLED -> {
                    cancelProjectionTimeout()
                    synchronized(stateLock) { projectionPending = false }
                    mainHandler.post {
                        toastMain(getString(R.string.record_projection_cancelled))
                    }
                    RecordSynthDebugLog.diagI("onStartCommand: user cancelled MediaProjection, record not started")
                }
                ACTION_START_VIDEO_ONLY -> {
                    cancelProjectionTimeout()
                    synchronized(stateLock) { projectionPending = false }
                    val r = recordBtn ?: return START_STICKY
                    val c = closeBtn ?: return START_STICKY
                    startRecordingInternal(r, c, null)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelWakeup()
        try {
            audioCaptureHelper?.stop()
        } catch (_: Exception) {
        }
        audioCaptureHelper = null
        stopAndReleaseMediaProjection()
        val w = stopWorker
        if (w != null) {
            try {
                w.join(6000)
            } catch (_: InterruptedException) {
            }
        } else if (isRecording || isStarting || isStopping) {
            try {
                stopScreenrecordGracefully()
            } catch (_: Exception) {
            }
            // 紧急保存：QS 磁贴直接停服务时，尝试保存当前录制的视频
            if (currentVideoPath != null) {
                try {
                    Thread.sleep(300) // 等 screenrecord 写完文件头
                    val workPath = currentVideoPath!!
                    val fileExists = PrivilegedShell.execCmd("test -f \"$workPath\"")
                    val fileSize = PrivilegedShell.captureOutput("stat -c %s \"$workPath\" 2>/dev/null")?.trim()?.toLongOrNull() ?: 0L
                    if (fileExists && fileSize > 0L) {
                        val workFile = File(workPath)
                        // file already in Movies, just mediaScan (like MRSS)
                        mediaScan(workFile)
                        RecordSynthDebugLog.diagI(
                            "onDestroy: emergency scan OK path=${workFile.absolutePath} size=$fileSize"
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }
        instance = null
        floatRoot?.let { v ->
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        floatRoot = null
        super.onDestroy()
    }

    private fun showFloat() {
        val ui = ContextThemeWrapper(this, R.style.Theme_MiRoot)
        val density = resources.displayMetrics.density
        val surface = MaterialColors.getColor(
            ui,
            com.google.android.material.R.attr.colorSurfaceContainer,
            Color.WHITE,
        )
        val outline = MaterialColors.getColor(
            ui,
            com.google.android.material.R.attr.colorOutlineVariant,
            0x14000000,
        )
        val onSurface = MaterialColors.getColor(
            ui,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )

        val outer = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (density * 4).toInt()
            val padV = (density * 2).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(surface)
                cornerRadius = density * 8
                setStroke(maxOf(1, (density * 1).toInt()), outline)
            }
        }

        val inner = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = ImageView(ui).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(onSurface))
            val s = (density * 30).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (density * 10).toInt()
            }
            setOnClickListener {
                if (isRecording) {
                    toastMain(getString(R.string.record_stop_first))
                } else {
                    stopSelf()
                }
            }
        }
        closeBtn = close

        val record = View(ui).apply {
            val s = (density * 32).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                try {
                    if (isStopping) {
                        toastMain(getString(R.string.record_busy))
                        return@setOnClickListener
                    }
                    if (isRecording) {
                        stopRecordingInternal(this, close)
                        return@setOnClickListener
                    }
                    if (isStarting) {
                        toastMain(getString(R.string.record_busy))
                        return@setOnClickListener
                    }
                    val wantsAudioNow = sessionAudioWanted()
                    RecordSynthDebugLog.diagI(
                        "record click: wantsAudioNow=$wantsAudioNow sdk=${Build.VERSION.SDK_INT}",
                    )
                    if (wantsAudioNow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (!RuntimePermissionGate.hasRecordAudio(this@RearScreenRecordService)) {
                            toastMain(getString(R.string.record_need_record_audio))
                            try {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            } catch (_: Exception) {
                            }
                            return@setOnClickListener
                        }
                        requestMediaProjectionThenStart(this, close)
                    } else {
                        startRecordingInternal(this, close, null)
                    }
                } catch (t: Throwable) {
                    RecordSynthDebugLog.diagW("record click failed: ${t.message}", t)
                    synchronized(stateLock) {
                        isStarting = false
                        projectionPending = false
                    }
                    toastMain(getString(R.string.record_float_fail, t.message ?: ""))
                }
            }
        }
        recordBtn = record
        setRecordUiRecording(false)

        val leftCol = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        leftCol.addView(close)
        leftCol.addView(record)

        val gap = (density * 4).toInt()
        val switchesRow = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // 三行开关之间纵向间距（8dp）
        val switchRowGap = (density * 8).toInt()
        fun switchRowLp(last: Boolean) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (!last) bottomMargin = switchRowGap
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (rowA, swA) = labeledSwitch(
                ui,
                getString(R.string.record_switch_audio),
                audioEnabled(),
            ) { on -> setAudioPref(on) }
            switchAudio = swA
            switchesRow.addView(rowA, switchRowLp(last = false))
        }
        val (rowC, swC) = labeledSwitch(
            ui,
            getString(R.string.record_switch_composite),
            compositeEnabled(),
        ) { on -> setCompositePref(on) }
        switchComposite = swC
        switchesRow.addView(rowC, switchRowLp(last = false))
        val (rowS, swS) = labeledSwitch(
            ui,
            getString(R.string.record_switch_sticker),
            recordStickerEnabled(),
        ) { on -> setRecordStickerPref(on) }
        switchSticker = swS
        switchesRow.addView(rowS, switchRowLp(last = true))

        inner.addView(
            leftCol,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = gap },
        )
        inner.addView(switchesRow)
        outer.addView(inner)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (density * 20).toInt()
            y = (density * 100).toInt()
        }

        var touchParams = params
        val slop = ViewConfiguration.get(ui).scaledTouchSlop * 2
        data class DragState(val ix: Int, val iy: Int, val fx: Float, val fy: Float, var dragging: Boolean)
        outer.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (touchHitsInteractiveChild(outer, ev.x, ev.y)) {
                        outer.tag = null
                        return@setOnTouchListener false
                    }
                    touchParams = floatRoot?.layoutParams as? WindowManager.LayoutParams ?: touchParams
                    outer.tag = DragState(touchParams.x, touchParams.y, ev.rawX, ev.rawY, false)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val st = outer.tag as? DragState ?: return@setOnTouchListener false
                    if (!st.dragging) {
                        if (abs(ev.rawX - st.fx) > slop || abs(ev.rawY - st.fy) > slop) {
                            st.dragging = true
                        }
                    }
                    if (st.dragging) {
                        val dx = (ev.rawX - st.fx).toInt()
                        val dy = (ev.rawY - st.fy).toInt()
                        // TOP|END：x 为距屏幕「末端」边的偏移，与 rawX 同向增量会使窗口反向移动（LTR 末端为右缘）。
                        val rtl =
                            outer.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                        touchParams.x = st.ix + if (rtl) dx else -dx
                        touchParams.y = st.iy + dy
                        try {
                            wm.updateViewLayout(outer, touchParams)
                        } catch (_: Exception) {
                        }
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    outer.tag = null
                    false
                }
                else -> false
            }
        }

        try {
            wm.addView(outer, params)
            floatRoot = outer
        } catch (e: Exception) {
            toastMain(getString(R.string.record_float_fail, e.message ?: ""))
        }
    }

    /** 仅在触摸落到可交互控件时放行点击；其余区域允许触发悬浮窗拖动。 */
    private fun touchHitsInteractiveChild(root: View, x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x > root.width || y > root.height) return false
        if (isInteractiveControl(root)) return true
        if (root !is ViewGroup) return false
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val localX = x - child.left
            val localY = y - child.top
            if (localX < 0f || localY < 0f || localX > child.width || localY > child.height) continue
            if (touchHitsInteractiveChild(child, localX, localY)) {
                return true
            }
        }
        return false
    }

    private fun isInteractiveControl(view: View): Boolean {
        return view.isClickable ||
            view.isLongClickable ||
            view.hasOnClickListeners() ||
            view is SwitchCompat
    }

    private fun labeledSwitch(
        ctx: Context,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): Pair<LinearLayout, SwitchCompat> {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
        }
        val labelColor = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val tv = android.widget.TextView(ctx).apply {
            text = label
            setTextColor(labelColor)
            textSize = 10f
            includeFontPadding = false
        }
        val sw = SwitchCompat(ctx).apply {
            isChecked = initial
            setShowText(false)
            setOnCheckedChangeListener { _, c -> onChange(c) }
            val compactH = (d * 16).toInt()
            minimumHeight = compactH
            minHeight = compactH
            setSwitchPadding(0)
            // 去掉主题自带的点按波纹/透明圆底，避免视觉上撑大行距
            background = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground = null
            }
        }
        row.addView(tv)
        row.addView(sw)
        return Pair(row, sw)
    }

    private fun setRecordUiRecording(recording: Boolean) {
        val btn = recordBtn ?: return
        val lp = btn.layoutParams
        val sizePx = if (lp.width > 0) lp.width else (resources.displayMetrics.density * 32).toInt()
        val stroke = (sizePx / 22).coerceAtLeast(2)
        val bg = GradientDrawable()
        if (recording) {
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = sizePx * 0.2f
            bg.setColor(Color.RED)
        } else {
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.RED)
        }
        bg.setStroke(stroke, Color.WHITE)
        btn.background = bg
    }

    /**
     * 取消投影授权超时定时器，配合 [projectionTimeoutRunnable] 使用。
     */
    private fun cancelProjectionTimeout() {
        projectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        projectionTimeoutRunnable = null
    }

    private fun requestMediaProjectionThenStart(recordView: View, closeView: View) {
        synchronized(stateLock) {
            if (isRecording || isStarting || isStopping || projectionPending) {
                toastMain(getString(R.string.record_busy))
                return
            }
            projectionPending = true
        }
        cancelProjectionTimeout()
        // 超时保护：若 MediaProjectionRequestActivity 被杀/无回调，30s 后自动释放锁
        val r = Runnable {
            synchronized(stateLock) {
                if (projectionPending) {
                    projectionPending = false
                    RecordSynthDebugLog.diagW("projectionPending timeout — auto released")
                }
            }
        }
        projectionTimeoutRunnable = r
        mainHandler.postDelayed(r, 30_000L)
        val req = Intent(this, MediaProjectionRequestActivity::class.java)
        req.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_HISTORY,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val opts = ActivityOptions.makeBasic()
                opts.launchDisplayId = android.view.Display.DEFAULT_DISPLAY
                startActivity(req, opts.toBundle())
            } else {
                startActivity(req)
            }
        } catch (t: Throwable) {
            cancelProjectionTimeout()
            synchronized(stateLock) { projectionPending = false }
            toastMain(getString(R.string.record_float_fail, t.message ?: ""))
        }
    }

    private fun startRecordingInternal(
        recordView: View,
        closeView: View,
        mediaProjection: MediaProjection?,
    ) {
        synchronized(stateLock) {
            if (isRecording || isStarting || isStopping) {
                toastMain(getString(R.string.record_busy))
                return
            }
            isStarting = true
        }
        RecordSynthDebugLog.diagI(
            "start: enter privilegedOk=${PermissionCache.privileged} cacheDir=${cacheDir.absolutePath} " +
                "hasProjection=${mediaProjection != null}",
        )
        if (!PermissionCache.privileged) {
            RecordSynthDebugLog.diagW("start: abort — no privileged shell (Root/Shizuku)")
            synchronized(stateLock) { isStarting = false }
            resetAfterFail(recordView, closeView)
            toastMain(getString(R.string.privilege_shell_required))
            return
        }
        AppExecutors.runInBackground {
            try {
                sessionAudioWanted = mediaProjection != null
                sessionPcmCapture = false
                // 录制前先唤醒背屏（参考 MRSS）
                PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
                Thread.sleep(200)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                currentCaptureTs = ts
                val captureDir = recordCaptureDir()
                if (!prepareShellRecordEnvironment()) {
                    failStart(recordView, closeView, getString(R.string.record_shell_workdir_fail))
                    return@runInBackground
                }
                val path = File(captureDir, "MiRoot_$ts.mp4").absolutePath
                currentVideoPath = path
                if (!verifyShellCanWritePath(path)) {
                    RecordSynthDebugLog.diagW("start: shell cannot write capture path=$path")
                    failStart(recordView, closeView, getString(R.string.record_shell_write_fail))
                    return@runInBackground
                }
                RecordSynthDebugLog.diagI("start: workPath=$path ts=$ts")

                // API 34+：须先以 mediaProjection(+microphone) 类型完成 startForeground，再采内录；否则
                // MissingForegroundServiceTypeException / 进程被系统终止。内录亦须 [RECORD_AUDIO]。
                if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    currentMediaProjection = mediaProjection
                    if (!RuntimePermissionGate.hasRecordAudio(this@RearScreenRecordService)) {
                        failStart(recordView, closeView, getString(R.string.record_need_record_audio))
                        return@runInBackground
                    }
                    val prepLatch = CountDownLatch(1)
                    var prepError: Throwable? = null
                    mainHandler.post {
                        try {
                            // FGS 已在 onStartCommand 的 ensureForegroundBeforeProjection 中升级；此处仅注册回调。
                            mediaProjection.registerCallback(
                                object : MediaProjection.Callback() {
                                    override fun onStop() {
                                        RecordSynthDebugLog.d("MediaProjection onStop")
                                    }
                                },
                                mainHandler,
                            )
                        } catch (t: Throwable) {
                            prepError = t
                            RecordSynthDebugLog.diagW("start: FGS+registerCallback failed: ${t.message}", t)
                        } finally {
                            prepLatch.countDown()
                        }
                    }
                    if (!prepLatch.await(5, TimeUnit.SECONDS)) {
                        failStart(recordView, closeView, getString(R.string.record_busy))
                        return@runInBackground
                    }
                    val err = prepError
                    if (err != null) {
                        failStart(recordView, closeView, err.message ?: "error")
                        return@runInBackground
                    }
                }

                var displayId = PrivilegedShell.captureOutput(
                    "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print \$2}'",
                )?.trim().orEmpty()
                if (displayId.isEmpty()) {
                    // 备选：dumpsys display / wm displays 解析
                    displayId = PrivilegedShell.captureOutput(
                        "dumpsys display 2>/dev/null | grep -oE 'Display\\d+' | awk 'NR==2' | grep -oE '[0-9]+'",
                    )?.trim().orEmpty()
                }
                if (displayId.isEmpty()) {
                    displayId = PrivilegedShell.captureOutput(
                        "wm displays 2>/dev/null | grep -oE 'displayId=[0-9]+' | awk -F= 'NR==2{print \$2}'",
                    )?.trim().orEmpty()
                }
                if (displayId.isEmpty()) displayId = "1"
                RecordSynthDebugLog.d("start: displayId=$displayId")

                val which = PrivilegedShell.captureOutput("which screenrecord")?.trim().orEmpty()
                if (which.isEmpty()) {
                    RecordSynthDebugLog.diagW("start: which screenrecord empty")
                    failStart(recordView, closeView, getString(R.string.record_no_screenrecord))
                    return@runInBackground
                }
                RecordSynthDebugLog.diagI("start: screenrecord bin=$which")

                if (!launchScreenrecordDetached(which, displayId, path)) {
                    RecordSynthDebugLog.diagW("start: launch failed")
                    failStart(recordView, closeView, getString(R.string.record_cmd_fail))
                    return@runInBackground
                }

                // 等 screenrecord 真正跑起来（Shizuku 启动略慢）
                Thread.sleep(250)

                var pcmStarted = false
                if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pcmPath = File(cacheDir, "MiRoot_${ts}_audio.pcm").absolutePath
                    currentPcmPath = pcmPath
                    val helper = AudioCaptureHelper(mediaProjection)
                    if (helper.start(pcmPath)) {
                        audioCaptureHelper = helper
                        pcmStarted = true
                        RecordSynthDebugLog.diagI("start: AudioCaptureHelper pcm (with video t0)=$pcmPath")
                    } else {
                        RecordSynthDebugLog.diagW("start: AudioCaptureHelper failed, video-only")
                        stopAndReleaseMediaProjection()
                        audioCaptureHelper = null
                        currentMediaProjection = null
                        currentPcmPath = null
                    }
                }

                // 轮询：持有 Process + 输出文件增长（不依赖 pid 文件 / pidof）
                var pollAttempts = 0
                val pollMax = 40
                var runningBin = false
                while (pollAttempts < pollMax) {
                    Thread.sleep(50)
                    pollAttempts++
                    runningBin = isScreenrecordActive()
                    val sizeBytes = captureFileSizeBytes(path)
                    if (runningBin && sizeBytes > 0L) break
                    if (!runningBin && sizeBytes > 0L) {
                        // 进程已退出但已有数据（极短录），仍视为启动成功
                        runningBin = true
                        break
                    }
                }
                val pidofLine = PrivilegedShell.captureOutput("pidof screenrecord 2>/dev/null").orEmpty()
                RecordSynthDebugLog.diagI(
                    "start: polled=${pollAttempts}×50ms recordPid=$recordPid " +
                        "active=$runningBin size=${captureFileSizeBytes(path)} pidof=$pidofLine",
                )

                // screenrecord 已启动但文件为 0 字节（背屏在 DOZE_SUSPEND 时收不到帧）
                if (runningBin) {
                    var fileCheckAttempts = 0
                    val fileCheckMax = 40
                    var sizeBytes = captureFileSizeBytes(path)
                    while (fileCheckAttempts < fileCheckMax && sizeBytes <= 0L) {
                        Thread.sleep(50)
                        fileCheckAttempts++
                        sizeBytes = captureFileSizeBytes(path)
                    }
                    if (sizeBytes <= 0L) {
                        RecordSynthDebugLog.diagW(
                            "start: --display-id=$displayId file still 0 bytes " +
                                "after ${fileCheckAttempts}×50ms",
                        )
                        stopScreenrecordGracefully()
                        runningBin = false
                    } else {
                        RecordSynthDebugLog.diagI(
                            "start: file growth OK size=${sizeBytes}B after ${fileCheckAttempts}×50ms",
                        )
                        PublicMediaExport.ensureAppReadable(path)
                    }
                }

                if (!runningBin) {
                    val reason = diagnoseScreenrecordStartFailure(
                        displayId,
                        path,
                        recordPid.toString(),
                        pidofLine,
                    )
                    RecordSynthDebugLog.diagW(
                        "start: process not running or 0-byte after all attempts " +
                            "(displayId=$displayId reason=$reason)",
                    )
                    failStart(
                        recordView,
                        closeView,
                        getString(R.string.record_proc_fail_reason, reason),
                        longDuration = true,
                    )
                    return@runInBackground
                }

                synchronized(stateLock) {
                    isRecording = true
                    isStarting = false
                }
                sessionCompositeEnabled = compositeEnabled()

                sessionPcmCapture = pcmStarted
                RecordSynthDebugLog.diagI(
                    "start: OK sessionComposite=$sessionCompositeEnabled sessionPcmCapture=$sessionPcmCapture",
                )
                mainHandler.post {
                    closeView.visibility = View.GONE
                    setRecordUiRecording(true)
                    setSwitchesBlockedDuringRecording(true)
                    startWakeup()
                    updateNotificationText(getString(R.string.record_notif_recording))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        sessionAudioWanted &&
                        !sessionPcmCapture
                    ) {
                        toastMain(getString(R.string.record_no_internal_audio), longDuration = true)
                    }
                }
            } catch (t: Throwable) {
                RecordSynthDebugLog.diagW("start: exception ${t.javaClass.simpleName}: ${t.message}", t)
                failStart(recordView, closeView, t.message ?: "error")
            }
        }
    }

    private fun failStart(
        recordView: View,
        closeView: View,
        msg: String,
        longDuration: Boolean = false,
    ) {
        RecordSynthDebugLog.diagW("failStart: $msg")
        cancelProjectionTimeout()
        try {
            audioCaptureHelper?.stop()
        } catch (_: Exception) {
        }
        audioCaptureHelper = null
        stopAndReleaseMediaProjection()
        stopScreenrecordGracefully()
        synchronized(stateLock) {
            isStarting = false
            isRecording = false
            projectionPending = false
        }
        currentCaptureTs = null
        currentVideoPath?.let { p ->
            try {
                File(p).delete()
            } catch (_: Exception) {
            }
            try {
                PrivilegedShell.execCmd("rm -f \"$p\"")
            } catch (_: Exception) {
            }
        }
        currentPcmPath?.let { pcm ->
            try {
                File(pcm).delete()
            } catch (_: Exception) {
            }
        }
        currentVideoPath = null
        currentPcmPath = null
        mainHandler.post {
            restoreForegroundSpecialUseOnly()
            resetAfterFail(recordView, closeView)
            toastMain(msg, longDuration = longDuration)
        }
    }

    private fun resetAfterFail(recordView: View, closeView: View) {
        setRecordUiRecording(false)
        closeView.visibility = View.VISIBLE
        setSwitchesBlockedDuringRecording(false)
    }

    private fun stopRecordingInternal(recordView: View, closeView: View) {
        synchronized(stateLock) {
            if (isStopping) return
            if (!isRecording && !isStarting) return
            isStopping = true
            isStarting = false
            // isRecording 在 afterStopUi 中才置 false，避免 onDestroy 在 worker 线程启动前
            // 因 isRecording = false 且 stopWorker = null 而漏杀 screenrecord 进程
        }
        mainHandler.post {
            cancelWakeup()
            setRecordUiRecording(false)
            closeView.visibility = View.VISIBLE
            setSwitchesBlockedDuringRecording(true)
            updateNotificationText(getString(R.string.record_notif_stopping))
        }
        stopWorker = thread(name = "MiRoot-RecStop") {
            try {
                RecordSynthDebugLog.diagI(
                    "stop: enter currentVideoPath=$currentVideoPath recordPid=$recordPid " +
                        "sessionPcmCapture=$sessionPcmCapture " +
                        "sessionComposite=$sessionCompositeEnabled",
                )

                val pcmPathForMerge = currentPcmPath
                val pcmRate = audioCaptureHelper?.pcmSampleRate ?: AudioCaptureHelper.SAMPLE_RATE
                val pcmCh = audioCaptureHelper?.pcmChannelCount ?: 2
                val tryPcmMerge = sessionPcmCapture && pcmPathForMerge != null

                try {
                    audioCaptureHelper?.stop()
                } catch (_: Exception) {
                }
                Thread.sleep(250)
                audioCaptureHelper = null
                stopAndReleaseMediaProjection()
                if (Build.VERSION.SDK_INT >= 34) {
                    mainHandler.post {
                        restoreForegroundSpecialUseOnly()
                    }
                }

                val killAttempts = 6
                for (i in 1..killAttempts) {
                    if (!isScreenrecordActive()) break
                    stopScreenrecordGracefully(awaitExitMs = if (i < killAttempts) 150L else 400L)
                    if (i < killAttempts) Thread.sleep(100)
                }
                recordPid = -1
                currentPcmPath = null

                val videoPath = currentVideoPath
                currentVideoPath = null
                val composite = sessionCompositeEnabled
                val workVideo = if (videoPath != null) File(videoPath) else null
                val appCtx = applicationContext

                if (workVideo != null) {
                    PublicMediaExport.ensureAppReadable(workVideo.absolutePath)
                }
                if (workVideo == null || !workVideo.isFile || workVideo.length() <= 0L) {
                    val shellSize = workVideo?.absolutePath?.let { PublicMediaExport.shellFileSize(it) } ?: 0L
                    if (shellSize <= 0L) {
                        currentCaptureTs = null
                        mainHandler.post {
                            toastMain(getString(R.string.record_saved_missing))
                            afterStopUi(closeView)
                        }
                        return@thread
                    }
                    PublicMediaExport.ensureAppReadable(workVideo!!.absolutePath)
                }
                val tsToken = currentCaptureTs
                    ?: workVideo.name.removeSuffix(".mp4").removePrefix("MiRoot_").takeIf { it.isNotEmpty() }
                currentCaptureTs = null
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                PrivilegedShell.execCmd("mkdir -p \"${moviesDir.absolutePath}\"")
                val finalVideo = File(
                    moviesDir,
                    "MiRoot_${tsToken ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4",
                )

                // PCM 音频合并/带壳合成
                val pcmFile = pcmPathForMerge?.let { File(it) }
                val pcmLen = pcmFile?.takeIf { it.isFile }?.length() ?: 0L
                val doMerge = tryPcmMerge && pcmLen >= AudioCaptureHelper.MIN_VALID_BYTES
                val singlePassShellWithPcm = composite && doMerge && pcmFile != null

                RecordSynthDebugLog.d(
                    "stop: work=${workVideo.length()} composite=$composite tryPcmMerge=$tryPcmMerge " +
                        "doMerge=$doMerge singlePassShell=$singlePassShellWithPcm pcmLen=$pcmLen " +
                        "final=${finalVideo.absolutePath}",
                )

                val videoForNextHolder = arrayOf(workVideo)
                if (doMerge && pcmFile != null && !composite) {
                    val latch = CountDownLatch(1)
                    val mergedOut = File(cacheDir, "miroot_merge_${System.currentTimeMillis()}.mp4")
                    mainHandler.post {
                        ShellMedia3Export.mergePcmIntoMp4(
                            appCtx,
                            workVideo,
                            pcmFile,
                            mergedOut,
                            audioLeadDelayUs = ShellMedia3Export.REAR_RECORD_AUDIO_LEAD_US,
                            pcmSampleRate = pcmRate,
                            pcmChannels = pcmCh,
                        ) { ok, outFile, err ->
                            try {
                                pcmFile.delete()
                            } catch (_: Exception) {
                            }
                            if (ok && outFile != null && outFile == mergedOut &&
                                mergedOut.isFile && mergedOut.length() > 0L
                            ) {
                                try {
                                    workVideo.delete()
                                } catch (_: Exception) {
                                }
                                videoForNextHolder[0] = mergedOut
                                RecordSynthDebugLog.d("stop: mergePcm ok len=${mergedOut.length()}")
                            } else {
                                RecordSynthDebugLog.w("stop: mergePcm failed err=$err")
                                try {
                                    mergedOut.delete()
                                } catch (_: Exception) {
                                }
                            }
                            latch.countDown()
                        }
                    }
                    if (!latch.await(4, TimeUnit.MINUTES)) {
                        RecordSynthDebugLog.w("stop: mergePcm await timeout")
                    }
                } else {
                    if (!singlePassShellWithPcm) {
                        try {
                            pcmFile?.delete()
                        } catch (_: Exception) {
                        }
                        if (tryPcmMerge && pcmLen in 1 until AudioCaptureHelper.MIN_VALID_BYTES) {
                            mainHandler.post {
                                toastMain(getString(R.string.record_shell_audio_missing))
                            }
                        }
                    }
                }

                val videoForNext = videoForNextHolder[0]
                if (!videoForNext.isFile || videoForNext.length() <= 0L) {
                    mainHandler.post {
                        toastMain(getString(R.string.record_saved_missing))
                        afterStopUi(closeView)
                    }
                    return@thread
                }

                if (!composite) {
                    val copyOk = copyToMoviesPrivileged(videoForNext, finalVideo)
                    if (copyOk) {
                        mediaScan(finalVideo)
                        RecordSynthDebugLog.d(
                            "branch: export nonComposite -> usedCR=$lastCopyUsedContentResolver",
                        )
                    } else {
                        RecordSynthDebugLog.diagW("branch: export nonComposite FAILED")
                    }
                    cleanupRecordTemps(
                        workVideo.absolutePath,
                        if (videoForNext != workVideo) videoForNext.absolutePath else null,
                        pcmPathForMerge,
                    )
                    mainHandler.post {
                        afterStopUi(closeView)
                        if (copyOk) {
                            toastMain(getString(R.string.record_done_process))
                        } else {
                            toastMain(getString(R.string.record_process_fail, "export_failed"))
                        }
                    }
                    return@thread
                }


                PublicMediaExport.ensureAppReadable(videoForNext.absolutePath)
                val tmp = File(appCtx.cacheDir, "miroot_record_out_${System.currentTimeMillis()}.mp4")
                val compositeInput = if (composite) {
                    File(appCtx.cacheDir, "miroot_composite_in_" + System.currentTimeMillis() + ".mp4").also { ci ->
                        try {
                            videoForNext.inputStream().use { input ->
                                ci.outputStream().use { output -> input.copyTo(output) }
                            }
                            RecordSynthDebugLog.d(
                                "composite: cp input OK to ${ci.absolutePath} len=${ci.length()}",
                            )
                        } catch (e: Exception) {
                            RecordSynthDebugLog.diagW("composite: cp input failed: ${e.message}")
                        }
                    }
                } else {
                    videoForNext
                }
                if (composite && (!compositeInput.isFile || compositeInput.length() <= 0L)) {
                    cleanupRecordTemps(
                        workVideo.absolutePath,
                        videoForNext.absolutePath,
                        compositeInput.absolutePath,
                        pcmPathForMerge,
                    )
                    mainHandler.post {
                        toastMain(getString(R.string.record_process_fail, "composite_input_empty"))
                        afterStopUi(closeView)
                    }
                    return@thread
                }
                val sourceForComposite = compositeInput
                val cb = object : ShellMedia3Export.ExportCallback {
                    override fun onFinished(success: Boolean, outputOrNull: File?, error: String?) {
                        val tmpPath = tmp.absolutePath
                        RecordSynthDebugLog.d(
                            "export onFinished: success=$success out=${outputOrNull?.absolutePath} " +
                                "outLen=${outputOrNull?.length()} err=$error tmp=$tmpPath " +
                                "sameRef=${outputOrNull === tmp} " +
                                "finalTarget=${finalVideo.absolutePath}",
                        )
                        mainHandler.post {
                            val out = outputOrNull?.takeIf { it.isFile && it.length() > 0L }
                            val outMatchesTmp = out != null &&
                                out.absolutePath == tmpPath
                            var errorDetail: String? = null
                            var exportOk = false
                            try {
                                if (success && out != null && outMatchesTmp) {
                                    if (copyToMoviesPrivileged(tmp, finalVideo)) {
                                        exportOk = true
                                    } else {
                                        errorDetail = "cp_to_movies_failed"
                                    }
                                } else {
                                    if (!success) {
                                        val detail = error?.takeIf { it.isNotBlank() }
                                            ?: getString(R.string.record_process_fail_unknown)
                                        // 复合失败时尝试原片直存：至少让用户拿到未经带壳/混音的原始录屏
                                        try {
                                            if (sourceForComposite.isFile && sourceForComposite.length() > 0L &&
                                                copyToMoviesPrivileged(sourceForComposite, finalVideo)
                                            ) {
                                                exportOk = true
                                                RecordSynthDebugLog.diagI(
                                                    "composite failed, raw fallback saved to Movies " +
                                                        "len=${sourceForComposite.length()}",
                                                )
                                                errorDetail = null
                                            } else {
                                                errorDetail = detail
                                            }
                                        } catch (_: Exception) {
                                            errorDetail = detail
                                        }
                                        RecordSynthDebugLog.diagW("composite callback unsuccessful: $detail")
                                        if (errorDetail != null) errorDetail = detail
                                    } else if (out != null && !outMatchesTmp) {
                                        RecordSynthDebugLog.diagW(
                                            "composite: success but output path != tmp (out=${out.absolutePath})",
                                        )
                                        try {
                                            if (copyToMoviesPrivileged(out, finalVideo)) {
                                                exportOk = true
                                            } else {
                                                errorDetail = "cp_mismatched_out_failed"
                                            }
                                        } catch (e: Exception) {
                                            RecordSynthDebugLog.diagW(
                                                "composite copy mismatched out: ${e.message}",
                                            )
                                            errorDetail = e.message ?: ""
                                        }
                                    } else if (tmp.isFile && tmp.length() > 0L) {
                                        RecordSynthDebugLog.diagW(
                                            "composite: success, move tmp fallback " +
                                                "(callback out=${outputOrNull?.absolutePath} len=${outputOrNull?.length()})",
                                        )
                                        if (copyToMoviesPrivileged(tmp, finalVideo)) {
                                            exportOk = true
                                        } else {
                                            errorDetail = "cp_tmp_fallback_failed"
                                        }
                                    }
                                }
                            } finally {
                                cleanupRecordTemps(
                                    tmp.absolutePath,
                                    sourceForComposite.absolutePath,
                                    workVideo.absolutePath,
                                    if (videoForNext != workVideo) videoForNext.absolutePath else null,
                                    pcmPathForMerge,
                                )
                                if (exportOk) {
                                    mediaScan(finalVideo)
                                }
                                afterStopUi(closeView)
                                if (exportOk) {
                                    toastMain(getString(R.string.record_done_process))
                                } else if (errorDetail != null) {
                                    toastMain(getString(R.string.record_process_fail, errorDetail ?: ""))
                                }
                            }
                        }
                    }
                }

                if (singlePassShellWithPcm) {
                    RecordSynthDebugLog.d(
                        "branch: mergePcmThenCompositeShell (1× Transformer) in=${workVideo.absolutePath}",
                    )
                    val pcmCleanupWrapper = object : ShellMedia3Export.ExportCallback {
                        override fun onFinished(success: Boolean, outputOrNull: File?, error: String?) {
                            try {
                                pcmFile.delete()
                            } catch (_: Exception) {
                            }
                            cb.onFinished(success, outputOrNull, error)
                        }
                    }
                    ShellMedia3Export.mergePcmThenCompositeShell(
                        appCtx,
                        compositeInput,
                        pcmFile,
                        tmp,
                        pcmSampleRate = pcmRate,
                        pcmChannels = pcmCh,
                        audioLeadDelayUs = ShellMedia3Export.REAR_RECORD_AUDIO_LEAD_US,
                        pcmCleanupWrapper,
                    )
                } else {
                    RecordSynthDebugLog.d("branch: compositeShell in=${sourceForComposite.absolutePath}")
                    ShellMedia3Export.compositeShellOverVideo(
                        appCtx,
                        sourceForComposite,
                        tmp,
                        cb,
                    )
                }
            } catch (t: Throwable) {
                RecordSynthDebugLog.e("stopRecordingInternal", t)
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: t.javaClass.simpleName
                mainHandler.post {
                    toastMain(getString(R.string.record_process_fail, detail))
                    afterStopUi(closeView)
                }
            } finally {
                stopWorker = null
            }
        }
    }

    private fun afterStopUi(closeView: View) {
        synchronized(stateLock) {
            isStopping = false
            isRecording = false
        }
        recordBtn?.let { setRecordUiRecording(false) }
        closeView.visibility = View.VISIBLE
        setSwitchesBlockedDuringRecording(false)
        updateNotificationText(getString(R.string.record_notif_text))
    }

    private fun stopAndReleaseMediaProjection() {
        try {
            currentMediaProjection?.stop()
        } catch (_: Exception) {
        }
        currentMediaProjection = null
    }

    /**
     * 屏录进程 PID 是否仍存活（优先 [pidof]，兜底 [ps -p]）。
     */
    private fun isScreenrecordBinaryProcessRunning(recordPid: Int): Boolean {
        val pidofOut = PrivilegedShell.captureOutput("pidof screenrecord 2>/dev/null")?.trim().orEmpty()
        if (pidofOut.isNotEmpty()) {
            val pids = pidofOut.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.toSet()
            return when {
                recordPid > 0 -> recordPid in pids
                else -> pids.isNotEmpty()
            }
        }
        if (recordPid <= 0) return false
        val comm = PrivilegedShell.captureOutput("ps -p $recordPid -o COMM= 2>/dev/null")?.trim().orEmpty()
        return comm == "screenrecord"
    }
    private fun killScreenrecordFallback() {
        val pidof = PrivilegedShell.captureOutput("pidof screenrecord")?.trim().orEmpty()
        if (pidof.isNotEmpty()) {
            RecordSynthDebugLog.d("killFallback: pidof=$pidof")
            for (p in pidof.split(Regex("\\s+"))) {
                if (p.isNotBlank()) PrivilegedShell.execCmd("kill -2 $p")
            }
            return
        }
        val pg = PrivilegedShell.captureOutput("pgrep screenrecord")?.trim().orEmpty()
        if (pg.isNotEmpty()) {
            RecordSynthDebugLog.d("killFallback: pgrep=$pg")
            for (p in pg.split(Regex("\\s+"))) {
                if (p.isNotBlank()) PrivilegedShell.execCmd("kill -2 $p")
            }
        } else {
            RecordSynthDebugLog.d("killFallback: no pidof/pgrep screenrecord")
        }
    }

    /** 仅「录屏/截图常亮」开启时按滑块间隔补发唤醒；与「始终常亮」分流（后者由 [RearAssistService] 负责）。 */
    private fun shouldPeriodicWakeForRecord(): Boolean {
        if (RearScreenWakeService.isWakeupLoopActive()) return false
        return RearAssistPrefs.isRecordScreenshotKeepScreenOnEnabled(this)
    }

    private fun startWakeup() {
        cancelWakeup()
        if (!shouldPeriodicWakeForRecord()) return
        val ht = HandlerThread("MiRoot-RecWake").apply { start() }
        wakeHandlerThread = ht
        wakeHandler = Handler(ht.looper)
        val loop = object : Runnable {
            override fun run() {
                if (!isRecording) return
                if (!shouldPeriodicWakeForRecord()) {
                    cancelWakeup()
                    return
                }
                if (!RearScreenWakeService.isWakeupLoopActive()) {
                    try {
                        PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
                    } catch (_: Throwable) {
                    }
                }
                if (isRecording) {
                    val delay = RearAssistPrefs.intervalMs(this@RearScreenRecordService)
                        .toLong()
                        .coerceAtLeast(RearAssistPrefs.MIN_INTERVAL_MS.toLong())
                    wakeHandler?.postDelayed(this, delay)
                }
            }
        }
        wakeHandler?.post(loop)
    }

    private fun cancelWakeup() {
        wakeHandler?.removeCallbacksAndMessages(null)
        try {
            wakeHandlerThread?.quitSafely()
        } catch (_: Throwable) {
        }
        wakeHandler = null
        wakeHandlerThread = null
    }

    private fun mediaScan(file: File) {
        // 0) 确保文件已落盘、mtime 最新（FUSE 延迟可能让 MediaStore 读不到刚写入的文件）
        PrivilegedShell.execCmd("touch \"${file.absolutePath}\"")

        // 1) MediaStore ContentProvider insert — Android 11+ 通用
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.SIZE, fileSizeInStorage(file))
                put(android.provider.MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(android.provider.MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                }
            }
            if (!lastCopyUsedContentResolver) {
                val inserted = contentResolver.insert(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            )
            RecordSynthDebugLog.diagI("mediaScan: ContentResolver insert inserted=$inserted")
            }
        } catch (e: Exception) {
            RecordSynthDebugLog.diagW("mediaScan ContentResolver insert failed: ${e.message}")
        }

        // 2) MediaScannerConnection (deprecated 但仍可触发系统扫描)
        try {
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null,
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Shizuku 下优先 MediaStore；Root 下可先 shell cp 并校验字节数。
     */
    private fun copyToMoviesPrivileged(src: File, dst: File): Boolean {
        val result = PublicMediaExport.saveVideoToMovies(this, src, dst.name)
        lastCopyUsedContentResolver = result.viaContentResolver
        return result.ok
    }

    /**
     * 后台启动 screenrecord（nohup detach）。Shizuku 下勿用 [PrivilegedShell.startShell] 长连接持有进程，易触发 Binder 崩溃。
     */
    private fun launchScreenrecordDetached(which: String, displayId: String, path: String): Boolean {
        prepareShellRecordWorkDir()
        PrivilegedShell.execCmd("rm -f \"${RecordPaths.SHELL_PID_FILE}\" \"$path\"")
        val cmd = "mkdir -p \"${RecordPaths.SHELL_CAPTURE_DIR}\"; " +
            "chmod 777 \"${RecordPaths.SHELL_CAPTURE_DIR}\" 2>/dev/null; " +
            "nohup $which --display-id $displayId --bit-rate 20000000 \"$path\" " +
            ">/dev/null 2>&1 </dev/null & echo \$! > \"${RecordPaths.SHELL_PID_FILE}\""
        RecordSynthDebugLog.d("start: detached cmd=${cmd.take(480)}")
        if (!PrivilegedShell.execCmd(cmd)) return false
        Thread.sleep(80)
        recordPid = PrivilegedShell.captureOutput("cat \"${RecordPaths.SHELL_PID_FILE}\" 2>/dev/null")
            ?.trim()
            ?.toIntOrNull()
            ?: resolveScreenrecordPid()
        return true
    }

    private fun isScreenrecordActive(): Boolean {
        if (recordPid <= 0) {
            recordPid = resolveScreenrecordPid()
        }
        return isScreenrecordBinaryProcessRunning(recordPid)
    }

    private fun captureFileSizeBytes(path: String): Long {
        val f = File(path)
        if (f.isFile && f.length() > 0L) return f.length()
        return PublicMediaExport.shellFileSize(path)
    }

    private fun resolveScreenrecordPid(): Int {
        val pidofOut = PrivilegedShell.captureOutput("pidof screenrecord 2>/dev/null")?.trim().orEmpty()
        if (pidofOut.isNotEmpty()) {
            return pidofOut.split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: -1
        }
        val pg = PrivilegedShell.captureOutput("pgrep -n screenrecord 2>/dev/null")?.trim().orEmpty()
        return pg.toIntOrNull() ?: -1
    }

    /** 发送 SIGINT 结束 screenrecord 并短暂等待落盘。 */
    private fun stopScreenrecordGracefully(awaitExitMs: Long = 400L) {
        var pid = recordPid
        if (pid <= 0) pid = resolveScreenrecordPid()
        if (pid > 0) {
            PrivilegedShell.execCmd("kill -2 $pid")
        } else {
            killScreenrecordFallback()
        }
        val deadline = SystemClock.elapsedRealtime() + awaitExitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isScreenrecordBinaryProcessRunning(pid) &&
                PrivilegedShell.captureOutput("pidof screenrecord 2>/dev/null").isNullOrBlank()
            ) {
                break
            }
            Thread.sleep(50)
        }
        recordPid = -1
        PrivilegedShell.execCmd("rm -f \"${RecordPaths.SHELL_PID_FILE}\"")
    }

    private fun prepareShellRecordWorkDir(): Boolean =
        PrivilegedShell.execCmd(
            "mkdir -p \"${RecordPaths.SHELL_WORK_DIR}\" && chmod 777 \"${RecordPaths.SHELL_WORK_DIR}\" 2>/dev/null",
        )

    private fun recordCaptureDir(): File = File(RecordPaths.SHELL_CAPTURE_DIR)

    private fun prepareShellRecordEnvironment(): Boolean =
        prepareShellRecordWorkDir() &&
            PrivilegedShell.execCmd(
                "mkdir -p \"${RecordPaths.SHELL_CAPTURE_DIR}\" && chmod 777 \"${RecordPaths.SHELL_CAPTURE_DIR}\" 2>/dev/null",
            )

    private fun verifyShellCanWritePath(path: String): Boolean {
        val testPath = "$path.__write_test"
        PrivilegedShell.execCmd("rm -f \"$testPath\"")
        return PrivilegedShell.execCmd("touch \"$testPath\" && rm -f \"$testPath\"")
    }

    private fun diagnoseScreenrecordStartFailure(
        displayId: String,
        path: String,
        pidFromFile: String,
        pidofLine: String,
    ): String =
        when {
            pidFromFile.isBlank() && pidofLine.isBlank() ->
                getString(R.string.record_fail_reason_no_pid)
            else -> getString(R.string.record_fail_reason_generic, displayId, path)
        }

    private fun cleanupRecordTemps(vararg paths: String?) {
        for (path in paths) {
            PublicMediaExport.deleteQuietly(path)
        }
    }

    /** 分区存储下通过 shell stat 获取文件字节数。 */
    private fun fileSizeInStorage(file: File): Long {
        if (file.isFile) return file.length()
        val out = PrivilegedShell.captureOutput("stat -c %s \"${file.absolutePath}\" 2>/dev/null")?.trim()
        return out?.toLongOrNull() ?: 0L
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    /**
     * 读取 Flutter 迁移偏好：[record_audio_enabled] 优先，其次 [flutter.record_audio_enabled]；兼容 string 存储。
     */
    private fun readFlutterRecordAudio(): Boolean {
        val fp = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        tryReadBoolPref(fp, "record_audio_enabled")?.let { return it }
        tryReadBoolPref(fp, "flutter.record_audio_enabled")?.let { return it }
        return false
    }

    /**
     * [record_composite_enabled] 优先，其次 [flutter.record_composite_enabled]；兼容 string 存储。
     */
    private fun readFlutterRecordComposite(): Boolean {
        val fp = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        tryReadBoolPref(fp, "record_composite_enabled")?.let { return it }
        tryReadBoolPref(fp, "flutter.record_composite_enabled")?.let { return it }
        return true
    }

    private fun tryReadBoolPref(p: SharedPreferences, key: String): Boolean? {
        if (!p.contains(key)) return null
        return try {
            p.getBoolean(key, false)
        } catch (_: ClassCastException) {
            try {
                val s = p.getString(key, null)?.trim() ?: return null
                when {
                    s.equals("true", ignoreCase = true) -> true
                    s.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun audioEnabled(): Boolean {
        val p = prefs()
        if (p.contains(KEY_AUDIO)) return p.getBoolean(KEY_AUDIO, false)
        return readFlutterRecordAudio()
    }

    /**
     * 录制入口优先读取悬浮窗实时开关，避免 UI 与 SharedPreferences 极端时序不同步。
     * 同时把值写回偏好，保证重启服务后状态一致。
     */
    private fun sessionAudioWanted(): Boolean {
        val fromUi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            switchAudio?.isChecked
        } else {
            null
        }
        val resolved = fromUi ?: audioEnabled()
        setAudioPref(resolved)
        return resolved
    }

    private fun setAudioPref(v: Boolean) {
        prefs().edit().putBoolean(KEY_AUDIO, v).apply()
    }

    private fun compositeEnabled(): Boolean {
        val p = prefs()
        if (p.contains(KEY_COMPOSITE)) return p.getBoolean(KEY_COMPOSITE, true)
        return readFlutterRecordComposite()
    }

    private fun setCompositePref(v: Boolean) {
        prefs().edit().putBoolean(KEY_COMPOSITE, v).apply()
    }

    private fun recordStickerEnabled(): Boolean =
        DeviceGeometry.isRecordStickerCompositeEnabled(this)

    private fun setRecordStickerPref(v: Boolean) {
        DeviceGeometry.persistRecordStickerComposite(this, v)
    }

    /**
     * 录制中禁止切换录音/带壳/贴图，但保持 [SwitchCompat.isEnabled] 为 true，避免 Material 禁用态变色。
     */
    private fun setSwitchesBlockedDuringRecording(blocked: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            switchAudio?.apply {
                isEnabled = true
                isClickable = !blocked
                isFocusable = !blocked
            }
        }
        switchComposite?.apply {
            isEnabled = true
            isClickable = !blocked
            isFocusable = !blocked
        }
        switchSticker?.apply {
            isEnabled = true
            isClickable = !blocked
            isFocusable = !blocked
        }
    }

    private fun toastMain(msg: String, longDuration: Boolean = false) {
        MainDisplayUi.showToast(
            applicationContext,
            msg,
            if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        )
    }

    /**
     * API 34+：在 [MediaProjectionManager.getMediaProjection] 之前必须已用
     * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION]（及内录所需 microphone）调用过
     * [ServiceCompat.startForeground]，否则系统抛 [SecurityException]。
     */
    private fun ensureForegroundBeforeProjection() {
        if (Build.VERSION.SDK_INT < 34) return
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (RuntimePermissionGate.hasRecordAudio(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            types,
        )
    }

    /** 投影未建立或失败时恢复为仅悬浮窗录制条所需类型，避免长时间持有 projection/mic 前台声明。 */
    private fun restoreForegroundSpecialUseOnly() {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (_: Exception) {
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.record_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
    }

    /**
     * 在录制开始/停止时更新通知栏文本，为用户提供持续性反馈（Toast 会超时消失）。
     */
    private fun updateNotificationText(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
            RecordSynthDebugLog.d("updateNotificationText: $text")
        } catch (e: Exception) {
            RecordSynthDebugLog.diagW("updateNotificationText failed: ${e.message}")
        }
    }

    private fun buildNotification(contentText: String? = null): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.record_notif_title))
            .setContentText(contentText ?: getString(R.string.record_notif_text))
            .setSmallIcon(R.drawable.ic_stat_notify_record)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START_WITH_PROJECTION = "com.wmqc.miroot.record.START_WITH_PROJECTION"
        /** 用户在录屏授权界面取消/拒绝；不启动录制（开启「录音」时）。 */
        const val ACTION_PROJECTION_CANCELLED = "com.wmqc.miroot.record.PROJECTION_CANCELLED"
        const val ACTION_START_VIDEO_ONLY = "com.wmqc.miroot.record.START_VIDEO_ONLY"
        const val EXTRA_MEDIA_PROJECTION_RESULT_CODE = "mp_result_code"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mp_data"

        @Volatile
        var instance: RearScreenRecordService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun isRecordingActive(): Boolean = instance?.isRecording ?: false

        private const val PREFS = "miroot_record"
        private const val KEY_AUDIO = "record_audio"
        private const val KEY_COMPOSITE = "record_composite"
        private const val TAG = "RearScreenRecord"
        private const val CHANNEL_ID = "miroot_rear_record"
        private const val NOTIF_ID = 10040
    }
}
