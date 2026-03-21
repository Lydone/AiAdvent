package dev.belaventsev.aiadvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val STRONG_MODEL = "stepfun/step-3.5-flash:free"
private const val MEDIUM_MODEL = "nvidia/nemotron-3-super-120b-a12b:free"
private const val WEAK_MODEL = "google/gemma-3n-e2b-it:free"

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val history = mutableListOf<ChatMessage>()

    fun ask(query: String, model: String) {
        val userMessage = ChatMessage(role = "user", content = query)
        history.add(userMessage)
        viewModelScope.launch { _uiState.value = fetchAnswer(model) }
    }

    fun reset() {
        history.clear()
        _uiState.value = ChatUiState.Idle
    }

    private suspend fun fetchAnswer(model: String): ChatUiState {
        _uiState.value = ChatUiState.Loading(history.toList())
        return try {
            val startTime = System.currentTimeMillis()
            val response = OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(
                    model = model,
                    messages = history.toList()
                )
            )
            val elapsed = System.currentTimeMillis() - startTime
            val answer = response.choices.first().message.content

            history.add(ChatMessage(role = "assistant", content = answer))
            ChatUiState.Success(
                history.toList(),
                elapsedMs = elapsed,
                tokensUsed = response.usage.totalTokens
            )
        } catch (e: Exception) {
            ChatUiState.Error(e.message ?: "Неизвестная ошибка")
        }
    }

    companion object {
        val MODELS = listOf(
            WEAK_MODEL,
            MEDIUM_MODEL,
            STRONG_MODEL,
        )
    }
}
