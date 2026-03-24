package dev.belaventsev.aiadvent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.belaventsev.aiadvent.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).chatMessageDao()

    private val agent = Agent(
        dao = dao,
        systemPrompt = "Ты — дружелюбный ассистент. Отвечай кратко и по делу."
    )

    private val isLoading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChatUiState> = combine(
        agent.messages,
        isLoading,
        error
    ) { messages, loading, err ->
        when {
            err != null -> ChatUiState.Error(err)
            loading -> ChatUiState.Loading(messages)
            messages.isEmpty() -> ChatUiState.Idle
            else -> ChatUiState.Success(messages)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState.Idle)

    fun ask(query: String) {
        error.value = null
        isLoading.value = true
        viewModelScope.launch {
            try {
                agent.ask(query)
            } catch (e: Exception) {
                error.value = e.message ?: "Неизвестная ошибка"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            agent.reset()
            error.value = null
            isLoading.value = false
        }
    }
}
