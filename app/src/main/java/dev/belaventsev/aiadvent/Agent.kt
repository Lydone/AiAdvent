package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Агент — самостоятельная сущность, инкапсулирующая:
 * - роль (системный промпт)
 * - историю диалога (хранится в Room)
 * - логику общения с LLM
 *
 * Единственный публичный метод — ask().
 * История доступна реактивно через messages: Flow.
 * Room — единственный источник правды.
 */
class Agent(
    private val dao: ChatMessageDao,
    private val systemPrompt: String,
    private val model: String = MODELS[1],
    private val temperature: Double = 0.7
) {
    /** Реактивная история — Room эмитит обновления автоматически. */
    val messages: Flow<List<ChatMessage>> =
        dao.observeAll().map { entities -> entities.map { it.toChatMessage() } }

    /**
     * Единственная точка входа:
     * 1. Сохраняет сообщение пользователя в Room → Flow обновляется → UI видит сразу
     * 2. Вызывает LLM → сохраняет ответ в Room → Flow обновляется
     */
    suspend fun ask(query: String) {
        dao.insert(ChatMessageEntity.fromChatMessage(ChatMessage(role = "user", content = query)))

        val history = messages.first()
        val apiMessages = listOf(ChatMessage(role = "system", content = systemPrompt)) + history

        val response = OpenRouterClient.service.chat(
            auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
            request = ChatRequest(
                model = model,
                messages = apiMessages,
                temperature = temperature
            )
        )
        val answer = response.choices.first().message.content
        dao.insert(
            ChatMessageEntity.fromChatMessage(
                ChatMessage(
                    role = "assistant",
                    content = answer
                )
            )
        )
    }

    /** Сброс контекста диалога. */
    suspend fun reset() {
        dao.deleteAll()
    }

    companion object {
        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",           // слабая, 2B
            "nvidia/nemotron-3-super-120b-a12b:free", // средняя, 120B/12B
            "stepfun/step-3.5-flash:free"             // сильная, 196B/11B
        )
    }
}
