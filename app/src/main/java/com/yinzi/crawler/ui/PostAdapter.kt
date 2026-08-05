package com.yinzi.crawler.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yinzi.crawler.R
import com.yinzi.crawler.databinding.ItemPostBinding
import com.yinzi.crawler.model.MediaItem
import com.yinzi.crawler.model.Post

class PostAdapter(
    private val onDownloadPost: (Post) -> Unit,
    private val onMediaClick: (MediaItem, Post) -> Unit,
    private val onReachEnd: () -> Unit
) : RecyclerView.Adapter<PostAdapter.VH>() {

    private val posts = mutableListOf<Post>()

    fun submit(list: List<Post>, clear: Boolean = false) {
        if (clear) posts.clear()
        posts.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() { posts.clear(); notifyDataSetChanged() }

    fun snapshot(): List<Post> = posts.toList()

    inner class VH(val b: ItemPostBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = posts.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val post = posts[position]
        val mediaAdapter = MediaAdapter { item, _ -> onMediaClick(item, post) }
        with(holder.b) {
            tvAuthor.text = post.author.ifEmpty { "鱼吧用户" }
            tvTime.text = post.time
            tvContent.text = post.content
            tvContent.visibility = if (post.content.isBlank()) View.GONE else View.VISIBLE

            if (post.avatar != null) {
                Glide.with(ivAvatar).load(post.avatar).circleCrop().into(ivAvatar)
            }

            // 媒体网格：3 列
            val spanCount = if (post.media.size == 1) 1 else 3
            val lm = GridLayoutManager(root.context, spanCount)
            rvMedia.layoutManager = lm
            rvMedia.adapter = mediaAdapter
            rvMedia.isNestedScrollingEnabled = false
            mediaAdapter.submit(post.media)

            val imgCount = post.imageCount
            val vidCount = post.videoCount
            val parts = mutableListOf<String>()
            if (imgCount > 0) parts.add(root.context.getString(R.string.images_count, imgCount))
            if (vidCount > 0) parts.add(root.context.getString(R.string.videos_count, vidCount))
            tvMediaCount.text = parts.joinToString(" · ")
            tvMediaCount.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

            btnDownload.isEnabled = post.media.isNotEmpty()
            btnDownload.setOnClickListener { onDownloadPost(post) }

            // 滚到接近底部时触发加载更多
            if (position == posts.size - 2) onReachEnd()
        }
    }
}
