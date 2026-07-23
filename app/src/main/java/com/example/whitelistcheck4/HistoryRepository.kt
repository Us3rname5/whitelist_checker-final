package com.example.whitelistcheck4

import android.content.Context

class HistoryRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.historyDao()

    suspend fun saveCheck(isRestricted: Boolean, statuses: List<ServiceStatus>, location: String?) {
        val json = statuses.joinToString("|") { "${it.name}:${it.isAccessible}" }
        val entry = HistoryEntity(
            isRestricted = isRestricted,
            statusesJson = json,
            location = location
        )
        dao.insert(entry)
    }

    suspend fun getHistory(): List<HistoryEntity> = dao.getLast50()

    suspend fun clearHistory() = dao.clearAll()
}
