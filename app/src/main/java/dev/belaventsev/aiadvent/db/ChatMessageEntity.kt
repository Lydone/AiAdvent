package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.belaventsev.aiadvent.ChatMessage
import dev.belaventsev.aiadvent.MessageWithTokens
import dev.belaventsev.aiadvent.Usage

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
) {
    fun toChatMessage() = ChatMessage(role, content)

    fun toMessageWithTokens() = MessageWithTokens(
        message = toChatMessage(),
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens
    )

    companion object {
        fun fromChatMessage(userId: String, msg: ChatMessage) =
            ChatMessageEntity(userId = userId, role = msg.role, content = msg.content)

        fun fromAssistantResponse(userId: String, content: String, usage: Usage?) =
            ChatMessageEntity(
                userId = userId,
                role = "assistant",
                content = content,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
                totalTokens = usage?.totalTokens ?: 0
            )
    }
}
