package com.ued.universaltvremote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ued.universaltvremote.model.Macro
import com.ued.universaltvremote.model.MacroAction
import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewModel
import java.util.UUID

@Composable
fun MacroScreen(viewModel: MainViewModel) {
    val macros by viewModel.macros.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordedKeys by remember { mutableStateOf<List<MacroAction>>(emptyList()) }
    var recordName by remember { mutableStateOf("") }
    var lastKeyTime by remember { mutableStateOf(System.currentTimeMillis()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Macros", style = MaterialTheme.typography.titleLarge, color = CyanAccent, modifier = Modifier.weight(1f))
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = CyanAccent,
                    contentColor = NavyBackground,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, "Add Macro", modifier = Modifier.size(20.dp))
                }
            }

            // Recording indicator
            if (isRecording) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(ErrorRed, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text("Recording… ${recordedKeys.size} keys", color = ErrorRed, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                isRecording = false
                                if (recordedKeys.isNotEmpty() && recordName.isNotBlank()) {
                                    val macro = Macro(UUID.randomUUID().toString(), recordName, recordedKeys)
                                    viewModel.saveMacro(macro)
                                }
                                recordedKeys = emptyList()
                                recordName = ""
                            }) {
                                Text("Save", color = SuccessGreen)
                            }
                            TextButton(onClick = {
                                isRecording = false
                                recordedKeys = emptyList()
                                recordName = ""
                            }) {
                                Text("Cancel", color = OnSurfaceMuted)
                            }
                        }
                        // Show last few keys
                        if (recordedKeys.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = recordedKeys.takeLast(6).joinToString(" → ") { it.keyCode.removePrefix("KEY_") },
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Record remote keys
                MacroRecordingRemote { keyCode ->
                    val now = System.currentTimeMillis()
                    val delay = (now - lastKeyTime).coerceIn(100L, 5000L)
                    lastKeyTime = now
                    recordedKeys = recordedKeys + MacroAction(keyCode, delay)
                    viewModel.sendKey(keyCode)
                }
            }

            if (macros.isEmpty() && !isRecording) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PlaylistPlay, null, tint = OnSurfaceMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No macros yet", color = OnSurfaceMuted, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Create a macro to automate key sequences", color = OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (!isRecording) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(macros, key = { it.id }) { macro ->
                        MacroCard(
                            macro = macro,
                            onPlay = { viewModel.playMacro(macro) },
                            onDelete = { viewModel.deleteMacro(macro.id) }
                        )
                    }
                }
            }
        }

        // Create macro dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                containerColor = SurfaceDark,
                title = { Text("New Macro", color = CyanAccent) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = recordName,
                            onValueChange = { recordName = it },
                            label = { Text("Macro name") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                focusedLabelColor = CyanAccent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "After clicking Record, press buttons on the remote to capture the sequence.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCreateDialog = false
                            isRecording = true
                            lastKeyTime = System.currentTimeMillis()
                        },
                        enabled = recordName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = NavyBackground)
                    ) {
                        Text("Start Recording")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel", color = OnSurfaceMuted)
                    }
                }
            )
        }
    }
}

@Composable
fun MacroCard(macro: Macro, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.15f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(CyanAccent.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.QueueMusic, null, tint = CyanAccent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(macro.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("${macro.actions.size} steps", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, "Play", tint = SuccessGreen)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete", tint = ErrorRed.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
fun MacroRecordingRemote(onKey: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Press keys to record", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("Home" to "KEY_HOME", "Back" to "KEY_RETURN", "OK" to "KEY_ENTER",
                    "Vol+" to "KEY_VOLUP", "Vol-" to "KEY_VOLDOWN").forEach { (label, key) ->
                    Button(
                        onClick = { onKey(key) },
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("Up" to "KEY_UP", "Down" to "KEY_DOWN", "Left" to "KEY_LEFT",
                    "Right" to "KEY_RIGHT", "Mute" to "KEY_MUTE").forEach { (label, key) ->
                    Button(
                        onClick = { onKey(key) },
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
