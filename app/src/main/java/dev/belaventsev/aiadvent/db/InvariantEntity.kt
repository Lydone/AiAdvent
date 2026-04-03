package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invariants")
data class InvariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)
