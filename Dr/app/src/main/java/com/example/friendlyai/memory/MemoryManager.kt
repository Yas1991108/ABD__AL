package com.example.friendlyai.memory

import android.content.Context

class MemoryManager(context: Context) {

    private val prefs =
        context.getSharedPreferences("sam_memory", Context.MODE_PRIVATE)

    fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun load(key: String): String? {
        return prefs.getString(key, null)
    }

    fun saveLastMessage(message: String) {
        save("last_message", message)
    }

    fun loadLastMessage(): String? {
        return load("last_message")
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
