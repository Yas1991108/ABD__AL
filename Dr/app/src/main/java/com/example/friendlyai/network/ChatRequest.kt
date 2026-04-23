package com.example.friendlyai.network

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String = "openai/gpt-3.5-turbo",
    val messages: List<ChatMessage>,
    val max_tokens: Int = 500
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: ChatMessage
)
