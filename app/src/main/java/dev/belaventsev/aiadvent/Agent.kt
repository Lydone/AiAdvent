package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.BranchDao
import dev.belaventsev.aiadvent.db.BranchEntity
import dev.belaventsev.aiadvent.db.ChatMessageDao
import dev.belaventsev.aiadvent.db.ChatMessageEntity
import dev.belaventsev.aiadvent.db.FactsDao
import dev.belaventsev.aiadvent.db.SummaryDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class Agent(
    private val chatDao: ChatMessageDao,
    private val summaryDao: SummaryDao,
    private val factsDao: FactsDao,
    private val branchDao: BranchDao,
    private val systemPrompt: String? = null,
    private val model: String = MODELS[3],
    private val temperature: Double = 0.7
) {
    private var strategy: ContextStrategy = SlidingWindowStrategy(6, systemPrompt)

    private val _strategyType =
        MutableStateFlow<ContextStrategyType>(ContextStrategyType.SlidingWindow())
    private val _branchId = MutableStateFlow("main")

    val strategyType: StateFlow<ContextStrategyType> = _strategyType.asStateFlow()

    /** Сообщения для UI — реактивно учитывают ветку и стратегию. */
    val messages: Flow<List<MessageWithTokens>> = combine(
        chatDao.observeAll(),
        branchDao.observeAll(),
        _branchId,
        _strategyType
    ) { allMessages, allBranches, branchId, type ->
        if (type is ContextStrategyType.Branching) {
            filterBranchMessages(allMessages, allBranches, branchId)
        } else {
            allMessages.map { it.toMessageWithTokens() }
        }
    }

    /** Метаданные для UI — объединены в один Flow, чтобы не раздувать combine в ViewModel. */
    val metadata: Flow<AgentMetadata> = combine(
        chatDao.observeTotalSpent(),
        factsDao.observe().map { it?.json },
        branchDao.observeAll().map { list -> list.map { BranchInfo(it.id, it.name) } },
        _branchId,
        _strategyType
    ) { spent, facts, branches, branchId, type ->
        AgentMetadata(spent, facts, branches, branchId, type)
    }

    // --- Strategy ---

    suspend fun setStrategy(type: ContextStrategyType) {
        strategy.reset()
        chatDao.deleteAll()
        summaryDao.deleteAll()

        _strategyType.value = type
        _branchId.value = (type as? ContextStrategyType.Branching)?.currentBranchId ?: "main"

        strategy = when (type) {
            is ContextStrategyType.SlidingWindow ->
                SlidingWindowStrategy(type.windowSize, systemPrompt)

            is ContextStrategyType.StickyFacts ->
                StickyFactsStrategy(type.windowSize, systemPrompt, factsDao, ::callLlm)

            is ContextStrategyType.Branching ->
                BranchingStrategy(type.currentBranchId, systemPrompt, branchDao)
        }
    }

    // --- Chat ---

    suspend fun ask(query: String) {
        val branchId = _branchId.value
        chatDao.insert(ChatMessageEntity.fromChatMessage(ChatMessage("user", query), branchId))

        val apiMessages = strategy.buildMessages(chatDao.getAll())

        val response = retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, apiMessages, temperature)
            )
        }

        chatDao.insert(
            ChatMessageEntity.fromAssistantResponse(
                response.choices.first().message.content, response.usage, branchId
            )
        )
    }

    suspend fun reset() {
        strategy.reset()
        chatDao.deleteAll()
        summaryDao.deleteAll()
        _branchId.value = "main"
    }

    // --- Branching ---

    suspend fun createBranch(name: String) {
        val bs = strategy as? BranchingStrategy
            ?: error("Branching not active")
        val lastMsg = chatDao.getLastMessage()
            ?: error("No messages to branch from")
        val newId = bs.createBranch(name, lastMsg.id)
        _branchId.value = newId
    }

    fun switchBranch(branchId: String) {
        (strategy as? BranchingStrategy)?.switchBranch(branchId)
        _branchId.value = branchId
    }

    // --- Private ---

    private suspend fun callLlm(messages: List<ChatMessage>): String =
        retrying {
            OpenRouterClient.service.chat(
                auth = "Bearer ${BuildConfig.OPENROUTER_API_KEY}",
                request = ChatRequest(model, messages, 0.3)
            )
        }.choices.first().message.content

    /** Чистая функция: собирает цепочку веток и фильтрует сообщения. */
    private fun filterBranchMessages(
        allMessages: List<ChatMessageEntity>,
        allBranches: List<BranchEntity>,
        branchId: String
    ): List<MessageWithTokens> {
        val branchMap = allBranches.associateBy { it.id }

        val chain = buildList {
            var id: String? = branchId
            while (id != null) {
                val branch = branchMap[id] ?: break
                add(0, branch)
                id = branch.parentBranchId
            }
        }

        val result = mutableListOf<ChatMessageEntity>()
        for (i in chain.indices) {
            val branch = chain[i]
            val branchMsgs = allMessages.filter { it.branchId == branch.id }
            if (i < chain.lastIndex) {
                val checkpoint = chain[i + 1].checkpointMessageId
                result.addAll(branchMsgs.filter { it.id <= checkpoint })
            } else {
                result.addAll(branchMsgs)
            }
        }

        return result.sortedBy { it.id }.map { it.toMessageWithTokens() }
    }

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

/** Всё, что UI показывает кроме сообщений — собрано в одну структуру. */
data class AgentMetadata(
    val totalSpent: Int = 0,
    val facts: String? = null,
    val branches: List<BranchInfo> = emptyList(),
    val currentBranchId: String = "main",
    val strategyType: ContextStrategyType = ContextStrategyType.SlidingWindow()
)

data class MessageWithTokens(
    val message: ChatMessage,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

data class BranchInfo(val id: String, val name: String)
