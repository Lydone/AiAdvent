package dev.belaventsev.aiadvent

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data class Loading(
        val messages: List<MessageWithTokens> = emptyList(),
        val totalSpent: Int = 0
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
    data class Success(
        val messages: List<MessageWithTokens> = emptyList(),
        val totalSpent: Int = 0
    ) : ChatUiState
}
