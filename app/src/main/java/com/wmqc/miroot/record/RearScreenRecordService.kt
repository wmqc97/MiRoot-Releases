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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
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
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.R
import com.wmqc.miroot.license.OfflineActivationRepository
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.rear.RearAssistPrefs
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.capability.RuntimePermissionGate
import com.wmqc.miroot.shell.DeviceGeometry
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
 * 原始视频在 [cacheDir]`/miroot_rear_work_<时间戳>`（root 写入，停止后 chown），最终 `Movies/MiRoot_*.mp4`。
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
    private var recordPid = -1

    /** 开始录制成功时快照；停止后带壳/保存路径依赖此值 */
    @Volatile
    private var sessionCompositeEnabled: Boolean = true
    /** 本会话是否实际在录 PCM（投影 + AudioCaptureHelper 成功启动） */
    @Volatile
    private var sessionPcmCapture: Boolean = false
    /** Android 12+ screenrecord --audio 内录音频（无需 MediaProjection/PCM 路径）。 */
    @Volatile
    private var sessionAudioBuiltin: Boolean = false
    /** 本次点击录制时是否期望内录（以悬浮窗实时开关为准）。 */
    @Volatile
    private var sessionAudioWanted: Boolean = false
    private var audioCaptureHelper: AudioCaptureHelper? = null
    private var currentMediaProjection: MediaProjection? = null

    @Volatile
    private var projectionPending: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var stopWorker: Thread? = null

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
                    synchronized(stateLock) { projectionPending = false }
                    mainHandler.post {
                        toastMain(getString(R.string.record_projection_cancelled))
                    }
                    RecordSynthDebugLog.diagI("onStartCommand: user cancelled MediaProjection, record not started")
                }
                ACTION_START_VIDEO_ONLY -> {
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
        } else if (isRecording) {
            try {
                if (recordPid > 0) PrivilegedShell.execCmd("kill -2 $recordPid")
                killScreenrecordFallback()
            } catch (_: Exception) {
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
                if (isRecording) {
                    stopRecordingInternal(this, close)
                    return@setOnClickListener
                }
                if (isStopping) {
                    toastMain(getString(R.string.record_busy))
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
                if (wantsAudioNow && Build.VERSION.SDK_INT >= 31) {
                    // 检查 screenrecord 是否支持 --audio，避免直接使用导致 screenrecord 失败退出
                    thread(name = "MiRoot-AudioProbe") {
                        val audioOk = screenrecordSupportsAudio()
                        RecordSynthDebugLog.diagI("record click: screenrecord --audio support=$audioOk")
                        mainHandler.post {
                            if (audioOk) {
                                startRecordingInternal(
                                    this@RearScreenRecordService, close, null,
                                    audioWantedByUser = true,
                                )
                            } else {
                                // --audio 不可用：回退到旧 MediaProjection + PCM 路径
                                RecordSynthDebugLog.diagW(
                                    "screenrecord --audio not supported, fallback to PCM path",
                                )
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
                                    return@post
                                }
                                toastMain(getString(R.string.record_audio_builtin_unavailable))
                                requestMediaProjectionThenStart(this, close)
                            }
                        }
                    }
                } else if (wantsAudioNow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    private fun requestMediaProjectionThenStart(recordView: View, closeView: View) {
        synchronized(stateLock) {
            if (isRecording || isStarting || isStopping || projectionPending) {
                toastMain(getString(R.string.record_busy))
                return
            }
            projectionPending = true
        }
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
            synchronized(stateLock) { projectionPending = false }
            toastMain(getString(R.string.record_float_fail, t.message ?: ""))
        }
    }

    private fun startRecordingInternal(
        recordView: View,
        closeView: View,
        mediaProjection: MediaProjection?,
        audioWantedByUser: Boolean = false,
    ) {
        synchronized(stateLock) {
            if (isRecording || isStarting || isStopping) {
                toastMain(getString(R.string.record_busy))
                return
            }
            isStarting = true
        }
        val useBuiltinAudio = audioWantedByUser && Build.VERSION.SDK_INT >= 31
        RecordSynthDebugLog.diagI(
            "start: enter privilegedOk=${privilegedOk()} cacheDir=${cacheDir.absolutePath} " +
                "hasProjection=${mediaProjection != null} useBuiltinAudio=$useBuiltinAudio",
        )
        if (!privilegedOk()) {
            RecordSynthDebugLog.diagW("start: abort — no privileged shell (Root/Shizuku)")
            synchronized(stateLock) { isStarting = false }
            resetAfterFail(recordView, closeView)
            toastMain(getString(R.string.privilege_shell_required))
            return
        }
        thread(name = "MiRoot-RecStart") {
            try {
                sessionAudioWanted = mediaProjection != null || useBuiltinAudio
                sessionPcmCapture = false
                sessionAudioBuiltin = useBuiltinAudio
                if (RearAssistPrefs.isRecordScreenshotKeepScreenOnEnabled(this) &&
                    !RearScreenWakeService.isWakeupLoopActive()
                ) {
                    PrivilegedShell.execCmd("input -d 1 keyevent KEYCODE_WAKEUP")
                }
                Thread.sleep(50)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                currentCaptureTs = ts
                val path = File(cacheDir, "miroot_rear_work_$ts").absolutePath
                currentVideoPath = path
                RecordSynthDebugLog.diagI("start: workPath=$path ts=$ts")

                // API 34+：须先以 mediaProjection(+microphone) 类型完成 startForeground，再采内录；否则
                // MissingForegroundServiceTypeException / 进程被系统终止。内录亦须 [RECORD_AUDIO]。
                if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    currentMediaProjection = mediaProjection
                    if (!RuntimePermissionGate.hasRecordAudio(this@RearScreenRecordService)) {
                        failStart(recordView, closeView, getString(R.string.record_need_record_audio))
                        return@thread
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
                        return@thread
                    }
                    val err = prepError
                    if (err != null) {
                        failStart(recordView, closeView, err.message ?: "error")
                        return@thread
                    }
                }

                var displayId = PrivilegedShell.captureOutput(
                    "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print \$2}'",
                )?.trim().orEmpty()
                if (displayId.isEmpty()) displayId = "1"
                RecordSynthDebugLog.d("start: displayId=$displayId")

                val which = PrivilegedShell.captureOutput("which screenrecord")?.trim().orEmpty()
                if (which.isEmpty()) {
                    RecordSynthDebugLog.diagW("start: which screenrecord empty")
                    failStart(recordView, closeView, getString(R.string.record_no_screenrecord))
                    return@thread
                }
                RecordSynthDebugLog.diagI("start: screenrecord bin=$which")

                val audioFlag = if (useBuiltinAudio) " --audio --audio-bit-rate 128000" else ""
                RecordSynthDebugLog.diagI(
                    "start: screenrecord bin=$which audioBuiltin=$useBuiltinAudio",
                )
                val cmd =
                    "$which --display-id $displayId --bit-rate 12000000$audioFlag $path > $LOG_FILE 2>&1 & echo \$! > $PID_FILE"
                RecordSynthDebugLog.d("start: launch cmd=${cmd.take(520)}${if (cmd.length > 520) "…" else ""}")
                if (!PrivilegedShell.execCmd(cmd)) {
                    RecordSynthDebugLog.diagW(
                        "start: launch failed | logTail=\n${snapshotScreenrecordLog()}",
                    )
                    failStart(recordView, closeView, getString(R.string.record_cmd_fail))
                    return@thread
                }

                var pcmStarted = false
                if (!useBuiltinAudio && mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pcmPath = "${path}_audio.pcm"
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
                    }
                } else if (useBuiltinAudio) {
                    pcmStarted = true  // audio embedded in screenrecord output
                }

                // 轮询 screenrecord 进程/文件确认已启动，替代固定 sleep
                var pollAttempts = 0
                val pollMax = 12 // 12×50ms = 600ms 上限（shell 调用耗时另计，合计 ~1s 与原 800ms 相当）
                var pidStr = ""
                var fileOk = false
                while (pollAttempts < pollMax) {
                    Thread.sleep(50)
                    pollAttempts++
                    pidStr = PrivilegedShell.captureOutput("cat $PID_FILE 2>/dev/null")?.trim().orEmpty()
                    recordPid = pidStr.toIntOrNull() ?: -1
                    val lsOut = PrivilegedShell.captureOutput("ls -l \"$path\" 2>&1").orEmpty()
                    fileOk = lsOut.isNotBlank() && !lsOut.contains("No such")
                    if (fileOk || isScreenrecordBinaryProcessRunning(recordPid)) break
                }
                val runningBin = isScreenrecordBinaryProcessRunning(recordPid)
                val pidofLine = PrivilegedShell.captureOutput("pidof screenrecord 2>/dev/null").orEmpty()
                RecordSynthDebugLog.diagI(
                    "start: polled=${pollAttempts}×50ms pidFile='$pidStr' recordPid=$recordPid fileOk=$fileOk runningBin=$runningBin pidof=$pidofLine",
                )
                if (!fileOk && !runningBin) {
                    RecordSynthDebugLog.diagW(
                        "start: proc/file check failed | log=\n${snapshotScreenrecordLog()}",
                    )
                    failStart(recordView, closeView, getString(R.string.record_proc_fail))
                    return@thread
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
                    toastMain(getString(R.string.record_started), longDuration = true)
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

    private fun failStart(recordView: View, closeView: View, msg: String) {
        RecordSynthDebugLog.diagW("failStart: $msg | logTail=\n${snapshotScreenrecordLog()}")
        try {
            audioCaptureHelper?.stop()
        } catch (_: Exception) {
        }
        audioCaptureHelper = null
        stopAndReleaseMediaProjection()
        synchronized(stateLock) {
            isStarting = false
            isRecording = false
            projectionPending = false
        }
        currentCaptureTs = null
        currentVideoPath?.let { p ->
            val isBuiltin = sessionAudioBuiltin
            try {
                File(p).delete()
            } catch (_: Exception) {
            }
            if (!isBuiltin) {
                try {
                    File("${p}_audio.pcm").delete()
                } catch (_: Exception) {
                }
                try {
                    PrivilegedShell.execCmd("rm -f \"$p\" \"${p}_audio.pcm\"")
                } catch (_: Exception) {
                }
            } else {
                try {
                    PrivilegedShell.execCmd("rm -f \"$p\"")
                } catch (_: Exception) {
                }
            }
        }
        currentVideoPath = null
        mainHandler.post {
            restoreForegroundSpecialUseOnly()
            resetAfterFail(recordView, closeView)
            toastMain(msg)
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
            isRecording = false
        }
        mainHandler.post {
            toastMain(getString(R.string.record_stopping))
            cancelWakeup()
            setRecordUiRecording(false)
            closeView.visibility = View.VISIBLE
            setSwitchesBlockedDuringRecording(true)
        }
        stopWorker = thread(name = "MiRoot-RecStop") {
            try {
                RecordSynthDebugLog.diagI(
                    "stop: enter currentVideoPath=$currentVideoPath recordPid=$recordPid " +
                        "sessionPcmCapture=$sessionPcmCapture sessionAudioBuiltin=$sessionAudioBuiltin " +
                        "sessionComposite=$sessionCompositeEnabled",
                )

                val pcmPathForMerge = if (!sessionAudioBuiltin) {
                    currentVideoPath?.let { "${it}_audio.pcm" }
                } else null
                val pcmRate = audioCaptureHelper?.pcmSampleRate ?: AudioCaptureHelper.SAMPLE_RATE
                val pcmCh = audioCaptureHelper?.pcmChannelCount ?: 2
                val tryPcmMerge = !sessionAudioBuiltin && sessionPcmCapture && pcmPathForMerge != null

                if (sessionAudioBuiltin) {
                    // --audio 路径：screenrecord 已内嵌音频，立即停止即可
                    audioCaptureHelper?.stop()
                    audioCaptureHelper = null
                } else {
                    try {
                        audioCaptureHelper?.stop()
                    } catch (_: Exception) {
                    }
                    Thread.sleep(250)
                    audioCaptureHelper = null
                }
                stopAndReleaseMediaProjection()
                if (Build.VERSION.SDK_INT >= 34) {
                    mainHandler.post {
                        restoreForegroundSpecialUseOnly()
                    }
                }

                val killAttempts = 6
                for (i in 1..killAttempts) {
                    val runningNow = recordPid > 0 && isScreenrecordBinaryProcessRunning(recordPid)
                    if (!runningNow) break
                    if (recordPid > 0) {
                        PrivilegedShell.execCmd("kill -2 $recordPid")
                    } else {
                        killScreenrecordFallback()
                    }
                    if (i < killAttempts) Thread.sleep(100)
                }
                recordPid = -1

                val videoPath = currentVideoPath
                currentVideoPath = null
                val composite = sessionCompositeEnabled
                val workVideo = if (videoPath != null) File(videoPath) else null

                val appCtx = applicationContext
                if (workVideo == null || !workVideo.exists()) {
                    currentCaptureTs = null
                    mainHandler.post {
                        toastMain(getString(R.string.record_saved_missing))
                        afterStopUi(closeView)
                    }
                    return@thread
                }

                // 修复 root 写入的文件权限
                chownWorkVideoToApp(workVideo.absolutePath)
                if (!workVideo.canRead()) {
                    RecordSynthDebugLog.diagW("stop: video unreadable after chown path=$videoPath")
                    mainHandler.post {
                        toastMain(getString(R.string.record_saved_missing))
                        afterStopUi(closeView)
                    }
                    return@thread
                }

                val tsToken = currentCaptureTs
                    ?: workVideo.name.removePrefix("miroot_rear_work_").takeIf { it.isNotEmpty() }
                currentCaptureTs = null
                PrivilegedShell.execCmd("mkdir -p /storage/emulated/0/Movies")
                val finalVideo = File(
                    "/storage/emulated/0/Movies/MiRoot_${tsToken ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4",
                )

                mainHandler.post { toastMain(getString(R.string.record_synthesis_start)) }

                // 内嵌音频路径：直接走 compositeShellOverVideo 或直接拷贝
                if (sessionAudioBuiltin) {
                    RecordSynthDebugLog.d("branch: --audio builtin, composite=$composite workLen=${workVideo.length()}")
                    if (!composite) {
                        try {
                            workVideo.copyTo(finalVideo, overwrite = true)
                        } catch (_: Exception) {
                        }
                        PrivilegedShell.execCmd("rm -f \"${workVideo.absolutePath}\"")
                        if (finalVideo.isFile && finalVideo.length() > 0L) {
                            mediaScan(finalVideo)
                        }
                        mainHandler.post {
                            afterStopUi(closeView)
                            toastMain(getString(R.string.record_done_process))
                        }
                        return@thread
                    }
                    // 带壳合成（保留内嵌音轨）
                    compositeShellOverVideoWithCallback(appCtx, workVideo, finalVideo, closeView)
                    return@thread
                }

                // 旧 PCM 路径 ↓
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
                    try {
                        videoForNext.copyTo(finalVideo, overwrite = true)
                    } catch (_: Exception) {
                    }
                    if (videoForNext != workVideo) {
                        try {
                            videoForNext.delete()
                        } catch (_: Exception) {
                        }
                    }
                    PrivilegedShell.execCmd("rm -f \"${workVideo.absolutePath}\"")
                    if (finalVideo.isFile && finalVideo.length() > 0L) {
                        mediaScan(finalVideo)
                    }
                    RecordSynthDebugLog.d("branch: copy to Movies -> ${finalVideo.length()} bytes")
                    mainHandler.post {
                        afterStopUi(closeView)
                        toastMain(getString(R.string.record_done_process))
                    }
                    return@thread
                }

                val tmp = File(appCtx.cacheDir, "miroot_record_out_${System.currentTimeMillis()}.mp4")
                val sourceForComposite = videoForNext
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
                            try {
                                if (success && out != null && outMatchesTmp) {
                                    try {
                                        PrivilegedShell.execCmd(
                                            "rm -f \"${sourceForComposite.absolutePath}\"",
                                        )
                                    } catch (e: Exception) {
                                        RecordSynthDebugLog.diagW("composite rm source: ${e.message}")
                                    }
                                    try {
                                        if (!tmp.renameTo(finalVideo)) {
                                            tmp.copyTo(finalVideo, overwrite = true)
                                        }
                                    } catch (e: Exception) {
                                        RecordSynthDebugLog.diagW(
                                            "composite move to Movies failed: ${e.message}",
                                        )
                                        errorDetail = e.message ?: ""
                                    }
                                } else {
                                    if (!success) {
                                        val detail = error?.takeIf { it.isNotBlank() }
                                            ?: getString(R.string.record_process_fail_unknown)
                                        // 某些机型回调 success=false，但仍可能产出可用文件；最终以落盘结果判定提示。
                                        RecordSynthDebugLog.diagW("composite callback unsuccessful: $detail")
                                        errorDetail = detail
                                    } else if (out != null && !outMatchesTmp) {
                                        RecordSynthDebugLog.diagW(
                                            "composite: success but output path != tmp (out=${out.absolutePath})",
                                        )
                                        try {
                                            out.copyTo(finalVideo, overwrite = true)
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
                                        try {
                                            PrivilegedShell.execCmd(
                                                "rm -f \"${sourceForComposite.absolutePath}\"",
                                            )
                                        } catch (e: Exception) {
                                            RecordSynthDebugLog.diagW("composite rm source: ${e.message}")
                                        }
                                        try {
                                            if (!tmp.renameTo(finalVideo)) {
                                                tmp.copyTo(finalVideo, overwrite = true)
                                            }
                                        } catch (e: Exception) {
                                            RecordSynthDebugLog.diagW(
                                                "composite tmp fallback move: ${e.message}",
                                            )
                                            errorDetail = e.message ?: ""
                                        }
                                    }
                                }
                            } finally {
                                if (tmp.exists()) {
                                    try {
                                        tmp.delete()
                                    } catch (_: Exception) {
                                    }
                                }
                                if (finalVideo.isFile && finalVideo.length() > 0L) {
                                    mediaScan(finalVideo)
                                }
                                afterStopUi(closeView)
                                if (finalVideo.isFile && finalVideo.length() > 0L) {
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
                        workVideo,
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

    /**
     * 带壳合成保留内嵌音轨；[--audio] 路径下直接调用，合成完成后移动最终文件到 Movies/。
     */
    private fun compositeShellOverVideoWithCallback(
        appCtx: Context,
        sourceFile: File,
        targetFile: File,
        closeView: View,
    ) {
        val tmp = File(appCtx.cacheDir, "miroot_record_out_${System.currentTimeMillis()}.mp4")
        ShellMedia3Export.compositeShellOverVideo(
            appCtx,
            sourceFile,
            tmp,
            object : ShellMedia3Export.ExportCallback {
                override fun onFinished(success: Boolean, outputOrNull: File?, error: String?) {
                    mainHandler.post {
                        try {
                            if (success && outputOrNull != null && outputOrNull.isFile && outputOrNull.length() > 0L) {
                                PrivilegedShell.execCmd("rm -f \"${sourceFile.absolutePath}\"")
                                if (!outputOrNull.renameTo(targetFile)) {
                                    outputOrNull.copyTo(targetFile, overwrite = true)
                                }
                            } else {
                                // 合成失败降级：直接拷贝原始文件
                                RecordSynthDebugLog.w("compositeShell fallback to raw: $error")
                                sourceFile.copyTo(targetFile, overwrite = true)
                            }
                        } catch (e: Exception) {
                            RecordSynthDebugLog.e("compositeShell finalize failed", e)
                        } finally {
                            if (tmp.exists()) tmp.delete()
                            if (targetFile.isFile && targetFile.length() > 0L) {
                                mediaScan(targetFile)
                            }
                            afterStopUi(closeView)
                            toastMain(getString(R.string.record_done_process))
                        }
                    }
                }
            },
        )
    }

    private fun afterStopUi(closeView: View) {
        synchronized(stateLock) {
            isStopping = false
        }
        recordBtn?.let { setRecordUiRecording(false) }
        closeView.visibility = View.VISIBLE
        setSwitchesBlockedDuringRecording(false)
    }

    private fun stopAndReleaseMediaProjection() {
        try {
            currentMediaProjection?.stop()
        } catch (_: Exception) {
        }
        currentMediaProjection = null
    }

    /**
     * screenrecord 以特权 shell 写入时属主常为 root/shell，应用进程无法打开；按 cache 目录的 uid:gid 修正并放宽读权限。
     */
    /** 拉取 screenrecord  stderr/stdout 重定向日志（特权可读路径）。 */
    private fun snapshotScreenrecordLog(): String {
        val raw = PrivilegedShell.captureOutput(
            "if test -r $LOG_FILE; then tail -c 6000 $LOG_FILE 2>/dev/null; else echo '<unreadable:$LOG_FILE>'; fi",
        ).orEmpty()
        return RecordSynthDebugLog.truncateShellOutput(raw, 2500)
    }

    /**
     * 检测本机 screenrecord 二进制是否支持 `--audio` 标志。
     * 在后台线程调用（会启动子进程）；`--audio` 自 Android 12（API 31）引入，
     * 但部分 ROM（含 HyperOS 早期版本）可能未包含此功能。
     */
    private fun screenrecordSupportsAudio(): Boolean {
        val out = PrivilegedShell.captureOutput(
            "screenrecord --help 2>/dev/null | grep -c -- '--audio'",
        )?.trim().orEmpty()
        val count = out.toIntOrNull() ?: 0
        RecordSynthDebugLog.d("screenrecordSupportsAudio: grep count=$out -> $count")
        return count > 0
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

    private fun chownWorkVideoToApp(workPath: String) {
        val dir = cacheDir.absolutePath
        val cmd =
            "test -f \"$workPath\" && " +
                "chown \$(ls -nd \"$dir\" | awk '{print \$3\":\"\$4}') \"$workPath\" 2>/dev/null; " +
                "chmod 644 \"$workPath\" 2>/dev/null || chmod 666 \"$workPath\" 2>/dev/null"
        val ok = PrivilegedShell.execCmd(cmd)
        RecordSynthDebugLog.d("chownWorkVideoToApp: run ok=$ok path=$workPath")
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
        val uri = android.net.Uri.fromFile(file).toString()
        PrivilegedShell.execCmd(
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d $uri",
        )
    }

    private fun privilegedOk(): Boolean =
        com.wmqc.miroot.capability.EnvironmentProbe.hasPrivilegedShellChannelSync()

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
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
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

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.record_notif_title))
            .setContentText(getString(R.string.record_notif_text))
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

        private const val PID_FILE = "/data/local/tmp/miroot_record.pid"
        private const val LOG_FILE = "/data/local/tmp/miroot_record.log"
        private const val CHANNEL_ID = "miroot_rear_record"
        private const val NOTIF_ID = 10040
        private const val PREFS = "miroot_record"
        private const val KEY_AUDIO = "record_audio"
        private const val KEY_COMPOSITE = "record_composite"
        private const val TAG = "RearScreenRecord"
    }
}
