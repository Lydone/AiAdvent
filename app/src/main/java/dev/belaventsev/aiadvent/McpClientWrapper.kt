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
 * Multi-server MCP client wrapper.
 * Aggregates tools from all registered servers and routes calls to the correct one.
 */
class McpClientWrapper(
    private val servers: List<McpServer> = DEFAULT_SERVERS
) {

    data class McpServer(
        val name: String,
        val url: String
    )

    // toolName -> serverUrl mapping, populated on listTools()
    private val toolRouting = mutableMapOf<String, String>()

    suspend fun listTools(): List<Tool> {
        toolRouting.clear()
        val allTools = mutableListOf<Tool>()

        for (server in servers) {
            try {
                val tools = withConnection(server.url) { it.listTools().tools }
                tools.forEach { tool ->
                    toolRouting[tool.name] = server.url
                }
                allTools.addAll(tools)
                println("[MCP] ${server.name}: ${tools.size} tools loaded")
            } catch (e: Exception) {
                println("[MCP] ${server.name}: connection failed — ${e.message}")
            }
        }

        return allTools
    }

    suspend fun callTool(name: String, arguments: Map<String, String>): String {
        val serverUrl = toolRouting[name]
            ?: throw IllegalArgumentException("Unknown tool: '$name'. Available: ${toolRouting.keys}")

        val jsonArgs: Map<String, JsonElement> = arguments.mapValues { (_, v) ->
            val intVal = v.toIntOrNull()
            val doubleVal = v.toDoubleOrNull()
            when {
                intVal != null -> JsonPrimitive(intVal)
                doubleVal != null -> JsonPrimitive(doubleVal)
                v == "true" || v == "false" -> JsonPrimitive(v.toBoolean())
                else -> JsonPrimitive(v)
            }
        }
        val result: CallToolResult = withConnection(serverUrl) { it.callTool(name, jsonArgs) }
        return result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
    }

    /** Build tool descriptions for injection into LLM system prompt */
    suspend fun toolDescriptions(): String {
        val tools = listTools()
        if (tools.isEmpty()) return ""

        // Group tools by server for clarity
        val toolsByServer = tools.groupBy { tool ->
            val url = toolRouting[tool.name] ?: "unknown"
            servers.find { it.url == url }?.name ?: url
        }

        return buildString {
            appendLine("AVAILABLE TOOLS (MCP):")
            appendLine("To use a tool, respond with EXACTLY this format on a SEPARATE line:")
            appendLine("[TOOL_CALL] tool_name {\"param\": \"value\"}")
            appendLine()

            for ((serverName, serverTools) in toolsByServer) {
                appendLine("=== $serverName ===")
                serverTools.forEach { tool ->
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
            }

            appendLine("IMPORTANT:")
            appendLine("- Use [TOOL_CALL] ONLY when you need external data.")
            appendLine("- You can call tools from DIFFERENT servers in sequence.")
            appendLine("- After you receive tool results, decide if another tool call is needed or respond to the user.")
        }
    }

    private suspend fun <T> withConnection(serverUrl: String, block: suspend (Client) -> T): T {
        val httpClient = HttpClient {
            install(SSE)
            install(DefaultRequest) {
                headers.append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
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
        val DEFAULT_SERVERS = listOf(
            McpServer(name = "Weather", url = "http://10.0.2.2:3001/mcp"),
        )
    }
}
