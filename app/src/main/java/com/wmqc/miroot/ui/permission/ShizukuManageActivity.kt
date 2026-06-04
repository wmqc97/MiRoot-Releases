package com.wmqc.miroot.ui.permission
import com.wmqc.miroot.display.MainDisplayUi

import com.wmqc.miroot.lyrics.LogHelper
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wmqc.miroot.R
import com.wmqc.miroot.databinding.ActivityShizukuManageBinding
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import rikka.shizuku.Shizuku

private const val SHIZUKU_REQ = 8722

class ShizukuManageActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityShizukuManageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyMiRootSecondarySystemBars()
        try {
            Shizuku.addRequestPermissionResultListener(this)
        } catch (t: Throwable) {
            LogHelper.e(TAG, "Shizuku addRequestPermissionResultListener failed", t)
        }
        binding = ActivityShizukuManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.buttonRequest.setOnClickListener {
            when {
                !Shizuku.pingBinder() -> {
                    MainDisplayUi.showToast(this, R.string.status_shizuku_off, Toast.LENGTH_SHORT)
                }
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    MainDisplayUi.showToast(this, R.string.status_ok, Toast.LENGTH_SHORT)
                }
                else -> Shizuku.requestPermission(SHIZUKU_REQ)
            }
        }

        binding.buttonOpenApp.setOnClickListener { openShizukuApp() }
    }

    private fun openShizukuApp() {
        val packages = listOf("moe.shizuku.privileged.api", "rikka.shizuku")
        for (pkg in packages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
        }
        MainDisplayUi.showToast(this, R.string.shizuku_manage_no_app, Toast.LENGTH_SHORT)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == SHIZUKU_REQ) {
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            MainDisplayUi.showToast(
                this,
                if (ok) getString(R.string.status_ok) else getString(R.string.status_shizuku_need_perm),
                Toast.LENGTH_SHORT,
            )
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(this)
        } catch (t: Throwable) {
            LogHelper.e(TAG, "Shizuku removeRequestPermissionResultListener failed", t)
        }
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "ShizukuManage"
    }
}
