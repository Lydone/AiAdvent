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

    /** Last blocked transition for UI display, e.g. "planning → done" */
    private var lastBlockedTransition: String? = null

    // region System prompt

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
        |- planning: You MUST ONLY ask clarifying questions. Do NOT produce any result, 
        |  solution, plan, list, or answer. Your ENTIRE response must consist of 1-2 short 
        |  questions to understand what the user needs. Any work output in this phase is a mistake.
        |- execution: Do the work step by step. After completing a meaningful step, ask to confirm.
        |- validation: The user is reviewing the result. If they confirm — respond briefly
        |  (e.g. "Done!" or "Great, the task is complete."). Do NOT repeat the result.
        |  If they request changes — go back to execution.
        |- done / idle: Respond briefly. Offer help with a new task if appropriate.
    """.trimMargin()

    // endregion

    // region Public API (flows)

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
            taskPhase = working?.phase ?: "idle",
            transitionBlocked = lastBlockedTransition
        )
    }

    // endregion

    // region Pipeline

    suspend fun ask(query: String) {
        // 1. Save user message
        chatDao.insert(ChatMessageEntity.fromChatMessage(userId, ChatMessage("user", query)))

        // 2. PRE: determine phase from user's message (before bot responds)
        val historyBeforeResponse = chatDao.getAll(userId)
        val currentMemory = workingMemoryDao.get(userId)
        val previousPhase = TaskPhase.fromString(currentMemory?.phase ?: "idle")

        val prePhase = extractPrePhase(historyBeforeResponse, currentMemory)
        val validatedPrePhase = if (isValidTransition(previousPhase, prePhase)) {
            lastBlockedTransition = null
            prePhase
        } else {
            lastBlockedTransition = "${previousPhase.label} → ${prePhase.label}"
            previousPhase
        }

        // 3. Save PRE working memory (so assemblePrompt sees the correct phase)
        workingMemoryDao.upsert(
            WorkingMemoryEntity(
                userId = userId,
                phase = validatedPrePhase.label,
                json = currentMemory?.json ?: "",
                lastProcessedMessageId = historyBeforeResponse.last().id
            )
        )

        // 4. Assemble prompt (now with correct phase)
        val invariants = collectInvariants()
        val apiMessages = assemblePrompt(historyBeforeResponse, invariants)

        // 5. Call LLM
        var chatResponse = callChatLlm(apiMessages)

        // 6. Validate against invariants; retry once if violated
        var wasInvariantRefusal = false
        if (invariants.isNotEmpty()) {
            val violation =
                checkInvariants(chatResponse.choices.first().message.content, invariants)
            if (violation != null) {
                wasInvariantRefusal = true
                val correctedMessages = apiMessages + invariantViolationHint(violation)
                chatResponse = callChatLlm(correctedMessages)
            }
        }

        // 7. Save assistant response
        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(
                userId, chatResponse.choices.first().message.content, chatResponse.usage
            )
        )

        // 8. POST: analyze full exchange, may advance phase one step forward
        val fullHistory = chatDao.getAll(userId)
        val postResult = extractPostState(fullHistory, currentMemory, wasInvariantRefusal)

        val finalPhase = if (isOneStepForward(validatedPrePhase, postResult.phase)) {
            postResult.phase  // bot advanced the phase one step — accept
        } else {
            validatedPrePhase // keep PRE phase
        }

        workingMemoryDao.upsert(
            WorkingMemoryEntity(
                userId = userId,
                phase = finalPhase.label,
                json = postResult.description,
                lastProcessedMessageId = fullHistory.last().id
            )
        )

        // 9. Extract and save long-term memory
        updateLongTermMemory(fullHistory)
    }

    suspend fun reset() {
        workingMemoryDao.deleteAll(userId)
        chatDao.deleteAll(userId)
        lastBlockedTransition = null
    }

    // endregion

    // region PRE: phase extraction (user message → determines phase)

    /**
     * Analyze user's message to determine the new task phase.
     * Called BEFORE the bot responds.
     */
    private suspend fun extractPrePhase(
        history: List<ChatMessageEntity>,
        current: WorkingMemoryEntity?
    ): TaskPhase {
        val lastProcessedId = current?.lastProcessedMessageId ?: 0
        val unprocessed = history.filter { it.id > lastProcessedId }

        val prompt = buildPrePhasePrompt(current, unprocessed)
        val result = callLlm(listOf(ChatMessage("user", prompt)))
        return extractPhase(result)
    }

    private fun buildPrePhasePrompt(
        current: WorkingMemoryEntity?,
        unprocessed: List<ChatMessageEntity>
    ): String = buildString {
        append("You are a task phase detector. Based on the USER's latest message, ")
        append("determine which phase the task is in NOW.\n\n")

        append("PHASES — use EXACTLY one of these words:\n")
        append("- idle: no active task, user is just chatting\n")
        append("- planning: user asked for something new, or the assistant needs more info before starting work\n")
        append("- execution: user provided enough information for the assistant to start/continue working\n")
        append("- validation: user is reacting to a delivered result ")
        append("(approving, requesting changes, commenting on output)\n")
        append("- done: user explicitly confirmed the final result is accepted\n\n")

        append("TRANSITION RULES:\n")
        append("- idle → planning: user requests a new task\n")
        append("- planning → planning: user answered some questions but NOT enough to start work\n")
        append("- planning → execution: user provided enough information for the assistant to begin. ")
        append("ONLY move here when the assistant has all it needs to produce a result\n")
        append("- execution → validation: user REACTS to a delivered result ")
        append("(\"looks good\", \"change X\", \"not quite\")\n")
        append("- execution → execution: no result was delivered yet, work continues\n")
        append("- validation → done: user confirms the result is final ")
        append("(\"perfect\", \"да, всё супер\", \"не нужно больше\")\n")
        append("- validation → execution: user requests changes to the result\n")
        append("- any → idle: user explicitly abandons the task or starts a completely unrelated topic\n\n")

        if (current != null) {
            append("Current phase: ${current.phase}\n")
            append("Current state: ${current.json}\n\n")
        } else {
            append("Current phase: idle\n\n")
        }

        append("New messages:\n")
        unprocessed.forEach { append("- [${it.role}] ${it.content}\n") }

        append("\nRespond with ONLY one word: the phase.")
    }

    // endregion

    // region POST: state extraction (full exchange → phase + description)

    /**
     * Analyze the full exchange (user + assistant) to extract phase and description.
     * POST phase is only accepted if it's exactly one step forward from PRE phase.
     */
    private suspend fun extractPostState(
        history: List<ChatMessageEntity>,
        previousMemory: WorkingMemoryEntity?,
        wasInvariantRefusal: Boolean
    ): PostStateResult {
        val lastProcessedId = previousMemory?.lastProcessedMessageId ?: 0
        val unprocessed = history.filter { it.id > lastProcessedId }

        val prompt = buildPostPrompt(previousMemory, unprocessed, wasInvariantRefusal)
        val result = callLlm(listOf(ChatMessage("user", prompt)))

        val phase = extractPhase(result)
        val description = cleanStateDescription(result)
        return PostStateResult(phase, description)
    }

    private fun buildPostPrompt(
        current: WorkingMemoryEntity?,
        unprocessed: List<ChatMessageEntity>,
        wasInvariantRefusal: Boolean
    ): String = buildString {
        append("You are a task state analyzer. Analyze the FULL exchange ")
        append("(user message + assistant response) and determine the current state.\n\n")

        append("PHASES — use EXACTLY one of these words:\n")
        append("- idle: no active task\n")
        append("- planning: requirements are being clarified, work has not started\n")
        append("- execution: the assistant is doing or has just done the work, ")
        append("but the user has NOT yet reacted to the result\n")
        append("- validation: the assistant delivered a result AND is waiting for user's reaction. ")
        append("Key signal: the assistant completed the task and asked the user to review or confirm\n")
        append("- done: task is complete and confirmed by the user\n\n")

        append("KEY RULES:\n")
        append("- If the assistant COMPLETED the work and asked for confirmation in the SAME message ")
        append("→ this is validation (the ball is in the user's court)\n")
        append("- If the assistant only asked clarifying questions → planning\n")
        append("- If the assistant is mid-work or delivered partial results → execution\n")
        append("- done is ONLY when the user already confirmed → you almost never set this here\n\n")

        if (wasInvariantRefusal) {
            append("NOTE: The assistant REFUSED the user's request because it violated ")
            append("a hard constraint. Keep the previous phase as is.\n\n")
        }

        if (current != null) {
            append("Previous phase: ${current.phase}\n")
            append("Previous state: ${current.json}\n\n")
        }

        append("Latest messages:\n")
        unprocessed.forEach { append("- [${it.role}] ${it.content}\n") }

        append("\nRESPONSE FORMAT:\n")
        append("Line 1: phase word\n")
        append("Line 2: ---\n")
        append("Line 3+: concise description of current task state in the user's language (2-4 sentences)")
    }

    private data class PostStateResult(
        val phase: TaskPhase,
        val description: String
    )

    // endregion

    // region Phase parsing helpers

    /**
     * Extract phase from LLM response.
     * Tries regex at start of text first, then falls back to searching first line.
     */
    private fun extractPhase(result: String): TaskPhase {
        val phaseNames = TaskPhase.entries.joinToString("|") { it.label }
        val regex = Regex("^\\s*($phaseNames)", RegexOption.IGNORE_CASE)
        val match = regex.find(result)
        if (match != null) return TaskPhase.fromString(match.groupValues[1])

        // Fallback: search anywhere in first line
        val firstLine = result.lines().firstOrNull()?.lowercase() ?: ""
        return TaskPhase.entries.firstOrNull { it.label in firstLine } ?: TaskPhase.IDLE
    }

    /**
     * Remove phase word and --- separator from LLM response, leaving only the description.
     */
    private fun cleanStateDescription(result: String): String {
        val lines = result.lines()
        val separatorIndex = lines.indexOfFirst { it.trim() == "---" }

        return if (separatorIndex >= 0 && separatorIndex + 1 < lines.size) {
            lines.drop(separatorIndex + 1).joinToString("\n").trim()
        } else {
            val phaseNames = TaskPhase.entries.joinToString("|") { it.label }
            val regex = Regex("^\\s*($phaseNames)[:\\s]*", RegexOption.IGNORE_CASE)
            result.replaceFirst(regex, "").trim()
        }
    }

    // endregion

    // region Transition validation

    private fun isValidTransition(from: TaskPhase, to: TaskPhase): Boolean {
        if (from == to) return true
        val allowed = TaskPhase.transitions[from] ?: emptySet()
        return to in allowed
    }

    /**
     * Check if [to] is exactly one step forward from [from] in the natural order:
     * idle → planning → execution → validation → done
     *
     * Used to allow POST to advance the phase when the bot's response warrants it
     * (e.g. bot completed work in planning phase → advance to execution).
     */
    private fun isOneStepForward(from: TaskPhase, to: TaskPhase): Boolean {
        val forwardPath = listOf(
            TaskPhase.IDLE,
            TaskPhase.PLANNING,
            TaskPhase.EXECUTION,
            TaskPhase.VALIDATION,
            TaskPhase.DONE
        )
        val fromIndex = forwardPath.indexOf(from)
        val toIndex = forwardPath.indexOf(to)
        return toIndex == fromIndex + 1
    }

    // endregion

    // region Long-term memory extraction

    private suspend fun updateLongTermMemory(history: List<ChatMessageEntity>) {
        val current = longTermMemoryDao.get(userId)
        val lastProcessedId = current?.lastProcessedMessageId ?: 0

        val unprocessed = history.filter { it.id > lastProcessedId && it.role == "user" }
        if (unprocessed.isEmpty()) return

        val prompt = buildLongTermMemoryPrompt(current, unprocessed)
        val result = callLlm(listOf(ChatMessage("user", prompt)))

        longTermMemoryDao.upsert(
            LongTermMemoryEntity(
                userId = userId,
                json = result,
                lastProcessedMessageId = history.last().id
            )
        )
    }

    private fun buildLongTermMemoryPrompt(
        current: LongTermMemoryEntity?,
        unprocessed: List<ChatMessageEntity>
    ): String = buildString {
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

    // endregion

    // region Prompt assembly

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

    // endregion

    // region Invariant checking

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

    // endregion

    // region Infrastructure

    private suspend fun callChatLlm(messages: List<ChatMessage>): ChatResponse =
        retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, messages, temperature)
            )
        }

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

    // endregion
}

data class AgentMetadata(
    val totalSpent: Int = 0,
    val workingMemory: String? = null,
    val longTermMemory: String? = null,
    val taskPhase: String = "idle",
    val transitionBlocked: String? = null
)

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
