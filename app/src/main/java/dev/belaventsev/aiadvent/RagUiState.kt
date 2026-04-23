package dev.belaventsev.aiadvent

data class RagUiState(
    val question: String = "",
    // Toggles
    val rewriteEnabled: Boolean = false,
    val rerankEnabled: Boolean = false,
    // Plain answer
    val plainAnswer: String? = null,
    val isLoadingPlain: Boolean = false,
    val plainDurationMs: Long? = null,
    // RAG answer + diagnostics
    val ragAnswer: String? = null,
    val ragSources: List<String> = emptyList(),
    val ragDiagnostics: RagEngine.RagDiagnostics? = null,
    val isLoadingRag: Boolean = false,
    val ragDurationMs: Long? = null,
    // Errors
    val error: String? = null
)
