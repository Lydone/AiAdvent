package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.FactsDao
import dev.belaventsev.aiadvent.db.FactsEntity

class StickyFactsStrategy(
    private val windowSize: Int,
    private val systemPrompt: String?,
    private val factsDao: FactsDao,
    private val llmCall: suspend (List<ChatMessage>) -> String
) : ContextStrategy {

    override suspend fun buildMessages(history: List<ChatMessageEntity>): List<ChatMessage> {
        updateFactsIfNeeded(history)

        val facts = factsDao.get()
        val recent = history.takeLast(windowSize)

        return buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
            facts?.let {
                add(ChatMessage("system", "Известные факты из диалога:\n${it.json}"))
            }
            addAll(recent.map { it.toChatMessage() })
        }
    }

    override suspend fun reset() {
        factsDao.deleteAll()
    }

    private suspend fun updateFactsIfNeeded(history: List<ChatMessageEntity>) {
        val currentFacts = factsDao.get()
        val lastProcessedId = currentFacts?.lastProcessedMessageId ?: 0

        val unprocessed = history.filter {
            it.id > lastProcessedId && it.role == "user"
        }
        if (unprocessed.isEmpty()) return

        val prompt = buildString {
            append("У тебя есть набор фактов из диалога в формате ключ: значение.\n")
            if (currentFacts != null) {
                append("Текущие факты:\n${currentFacts.json}\n\n")
            } else {
                append("Пока фактов нет.\n\n")
            }
            append("Новые сообщения пользователя:\n")
            unprocessed.forEach { append("- ${it.content}\n") }
            append("\nОбнови факты: добавь новые, измени существующие если пользователь передумал. ")
            append("Верни ТОЛЬКО обновлённый список в формате:\n")
            append("ключ: значение\nключ: значение\n")
            append("Без пояснений, без markdown, только факты.")
        }

        val updatedJson = llmCall(listOf(ChatMessage("user", prompt)))

        factsDao.upsert(
            FactsEntity(
                json = updatedJson,
                lastProcessedMessageId = history.last().id
            )
        )
    }
}
