package com.lamdaai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lamdaai.app.data.ChatRepository

class ChatListViewModelFactory(
    private val repository: ChatRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatListViewModel::class.java))
        return ChatListViewModel(repository) as T
    }
}
