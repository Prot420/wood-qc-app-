package com.woodqc.app.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.woodqc.app.database.ItemLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private const val TAG = "CsvExporter"
    private const val FOLDER_NAME = "WoodQC"

    /**
     * Exports a list of inspection logs to a CSV file in Downloads/WoodQC/.
     *
     * CSV format (TJX/Kirkland buyer compatible):
     * ID, Date, Time, Category, Wood Type, Verdict, Defect Type, Confidence (%), Photo Path
     *
     * @return File path of exported CSV, or empty string on failure
     */
    fun exportToCsv(context: Context, logs: List<ItemLog>): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "WoodQC_Report_$timestamp.csv"
        val csvContent = buildCsvContent(logs)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, csvContent, fileName)
            } else {
                saveViaLegacyFile(csvContent, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CSV export failed: ${e.message}", e)
            ""
        }
    }

    private fun buildCsvContent(logs: List<ItemLog>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val sb = StringBuilder()

        // Header row
        sb.appendLine("ID,Date,Time,Category,Wood Type,Verdict,Defect Type,Confidence (%),Photo Saved")

        // Data rows
        logs.forEach { log ->
            val date = dateFormat.format(Date(log.timestamp))
            val time = timeFormat.format(Date(log.timestamp))
            val confidence = if (log.verdict == "PASS") "100" else "${(log.confidence * 100).toInt()}"
            val photoSaved = if (log.photoPath.isNotEmpty()) "YES" else "NO"

            // Escape commas in text fields
            sb.appendLine(
                "${log.id},$date,$time," +
                "\"${log.category}\",\"${log.woodType}\"," +
                "${log.verdict},\"${log.defectType}\"," +
                "$confidence,$photoSaved"
            )
        }

        // Summary section
        val totalPass = logs.count { it.verdict == "PASS" }
        val totalReject = logs.count { it.verdict == "REJECT" }
        val yieldPct = if (logs.isNotEmpty()) (totalPass * 100) / logs.size else 100

        sb.appendLine()
        sb.appendLine("SUMMARY")
        sb.appendLine("Total Inspected,${logs.size}")
        sb.appendLine("Total Pass,$totalPass")
        sb.appendLine("Total Reject,$totalReject")
        sb.appendLine("Yield %,$yieldPct%")
        sb.appendLine("Report Generated,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("Factory,Senses Lifestyle - Moradabad")

        return sb.toString()
    }

    private fun saveViaMediaStore(context: Context, content: String, fileName: String): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME")
        }

        val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return ""
        } else {
            return saveViaLegacyFile(content, fileName)
        }

        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }

        return uri.toString()
    }

    private fun saveViaLegacyFile(content: String, fileName: String): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val woodQcDir = File(downloadsDir, FOLDER_NAME).apply { mkdirs() }
        val file = File(woodQcDir, fileName)
        FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return file.absolutePath
    }
}