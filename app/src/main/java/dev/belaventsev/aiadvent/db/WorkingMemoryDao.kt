package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkingMemoryDao {

    @Query("SELECT * FROM working_memory WHERE userId = :userId")
    suspend fun get(userId: String): WorkingMemoryEntity?

    @Query("SELECT * FROM working_memory WHERE userId = :userId")
    fun observe(userId: String): Flow<WorkingMemoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: WorkingMemoryEntity)

    @Query("DELETE FROM working_memory WHERE userId = :userId")
    suspend fun deleteAll(userId: String)
}
