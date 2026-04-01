package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "long_term_memory")
data class LongTermMemoryEntity(
    @PrimaryKey val userId: String,
    val json: String,
    val lastProcessedMessageId: Long = 0
)
