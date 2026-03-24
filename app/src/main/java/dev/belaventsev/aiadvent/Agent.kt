package dev.belaventsev.aiadvent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Агент — самостоятельная сущность, инкапсулирующая:
 * - роль (системный промпт)
 * - историю диалога
 * - логику общения с LLM
 *
 * Единственный публичный метод — ask().
 * История доступна реактивно через messages: StateFlow.
 */
class Agent(
    private val systemPrompt: String,
    private val model: String = MODELS[1],
    private val temperature: Double = 0.7
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** Реактивная история сообщений (user + assistant). */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private fun buildApiMessages(): List<ChatMessage> {
        return listOf(ChatMessage(role = "system", content = systemPrompt)) + _messages.value
    }

    /**
     * Единственная точка входа:
     * 1. Добавляет сообщение пользователя → UI видит сразу
     * 2. Вызывает LLM → добавляет ответ
     */
    suspend fun ask(query: String) {
        _messages.value += ChatMessage(role = "user", content = query)

        val response = OpenRouterClient.service.chat(
            auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
            request = ChatRequest(
                model = model,
                messages = buildApiMessages(),
                temperature = temperature
            )
        )
        val answer = response.choices.first().message.content
        _messages.value += ChatMessage(role = "assistant", content = answer)
    }

    /** Сброс контекста диалога. */
    fun reset() {
        _messages.value = emptyList()
    }

    companion object {
        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",           // слабая, 2B
            "nvidia/nemotron-3-super-120b-a12b:free", // средняя, 120B/12B
            "stepfun/step-3.5-flash:free"             // сильная, 196B/11B
        )
    }
}
