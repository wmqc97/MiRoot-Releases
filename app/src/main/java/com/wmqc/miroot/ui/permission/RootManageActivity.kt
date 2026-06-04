package com.wmqc.miroot.ui.permission

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.wmqc.miroot.R
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.databinding.ActivityRootManageBinding
import com.wmqc.miroot.ui.applyMiRootSecondarySystemBars
import kotlinx.coroutines.launch

class RootManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootManageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyMiRootSecondarySystemBars()
        binding = ActivityRootManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.buttonProbe.setOnClickListener {
            binding.textResult.isVisible = false
            lifecycleScope.launch {
                val ok = EnvironmentProbe.probeRoot()
                binding.textResult.isVisible = true
                binding.textResult.text = if (ok) {
                    getString(R.string.root_manage_result_ok)
                } else {
                    getString(R.string.root_manage_result_no)
                }
                binding.textResult.setTextColor(
                    ContextCompat.getColor(this@RootManageActivity, if (ok) R.color.perm_green else R.color.perm_red),
                )
            }
        }
    }
}
