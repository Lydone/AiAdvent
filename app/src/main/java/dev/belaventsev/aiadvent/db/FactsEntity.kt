package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "facts")
data class FactsEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val lastProcessedMessageId: Long = 0
)
