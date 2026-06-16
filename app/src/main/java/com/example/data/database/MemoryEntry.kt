package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val speaker: String,
    val text: String,
    val response: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMemoryHighlight: Boolean = false
)
