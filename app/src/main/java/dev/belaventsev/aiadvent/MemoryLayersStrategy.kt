package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.LongTermMemoryDao
import dev.belaventsev.aiadvent.db.LongTermMemoryEntity
import dev.belaventsev.aiadvent.db.WorkingMemoryDao
import dev.belaventsev.aiadvent.db.WorkingMemoryEntity

class MemoryLayersStrategy(
    private val windowSize: Int,
    private val systemPrompt: String?,
    private val workingMemoryDao: WorkingMemoryDao,
    private val longTermMemoryDao: LongTermMemoryDao,
    private val llmCall: suspend (List<ChatMessage>) -> String
) {

    // --- Pipeline: query → updateMemories → collectInvariants → buildPrompt → validate → result ---

    suspend fun buildMessages(history: List<ChatMessageEntity>): List<ChatMessage> {
        updateWorkingMemory(history)
        updateLongTermMemory(history)
        val invariants = collectInvariants()
        val prompt = assemblePrompt(history, invariants)
        return validate(prompt)
    }

    suspend fun reset() {
        workingMemoryDao.deleteAll()
        // Long-term memory survives reset — intentionally NOT cleared
    }

    // --- Pipeline stages ---

    private suspend fun updateWorkingMemory(history: List<ChatMessageEntity>) {
        val current = workingMemoryDao.get()
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

        val result = llmCall(listOf(ChatMessage("user", prompt)))
        workingMemoryDao.upsert(
            WorkingMemoryEntity(json = result, lastProcessedMessageId = history.last().id)
        )
    }

    private suspend fun updateLongTermMemory(history: List<ChatMessageEntity>) {
        val current = longTermMemoryDao.get()
        val lastProcessedId = current?.lastProcessedMessageId ?: 0

        val unprocessed = history.filter { it.id > lastProcessedId && it.role == "user" }
        if (unprocessed.isEmpty()) return

        val prompt = buildString {
            append("You are a user IDENTITY extractor. Extract ONLY permanent traits about WHO the user is.\n\n")
            append("INCLUDE (stable facts that stay true across conversations):\n")
            append("- name, age, gender, location, language\n")
            append("- dietary restrictions, allergies, health conditions\n")
            append("- profession, expertise, education\n")
            append("- long-term preferences (cuisine, hobbies, communication style)\n")
            append("- family, pets, living situation\n\n")
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

        val result = llmCall(listOf(ChatMessage("user", prompt)))
        longTermMemoryDao.upsert(
            LongTermMemoryEntity(json = result, lastProcessedMessageId = history.last().id)
        )
    }

    private fun collectInvariants(): List<String> = emptyList()

    private suspend fun assemblePrompt(
        history: List<ChatMessageEntity>,
        invariants: List<String>
    ): List<ChatMessage> {
        val longTerm = longTermMemoryDao.get()
        val working = workingMemoryDao.get()
        val recent = history.takeLast(windowSize)

        return buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
            longTerm?.let {
                add(
                    ChatMessage(
                        "system",
                        "User profile (long-term memory):\n${it.json}"
                    )
                )
            }
            working?.let {
                add(
                    ChatMessage(
                        "system",
                        "Current task (working memory):\n${it.json}"
                    )
                )
            }
            if (invariants.isNotEmpty()) {
                add(ChatMessage("system", "Invariants:\n${invariants.joinToString("\n")}"))
            }
            addAll(recent.map { it.toChatMessage() })
        }
    }

    private fun validate(messages: List<ChatMessage>): List<ChatMessage> = messages
}
