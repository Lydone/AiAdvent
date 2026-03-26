package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class Agent(
    private val dao: ChatMessageDao,
    private val systemPrompt: String? = null,
    private val model: String = MODELS[0],
    private val temperature: Double = 0.7
) {
    val messagesWithTokens: Flow<List<MessageWithTokens>> =
        dao.observeAll().map { entities ->
            entities.map { entity ->
                MessageWithTokens(
                    message = entity.toChatMessage(),
                    promptTokens = entity.promptTokens,
                    completionTokens = entity.completionTokens,
                    totalTokens = entity.totalTokens
                )
            }
        }

    val totalSpent: Flow<Int> = dao.observeTotalSpent()

    suspend fun ask(query: String) {
        dao.insert(ChatMessageEntity.fromChatMessage(ChatMessage(role = "user", content = query)))

        val history = dao.observeAll().first().map { it.toChatMessage() }
        val apiMessages = buildList {
            systemPrompt?.let { add(ChatMessage(role = "system", content = it)) }
            addAll(history)
        }

        val response = OpenRouterClient.service.chat(
            auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
            request = ChatRequest(
                model = model,
                messages = apiMessages,
                temperature = temperature
            )
        )

        val answer = response.choices.first().message.content
        dao.insert(ChatMessageEntity.fromAssistantResponse(answer, response.usage))
    }

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

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
