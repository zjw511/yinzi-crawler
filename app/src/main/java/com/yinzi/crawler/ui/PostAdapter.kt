package com.yinzi.crawler.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yinzi.crawler.R
import com.yinzi.crawler.databinding.ItemPostBinding
import com.yinzi.crawler.download.DownloadManager
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post

class PostAdapter(
    private val onDownloadPost: (Post) -> Unit,
    private val onMediaClick: (MediaItem, Post) -> Unit,
    private val onReachEnd: () -> Unit
) : RecyclerView.Adapter<PostAdapter.VH>() {

    private val posts = mutableListOf<Post>()

    /** 所有活跃的 MediaAdapter 引用（用于进度刷新） */
    private val mediaAdapters = mutableSetOf<MediaAdapter>()

    fun submit(list: List<Post>, clear: Boolean = false) {
        if (clear) posts.clear()
        posts.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() { posts.clear(); notifyDataSetChanged() }

    fun snapshot(): List<Post> = posts.toList()

    /** 下载进度变化时调用：刷新所有活跃的 MediaAdapter */
    fun onProgressChanged() {
        val map = DownloadManager.progress.value
        for (ma in mediaAdapters) {
            ma.refreshProgress(map)
        }
    }

    inner class VH(val b: ItemPostBinding) : RecyclerView.ViewHolder(b.root) {
        var mediaAdapter: MediaAdapter? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = posts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val post = posts[position]
        val mediaAdapter = MediaAdapter { item, _ -> onMediaClick(item, post) }
        holder.mediaAdapter?.let { mediaAdapters.remove(it) }
        mediaAdapters.add(mediaAdapter)
        holder.mediaAdapter = mediaAdapter
        with(holder.b) {
            // 媒体网格：2 列（单张时撑满整行），图片更大更清晰
            val spanCount = if (post.media.size == 1) 1 else 2
            val lm = GridLayoutManager(root.context, spanCount)
            rvMedia.layoutManager = lm
            rvMedia.adapter = mediaAdapter
            rvMedia.isNestedScrollingEnabled = false
            mediaAdapter.submit(post.media)

            btnDownload.isEnabled = post.media.isNotEmpty()
            btnDownload.visibility = if (post.media.isNotEmpty())
                android.view.View.VISIBLE else android.view.View.GONE
            btnDownload.setOnClickListener { onDownloadPost(post) }

            // 滚到接近底部时触发加载更多
            if (position == posts.size - 2) onReachEnd()
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.mediaAdapter?.let { mediaAdapters.remove(it) }
        holder.mediaAdapter = null
    }
}
