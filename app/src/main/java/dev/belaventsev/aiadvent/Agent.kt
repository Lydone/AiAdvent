package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.Agent.Companion.MAX_RETRIES
import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.SummaryDao
import dev.belaventsev.aiadvent.db.SummaryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class Agent(
    private val chatDao: ChatMessageDao,
    private val summaryDao: SummaryDao,
    private val systemPrompt: String? = null,
    private val model: String = MODELS[2],
    private val temperature: Double = 0.7
) {
    val messagesWithTokens: Flow<List<MessageWithTokens>> =
        chatDao.observeAll().map { entities ->
            entities.map { it.toMessageWithTokens() }
        }

    val totalSpent: Flow<Int> = chatDao.observeTotalSpent()

    val summary: Flow<SummaryEntity?> = summaryDao.observe()

    suspend fun ask(query: String, useCompression: Boolean) {
        chatDao.insert(ChatMessageEntity.fromChatMessage(ChatMessage("user", query)))

        val apiMessages = if (useCompression) compressedMessages() else fullMessages()

        val response = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, apiMessages, temperature)
            )
        }

        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(
                response.choices.first().message.content,
                response.usage
            )
        )
    }

    suspend fun reset() {
        chatDao.deleteAll()
        summaryDao.deleteAll()
    }

    private suspend fun fullMessages(): List<ChatMessage> = buildList {
        systemPrompt?.let { add(ChatMessage("system", it)) }
        addAll(chatDao.getAll().map { it.toChatMessage() })
    }

    private suspend fun compressedMessages(): List<ChatMessage> {
        val all = chatDao.getAll()
        val summary = summaryDao.get()
        val unsummarized =
            if (summary != null) all.filter { it.id > summary.lastMessageId } else all

        if (unsummarized.size > WINDOW_SIZE) {
            val toSummarize = unsummarized.dropLast(WINDOW_SIZE)
            summaryDao.upsert(
                SummaryEntity(
                    text = summarize(summary?.text, toSummarize.map { it.toChatMessage() }),
                    lastMessageId = toSummarize.last().id
                )
            )
        }

        val finalSummary = summaryDao.get()
        val recent =
            if (finalSummary != null) all.filter { it.id > finalSummary.lastMessageId } else all

        return buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
            finalSummary?.let {
                add(
                    ChatMessage(
                        "system",
                        "Краткое содержание предыдущего диалога:\n${it.text}"
                    )
                )
            }
            addAll(recent.map { it.toChatMessage() })
        }
    }

    private suspend fun summarize(previousSummary: String?, messages: List<ChatMessage>): String {
        val prompt = buildString {
            append("Кратко резюмируй следующий диалог в 2-3 предложениях на русском языке. ")
            append("Сохрани ключевые факты и контекст.\n\n")
            previousSummary?.let { append("Предыдущее резюме: $it\n\n") }
            append("Новые сообщения:\n")
            messages.forEach { append("${it.role}: ${it.content}\n") }
        }

        val response = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, listOf(ChatMessage("user", prompt)), 0.3)
            )
        }

        return response.choices.first().message.content
    }

    /**
     * Выполняет блок до [MAX_RETRIES] раз с экспоненциальной задержкой.
     * Если все попытки неуспешны — пробрасывает последнее исключение.
     */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastException!!
    }

    companion object {
        const val WINDOW_SIZE = 6
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L

        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "stepfun/step-3.5-flash:free"
        )
    }
}

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
