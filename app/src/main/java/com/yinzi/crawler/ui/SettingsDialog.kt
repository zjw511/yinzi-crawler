package com.yinzi.crawler.ui

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yinzi.crawler.R
import com.yinzi.crawler.databinding.DialogSettingsBinding
import com.yinzi.crawler.util.Prefs

class SettingsDialog : DialogFragment() {

    private val vm: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogSettingsBinding.inflate(layoutInflater)
        binding.etGroupId.setText(Prefs.groupId)
        binding.etCookie.setText(Prefs.cookie)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val id = binding.etGroupId.text?.toString()?.trim().orEmpty()
                val cookie = binding.etCookie.text?.toString()?.trim().orEmpty()
                if (id.isNotEmpty()) {
                    Prefs.groupId = id
                }
                Prefs.cookie = cookie
                if (id.isNotEmpty()) vm.setGroupId(id)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
