package com.woodqc.app.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.woodqc.app.database.ItemLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {

    private const val PAGE_WIDTH = 595   // A4 width in points
    private const val PAGE_HEIGHT = 842  // A4 height in points
    private const val MARGIN = 40f
    private const val FOLDER = "WoodQC"

    fun exportToPdf(context: Context, logs: List<ItemLog>, isHindi: Boolean): String {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        drawReport(canvas, logs, isHindi)

        document.finishPage(page)

        // If logs are too many, add more pages
        if (logs.size > 25) {
            var pageNum = 2
            var startIndex = 25
            while (startIndex < logs.size) {
                val extraPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                val extraPage = document.startPage(extraPageInfo)
                drawLogsPage(extraPage.canvas, logs, startIndex, isHindi)
                document.finishPage(extraPage)
                startIndex += 35
                pageNum++
            }
        }

        val fileName = "WoodQC_Report_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }.pdf"

        return savePdf(context, document, fileName)
    }

    private fun drawReport(canvas: Canvas, logs: List<ItemLog>, isHindi: Boolean) {
        val totalPass   = logs.count { it.verdict == "PASS" }
        val totalReject = logs.count { it.verdict == "REJECT" }
        val yieldPct    = if (logs.isNotEmpty()) (totalPass * 100) / logs.size else 100
        val dateStr     = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())

        // ── Background ────────────────────────────────────────────────
        val bgPaint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

        // ── Header Bar ────────────────────────────────────────────────
        val headerPaint = Paint().apply { color = Color.parseColor("#0F0F14") }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 80f, headerPaint)

        val titlePaint = Paint().apply {
            color = Color.parseColor("#FFB300")
            textSize = 22f
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("🪵 WOOD QC INSPECTION REPORT", MARGIN, 42f, titlePaint)

        val subtitlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 11f
            isAntiAlias = true
        }
        canvas.drawText("Senses Lifestyle — Moradabad Export House", MARGIN, 62f, subtitlePaint)
        canvas.drawText(dateStr, PAGE_WIDTH - MARGIN - 160f, 62f, subtitlePaint)

        var yPos = 110f

        // ── Summary Cards ─────────────────────────────────────────────
        val labelPaint = Paint().apply {
            color = Color.parseColor("#666666")
            textSize = 10f
            isAntiAlias = true
        }
        val valuePaint = Paint().apply {
            color = Color.BLACK
            textSize = 20f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val cards = listOf(
            Triple(if (isHindi) "कुल स्कैन" else "TOTAL", logs.size.toString(), "#333333"),
            Triple(if (isHindi) "पास" else "PASS", totalPass.toString(), "#00C853"),
            Triple(if (isHindi) "रिजेक्ट" else "REJECT", totalReject.toString(), "#FF1744"),
            Triple(if (isHindi) "यील्ड" else "YIELD", "$yieldPct%", "#FFB300")
        )

        val cardWidth = (PAGE_WIDTH - MARGIN * 2 - 30f) / 4f
        cards.forEachIndexed { i, (label, value, color) ->
            val x = MARGIN + i * (cardWidth + 10f)
            val cardPaint = Paint().apply {
                this.color = Color.parseColor("#F5F5F5")
                isAntiAlias = true
            }
            val borderPaint = Paint().apply {
                this.color = Color.parseColor(color)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }
            canvas.drawRoundRect(RectF(x, yPos, x + cardWidth, yPos + 60f), 8f, 8f, cardPaint)
            canvas.drawRoundRect(RectF(x, yPos, x + cardWidth, yPos + 60f), 8f, 8f, borderPaint)

            labelPaint.color = Color.parseColor("#888888")
            canvas.drawText(label, x + 10f, yPos + 20f, labelPaint)
            valuePaint.color = Color.parseColor(color)
            canvas.drawText(value, x + 10f, yPos + 48f, valuePaint)
        }

        yPos += 80f

        // ── AQL Info ──────────────────────────────────────────────────
        val aqlPaint = Paint().apply {
            color = Color.parseColor("#1565C0")
            textSize = 10f
            isAntiAlias = true
        }
        canvas.drawText(
            if (isHindi) "मानक: ISO 2859-1 · स्तर II · AQL 2.5 | खरीदार: TJX, Kirkland, Stephen Joseph"
            else "Standard: ISO 2859-1 · Level II · AQL 2.5 | Buyers: TJX, Kirkland, Stephen Joseph",
            MARGIN, yPos, aqlPaint
        )

        yPos += 25f

        // ── Table Header ──────────────────────────────────────────────
        val tableHeaderPaint = Paint().apply { color = Color.parseColor("#0F0F14") }
        canvas.drawRect(MARGIN, yPos, PAGE_WIDTH - MARGIN, yPos + 25f, tableHeaderPaint)

        val tableHeaderTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val col1 = MARGIN + 5f
        val col2 = MARGIN + 130f
        val col3 = MARGIN + 260f
        val col4 = MARGIN + 340f
        val col5 = PAGE_WIDTH - MARGIN - 60f

        canvas.drawText(if (isHindi) "समय" else "TIME", col1, yPos + 17f, tableHeaderTextPaint)
        canvas.drawText(if (isHindi) "खराबी प्रकार" else "DEFECT TYPE", col2, yPos + 17f, tableHeaderTextPaint)
        canvas.drawText(if (isHindi) "विश्वास" else "CONFIDENCE", col3, yPos + 17f, tableHeaderTextPaint)
        canvas.drawText(if (isHindi) "फोटो" else "PHOTO", col4, yPos + 17f, tableHeaderTextPaint)
        canvas.drawText(if (isHindi) "परिणाम" else "VERDICT", col5, yPos + 17f, tableHeaderTextPaint)

        yPos += 30f

        // ── Table Rows (first 25) ─────────────────────────────────────
        drawLogsTable(canvas, logs.take(25), yPos, isHindi, col1, col2, col3, col4, col5)

        // ── Footer ────────────────────────────────────────────────────
        val footerPaint = Paint().apply {
            color = Color.parseColor("#AAAAAA")
            textSize = 9f
            isAntiAlias = true
        }
        canvas.drawText(
            "Generated by Wood QC Offline App — Senses Lifestyle Moradabad",
            MARGIN, PAGE_HEIGHT - 20f, footerPaint
        )
        if (logs.size > 25) {
            canvas.drawText("Page 1 of ${(logs.size / 35) + 1}", PAGE_WIDTH - 80f, PAGE_HEIGHT - 20f, footerPaint)
        }
    }

    private fun drawLogsPage(canvas: Canvas, logs: List<ItemLog>, startIndex: Int, isHindi: Boolean) {
        val bgPaint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

        val col1 = MARGIN + 5f
        val col2 = MARGIN + 130f
        val col3 = MARGIN + 260f
        val col4 = MARGIN + 340f
        val col5 = PAGE_WIDTH - MARGIN - 60f

        drawLogsTable(canvas, logs.drop(startIndex).take(35), 40f, isHindi, col1, col2, col3, col4, col5)

        val footerPaint = Paint().apply {
            color = Color.parseColor("#AAAAAA")
            textSize = 9f
            isAntiAlias = true
        }
        canvas.drawText(
            "Generated by Wood QC Offline App — Senses Lifestyle Moradabad",
            MARGIN, PAGE_HEIGHT - 20f, footerPaint
        )
    }

    private fun drawLogsTable(
        canvas: Canvas,
        logs: List<ItemLog>,
        startY: Float,
        isHindi: Boolean,
        col1: Float, col2: Float, col3: Float, col4: Float, col5: Float
    ) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val rowTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9f
            isAntiAlias = true
        }
        val passTextPaint = Paint().apply {
            color = Color.parseColor("#00C853")
            textSize = 9f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val rejectTextPaint = Paint().apply {
            color = Color.parseColor("#FF1744")
            textSize = 9f
            isFakeBoldText = true
            isAntiAlias = true
        }

        var yPos = startY
        logs.forEachIndexed { i, log ->
            val rowBg = Paint().apply {
                color = if (i % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE
            }
            canvas.drawRect(MARGIN, yPos - 12f, PAGE_WIDTH - MARGIN, yPos + 8f, rowBg)

            val timeStr = timeFormat.format(Date(log.timestamp))
            val defect = if (log.verdict == "PASS")
                (if (isHindi) "कोई खराबी नहीं" else "No Defect")
            else log.defectType
            val conf = if (log.verdict == "PASS") "100%" else "${(log.confidence * 100).toInt()}%"
            val photo = if (log.photoPath.isNotEmpty()) (if (isHindi) "हाँ" else "Yes") else (if (isHindi) "नहीं" else "No")
            val verdict = if (isHindi) {
                if (log.verdict == "PASS") "पास" else "रिजेक्ट"
            } else log.verdict

            canvas.drawText(timeStr, col1, yPos, rowTextPaint)
            canvas.drawText(defect, col2, yPos, rowTextPaint)
            canvas.drawText(conf, col3, yPos, rowTextPaint)
            canvas.drawText(photo, col4, yPos, rowTextPaint)

            val verdictPaint = if (log.verdict == "PASS") passTextPaint else rejectTextPaint
            canvas.drawText(verdict, col5, yPos, verdictPaint)

            yPos += 20f
        }
    }

    private fun savePdf(context: Context, document: PdfDocument, fileName: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                ) ?: return ""
                context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                uri.toString()
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                ).apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { document.writeTo(it) }
                file.absolutePath
            }
        } catch (e: Exception) {
            ""
        } finally {
            document.close()
        }
    }
}