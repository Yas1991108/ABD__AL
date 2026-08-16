package com.lamdaai.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Metadata row for one chat conversation.
 *
 * Deliberately holds no messages yet — message storage arrives with the
 * llama.cpp bridge slice, since there's no point designing that table
 * until there's a generator producing content for it.
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)
