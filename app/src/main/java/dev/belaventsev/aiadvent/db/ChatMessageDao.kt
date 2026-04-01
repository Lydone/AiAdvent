package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages WHERE userId = :userId ORDER BY id ASC")
    fun observeAll(userId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE userId = :userId ORDER BY id ASC")
    suspend fun getAll(userId: String): List<ChatMessageEntity>

    @Query("SELECT COALESCE(SUM(totalTokens), 0) FROM chat_messages WHERE userId = :userId")
    fun observeTotalSpent(userId: String): Flow<Int>

    @Insert
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE userId = :userId")
    suspend fun deleteAll(userId: String)
}
