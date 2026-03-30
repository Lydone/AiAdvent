package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "working_memory")
data class WorkingMemoryEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val lastProcessedMessageId: Long = 0
)
