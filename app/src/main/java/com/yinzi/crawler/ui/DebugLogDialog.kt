package com.yinzi.crawler.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yinzi.crawler.R
import com.yinzi.crawler.databinding.DialogDebugLogBinding
import com.yinzi.crawler.util.DebugLog

/**
 * 调试日志对话框：展示内存中缓存的 DebugLog 内容，支持复制和刷新。
 * 用深色背景 + 等宽字体，像终端一样清晰。
 */
class DebugLogDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogDebugLogBinding.inflate(layoutInflater)

        refresh(binding)

        binding.btnRefresh.setOnClickListener { refresh(binding) }

        binding.btnCopy.setOnClickListener {
            val text = DebugLog.dumpText().ifBlank { "(空日志)" }
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("yinzi_debug_log", text))
            android.widget.Toast.makeText(
                requireContext(),
                "已复制 ${text.length} 字符到剪贴板，去粘贴到聊天框里给开发者看",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔍 调试日志（共 ${DebugLog.getAllLogs().size} 条）")
            .setView(binding.root)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空日志") { _, _ ->
                DebugLog.clear()
                refresh(binding)
                android.widget.Toast.makeText(requireContext(), "日志已清空", android.widget.Toast.LENGTH_SHORT).show()
            }
            .create()
    }

    private fun refresh(b: DialogDebugLogBinding) {
        val logs = DebugLog.getAllLogs()
        b.tvLogCount.text = "${logs.size} 条日志（最多保留 300 条）"
        if (logs.isEmpty()) {
            b.tvLog.text = "(暂无日志，回到主界面下拉刷新触发一次请求，就能看到完整抓数链路了)"
        } else {
            // 倒序显示：最新在最上面（用户不用拉到底）
            b.tvLog.text = logs.asReversed().joinToString("\n\n") { it }
        }
        // 滚到最顶
        b.svLog.post { b.svLog.scrollTo(0, 0) }
    }
}
