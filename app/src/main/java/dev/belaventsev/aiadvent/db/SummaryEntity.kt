package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "summary")
data class SummaryEntity(
    @PrimaryKey val id: Int = 0,
    val text: String,
    val lastMessageId: Long,
    val timestamp: Long = System.currentTimeMillis()
)
