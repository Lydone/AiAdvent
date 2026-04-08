package dev.belaventsev.aiadvent

import io.modelcontextprotocol.kotlin.sdk.types.Tool

data class McpUiState(
    val serverUrl: String = "http://10.0.2.2:3001/mcp",
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val tools: List<Tool> = emptyList(),
    val callResult: String? = null
)
