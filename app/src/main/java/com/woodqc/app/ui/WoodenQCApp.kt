package com.woodqc.app.ui

import android.content.Context
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.woodqc.app.camera.CameraAnalyzer
import com.woodqc.app.database.DatabaseFactory
import com.woodqc.app.database.ItemLog
import com.woodqc.app.utils.AqlCalculator
import com.woodqc.app.utils.CsvExporter
import com.woodqc.app.utils.PdfExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Colors ────────────────────────────────────────────────────────────────
private val DarkBg      = Color(0xFF0F0F14)
private val CardBg      = Color(0xFF181822)
private val BorderColor = Color(0xFF2C2C3C)
private val Yellow      = Color(0xFFFFB300)
private val Green       = Color(0xFF00E676)
private val Red         = Color(0xFFFF1744)
private val Blue        = Color(0xFF2979FF)
private val Orange      = Color(0xFFFF6D00)
private val Purple      = Color(0xFFAA00FF)

private fun t(h: Boolean, en: String, hi: String) = if (h) hi else en

@Composable
fun WoodenQCApp(
    analyzerState: CameraAnalyzer.AnalyzerState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onResetToIdle: () -> Unit,
    onPreviewViewCreated: (PreviewView) -> Unit,
    isHindi: Boolean,
    onLanguageToggle: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { DatabaseFactory.getDatabase(context) }
    val scope = rememberCoroutineScope()

    val logs by db.itemLogDao().getAllLogs().collectAsState(initial = emptyList())
    val passCount by db.itemLogDao().getPassCount().collectAsState(initial = 0)
    val rejectCount by db.itemLogDao().getRejectCount().collectAsState(initial = 0)
    val total = passCount + rejectCount
    val yieldPct = if (total > 0) (passCount * 100) / total else 100

    var activeTab by remember { mutableIntStateOf(0) }
    var showAqlSetup by remember { mutableStateOf(false) }
    var aqlPlan by remember { mutableStateOf<AqlCalculator.AqlPlan?>(null) }
    var aqlBatchStartTime by remember { mutableStateOf(0L) }

    if (showAqlSetup) {
        AqlSetupDialog(
            isHindi = isHindi,
            onConfirm = { size ->
                aqlPlan = AqlCalculator.getPlan(size)
                aqlBatchStartTime = System.currentTimeMillis()
                showAqlSetup = false
                activeTab = 1
            },
            onDismiss = { showAqlSetup = false }
        )
    }

    Scaffold(
        containerColor = DarkBg,
        bottomBar = {
            // Hide bottom nav during result screen
            if (analyzerState !is CameraAnalyzer.AnalyzerState.Result) {
                NavigationBar(containerColor = CardBg, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        icon = { Icon(Icons.Default.CameraAlt, null) },
                        label = { Text(t(isHindi, "Scan", "स्कैन")) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Yellow, selectedTextColor = Yellow,
                            indicatorColor = Yellow.copy(0.15f),
                            unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { if (aqlPlan == null) showAqlSetup = true else activeTab = 1 },
                        icon = { Icon(Icons.Default.Assignment, null) },
                        label = { Text("AQL") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Blue, selectedTextColor = Blue,
                            indicatorColor = Blue.copy(0.15f),
                            unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        icon = { Icon(Icons.Default.List, null) },
                        label = { Text(t(isHindi, "Logs", "लॉग्स")) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Green, selectedTextColor = Green,
                            indicatorColor = Green.copy(0.15f),
                            unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
                        )
                    )
                }
            }
        }
    ) { paddingValues ->

        // ── Result screen takes full screen ───────────────────────────────
        if (analyzerState is CameraAnalyzer.AnalyzerState.Result) {
            ResultScreen(
                result = analyzerState,
                isHindi = isHindi,
                onConfirmDecision = { _ -> onResetToIdle() },
                onScanNext = { onResetToIdle() }
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // ── Header ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(t(isHindi, "FACTORY QC", "फैक्टरी QC"), color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("Senses Lifestyle", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetricChip(t(isHindi, "कुल", "TOTAL"), total.toString(), Color.White)
                    MetricChip(t(isHindi, "पास", "PASS"), passCount.toString(), Green)
                    MetricChip(t(isHindi, "रिजेक्ट", "REJ"), rejectCount.toString(), Red)
                    MetricChip(t(isHindi, "यील्ड", "YIELD"), "$yieldPct%", Yellow)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isHindi) Orange.copy(0.2f) else Blue.copy(0.2f))
                            .border(1.dp, if (isHindi) Orange else Blue, RoundedCornerShape(8.dp))
                            .clickable { onLanguageToggle() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if (isHindi) "EN" else "हि", color = if (isHindi) Orange else Blue, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when (activeTab) {
                0 -> ScanTab(
                    analyzerState = analyzerState,
                    isHindi = isHindi,
                    onPreviewViewCreated = onPreviewViewCreated,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording
                )
                1 -> AqlBatchTab(plan = aqlPlan, batchStartTime = aqlBatchStartTime, db = db, isHindi = isHindi, onNewBatch = { showAqlSetup = true }, onResetBatch = { aqlPlan = null; aqlBatchStartTime = 0L })
                2 -> LogsTab(logs = logs, context = context, scope = scope, isHindi = isHindi)
            }
        }
    }
}

// ── Scan Tab ──────────────────────────────────────────────────────────────
@Composable
private fun ScanTab(
    analyzerState: CameraAnalyzer.AnalyzerState,
    isHindi: Boolean,
    onPreviewViewCreated: (PreviewView) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val isRecording = analyzerState is CameraAnalyzer.AnalyzerState.Recording
    val isAnalyzing = analyzerState is CameraAnalyzer.AnalyzerState.Analyzing

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Camera Box ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 3.dp,
                    color = when {
                        isRecording -> Red
                        isAnalyzing -> Purple
                        else -> BorderColor
                    },
                    shape = RoundedCornerShape(20.dp)
                )
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Camera preview — always on
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        onPreviewViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Recording indicator
            if (isRecording) {
                val recording = analyzerState as CameraAnalyzer.AnalyzerState.Recording
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xBB000000))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Red))
                            Text(
                                t(isHindi, "RECORDING — Rotate item slowly", "रिकॉर्डिंग — आइटम धीरे घुमाएं"),
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xBB000000))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column {
                            LinearProgressIndicator(
                                progress = { recording.progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Red, trackColor = BorderColor
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t(isHindi, "${recording.elapsedSeconds}s • ${recording.framesCaptured} frames captured", "${recording.elapsedSeconds}s • ${recording.framesCaptured} फ्रेम"),
                                color = Color.LightGray, fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Analyzing overlay
            if (isAnalyzing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xEE0F0F14)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = Purple, modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                        Text(t(isHindi, "AI Analyzing frames...", "AI फ्रेम जांच रहा है..."), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(t(isHindi, "Please wait", "कृपया प्रतीक्षा करें"), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            // Idle hint
            if (analyzerState is CameraAnalyzer.AnalyzerState.Idle) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        t(isHindi, "Position item in frame → tap RECORD", "आइटम फ्रेम में रखें → RECORD दबाएं"),
                        color = Color.LightGray, fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Main Action Button ────────────────────────────────────────
        when {
            analyzerState is CameraAnalyzer.AnalyzerState.Idle -> {
                // RECORD button
                Button(
                    onClick = onStartRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.FiberManualRecord, null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        t(isHindi, "START RECORDING", "रिकॉर्डिंग शुरू करें"),
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    t(isHindi,
                        "Tap RECORD → rotate item 360° → tap STOP\nAI will analyze all sides",
                        "RECORD दबाएं → आइटम 360° घुमाएं → STOP दबाएं\nAI सभी तरफ से जांचेगा"),
                    color = Color.Gray, fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            analyzerState is CameraAnalyzer.AnalyzerState.Recording -> {
                // STOP button
                Button(
                    onClick = onStopRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333344)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Red)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(28.dp), tint = Red)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        t(isHindi, "STOP & ANALYZE", "रोकें और जांचें"),
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Red
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    t(isHindi,
                        "Rotate item slowly on all sides\nAuto-stops in ${8 - (analyzerState as CameraAnalyzer.AnalyzerState.Recording).elapsedSeconds}s",
                        "आइटम को सभी तरफ से धीरे घुमाएं\n${8 - (analyzerState as CameraAnalyzer.AnalyzerState.Recording).elapsedSeconds}s में auto-stop"),
                    color = Color.Gray, fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            analyzerState is CameraAnalyzer.AnalyzerState.Analyzing -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple.copy(0.3f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    CircularProgressIndicator(color = Purple, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(t(isHindi, "AI Analyzing...", "AI जांच रहा है..."), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Purple)
                }
            }

            else -> {}
        }
    }
}

// ── Result Screen ─────────────────────────────────────────────────────────
@Composable
private fun ResultScreen(
    result: CameraAnalyzer.AnalyzerState.Result,
    isHindi: Boolean,
    onConfirmDecision: (String) -> Unit,
    onScanNext: () -> Unit
) {
    val verdictColor = when (result.aiVerdict) {
        "PASS" -> Green
        "REJECT" -> Red
        else -> Yellow  // REVIEW
    }
    val verdictIcon = when (result.aiVerdict) {
        "PASS" -> "✅"
        "REJECT" -> "❌"
        else -> "⚠️"
    }
    val verdictText = when (result.aiVerdict) {
        "PASS" -> t(isHindi, "PASS", "पास")
        "REJECT" -> t(isHindi, "REJECT", "रिजेक्ट")
        else -> t(isHindi, "REVIEW NEEDED", "समीक्षा करें")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Verdict Banner ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(verdictColor.copy(0.15f))
                .border(2.dp, verdictColor, RoundedCornerShape(16.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(verdictIcon, fontSize = 32.sp)
                Column {
                    Text(
                        t(isHindi, "AI VERDICT", "AI का फैसला"),
                        color = verdictColor.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                    Text(verdictText, color = verdictColor, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    t(isHindi, "${result.totalFrames} frames\nanalyzed", "${result.totalFrames} फ्रेम\nजांचे"),
                    color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.End
                )
            }
        }

        // ── Defect Photo (if reject/review) ──────────────────────────
        if (result.worstFrameBitmap != null && !result.worstFrameBitmap.isRecycled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, Red, RoundedCornerShape(14.dp))
            ) {
                Image(
                    bitmap = result.worstFrameBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(t(isHindi, "Worst defect frame", "सबसे खराब फ्रेम"), color = Color.White, fontSize = 10.sp)
                }
                if (result.savedPhotoPath.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("📷 ${t(isHindi, "Saved", "सेव हुई")}", color = Green, fontSize = 10.sp)
                    }
                }
            }
        }

        // ── AI Checklist ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                t(isHindi, "AI ANALYSIS REPORT", "AI विश्लेषण रिपोर्ट"),
                color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
            )

            // Structure checks
            Text(t(isHindi, "STRUCTURE", "संरचना"), color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            result.checks.filter { it.defectType in listOf("Crack", "Knot", "Surface Hole") }
                .forEach { check -> DefectRow(check = check, isHindi = isHindi) }

            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

            // Surface checks
            Text(t(isHindi, "SURFACE", "सतह"), color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            result.checks.filter { it.defectType in listOf("Fungal Mold") }
                .forEach { check -> DefectRow(check = check, isHindi = isHindi) }
        }

        Spacer(Modifier.weight(1f))

        // ── Inspector Decision ────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                t(isHindi, "INSPECTOR FINAL DECISION", "निरीक्षक का अंतिम फैसला"),
                color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Text(
                t(isHindi, "AI has analyzed. You make the final call.", "AI ने जांच की। अंतिम निर्णय आपका है।"),
                color = Color.LightGray, fontSize = 12.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // CONFIRM PASS
                Button(
                    onClick = { onConfirmDecision("PASS") },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(20.dp), tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(t(isHindi, "PASS", "पास"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                }

                // CONFIRM REJECT
                Button(
                    onClick = { onConfirmDecision("REJECT") },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Cancel, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t(isHindi, "REJECT", "रिजेक्ट"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            // Scan next
            OutlinedButton(
                onClick = onScanNext,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue),
                border = androidx.compose.foundation.BorderStroke(1.dp, Blue)
            ) {
                Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t(isHindi, "SCAN NEXT ITEM", "अगला आइटम स्कैन करें"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Defect Row ────────────────────────────────────────────────────────────
@Composable
private fun DefectRow(check: CameraAnalyzer.DefectCheckResult, isHindi: Boolean) {
    val defectNameHindi = when (check.defectType) {
        "Crack" -> "दरार"; "Knot" -> "गाँठ"; "Surface Hole" -> "सतह छेद"
        "Fungal Mold" -> "फफूंद"; else -> check.defectType
    }
    val displayName = t(isHindi, check.defectType, defectNameHindi)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (check.found) Red else Green)
            )
            Text(displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        if (check.found) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Red.copy(0.15f))
                    .border(1.dp, Red.copy(0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    t(isHindi, "FOUND ${(check.confidence * 100).toInt()}%", "मिला ${(check.confidence * 100).toInt()}%"),
                    color = Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Green.copy(0.1f))
                    .border(1.dp, Green.copy(0.3f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    t(isHindi, "CLEAR ✓", "साफ ✓"),
                    color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Logs Tab ──────────────────────────────────────────────────────────────
@Composable
private fun LogsTab(logs: List<ItemLog>, context: Context, scope: kotlinx.coroutines.CoroutineScope, isHindi: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(t(isHindi, "INSPECTION LOGS", "निरीक्षण लॉग्स"), color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            if (logs.isEmpty()) { Toast.makeText(context, "No logs", Toast.LENGTH_SHORT).show(); return@launch }
                            val path = CsvExporter.exportToCsv(context, logs)
                            Toast.makeText(context, if (path.isNotEmpty()) "✅ CSV saved!" else "❌ Failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green.copy(0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(14.dp), tint = Color.Black)
                    Spacer(Modifier.width(4.dp))
                    Text("CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                Button(
                    onClick = {
                        scope.launch {
                            if (logs.isEmpty()) { Toast.makeText(context, "No logs", Toast.LENGTH_SHORT).show(); return@launch }
                            val path = PdfExporter.exportToPdf(context, logs, isHindi)
                            Toast.makeText(context, if (path.isNotEmpty()) "✅ PDF saved!" else "❌ Failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Orange.copy(0.85f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t(isHindi, "No logs yet.", "अभी कोई लॉग नहीं।"), color = Color.DarkGray, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs, key = { it.id }) { log -> LogRow(log = log, isHindi = isHindi) }
            }
        }
    }
}

// ── AQL Tab ───────────────────────────────────────────────────────────────
@Composable
private fun AqlBatchTab(plan: AqlCalculator.AqlPlan?, batchStartTime: Long, db: com.woodqc.app.database.AppDatabase, isHindi: Boolean, onNewBatch: () -> Unit, onResetBatch: () -> Unit) {
    if (plan == null) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Assignment, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(t(isHindi, "No Active Batch", "कोई सक्रिय बैच नहीं"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNewBatch, colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(12.dp), modifier = Modifier.height(50.dp).fillMaxWidth(0.65f)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp))
                Text(t(isHindi, "START NEW BATCH", "नया बैच शुरू करें"), fontWeight = FontWeight.Bold)
            }
        }
        return
    }
    var scanned by remember { mutableIntStateOf(0) }
    var rejects by remember { mutableIntStateOf(0) }
    LaunchedEffect(batchStartTime) { while (true) { scanned = db.itemLogDao().getTotalCountSince(batchStartTime); rejects = db.itemLogDao().getRejectCountSince(batchStartTime); delay(1000) } }
    val result = AqlCalculator.evaluate(plan, scanned, rejects)
    val verdictColor = when (result.verdict) { AqlCalculator.ShipmentVerdict.PASS_SHIPMENT -> Green; AqlCalculator.ShipmentVerdict.HOLD_SHIPMENT -> Red; else -> Yellow }
    val verdictText = when (result.verdict) { AqlCalculator.ShipmentVerdict.PASS_SHIPMENT -> t(isHindi, "✅ PASS SHIPMENT", "✅ शिपमेंट पास"); AqlCalculator.ShipmentVerdict.HOLD_SHIPMENT -> t(isHindi, "🚫 HOLD SHIPMENT", "🚫 शिपमेंट रोकें"); else -> t(isHindi, "🔍 SCANNING...", "🔍 स्कैन जारी...") }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(verdictColor.copy(0.15f)).border(2.dp, verdictColor, RoundedCornerShape(16.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
            Text(verdictText, color = verdictColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(t(isHindi, "Batch", "बैच"), "${plan.batchSize}", Color.White, Modifier.weight(1f))
            StatCard(t(isHindi, "Sample", "नमूना"), "${plan.sampleSize}", Yellow, Modifier.weight(1f))
            StatCard(t(isHindi, "Scanned", "स्कैन"), "$scanned", Blue, Modifier.weight(1f))
            StatCard(t(isHindi, "Rejects", "रिजेक्ट"), "$rejects/${plan.acceptNumber}", Red, Modifier.weight(1f))
        }
        val progress = if (plan.sampleSize > 0) scanned.toFloat() / plan.sampleSize else 0f
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = verdictColor, trackColor = BorderColor)
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onResetBatch, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Red), border = androidx.compose.foundation.BorderStroke(1.dp, Red)) { Text(t(isHindi, "RESET", "रीसेट"), fontWeight = FontWeight.Bold) }
            Button(onClick = onNewBatch, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(12.dp)) { Text(t(isHindi, "NEW BATCH", "नया बैच"), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AqlSetupDialog(isHindi: Boolean, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(CardBg).border(1.dp, BorderColor, RoundedCornerShape(20.dp)).padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(t(isHindi, "Set Batch Size", "बैच साइज़ सेट करें"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = text, onValueChange = { text = it.filter { c -> c.isDigit() }; isError = false }, label = { Text(t(isHindi, "Total items (e.g. 1200)", "कुल आइटम"), color = Color.Gray) }, isError = isError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = BorderColor, focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.fillMaxWidth())
                val preview = text.toIntOrNull()
                if (preview != null && preview >= 2) {
                    val p = AqlCalculator.getPlan(preview)
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Blue.copy(0.1f)).border(1.dp, Blue.copy(0.4f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Column { Text(t(isHindi, "Inspect: ${p.sampleSize} items", "जांचें: ${p.sampleSize} आइटम"), color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(t(isHindi, "Max rejects: ${p.acceptNumber}", "अधिकतम रिजेक्ट: ${p.acceptNumber}"), color = Color.LightGray, fontSize = 12.sp) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray), border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)) { Text(t(isHindi, "Cancel", "रद्द करें")) }
                    Button(onClick = { val s = text.toIntOrNull(); if (s == null || s < 2) isError = true else onConfirm(s) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(10.dp)) { Text(t(isHindi, "START", "शुरू करें"), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ── Reusable ──────────────────────────────────────────────────────────────
@Composable fun MetricChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable fun StatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(10.dp)).background(CardBg).border(1.dp, BorderColor, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Column { Text(label, color = Color.Gray, fontSize = 9.sp); Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

@Composable fun LogRow(log: ItemLog, isHindi: Boolean) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dh = when (log.defectType) { "Crack" -> "दरार"; "Knot" -> "गाँठ"; "Fungal Mold" -> "फफूंद"; "Surface Hole" -> "छेद"; "None" -> "कोई नहीं"; else -> log.defectType }
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF101017)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (log.verdict == "PASS") t(isHindi, "Quality OK", "गुणवत्ता ठीक") else t(isHindi, log.defectType, dh), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (log.photoPath.isNotEmpty()) Text("📷", fontSize = 10.sp)
            }
            Text(if (log.verdict == "PASS") t(isHindi, "No defect found", "कोई खराबी नहीं") else "${t(isHindi, "Confidence", "विश्वास")}: ${(log.confidence * 100).toInt()}%", color = Color.Gray, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (log.verdict == "PASS") Green.copy(0.15f) else Red.copy(0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(if (log.verdict == "PASS") t(isHindi, "PASS", "पास") else t(isHindi, "REJECT", "रिजेक्ट"), color = if (log.verdict == "PASS") Green else Red, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Text(fmt.format(Date(log.timestamp)), color = Color.DarkGray, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}