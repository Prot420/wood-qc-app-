package com.woodqc.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ItemLog): Long

    // Live flow for UI — updates automatically as new logs come in
    @Query("SELECT * FROM item_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ItemLog>>

    // One-shot suspend for CSV export — no live updates needed
    @Query("SELECT * FROM item_logs ORDER BY timestamp DESC")
    suspend fun getAllLogsOnce(): List<ItemLog>

    // Stats
    @Query("SELECT COUNT(*) FROM item_logs WHERE verdict = 'PASS'")
    fun getPassCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM item_logs WHERE verdict = 'REJECT'")
    fun getRejectCount(): Flow<Int>

    // AQL: count results within a specific batch session (by timestamp range)
    @Query("SELECT COUNT(*) FROM item_logs WHERE verdict = 'REJECT' AND timestamp >= :sinceTimestamp")
    suspend fun getRejectCountSince(sinceTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM item_logs WHERE timestamp >= :sinceTimestamp")
    suspend fun getTotalCountSince(sinceTimestamp: Long): Int

    @Query("DELETE FROM item_logs")
    suspend fun clearLogs()
}