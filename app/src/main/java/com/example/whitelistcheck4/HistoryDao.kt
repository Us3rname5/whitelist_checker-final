package com.example.whitelistcheck4

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 50")
    suspend fun getLast50(): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clearAll()
}
