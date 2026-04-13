package com.ued.universaltvremote.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.model.TvDevice
import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewModel
import com.ued.universaltvremote.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.shadow
import com.ued.universaltvremote.model.TvBrand

@Composable
fun ScanScreen(viewModel: MainViewModel, onConnected: () -> Unit, connectionState: ConnectionState? = null) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val localConnectionState by viewModel.connectionState.collectAsState()
    val currentState = connectionState ?: localConnectionState
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("8008") }
    var selectedBrand by remember { mutableStateOf(TvBrand.ANDROID_TV) }
    var showManualEntry by remember { mutableStateOf(false) }



    Box(
        modifier = Modifier

                // Header with Logo
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // UED Logo
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.logo_ued),
                        contentDescription = "UED Logo",
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(8.dp, CircleShape)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Nhóm 5 UED",
                        style = MaterialTheme.typography.titleSmall,
                        color = GoldLight
                    )
                    Text(
                        text = "Universal TV Remote",
                        style = MaterialTheme.typography.headlineMedium,
                        color = GoldPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Find and connect to your Smart TV",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted
                    )
                }
            }

            item {
                // Radar animation + scan button
                RadarScanSection(isScanning = isScanning) {
                    viewModel.startScan()
                }
            }

            if (discoveredDevices.isNotEmpty()) {
                item {
                    Text(
                        text = "Found Devices",
                        style = MaterialTheme.typography.titleMedium,
                        color = GoldPrimary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(discoveredDevices) { device ->
                    val isConnectedDevice = currentState is ConnectionState.Connected && 
                            currentState.device.ip == device.ip
                            
                    TvDeviceCard(
                        device = device,
                        isConnected = isConnectedDevice
                    ) {
                        viewModel.connect(device)
                        onConnected() // Close sheet when selected
                    }
                }
            }

            item {
                // Manual IP entry
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showManualEntry = !showManualEntry },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Enter IP Manually")
                    }

                    if (showManualEntry) {
                        Spacer(Modifier.height(8.dp))

                        // IP input
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = { ipInput = it },
                            label = { Text("TV IP Address") },
                            placeholder = { Text("e.g. 192.168.1.100") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                focusedLabelColor = CyanAccent,
                                cursorColor = CyanAccent
                            )
                        )

                        Spacer(Modifier.height(8.dp))

                        // Port input
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Port") },
                            placeholder = { Text("e.g. 8008, 80, 3001") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                focusedLabelColor = CyanAccent,
                                cursorColor = CyanAccent
                            )
                        )

                        Spacer(Modifier.height(8.dp))

                        // Brand selector
                        var brandExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = brandExpanded,
                            onExpandedChange = { brandExpanded = !brandExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedBrand.name.replace('_', ' '),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("TV Brand") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = brandExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanAccent,
                                    focusedLabelColor = CyanAccent
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = brandExpanded,
                                onDismissRequest = { brandExpanded = false }
                            ) {
                                TvBrand.entries.filter { it != TvBrand.UNKNOWN }.forEach { brand ->
                                    DropdownMenuItem(
                                        text = { Text(brand.name.replace('_', ' ')) },
                                        onClick = {
                                            selectedBrand = brand
                                            brandExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Connect button
                        Button(
                            onClick = {
                                val ip = ipInput.trim()
                                val port = portInput.filter { it.isDigit() }.toIntOrNull() ?: 0
                                if (ip.isNotBlank()) {
                                    viewModel.connectToIp(ip, port, selectedBrand)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanAccent,
                                contentColor = NavyBackground
                            )
                        ) {
                            Icon(Icons.Default.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun RadarScanSection(isScanning: Boolean, onScan: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                if (isScanning) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .scale(scale)
                            .border(2.dp, CyanAccent.copy(alpha = alpha * 0.5f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .scale(scale * 0.7f)
                            .border(1.dp, CyanAccent.copy(alpha = alpha * 0.3f), CircleShape)
                    )
                }
                Button(
                    onClick = onScan,
                    modifier = Modifier.size(90.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) SurfaceVariantDark else GoldPrimary,
                        contentColor = if (isScanning) GoldPrimary else NavyBackground
                    )
                ) {
                    Icon(
                        if (isScanning) Icons.Default.Radar else Icons.Default.Search,
                        contentDescription = "Scan",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isScanning) "Scanning network…" else "Tap to scan",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isScanning) GoldPrimary else OnSurfaceMuted
            )
        }
    }
}

@Composable
fun TvDeviceCard(device: TvDevice, isConnected: Boolean = false, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "card_pulse")
    val borderAlpha by pulse.animateFloat(
        initialValue = if (isConnected) 0.8f else 0.3f,
        targetValue = if (isConnected) 0.3f else 0.8f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "border_alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) SuccessGreen.copy(alpha = 0.1f) else SurfaceVariantDark
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isConnected) 2.dp else 1.dp,
            if (isConnected) SuccessGreen.copy(alpha = borderAlpha) else CyanAccent.copy(alpha = borderAlpha)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(if (isConnected) SuccessGreen.copy(alpha = 0.2f) else SurfaceDark, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Tv,
                    contentDescription = null,
                    tint = if (isConnected) SuccessGreen else CyanAccent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isConnected) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device.ip + if (device.port != 8001) ":${device.port}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted
                )
                Text(
                    text = device.brand.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent.copy(alpha = 0.8f)
                )
                if (device.macAddress.isNotBlank()) {
                    Text(
                        text = "MAC: ${device.macAddress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted.copy(alpha = 0.5f)
                    )
                }
            }
            if (isConnected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Connected",
                    tint = SuccessGreen,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = OnSurfaceMuted
                )
            }
        }
    }
}
