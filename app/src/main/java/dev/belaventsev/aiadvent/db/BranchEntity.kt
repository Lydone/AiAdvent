package dev.belaventsev.aiadvent.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "branches")
data class BranchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentBranchId: String? = null,
    val checkpointMessageId: Long = 0
)
