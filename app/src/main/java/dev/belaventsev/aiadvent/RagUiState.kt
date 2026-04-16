package dev.belaventsev.aiadvent

data class RagUiState(
    val question: String = "",
    val plainAnswer: String? = null,
    val ragAnswer: String? = null,
    val ragSources: List<String> = emptyList(),
    val ragContext: String = "",
    val isLoadingPlain: Boolean = false,
    val isLoadingRag: Boolean = false,
    val error: String? = null
)
