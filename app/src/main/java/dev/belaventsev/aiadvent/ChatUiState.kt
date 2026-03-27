package dev.belaventsev.aiadvent

data class ChatUiState(
    val messages: List<MessageWithTokens> = emptyList(),
    val totalSpent: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val useCompression: Boolean = false,
    val summaryText: String? = null
)
