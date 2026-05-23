package com.wmqc.miroot.license
import com.wmqc.miroot.display.MainDisplayUi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.KeyEvent
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatActivity
import com.wmqc.miroot.MainActivity
import com.wmqc.miroot.WelcomeIntro
import com.wmqc.miroot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineActivationDialogFragment : DialogFragment() {

    private var deviceCodeTapCount = 0
    private var lastDeviceCodeTapAt = 0L
    private var allowCarControlJump = false
    private var activationCodeInput: TextInputEditText? = null

    override fun onCancel(dialog: DialogInterface) {
        // 强制模式：未激活时取消/关闭弹窗 -> 退出软件。
        if (!isActivatedNow()) {
            requireActivity().finishAffinity()
        }
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        // 强制模式：只要弹窗关闭且尚未激活 -> 退出软件。
        if (!isActivatedNow()) {
            requireActivity().finishAffinity()
        }
        super.onDismiss(dialog)
    }

    private fun isActivatedNow(): Boolean = runCatching {
        OfflineActivationRepository.isActivated(requireContext())
    }.getOrDefault(false)

    override fun onCreateDialog(savedInstanceState: Bundle?) = run {
        val ctx = requireContext()
        val content = LayoutInflater.from(ctx).inflate(R.layout.dialog_offline_activation, null, false)

        val deviceCodeTv = content.findViewById<TextView>(R.id.text_device_code)
        val copyDeviceBtn = content.findViewById<MaterialButton>(R.id.button_copy_device_code)

        val tilCode = content.findViewById<TextInputLayout>(R.id.input_layout_activation_code)
        val editActivationCode = content.findViewById<TextInputEditText>(R.id.edit_activation_code)
        activationCodeInput = editActivationCode

        val activateBtn = content.findViewById<MaterialButton>(R.id.button_activate_now)
        val exitBtn = content.findViewById<MaterialButton>(R.id.button_exit_now)

        val deviceCode = OfflineActivationRepository.getOrCreateDeviceCode(ctx)
        allowCarControlJump = deviceCode == ALLOWED_CAR_CONTROL_DEVICE_CODE
        deviceCodeTv.text = deviceCode
        copyDeviceBtn.isEnabled = true
        deviceCodeTv.setOnClickListener {
            if (!allowCarControlJump) return@setOnClickListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastDeviceCodeTapAt > 1500L) {
                deviceCodeTapCount = 0
            }
            lastDeviceCodeTapAt = now
            deviceCodeTapCount += 1
            if (deviceCodeTapCount >= 3) {
                deviceCodeTapCount = 0
                dismissAllowingStateLoss()
                startActivity(Intent(ctx, com.wmqc.miroot.car.CarControlLoginActivity::class.java))
            }
        }

        fun copyToClipboard(text: String) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MiRoot", text))
        }

        copyDeviceBtn.setOnClickListener {
            copyToClipboard(deviceCode)
            MainDisplayUi.showToast(ctx, R.string.offline_activation_copy_device_toast_ok, Toast.LENGTH_SHORT)
        }

        activateBtn.isEnabled = false
        editActivationCode.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    tilCode.error = null
                    val normalizedLength = s
                        ?.toString()
                        .orEmpty()
                        .uppercase()
                        .replace("[^0-9A-Z]".toRegex(), "")
                        .length
                    activateBtn.isEnabled = normalizedLength >= 64
                }
            },
        )

        activateBtn.setOnClickListener {
            val userCode = editActivationCode.text?.toString().orEmpty()
            if (userCode.isBlank()) {
                tilCode.error = getString(R.string.offline_activation_code_empty)
                return@setOnClickListener
            }
            val normalizedLength = userCode.uppercase().replace("[^0-9A-Z]".toRegex(), "").length
            if (normalizedLength < 64) {
                tilCode.error = getString(R.string.offline_activation_code_too_short)
                return@setOnClickListener
            }
            tilCode.error = null
            activateBtn.isEnabled = false
            MainDisplayUi.showToast(ctx, R.string.offline_activation_toast_verifying, Toast.LENGTH_SHORT)

            // 部分 ROM/时序下 Keystore 会在“输入激活码后”短暂不可用。
            // 对这类可重试错误，自动等待并重试，避免用户被迫重启/重复输入。
            val normalizedCode = userCode
            val maxAttempts = 6
            val retryDelayMs = 450L
            lifecycleScope.launch {
                var lastErr: Throwable? = null
                for (attempt in 1..maxAttempts) {
                    val result = withContext(Dispatchers.Default) {
                        OfflineActivationRepository.verifyAndActivate(ctx, normalizedCode)
                    }
                    if (result.isSuccess) {
                        MainDisplayUi.showToast(ctx, R.string.offline_activation_toast_ok, Toast.LENGTH_SHORT)
                        val host = activity as? AppCompatActivity
                        (host as? MainActivity)?.refreshActivationUi()
                        dismiss()
                        host?.window?.decorView?.post {
                            if (!host.isFinishing) {
                                WelcomeIntro.showAfterSuccessfulActivation(host)
                            }
                        }
                        return@launch
                    }

                    val e = result.exceptionOrNull()
                    lastErr = e
                    val msg = e?.message.orEmpty()
                    val keystoreNotReady = msg.contains("Keystore 未就绪") || msg.contains("Keystore")

                    if (keystoreNotReady && attempt < maxAttempts) {
                        // 不刷屏 toast：仅延迟重试，最后一次失败才提示。
                        delay(retryDelayMs)
                        continue
                    }

                    // 非 Keystore 未就绪，或已到最后一次：直接失败提示。
                    MainDisplayUi.showToast(
                        ctx,
                        e?.message ?: getString(R.string.offline_activation_toast_fail),
                        Toast.LENGTH_LONG,
                    )
                    activateBtn.isEnabled = true
                    return@launch
                }

                MainDisplayUi.showToast(
                    ctx,
                    lastErr?.message ?: getString(R.string.offline_activation_toast_fail),
                    Toast.LENGTH_LONG,
                )
                activateBtn.isEnabled = true
            }
        }

        exitBtn.setOnClickListener {
            requireActivity().finishAffinity()
            dismiss()
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.offline_activation_dialog_title)
            .setView(content)
            .setCancelable(false)
            .create()

        // 强制模式：禁止触摸外部与返回键关闭。
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, _ ->
            keyCode == KeyEvent.KEYCODE_BACK
        }

        dialog
    }

    companion object {
        const val TAG = "offline_activation_dialog"
        private const val ALLOWED_CAR_CONTROL_DEVICE_CODE = "234DB891E59CC405"
    }

    fun prefillActivationCode(code: String) {
        val input = activationCodeInput ?: return
        input.setText(code)
        input.setSelection(input.text?.length ?: 0)
    }
}

