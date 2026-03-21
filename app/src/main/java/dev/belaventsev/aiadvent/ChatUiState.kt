package dev.belaventsev.aiadvent

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data class Loading(val messages: List<ChatMessage> = emptyList()) : ChatUiState
    data class Error(val message: String) : ChatUiState
    data class Success(
        val messages: List<ChatMessage> = emptyList(),
        val elapsedMs: Long = 0,
        val tokensUsed: Int = 0
    ) : ChatUiState
}
