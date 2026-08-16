package com.lamdaai.app.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val dao: ChatDao) {

    fun observeChats(query: String): Flow<List<ChatEntity>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query)

    suspend fun createChat(title: String = "New chat"): ChatEntity {
        val now = System.currentTimeMillis()
        val chat = ChatEntity(title = title, createdAt = now, updatedAt = now)
        dao.upsert(chat)
        return chat
    }

    suspend fun renameChat(id: String, newTitle: String) {
        dao.rename(id, newTitle, System.currentTimeMillis())
    }

    suspend fun deleteChat(id: String) {
        dao.delete(id)
    }
}
