package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.InvariantDao
import dev.belaventsev.aiadvent.db.LongTermMemoryDao
import dev.belaventsev.aiadvent.db.LongTermMemoryEntity
import dev.belaventsev.aiadvent.db.WorkingMemoryDao
import dev.belaventsev.aiadvent.db.WorkingMemoryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class Agent(
    private val userId: String,
    private val chatDao: ChatMessageDao,
    private val workingMemoryDao: WorkingMemoryDao,
    private val longTermMemoryDao: LongTermMemoryDao,
    private val invariantDao: InvariantDao,
    private val model: String = DEFAULT_MODEL,
    private val temperature: Double = 0.7,
    private val windowSize: Int = 6
) {

    private val systemPrompt = """
        |You are a helpful assistant that works through tasks step by step.
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
        |
        |BEHAVIOR BY TASK PHASE:
        |- planning: Clarify what the user needs. Ask short, specific questions. Do not start working yet.
        |- execution: Do the work step by step. After completing a meaningful step, ask to confirm.
        |- validation: The user is reviewing the result. If they confirm — respond briefly
        |  (e.g. "Done!" or "Great, the task is complete."). Do NOT repeat the result.
        |  If they request changes — go back to execution.
        |- done / idle: Respond briefly. Offer help with a new task if appropriate.
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
            longTermMemory = longTerm,
            taskPhase = working?.phase ?: "idle"
        )
    }

    // --- Pipeline ---

    suspend fun ask(query: String) {
        // 1. Save user message
        chatDao.insert(ChatMessageEntity.fromChatMessage(userId, ChatMessage("user", query)))

        // 2. Assemble prompt (uses PREVIOUS working memory — not yet updated)
        val history = chatDao.getAll(userId)
        val invariants = collectInvariants()
        val apiMessages = assemblePrompt(history, invariants)

        // 3. Call LLM
        var chatResponse = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, apiMessages, temperature)
            )
        }

        // 4. Validate against invariants; retry once if violated
        var wasInvariantRefusal = false
        if (invariants.isNotEmpty()) {
            val violation =
                checkInvariants(chatResponse.choices.first().message.content, invariants)
            if (violation != null) {
                wasInvariantRefusal = true
                val correctedMessages = apiMessages + invariantViolationHint(violation)
                chatResponse = retrying {
                    OpenRouterClient.service.chat(
                        auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                        request = ChatRequest(model, correctedMessages, temperature)
                    )
                }
            }
        }

        // 5. Save assistant response
        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(
                userId, chatResponse.choices.first().message.content, chatResponse.usage
            )
        )

        // 6. Extract working memory (analyzes FULL exchange: user message + assistant response)
        val fullHistory = chatDao.getAll(userId)
        val current = workingMemoryDao.get(userId)
        val previousPhase = TaskPhase.fromString(current?.phase ?: "idle")
        val extracted =
            extractWorkingMemory(fullHistory, current, wasInvariantRefusal = wasInvariantRefusal)

        // 7. Validate task state transition; retry once if invalid, then advance one step
        val validatedMemory = if (isValidTransition(previousPhase, extracted)) {
            extracted
        } else {
            val retried = extractWorkingMemory(
                fullHistory, current, invalidTransitionHint(previousPhase, extracted),
                wasInvariantRefusal = wasInvariantRefusal
            )
            if (isValidTransition(previousPhase, retried)) {
                retried
            } else {
                // Can't jump multiple phases — advance one step toward the target
                val nextPhase = advanceOneStep(previousPhase, TaskPhase.fromString(extracted.phase))
                val fixedJson = extracted.json.replaceFirst(
                    extracted.json.lines().first(),
                    nextPhase.label
                )
                extracted.copy(phase = nextPhase.label, json = fixedJson)
            }
        }

        // 8. Save working memory
        workingMemoryDao.upsert(validatedMemory)

        // 9. Extract and save long-term memory
        updateLongTermMemory(fullHistory)
    }

    suspend fun reset() {
        workingMemoryDao.deleteAll(userId)
        chatDao.deleteAll(userId)
    }

    // --- Pipeline stages ---

    /**
     * Extract working memory via LLM. Returns entity without saving.
     * Optional hint is appended to the prompt (used for retry with error context).
     */
    private suspend fun extractWorkingMemory(
        history: List<ChatMessageEntity>,
        current: WorkingMemoryEntity?,
        hint: String? = null,
        wasInvariantRefusal: Boolean = false
    ): WorkingMemoryEntity {
        val lastProcessedId = current?.lastProcessedMessageId ?: 0
        val unprocessed = history.filter { it.id > lastProcessedId }

        val prompt = buildString {
            append("You are a task state analyzer.\n\n")
            append("PHASES (use ONLY one of these words): idle, planning, execution, validation, done\n\n")
            if (wasInvariantRefusal) {
                append("NOTE: In the latest exchange, the assistant REFUSED the user's request ")
                append("because it violated a hard constraint (invariant). ")
                append("This refusal does NOT change the task phase — keep the previous phase as is.\n\n")
            }
            if (current != null) {
                append("Previous state (phase: ${current.phase}):\n${current.json}\n\n")
            }
            append("New messages:\n")
            unprocessed.forEach { append("- [${it.role}] ${it.content}\n") }
            append("\nRESPOND IN EXACTLY THIS FORMAT:\n")
            append("First line: only ONE word — the phase (idle, planning, execution, validation, or done).\n")
            append("Next lines: free-form description of the current task state in the user's language ")
            append("(what is being done, what step we are on, what is expected from the user, ")
            append("any intermediate results or artifacts).\n\n")
            append("If there is no active task, write:\nidle\nNo active task.")
            hint?.let { append("\n\n$it") }
        }

        val result = callLlm(listOf(ChatMessage("user", prompt)))
        val phase = extractPhase(result)

        return WorkingMemoryEntity(
            userId = userId,
            phase = phase.label,
            json = result,
            lastProcessedMessageId = history.last().id
        )
    }

    private fun isValidTransition(
        previousPhase: TaskPhase,
        extracted: WorkingMemoryEntity
    ): Boolean {
        val newPhase = TaskPhase.fromString(extracted.phase)
        return validateTransition(previousPhase, newPhase) != null
    }

    private fun invalidTransitionHint(
        previousPhase: TaskPhase,
        extracted: WorkingMemoryEntity
    ): String {
        val newPhase = TaskPhase.fromString(extracted.phase)
        val allowed = TaskPhase.transitions[previousPhase]?.joinToString { it.label } ?: "idle"
        return "CORRECTION NEEDED: transition ${previousPhase.label} → ${newPhase.label} is not allowed. " +
                "Allowed from ${previousPhase.label}: $allowed (or stay at ${previousPhase.label})."
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
            append("\nUpdate the profile: add new identity facts, modify changed ones (e.g. moved to a new city). ")
            append("Remove ANYTHING that is a task, request, or temporary plan. ")
            append("Keep the user's language for values. ")
            append("Output ONLY the updated profile in format:\n")
            append("key: value\nkey: value\n\n")
            append("No explanations, no markdown — only key-value pairs.")
        }

        val result = callLlm(listOf(ChatMessage("user", prompt)))
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

    private suspend fun assemblePrompt(
        history: List<ChatMessageEntity>,
        invariants: List<String>
    ): List<ChatMessage> {
        val longTerm = longTermMemoryDao.get(userId)
        val working = workingMemoryDao.get(userId)
        val recent = history.takeLast(windowSize)

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
                if (it.phase != "idle") {
                    add(
                        ChatMessage(
                            "system",
                            "Current task state (phase: ${it.phase}):\n${it.json}\n\n" +
                                    "IMPORTANT: The user may have paused and returned. " +
                                    "Continue from where you left off without repeating previous explanations."
                        )
                    )
                }
            }
            // If no active task, remind the model to clarify before executing
            if (working == null || working.phase == "idle") {
                add(
                    ChatMessage(
                        "system",
                        "You have no active task. If the user asks you to do something, " +
                                "first clarify their requirements with 1-2 short questions. " +
                                "Do NOT jump straight to a solution."
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

    /**
     * Check if assistant response violates any invariant.
     * Returns violation description or null if OK.
     */
    private suspend fun checkInvariants(
        response: String,
        invariants: List<String>
    ): String? {
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

        val verdict = callLlm(listOf(ChatMessage("user", prompt)))
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

    // --- Helpers ---

    /**
     * Extract phase from the first line of LLM response.
     * First line should be one word: idle, planning, execution, validation, or done.
     */
    private fun extractPhase(result: String): TaskPhase {
        val firstLine = result.lines().firstOrNull()?.trim()?.lowercase() ?: "idle"
        return TaskPhase.fromString(firstLine)
    }

    private fun validateTransition(from: TaskPhase, to: TaskPhase): TaskPhase? {
        if (from == to) return to
        val allowed = TaskPhase.transitions[from] ?: emptySet()
        return if (to in allowed) to else null
    }

    /**
     * When LLM wants to jump multiple phases (e.g. idle→done),
     * advance just one step forward along the path: idle→planning→execution→validation→done.
     * If target is backward or same, stay at current.
     */
    private fun advanceOneStep(from: TaskPhase, target: TaskPhase): TaskPhase {
        val path = listOf(
            TaskPhase.IDLE,
            TaskPhase.PLANNING,
            TaskPhase.EXECUTION,
            TaskPhase.VALIDATION,
            TaskPhase.DONE
        )
        val fromIndex = path.indexOf(from)
        val targetIndex = path.indexOf(target)

        return if (targetIndex > fromIndex) {
            path[fromIndex + 1]
        } else {
            from // stay at current phase if target is backward or same
        }
    }

    // --- Infrastructure ---

    private suspend fun callLlm(messages: List<ChatMessage>): String =
        retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, messages, 0.3)
            )
        }.choices.first().message.content

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastException!!
    }

    companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L
        const val DEFAULT_MODEL = "nvidia/nemotron-3-nano-30b-a3b:free"

        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "stepfun/step-3.5-flash:free",
            DEFAULT_MODEL
        )
    }
}

data class AgentMetadata(
    val totalSpent: Int = 0,
    val workingMemory: String? = null,
    val longTermMemory: String? = null,
    val taskPhase: String = "idle"
)

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
