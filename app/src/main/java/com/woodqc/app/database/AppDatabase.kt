package com.woodqc.app.database

import androidx.room.Database
import androidx.room.RoomDatabase

// CLEAN @Database class — no SQLCipher imports here
// KSP processes only this file for Room code generation
// SQLCipher setup is in DatabaseFactory.kt
@Database(
    entities = [ItemLog::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemLogDao(): ItemLogDao
}
