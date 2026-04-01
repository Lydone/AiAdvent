package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LongTermMemoryDao {

    @Query("SELECT * FROM long_term_memory WHERE userId = :userId")
    suspend fun get(userId: String): LongTermMemoryEntity?

    @Query("SELECT * FROM long_term_memory WHERE userId = :userId")
    fun observe(userId: String): Flow<LongTermMemoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: LongTermMemoryEntity)

    @Query("DELETE FROM long_term_memory WHERE userId = :userId")
    suspend fun deleteAll(userId: String)
}
