package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.InvariantDao
import dev.belaventsev.aiadvent.db.LongTermMemoryDao
import dev.belaventsev.aiadvent.db.LongTermMemoryEntity
import dev.belaventsev.aiadvent.db.WorkingMemoryDao
import dev.belaventsev.aiadvent.db.WorkingMemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

class Agent(
    private val userId: String,
    private val chatDao: ChatMessageDao,
    private val workingMemoryDao: WorkingMemoryDao,
    private val longTermMemoryDao: LongTermMemoryDao,
    private val invariantDao: InvariantDao,
    private val llmProvider: () -> LlmClient = { LlmClientFactory.current() },
    private val mcpClient: McpClientWrapper? = null,
    private val windowSize: Int = 6
) {

    private val systemPrompt = """
        |You are a helpful assistant.
        |
        |RULES:
        |1. Answer in the user's language (detect from their messages).
        |2. Do NOT use markdown formatting: no **, ##, ```, tables, or bullet lists with dashes.
        |   Write in plain text. Use numbered lists only when listing steps.
        |3. Keep answers concise unless the user's profile says otherwise.
        |4. If the user returns after a pause, continue from where you left off.
        |   Do NOT repeat explanations already given.
        |
        |INVARIANTS:
        |You may be given a list of invariants — hard constraints that MUST NOT be violated.
        |If the user's request conflicts with any invariant:
        |1. Do NOT fulfill the request.
        |2. Clearly state which invariant would be violated and why.
        |3. Suggest an alternative that respects the invariant.
        |4. Ask a clarifying question to help the user reformulate the request.
        |Never ignore, bend, or work around invariants, even if the user insists.
    """.trimMargin()

    val messages: Flow<List<MessageWithTokens>> =
        chatDao.observeAll(userId).map { list -> list.map { it.toMessageWithTokens() } }

    val metadata: Flow<AgentMetadata> = combine(
        chatDao.observeTotalSpent(userId),
        workingMemoryDao.observe(userId),
        longTermMemoryDao.observe(userId).map { it?.json }
    ) { spent, working, longTerm ->
        AgentMetadata(
            totalSpent = spent,
            workingMemory = working?.json,
            longTermMemory = longTerm
        )
    }

    // --- Pipeline ---

    suspend fun ask(query: String) {
        // 1. Save user message
        chatDao.insert(ChatMessageEntity.fromChatMessage(userId, ChatMessage("user", query)))

        // 2. RAG: search documents for context
        val ragContext = searchDocuments(query)

        // 3. Assemble prompt (with RAG context)
        val history = chatDao.getAll(userId)
        val invariants = collectInvariants()
        val apiMessages = assemblePrompt(history, invariants, ragContext)

        // 4. Call LLM
        var response = llmProvider().chat(apiMessages)

        // 4. Validate against invariants; retry once if violated
        if (invariants.isNotEmpty()) {
            val violation = checkInvariants(response.content, invariants)
            if (violation != null) {
                val correctedMessages = apiMessages + invariantViolationHint(violation)
                response = llmProvider().chat(correctedMessages)
            }
        }

        var assistantContent = response.content

// 5. Handle MCP tool calls — loop for pipeline
        var iteration = 0
        val maxIterations = 5

        while (iteration < maxIterations && mcpClient != null) {
            val toolCall = parseToolCall(assistantContent) ?: break
            iteration++

            // Save intermediate step
            chatDao.insert(
                ChatMessageEntity.toolStep(
                    userId,
                    "Вызов: ${toolCall.toolName}(${toolCall.arguments})"
                )
            )

            val toolResult = try {
                mcpClient.callTool(toolCall.toolName, toolCall.arguments)
            } catch (e: Exception) {
                "Ошибка вызова инструмента: ${e.message}"
            }

            // Save tool result
            chatDao.insert(
                ChatMessageEntity.toolStep(userId, "Результат ${toolCall.toolName}:\n$toolResult")
            )

            // Ask LLM what to do next
            val currentHistory = chatDao.getAll(userId)
            val nextMessages = assemblePrompt(currentHistory, invariants) +
                    ChatMessage(
                        "system",
                        "Tool '${toolCall.toolName}' returned:\n$toolResult\n\n" +
                                "If you need to call another tool in the pipeline, respond with [TOOL_CALL]. " +
                                "If all steps are done, respond naturally to the user. " +
                                "Do NOT repeat tool results verbatim — summarize them."
                    )

            val nextResponse = llmProvider().chat(nextMessages)
            assistantContent = nextResponse.content
        }

        // 6. Save assistant response
        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(userId, assistantContent, response.usage)
        )

        // 7. Update working memory
        val fullHistory = chatDao.getAll(userId)
        updateWorkingMemory(fullHistory)

        // 8. Update long-term memory
        updateLongTermMemory(fullHistory)
    }

    suspend fun reset() {
        workingMemoryDao.deleteAll(userId)
        chatDao.deleteAll(userId)
    }

    // --- Tool call parsing ---

    private data class ToolCallRequest(
        val toolName: String,
        val arguments: Map<String, String>
    )

    private fun parseToolCall(response: String): ToolCallRequest? {
        val line = response.lines().firstOrNull { it.trimStart().startsWith("[TOOL_CALL]") }
            ?: return null

        val afterTag = line.substringAfter("[TOOL_CALL]").trim()
        val toolName = afterTag.substringBefore(" ").substringBefore("{").trim()
        if (toolName.isBlank()) return null

        val jsonStart = afterTag.indexOf("{")
        if (jsonStart < 0) return ToolCallRequest(toolName, emptyMap())

        val jsonStr = afterTag.substring(jsonStart)
        return try {
            val jsonMap =
                Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(jsonStr)
            val map = jsonMap.mapValues { (_, v) -> v.jsonPrimitive.content }
            ToolCallRequest(toolName, map)
        } catch (_: Exception) {
            ToolCallRequest(toolName, emptyMap())
        }
    }

    // --- Pipeline stages ---

    private suspend fun updateWorkingMemory(history: List<ChatMessageEntity>) {
        val current = workingMemoryDao.get(userId)
        val lastProcessedId = current?.lastProcessedMessageId ?: 0
        val unprocessed = history.filter { it.id > lastProcessedId }
        if (unprocessed.isEmpty()) return

        val prompt = buildString {
            append("You are a working memory extractor.\n\n")
            append("Your job: summarize the CURRENT task state from the conversation.\n")
            append("Include: what is being done, what step we are on, what is expected next, ")
            append("any intermediate results or artifacts.\n\n")
            if (current != null) {
                append("Previous state:\n${current.json}\n\n")
            }
            append("New messages:\n")
            unprocessed.forEach { append("- [${it.role}] ${it.content}\n") }
            append("\nIf there is no active task, write: No active task.\n")
            append("Output ONLY the state summary in the user's language. No explanations.")
        }

        val result = llmProvider().ask(listOf(ChatMessage("user", prompt)))
        workingMemoryDao.upsert(
            WorkingMemoryEntity(
                userId = userId,
                json = result,
                lastProcessedMessageId = history.last().id
            )
        )
    }

    private suspend fun updateLongTermMemory(history: List<ChatMessageEntity>) {
        val current = longTermMemoryDao.get(userId)
        val lastProcessedId = current?.lastProcessedMessageId ?: 0

        val unprocessed = history.filter { it.id > lastProcessedId && it.role == "user" }
        if (unprocessed.isEmpty()) return

        val prompt = buildString {
            append("You are a user IDENTITY extractor. Extract ONLY permanent traits about WHO the user is.\n\n")
            append("INCLUDE (stable facts that stay true across conversations):\n")
            append("- name, age, gender, location, language\n")
            append("- dietary restrictions, allergies, health conditions\n")
            append("- profession, expertise, education\n")
            append("- family, pets, living situation\n")
            append("- long-term preferences: cuisine, hobbies, interests\n")
            append("- response style preferences: brief/detailed, formal/casual, humor level\n")
            append("- format preferences: plain text / lists / step-by-step, preferred language for answers\n")
            append("- restrictions and dislikes: topics to avoid, foods disliked, things the user explicitly does not want\n\n")
            append("NEVER INCLUDE (these belong to working memory, not profile):\n")
            append("- current tasks, questions, or requests\n")
            append("- shopping lists, plans, recipes, calculations\n")
            append("- what the user asked to do in this conversation\n")
            append("- temporary goals or one-time decisions\n\n")
            if (current != null) {
                append("Current profile:\n${current.json}\n\n")
            } else {
                append("Current profile: empty\n\n")
            }
            append("New messages:\n")
            unprocessed.forEach { append("- ${it.content}\n") }
            append("\nUpdate the profile: add new identity facts, modify changed ones. ")
            append("Remove ANYTHING that is a task, request, or temporary plan. ")
            append("Keep the user's language for values. ")
            append("Output ONLY the updated profile in format:\n")
            append("key: value\nkey: value\n\n")
            append("No explanations, no markdown — only key-value pairs.")
        }

        val result = llmProvider().ask(listOf(ChatMessage("user", prompt)))
        longTermMemoryDao.upsert(
            LongTermMemoryEntity(
                userId = userId,
                json = result,
                lastProcessedMessageId = history.last().id
            )
        )
    }

    private suspend fun collectInvariants(): List<String> =
        invariantDao.getAll(userId).map { it.text }

    /**
     * Search documents via MCP for RAG context.
     * Returns formatted context string or empty if search fails/no results.
     */
    private suspend fun searchDocuments(query: String): String {
        if (mcpClient == null) return ""

        return try {
            // Ensure tools are loaded
            mcpClient.listTools()

            val result = mcpClient.callTool(
                name = "search_documents",
                arguments = mapOf(
                    "query" to query,
                    "strategy" to "all",
                    "top_k" to "3"
                )
            )

            if (result.contains("Ничего не найдено")) "" else result
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun assemblePrompt(
        history: List<ChatMessageEntity>,
        invariants: List<String>,
        ragContext: String = ""
    ): List<ChatMessage> {
        val longTerm = longTermMemoryDao.get(userId)
        val working = workingMemoryDao.get(userId)
        val recent = history.takeLast(windowSize)

        val toolsSection = try {
            mcpClient?.toolDescriptions() ?: ""
        } catch (_: Exception) {
            ""
        }

        return buildList {
            add(ChatMessage("system", systemPrompt))
            longTerm?.let {
                add(
                    ChatMessage(
                        "system",
                        "User profile (long-term memory):\n${it.json}\n\n" +
                                "IMPORTANT: Adapt your responses according to this profile. " +
                                "Respect the user's style, format, and language preferences. " +
                                "Honor any restrictions or dislikes. " +
                                "Do NOT mention the profile explicitly — just apply it naturally."
                    )
                )
            }
            working?.let {
                add(
                    ChatMessage(
                        "system",
                        "Current task context (working memory):\n${it.json}\n\n" +
                                "IMPORTANT: The user may have paused and returned. " +
                                "Continue from where you left off without repeating previous explanations."
                    )
                )
            }
            if (toolsSection.isNotBlank()) {
                add(ChatMessage("system", toolsSection))
            }
            if (ragContext.isNotBlank()) {
                add(
                    ChatMessage(
                        "system",
                        "ДОКУМЕНТНЫЙ КОНТЕКСТ (найден автоматически по запросу пользователя):\n\n" +
                                "$ragContext\n\n" +
                                "ПРАВИЛА ИСПОЛЬЗОВАНИЯ КОНТЕКСТА:\n" +
                                "1. Если контекст содержит ответ — используй его.\n" +
                                "2. ОБЯЗАТЕЛЬНО приведи 1-2 цитаты из контекста в кавычках «». " +
                                "Ответ без цитаты — это ОШИБКА.\n" +
                                "3. Пример формата ответа:\n" +
                                "   Стоимость составляет 100 рублей.\n" +
                                "   Цитата: «Стоимость проезда в одну сторону 100 рублей.»\n" +
                                "4. Если контекст не относится к вопросу — игнорируй его и отвечай как обычно, без цитат.\n" +
                                "5. Не выдумывай факты. Если не знаешь — скажи об этом."
                    )
                )
            }
            if (invariants.isNotEmpty()) {
                val numbered = invariants.mapIndexed { i, text -> "${i + 1}. $text" }
                add(
                    ChatMessage(
                        "system",
                        "ACTIVE INVARIANTS (hard constraints — never violate):\n" +
                                numbered.joinToString("\n") +
                                "\n\nIf the user's request conflicts with any of the above, " +
                                "refuse and explain which invariant (by number) would be violated."
                    )
                )
            }
            addAll(recent.map { it.toChatMessage() })
        }
    }

    private suspend fun checkInvariants(response: String, invariants: List<String>): String? {
        val numbered = invariants.mapIndexed { i, text -> "${i + 1}. $text" }
        val prompt = buildString {
            append("You are an invariant compliance checker.\n\n")
            append("INVARIANTS:\n${numbered.joinToString("\n")}\n\n")
            append("ASSISTANT RESPONSE:\n$response\n\n")
            append("IMPORTANT: Invariants apply ONLY to technical recommendations, solutions, ")
            append("and suggestions the assistant makes. They do NOT apply to:\n")
            append("- The natural language the assistant uses to communicate\n")
            append("- Greetings, clarifying questions, or general conversation\n")
            append("- Responses that don't contain any technical recommendation\n\n")
            append("Does the response RECOMMEND or PROPOSE something that violates an invariant?\n")
            append("Answer EXACTLY in this format:\n")
            append("First line: OK or VIOLATION\n")
            append("If VIOLATION — next line: which invariant number and a brief explanation.\n")
            append("Nothing else.")
        }

        val verdict = llmProvider().ask(listOf(ChatMessage("user", prompt)))
        val firstLine = verdict.lines().firstOrNull()?.trim()?.uppercase() ?: "OK"

        return if (firstLine.startsWith("VIOLATION")) {
            verdict.lines().drop(1).joinToString(" ").trim()
        } else null
    }

    private fun invariantViolationHint(violationDetail: String) = ChatMessage(
        "system",
        "YOUR PREVIOUS RESPONSE VIOLATED AN INVARIANT: $violationDetail\n" +
                "Regenerate your answer. If the user's request fundamentally conflicts " +
                "with the invariant, refuse politely and explain which invariant is violated."
    )

    companion object {
        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "arcee-ai/trinity-large-preview:free",
            "nvidia/nemotron-3-nano-30b-a3b:free"
        )
    }
}

data class AgentMetadata(
    val totalSpent: Int = 0,
    val workingMemory: String? = null,
    val longTermMemory: String? = null
)

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val isToolStep: Boolean = false
)
