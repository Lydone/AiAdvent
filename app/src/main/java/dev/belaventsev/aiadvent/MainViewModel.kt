package dev.belaventsev.aiadvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MODEL = "stepfun/step-3.5-flash:free"

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val history = mutableListOf<ChatMessage>()

    fun ask(query: String, systemPrompt: String = "", temperature: Double? = null) {
        val userMessage = ChatMessage(role = "user", content = query)
        history.add(userMessage)
        viewModelScope.launch { _uiState.value = fetchAnswer(systemPrompt, temperature) }
    }

    fun reset() {
        history.clear()
        _uiState.value = ChatUiState.Idle
    }

    private suspend fun fetchAnswer(systemPrompt: String, temperature: Double?): ChatUiState {
        _uiState.value = ChatUiState.Loading(history.toList())
        return try {
            val answer = OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(
                    model = MODEL,
                    messages = buildMessages(systemPrompt),
                    temperature = temperature
                )
            ).choices.first().message.content

            history.add(ChatMessage(role = "assistant", content = answer))
            ChatUiState.Success(history.toList())
        } catch (e: Exception) {
            ChatUiState.Error(e.message ?: "Неизвестная ошибка")
        }
    }

    private fun buildMessages(systemPrompt: String) = buildList {
        if (systemPrompt.isNotBlank()) add(ChatMessage(role = "system", content = systemPrompt))
        addAll(history)
    }
}
