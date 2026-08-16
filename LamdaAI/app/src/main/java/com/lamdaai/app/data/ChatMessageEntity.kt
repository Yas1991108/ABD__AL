package com.lamdaai.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class MessageRole { USER, ASSISTANT, SYSTEM }

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long
)
