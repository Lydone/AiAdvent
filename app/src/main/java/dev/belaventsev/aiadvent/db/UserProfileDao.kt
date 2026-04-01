package dev.belaventsev.aiadvent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM user_profiles ORDER BY createdAt ASC")
    suspend fun getAll(): List<UserProfileEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profiles WHERE userId = :userId")
    suspend fun delete(userId: String)
}
