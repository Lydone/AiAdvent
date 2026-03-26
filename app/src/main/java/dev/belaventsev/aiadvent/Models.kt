package dev.belaventsev.aiadvent

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
//    val plugins: List<PluginConfig> = listOf(
//        PluginConfig(id = "context-compression", enabled = false)
//    )
)

data class ChatChoice(
    val message: ChatMessage
)

data class ChatResponse(
    val choices: List<ChatChoice>,
    val usage: Usage
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

data class PluginConfig(
    val id: String,
    val enabled: Boolean
)
