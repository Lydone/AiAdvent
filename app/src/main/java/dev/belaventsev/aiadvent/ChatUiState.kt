package dev.belaventsev.aiadvent

data class ChatUiState(
    val messages: List<MessageWithTokens> = emptyList(),
    val totalSpent: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val strategy: ContextStrategyType = ContextStrategyType.SlidingWindow(),
    val facts: String? = null,
    val currentBranchId: String = "main",
    val branches: List<BranchInfo> = emptyList()
)
