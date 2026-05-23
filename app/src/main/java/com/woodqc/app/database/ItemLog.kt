package com.woodqc.app.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "item_logs")
data class ItemLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // e.g., "Salad Bowl", "Cutting Board", "Spoon", "Plate"
    val woodType: String, // e.g., "Mango", "Acacia", "Sheesham"
    val verdict: String,  // "PASS" or "REJECT"
    val defectType: String, // "Crack", "Knot", "Mold", "None"
    val confidence: Float,  // Detection confidence score
    val timestamp: Long = System.currentTimeMillis()
)
