package com.wmqc.miroot.ui.features
import com.wmqc.miroot.display.MainDisplayUi

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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
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
import com.wmqc.miroot.AppExecutors
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.common.showSectionHelp
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.capability.RuntimePermissionGate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.wmqc.miroot.charging.ChargingAnimationPrefs
import com.wmqc.miroot.charging.ChargingIntents
import com.wmqc.miroot.charging.ChargingBackgroundLoader
import com.wmqc.miroot.charging.ChargingBatteryLevel
import com.wmqc.miroot.charging.ChargingMascotLoader
import com.wmqc.miroot.charging.ChargingPreviewLauncher
import com.wmqc.miroot.charging.ChargingServiceSync
import com.wmqc.miroot.charging.RearScreenChargingActivity
import com.wmqc.miroot.lyrics.LyricsFontHelper
import com.wmqc.miroot.ui.music.LyricsFontImporter
import com.wmqc.miroot.databinding.DialogCompositeXyBinding
import com.wmqc.miroot.databinding.DialogStickerOverlayBinding
import com.wmqc.miroot.databinding.FragmentFeaturesBinding
import com.wmqc.miroot.rear.RearAssistPrefs
import com.wmqc.miroot.rear.RearAssistService
import com.wmqc.miroot.rear.RearSwitchKeeperService
import com.wmqc.miroot.rear.balance.RearBalanceGameLaunchHelper
import com.wmqc.miroot.rear.truthdare.RearTruthDareWheelLaunchHelper
import com.wmqc.miroot.rear.truthdare.TruthDareSettingsActivity
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
import com.wmqc.miroot.lyrics.ITaskService
import com.wmqc.miroot.lyrics.LogHelper
import com.wmqc.miroot.lyrics.RootTaskService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 与歌词/车控/磁贴共用的投屏常亮偏好键（Flutter SharedPreferences）。 */
private const val FLUTTER_KEEP_SCREEN_ON_KEY = "flutter.keep_screen_on_enabled"

class FeaturesFragment : Fragment(R.layout.fragment_features) {

    private var _binding: FragmentFeaturesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainPermissionViewModel by activityViewModels()
    private var suppressShellCallbacks = false
    private var suppressRecordScreenshotCallbacks = false

    private val rearAssistUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded || _binding == null) return
            syncRearAssistUiFromPrefs()
        }
    }
    private var lastValidShellChipId = R.id.chip_shell_green
    private var suppressRearAssistCallbacks = false
    private var suppressChargingAnimationCallbacks = false
    // 充电动画常亮已移除（3.x）：不再暴露该开关。
    private var rearDpiTaskService: ITaskService? = null
    private var rearDpiServiceBound = false
    private val rearDpiServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rearDpiTaskService = ITaskService.Stub.asInterface(service)
            if (isAdded && _binding != null) {
                loadRearDpiFromService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rearDpiTaskService = null
        }
    }

    /** 背屏旋转等逻辑使用的副屏 displayId（与歌词/车控迁屏约定一致，一般为 1）。 */
    private var rearDisplayRotation: Int = -1
    private var suppressRearRotationToggle = false
    private var rearRotationApplyInFlight = false

    private val wakeSliderStepState = mutableIntStateOf(0)
    private val chargingFillSpeedStepState = mutableIntStateOf(27)

    private val pickChargingMascotLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (!isAdded || uri == null) return@registerForActivityResult
        val ctx = requireContext()
        if (ChargingMascotLoader.copyFromUri(ctx, uri)) {
            MainDisplayUi.showToast(ctx, R.string.features_charging_mascot_picked, Toast.LENGTH_SHORT)
            syncChargingMascotStatusText()
            sendReloadChargingSettingsBroadcast(ctx)
        } else {
            MainDisplayUi.showToast(ctx, R.string.features_charging_mascot_copy_fail, Toast.LENGTH_LONG)
        }
    }

    private val pickChargingFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (!isAdded) return@registerForActivityResult
        val ctx = requireContext()
        if (uri == null) {
            syncChargingFontUiFromPrefs()
            return@registerForActivityResult
        }
        val path = LyricsFontImporter.copyImportedFont(
            ctx,
            uri,
            LyricsFontImporter.Slot.CHARGING,
        )
        if (path != null) {
            ChargingAnimationPrefs.setFont(ctx, LyricsFontHelper.ID_CUSTOM, path)
            sendReloadChargingSettingsBroadcast(ctx)
            syncChargingFontUiFromPrefs()
        } else {
            MainDisplayUi.showToast(ctx, R.string.music_font_import_failed, Toast.LENGTH_SHORT)
            syncChargingFontUiFromPrefs()
        }
    }

    private val pickChargingBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (!isAdded || uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val isVideo = ChargingBackgroundLoader.copyMediaFromUriIsVideo(ctx, uri)
        if (ChargingBackgroundLoader.copyMediaFromUri(ctx, uri)) {
            val toastRes = if (isVideo) {
                R.string.features_charging_background_video_picked
            } else {
                R.string.features_charging_background_picked
            }
            MainDisplayUi.showToast(ctx, toastRes, Toast.LENGTH_SHORT)
            syncChargingBackgroundStatusText()
            sendReloadChargingSettingsBroadcast(ctx)
        } else {
            val failRes = if (isVideo) {
                R.string.features_charging_background_video_copy_fail
            } else {
                R.string.features_charging_background_copy_fail
            }
            MainDisplayUi.showToast(ctx, failRes, Toast.LENGTH_LONG)
        }
    }

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
            MainDisplayUi.showToast(ctx, R.string.features_sticker_picked, Toast.LENGTH_SHORT)
            syncStickerDialogFileRow(b)
            autofillStickerDimensionsFromFile(ctx, b)
            b.root.post { refreshStickerPreview(b) }
        } else {
            MainDisplayUi.showToast(ctx, R.string.features_sticker_copy_fail, Toast.LENGTH_LONG)
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
        AppExecutors.runInBackground {
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
                MainDisplayUi.showToast(
                    ctx,
                    if (ok) R.string.features_sticker_export_ok else R.string.features_sticker_export_fail,
                    Toast.LENGTH_SHORT,
                )
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
        binding.textSectionFeaturesCharging.setOnLongClickListener {
            if (!requirePrivilege()) {
                return@setOnLongClickListener true
            }
            val ctx = requireContext()
            ChargingServiceSync.sync(ctx, viewModel.snapshot.value?.privileged == true)
            ChargingPreviewLauncher.requestPreview(ctx)
            val toastRes = if (ChargingAnimationPrefs.isAlwaysOn(ctx)) {
                R.string.features_charging_preview_started_always_on
            } else {
                val level = ChargingBatteryLevel.getPercent(ctx).coerceIn(0, 100)
                val seconds = ChargingAnimationPrefs.estimateChargingVisibleDurationMs(ctx, level) / 1000f
                MainDisplayUi.showToast(
                    ctx,
                    getString(R.string.features_charging_preview_started, seconds),
                    Toast.LENGTH_SHORT,
                )
                return@setOnLongClickListener true
            }
            MainDisplayUi.showToast(ctx, toastRes, Toast.LENGTH_SHORT)
            true
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
                MainDisplayUi.showToast(requireContext(), R.string.privilege_shell_required, Toast.LENGTH_LONG)
                return@setOnClickListener
            }
            if (!RuntimePermissionGate.hasOverlay(requireContext())) {
                MainDisplayUi.showToast(requireContext(), R.string.record_need_overlay, Toast.LENGTH_LONG)
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}"),
                    ),
                )
                return@setOnClickListener
            }
            if (!RuntimePermissionGate.canPostNotifications(requireContext())) {
                MainDisplayUi.showToast(requireContext(), R.string.record_need_post_notifications, Toast.LENGTH_LONG)
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
        binding.switchRecordScreenshotKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            if (suppressRecordScreenshotCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressRecordScreenshotCallbacks = true
                binding.switchRecordScreenshotKeepScreenOn.isChecked = !checked
                suppressRecordScreenshotCallbacks = false
                return@setOnCheckedChangeListener
            }
            RearAssistPrefs.prefs(requireContext()).edit()
                .putBoolean(RearAssistPrefs.KEY_RECORD_SCREENSHOT_KEEP_SCREEN_ON, checked)
                .apply()
        }
        syncRecordScreenshotUiFromPrefs()

        binding.buttonCompositeXy.setOnClickListener {
            showCompositeXyDialog()
        }

        binding.buttonStickerOverlay.setOnClickListener {
            showStickerOverlayDialog()
        }

        binding.buttonRearGestureConfig.setOnClickListener {
            startActivity(Intent(requireContext(), RearGestureConfigActivity::class.java))
        }

        binding.buttonBalanceGameOpenRear.setOnClickListener {
            RearBalanceGameLaunchHelper.requestOpenBalanceGame(requireContext().applicationContext)
        }

        binding.buttonTruthDareSettings.setOnClickListener {
            startActivity(Intent(requireContext(), TruthDareSettingsActivity::class.java))
        }
        binding.buttonTruthDareOpenRear.setOnClickListener {
            RearTruthDareWheelLaunchHelper.requestOpenTruthDareWheel(requireContext().applicationContext)
        }

        binding.buttonScreenshot.setOnClickListener {
            if (!requirePrivilege()) return@setOnClickListener
            val composite = binding.switchScreenshotShell.isChecked
            RearScreenshotCoordinator.capture(requireContext(), composite) { ok, msg ->
                toastResult(ok, msg)
            }
        }

        bindRearAssistSection()
        bindRearDpiSection()
    }

    private fun bindRearDpiSection() {
        binding.textSectionRearDpi.setOnClickListener {
            showSectionHelp(R.string.features_section_rear_dpi, R.string.help_features_rear_dpi)
        }
        binding.buttonRearDpiApply.setOnClickListener { applyRearDpi() }
        binding.buttonRearDpiReset.setOnClickListener { resetRearDpi() }
        binding.toggleGroupRearRotation.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppressRearRotationToggle || !isChecked) return@addOnButtonCheckedListener
            val target = rearRotationFromButtonId(checkedId)
            if (target < 0) return@addOnButtonCheckedListener
            // 某些 ROM 皮肤下 ToggleGroup 可能出现多选，手动强制互斥仅保留当前项。
            syncRearRotationToggleUi(target)
            // 防重复点击：同方向重复点，或上一次旋转仍在执行时不重复下发。
            if (rearRotationApplyInFlight || target == rearDisplayRotation) return@addOnButtonCheckedListener
            applyRearRotation(target)
        }
    }

    private fun bindRearDpiTaskService() {
        if (rearDpiServiceBound) return
        val ctx = context ?: return
        val ok = try {
            ctx.bindService(
                Intent(ctx, RootTaskService::class.java),
                rearDpiServiceConnection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Exception) {
            LogHelper.e(LOG_TAG, "bind RearDpi RootTaskService failed", e)
            false
        }
        if (ok) {
            rearDpiServiceBound = true
        }
    }

    private fun unbindRearDpiTaskService() {
        if (!rearDpiServiceBound) return
        try {
            context?.unbindService(rearDpiServiceConnection)
        } catch (e: Exception) {
            LogHelper.w(LOG_TAG, "unbind RearDpi RootTaskService: ${e.message}")
        }
        rearDpiServiceBound = false
        rearDpiTaskService = null
    }

    private fun setRearDpiUiBusy(busy: Boolean) {
        if (_binding == null) return
        binding.progressRearDpi.visibility = if (busy) View.VISIBLE else View.GONE
        binding.buttonRearDpiApply.isEnabled = !busy
        binding.buttonRearDpiReset.isEnabled = !busy
        binding.editRearDpiValue.isEnabled = !busy
        binding.toggleGroupRearRotation.isEnabled = !busy
    }

    private fun loadRearDpiFromService() {
        if (_binding == null) return
        val ts = rearDpiTaskService
        if (ts == null) {
            binding.textRearDpiStatus.setText(R.string.features_rear_dpi_service_waiting)
            return
        }
        setRearDpiUiBusy(true)
        binding.textRearDpiStatus.setText(R.string.features_rear_dpi_unknown)
        AppExecutors.runInBackground {
            val dpi = try {
                ts.currentRearDpi
            } catch (e: RemoteException) {
                LogHelper.w(LOG_TAG, "getCurrentRearDpi: ${e.message}")
                0
            }
            val rotRaw = try {
                ts.getDisplayRotation(REAR_DISPLAY_ID)
            } catch (e: RemoteException) {
                LogHelper.w(LOG_TAG, "getDisplayRotation: ${e.message}")
                -1
            }
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                setRearDpiUiBusy(false)
                if (dpi > 0) {
                    binding.textRearDpiStatus.text = getString(R.string.features_rear_dpi_current_fmt, dpi)
                    binding.editRearDpiValue.setText(dpi.toString())
                } else {
                    binding.textRearDpiStatus.setText(R.string.features_rear_dpi_unknown)
                }
                val rot = if (rotRaw in 0..3) rotRaw else 0
                rearDisplayRotation = rot
                syncRearRotationToggleUi(rot)
            }
        }
    }

    private fun syncRearRotationToggleUi(rotation: Int) {
        if (_binding == null) return
        suppressRearRotationToggle = true
        val id = rearRotationButtonId(rotation)
        if (id != View.NO_ID) {
            binding.toggleGroupRearRotation.check(id)
            binding.buttonRearRotation0.isChecked = id == R.id.button_rear_rotation_0
            binding.buttonRearRotation90.isChecked = id == R.id.button_rear_rotation_90
            binding.buttonRearRotation180.isChecked = id == R.id.button_rear_rotation_180
            binding.buttonRearRotation270.isChecked = id == R.id.button_rear_rotation_270
        } else {
            binding.toggleGroupRearRotation.clearChecked()
            binding.buttonRearRotation0.isChecked = false
            binding.buttonRearRotation90.isChecked = false
            binding.buttonRearRotation180.isChecked = false
            binding.buttonRearRotation270.isChecked = false
        }
        suppressRearRotationToggle = false
    }

    private fun rearRotationButtonId(rotation: Int): Int = when (rotation) {
        0 -> R.id.button_rear_rotation_0
        1 -> R.id.button_rear_rotation_90
        2 -> R.id.button_rear_rotation_180
        3 -> R.id.button_rear_rotation_270
        else -> View.NO_ID
    }

    private fun rearRotationFromButtonId(checkedId: Int): Int = when (checkedId) {
        R.id.button_rear_rotation_0 -> 0
        R.id.button_rear_rotation_90 -> 1
        R.id.button_rear_rotation_180 -> 2
        R.id.button_rear_rotation_270 -> 3
        else -> -1
    }

    private fun applyRearRotation(target: Int) {
        if (target !in 0..3) return
        if (!requirePrivilege()) {
            syncRearRotationToggleUi(rearDisplayRotation)
            return
        }
        val ts = rearDpiTaskService
        if (ts == null) {
            MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_service_waiting, Toast.LENGTH_SHORT)
            syncRearRotationToggleUi(rearDisplayRotation)
            bindRearDpiTaskService()
            return
        }
        rearRotationApplyInFlight = true
        setRearDpiUiBusy(true)
        AppExecutors.runInBackground {
            val ok = try {
                ts.setDisplayRotation(REAR_DISPLAY_ID, target)
            } catch (e: RemoteException) {
                LogHelper.w(LOG_TAG, "setDisplayRotation: ${e.message}")
                false
            }
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                rearRotationApplyInFlight = false
                setRearDpiUiBusy(false)
                if (ok) {
                    rearDisplayRotation = target
                    syncRearRotationToggleUi(target)
                    MainDisplayUi.showToast(
                        requireContext(),
                        getString(R.string.features_rear_rotation_toast_ok, target * 90),
                        Toast.LENGTH_SHORT,
                    )
                } else {
                    MainDisplayUi.showToast(requireContext(), R.string.features_rear_rotation_fail, Toast.LENGTH_LONG)
                    syncRearRotationToggleUi(rearDisplayRotation)
                }
            }
        }
    }

    private fun applyRearDpi() {
        if (!requirePrivilege()) return
        val ts = rearDpiTaskService
        if (ts == null) {
            MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_service_waiting, Toast.LENGTH_SHORT)
            bindRearDpiTaskService()
            return
        }
        val dpi = binding.editRearDpiValue.text?.toString()?.trim()?.toIntOrNull()
        if (dpi == null || dpi <= 0 || dpi > 9999) {
            MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_invalid, Toast.LENGTH_SHORT)
            return
        }
        setRearDpiUiBusy(true)
        AppExecutors.runInBackground {
            val ok = try {
                ts.setRearDpi(dpi)
            } catch (e: RemoteException) {
                LogHelper.w(LOG_TAG, "setRearDpi: ${e.message}")
                false
            }
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                setRearDpiUiBusy(false)
                if (ok) {
                    MainDisplayUi.showToast(
                        requireContext(),
                        getString(R.string.features_rear_dpi_toast_set, dpi),
                        Toast.LENGTH_SHORT,
                    )
                    loadRearDpiFromService()
                } else {
                    MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_fail, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun resetRearDpi() {
        if (!requirePrivilege()) return
        val ts = rearDpiTaskService
        if (ts == null) {
            MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_service_waiting, Toast.LENGTH_SHORT)
            bindRearDpiTaskService()
            return
        }
        setRearDpiUiBusy(true)
        AppExecutors.runInBackground {
            val okDpi = try {
                ts.resetRearDpi()
            } catch (e: RemoteException) {
                LogHelper.w(LOG_TAG, "resetRearDpi: ${e.message}")
                false
            }
            var okRot = true
            if (okDpi) {
                okRot = try {
                    ts.setDisplayRotation(REAR_DISPLAY_ID, 0)
                } catch (e: RemoteException) {
                    LogHelper.w(LOG_TAG, "resetRearDpi setDisplayRotation(0): ${e.message}")
                    false
                }
            }
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                setRearDpiUiBusy(false)
                if (okDpi) {
                    MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_toast_reset, Toast.LENGTH_SHORT)
                    if (!okRot) {
                        MainDisplayUi.showToast(requireContext(), R.string.features_rear_rotation_fail, Toast.LENGTH_LONG)
                    }
                    loadRearDpiFromService()
                } else {
                    MainDisplayUi.showToast(requireContext(), R.string.features_rear_dpi_fail, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun bindChargingAnimationSection() {
        setupChargingFillSpeedSliderCompose()
        setupChargingWaterColorSection()
        setupChargingFloatingDisplayToggle()
        setupChargingFontToggle()
        syncChargingAnimationUiFromPrefs()
        binding.switchChargingAnimation.setOnCheckedChangeListener { _, checked ->
            if (suppressChargingAnimationCallbacks) return@setOnCheckedChangeListener
            if (!requirePrivilege()) {
                suppressChargingAnimationCallbacks = true
                binding.switchChargingAnimation.isChecked = !checked
                suppressChargingAnimationCallbacks = false
                setChargingSubSectionVisible(binding.switchChargingAnimation.isChecked)
                return@setOnCheckedChangeListener
            }
            ChargingAnimationPrefs.setEnabled(requireContext(), checked)
            ChargingServiceSync.sync(requireContext(), viewModel.snapshot.value?.privileged == true)
            setChargingSubSectionVisible(checked)
        }
        binding.buttonChargingPickBackground.setOnClickListener {
            pickChargingBackgroundLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }
        binding.buttonChargingResetBackground.setOnClickListener {
            val ctx = requireContext()
            ChargingBackgroundLoader.deleteCustom(ctx)
            MainDisplayUi.showToast(ctx, R.string.features_charging_background_reset_ok, Toast.LENGTH_SHORT)
            syncChargingBackgroundStatusText()
            sendReloadChargingSettingsBroadcast(ctx)
        }
        binding.buttonChargingPickMascot.setOnClickListener {
            pickChargingMascotLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        binding.buttonChargingInfoItems.setOnClickListener {
            showChargingInfoItemsDialog()
        }
        syncChargingAlwaysOnUiFromPrefs()
        binding.switchChargingAlwaysOn.setOnCheckedChangeListener { _, checked ->
            ChargingAnimationPrefs.setAlwaysOn(requireContext(), checked)
            sendReloadChargingSettingsBroadcast(requireContext())
        }
    }

    private fun setupChargingWaterColorSection() {
        binding.buttonChargingPickWaterColor.setOnClickListener {
            showChargingWaterColorDialog()
        }
    }

    private fun setupChargingFloatingDisplayToggle() {
        binding.toggleChargingFloating.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressChargingAnimationCallbacks) return@addOnButtonCheckedListener
            val ctx = requireContext()
            val mode = when (checkedId) {
                R.id.button_charging_floating_image -> ChargingAnimationPrefs.FLOATING_IMAGE
                R.id.button_charging_floating_battery -> ChargingAnimationPrefs.FLOATING_BATTERY
                else -> ChargingAnimationPrefs.FLOATING_NONE
            }
            ChargingAnimationPrefs.setFloatingDisplay(ctx, mode)
            updateChargingMascotRowVisibility()
            sendReloadChargingSettingsBroadcast(ctx)
        }
    }

    private fun setupChargingFontToggle() {
        binding.toggleChargingFont.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressChargingAnimationCallbacks) return@addOnButtonCheckedListener
            val ctx = requireContext()
            when (checkedId) {
                R.id.button_charging_font_system -> {
                    ChargingAnimationPrefs.setFont(ctx, LyricsFontHelper.ID_SYSTEM, null)
                    sendReloadChargingSettingsBroadcast(ctx)
                }
                R.id.button_charging_font_mfgehei -> {
                    ChargingAnimationPrefs.setFont(ctx, LyricsFontHelper.ID_MFGEHEI, null)
                    sendReloadChargingSettingsBroadcast(ctx)
                }
                R.id.button_charging_font_import -> {
                    pickChargingFontLauncher.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf"))
                }
            }
        }
    }

    private fun syncChargingFontUiFromPrefs() {
        val fontId = ChargingAnimationPrefs.getFontId(requireContext())
        binding.buttonChargingFontSystem.isChecked = fontId == LyricsFontHelper.ID_SYSTEM
        binding.buttonChargingFontMfgehei.isChecked = fontId == LyricsFontHelper.ID_MFGEHEI
        binding.buttonChargingFontImport.isChecked = fontId == LyricsFontHelper.ID_CUSTOM
    }

    private fun syncChargingFloatingDisplayUiFromPrefs() {
        val mode = ChargingAnimationPrefs.getFloatingDisplay(requireContext())
        binding.buttonChargingFloatingNone.isChecked = mode == ChargingAnimationPrefs.FLOATING_NONE
        binding.buttonChargingFloatingImage.isChecked = mode == ChargingAnimationPrefs.FLOATING_IMAGE
        binding.buttonChargingFloatingBattery.isChecked = mode == ChargingAnimationPrefs.FLOATING_BATTERY
    }

    private fun showChargingWaterColorDialog() {
        val ctx = requireContext()
        val dialogView = layoutInflater.inflate(R.layout.dialog_charging_water_color, null)
        val wheel = dialogView.findViewById<HsvColorWheelView>(R.id.charging_water_color_wheel)
        val preview = dialogView.findViewById<View>(R.id.charging_water_color_preview)
        val hexText = dialogView.findViewById<android.widget.TextView>(R.id.charging_water_color_hex)
        val opacitySlider = dialogView.findViewById<com.google.android.material.slider.Slider>(
            R.id.charging_water_opacity_slider,
        )
        val opacityValue = dialogView.findViewById<android.widget.TextView>(R.id.charging_water_opacity_value)
        var selected = ChargingAnimationPrefs.getWaterColor(ctx)
        var opacity = ChargingAnimationPrefs.getWaterOpacityPercent(ctx)
        wheel.setColor(selected)
        opacitySlider.value = opacity.toFloat()
        opacityValue.text = getString(R.string.features_charging_water_opacity_value, opacity)
        refreshWaterColorDialogPreview(preview, hexText, selected, opacity)
        wheel.onColorChanged = { color ->
            selected = color
            refreshWaterColorDialogPreview(preview, hexText, color, opacity)
        }
        opacitySlider.addOnChangeListener { _, value, _ ->
            opacity = value.toInt().coerceIn(
                ChargingAnimationPrefs.MIN_WATER_OPACITY_PERCENT,
                ChargingAnimationPrefs.MAX_WATER_OPACITY_PERCENT,
            )
            opacityValue.text = getString(R.string.features_charging_water_opacity_value, opacity)
            refreshWaterColorDialogPreview(preview, hexText, selected, opacity)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.features_charging_water_color_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.car_rear_btn_dialog_confirm) { _, _ ->
                ChargingAnimationPrefs.setWaterColor(ctx, selected)
                ChargingAnimationPrefs.setWaterOpacityPercent(ctx, opacity)
                syncChargingWaterColorPreviewFromPrefs()
                sendReloadChargingSettingsBroadcast(ctx)
            }
            .setNegativeButton(R.string.car_rear_btn_dialog_cancel, null)
            .show()
    }

    private fun refreshWaterColorDialogPreview(
        preview: View,
        hexText: android.widget.TextView,
        argb: Int,
        opacityPercent: Int,
    ) {
        setWaterColorPreviewColor(preview, argb, opacityPercent)
        hexText.text = formatWaterColorSummary(argb, opacityPercent)
    }

    private fun formatWaterColorSummary(argb: Int, opacityPercent: Int): String =
        "${formatWaterColorHex(argb)} · ${opacityPercent}%"

    private fun formatWaterColorHex(argb: Int): String =
        String.format("#%06X", argb and 0xFFFFFF)

    private fun setWaterColorPreviewColor(view: View, argb: Int, opacityPercent: Int = 100) {
        val alpha = (255f * opacityPercent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255)
        val color = (argb and 0x00FFFFFF) or (alpha shl 24)
        val bg = view.background?.mutate()
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            view.setBackgroundColor(color)
        }
    }

    private fun syncChargingWaterColorPreviewFromPrefs() {
        val ctx = requireContext()
        val color = ChargingAnimationPrefs.getWaterColor(ctx)
        val opacity = ChargingAnimationPrefs.getWaterOpacityPercent(ctx)
        setWaterColorPreviewColor(binding.viewChargingWaterColorPreview, color, opacity)
        binding.textChargingWaterColorValue.text = formatWaterColorSummary(color, opacity)
    }

    private fun syncChargingWaterColorUiFromPrefs() {
        syncChargingWaterColorPreviewFromPrefs()
    }

    private fun syncChargingAlwaysOnUiFromPrefs() {
        binding.switchChargingAlwaysOn.isChecked =
            ChargingAnimationPrefs.isAlwaysOn(requireContext())
    }

    private fun setChargingSubSectionVisible(enabled: Boolean) {
        val v = if (enabled) View.VISIBLE else View.GONE
        binding.textChargingFillSpeedValue.visibility = v
        binding.composeChargingFillSpeedSlider.visibility = v
        binding.layoutChargingBackground.visibility = v
        binding.layoutChargingWaterColor.visibility = v
        binding.textChargingFloatingTitle.visibility = v
        binding.toggleChargingFloating.visibility = v
        binding.textChargingFontTitle.visibility = v
        binding.toggleChargingFont.visibility = v
        if (enabled) {
            updateChargingMascotRowVisibility()
        } else {
            binding.layoutChargingMascot.visibility = View.GONE
        }
    }

    private fun syncChargingBackgroundStatusText() {
        val ctx = requireContext()
        val textRes = when {
            ChargingAnimationPrefs.hasCustomBackgroundVideo(ctx) ->
                R.string.features_charging_background_video
            ChargingAnimationPrefs.hasCustomBackground(ctx) ->
                R.string.features_charging_background_custom
            else -> R.string.features_charging_background_default
        }
        binding.textChargingBackgroundStatus.text = getString(textRes)
    }

    private fun updateChargingMascotRowVisibility() {
        val show = binding.switchChargingAnimation.isChecked &&
            binding.buttonChargingFloatingImage.isChecked
        binding.layoutChargingMascot.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun syncChargingMascotStatusText() {
        binding.textChargingMascotStatus.text = getString(
            if (ChargingAnimationPrefs.hasCustomMascot(requireContext())) {
                R.string.features_charging_mascot_custom
            } else {
                R.string.features_charging_mascot_unselected
            },
        )
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
                        ) / 1000f,
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

    /** 步进 0–99（左→右）映射为满幅 4–8s，再换算为内部 speedPercent。 */
    private fun fillSpeedPercentFromStep(step: Int): Int {
        val durationMs = fillDurationMsFromStep(step)
        val p = (ChargingAnimationPrefs.FILL_MS_FOR_FULL_SCALE * 100f / durationMs).roundToInt()
        return p.coerceIn(
            ChargingAnimationPrefs.MIN_FILL_RISE_SPEED_PERCENT,
            ChargingAnimationPrefs.MAX_FILL_RISE_SPEED_PERCENT,
        )
    }

    private fun fillSpeedStepFromPercent(percent: Int): Int {
        val durationMs = ChargingAnimationPrefs.fillDurationMsForFullFill(percent).coerceIn(4_000, 8_000)
        return ((durationMs - 4_000) * 99f / (8_000 - 4_000)).roundToInt().coerceIn(0, 99)
    }

    /** 滑块左→右：4s→8s。 */
    private fun fillDurationMsFromStep(step: Int): Int {
        val s = step.coerceIn(0, 99)
        return 4_000 + s * (8_000 - 4_000) / 99
    }

    private fun syncChargingAnimationUiFromPrefs() {
        suppressChargingAnimationCallbacks = true
        val ctx = requireContext()
        val enabled = ChargingAnimationPrefs.isEnabled(ctx)
        binding.switchChargingAnimation.isChecked = enabled
        syncChargingFloatingDisplayUiFromPrefs()
        syncChargingFontUiFromPrefs()
        suppressChargingAnimationCallbacks = false
        setChargingSubSectionVisible(enabled)
        syncChargingBackgroundStatusText()
        syncChargingWaterColorUiFromPrefs()
        syncChargingMascotStatusText()
        updateChargingMascotRowVisibility()
        val fillP = ChargingAnimationPrefs.getFillRiseSpeedPercent(requireContext())
        chargingFillSpeedStepState.intValue = fillSpeedStepFromPercent(fillP)
        binding.textChargingFillSpeedValue.text =
            getString(
                R.string.features_charging_fill_speed_fmt,
                ChargingAnimationPrefs.fillDurationMsForFullFill(fillP) / 1000f,
            )
        ChargingServiceSync.sync(requireContext(), viewModel.snapshot.value?.privileged == true)
    }

    private fun sendReloadChargingSettingsBroadcast(ctx: Context) {
        ctx.applicationContext.sendBroadcast(
            Intent(ChargingIntents.ACTION_RELOAD_CHARGING_SETTINGS).setPackage(ctx.packageName),
        )
    }

    /** 弹出背屏充电信息项设置对话框。 */
    private fun showChargingInfoItemsDialog() {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val currentItems = ChargingAnimationPrefs.getInfoItems(ctx)

        // data class for each item row
        data class ItemRow(
            val id: String,
            val label: String,
            val cb: android.widget.CheckBox,
            val upBtn: android.widget.Button,
            val downBtn: android.widget.Button,
            val row: android.widget.LinearLayout,
        )

        val btnSize = (36 * density).toInt()
        val allRows = ChargingAnimationPrefs.getAllInfoItems().map { (id, label) ->
            val cb = android.widget.CheckBox(ctx).apply {
                text = label
                isChecked = currentItems.contains(id)
                setTextColor(0xFF000000.toInt())
            }
            val upBtn = android.widget.Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                text = "▲"
                textSize = 10f
            }
            val downBtn = android.widget.Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                text = "▼"
                textSize = 10f
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                addView(cb, android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(upBtn, android.widget.LinearLayout.LayoutParams(btnSize, btnSize))
                addView(downBtn, android.widget.LinearLayout.LayoutParams(btnSize, btnSize))
            }
            ItemRow(id, label, cb, upBtn, downBtn, row)
        }

        val dialogLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            for (itemRow in allRows) addView(itemRow.row)
        }

        val hintView = android.widget.TextView(ctx).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF666666.toInt())
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        dialogLayout.addView(hintView)

        /** 刷新：勾选的排前面，不勾选的排后面，保持各自内部顺序。 */
        fun reorderRows() {
            val checked = allRows.filter { it.cb.isChecked }
            val unchecked = allRows.filter { !it.cb.isChecked }
            val ordered = checked + unchecked
            dialogLayout.removeAllViews()
            for (item in ordered) dialogLayout.addView(item.row)
            dialogLayout.addView(hintView)
            val c = checked.size
            hintView.text = "已选择 $c/${ChargingAnimationPrefs.MAX_INFO_ITEMS}，最多选择 ${ChargingAnimationPrefs.MAX_INFO_ITEMS} 项"
        }

        /** 交换 dialogLayout 中两个 row 的位置。 */
        fun swapRows(fromIdx: Int, toIdx: Int) {
            if (fromIdx < 0 || toIdx < 0 || fromIdx >= allRows.size || toIdx >= allRows.size) return
            val rowsInLayout = allRows.sortedBy { dialogLayout.indexOfChild(it.row) }
            val fromRow = rowsInLayout[fromIdx]
            val toRow = rowsInLayout[toIdx]
            val fromPos = dialogLayout.indexOfChild(fromRow.row)
            val toPos = dialogLayout.indexOfChild(toRow.row)
            if (fromPos < 0 || toPos < 0) return
            // Swap by removing and re-adding
            dialogLayout.removeView(fromRow.row)
            dialogLayout.removeView(toRow.row)
            // The positions shifted after first removal
            if (fromPos < toPos) {
                dialogLayout.addView(toRow.row, fromPos)
                dialogLayout.addView(fromRow.row, toPos)
            } else {
                dialogLayout.addView(toRow.row, fromPos)
                dialogLayout.addView(fromRow.row, toPos)
            }
            // Move hintView back to end
            dialogLayout.removeView(hintView)
            dialogLayout.addView(hintView)
        }

        // 设置点击事件
        for (i in allRows.indices) {
            val idx = i
            allRows[i].upBtn.setOnClickListener {
                val layoutRows = allRows.sortedBy { dialogLayout.indexOfChild(it.row) }
                val curIdx = layoutRows.indexOf(allRows[idx])
                if (curIdx > 0) {
                    swapLayoutChildren(dialogLayout, curIdx, curIdx - 1)
                    // Move hintView back to end
                    dialogLayout.removeView(hintView)
                    dialogLayout.addView(hintView)
                }
            }
            allRows[i].downBtn.setOnClickListener {
                val layoutRows = allRows.sortedBy { dialogLayout.indexOfChild(it.row) }
                val curIdx = layoutRows.indexOf(allRows[idx])
                if (curIdx < layoutRows.size - 1) {
                    swapLayoutChildren(dialogLayout, curIdx, curIdx + 1)
                    dialogLayout.removeView(hintView)
                    dialogLayout.addView(hintView)
                }
            }
            allRows[i].cb.setOnCheckedChangeListener { btn, isChecked ->
                if (isChecked) {
                    val c = allRows.count { it.cb.isChecked }
                    if (c > ChargingAnimationPrefs.MAX_INFO_ITEMS) {
                        btn.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                }
                reorderRows()
            }
        }

        reorderRows()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("背屏信息显示设置")
            .setView(dialogLayout)
            .setPositiveButton("确定") { _, _ ->
                val selected = allRows.filter { it.cb.isChecked }.map { it.id }
                ChargingAnimationPrefs.setInfoItems(ctx, selected)
                sendReloadChargingSettingsBroadcast(ctx)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 交换 LinearLayout 中两个子 View 的位置。 */
    private fun swapLayoutChildren(parent: android.widget.LinearLayout, pos1: Int, pos2: Int) {
        if (pos1 == pos2) return
        val children = (0 until parent.childCount).map { parent.getChildAt(it) }
        parent.removeAllViews()
        for (i in children.indices) {
            parent.addView(
                when (i) {
                    pos1 -> children[pos2]
                    pos2 -> children[pos1]
                    else -> children[i]
                },
            )
        }
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

    /** 应用投屏 Keeper 与音乐/车控 [RearScreenWakeService] 内 WAKEUP / WakeLock 与 [RearAssistPrefs.isKeepScreenOnEnabled] 一致。 */
    private fun notifyRearSwitchKeeperKeepScreenFromPrefs(ctx: Context) {
        try {
            val on = RearAssistPrefs.isKeepScreenOnEnabled(ctx)
            ctx.startService(
                Intent(ctx, RearSwitchKeeperService::class.java)
                    .setAction(RearSwitchKeeperService.ACTION_SET_KEEP_SCREEN_ON_ENABLED)
                    .putExtra("enabled", on),
            )
            ctx.startService(
                Intent(ctx, RearScreenWakeService::class.java)
                    .setAction(RearScreenWakeService.ACTION_SET_KEEP_SCREEN_ON_ENABLED)
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
        MainDisplayUi.showToast(ctx, R.string.rear_assist_need_post_notifications, Toast.LENGTH_LONG)
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
        binding.switchRearKeepScreenOn.isChecked = RearAssistPrefs.isKeepScreenOnEnabled(ctx)
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
        syncRecordScreenshotUiFromPrefs()
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
                    MainDisplayUi.showToast(ctx, R.string.features_shell_persist_fail, Toast.LENGTH_LONG)
                }
                updateShellColorChipLabels()
            },
        )

        binding.switchPromaxShellFull.setOnCheckedChangeListener { _, checked ->
            if (suppressShellCallbacks) return@setOnCheckedChangeListener
            val key = colorKeyFromCheckedChip()
            val ok = DeviceGeometry.persistShellBackdropSelection(ctx, key, checked)
            if (!ok) {
                MainDisplayUi.showToast(ctx, R.string.features_shell_persist_fail, Toast.LENGTH_LONG)
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

    private fun syncRecordScreenshotUiFromPrefs() {
        suppressRecordScreenshotCallbacks = true
        binding.switchRecordScreenshotKeepScreenOn.isChecked =
            RearAssistPrefs.isRecordScreenshotKeepScreenOnEnabled(requireContext())
        suppressRecordScreenshotCallbacks = false
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
            MainDisplayUi.showToast(requireContext(), R.string.privilege_shell_required, Toast.LENGTH_LONG)
        }
        return ok
    }

    private fun toastResult(ok: Boolean, msg: String) {
        MainDisplayUi.showToast(
            requireContext(),
            msg,
            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        )
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
            MainDisplayUi.showToast(ctx, R.string.settings_overlay_reset_ok, Toast.LENGTH_SHORT)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hx = parseCoord(dialogBinding.editHalfX.text?.toString().orEmpty())
                val hy = parseCoord(dialogBinding.editHalfY.text?.toString().orEmpty())
                val fx = parseCoord(dialogBinding.editFullX.text?.toString().orEmpty())
                val fy = parseCoord(dialogBinding.editFullY.text?.toString().orEmpty())
                if (hx == null || hy == null || fx == null || fy == null) {
                    MainDisplayUi.showToast(ctx, R.string.settings_overlay_invalid, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }
                DeviceGeometry.persistCompositeXYHalf(ctx, hx, hy)
                DeviceGeometry.persistCompositeXYFull(ctx, fx, fy)
                MainDisplayUi.showToast(ctx, R.string.settings_overlay_saved, Toast.LENGTH_SHORT)
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
            MainDisplayUi.showToast(ctx, R.string.features_sticker_cleared, Toast.LENGTH_SHORT)
        }
        dialogBinding.buttonResetSticker.setOnClickListener {
            DeviceGeometry.resetStickerOverlay(ctx)
            fillStickerDialogFields(dialogBinding)
            syncStickerDialogFileRow(dialogBinding)
            refreshStickerPreview(dialogBinding)
            MainDisplayUi.showToast(ctx, R.string.settings_overlay_reset_ok, Toast.LENGTH_SHORT)
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
                MainDisplayUi.showToast(ctx, R.string.settings_overlay_invalid, Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            if ((w > 0) xor (h > 0)) {
                MainDisplayUi.showToast(ctx, R.string.features_sticker_wh_invalid, Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            val cornerDp = parseCornerDpForSave(dialogBinding.editStickerCornerDp.text?.toString().orEmpty())
            if (cornerDp == null) {
                MainDisplayUi.showToast(ctx, R.string.features_sticker_corner_dp_invalid, Toast.LENGTH_SHORT)
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
            MainDisplayUi.showToast(ctx, R.string.features_sticker_saved, Toast.LENGTH_SHORT)
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
        AppExecutors.runInBackground {
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
                    MainDisplayUi.showToast(ctx, R.string.features_sticker_export_fail, Toast.LENGTH_SHORT)
                    fullDialog.dismiss()
                }
            }
        }
    }

    private fun exportStickerPreview(dialogBinding: DialogStickerOverlayBinding) {
        val ctx = requireContext()
        val overlay = buildStickerPreviewOverlay(dialogBinding)
        AppExecutors.runInBackground {
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
                    MainDisplayUi.showToast(ctx, R.string.features_sticker_export_fail, Toast.LENGTH_LONG)
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
        AppExecutors.runInBackground {
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
        bindRearDpiTaskService()
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

    override fun onResume() {
        super.onResume()
        if (rearDpiTaskService != null && _binding != null) {
            loadRearDpiFromService()
        }
    }

    override fun onStop() {
        unbindRearDpiTaskService()
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
        private const val LOG_TAG = "FeaturesFragment"
        private const val REAR_DISPLAY_ID = 1
        /** 三连击标题时，相邻两次点击允许的最大间隔（毫秒），超时则重新计数。 */
        private const val TITLE_TRIPLE_TAP_WINDOW_MS = 600L
    }
}
