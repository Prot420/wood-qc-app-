package com.woodqc.app.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

// ── PIN Storage helpers ────────────────────────────────────────────────────
private const val PREF_FILE = "wood_qc_pin"
private const val PREF_KEY_PIN_HASH = "pin_hash"
private const val DEFAULT_PIN = "1234"

private fun hashPin(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun isPinSet(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    return prefs.contains(PREF_KEY_PIN_HASH)
}

fun savePin(context: Context, pin: String) {
    context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_KEY_PIN_HASH, hashPin(pin))
        .apply()
}

fun verifyPin(context: Context, pin: String): Boolean {
    val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    val stored = prefs.getString(PREF_KEY_PIN_HASH, hashPin(DEFAULT_PIN)) ?: hashPin(DEFAULT_PIN)
    return hashPin(pin) == stored
}

// ── PIN Lock Screen Composable ─────────────────────────────────────────────
@Composable
fun PinLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current

    // Set default PIN on first launch
    LaunchedEffect(Unit) {
        if (!isPinSet(context)) {
            savePin(context, DEFAULT_PIN)
        }
    }

    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var attempts by remember { mutableStateOf(0) }
    var isLocked by remember { mutableStateOf(false) }

    val dotColor by animateColorAsState(
        targetValue = if (isError) Color(0xFFFF1744) else Color(0xFFFFB300),
        animationSpec = tween(200),
        label = "dot_color"
    )

    // Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {

            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "🪵",
                    fontSize = 48.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "WOOD QC",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                )
                Text(
                    "Senses Lifestyle — Moradabad",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            // Status / prompt
            Text(
                text = when {
                    isLocked -> "Too many attempts. Restart app."
                    isError -> "Wrong PIN. ${3 - attempts} tries left."
                    else -> "Enter Inspector PIN"
                },
                color = if (isError || isLocked) Color(0xFFFF1744) else Color.LightGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            // PIN dots indicator
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                if (index < enteredPin.length) dotColor
                                else Color(0xFF2C2C3C)
                            )
                    )
                }
            }

            // Numpad
            if (!isLocked) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "⌫")
                    )

                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            row.forEach { key ->
                                NumPadKey(
                                    label = key,
                                    onClick = {
                                        when (key) {
                                            "⌫" -> {
                                                if (enteredPin.isNotEmpty()) {
                                                    enteredPin = enteredPin.dropLast(1)
                                                    isError = false
                                                }
                                            }
                                            "" -> { /* empty slot */ }
                                            else -> {
                                                if (enteredPin.length < 4) {
                                                    enteredPin += key
                                                    isError = false

                                                    // Auto-submit when 4 digits entered
                                                    if (enteredPin.length == 4) {
                                                        if (verifyPin(context, enteredPin)) {
                                                            onUnlocked()
                                                        } else {
                                                            isError = true
                                                            attempts++
                                                            enteredPin = ""
                                                            if (attempts >= 3) isLocked = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Default PIN hint (only show first time)
            if (!isPinSet(context)) {
                Text(
                    "Default PIN: 1234",
                    color = Color(0xFF444455),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun NumPadKey(label: String, onClick: () -> Unit) {
    if (label.isEmpty()) {
        Box(modifier = Modifier.size(72.dp))
        return
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color(0xFF181822))
            .border(1.dp, Color(0xFF2C2C3C), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label == "⌫") {
            Icon(
                imageVector = Icons.Default.Backspace,
                contentDescription = "Delete",
                tint = Color.LightGray,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(
                label,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}