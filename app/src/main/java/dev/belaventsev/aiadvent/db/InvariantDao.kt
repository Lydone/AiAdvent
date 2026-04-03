package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InvariantDao {

    @Query("SELECT * FROM invariants WHERE userId = :userId ORDER BY createdAt ASC")
    fun observeAll(userId: String): Flow<List<InvariantEntity>>

    @Query("SELECT * FROM invariants WHERE userId = :userId ORDER BY createdAt ASC")
    suspend fun getAll(userId: String): List<InvariantEntity>

    @Insert
    suspend fun insert(invariant: InvariantEntity)

    @Query("DELETE FROM invariants WHERE id = :id")
    suspend fun deleteById(id: Long)
}
