package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "long_term_memory")
data class LongTermMemoryEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val lastProcessedMessageId: Long = 0
)
