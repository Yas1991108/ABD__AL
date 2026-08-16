package com.lamdaai.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateContent(messageId: String, content: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteAllForChat(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND createdAt > :afterCreatedAt")
    suspend fun deleteCreatedAfter(chatId: String, afterCreatedAt: Long)
}
