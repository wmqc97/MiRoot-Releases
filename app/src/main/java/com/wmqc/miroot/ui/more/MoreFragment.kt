package com.wmqc.miroot.ui.more

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.wmqc.miroot.R
import com.wmqc.miroot.ui.common.showSectionHelp
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.databinding.FragmentMoreBinding
import com.wmqc.miroot.viewmodel.MainPermissionViewModel

class MoreFragment : Fragment(R.layout.fragment_more) {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainPermissionViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMoreBinding.bind(view)
        viewModel.snapshot.observe(viewLifecycleOwner, ::render)

        binding.textSectionMoreXposed.setOnClickListener {
            showSectionHelp(R.string.more_xposed_title, R.string.help_more_xposed)
        }
    }

    private fun render(snap: PermissionSnapshot) {
        val body = buildString {
            append(getString(R.string.more_xposed_body))
            append("\n\n")
            append(
                if (snap.xposed) getString(R.string.status_xposed_on) else getString(R.string.status_xposed_off),
            )
        }
        binding.textXposedDetail.text = body
        binding.textXposedDetail.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (snap.xposed) R.color.perm_green else R.color.mi_text_secondary,
            ),
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
