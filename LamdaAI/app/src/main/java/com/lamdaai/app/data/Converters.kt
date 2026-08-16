package com.lamdaai.app.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toRole(value: String): MessageRole = MessageRole.valueOf(value)
}
