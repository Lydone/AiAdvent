package dev.belaventsev.aiadvent

import io.modelcontextprotocol.kotlin.sdk.types.Tool

data class McpUiState(
    val serverUrl: String = "https://mcp001.vkusvill.ru/mcp",
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val tools: List<Tool> = emptyList()
)
