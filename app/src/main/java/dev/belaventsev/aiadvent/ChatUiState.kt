package dev.belaventsev.aiadvent

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data object Loading : ChatUiState
    data class Success(val answer: String) : ChatUiState
    data class Error(val message: String) : ChatUiState
}
