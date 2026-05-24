package com.woodqc.app.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoSaver {

    private const val TAG = "PhotoSaver"
    private const val FOLDER_NAME = "WoodQC"

    /**
     * Saves a defect bitmap to the device's Pictures/WoodQC/ folder.
     *
     * Uses MediaStore API for Android 10+ (API 29+) — no storage permission needed.
     * Falls back to legacy File API for Android 8-9 (API 26-28) — needs
     * WRITE_EXTERNAL_STORAGE permission (declared in manifest with maxSdkVersion=28).
     *
     * @param context Application context
     * @param bitmap  The defect-annotated frozen bitmap
     * @param defectType e.g. "Crack", "Fungal Mold"
     * @return Absolute file path string if saved successfully, empty string on failure
     */
    fun saveDefectPhoto(context: Context, bitmap: Bitmap, defectType: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REJECT_${defectType.replace(" ", "_")}_$timestamp.jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, fileName)
            } else {
                saveViaLegacyFile(bitmap, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save defect photo: ${e.message}", e)
            ""
        }
    }

    // ── Android 10+ (API 29+): MediaStore scoped storage ─────────────────────
    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, fileName: String): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return ""

        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }

        // Mark file as complete (remove IS_PENDING flag)
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        // Return the real file path for DB storage
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                return cursor.getString(pathIndex) ?: uri.toString()
            }
        }
        return uri.toString()
    }

    // ── Android 8-9 (API 26-28): Legacy file system ───────────────────────────
    private fun saveViaLegacyFile(bitmap: Bitmap, fileName: String): String {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val woodQcDir = File(picturesDir, FOLDER_NAME).apply { mkdirs() }
        val file = File(woodQcDir, fileName)

        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }

        return file.absolutePath
    }
}