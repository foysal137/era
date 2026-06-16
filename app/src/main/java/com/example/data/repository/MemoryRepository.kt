package com.example.data.repository

import com.example.data.database.MemoryDao
import com.example.data.database.MemoryEntry
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {
    val allMemories: Flow<List<MemoryEntry>> = memoryDao.getAllMemories()

    suspend fun getRecentMemories(limit: Int): List<MemoryEntry> {
        return memoryDao.getRecentMemories(limit)
    }

    suspend fun insertMemory(memory: MemoryEntry): Long {
        return memoryDao.insertMemory(memory)
    }

    suspend fun deleteMemory(id: Long) {
        memoryDao.deleteMemory(id)
    }

    suspend fun clearAll() {
        memoryDao.clearMemories()
    }
}
