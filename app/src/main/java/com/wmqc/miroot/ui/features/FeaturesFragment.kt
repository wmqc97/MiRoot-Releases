package com.wmqc.miroot.ui.features

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.res.ColorStateList
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.common.showSectionHelp
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.capability.RuntimePermissionGate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.wmqc.miroot.charging.ChargingAnimationPrefs
import com.wmqc.miroot.charging.ChargingIntents
import com.wmqc.miroot.charging.ChargingServiceSync
import com.wmqc.miroot.charging.RearScreenChargingActivity
import com.wmqc.miroot.databinding.DialogCompositeXyBinding
import com.wmqc.miroot.databinding.DialogStickerOverlayBinding
import com.wmqc.miroot.databinding.FragmentFeaturesBinding
import com.wmqc.miroot.rear.OfficialSubscreenServiceGate
import com.wmqc.miroot.rear.RearAssistPrefs
import com.wmqc.miroot.rear.RearAssistService
import com.wmqc.miroot.rear.RearSwitchKeeperService
import com.wmqc.miroot.lyrics.RearScreenWakeService
import com.wmqc.miroot.record.RearScreenRecordService
import com.wmqc.miroot.shell.DeviceGeometry
import com.wmqc.miroot.shell.RearScreenshotCoordinator
import com.wmqc.miroot.shell.ShellStickerOverlay
import com.wmqc.miroot.shell.StickerPreviewOverlay
import com.wmqc.miroot.shell.StickerPreviewRenderer
import com.wmqc.miroot.shell.StickerScaleHelper
import com.wmqc.miroot.shell.StickerScaleMode
import kotlin.math.max
import kotlin.math.roundToInt
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import com.wmqc.miroot.car.CarControlEntry
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 与歌词/车控/磁贴共用的投屏常亮偏好键（Flutter SharedPreferences）。 */
private const val FLUTTER_KEEP_SCREEN_ON_KEY = "flutter.keep_screen_on_enabled"

class FeaturesFragment : Fragment(R.layout.fragment_features) {

    private var _binding: FragmentFeaturesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainPermissionViewModel by activityViewModels()
    private var suppressShellCallbacks = false

    private val rearAssistUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded || _binding == null) return
            syncRearAssistUiFromPrefs()
        }
    }
    private var lastValidShellChipId = R.id.chip_shell_green
    private var suppressRearAssistCallbacks = false
    private var suppressChargingAnimationCallbacks = false
    private var suppressChargingAlwaysOnCallbacks = false
    private var suppressChargingDebugMainCallbacks = false
    private var suppressOfficialSubscreenCallback = false
    private val wakeSliderStepState = mutableIntStateOf(0)
    private val chargingFillSpeedStepState = mutableIntStateOf(27)

    private var featuresTitleTapCount = 0
    private var featuresTitleLastTapUptime = 0L

    private var stickerDialogBinding: DialogStickerOverlayBinding? = null
    private val stickerPreviewHandler = Handler(Looper.getMainLooper())
    private var stickerPreviewDebounce: Runnable? = null
    /** 程序化写入 X/Y 等输入框时不触发预览防抖。 */
    private var suppressStickerFieldWatchers = false

    private val pickStickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (!isAdded) return@registerForActivityResult
        val b = stickerDialogBinding ?: return@registerForActivityResult
        val ctx = requireContext()
        if (uri == null) return@registerForActivityResult
        if (copyStickerFromUri(ctx, uri)) {
            b.switchStickerEnabled.isChecked = true
            Toast.makeText(ctx, R.string.features_sticker_picked, Toast.LENGTH_SHORT).show()
            syncStickerDialogFileRow(b)
            autofillStickerDimensionsFromFile(ctx, b)
            b.root.post { refreshStickerPreview(b) }
        } else {
            Toast.makeText(ctx, R.string.features_sticker_copy_fail, Toast.LENGTH_LONG).show()
        }
    }

    private var pendingStickerExportBitmap: Bitmap? = null

    private val exportStickerPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri: Uri? ->
        val bmp = pendingStickerExportBitmap
        pendingStickerExportBitmap = null
        if (bmp == null) return@registerForActivityResult
        if (uri == null) {
            bmp.recycle()
            return@registerForActivityResult
        }
        val ctx = context ?: run {
            bmp.recycle()
            return@registerForActivityResult
        }
        thread(name = "MiRoot-StickerExportWrite") {
            var ok = false
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            } catch (_: Exception) {
                ok = false
            } finally {
                bmp.recycle()
            }
            activity?.runOnUiThread {
                Toast.makeText(
                    ctx,
                    if (ok) R.string.features_sticker_export_ok else R.string.features_sticker_export_fail,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFeaturesBinding.bind(view)
        viewModel.snapshot.observe(viewLifecycleOwner, ::render)

        binding.textSectionFeaturesRecord.setOnClickListener {
            showSectionHelp(R.string.features_section_record_screenshot, R.string.help_features_record_screenshot)
        }
        binding.textSectionFeaturesCharging.setOnClickListener {
            showSectionHelp(R.string.features_section_charging_animation, R.string.help_features_charging)
        }
        binding.textSectionFeaturesRearAssist.setOnClickListener {
            showSectionHelp(R.string.features_section_rear_assist, R.string.help_features_rear_assist)
        }

        binding.textFeaturesDeviceModel.text = if (DeviceGeometry.isProMaxModel()) {
            getString(R.string.features_device_model_promax)
        } else {
            getString(R.string.features_device_model_pro)
        }

        syncShellUiFromPrefs()
        ensureShellCopiedToWorkDir()
        bindShellBackdropControls()

        suppressOfficialSubscreenCallback = true
        binding.switchOfficialSubscreenService.isChecked =
            OfficialSubscreenServiceGate.isDisableEnabled(requireContext())
        suppressOfficialSubscreenCallback = false
        binding.switchOfficialSubscreenService.setOnCheckedChangeListener { _, checked ->
            if (suppressOfficialSubscreenCallback) return@setOnCheckedChangeListener
            OfficialSubscreenServiceGate.setDisableEnabled(requireContext(), checked)
        }

        bindChargingAnimationSection()

        binding.textFeaturesTitle.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            if (now - featuresTitleLastTapUptime > TITLE_TRIPLE_TAP_WINDOW_MS) {
                featuresTitleTapCount = 0
            }
            featuresTitleLastTapUptime = now
            featuresTitleTapCount++
            if (featuresTitleTapCount >= 3) {
                featuresTitleTapCount = 0
                CarControlEntry.openCarControlSettingsFromFeatures(requireContext())
            }
        }

        binding.buttonOpenRearRecord.setOnClickListener {
            val snap = viewModel.snapshot.value
            if (snap == null || !snap.privileged) {
                Toast.makeText(requireContext(), R.string.privilege_shell_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!RuntimePermissionGate.hasOverlay(requireContext())) {
                Toast.makeText(requireContext(), R.string.record_need_overlay, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}"),
                    ),
                )
                return@setOnClickListener
            }
            if (!RuntimePermissionGate.canPostNotifications(requireContext())) {
                Toast.makeText(requireContext(), R.string.record_need_post_notifications, Toast.LENGTH_LONG).show()
                startActivity(RuntimePermissionGate.intentAppNotificationSettings(requireContext()))
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), RearScreenRecordService::class.java),
            )
        }

        binding.switchScreenshotShell.setOnCheckedChangeListener { _, checked ->
            DeviceGeometry.persistScreenshotShellEnabled(requireContext(), checked)
        }

        binding.buttonCompositeXy.setOnClickListener {
            showCompositeXyDialog()
        }

        binding.buttonStickerOverlay.setOnClickListener {
            showStickerOverlayDialog()
        }

        binding.buttonScreenshot.setOnClickListener {
            if (!requirePrivilege()) return@setOnClickListener
            val composite = binding.switchScreenshotShell.isChecked
            RearScreenshotCoordinator.capture(requireContext(), composite) { ok, msg ->
                toastResult(ok, msg)
            }
        }

        bindRearAssistSection()
    }

    private fun bindChargingAnimationSection() {
        setupChargingFillSpeedSliderCompose()
        syncChargingAnimationUiFromPrefs()
        binding.switchChargingAnimation.setOnCheckedChangeListener { _, checked ->
            if (suppressChargingAnimationCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressChargingAnimationCallbacks = true
                binding.switchChargingAnimation.isChecked = !checked
                suppressChargingAnimationCallbacks = false
                return@setOnCheckedChangeListener
            }
            ChargingAnimationPrefs.setEnabled(requireContext(), checked)
            ChargingServiceSync.sync(requireContext(), viewModel.snapshot.value?.privileged == true)
        }
        binding.switchChargingAlwaysOn.setOnCheckedChangeListener { _, checked ->
            if (suppressChargingAlwaysOnCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressChargingAlwaysOnCallbacks = true
                binding.switchChargingAlwaysOn.isChecked = !checked
                suppressChargingAlwaysOnCallbacks = false
                return@setOnCheckedChangeListener
            }
            ChargingAnimationPrefs.setAlwaysOn(requireContext(), checked)
            sendReloadChargingSettingsBroadcast(requireContext())
        }
        binding.switchChargingDebugMain.setOnCheckedChangeListener { _, checked ->
            if (suppressChargingDebugMainCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressChargingDebugMainCallbacks = true
                binding.switchChargingDebugMain.isChecked = !checked
                suppressChargingDebugMainCallbacks = false
                return@setOnCheckedChangeListener
            }
            ChargingAnimationPrefs.setDebugMainScreenOnly(requireContext(), checked)
            sendReloadChargingSettingsBroadcast(requireContext())
        }
        binding.textChargingDebugMainLabel.setOnClickListener {
            startChargingAnimationMainPreview()
        }
    }

    private fun setupChargingFillSpeedSliderCompose() {
        binding.composeChargingFillSpeedSlider.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeChargingFillSpeedSlider.setContent {
            ChargingFillSpeedSlider(
                stepState = chargingFillSpeedStepState,
                onStepChangeWhileDragging = { step ->
                    binding.textChargingFillSpeedValue.text = getString(
                        R.string.features_charging_fill_speed_fmt,
                        ChargingAnimationPrefs.fillDurationMsForFullFill(
                            fillSpeedPercentFromStep(step),
                        ),
                    )
                },
                onValueChangeFinished = {
                    val ctx = requireContext()
                    ChargingAnimationPrefs.setFillRiseSpeedPercent(
                        ctx,
                        fillSpeedPercentFromStep(chargingFillSpeedStepState.intValue),
                    )
                    sendReloadChargingSettingsBroadcast(ctx)
                },
            )
        }
    }

    /** 步进 0–99 → 内部涨水参数 25%–300%（与 [ChargingAnimationPrefs] 一致）；界面展示为满幅时长 ms。 */
    private fun fillSpeedPercentFromStep(step: Int): Int {
        val s = step.coerceIn(0, 99)
        return 25 + s * (ChargingAnimationPrefs.MAX_FILL_RISE_SPEED_PERCENT - ChargingAnimationPrefs.MIN_FILL_RISE_SPEED_PERCENT) / 99
    }

    private fun fillSpeedStepFromPercent(percent: Int): Int {
        val p = percent.coerceIn(
            ChargingAnimationPrefs.MIN_FILL_RISE_SPEED_PERCENT,
            ChargingAnimationPrefs.MAX_FILL_RISE_SPEED_PERCENT,
        )
        val span = (ChargingAnimationPrefs.MAX_FILL_RISE_SPEED_PERCENT - ChargingAnimationPrefs.MIN_FILL_RISE_SPEED_PERCENT).toFloat()
        return (((p - ChargingAnimationPrefs.MIN_FILL_RISE_SPEED_PERCENT) * 99f / span).roundToInt()).coerceIn(0, 99)
    }

    private fun syncChargingAnimationUiFromPrefs() {
        suppressChargingAnimationCallbacks = true
        binding.switchChargingAnimation.isChecked =
            ChargingAnimationPrefs.isEnabled(requireContext())
        suppressChargingAnimationCallbacks = false
        suppressChargingAlwaysOnCallbacks = true
        binding.switchChargingAlwaysOn.isChecked =
            ChargingAnimationPrefs.isAlwaysOn(requireContext())
        suppressChargingAlwaysOnCallbacks = false
        suppressChargingDebugMainCallbacks = true
        binding.switchChargingDebugMain.isChecked =
            ChargingAnimationPrefs.isDebugMainScreenOnly(requireContext())
        suppressChargingDebugMainCallbacks = false
        val fillP = ChargingAnimationPrefs.getFillRiseSpeedPercent(requireContext())
        chargingFillSpeedStepState.intValue = fillSpeedStepFromPercent(fillP)
        binding.textChargingFillSpeedValue.text =
            getString(
                R.string.features_charging_fill_speed_fmt,
                ChargingAnimationPrefs.fillDurationMsForFullFill(fillP),
            )
        ChargingServiceSync.sync(requireContext(), viewModel.snapshot.value?.privileged == true)
    }

    private fun startChargingAnimationMainPreview() {
        val ctx = requireContext()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
        val i = Intent(ctx, RearScreenChargingActivity::class.java).apply {
            putExtra(RearScreenChargingActivity.EXTRA_BATTERY_LEVEL, level)
            putExtra(RearScreenChargingActivity.EXTRA_DEBUG_MAIN_PREVIEW, true)
        }
        startActivity(i)
    }

    private fun sendReloadChargingSettingsBroadcast(ctx: Context) {
        ctx.applicationContext.sendBroadcast(
            Intent(ChargingIntents.ACTION_RELOAD_CHARGING_SETTINGS).setPackage(ctx.packageName),
        )
    }

    private fun bindRearAssistSection() {
        val ctx = requireContext()
        setupRearWakeSliderCompose(ctx)
        syncRearAssistUiFromPrefs()

        binding.switchRearCoverDetection.setOnCheckedChangeListener { _, checked ->
            if (suppressRearAssistCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressRearAssistCallbacks = true
                binding.switchRearCoverDetection.isChecked = !checked
                suppressRearAssistCallbacks = false
                return@setOnCheckedChangeListener
            }
            RearAssistPrefs.prefs(ctx).edit().putBoolean(RearAssistPrefs.KEY_PROXIMITY, checked).commit()
            RearAssistService.sync(ctx, viewModel.snapshot.value?.privileged == true)
            RearSwitchKeeperService.syncProximityWithRearAssistPrefs(ctx)
        }

        binding.switchRearKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            if (suppressRearAssistCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressRearAssistCallbacks = true
                binding.switchRearKeepScreenOn.isChecked = !checked
                suppressRearAssistCallbacks = false
                return@setOnCheckedChangeListener
            }
            val ed = RearAssistPrefs.prefs(ctx).edit()
            ed.putBoolean(RearAssistPrefs.KEY_KEEP_SCREEN_ON, checked)
            if (checked) {
                ed.putBoolean(RearAssistPrefs.KEY_ALWAYS_WAKEUP, false)
                suppressRearAssistCallbacks = true
                binding.switchRearAlwaysWakeup.isChecked = false
                binding.textRearBurnInHint.visibility = View.GONE
                suppressRearAssistCallbacks = false
            }
            ed.commit()
            ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
                .putBoolean(FLUTTER_KEEP_SCREEN_ON_KEY, checked)
                .commit()
            try {
                RearScreenWakeService.requestNotificationRefresh(ctx)
            } catch (_: Exception) {
            }
            notifyRearSwitchKeeperKeepScreenFromPrefs(ctx)
            RearAssistService.sync(ctx, viewModel.snapshot.value?.privileged == true)
        }

        binding.switchRearAlwaysWakeup.setOnCheckedChangeListener { _, checked ->
            if (suppressRearAssistCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressRearAssistCallbacks = true
                binding.switchRearAlwaysWakeup.isChecked = !checked
                suppressRearAssistCallbacks = false
                return@setOnCheckedChangeListener
            }
            val ed = RearAssistPrefs.prefs(ctx).edit()
            ed.putBoolean(RearAssistPrefs.KEY_ALWAYS_WAKEUP, checked)
            if (checked) {
                ed.putBoolean(RearAssistPrefs.KEY_KEEP_SCREEN_ON, false)
                ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
                    .putBoolean(FLUTTER_KEEP_SCREEN_ON_KEY, false)
                    .commit()
                suppressRearAssistCallbacks = true
                binding.switchRearKeepScreenOn.isChecked = false
                binding.textRearBurnInHint.visibility = View.VISIBLE
                suppressRearAssistCallbacks = false
                try {
                    RearScreenWakeService.requestNotificationRefresh(ctx)
                } catch (_: Exception) {
                }
            } else {
                binding.textRearBurnInHint.visibility = View.GONE
            }
            ed.commit()
            notifyRearSwitchKeeperKeepScreenFromPrefs(ctx)
            RearAssistService.sync(ctx, viewModel.snapshot.value?.privileged == true)
            promptPostNotificationsIfNeededForRearAssist(checked)
        }
    }

    /** 应用投屏 Keeper 内 WAKEUP 与 WakeLock 与 [RearAssistPrefs.isKeepScreenOnEnabled] 一致。 */
    private fun notifyRearSwitchKeeperKeepScreenFromPrefs(ctx: Context) {
        try {
            val on = RearAssistPrefs.isKeepScreenOnEnabled(ctx)
            ctx.startService(
                Intent(ctx, RearSwitchKeeperService::class.java)
                    .setAction(RearSwitchKeeperService.ACTION_SET_KEEP_SCREEN_ON_ENABLED)
                    .putExtra("enabled", on),
            )
        } catch (_: Exception) {
        }
    }

    /** Android 13+：开启「始终常亮」时提示通知权限，否则看不到该前台服务通知。 */
    private fun promptPostNotificationsIfNeededForRearAssist(enabled: Boolean) {
        if (!enabled) return
        val ctx = requireContext()
        if (RuntimePermissionGate.canPostNotifications(ctx)) return
        Toast.makeText(ctx, R.string.rear_assist_need_post_notifications, Toast.LENGTH_LONG).show()
        startActivity(RuntimePermissionGate.intentAppNotificationSettings(ctx))
    }

    private fun setupRearWakeSliderCompose(ctx: Context) {
        binding.composeRearWakeSlider.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeRearWakeSlider.setContent {
            RearWakeIntervalSlider(
                stepState = wakeSliderStepState,
                onStepChangeWhileDragging = { step ->
                    binding.textRearWakeIntervalValue.text =
                        getString(R.string.features_rear_wake_interval_fmt, rearWakeMsFromStep(step))
                },
                onValueChangeFinished = {
                    if (!requirePrivilege()) {
                        syncRearAssistUiFromPrefs()
                    } else {
                        val ms = rearWakeMsFromStep(wakeSliderStepState.intValue)
                        RearAssistPrefs.prefs(ctx).edit().putInt(RearAssistPrefs.KEY_INTERVAL_MS, ms).apply()
                        RearSwitchKeeperService.syncWakeIntervalFromPrefs(ctx)
                        RearAssistService.sync(ctx, viewModel.snapshot.value?.privileged == true)
                    }
                },
            )
        }
    }

    private fun syncRearAssistUiFromPrefs() {
        suppressRearAssistCallbacks = true
        val ctx = requireContext()
        val p = RearAssistPrefs.prefs(ctx)
        val flutterKeep = ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getBoolean(FLUTTER_KEEP_SCREEN_ON_KEY, true)
        binding.switchRearCoverDetection.isChecked = p.getBoolean(RearAssistPrefs.KEY_PROXIMITY, false)
        binding.switchRearKeepScreenOn.isChecked = flutterKeep
        val nativeKeep = p.getBoolean(RearAssistPrefs.KEY_KEEP_SCREEN_ON, false)
        if (nativeKeep != flutterKeep) {
            p.edit().putBoolean(RearAssistPrefs.KEY_KEEP_SCREEN_ON, flutterKeep).apply()
        }
        binding.switchRearAlwaysWakeup.isChecked =
            p.getBoolean(RearAssistPrefs.KEY_ALWAYS_WAKEUP, false)
        binding.textRearBurnInHint.visibility =
            if (binding.switchRearAlwaysWakeup.isChecked) View.VISIBLE else View.GONE
        val ms = RearAssistPrefs.intervalMs(ctx)
        wakeSliderStepState.intValue = rearWakeStepFromMs(ms)
        binding.textRearWakeIntervalValue.text =
            getString(R.string.features_rear_wake_interval_fmt, ms)
        suppressRearAssistCallbacks = false
        RearAssistService.sync(ctx, viewModel.snapshot.value?.privileged == true)
        RearSwitchKeeperService.syncProximityWithRearAssistPrefs(ctx)
    }

    /** 0–99 步进映射到 100–10000 ms。 */
    private fun rearWakeMsFromStep(step: Int): Int {
        val s = step.coerceIn(0, 99)
        return 100 + s * (10_000 - 100) / 99
    }

    private fun rearWakeStepFromMs(ms: Int): Int {
        val m = ms.coerceIn(100, 10_000)
        return ((m - 100) * 99f / (10_000 - 100)).toInt().coerceIn(0, 99)
    }

    private fun bindShellBackdropControls() {
        val ctx = requireContext()
        binding.chipGroupShellColor.setOnCheckedStateChangeListener(
            ChipGroup.OnCheckedStateChangeListener { _, checkedIds ->
                if (suppressShellCallbacks) return@OnCheckedStateChangeListener
                if (checkedIds.isEmpty()) {
                    suppressShellCallbacks = true
                    binding.chipGroupShellColor.check(lastValidShellChipId)
                    suppressShellCallbacks = false
                    updateShellColorChipLabels()
                    return@OnCheckedStateChangeListener
                }
                val id = checkedIds.first()
                lastValidShellChipId = id
                val key = shellColorKeyForChipId(id)
                val ok = DeviceGeometry.persistShellBackdropSelection(
                    ctx,
                    key,
                    binding.switchPromaxShellFull.isChecked,
                )
                if (!ok) {
                    Toast.makeText(ctx, R.string.features_shell_persist_fail, Toast.LENGTH_LONG).show()
                }
                updateShellColorChipLabels()
            },
        )

        binding.switchPromaxShellFull.setOnCheckedChangeListener { _, checked ->
            if (suppressShellCallbacks) return@setOnCheckedChangeListener
            val key = colorKeyFromCheckedChip()
            val ok = DeviceGeometry.persistShellBackdropSelection(ctx, key, checked)
            if (!ok) {
                Toast.makeText(ctx, R.string.features_shell_persist_fail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shellColorKeyForChipId(id: Int): String =
        when (id) {
            R.id.chip_shell_white -> "white"
            R.id.chip_shell_gray -> "gray"
            R.id.chip_shell_purple -> "purple"
            else -> "green"
        }

    private fun colorKeyFromCheckedChip(): String =
        shellColorKeyForChipId(binding.chipGroupShellColor.checkedChipId)

    private fun syncShellUiFromPrefs() {
        suppressShellCallbacks = true
        val ctx = requireContext()
        val promax = DeviceGeometry.isProMaxModel()
        binding.switchPromaxShellFull.visibility = View.VISIBLE

        val key = if (promax) {
            DeviceGeometry.readPromaxBackColorKey(ctx)
        } else {
            DeviceGeometry.readProBackColorKey(ctx)
        }
        val chipId = when (key) {
            "white" -> R.id.chip_shell_white
            "gray" -> R.id.chip_shell_gray
            "purple" -> R.id.chip_shell_purple
            else -> R.id.chip_shell_green
        }
        binding.chipGroupShellColor.check(chipId)
        lastValidShellChipId = chipId

        binding.switchPromaxShellFull.isChecked = DeviceGeometry.isShellFullBackdrop(ctx)
        binding.switchScreenshotShell.isChecked = DeviceGeometry.isScreenshotShellEnabled(ctx)
        suppressShellCallbacks = false
        updateShellColorChipLabels()
    }

    /** 勾选标记在文字后（Material Chip 默认勾选图标在左侧且不可改位置）。 */
    private fun updateShellColorChipLabels() {
        val ctx = requireContext()
        fun apply(chip: Chip, strId: Int) {
            val base = ctx.getString(strId)
            chip.text = if (chip.isChecked) {
                ctx.getString(R.string.shell_color_label_checked, base)
            } else {
                base
            }
        }
        apply(binding.chipShellGreen, R.string.shell_color_green)
        apply(binding.chipShellWhite, R.string.shell_color_white)
        apply(binding.chipShellGray, R.string.shell_color_gray)
        apply(binding.chipShellPurple, R.string.shell_color_purple)
        applyShellChipSelectionColors()
    }

    /**
     * 选中态：底图与描边使用该 Chip 的文案色（与未选中时文字颜色一致）；选中时文字按亮度反色保证可读。
     */
    private fun applyShellChipSelectionColors() {
        val ctx = requireContext()
        val surface = ContextCompat.getColor(ctx, R.color.mi_card_surface)
        val strokeUnchecked = ContextCompat.getColor(ctx, R.color.mi_divider)
        val onLightFill = ContextCompat.getColor(ctx, R.color.mi_text_primary)

        fun styleChip(chip: Chip, labelColorRes: Int) {
            val label = ContextCompat.getColor(ctx, labelColorRes)
            val checkedText =
                if (ColorUtils.calculateLuminance(label) > 0.55) onLightFill else Color.WHITE
            chip.chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(label, surface),
            )
            chip.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(checkedText, label),
                ),
            )
            chip.chipStrokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(label, strokeUnchecked),
            )
            chip.chipStrokeWidth = resources.getDimension(R.dimen.mi_card_stroke)
        }

        styleChip(binding.chipShellGreen, R.color.shell_chip_green)
        styleChip(binding.chipShellWhite, R.color.shell_chip_white)
        styleChip(binding.chipShellGray, R.color.shell_chip_gray)
        styleChip(binding.chipShellPurple, R.color.shell_chip_purple)
    }

    /** 首次进入时若 work 目录无底图，则按当前偏好从 assets 拷贝一份（与旧版切换后写入目录一致）。 */
    private fun ensureShellCopiedToWorkDir() {
        val ctx = requireContext()
        val name = DeviceGeometry.phoneBackFileName(ctx)
        val f = File(DeviceGeometry.screenshotWorkDir(ctx), name)
        if (f.isFile && f.length() > 0L) return
        val key = if (DeviceGeometry.isProMaxModel()) {
            DeviceGeometry.readPromaxBackColorKey(ctx)
        } else {
            DeviceGeometry.readProBackColorKey(ctx)
        }
        val full = DeviceGeometry.isShellFullBackdrop(ctx)
        DeviceGeometry.persistShellBackdropSelection(ctx, key, full)
    }

    private fun requirePrivilege(): Boolean {
        val ok = viewModel.snapshot.value?.privileged == true
        if (!ok) {
            Toast.makeText(requireContext(), R.string.privilege_shell_required, Toast.LENGTH_LONG).show()
        }
        return ok
    }

    private fun toastResult(ok: Boolean, msg: String) {
        Toast.makeText(
            requireContext(),
            msg,
            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
    }

    private fun showCompositeXyDialog() {
        val ctx = requireContext()
        val dialogBinding = DialogCompositeXyBinding.inflate(layoutInflater)
        fun fill() {
            val h = DeviceGeometry.compositeXYForHalfBackdrop(ctx)
            val f = DeviceGeometry.compositeXYForFullBackdrop(ctx)
            dialogBinding.editHalfX.setText(h[0].toString())
            dialogBinding.editHalfY.setText(h[1].toString())
            dialogBinding.editFullX.setText(f[0].toString())
            dialogBinding.editFullY.setText(f[1].toString())
        }
        fill()
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.features_composite_xy_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.settings_save_overlay, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialogBinding.buttonResetCompositeXy.setOnClickListener {
            DeviceGeometry.resetCompositeXYOverrides(ctx)
            fill()
            Toast.makeText(ctx, R.string.settings_overlay_reset_ok, Toast.LENGTH_SHORT).show()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hx = parseCoord(dialogBinding.editHalfX.text?.toString().orEmpty())
                val hy = parseCoord(dialogBinding.editHalfY.text?.toString().orEmpty())
                val fx = parseCoord(dialogBinding.editFullX.text?.toString().orEmpty())
                val fy = parseCoord(dialogBinding.editFullY.text?.toString().orEmpty())
                if (hx == null || hy == null || fx == null || fy == null) {
                    Toast.makeText(ctx, R.string.settings_overlay_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                DeviceGeometry.persistCompositeXYHalf(ctx, hx, hy)
                DeviceGeometry.persistCompositeXYFull(ctx, fx, fy)
                Toast.makeText(ctx, R.string.settings_overlay_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parseCoord(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val v = t.toIntOrNull() ?: return null
        if (v < 0 || v > 4096) return null
        return v
    }

    private fun showStickerOverlayDialog() {
        val ctx = requireContext()
        val dialogBinding = DialogStickerOverlayBinding.inflate(layoutInflater)
        stickerDialogBinding = dialogBinding
        fillStickerDialogFields(dialogBinding)
        syncStickerDialogFileRow(dialogBinding)
        refreshStickerPreview(dialogBinding)
        val dialog = MaterialAlertDialogBuilder(ctx, R.style.ThemeOverlay_MiRoot_StickerDialog)
            .setTitle(R.string.features_sticker_overlay_dialog_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.features_sticker_export_preview, null)
            .setNeutralButton(R.string.features_sticker_preview_fullscreen, null)
            .setPositiveButton(R.string.features_sticker_save, null)
            .create()
        dialog.setOnDismissListener {
            stickerPreviewDebounce?.let { stickerPreviewHandler.removeCallbacks(it) }
            stickerPreviewDebounce = null
            recycleStickerPreviewBitmap(dialogBinding.imageStickerPreview)
            stickerDialogBinding = null
        }
        val debouncedPreview = {
            scheduleStickerPreviewRefresh(dialogBinding)
        }
        val toggleListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            debouncedPreview()
        }
        dialogBinding.switchStickerEnabled.setOnCheckedChangeListener(toggleListener)
        dialogBinding.switchStickerRounded.setOnCheckedChangeListener { _, _ ->
            applyStickerRoundedUiState(dialogBinding)
            debouncedPreview()
        }
        dialogBinding.switchStickerCenterHorizontal.setOnCheckedChangeListener { _, _ ->
            applyStickerCenterUiState(dialogBinding)
            debouncedPreview()
        }
        dialogBinding.switchStickerCenterVertical.setOnCheckedChangeListener { _, _ ->
            applyStickerVerticalUiState(dialogBinding)
            debouncedPreview()
        }
        val xyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressStickerFieldWatchers) return
                debouncedPreview()
            }
        }
        dialogBinding.editStickerX.addTextChangedListener(xyWatcher)
        dialogBinding.editStickerY.addTextChangedListener(xyWatcher)
        dialogBinding.editStickerW.addTextChangedListener(xyWatcher)
        dialogBinding.editStickerH.addTextChangedListener(xyWatcher)
        dialogBinding.editStickerCornerDp.addTextChangedListener(xyWatcher)
        dialogBinding.toggleStickerScaleMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) debouncedPreview()
        }
        dialogBinding.buttonStickerPick.setOnClickListener {
            pickStickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        dialogBinding.buttonStickerClear.setOnClickListener {
            DeviceGeometry.stickerOverlayFile(ctx).delete()
            syncStickerDialogFileRow(dialogBinding)
            refreshStickerPreview(dialogBinding)
            Toast.makeText(ctx, R.string.features_sticker_cleared, Toast.LENGTH_SHORT).show()
        }
        dialogBinding.buttonResetSticker.setOnClickListener {
            DeviceGeometry.resetStickerOverlay(ctx)
            fillStickerDialogFields(dialogBinding)
            syncStickerDialogFileRow(dialogBinding)
            refreshStickerPreview(dialogBinding)
            Toast.makeText(ctx, R.string.settings_overlay_reset_ok, Toast.LENGTH_SHORT).show()
        }
        dialogBinding.imageStickerPreview.setOnClickListener {
            showStickerPreviewFullscreen(buildStickerPreviewOverlay(dialogBinding))
        }
        dialog.show()
        val dm = ctx.resources.displayMetrics
        val dialogW = (dm.widthPixels * 0.98f).toInt()
        val dialogH = (dm.heightPixels * 0.95f).toInt()
        dialog.window?.setLayout(dialogW, dialogH)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            exportStickerPreview(dialogBinding)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            showStickerPreviewFullscreen(buildStickerPreviewOverlay(dialogBinding))
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val x = parseCoord(dialogBinding.editStickerX.text?.toString().orEmpty())
            val y = parseCoord(dialogBinding.editStickerY.text?.toString().orEmpty())
            val w = parseStickerSizeForSave(dialogBinding.editStickerW.text?.toString().orEmpty())
            val h = parseStickerSizeForSave(dialogBinding.editStickerH.text?.toString().orEmpty())
            if (x == null || y == null || w == null || h == null) {
                Toast.makeText(ctx, R.string.settings_overlay_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if ((w > 0) xor (h > 0)) {
                Toast.makeText(ctx, R.string.features_sticker_wh_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cornerDp = parseCornerDpForSave(dialogBinding.editStickerCornerDp.text?.toString().orEmpty())
            if (cornerDp == null) {
                Toast.makeText(ctx, R.string.features_sticker_corner_dp_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DeviceGeometry.persistStickerEnabled(ctx, dialogBinding.switchStickerEnabled.isChecked)
            DeviceGeometry.persistStickerRounded(ctx, dialogBinding.switchStickerRounded.isChecked)
            DeviceGeometry.persistStickerCornerRadiusDp(ctx, cornerDp)
            DeviceGeometry.persistStickerCenterHorizontal(
                ctx,
                dialogBinding.switchStickerCenterHorizontal.isChecked,
            )
            DeviceGeometry.persistStickerCenterVertical(
                ctx,
                dialogBinding.switchStickerCenterVertical.isChecked,
            )
            DeviceGeometry.persistStickerScaleMode(ctx, stickerScaleModeFromDialog(dialogBinding))
            if (DeviceGeometry.isShellFullBackdrop(ctx)) {
                DeviceGeometry.persistStickerXYFull(ctx, x, y)
                DeviceGeometry.persistStickerSizeFull(ctx, w, h)
            } else {
                DeviceGeometry.persistStickerXYHalf(ctx, x, y)
                DeviceGeometry.persistStickerSizeHalf(ctx, w, h)
            }
            Toast.makeText(ctx, R.string.features_sticker_saved, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }

    private fun fillStickerDialogFields(b: DialogStickerOverlayBinding) {
        val ctx = requireContext()
        suppressStickerFieldWatchers = true
        try {
            b.switchStickerEnabled.isChecked = DeviceGeometry.isStickerEnabled(ctx)
            b.switchStickerRounded.isChecked = DeviceGeometry.isStickerRounded(ctx)
            b.switchStickerCenterHorizontal.isChecked = DeviceGeometry.isStickerCenterHorizontal(ctx)
            b.switchStickerCenterVertical.isChecked = DeviceGeometry.isStickerCenterVertical(ctx)
            val full = DeviceGeometry.isShellFullBackdrop(ctx)
            b.editStickerCornerDp.setText(DeviceGeometry.stickerCornerRadiusDp(ctx).toString())
            applyStickerRoundedUiState(b)
            val xy = if (full) {
                DeviceGeometry.stickerXYForFullBackdrop(ctx)
            } else {
                DeviceGeometry.stickerXYForHalfBackdrop(ctx)
            }
            val wh = if (full) {
                DeviceGeometry.stickerSizeForFullBackdrop(ctx)
            } else {
                DeviceGeometry.stickerSizeForHalfBackdrop(ctx)
            }
            b.editStickerX.setText(xy[0].toString())
            b.editStickerY.setText(xy[1].toString())
            b.editStickerW.setText(wh[0].toString())
            b.editStickerH.setText(wh[1].toString())
            applyStickerScaleModeToToggle(b, DeviceGeometry.stickerScaleMode(ctx))
            applyStickerCenterUiState(b)
            applyStickerVerticalUiState(b)
            applyStickerWhInputPlaceholders(b)
        } finally {
            suppressStickerFieldWatchers = false
        }
    }

    /**
     * 宽/高输入框内灰色占位：W 固定 1080；H 为当前贴图文件高度像素（无贴图则清空占位）。
     */
    private fun applyStickerWhInputPlaceholders(b: DialogStickerOverlayBinding) {
        val ctx = requireContext()
        b.tilStickerW.placeholderText = ctx.getString(R.string.features_sticker_w_placeholder)
        val file = DeviceGeometry.stickerOverlayFile(ctx)
        b.tilStickerH.placeholderText =
            if (file.isFile && file.length() > 0L) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outHeight > 0) opts.outHeight.toString() else null
            } else {
                null
            }
    }

    private fun syncStickerDialogFileRow(b: DialogStickerOverlayBinding) {
        val f = DeviceGeometry.stickerOverlayFile(requireContext())
        b.buttonStickerPick.text = if (f.isFile && f.length() > 0L) {
            getString(R.string.features_sticker_pick_image_selected)
        } else {
            getString(R.string.features_sticker_pick_image)
        }
        applyStickerWhInputPlaceholders(b)
    }

    private fun scheduleStickerPreviewRefresh(b: DialogStickerOverlayBinding) {
        stickerPreviewDebounce?.let { stickerPreviewHandler.removeCallbacks(it) }
        val run = Runnable {
            if (stickerDialogBinding === b && isAdded) {
                refreshStickerPreview(b)
            }
        }
        stickerPreviewDebounce = run
        stickerPreviewHandler.postDelayed(run, 400L)
    }

    private fun buildStickerPreviewOverlay(b: DialogStickerOverlayBinding): StickerPreviewOverlay {
        val ctx = requireContext()
        val f = DeviceGeometry.stickerOverlayFile(ctx)
        val hasFile = f.isFile && f.length() > 0L
        val xy = resolveStickerPreviewXY(b)
        val wh = resolveStickerPreviewWH(b)
        return StickerPreviewOverlay(
            drawSticker = hasFile,
            rounded = b.switchStickerRounded.isChecked,
            x = xy[0],
            y = xy[1],
            targetW = wh[0],
            targetH = wh[1],
            scaleMode = stickerScaleModeFromDialog(b),
            centerStickerHorizontal = b.switchStickerCenterHorizontal.isChecked,
            centerStickerVertical = b.switchStickerCenterVertical.isChecked,
            cornerRadiusDp = resolveStickerPreviewCornerDp(b),
        )
    }

    private fun stickerScaleModeFromDialog(b: DialogStickerOverlayBinding): StickerScaleMode {
        return when (b.toggleStickerScaleMode.checkedButtonId) {
            R.id.button_sticker_scale_fit -> StickerScaleMode.FIT
            R.id.button_sticker_scale_crop -> StickerScaleMode.CROP
            else -> StickerScaleMode.STRETCH
        }
    }

    private fun applyStickerScaleModeToToggle(b: DialogStickerOverlayBinding, mode: StickerScaleMode) {
        val id = when (mode) {
            StickerScaleMode.STRETCH -> R.id.button_sticker_scale_stretch
            StickerScaleMode.FIT -> R.id.button_sticker_scale_fit
            StickerScaleMode.CROP -> R.id.button_sticker_scale_crop
        }
        if (b.toggleStickerScaleMode.checkedButtonId != id) {
            b.toggleStickerScaleMode.check(id)
        }
    }

    private fun applyStickerRoundedUiState(b: DialogStickerOverlayBinding) {
        val on = b.switchStickerRounded.isChecked
        b.tilStickerCornerDp.isEnabled = on
        b.editStickerCornerDp.isEnabled = on
    }

    private fun resolveStickerPreviewCornerDp(b: DialogStickerOverlayBinding): Int {
        val ctx = requireContext()
        val def = DeviceGeometry.stickerCornerRadiusDp(ctx)
        val raw = b.editStickerCornerDp.text?.toString().orEmpty()
        if (raw.isBlank()) return def
        return parseCornerDpForSave(raw) ?: def
    }

    /** 保存与预览用：0–[DeviceGeometry.STICKER_CORNER_RADIUS_DP_MAX]。 */
    private fun parseCornerDpForSave(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val v = t.toIntOrNull() ?: return null
        if (v < 0 || v > DeviceGeometry.STICKER_CORNER_RADIUS_DP_MAX) return null
        return v
    }

    private fun applyStickerCenterUiState(b: DialogStickerOverlayBinding) {
        val lockX = b.switchStickerCenterHorizontal.isChecked
        b.tilStickerX.isEnabled = !lockX
        b.editStickerX.isEnabled = !lockX
        if (lockX) {
            refreshStickerCenteredXDisplay(b)
        }
    }

    private fun applyStickerVerticalUiState(b: DialogStickerOverlayBinding) {
        val lockY = b.switchStickerCenterVertical.isChecked
        b.tilStickerY.isEnabled = !lockY
        b.editStickerY.isEnabled = !lockY
        if (lockY) {
            refreshStickerCenteredYDisplay(b)
        }
    }

    /**
     * 水平居中锁定时，将 X 输入框更新为与 [ShellStickerOverlay] 一致的等效像素（画布坐标）。
     */
    private fun refreshStickerCenteredXDisplay(b: DialogStickerOverlayBinding) {
        if (!b.switchStickerCenterHorizontal.isChecked) return
        val ctx = requireContext()
        val canvasSz = DeviceGeometry.phoneBackBitmapPixelSize(ctx) ?: DeviceGeometry.canvasSize(ctx)
        val cw = canvasSz[0]
        val wh = resolveStickerPreviewWH(b)
        val tw = wh[0]
        val th = wh[1]
        val mode = stickerScaleModeFromDialog(b)
        val file = DeviceGeometry.stickerOverlayFile(ctx)

        val drawW: Int? = when {
            tw > 0 && th > 0 -> {
                if (file.isFile && file.length() > 0L) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    val ow = opts.outWidth
                    val oh = opts.outHeight
                    if (ow > 0 && oh > 0) {
                        StickerScaleHelper.drawSizeInSlot(ow, oh, tw, th, mode).first
                    } else {
                        tw
                    }
                } else {
                    tw
                }
            }
            file.isFile && file.length() > 0L -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.outWidth.takeIf { it > 0 }
            }
            else -> null
        }
        if (drawW == null || drawW <= 0) return
        val xDisplay = (
            max(0, cw - drawW) / 2f +
                ShellStickerOverlay.STICKER_HORIZONTAL_CENTER_OFFSET_PX
            ).roundToInt()
        suppressStickerFieldWatchers = true
        try {
            b.editStickerX.setText(xDisplay.toString())
        } finally {
            suppressStickerFieldWatchers = false
        }
    }

    /**
     * 垂直居中锁定时，将 Y 输入框更新为与 [ShellStickerOverlay.stickerVerticalCenterTopPx] 一致的顶边像素（画布坐标；
     * 高度取 **槽位内实际绘制高度**（与合成一致，FIT 时可能小于设置 H）。
     */
    private fun refreshStickerCenteredYDisplay(b: DialogStickerOverlayBinding) {
        if (!b.switchStickerCenterVertical.isChecked) return
        val ctx = requireContext()
        val canvasSz = DeviceGeometry.phoneBackBitmapPixelSize(ctx) ?: DeviceGeometry.canvasSize(ctx)
        val ch = canvasSz[1]
        val wh = resolveStickerPreviewWH(b)
        val tw = wh[0]
        val th = wh[1]
        val mode = stickerScaleModeFromDialog(b)
        val file = DeviceGeometry.stickerOverlayFile(ctx)

        val drawH: Int? = when {
            tw > 0 && th > 0 -> {
                if (file.isFile && file.length() > 0L) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    val ow = opts.outWidth
                    val oh = opts.outHeight
                    if (ow > 0 && oh > 0) {
                        StickerScaleHelper.drawSizeInSlot(ow, oh, tw, th, mode).second
                    } else {
                        th
                    }
                } else {
                    th
                }
            }
            file.isFile && file.length() > 0L -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.outHeight.takeIf { it > 0 }
            }
            else -> null
        }
        if (drawH == null || drawH <= 0) return
        val yDisplay = ShellStickerOverlay.stickerVerticalCenterTopPx(ch, drawH).roundToInt()
        suppressStickerFieldWatchers = true
        try {
            b.editStickerY.setText(yDisplay.toString())
        } finally {
            suppressStickerFieldWatchers = false
        }
    }

    private fun resolveStickerPreviewXY(b: DialogStickerOverlayBinding): IntArray {
        val ctx = requireContext()
        val def = if (DeviceGeometry.isShellFullBackdrop(ctx)) {
            DeviceGeometry.stickerXYForFullBackdrop(ctx)
        } else {
            DeviceGeometry.stickerXYForHalfBackdrop(ctx)
        }
        val x = parseCoord(b.editStickerX.text?.toString().orEmpty()) ?: def[0]
        val y = parseCoord(b.editStickerY.text?.toString().orEmpty()) ?: def[1]
        return intArrayOf(x, y)
    }

    private fun resolveStickerPreviewWH(b: DialogStickerOverlayBinding): IntArray {
        val ctx = requireContext()
        val def = if (DeviceGeometry.isShellFullBackdrop(ctx)) {
            DeviceGeometry.stickerSizeForFullBackdrop(ctx)
        } else {
            DeviceGeometry.stickerSizeForHalfBackdrop(ctx)
        }
        val ws = b.editStickerW.text?.toString().orEmpty()
        val hs = b.editStickerH.text?.toString().orEmpty()
        val w = if (ws.isBlank()) def[0] else (parseStickerSizeForSave(ws) ?: def[0])
        val h = if (hs.isBlank()) def[1] else (parseStickerSizeForSave(hs) ?: def[1])
        if ((w > 0) xor (h > 0)) {
            return intArrayOf(def[0], def[1])
        }
        return intArrayOf(w, h)
    }

    /** 空串视为 0（原始尺寸）。 */
    private fun parseStickerSizeForSave(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return 0
        val v = t.toIntOrNull() ?: return null
        if (v < 0 || v > 4096) return null
        return v
    }

    private fun autofillStickerDimensionsFromFile(ctx: Context, b: DialogStickerOverlayBinding) {
        val full = DeviceGeometry.isShellFullBackdrop(ctx)
        val size = if (full) {
            DeviceGeometry.stickerSizeForFullBackdrop(ctx)
        } else {
            DeviceGeometry.stickerSizeForHalfBackdrop(ctx)
        }
        if (size[0] != 0 || size[1] != 0) return
        val path = DeviceGeometry.stickerOverlayFile(ctx).absolutePath
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return
        b.editStickerW.setText(opts.outWidth.toString())
        b.editStickerH.setText(opts.outHeight.toString())
    }

    private fun showStickerPreviewFullscreen(overlay: StickerPreviewOverlay) {
        val ctx = requireContext()
        val fullDialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(ctx)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setBackgroundColor(Color.BLACK)
        imageView.contentDescription = getString(R.string.features_sticker_preview_fullscreen_cd)
        fullDialog.setContentView(imageView)
        fullDialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        fullDialog.setCanceledOnTouchOutside(true)
        fullDialog.setOnDismissListener {
            val b = imageView.tag as? Bitmap
            imageView.tag = null
            imageView.setImageDrawable(null)
            b?.recycle()
        }
        imageView.setOnClickListener { fullDialog.dismiss() }
        fullDialog.show()
        thread(name = "MiRoot-StickerFullscreen") {
            val bmp = try {
                StickerPreviewRenderer.renderFullPixelForExport(ctx, overlay)
            } catch (_: OutOfMemoryError) {
                null
            } ?: try {
                StickerPreviewRenderer.render(ctx, overlay, maxSide = 2048, grayscaleBackdrop = false)
            } catch (_: OutOfMemoryError) {
                null
            }
            activity?.runOnUiThread {
                if (!fullDialog.isShowing) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    imageView.tag = bmp
                } else {
                    Toast.makeText(ctx, R.string.features_sticker_export_fail, Toast.LENGTH_SHORT).show()
                    fullDialog.dismiss()
                }
            }
        }
    }

    private fun exportStickerPreview(dialogBinding: DialogStickerOverlayBinding) {
        val ctx = requireContext()
        val overlay = buildStickerPreviewOverlay(dialogBinding)
        thread(name = "MiRoot-StickerExport") {
            val bmp = try {
                StickerPreviewRenderer.renderFullPixelForExport(ctx, overlay)
            } catch (_: OutOfMemoryError) {
                null
            }
            activity?.runOnUiThread {
                if (!isAdded) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (stickerDialogBinding !== dialogBinding) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (bmp == null) {
                    Toast.makeText(ctx, R.string.features_sticker_export_fail, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                pendingStickerExportBitmap = bmp
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                exportStickerPreviewLauncher.launch("MiRoot_sticker_preview_$stamp.png")
            }
        }
    }

    private fun refreshStickerPreview(b: DialogStickerOverlayBinding) {
        val ctx = requireContext()
        if (b.switchStickerCenterHorizontal.isChecked) {
            refreshStickerCenteredXDisplay(b)
        }
        if (b.switchStickerCenterVertical.isChecked) {
            refreshStickerCenteredYDisplay(b)
        }
        val iv = b.imageStickerPreview
        val overlay = buildStickerPreviewOverlay(b)
        recycleStickerPreviewBitmap(iv)
        thread(name = "MiRoot-StickerPreview") {
            val bmp = try {
                StickerPreviewRenderer.render(ctx, overlay, maxSide = 0, grayscaleBackdrop = false)
            } catch (_: OutOfMemoryError) {
                null
            }
            activity?.runOnUiThread {
                if (stickerDialogBinding !== b) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                    iv.tag = bmp
                }
            }
        }
    }

    private fun recycleStickerPreviewBitmap(iv: ImageView) {
        val old = iv.tag as? Bitmap
        iv.tag = null
        iv.setImageDrawable(null)
        old?.recycle()
    }

    private fun copyStickerFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val outFile = DeviceGeometry.stickerOverlayFile(context)
            outFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            } ?: return false
            outFile.isFile && outFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun render(snap: PermissionSnapshot) {
        RearAssistService.sync(requireContext(), snap.privileged)
        RearSwitchKeeperService.syncProximityWithRearAssistPrefs(requireContext())
        ChargingServiceSync.sync(requireContext(), snap.privileged)
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter(RearAssistService.ACTION_UI_REAR_PREFS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                rearAssistUiReceiver,
                f,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(rearAssistUiReceiver, f)
        }
    }

    override fun onStop() {
        try {
            requireContext().unregisterReceiver(rearAssistUiReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /** 三连击标题时，相邻两次点击允许的最大间隔（毫秒），超时则重新计数。 */
        private const val TITLE_TRIPLE_TAP_WINDOW_MS = 600L
    }
}
