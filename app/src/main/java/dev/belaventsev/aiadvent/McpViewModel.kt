package dev.belaventsev.aiadvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class McpViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(McpUiState())
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    private val mcpClient = McpClientWrapper()

    fun connect() {
        _uiState.update { it.copy(isLoading = true, error = null, callResult = null) }
        viewModelScope.launch {
            try {
                val tools = mcpClient.listTools()
                _uiState.update {
                    it.copy(isLoading = false, isConnected = true, tools = tools)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isConnected = false,
                        error = e.message ?: "Ошибка подключения"
                    )
                }
            }
        }
    }

    fun callTool(toolName: String, arguments: Map<String, String>) {
        _uiState.update { it.copy(isLoading = true, error = null, callResult = null) }
        viewModelScope.launch {
            try {
                val result = mcpClient.callTool(toolName, arguments)
                _uiState.update {
                    it.copy(isLoading = false, callResult = result)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Ошибка вызова")
                }
            }
        }
    }
}
