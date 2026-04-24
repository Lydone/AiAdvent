package dev.belaventsev.aiadvent

import kotlinx.coroutines.delay

class LlmClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    private val numCtx: Int? = null
) {

    private val service get() = OpenRouterClient.serviceFor(baseUrl)

    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.7
    ): LlmResponse {
        val response = retrying {
            service.chat(
                auth = apiKey?.let { "Bearer $it" },
                request = ChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    options = numCtx?.let { mapOf("num_ctx" to it) }
                )
            )
        }
        return LlmResponse(
            content = response.choices.first().message.content,
            usage = response.usage
        )
    }

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
