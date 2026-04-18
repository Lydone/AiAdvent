package dev.belaventsev.aiadvent

/**
 * Retrieval-Augmented Generation engine with optional query rewriting
 * and LLM-based reranking.
 */
class RagEngine(
    private val llm: LlmClient = LlmClient(),
    private val mcpClient: McpClientWrapper = McpClientWrapper(),
    private val initialTopK: Int = 7,      // wider retrieval for reranking
    private val finalTopK: Int = 3,        // how many chunks go into LLM context
    private val coarseThreshold: Double = 0.30, // pre-rerank filter
    private val fineThreshold: Double = 0.50    // post-rerank filter (0..1 normalized)
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
        val diagnostics: RagDiagnostics = RagDiagnostics()
    )

    /** Pipeline stage details for diagnostics and UI display. */
    data class RagDiagnostics(
        val originalQuery: String = "",
        val rewrittenQuery: String? = null,
        val initialChunks: List<ChunkInfo> = emptyList(),
        val afterCoarseFilter: List<ChunkInfo> = emptyList(),
        val afterReranking: List<ChunkInfo> = emptyList(),
        val afterFineFilter: List<ChunkInfo> = emptyList()
    )

    data class ChunkInfo(
        val source: String,
        val textPreview: String,
        val embeddingScore: Double,
        val rerankScore: Double? = null
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
        return RagResponse(
            answer = answer,
            diagnostics = RagDiagnostics(originalQuery = question)
        )
    }

    /**
     * Full RAG pipeline with optional query rewriting and reranking.
     */
    suspend fun askWithRag(
        question: String,
        rewriteEnabled: Boolean = false,
        rerankEnabled: Boolean = false
    ): RagResponse {
        ensureToolsInitialized()

        // === Stage 1: Query rewrite ===
        val rewrittenQuery = if (rewriteEnabled) {
            try {
                rewriteQuery(question)
            } catch (e: Exception) {
                null
            }
        } else null

        val searchQuery = rewrittenQuery ?: question

        // === Stage 2: Initial retrieval ===
        val topK = if (rerankEnabled) initialTopK else finalTopK
        val searchResult = try {
            mcpClient.callTool(
                name = "search_documents",
                arguments = mapOf(
                    "query" to searchQuery,
                    "strategy" to "all",
                    "top_k" to topK.toString()
                )
            )
        } catch (e: Exception) {
            return RagResponse(
                answer = "Ошибка RAG-поиска: ${e.message}",
                diagnostics = RagDiagnostics(
                    originalQuery = question,
                    rewrittenQuery = rewrittenQuery
                )
            )
        }

        val initialChunks = parseSearchResult(searchResult)
        if (initialChunks.isEmpty()) {
            return RagResponse(
                answer = "Не могу ответить — в документах ничего не найдено по запросу.",
                diagnostics = RagDiagnostics(
                    originalQuery = question,
                    rewrittenQuery = rewrittenQuery
                )
            )
        }

        // === Stage 3: Coarse filter by embedding score ===
        val afterCoarse = initialChunks.filter { it.embeddingScore >= coarseThreshold }
        if (afterCoarse.isEmpty()) {
            return RagResponse(
                answer = "Не могу ответить на основе предоставленных документов — релевантных фрагментов не найдено.",
                diagnostics = RagDiagnostics(
                    originalQuery = question,
                    rewrittenQuery = rewrittenQuery,
                    initialChunks = initialChunks
                )
            )
        }

        // === Stage 4: Reranking (optional) ===
        val afterRerank = if (rerankEnabled) {
            rerankChunks(question, afterCoarse).sortedByDescending { it.rerankScore ?: 0.0 }
        } else {
            afterCoarse
        }

        // === Stage 5: Fine filter by rerank score ===
        val afterFine = if (rerankEnabled) {
            afterRerank.filter { (it.rerankScore ?: 0.0) >= fineThreshold }
        } else {
            afterRerank
        }

        val finalChunks = afterFine.take(finalTopK)

        if (finalChunks.isEmpty()) {
            return RagResponse(
                answer = "Не могу ответить на основе предоставленных документов — после фильтрации не осталось релевантных фрагментов.",
                diagnostics = RagDiagnostics(
                    originalQuery = question,
                    rewrittenQuery = rewrittenQuery,
                    initialChunks = initialChunks,
                    afterCoarseFilter = afterCoarse,
                    afterReranking = if (rerankEnabled) afterRerank else emptyList(),
                    afterFineFilter = afterFine
                )
            )
        }

        // === Stage 6: Answer generation ===
        val context = buildContextFromChunks(finalChunks)
        val systemPrompt = """
            |Ты отвечаешь на вопрос пользователя, используя ТОЛЬКО информацию из контекста ниже.
            |Если в контексте нет ответа — скажи "Не могу ответить на основе предоставленных документов".
            |Не выдумывай и не добавляй факты из своих знаний.
            |Отвечай на языке пользователя. Кратко и по делу.
            |
            |КОНТЕКСТ:
            |$context
        """.trimMargin()

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", question)
        )
        val answer = llm.ask(messages, temperature = 0.2)

        return RagResponse(
            answer = answer,
            sources = finalChunks.map { it.source }.distinct(),
            diagnostics = RagDiagnostics(
                originalQuery = question,
                rewrittenQuery = rewrittenQuery,
                initialChunks = initialChunks,
                afterCoarseFilter = afterCoarse,
                afterReranking = if (rerankEnabled) afterRerank else emptyList(),
                afterFineFilter = finalChunks
            )
        )
    }

    // --- Pipeline stages ---

    private suspend fun rewriteQuery(question: String): String {
        val prompt = """
            |Переформулируй вопрос пользователя так, чтобы он лучше подходил для поиска по документам.
            |
            |ПРАВИЛА:
            |- Сохрани исходный смысл вопроса
            |- Добавь конкретные ключевые термины, которые могут встречаться в документах
            |- Раскрой короткие запросы — добавь контекст
            |- НЕ меняй язык вопроса
            |- НЕ добавляй своих предположений об ответе
            |
            |Выведи ТОЛЬКО переформулированный запрос, без пояснений и кавычек.
            |
            |Вопрос: $question
        """.trimMargin()

        val result = llm.ask(listOf(ChatMessage("user", prompt)), temperature = 0.2).trim()
        return if (result.length in 5..500) result else question
    }

    /**
     * Batch rerank: send all chunks in a single LLM call.
     * LLM returns one score per line, parsed by position.
     */
    private suspend fun rerankChunks(
        question: String,
        chunks: List<ChunkInfo>
    ): List<ChunkInfo> {
        val numberedChunks = chunks.mapIndexed { i, c ->
            "[${i + 1}] ${c.textPreview.take(150)}"
        }.joinToString("\n\n")

        val prompt = """
            |Оцени, насколько каждый фрагмент отвечает на вопрос пользователя.
            |
            |ШКАЛА: 0 — нерелевантно, 5 — частично, 10 — прямой полный ответ.
            |
            |Вопрос: $question
            |
            |Фрагменты:
            |$numberedChunks
            |
            |Выведи ТОЛЬКО числа через запятую, по одному на каждый фрагмент.
            |Пример для 4 фрагментов: 8, 3, 10, 1
            |Никаких объяснений, только числа.
        """.trimMargin()

        val response = try {
            llm.ask(listOf(ChatMessage("user", prompt)), temperature = 0.1).trim()
        } catch (_: Exception) {
            return chunks // fallback: keep original order
        }

        println("[Reranker] LLM response: $response")

        // Parse comma-separated scores
        val scores = Regex("\\d+").findAll(response)
            .map { it.value.toIntOrNull()?.coerceIn(0, 10) ?: 5 }
            .toList()

        // If parsing failed or all scores are 0, fall back to embedding scores
        if (scores.isEmpty() || scores.all { it == 0 }) {
            println("[Reranker] Fallback: scores empty or all zero")
            return chunks.map { it.copy(rerankScore = it.embeddingScore) }
        }

        return chunks.mapIndexed { i, chunk ->
            val score = if (i < scores.size) scores[i] / 10.0 else chunk.embeddingScore
            chunk.copy(rerankScore = score)
        }
    }

    private fun parseSearchResult(searchResult: String): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        val entries = searchResult.split(Regex("\\n(?=\\d+\\. \\[score:)"))
        for (entry in entries) {
            val scoreMatch = Regex("\\[score: ([\\d.,]+)\\]").find(entry) ?: continue
            val score = scoreMatch.groupValues[1].replace(",", ".").toDoubleOrNull() ?: continue

            val sourceMatch = Regex("Источник: (.+)").find(entry)
            val source = sourceMatch?.groupValues?.get(1)?.trim() ?: "неизвестно"

            val textMatch = Regex("Текст: ([\\s\\S]+?)(?=\\n\\s*\\d+\\. \\[score|\\Z)").find(entry)
            val text = textMatch?.groupValues?.get(1)?.trim() ?: ""

            chunks.add(ChunkInfo(source = source, textPreview = text, embeddingScore = score))
        }
        return chunks
    }

    private fun buildContextFromChunks(chunks: List<ChunkInfo>): String = buildString {
        chunks.forEach { chunk ->
            appendLine("[Источник: ${chunk.source}]")
            appendLine(chunk.textPreview)
            appendLine()
        }
    }
}
