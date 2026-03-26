package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    /** Суммарный расход токенов за весь диалог. */
    @Query("SELECT COALESCE(SUM(totalTokens), 0) FROM chat_messages")
    fun observeTotalSpent(): Flow<Int>

    @Insert
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
