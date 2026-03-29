package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FactsDao {

    @Query("SELECT * FROM facts WHERE id = 0")
    suspend fun get(): FactsEntity?

    @Query("SELECT * FROM facts WHERE id = 0")
    fun observe(): Flow<FactsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(facts: FactsEntity)

    @Query("DELETE FROM facts")
    suspend fun deleteAll()
}
