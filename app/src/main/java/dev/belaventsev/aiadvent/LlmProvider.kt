package dev.belaventsev.aiadvent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LlmProvider(val displayName: String) {
    OPENROUTER("OpenRouter"),
    LOCAL("Local")
}

/**
 * Global in-memory holder for the active LLM provider.
 * Reset to OPENROUTER on every app start.
 */
object LlmProviderHolder {
    private val _provider = MutableStateFlow(LlmProvider.OPENROUTER)
    val provider: StateFlow<LlmProvider> = _provider.asStateFlow()

    fun toggle() {
        _provider.value = when (_provider.value) {
            LlmProvider.OPENROUTER -> LlmProvider.LOCAL
            LlmProvider.LOCAL -> LlmProvider.OPENROUTER
        }
    }

    fun current(): LlmProvider = _provider.value
}

/**
 * Builds a configured LlmClient for the given provider.
 * Local = Ollama on Mac host, reachable from the emulator via 10.0.2.2.
 */
object LlmClientFactory {

    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/"
    private const val LOCAL_BASE_URL = "http://10.0.2.2:11434/"
    private const val LOCAL_MODEL = "qwen2.5:7b-instruct"

    fun create(provider: LlmProvider): LlmClient = when (provider) {
        LlmProvider.OPENROUTER -> LlmClient(
            baseUrl = OPENROUTER_BASE_URL,
            model = Agent.MODELS[2],
            apiKey = BuildConfig.OPENROUTER_API_KEY
        )

        LlmProvider.LOCAL -> LlmClient(
            baseUrl = LOCAL_BASE_URL,
            model = LOCAL_MODEL,
            apiKey = null
        )
    }

    /** Convenience: build a client for the currently active provider */
    fun current(): LlmClient = create(LlmProviderHolder.current())
}
