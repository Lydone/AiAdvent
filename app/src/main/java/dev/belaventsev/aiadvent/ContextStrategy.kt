package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageEntity

interface ContextStrategy {
    suspend fun buildMessages(history: List<ChatMessageEntity>): List<ChatMessage>
    suspend fun reset()
}
