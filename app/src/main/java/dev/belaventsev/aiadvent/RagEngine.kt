package dev.belaventsev.aiadvent

/**
 * Retrieval-Augmented Generation engine.
 * Builds prompts with/without document context and calls the LLM.
 */
class RagEngine(
    private val llm: LlmClient = LlmClient(),
    private val mcpClient: McpClientWrapper = McpClientWrapper(),
    private val topK: Int = 3
) {

    // Tool routing is only populated after listTools() - ensure it's done once.
    private var toolsInitialized = false

    private suspend fun ensureToolsInitialized() {
        if (!toolsInitialized) {
            mcpClient.listTools()
            toolsInitialized = true
        }
    }

    data class RagResponse(
        val answer: String,
        val sources: List<String> = emptyList(),
        val rawContext: String = ""
    )

    /** Plain LLM call without any document context */
    suspend fun askWithoutRag(question: String): RagResponse {
        val messages = listOf(
            ChatMessage(
                "system",
                "Ты полезный помощник. Отвечай кратко и по делу на языке пользователя. " +
                        "Если не знаешь ответа, честно скажи об этом."
            ),
            ChatMessage("user", question)
        )
        val answer = llm.ask(messages, temperature = 0.3)
        return RagResponse(answer = answer)
    }

    /** RAG pipeline: search → inject context → ask LLM */
    suspend fun askWithRag(question: String): RagResponse {
        // 1. Retrieve relevant chunks via MCP search_documents
        val searchResult = try {
            ensureToolsInitialized()
            mcpClient.callTool(
                name = "search_documents",
                arguments = mapOf(
                    "query" to question,
                    "strategy" to "all",
                    "top_k" to topK.toString()
                )
            )
        } catch (e: Exception) {
            return RagResponse(
                answer = "Ошибка RAG-поиска: ${e.message}",
                rawContext = "(поиск не выполнен)"
            )
        }

        // 2. Check if context is empty
        if (searchResult.contains("Ничего не найдено")) {
            return RagResponse(
                answer = "Не могу ответить — в документах ничего не найдено по запросу.",
                rawContext = searchResult
            )
        }

        // 3. Extract source names for display
        val sources = Regex("Источник: (\\S+\\.md[^\\n]*)")
            .findAll(searchResult)
            .map { it.groupValues[1].trim() }
            .distinct()
            .toList()

        // 4. Build prompt with context
        val systemPrompt = """
            |Ты отвечаешь на вопрос пользователя, используя ТОЛЬКО информацию из контекста ниже.
            |Если в контексте нет ответа — скажи "Не могу ответить на основе предоставленных документов".
            |Не выдумывай и не добавляй факты из своих знаний.
            |Отвечай на языке пользователя. Кратко и по делу.
            |
            |КОНТЕКСТ:
            |$searchResult
        """.trimMargin()

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", question)
        )
        val answer = llm.ask(messages, temperature = 0.2)

        return RagResponse(
            answer = answer,
            sources = sources,
            rawContext = searchResult
        )
    }
}

// Сколько стоит проезд на канатной дороге?
