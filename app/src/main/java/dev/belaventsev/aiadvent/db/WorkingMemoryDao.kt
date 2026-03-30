package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkingMemoryDao {

    @Query("SELECT * FROM working_memory WHERE id = 0")
    suspend fun get(): WorkingMemoryEntity?

    @Query("SELECT * FROM working_memory WHERE id = 0")
    fun observe(): Flow<WorkingMemoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: WorkingMemoryEntity)

    @Query("DELETE FROM working_memory")
    suspend fun deleteAll()
}
