package com.lamdaai.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chats
        WHERE title LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
    """)
    fun search(query: String): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("UPDATE chats SET title = :newTitle, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun rename(chatId: String, newTitle: String, updatedAt: Long)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)
}
