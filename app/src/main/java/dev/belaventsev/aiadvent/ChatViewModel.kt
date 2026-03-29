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
    private val settingsRepository = SettingsRepository(application)

    private val agent = Agent(
        chatDao = db.chatMessageDao(),
        summaryDao = db.summaryDao(),
        factsDao = db.factsDao(),
        branchDao = db.branchDao(),
        systemPrompt = "Ты — дружелюбный ассистент. Отвечай кратко и по делу. Не используй markdown-разметку в ответах: без **, ##, ```, таблиц и списков с дефисами. Пиши простым текстом."
    )

    private val vmState = MutableStateFlow(VmState())

    init {
        viewModelScope.launch {
            settingsRepository.strategyType.collect { agent.setStrategy(it) }
        }
    }

    val uiState: StateFlow<ChatUiState> = combine(
        agent.messages,
        agent.metadata,
        vmState
    ) { messages, meta, vm ->
        ChatUiState(
            messages = messages,
            totalSpent = meta.totalSpent,
            isLoading = vm.isLoading,
            error = vm.error,
            strategy = meta.strategyType,
            facts = meta.facts,
            currentBranchId = meta.currentBranchId,
            branches = meta.branches
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    fun ask(query: String) {
        vmState.update { it.copy(error = null, isLoading = true) }
        viewModelScope.launch {
            try {
                agent.ask(query)
            } catch (e: Exception) {
                vmState.update { it.copy(error = e.message ?: "Неизвестная ошибка") }
            } finally {
                vmState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            agent.reset()
            vmState.update { it.copy(error = null, isLoading = false) }
        }
    }

    fun setStrategy(type: ContextStrategyType) {
        viewModelScope.launch { settingsRepository.setStrategy(type) }
    }

    fun createBranch(name: String) {
        viewModelScope.launch {
            try {
                agent.createBranch(name)
            } catch (e: Exception) {
                vmState.update { it.copy(error = e.message) }
            }
        }
    }

    fun switchBranch(branchId: String) = agent.switchBranch(branchId)

    private data class VmState(
        val isLoading: Boolean = false,
        val error: String? = null
    )
}
