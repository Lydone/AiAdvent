package dev.belaventsev.aiadvent

import kotlinx.coroutines.delay

/**
 * Wrapper around OpenRouter API.
 * Encapsulates retry logic, auth, and request construction.
 */
class LlmClient(
    private val model: String = Agent.DEFAULT_MODEL,
    private val apiKey: String = BuildConfig.OPENROUTER_API_KEY
) {

    /** Send chat messages and return assistant's text response */
    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.7
    ): LlmResponse {
        val response = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer $apiKey",
                request = ChatRequest(model, messages, temperature)
            )
        }
        return LlmResponse(
            content = response.choices.first().message.content,
            usage = response.usage
        )
    }

    /** Convenience: send messages, return only text */
    suspend fun ask(
        messages: List<ChatMessage>,
        temperature: Double = 0.3
    ): String = chat(messages, temperature).content

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
    }
}

data class LlmResponse(
    val content: String,
    val usage: Usage?
)
