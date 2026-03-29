package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageEntity

class SlidingWindowStrategy(
    private val windowSize: Int,
    private val systemPrompt: String?
) : ContextStrategy {

    override suspend fun buildMessages(history: List<ChatMessageEntity>): List<ChatMessage> =
        buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
            addAll(history.takeLast(windowSize).map { it.toChatMessage() })
        }

    override suspend fun reset() { /* нет собственного состояния */
    }
}
