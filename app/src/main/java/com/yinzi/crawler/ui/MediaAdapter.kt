package com.yinzi.crawler.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yinzi.crawler.databinding.ItemMediaBinding
import com.yinzi.crawler.download.DownloadManager
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.util.Prefs

class MediaAdapter(
    private val onClick: (MediaItem, Int) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    private val items = mutableListOf<MediaItem>()

    fun submit(list: List<MediaItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        bind(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("progress")) {
            // 仅刷新进度条，不重新加载图片
            val item = items[position]
            val p = DownloadManager.progress.value[item.url]
            with(holder.b) {
                if (p != null) {
                    if (p < 0) {
                        pbItem.isIndeterminate = true
                        pbItem.visibility = android.view.View.VISIBLE
                        tvProgress.text = "下载中…"
                        tvProgress.visibility = android.view.View.VISIBLE
                    } else {
                        pbItem.isIndeterminate = false
                        pbItem.progress = p
                        pbItem.visibility = android.view.View.VISIBLE
                        tvProgress.text = "$p%"
                        tvProgress.visibility = android.view.View.VISIBLE
                    }
                } else {
                    pbItem.visibility = android.view.View.GONE
                    tvProgress.visibility = android.view.View.GONE
                }
                ivDone.visibility = if (Prefs.isDownloaded(item.url)) android.view.View.VISIBLE else android.view.View.GONE
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bind(holder: VH, position: Int) {
        val item = items[position]
        with(holder.b) {
            val thumb = item.thumbUrl ?: item.url
            Glide.with(ivThumb).load(thumb).centerCrop().into(ivThumb)
            ivVideoTag.visibility = if (item.isVideo) android.view.View.VISIBLE else android.view.View.GONE
            ivDone.visibility = if (Prefs.isDownloaded(item.url)) android.view.View.VISIBLE else android.view.View.GONE
            root.setOnClickListener { onClick(item, position) }

            val p = DownloadManager.progress.value[item.url]
            if (p != null) {
                if (p < 0) {
                    pbItem.isIndeterminate = true
                    pbItem.visibility = android.view.View.VISIBLE
                    tvProgress.text = "下载中…"
                    tvProgress.visibility = android.view.View.VISIBLE
                } else {
                    pbItem.isIndeterminate = false
                    pbItem.progress = p
                    pbItem.visibility = android.view.View.VISIBLE
                    tvProgress.text = "$p%"
                    tvProgress.visibility = android.view.View.VISIBLE
                }
            } else {
                pbItem.visibility = android.view.View.GONE
                tvProgress.visibility = android.view.View.GONE
            }
        }
    }

    /** 刷新所有 item 的进度条（onBindViewHolder 会判断是否显示） */
    fun refreshProgress(@Suppress("UNUSED_PARAMETER") progressMap: Map<String, Int>) {
        for (i in items.indices) {
            notifyItemChanged(i, "progress")
        }
    }
}
