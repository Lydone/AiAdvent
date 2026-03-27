package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {

    @Query("SELECT * FROM summary WHERE id = 0")
    suspend fun get(): SummaryEntity?

    @Query("SELECT * FROM summary WHERE id = 0")
    fun observe(): Flow<SummaryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity)

    @Query("DELETE FROM summary")
    suspend fun deleteAll()
}
