package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "working_memory")
data class WorkingMemoryEntity(
    @PrimaryKey val userId: String,
    val json: String,
    val lastProcessedMessageId: Long = 0
)
