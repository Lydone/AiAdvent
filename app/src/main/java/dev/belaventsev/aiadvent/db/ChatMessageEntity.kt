package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.belaventsev.aiadvent.ChatMessage
import dev.belaventsev.aiadvent.MessageWithTokens
import dev.belaventsev.aiadvent.Usage

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val branchId: String = "main",
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
        fun fromChatMessage(msg: ChatMessage, branchId: String = "main") =
            ChatMessageEntity(role = msg.role, content = msg.content, branchId = branchId)

        fun fromAssistantResponse(content: String, usage: Usage?, branchId: String = "main") =
            ChatMessageEntity(
                role = "assistant",
                content = content,
                branchId = branchId,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
                totalTokens = usage?.totalTokens ?: 0
            )
    }
}
