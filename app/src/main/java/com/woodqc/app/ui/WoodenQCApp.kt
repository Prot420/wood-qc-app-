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

private fun t(hindi: Boolean, en: String, hi: String) = if (hindi) hi else en

@Composable
fun WoodenQCApp(
    analyzerState: CameraAnalyzer.AnalyzerState,
    onResumeScan: () -> Unit,
    onPhotoCapture: () -> Unit,
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
    var isPhotoMode by remember { mutableStateOf(false) }
    var showAqlSetup by remember { mutableStateOf(false) }
    var aqlPlan by remember { mutableStateOf<AqlCalculator.AqlPlan?>(null) }
    var aqlBatchStartTime by remember { mutableStateOf(0L) }

    // ── Auto-resume countdown ─────────────────────────────────────────────
    var countdownValue by remember { mutableIntStateOf(3) }
    LaunchedEffect(analyzerState) {
        if (analyzerState is CameraAnalyzer.AnalyzerState.Reject ||
            analyzerState is CameraAnalyzer.AnalyzerState.Pass
        ) {
            countdownValue = 3
            repeat(3) {
                delay(1000)
                countdownValue--
            }
            if (!isPhotoMode) onResumeScan()
        }
    }

    // ── Ring animation ────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "scale"
    )

    val ringColor = when (analyzerState) {
        is CameraAnalyzer.AnalyzerState.Scanning -> Yellow
        is CameraAnalyzer.AnalyzerState.Pass -> Green
        is CameraAnalyzer.AnalyzerState.Reject -> Red
    }

    if (showAqlSetup) {
        AqlSetupDialog(
            isHindi = isHindi,
            onConfirm = { batchSize ->
                aqlPlan = AqlCalculator.getPlan(batchSize)
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
            NavigationBar(containerColor = CardBg, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.CameraAlt, null) },
                    label = { Text(t(isHindi, "Scan", "स्कैन")) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Yellow, selectedTextColor = Yellow,
                        indicatorColor = Yellow.copy(alpha = 0.15f),
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
                        indicatorColor = Blue.copy(alpha = 0.15f),
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
                        indicatorColor = Green.copy(alpha = 0.15f),
                        unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Header ───────────────────────────────────────────────
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
                    Text(
                        t(isHindi, "FACTORY QC", "फैक्टरी QC"),
                        color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Senses Lifestyle",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricBox(t(isHindi, "कुल", "TOTAL"), total.toString(), Color.White)
                    MetricBox(t(isHindi, "पास", "PASS"), passCount.toString(), Green)
                    MetricBox(t(isHindi, "रिजेक्ट", "REJECT"), rejectCount.toString(), Red)
                    MetricBox(t(isHindi, "यील्ड", "YIELD"), "$yieldPct%", Yellow)

                    // Language toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isHindi) Orange.copy(0.2f) else Blue.copy(0.2f))
                            .border(1.dp, if (isHindi) Orange else Blue, RoundedCornerShape(8.dp))
                            .clickable { onLanguageToggle() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (isHindi) "EN" else "हि",
                            color = if (isHindi) Orange else Blue,
                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when (activeTab) {
                // ── SCAN TAB ──────────────────────────────────────────
                0 -> ScanTab(
                    analyzerState = analyzerState,
                    ringColor = ringColor,
                    pulseScale = pulseScale,
                    countdownValue = countdownValue,
                    isPhotoMode = isPhotoMode,
                    isHindi = isHindi,
                    onPreviewViewCreated = onPreviewViewCreated,
                    onResumeScan = onResumeScan,
                    onPhotoCapture = onPhotoCapture,
                    onPhotoModeToggle = {
                        isPhotoMode = !isPhotoMode
                        if (!isPhotoMode) onResumeScan()
                    }
                )

                // ── AQL TAB ───────────────────────────────────────────
                1 -> AqlBatchTab(
                    plan = aqlPlan,
                    batchStartTime = aqlBatchStartTime,
                    db = db,
                    isHindi = isHindi,
                    onNewBatch = { showAqlSetup = true },
                    onResetBatch = { aqlPlan = null; aqlBatchStartTime = 0L }
                )

                // ── LOGS TAB ──────────────────────────────────────────
                2 -> LogsTab(logs = logs, context = context, scope = scope, isHindi = isHindi)
            }
        }
    }
}

// ── Scan Tab ──────────────────────────────────────────────────────────────
@Composable
private fun ScanTab(
    analyzerState: CameraAnalyzer.AnalyzerState,
    ringColor: Color,
    pulseScale: Float,
    countdownValue: Int,
    isPhotoMode: Boolean,
    isHindi: Boolean,
    onPreviewViewCreated: (PreviewView) -> Unit,
    onResumeScan: () -> Unit,
    onPhotoCapture: () -> Unit,
    onPhotoModeToggle: () -> Unit
) {
    val statusText = when (analyzerState) {
        is CameraAnalyzer.AnalyzerState.Scanning ->
            if (isPhotoMode) t(isHindi, "PHOTO MODE — TAP CAPTURE", "फोटो मोड — कैप्चर दबाएं")
            else t(isHindi, "SCANNING WOOD", "लकड़ी स्कैन हो रही है")
        is CameraAnalyzer.AnalyzerState.Pass ->
            t(isHindi, "✓ ITEM PASSED", "✓ आइटम पास")
        is CameraAnalyzer.AnalyzerState.Reject -> {
            val d = (analyzerState as CameraAnalyzer.AnalyzerState.Reject).defectType
            val dh = when (d) {
                "Crack" -> "दरार"; "Knot" -> "गाँठ"; "Fungal Mold" -> "फफूंद"
                "Surface Hole" -> "छेद"; else -> d
            }
            t(isHindi, "✗ REJECTED — $d", "✗ रिजेक्ट — $dh")
        }
    }

    // Camera viewfinder box
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = (3.dp.value * pulseScale).dp,
                color = ringColor,
                shape = RoundedCornerShape(20.dp)
            )
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // ── Camera Preview (always rendered — never removed from composition) ──
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // Use TEXTURE_VIEW for better compositing with overlays
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    onPreviewViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Frozen frame overlay (REJECT only) ────────────────────────
        if (analyzerState is CameraAnalyzer.AnalyzerState.Reject) {
            val frozenBitmap = (analyzerState as CameraAnalyzer.AnalyzerState.Reject).frozenImage
            if (!frozenBitmap.isRecycled) {
                Image(
                    bitmap = frozenBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // ── PASS green overlay ────────────────────────────────────────
        AnimatedVisibility(
            visible = analyzerState is CameraAnalyzer.AnalyzerState.Pass,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xDD00C853)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(80.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t(isHindi, "QUALITY ASSURED", "गुणवत्ता सुनिश्चित"),
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // ── Status badge ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xBB000000))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ringColor))
                Text(statusText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        // ── Photo saved badge ─────────────────────────────────────────
        if (analyzerState is CameraAnalyzer.AnalyzerState.Reject &&
            (analyzerState as CameraAnalyzer.AnalyzerState.Reject).savedPhotoPath.isNotEmpty()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(t(isHindi, "📷 Photo saved", "📷 फोटो सेव हुई"), color = Color.White, fontSize = 10.sp)
            }
        }

        // ── Countdown badge ───────────────────────────────────────────
        if (analyzerState !is CameraAnalyzer.AnalyzerState.Scanning && !isPhotoMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC0F0F14))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    t(isHindi, "स्कैन ${countdownValue}s में शुरू होगा", "Resumes in ${countdownValue}s"),
                    color = Color.LightGray, fontSize = 11.sp
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // ── Mode toggle + action buttons ─────────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onPhotoModeToggle,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isPhotoMode) Blue else Yellow
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, if (isPhotoMode) Blue else Yellow
            )
        ) {
            Icon(
                if (isPhotoMode) Icons.Default.PhotoCamera else Icons.Default.Videocam,
                null, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isPhotoMode) t(isHindi, "PHOTO MODE", "फोटो मोड")
                else t(isHindi, "VIDEO MODE", "वीडियो मोड"),
                fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = { if (isPhotoMode) onPhotoCapture() else onResumeScan() },
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPhotoMode) Blue else ringColor
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                if (isPhotoMode) Icons.Default.Camera else Icons.Default.PlayArrow,
                null, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isPhotoMode) t(isHindi, "CAPTURE", "कैप्चर")
                else t(isHindi, "RESUME SCAN", "स्कैन जारी रखें"),
                color = if (!isPhotoMode && ringColor == Yellow) Color.Black else Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        if (isPhotoMode) t(isHindi, "Position product → tap CAPTURE", "उत्पाद रखें → कैप्चर दबाएं")
        else t(isHindi, "Hold product steady — auto-detects defects", "उत्पाद स्थिर रखें — खराबी अपने आप पकड़ेगा"),
        color = Color.Gray, fontSize = 11.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
    )
}

// ── Logs Tab ──────────────────────────────────────────────────────────────
@Composable
private fun LogsTab(
    logs: List<ItemLog>,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    isHindi: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                t(isHindi, "INSPECTION LOGS", "निरीक्षण लॉग्स"),
                color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            if (logs.isEmpty()) {
                                Toast.makeText(context, t(isHindi, "No logs", "कोई लॉग नहीं"), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val path = CsvExporter.exportToCsv(context, logs)
                            Toast.makeText(
                                context,
                                if (path.isNotEmpty()) t(isHindi, "✅ CSV saved!", "✅ CSV सेव हुई!")
                                else t(isHindi, "❌ Failed", "❌ विफल"),
                                Toast.LENGTH_SHORT
                            ).show()
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
                            if (logs.isEmpty()) {
                                Toast.makeText(context, t(isHindi, "No logs", "कोई लॉग नहीं"), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val path = PdfExporter.exportToPdf(context, logs, isHindi)
                            Toast.makeText(
                                context,
                                if (path.isNotEmpty()) t(isHindi, "✅ PDF saved!", "✅ PDF सेव हुई!")
                                else t(isHindi, "❌ Failed", "❌ विफल"),
                                Toast.LENGTH_SHORT
                            ).show()
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
                Text(
                    t(isHindi, "No inspection logs yet.", "अभी कोई लॉग नहीं।"),
                    color = Color.DarkGray, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogItemRow(log = log, isHindi = isHindi, passColor = Green, rejectColor = Red)
                }
            }
        }
    }
}

// ── AQL Tab ───────────────────────────────────────────────────────────────
@Composable
private fun AqlBatchTab(
    plan: AqlCalculator.AqlPlan?,
    batchStartTime: Long,
    db: com.woodqc.app.database.AppDatabase,
    isHindi: Boolean,
    onNewBatch: () -> Unit,
    onResetBatch: () -> Unit
) {
    if (plan == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Assignment, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(t(isHindi, "No Active Batch", "कोई सक्रिय बैच नहीं"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(t(isHindi, "Start a new batch for AQL inspection", "AQL जांच के लिए नया बैच शुरू करें"), color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onNewBatch,
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp).fillMaxWidth(0.65f)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(t(isHindi, "START NEW BATCH", "नया बैच शुरू करें"), fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    var scanned by remember { mutableIntStateOf(0) }
    var rejects by remember { mutableIntStateOf(0) }

    LaunchedEffect(batchStartTime) {
        while (true) {
            scanned = db.itemLogDao().getTotalCountSince(batchStartTime)
            rejects = db.itemLogDao().getRejectCountSince(batchStartTime)
            delay(1000)
        }
    }

    val result = AqlCalculator.evaluate(plan, scanned, rejects)
    val verdictColor = when (result.verdict) {
        AqlCalculator.ShipmentVerdict.PASS_SHIPMENT -> Green
        AqlCalculator.ShipmentVerdict.HOLD_SHIPMENT -> Red
        AqlCalculator.ShipmentVerdict.SCANNING -> Yellow
    }
    val verdictText = when (result.verdict) {
        AqlCalculator.ShipmentVerdict.PASS_SHIPMENT -> t(isHindi, "✅ PASS SHIPMENT", "✅ शिपमेंट पास")
        AqlCalculator.ShipmentVerdict.HOLD_SHIPMENT -> t(isHindi, "🚫 HOLD SHIPMENT", "🚫 शिपमेंट रोकें")
        AqlCalculator.ShipmentVerdict.SCANNING -> t(isHindi, "🔍 SCANNING...", "🔍 स्कैन जारी है...")
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(verdictColor.copy(0.15f))
                .border(2.dp, verdictColor, RoundedCornerShape(16.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(verdictText, color = verdictColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AqlStatCard(t(isHindi, "Batch", "बैच"), "${plan.batchSize}", Color.White, Modifier.weight(1f))
            AqlStatCard(t(isHindi, "Sample", "नमूना"), "${plan.sampleSize}", Yellow, Modifier.weight(1f))
            AqlStatCard(t(isHindi, "Scanned", "स्कैन"), "$scanned", Blue, Modifier.weight(1f))
            AqlStatCard(t(isHindi, "Rejects", "रिजेक्ट"), "$rejects/${plan.acceptNumber}", Red, Modifier.weight(1f))
        }

        val progress = if (plan.sampleSize > 0) scanned.toFloat() / plan.sampleSize else 0f
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = verdictColor, trackColor = BorderColor
            )
            Text("${(progress * 100).toInt()}% ${t(isHindi, "scanned", "स्कैन हुआ")}", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ISO 2859-1 · Level II · AQL 2.5", color = Color.Gray, fontSize = 11.sp)
                Text(t(isHindi, "Accept ≤ ${plan.acceptNumber} rejects", "स्वीकार ≤ ${plan.acceptNumber} रिजेक्ट"), color = Color.LightGray, fontSize = 12.sp)
                Text(t(isHindi, "Hold if ≥ ${plan.rejectNumber} rejects", "रोकें ≥ ${plan.rejectNumber} रिजेक्ट पर"), color = Red, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onResetBatch,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                border = androidx.compose.foundation.BorderStroke(1.dp, Red)
            ) { Text(t(isHindi, "RESET", "रीसेट"), fontWeight = FontWeight.Bold) }
            Button(
                onClick = onNewBatch,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp)
            ) { Text(t(isHindi, "NEW BATCH", "नया बैच"), fontWeight = FontWeight.Bold) }
        }
    }
}

// ── AQL Setup Dialog ──────────────────────────────────────────────────────
@Composable
private fun AqlSetupDialog(isHindi: Boolean, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var batchSizeText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
                .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(t(isHindi, "Set Batch Size", "बैच साइज़ सेट करें"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = batchSizeText,
                    onValueChange = { batchSizeText = it.filter { c -> c.isDigit() }; isError = false },
                    label = { Text(t(isHindi, "Total items (e.g. 1200)", "कुल आइटम (जैसे 1200)"), color = Color.Gray) },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Blue, unfocusedBorderColor = BorderColor,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                val previewSize = batchSizeText.toIntOrNull()
                if (previewSize != null && previewSize >= 2) {
                    val plan = AqlCalculator.getPlan(previewSize)
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Blue.copy(0.1f))
                            .border(1.dp, Blue.copy(0.4f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(t(isHindi, "Inspect: ${plan.sampleSize} items", "जांचें: ${plan.sampleSize} आइटम"), color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(t(isHindi, "Max rejects allowed: ${plan.acceptNumber}", "अधिकतम रिजेक्ट: ${plan.acceptNumber}"), color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                    ) { Text(t(isHindi, "Cancel", "रद्द करें")) }
                    Button(
                        onClick = {
                            val size = batchSizeText.toIntOrNull()
                            if (size == null || size < 2) isError = true else onConfirm(size)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(t(isHindi, "START", "शुरू करें"), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────
@Composable
fun MetricBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun AqlStatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(label, color = Color.Gray, fontSize = 9.sp)
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun LogItemRow(log: ItemLog, isHindi: Boolean, passColor: Color, rejectColor: Color) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val defectHindi = when (log.defectType) {
        "Crack" -> "दरार"; "Knot" -> "गाँठ"; "Fungal Mold" -> "फफूंद"
        "Surface Hole" -> "छेद"; "None" -> "कोई नहीं"; else -> log.defectType
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101017))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (log.verdict == "PASS") t(isHindi, "Quality OK", "गुणवत्ता ठीक")
                    else t(isHindi, log.defectType, defectHindi),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                if (log.photoPath.isNotEmpty()) Text("📷", fontSize = 10.sp)
            }
            Text(
                if (log.verdict == "PASS") t(isHindi, "No defect found", "कोई खराबी नहीं")
                else "${t(isHindi, "Confidence", "विश्वास")}: ${(log.confidence * 100).toInt()}%",
                color = Color.Gray, fontSize = 11.sp
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (log.verdict == "PASS") passColor.copy(0.15f)
                        else rejectColor.copy(0.15f)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    if (log.verdict == "PASS") t(isHindi, "PASS", "पास")
                    else t(isHindi, "REJECT", "रिजेक्ट"),
                    color = if (log.verdict == "PASS") passColor else rejectColor,
                    fontSize = 11.sp, fontWeight = FontWeight.Black
                )
            }
            Text(
                formatter.format(Date(log.timestamp)),
                color = Color.DarkGray, fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}