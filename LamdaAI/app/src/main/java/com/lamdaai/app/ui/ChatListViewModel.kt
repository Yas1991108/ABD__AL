package com.lamdaai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamdaai.app.data.ChatEntity
import com.lamdaai.app.data.ChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatListViewModel(private val repository: ChatRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()

    val chats: StateFlow<List<ChatEntity>> = query
        .debounce(150)
        .flatMapLatest { repository.observeChats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun createChat(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val chat = repository.createChat()
            onCreated(chat.id)
        }
    }

    fun renameChat(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch { repository.renameChat(id, newTitle.trim()) }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch { repository.deleteChat(id) }
    }
}
