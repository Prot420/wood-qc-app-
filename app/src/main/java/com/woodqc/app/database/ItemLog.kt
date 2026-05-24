package com.woodqc.app.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "item_logs")
data class ItemLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,       // "Salad Bowl", "Cutting Board", "Spoon", "Plate", "Coaster"
    val woodType: String,       // "Mango", "Acacia", "Sheesham"
    val verdict: String,        // "PASS" or "REJECT"
    val defectType: String,     // "Crack", "Knot", "Fungal Mold", "None"
    val confidence: Float,      // Detection confidence score 0.0 - 1.0
    val timestamp: Long = System.currentTimeMillis(),

    // Phase 1: Path to the saved defect photo on device storage
    // Empty string "" means no photo was saved (PASS items)
    val photoPath: String = ""
)