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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import com.woodqc.app.database.AppDatabase
import com.woodqc.app.database.ItemLog
import com.woodqc.app.utils.AqlCalculator
import com.woodqc.app.utils.CsvExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Color Palette ─────────────────────────────────────────────────────────
private val DarkBg       = Color(0xFF0F0F14)
private val CardBg       = Color(0xFF181822)
private val BorderColor  = Color(0xFF2C2C3C)
private val Yellow       = Color(0xFFFFB300)
private val Green        = Color(0xFF00E676)
private val Red          = Color(0xFFFF1744)
private val Blue         = Color(0xFF2979FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WoodenQCApp(
    analyzerState: CameraAnalyzer.AnalyzerState,
    onResumeScan: () -> Unit,
    onPhotoCapture: () -> Unit,
    onPreviewViewCreated: (PreviewView) -> Unit,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    selectedWoodType: String,
    onWoodTypeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    val logs by db.itemLogDao().getAllLogs().collectAsState(initial = emptyList())
    val passCount by db.itemLogDao().getPassCount().collectAsState(initial = 0)
    val rejectCount by db.itemLogDao().getRejectCount().collectAsState(initial = 0)
    val totalScanned = passCount + rejectCount
    val yieldPct = if (totalScanned > 0) (passCount * 100) / totalScanned else 100

    // Screen tab: 0 = Scan, 1 = AQL, 2 = Logs
    var activeTab by remember { mutableIntStateOf(0) }

    // Phase 1: Photo mode toggle
    var isPhotoMode by remember { mutableStateOf(false) }

    // AQL state
    var showAqlSetup by remember { mutableStateOf(false) }
    var aqlPlan by remember { mutableStateOf<AqlCalculator.AqlPlan?>(null) }
    var aqlBatchStartTime by remember { mutableStateOf(0L) }

    // Auto-resume countdown
    var countdownValue by remember { mutableIntStateOf(3) }
    LaunchedEffect(analyzerState) {
        if (analyzerState is CameraAnalyzer.AnalyzerState.Reject ||
            analyzerState is CameraAnalyzer.AnalyzerState.Pass
        ) {
            countdownValue = 3
            while (countdownValue > 0) {
                delay(1000)
                countdownValue--
            }
            if (!isPhotoMode) onResumeScan()
        }
    }

    // Ring animation
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
    val statusText = when (analyzerState) {
        is CameraAnalyzer.AnalyzerState.Scanning ->
            if (isPhotoMode) "PHOTO MODE — TAP CAPTURE" else "SCANNING WOOD"
        is CameraAnalyzer.AnalyzerState.Pass -> "ITEM PASSED ✓"
        is CameraAnalyzer.AnalyzerState.Reject ->
            "REJECTED — ${(analyzerState as CameraAnalyzer.AnalyzerState.Reject).defectType}"
    }

    // AQL dialog
    if (showAqlSetup) {
        AqlSetupDialog(
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
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBg,
        bottomBar = {
            NavigationBar(containerColor = CardBg, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("Scan") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Yellow,
                        selectedTextColor = Yellow,
                        indicatorColor = Yellow.copy(alpha = 0.15f),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = {
                        if (aqlPlan == null) showAqlSetup = true else activeTab = 1
                    },
                    icon = { Icon(Icons.Default.Assignment, contentDescription = null) },
                    label = { Text("AQL") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Blue,
                        selectedTextColor = Blue,
                        indicatorColor = Blue.copy(alpha = 0.15f),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Logs") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Green,
                        selectedTextColor = Green,
                        indicatorColor = Green.copy(alpha = 0.15f),
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
            }
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Header Stats ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBg)
                    .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("FACTORY METRICS", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Senses Lifestyle QC", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetricBox("TOTAL", totalScanned.toString(), Color.White)
                    MetricBox("PASS", passCount.toString(), Green)
                    MetricBox("REJECT", rejectCount.toString(), Red)
                    MetricBox("YIELD", "$yieldPct%", Yellow)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Tab Content ───────────────────────────────────────────────
            when (activeTab) {

                // ── TAB 0: SCAN ───────────────────────────────────────────
                0 -> {
                    // Dropdowns
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QCSelectionDropdown(
                            label = "QC Category",
                            selectedValue = selectedCategory,
                            options = listOf("Salad Bowl", "Cutting Board", "Spoon", "Plate", "Coaster", "Tray", "Cheese Server"),
                            modifier = Modifier.weight(1f),
                            onSelected = onCategorySelected
                        )
                        QCSelectionDropdown(
                            label = "Wood Profile",
                            selectedValue = selectedWoodType,
                            options = listOf("Mango", "Acacia", "Sheesham"),
                            modifier = Modifier.weight(1f),
                            onSelected = onWoodTypeSelected
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Camera Viewfinder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                width = 4.dp * pulseScale,
                                color = ringColor,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .shadow(16.dp, RoundedCornerShape(20.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    onPreviewViewCreated(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Frozen defect snapshot overlay
                        if (analyzerState is CameraAnalyzer.AnalyzerState.Reject) {
                            Image(
                                bitmap = (analyzerState as CameraAnalyzer.AnalyzerState.Reject)
                                    .frozenImage.asImageBitmap(),
                                contentDescription = "Defect Snapshot",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FIT
                            )
                        }

                        // PASS green overlay
                        if (analyzerState is CameraAnalyzer.AnalyzerState.Pass) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color(0xD900E676)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(72.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("QUALITY ASSURED", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Status badge top
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 14.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 18.dp, vertical = 7.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(ringColor))
                                Text(statusText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }

                        // Photo saved badge (bottom left) — Phase 1
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
                                Text("📷 Photo saved", color = Color.White, fontSize = 10.sp)
                            }
                        }

                        // Countdown badge bottom
                        if (analyzerState !is CameraAnalyzer.AnalyzerState.Scanning && !isPhotoMode) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 14.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xCC0F0F14))
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text("Scan resumes in ${countdownValue}s", color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Mode Toggle + Action Buttons ─────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Video / Photo mode toggle
                        OutlinedButton(
                            onClick = {
                                isPhotoMode = !isPhotoMode
                                if (!isPhotoMode) onResumeScan()
                            },
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
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isPhotoMode) "PHOTO MODE" else "VIDEO MODE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Action button
                        Button(
                            onClick = {
                                if (isPhotoMode) {
                                    // Photo mode: capture single frame
                                    onPhotoCapture()
                                } else {
                                    // Video mode: resume scan
                                    onResumeScan()
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPhotoMode) Blue else ringColor
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (isPhotoMode) Icons.Default.Camera else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isPhotoMode) "CAPTURE" else "RESUME SCAN",
                                color = if (!isPhotoMode && ringColor == Yellow) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Hint text
                    Text(
                        text = if (isPhotoMode)
                            "Position product in frame → tap CAPTURE to analyze"
                        else
                            "Hold product steady in viewfinder — auto-detects defects",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── TAB 1: AQL BATCH ──────────────────────────────────────
                1 -> {
                    AqlBatchTab(
                        plan = aqlPlan,
                        batchStartTime = aqlBatchStartTime,
                        db = db,
                        onNewBatch = { showAqlSetup = true },
                        onResetBatch = {
                            aqlPlan = null
                            aqlBatchStartTime = 0L
                        }
                    )
                }

                // ── TAB 2: LOGS ───────────────────────────────────────────
                2 -> {
                    LogsTab(logs = logs, context = context, scope = rememberCoroutineScope())
                }
            }
        }
    }
}

// ── AQL Batch Tab ─────────────────────────────────────────────────────────
@Composable
private fun AqlBatchTab(
    plan: AqlCalculator.AqlPlan?,
    batchStartTime: Long,
    db: com.woodqc.app.database.AppDatabase,
    onNewBatch: () -> Unit,
    onResetBatch: () -> Unit
) {
    val scope = rememberCoroutineScope()

    if (plan == null) {
        // No active batch
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Assignment, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("No Active Batch", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Set batch size to start AQL inspection", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onNewBatch,
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp).fillMaxWidth(0.6f)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("START NEW BATCH", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    // Active batch
    var scanned by remember { mutableIntStateOf(0) }
    var rejects by remember { mutableIntStateOf(0) }

    // Live count from DB since batch start
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
        AqlCalculator.ShipmentVerdict.PASS_SHIPMENT -> "✅ PASS SHIPMENT"
        AqlCalculator.ShipmentVerdict.HOLD_SHIPMENT -> "🚫 HOLD SHIPMENT"
        AqlCalculator.ShipmentVerdict.SCANNING -> "🔍 SCANNING..."
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Verdict banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(verdictColor.copy(alpha = 0.15f))
                .border(2.dp, verdictColor, RoundedCornerShape(16.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(verdictText, color = verdictColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        // AQL stats grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AqlStatCard("Batch Size", "${plan.batchSize}", Color.White, Modifier.weight(1f))
            AqlStatCard("Sample Size", "${plan.sampleSize}", Yellow, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AqlStatCard("Scanned", "$scanned / ${plan.sampleSize}", Blue, Modifier.weight(1f))
            AqlStatCard("Rejects", "$rejects / ${plan.acceptNumber} max", Red, Modifier.weight(1f))
        }

        // Progress bar
        val progress = if (plan.sampleSize > 0) scanned.toFloat() / plan.sampleSize else 0f
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Inspection Progress", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = verdictColor,
                trackColor = BorderColor
            )
            Text(
                "${(progress * 100).toInt()}% of sample scanned",
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // AQL info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AQL Standard: ISO 2859-1 · Level II · AQL 2.5", color = Color.Gray, fontSize = 11.sp)
                Text("Accept if rejects ≤ ${plan.acceptNumber}  |  Hold if rejects ≥ ${plan.rejectNumber}", color = Color.LightGray, fontSize = 12.sp)
                Text("Used by TJX, Kirkland, Stephen Joseph buyers", color = Color.Gray, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        // Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onResetBatch,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                border = androidx.compose.foundation.BorderStroke(1.dp, Red)
            ) {
                Text("RESET", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onNewBatch,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("NEW BATCH", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AqlStatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column {
            Text(label, color = Color.Gray, fontSize = 10.sp)
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

// ── Logs Tab ──────────────────────────────────────────────────────────────
@Composable
private fun LogsTab(
    logs: List<ItemLog>,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        // Header with CSV export button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "INSPECTION LOGS",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            // Phase 1: CSV Export button
            Button(
                onClick = {
                    scope.launch {
                        val allLogs = logs // already loaded
                        if (allLogs.isEmpty()) {
                            Toast.makeText(context, "No logs to export", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val path = CsvExporter.exportToCsv(context, allLogs)
                        if (path.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                "✅ CSV saved to Downloads/WoodQC/",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "❌ Export failed. Check storage permissions.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Green.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("CSV", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No inspection logs yet.", color = Color.DarkGray, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(logs) { log -> LogItemRow(log, Green, Red) }
            }
        }
    }
}

// ── AQL Setup Dialog ─────────────────────────────────────────────────────
@Composable
private fun AqlSetupDialog(
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
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
                Text("Set Batch Size", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Enter the total number of items in this production batch. The app will calculate the AQL sample size automatically (ISO 2859-1, Level II).",
                    color = Color.Gray,
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = batchSizeText,
                    onValueChange = {
                        batchSizeText = it.filter { c -> c.isDigit() }
                        isError = false
                    },
                    label = { Text("Batch Size (e.g. 1200)", color = Color.Gray) },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Blue,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Preview AQL plan
                val previewSize = batchSizeText.toIntOrNull()
                if (previewSize != null && previewSize >= 2) {
                    val plan = AqlCalculator.getPlan(previewSize)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Blue.copy(alpha = 0.1f))
                            .border(1.dp, Blue.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text("Inspect: ${plan.sampleSize} items", color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Accept if rejects ≤ ${plan.acceptNumber}", color = Color.LightGray, fontSize = 12.sp)
                            Text("Hold if rejects ≥ ${plan.rejectNumber}", color = Red, fontSize = 12.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                    ) { Text("Cancel") }

                    Button(
                        onClick = {
                            val size = batchSizeText.toIntOrNull()
                            if (size == null || size < 2) {
                                isError = true
                            } else {
                                onConfirm(size)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("START BATCH", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ── Reusable Composables ──────────────────────────────────────────────────
@Composable
fun MetricBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun QCSelectionDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column {
            Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg)
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedValue, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ArrowDropDown, null, tint = Color.Gray)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(CardBg).border(1.dp, BorderColor)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Color.White) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}

@Composable
fun LogItemRow(log: ItemLog, passColor: Color, rejectColor: Color) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101017))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(log.category, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("(${log.woodType})", color = Color.Gray, fontSize = 11.sp)
                // Phase 1: Show camera icon if photo was saved
                if (log.photoPath.isNotEmpty()) {
                    Text("📷", fontSize = 11.sp)
                }
            }
            Text(
                if (log.verdict == "PASS") "Flawless"
                else "Defect: ${log.defectType} (${(log.confidence * 100).toInt()}%)",
                color = Color.Gray, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
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
                    log.verdict,
                    color = if (log.verdict == "PASS") passColor else rejectColor,
                    fontSize = 11.sp, fontWeight = FontWeight.Black
                )
            }
            Text(
                formatter.format(Date(log.timestamp)),
                color = Color.DarkGray, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}