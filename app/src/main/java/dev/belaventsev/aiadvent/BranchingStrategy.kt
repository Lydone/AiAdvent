package dev.belaventsev.aiadvent

import dev.belaventsev.aiadvent.db.BranchDao
import dev.belaventsev.aiadvent.db.BranchEntity
import dev.belaventsev.aiadvent.db.ChatMessageEntity

class BranchingStrategy(
    private var currentBranchId: String,
    private val systemPrompt: String?,
    private val branchDao: BranchDao
) : ContextStrategy {

    override suspend fun buildMessages(history: List<ChatMessageEntity>): List<ChatMessage> {
        ensureMainBranch()

        val chain = buildBranchChain()
        val messages = mutableListOf<ChatMessageEntity>()

        for (i in chain.indices) {
            val branch = chain[i]
            val branchMessages = history.filter { it.branchId == branch.id }

            if (i < chain.lastIndex) {
                val nextBranch = chain[i + 1]
                messages.addAll(branchMessages.filter { it.id <= nextBranch.checkpointMessageId })
            } else {
                messages.addAll(branchMessages)
            }
        }

        return buildList {
            systemPrompt?.let { add(ChatMessage("system", it)) }
            addAll(messages.sortedBy { it.id }.map { it.toChatMessage() })
        }
    }

    override suspend fun reset() {
        branchDao.deleteAll()
        currentBranchId = "main"
    }

    fun getCurrentBranchId(): String = currentBranchId

    suspend fun createBranch(name: String, checkpointMessageId: Long): String {
        ensureMainBranch()
        val branchId = name.lowercase().replace(" ", "-")
        branchDao.upsert(
            BranchEntity(
                id = branchId,
                name = name,
                parentBranchId = currentBranchId,
                checkpointMessageId = checkpointMessageId
            )
        )
        currentBranchId = branchId
        return branchId
    }

    fun switchBranch(branchId: String) {
        currentBranchId = branchId
    }

    private suspend fun ensureMainBranch() {
        if (branchDao.getById("main") == null) {
            branchDao.upsert(BranchEntity(id = "main", name = "main"))
        }
    }

    /** Собирает цепочку веток от корня до текущей. */
    private suspend fun buildBranchChain(): List<BranchEntity> {
        val chain = mutableListOf<BranchEntity>()
        var id: String? = currentBranchId

        while (id != null) {
            val branch = branchDao.getById(id) ?: break
            chain.add(0, branch)
            id = branch.parentBranchId
        }

        return chain
    }
}
