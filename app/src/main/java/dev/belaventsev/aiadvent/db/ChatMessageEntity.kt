package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.belaventsev.aiadvent.ChatMessage

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toChatMessage() = ChatMessage(role = role, content = content)

    companion object {
        fun fromChatMessage(msg: ChatMessage) =
            ChatMessageEntity(role = msg.role, content = msg.content)
    }
}
