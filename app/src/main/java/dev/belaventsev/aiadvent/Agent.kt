package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.LongTermMemoryDao
import dev.belaventsev.aiadvent.db.WorkingMemoryDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class Agent(
    private val chatDao: ChatMessageDao,
    private val workingMemoryDao: WorkingMemoryDao,
    private val longTermMemoryDao: LongTermMemoryDao,
    private val systemPrompt: String? = null,
    private val model: String = MODELS[3],
    private val temperature: Double = 0.7,
    windowSize: Int = 6
) {
    private val strategy = MemoryLayersStrategy(
        windowSize, systemPrompt, workingMemoryDao, longTermMemoryDao, ::callLlm
    )

    val messages: Flow<List<MessageWithTokens>> =
        chatDao.observeAll().map { list -> list.map { it.toMessageWithTokens() } }

    val metadata: Flow<AgentMetadata> = combine(
        chatDao.observeTotalSpent(),
        workingMemoryDao.observe().map { it?.json },
        longTermMemoryDao.observe().map { it?.json }
    ) { spent, working, longTerm ->
        AgentMetadata(spent, working, longTerm)
    }

    suspend fun ask(query: String) {
        chatDao.insert(ChatMessageEntity.fromChatMessage(ChatMessage("user", query)))

        val apiMessages = strategy.buildMessages(chatDao.getAll())

        val response = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, apiMessages, temperature)
            )
        }

        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(
                response.choices.first().message.content, response.usage
            )
        )
    }

    suspend fun reset() {
        strategy.reset()
        chatDao.deleteAll()
        // Long-term memory is NOT cleared — handled by strategy.reset()
    }

    private suspend fun callLlm(messages: List<ChatMessage>): String =
        retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, messages, 0.3)
            )
        }.choices.first().message.content

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastException!!
    }

    companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L

        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "stepfun/step-3.5-flash:free",
            "nvidia/nemotron-3-nano-30b-a3b:free"
        )
    }
}

data class AgentMetadata(
    val totalSpent: Int = 0,
    val workingMemory: String? = null,
    val longTermMemory: String? = null
)

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
