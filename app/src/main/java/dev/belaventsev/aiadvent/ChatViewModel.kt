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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val agent = Agent(
        chatDao = db.chatMessageDao(),
        summaryDao = db.summaryDao(),
        systemPrompt = "Ты — дружелюбный ассистент. Отвечай кратко и по делу."
    )

    private val vmState = MutableStateFlow(VmState())

    val uiState: StateFlow<ChatUiState> = combine(
        agent.messagesWithTokens,
        agent.totalSpent,
        agent.summary,
        vmState
    ) { messages, spent, summary, vm ->
        ChatUiState(
            messages = messages,
            totalSpent = spent,
            isLoading = vm.isLoading,
            error = vm.error,
            useCompression = vm.useCompression,
            summaryText = summary?.text
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    fun ask(query: String) {
        vmState.update { it.copy(error = null, isLoading = true) }
        viewModelScope.launch {
            try {
                agent.ask(query, vmState.value.useCompression)
            } catch (e: Exception) {
                vmState.update { it.copy(error = e.message ?: "Неизвестная ошибка") }
            } finally {
                vmState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleCompression() {
        vmState.update { it.copy(useCompression = !it.useCompression) }
    }

    fun reset() {
        viewModelScope.launch {
            agent.reset()
            vmState.update { it.copy(error = null, isLoading = false) }
        }
    }

    private data class VmState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val useCompression: Boolean = false
    )
}
