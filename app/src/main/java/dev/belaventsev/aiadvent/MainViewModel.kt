package dev.belaventsev.aiadvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun ask(query: String) {
        viewModelScope.launch {
            _uiState.value = ChatUiState.Loading

            _uiState.value = try {
                val answer = OpenRouterClient.service.chat(
                    auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                    request = ChatRequest(
                        model = "google/gemma-3n-e2b-it:free",
                        messages = listOf(ChatMessage("user", query))
                    )
                ).choices.first().message.content

                ChatUiState.Success(answer)
            } catch (e: Exception) {
                ChatUiState.Error(e.message ?: "Неизвестная ошибка")
            }
        }
    }
}
