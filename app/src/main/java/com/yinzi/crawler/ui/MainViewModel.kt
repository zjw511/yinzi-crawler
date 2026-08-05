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
    data class Success(val posts: List<Post>, val isRefresh: Boolean) : UiState()
    data class Error(val msg: String) : UiState()
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _groupId = MutableStateFlow(com.yinzi.crawler.util.Prefs.groupId)
    val groupId: StateFlow<String> = _groupId.asStateFlow()

    private var page = 0
    private var loaded: List<Post> = emptyList()

    fun refresh(groupId: String = _groupId.value) {
        page = 0
        load(groupId, isRefresh = true)
    }

    fun loadMore() {
        if (_state.value is UiState.Loading) return
        page++
        load(_groupId.value, isRefresh = false)
    }

    private fun load(groupId: String, isRefresh: Boolean) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val posts = YubaRepository.fetchPosts(groupId, page)
                loaded = if (isRefresh) posts else loaded + posts
                _state.value = UiState.Success(loaded, isRefresh)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setGroupId(id: String) {
        _groupId.value = id
        com.yinzi.crawler.util.Prefs.groupId = id
        refresh(id)
    }
}
