package dev.belaventsev.aiadvent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class McpViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(McpUiState())
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    fun connect() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val tools = fetchTools(_uiState.value.serverUrl)
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

    private suspend fun fetchTools(serverUrl: String): List<io.modelcontextprotocol.kotlin.sdk.types.Tool> {
        val httpClient = HttpClient {
            install(SSE)
        }

        val client = Client(
            clientInfo = Implementation(
                name = "aiadvent-android",
                version = "1.0.0"
            )
        )

        return try {
            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = serverUrl
            )
            client.connect(transport)
            client.listTools().tools
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            httpClient.close()
        }
    }
}
