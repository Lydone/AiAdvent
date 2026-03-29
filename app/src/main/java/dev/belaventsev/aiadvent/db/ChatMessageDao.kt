package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE branchId = :branchId ORDER BY id ASC")
    fun observeByBranch(branchId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT COALESCE(SUM(totalTokens), 0) FROM chat_messages")
    fun observeTotalSpent(): Flow<Int>

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT 1")
    suspend fun getLastMessage(): ChatMessageEntity?

    @Insert
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
