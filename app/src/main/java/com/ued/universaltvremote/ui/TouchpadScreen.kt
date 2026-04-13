package com.ued.universaltvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewModel
import kotlin.math.abs

@Composable
fun TouchpadScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val haptic = LocalHapticFeedback.current
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Touchpad", style = MaterialTheme.typography.titleLarge, color = CyanAccent)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showKeyboard = !showKeyboard },
                    modifier = Modifier.background(SurfaceVariantDark, CircleShape)
                ) {
                    Icon(Icons.Default.KeyboardAlt, "Keyboard", tint = CyanAccent)
                }
            }

            if (!isConnected) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Connect to a TV first", color = OnSurfaceMuted, style = MaterialTheme.typography.titleMedium)
                }
                return@Column
            }

            // Keyboard input overlay
            if (showKeyboard) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = keyboardText,
                            onValueChange = { new ->
                                keyboardText = new
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type to send to TV…") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                cursorColor = CyanAccent
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            if (keyboardText.isNotBlank()) {
                                viewModel.sendText(keyboardText)
                                keyboardText = ""
                            }
                        }) {
                            Icon(Icons.Default.Send, "Send", tint = CyanAccent)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Swipe gesture hints
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("• Drag to navigate", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                Text("• Tap = OK", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                Text("• 2-finger = scroll", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }

            Spacer(Modifier.height(8.dp))

            // Main touchpad area
            var accX by remember { mutableStateOf(0f) }
            var accY by remember { mutableStateOf(0f) }
            val threshold = 60f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
                    .background(SurfaceVariantDark, RoundedCornerShape(24.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendKey("KEY_ENTER")
                            },
                            onDoubleTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendKey("KEY_RETURN")
                            },
                            onLongPress = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendKey("KEY_MENU")
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            accX += dragAmount.x
                            accY += dragAmount.y
                            while (abs(accX) >= threshold) {
                                if (accX > 0) viewModel.sendKey("KEY_RIGHT") else viewModel.sendKey("KEY_LEFT")
                                accX -= threshold * (if (accX > 0) 1 else -1)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            while (abs(accY) >= threshold) {
                                if (accY > 0) viewModel.sendKey("KEY_DOWN") else viewModel.sendKey("KEY_UP")
                                accY -= threshold * (if (accY > 0) 1 else -1)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PanTool, null, tint = CyanAccent.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Text("Touchpad Area", color = OnSurfaceMuted.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Action buttons row at bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RemoteIconButton(Icons.Default.ArrowBack, "Back") { viewModel.sendKey("KEY_RETURN") }
                RemoteIconButton(Icons.Default.Home, "Home") { viewModel.sendKey("KEY_HOME") }
                RemoteIconButton(Icons.Default.Menu, "Menu") { viewModel.sendKey("KEY_MENU") }
                RemoteIconButton(Icons.Default.PlayArrow, "Play") { viewModel.sendKey("KEY_PLAY") }
                RemoteIconButton(Icons.Default.PauseCircle, "Pause") { viewModel.sendKey("KEY_PAUSE") }
            }
        }
    }
}


