package dev.belaventsev.aiadvent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reusable wrapper around MCP SDK client.
 * Creates a fresh connection for each operation, then closes.
 */
class McpClientWrapper(
    private val serverUrl: String = MCP_SERVER_URL
) {

    suspend fun listTools(): List<Tool> =
        withConnection { it.listTools().tools }

    suspend fun callTool(name: String, arguments: Map<String, String>): String {
        val jsonArgs: Map<String, JsonElement> = arguments.mapValues { (_, v) ->
            JsonPrimitive(v)
        }
        val result: CallToolResult = withConnection { it.callTool(name, jsonArgs) }
        return result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
    }

    /** Build tool descriptions for injection into LLM system prompt */
    suspend fun toolDescriptions(): String {
        val tools = listTools()
        if (tools.isEmpty()) return ""

        return buildString {
            appendLine("AVAILABLE TOOLS (MCP):")
            appendLine("To use a tool, respond with EXACTLY this format on a SEPARATE line:")
            appendLine("[TOOL_CALL] tool_name {\"param\": \"value\"}")
            appendLine()
            appendLine("Available tools:")
            tools.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description ?: "no description"}")
                tool.inputSchema.properties?.let { props ->
                    val required = tool.inputSchema.required ?: emptyList()
                    props.entries.forEach { (name, schema) ->
                        val req = if (name in required) " (required)" else " (optional)"
                        appendLine("    $name$req: $schema")
                    }
                }
            }
            appendLine()
            appendLine("IMPORTANT: Use [TOOL_CALL] ONLY when you need external data.")
            appendLine("After you receive tool results, formulate a natural response to the user.")
        }
    }

    private suspend fun <T> withConnection(block: suspend (Client) -> T): T {
        val httpClient = HttpClient {
            install(SSE)
            install(DefaultRequest) {
                headers.append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
            }
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
            block(client)
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            httpClient.close()
        }
    }

    companion object {
        const val MCP_SERVER_URL = "http://10.0.2.2:3001/mcp"
    }
}
