package com.woodqc.app.ui

import android.graphics.Bitmap
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.woodqc.app.camera.CameraAnalyzer
import com.woodqc.app.database.AppDatabase
import com.woodqc.app.database.ItemLog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WoodenQCApp(
    analyzerState: CameraAnalyzer.AnalyzerState,
    onResumeScan: () -> Unit,
    onPreviewViewCreated: (PreviewView) -> Unit,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    selectedWoodType: String,
    onWoodTypeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Live logs flow from database
    val logs by db.itemLogDao().getAllLogs().collectAsState(initial = emptyList())
    val passCount by db.itemLogDao().getPassCount().collectAsState(initial = 0)
    val rejectCount by db.itemLogDao().getRejectCount().collectAsState(initial = 0)
    
    val totalScanned = passCount + rejectCount
    val yieldPercentage = if (totalScanned > 0) (passCount * 100) / totalScanned else 100

    // Auto-resume timer logic
    var countdownValue by remember { mutableStateOf(3) }
    LaunchedEffect(analyzerState) {
        if (analyzerState is CameraAnalyzer.AnalyzerState.Reject || analyzerState is CameraAnalyzer.AnalyzerState.Pass) {
            countdownValue = 3
            while (countdownValue > 0) {
                delay(1000)
                countdownValue--
            }
            onResumeScan()
        }
    }

    // Color definitions for modern aesthetics
    val darkBackground = Color(0xFF0F0F14)
    val cardBackground = Color(0xFF181822)
    val highlightYellow = Color(0xFFFFB300)
    val highlightGreen = Color(0xFF00E676)
    val highlightRed = Color(0xFFFF1744)

    // Viewfinder Ring Animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val (ringColor, statusText) = when (analyzerState) {
        is CameraAnalyzer.AnalyzerState.Scanning -> highlightYellow to "SCANNING WOOD"
        is CameraAnalyzer.AnalyzerState.Pass -> highlightGreen to "ITEM PASSED"
        is CameraAnalyzer.AnalyzerState.Reject -> highlightRed to "ITEM REJECTED"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = darkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header stats block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBackground)
                    .border(1.dp, Color(0xFF2C2C3C), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("FACTORY METRICS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Moradabad Export QC", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricBox("TOTAL", totalScanned.toString(), Color.White)
                    MetricBox("PASS", passCount.toString(), highlightGreen)
                    MetricBox("REJECT", rejectCount.toString(), highlightRed)
                    MetricBox("YIELD", "$yieldPercentage%", highlightYellow)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Configuration dropdown selectors (Item and Wood profile)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QCSelectionDropdown(
                    label = "QC Category",
                    selectedValue = selectedCategory,
                    options = listOf("Salad Bowl", "Cutting Board", "Spoon", "Plate", "Coaster"),
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

            Spacer(modifier = Modifier.height(16.dp))

            // Camera Viewfinder & Frozen Snapshot container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = 4.dp * pulseScale,
                        color = ringColor,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .shadow(16.dp, RoundedCornerShape(24.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // Background video feed preview via CameraX
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            onPreviewViewCreated(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlap frozen annotated bitmap on defect detection
                if (analyzerState is CameraAnalyzer.AnalyzerState.Reject) {
                    Image(
                        bitmap = analyzerState.frozenImage.asImageBitmap(),
                        contentDescription = "Frozen Defect Snapshot",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FIT
                    )
                }

                // Overlay flash screen for PASS state
                if (analyzerState is CameraAnalyzer.AnalyzerState.Pass) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xD900E676)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Passed",
                                tint = Color.White,
                                modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "QUALITY ASSURED",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Status Overlay Bar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ringColor)
                        )
                        Text(
                            statusText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Overlay countdown when frozen (Reject / Pass state)
                if (analyzerState !is CameraAnalyzer.AnalyzerState.Scanning) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xCC0F0F14))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Scanning restarts in ${countdownValue}s",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action section below viewfinder
            if (analyzerState !is CameraAnalyzer.AnalyzerState.Scanning) {
                Button(
                    onClick = onResumeScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ringColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "RESUME SCAN",
                        color = if (ringColor == highlightYellow) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            } else {
                // Info block during live scanning
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardBackground)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Position wooden product in viewfinder box. App will auto-freeze on defects.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Database logs matrix
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBackground)
                    .border(1.dp, Color(0xFF2C2C3C), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "DAILY QUALITY LOGS",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No inspection logs recorded today.",
                            color = Color.DarkGray,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(logs) { log ->
                            LogItemRow(log, highlightGreen, highlightRed)
                        }
                    }
                }
            }
        }
    }
}

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
            Text(label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF181822))
                    .border(1.dp, Color(0xFF2C2C3C), RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedValue, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown Arrow",
                    tint = Color.Gray
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF181822)).border(1.dp, Color(0xFF2C2C3C))
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Color.White, fontWeight = FontWeight.Medium) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LogItemRow(log: ItemLog, passColor: Color, rejectColor: Color) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeString = formatter.format(Date(log.timestamp))

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    log.category,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "(${log.woodType})",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
            Text(
                text = if (log.verdict == "PASS") "Status: Flawless" else "Defect: ${log.defectType} (${(log.confidence * 100).toInt()}%)",
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (log.verdict == "PASS") passColor.copy(alpha = 0.15f) else rejectColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    log.verdict,
                    color = if (log.verdict == "PASS") passColor else rejectColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Text(
                timeString,
                color = Color.DarkGray,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
