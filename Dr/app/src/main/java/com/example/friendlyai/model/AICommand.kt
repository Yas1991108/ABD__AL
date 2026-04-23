package com.example.friendlyai.model

data class AICommand(
    val action: String,
    val value: String? = null,
    val steps: List<AICommand>? = null
)
