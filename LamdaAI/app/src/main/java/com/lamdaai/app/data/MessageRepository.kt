package com.lamdaai.app.data

import kotlinx.coroutines.flow.Flow

class MessageRepository(private val dao: MessageDao) {

    fun observeMessages(chatId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(chatId)

    suspend fun addMessage(chatId: String, role: MessageRole, content: String): MessageEntity {
        val message = MessageEntity(
            chatId = chatId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis()
        )
        dao.insert(message)
        return message
    }

    suspend fun updateContent(messageId: String, content: String) {
        dao.updateContent(messageId, content)
    }

    suspend fun deleteMessage(messageId: String) {
        dao.delete(messageId)
    }

    /** Removes every message strictly after [afterCreatedAt] in the same chat — used by edit/regenerate to drop stale turns. */
    suspend fun deleteMessagesAfter(chatId: String, afterCreatedAt: Long) {
        dao.deleteCreatedAfter(chatId, afterCreatedAt)
    }
}
