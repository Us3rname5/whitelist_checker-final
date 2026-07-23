package com.example.whitelistcheck4

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val isRestricted: Boolean,
    val statusesJson: String,
    val location: String?
)
