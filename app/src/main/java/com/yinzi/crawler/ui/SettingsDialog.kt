package com.yinzi.crawler.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yinzi.crawler.R
import com.yinzi.crawler.databinding.DialogSettingsBinding
import com.yinzi.crawler.util.DebugLog
import com.yinzi.crawler.util.Prefs

class SettingsDialog : DialogFragment() {

    private val vm: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogSettingsBinding.inflate(layoutInflater)

        binding.etGroupId.setText(Prefs.groupId)
        binding.etCookie.setText(Prefs.cookie)

        refreshModeBar(binding)

        // 模式实时变化（用户在 cookie 输入时提示当前模式）
        binding.etCookie.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshModeBar(binding)
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        // 一键打开内嵌浏览器登录
        binding.btnAppLogin.setOnClickListener {
            LoginActivity.start(requireContext())
            dismiss()
        }

        // 查看调试日志
        binding.btnDebugLog.setOnClickListener {
            DebugLogDialog().show(parentFragmentManager, "debug_log")
        }

        // 清空日志
        binding.btnClearLog.setOnClickListener {
            DebugLog.clear()
            Snackbar.make(binding.root, "调试日志已清空", Snackbar.LENGTH_SHORT).show()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val id = binding.etGroupId.text?.toString()?.trim().orEmpty()
                val cookie = binding.etCookie.text?.toString()?.trim().orEmpty()
                if (id.isNotEmpty()) Prefs.groupId = id
                Prefs.cookie = cookie
                Prefs.syncToWebView()
                if (id.isNotEmpty()) vm.setGroupId(id)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从 LoginActivity 回来，Cookie 可能被自动写入，刷新一下输入框
        view?.let {
            val binding = DialogSettingsBinding.bind(it)
            if (binding.etCookie.text?.toString().isNullOrBlank()) {
                binding.etCookie.setText(Prefs.cookie)
                refreshModeBar(binding)
            }
        }
    }

    /** 模式指示条：匿名 蓝绿底 + 绿色对勾；登录态 橙底 + 黄色星 */
    private fun refreshModeBar(b: DialogSettingsBinding) {
        val current = b.etCookie.text?.toString()?.trim().orEmpty()
        val isAnon = current.isBlank()
        if (isAnon) {
            b.tvModeIcon.text = "🟢"
            b.tvMode.text = "匿名模式：装完就能爬，不登录也能看到鱼吧帖子和图片。"
            b.modeBar.setBackgroundColor(Color.parseColor("#E3F2FD"))
            b.tvMode.setTextColor(Color.parseColor("#0D47A1"))
        } else {
            b.tvModeIcon.text = "🟡"
            b.tvMode.text = "登录模式：已填 Cookie，可看到完整内容、高清原图、更多帖子。"
            b.modeBar.setBackgroundColor(Color.parseColor("#FFF3E0"))
            b.tvMode.setTextColor(Color.parseColor("#E65100"))
        }
    }
}
