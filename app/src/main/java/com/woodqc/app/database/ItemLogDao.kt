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

    @Query("SELECT * FROM item_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ItemLog>>

    @Query("SELECT COUNT(*) FROM item_logs WHERE verdict = 'PASS'")
    fun getPassCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM item_logs WHERE verdict = 'REJECT'")
    fun getRejectCount(): Flow<Int>

    @Query("DELETE FROM item_logs")
    suspend fun clearLogs()
}
