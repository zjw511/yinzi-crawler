package com.yinzi.crawler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yinzi.crawler.model.Post
import com.yinzi.crawler.network.YubaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(
        val posts: List<Post>,
        val isRefresh: Boolean,
        /** 调试信息：走了哪条链路(json/webview/empty)，API错误等，用于弹提示 */
        val debug: String? = null
    ) : UiState()
    data class Error(val msg: String) : UiState()
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _groupId = MutableStateFlow(com.yinzi.crawler.util.Prefs.groupId)
    val groupId: StateFlow<String> = _groupId.asStateFlow()

    private var page = 0
    private var loaded: List<Post> = emptyList()
    /** 已加载过的 post_id 集合，防止分页重复 */
    private val loadedIds = mutableSetOf<String>()
    /** 是否还有更多数据 */
    private var hasMore = true

    fun refresh(groupId: String = _groupId.value) {
        page = 0
        loadedIds.clear()
        hasMore = true
        load(groupId, isRefresh = true)
    }

    fun loadMore() {
        if (_state.value is UiState.Loading) return
        if (!hasMore) return
        page++
        load(_groupId.value, isRefresh = false)
    }

    private fun load(groupId: String, isRefresh: Boolean) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val result = YubaRepository.fetchPostsDebug(groupId, page)
                val posts = result.posts

                // 去重：过滤掉已加载的帖子
                val newPosts = if (isRefresh) posts else posts.filter { it.id !in loadedIds }
                newPosts.forEach { loadedIds.add(it.id) }

                // 如果本页新帖为0，说明到底了
                if (!isRefresh && newPosts.isEmpty()) {
                    hasMore = false
                }
                // API 返回空也标记到底
                if (posts.isEmpty()) {
                    hasMore = false
                }

                loaded = if (isRefresh) posts else loaded + newPosts

                // 拼接调试信息（下拉刷新时显示，让用户立刻知道链路）
                val debug = buildString {
                    append("链路：").append(
                        when (result.via) {
                            "json" -> "✅ JSON接口（快速稳定）"
                            "webview" -> "🟡 WebView兜底（稍慢）"
                            else -> "❌ 两条路都没抓到数据"
                        }
                    )
                    append(" · 本页").append(posts.size).append("条")
                    if (result.apiError != null) append(" · API错误：").append(result.apiError)
                    if (result.parseError != null) append(" · 解析错误：").append(result.parseError)
                }

                if (loaded.isEmpty()) {
                    val hint = buildString {
                        append("鱼吧返回了 0 条帖子。\n")
                        append(debug).append("\n\n")
                        append("排查建议：\n1. 下拉刷新试试；\n2. 网络不通？切 4G/Wi-Fi 重开；\n3. 点右上角 设置 → App 内登录斗鱼 解锁更多内容。")
                    }
                    _state.value = UiState.Error(hint)
                } else {
                    _state.value = UiState.Success(loaded, isRefresh, debug = debug)
                }
            } catch (e: Throwable) {
                val msg = buildString {
                    append("加载失败：").append(e.message ?: e.javaClass.simpleName)
                    append("\n\n排查建议：\n1. 下拉刷新重试；\n2. 确认手机能上网；\n3. 鱼吧服务器可能临时抽风，稍后再试；\n4. 设置 → App 内登录斗鱼试试。")
                }
                _state.value = UiState.Error(msg)
            }
        }
    }

    fun setGroupId(id: String) {
        _groupId.value = id
        com.yinzi.crawler.util.Prefs.groupId = id
        refresh(id)
    }
}
