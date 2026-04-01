package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val userId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
