package com.wmqc.miroot.ui.more
import com.wmqc.miroot.display.MainDisplayUi

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.wmqc.miroot.R
import com.wmqc.miroot.lyrics.JiebaTokenizerEngine
import com.wmqc.miroot.capability.PermissionSnapshot
import com.wmqc.miroot.databinding.FragmentMoreBinding
import com.wmqc.miroot.viewmodel.MainPermissionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoreFragment : Fragment(R.layout.fragment_more) {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!
    private val pickLyricsDictLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::onLyricsDictPicked)

    private val viewModel: MainPermissionViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMoreBinding.bind(view)
        viewModel.snapshot.observe(viewLifecycleOwner, ::render)

        binding.textSectionLyricsDict.setOnClickListener {
            pickLyricsDictLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
        }
        binding.buttonImportLyricsDict.setOnClickListener {
            pickLyricsDictLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
        }
    }

    private fun render(snap: PermissionSnapshot) {
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun onLyricsDictPicked(uri: Uri?) {
        if (uri == null) return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { JiebaTokenizerEngine.importUserDictionary(ctx, uri) }
            }
            if (!isAdded) return@launch
            result.onSuccess { data ->
                MainDisplayUi.showToast(
                    requireContext(),
                    getString(
                        R.string.more_lyrics_dict_import_success,
                        data.mergedWords,
                        data.totalWords,
                    ),
                    Toast.LENGTH_LONG,
                )
                binding.textLyricsDictHint.text =
                    getString(R.string.more_lyrics_dict_runtime_path, data.filePath)
            }.onFailure {
                MainDisplayUi.showToast(
                    requireContext(),
                    getString(R.string.more_lyrics_dict_import_failed, it.message ?: "unknown"),
                    Toast.LENGTH_LONG,
                )
            }
        }
    }
}
