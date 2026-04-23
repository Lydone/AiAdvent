package dev.belaventsev.aiadvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RagViewModel : ViewModel() {

    private val engine = RagEngine()

    private val _uiState = MutableStateFlow(RagUiState())
    val uiState: StateFlow<RagUiState> = _uiState.asStateFlow()

    fun updateQuestion(text: String) {
        _uiState.update { it.copy(question = text) }
    }

    fun toggleRewrite() {
        _uiState.update { it.copy(rewriteEnabled = !it.rewriteEnabled) }
    }

    fun toggleRerank() {
        _uiState.update { it.copy(rerankEnabled = !it.rerankEnabled) }
    }

    fun askWithoutRag() {
        _uiState.update {
            it.copy(
                isLoadingPlain = true,
                plainAnswer = null,
                plainDurationMs = null,
                error = null
            )
        }
        val q = _uiState.value.question.trim()
        if (q.isEmpty()) return

        _uiState.update { it.copy(isLoadingPlain = true, plainAnswer = null, error = null) }
        viewModelScope.launch {
            try {
                val startMs = System.currentTimeMillis()
                val response = engine.askWithoutRag(q)
                val elapsedMs = System.currentTimeMillis() - startMs
                _uiState.update {
                    it.copy(
                        plainAnswer = response.answer,
                        plainDurationMs = elapsedMs,
                        isLoadingPlain = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingPlain = false,
                        error = "Без RAG: ${e.message ?: "неизвестная ошибка"}"
                    )
                }
            }
        }
    }

    fun askWithRag() {
        _uiState.update {
            it.copy(
                isLoadingRag = true,
                ragAnswer = null,
                ragSources = emptyList(),
                ragDiagnostics = null,
                ragDurationMs = null,
                error = null
            )
        }
        val state = _uiState.value
        val q = state.question.trim()
        if (q.isEmpty()) return

        _uiState.update {
            it.copy(
                isLoadingRag = true,
                ragAnswer = null,
                ragSources = emptyList(),
                ragDiagnostics = null,
                error = null
            )
        }
        viewModelScope.launch {
            try {
                val startMs = System.currentTimeMillis()
                val response = engine.askWithRag(
                    question = q,
                    rewriteEnabled = state.rewriteEnabled,
                    rerankEnabled = state.rerankEnabled
                )
                val elapsedMs = System.currentTimeMillis() - startMs
                _uiState.update {
                    it.copy(
                        ragAnswer = response.answer,
                        ragSources = response.sources,
                        ragDiagnostics = response.diagnostics,
                        ragDurationMs = elapsedMs,
                        isLoadingRag = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingRag = false,
                        error = "С RAG: ${e.message ?: "неизвестная ошибка"}"
                    )
                }
            }
        }
    }

    fun clear() {
        _uiState.update {
            RagUiState(
                question = it.question,
                rewriteEnabled = it.rewriteEnabled,
                rerankEnabled = it.rerankEnabled
            )
        }
    }
}
