package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
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
    private val systemPrompt: String? = null,
    private val model: String = MODELS[3],
    private val temperature: Double = 0.7,
    private val windowSize: Int = 6
) {

    val messages: Flow<List<MessageWithTokens>> =
        chatDao.observeAll(userId).map { list -> list.map { it.toMessageWithTokens() } }

    val metadata: Flow<AgentMetadata> = combine(
        chatDao.observeTotalSpent(userId),
        workingMemoryDao.observe(userId).map { it?.json },
        longTermMemoryDao.observe(userId).map { it?.json }
    ) { spent, working, longTerm ->
        AgentMetadata(spent, working, longTerm)
    }

    // --- Pipeline: save → updateMemories → collectInvariants → assemblePrompt → validate → callLlm → save ---

    suspend fun ask(query: String) {
        chatDao.insert(ChatMessageEntity.fromChatMessage(userId, ChatMessage("user", query)))

        val history = chatDao.getAll(userId)

        updateWorkingMemory(history)
        updateLongTermMemory(history)

        val invariants = collectInvariants()
        val apiMessages = assemblePrompt(history, invariants)

        val response = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, apiMessages, temperature)
            )
        }

        val rawContent = response.choices.first().message.content
        val validatedContent = validate(rawContent)

        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(userId, validatedContent, response.usage)
        )
    }

    suspend fun reset() {
        workingMemoryDao.deleteAll(userId)
        chatDao.deleteAll(userId)
        // Long-term memory survives reset — intentionally NOT cleared
    }

    // --- Pipeline stages ---

    private suspend fun updateWorkingMemory(history: List<ChatMessageEntity>) {
        val current = workingMemoryDao.get(userId)
        val lastProcessedId = current?.lastProcessedMessageId ?: 0

        val unprocessed = history.filter { it.id > lastProcessedId && it.role == "user" }
        if (unprocessed.isEmpty()) return

        val prompt = buildString {
            append("You are a working memory extractor. Analyze the conversation and extract:\n")
            append("1. Current goal — what is the user trying to accomplish right now?\n")
            append("2. Current stage — where are they in the process?\n")
            append("3. Artifacts — any intermediate results (lists, plans, calculations).\n\n")
            if (current != null) {
                append("Previous working memory:\n${current.json}\n\n")
            }
            append("New user messages:\n")
            unprocessed.forEach { append("- ${it.content}\n") }
            append("\nOutput format (keep the user's language for values):\n")
            append("goal: ...\nstage: ...\nartifacts: ...\n\n")
            append("If there is no clear task, write \"goal: none\".\n")
            append("No explanations, no markdown — only the structured output above.")
        }

        val result = callLlm(listOf(ChatMessage("user", prompt)))
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

    private fun collectInvariants(): List<String> = emptyList()

    private suspend fun assemblePrompt(
        history: List<ChatMessageEntity>,
        invariants: List<String>
    ): List<ChatMessage> {
        val longTerm = longTermMemoryDao.get(userId)
        val working = workingMemoryDao.get(userId)
        val recent = history.takeLast(windowSize)

        return buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
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
                add(ChatMessage("system", "Current task (working memory):\n${it.json}"))
            }
            if (invariants.isNotEmpty()) {
                add(ChatMessage("system", "Invariants:\n${invariants.joinToString("\n")}"))
            }
            addAll(recent.map { it.toChatMessage() })
        }
    }

    private fun validate(response: String): String = response

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

        val MODELS = listOf(
            "google/gemma-3n-e2b-it:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "stepfun/step-3.5-flash:free",
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
    val totalTokens: Int = 0
)
