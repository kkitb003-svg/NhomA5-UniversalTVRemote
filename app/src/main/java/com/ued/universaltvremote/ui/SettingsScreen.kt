package com.ued.universaltvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewModel
import com.ued.universaltvremote.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val device = (connectionState as? ConnectionState.Connected)?.device
    val savedDevices by viewModel.savedDevices.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBackground)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = CyanAccent, modifier = Modifier.padding(vertical = 8.dp))
            }

            // Connection status card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isConnected) SuccessGreen.copy(alpha = 0.4f) else OnSurfaceMuted.copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isConnected) Icons.Default.CheckCircle else Icons.Default.WifiOff,
                                null,
                                tint = if (isConnected) SuccessGreen else OnSurfaceMuted,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (isConnected) "Connected" else "Not Connected",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isConnected) SuccessGreen else OnSurfaceMuted,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (device != null) {
                            Spacer(Modifier.height(12.dp))
                            SettingsInfoRow("TV Name", device.name)
                            SettingsInfoRow("IP Address", device.ip)
                            SettingsInfoRow("Port", device.port.toString())
                            if (device.macAddress.isNotBlank()) {
                                SettingsInfoRow("MAC Address", device.macAddress)
                            }
                            if (device.modelYear.isNotBlank()) {
                                SettingsInfoRow("Model Year", device.modelYear)
                            }
                        }
                    }
                }
            }

            // Saved Devices History
            if (savedDevices.isNotEmpty()) {
                item {
                    Text("Previously Connected Devices", style = MaterialTheme.typography.titleSmall, color = OnSurfaceMuted, modifier = Modifier.padding(top = 4.dp))
                }
                items(savedDevices) { savedDevice ->
                    SavedDeviceCard(
                        device = savedDevice,
                        onConnect = { viewModel.connect(savedDevice) },
                        onDelete = { viewModel.removeSavedDevice(savedDevice) }
                    )
                }
            }

            // Actions
            item {
                Text("Actions", style = MaterialTheme.typography.titleSmall, color = OnSurfaceMuted, modifier = Modifier.padding(top = 4.dp))
            }

            item {
                SettingsActionCard(
                    icon = Icons.Default.NetworkWifi,
                    title = "Wake-on-LAN",
                    subtitle = "Send magic packet to wake TV from standby",
                    buttonLabel = "Wake TV",
                    tint = CyanAccent
                ) {
                    viewModel.wakeOnLan()
                }
            }

            if (isConnected) {
                item {
                    SettingsActionCard(
                        icon = Icons.Default.WifiOff,
                        title = "Disconnect",
                        subtitle = "Disconnect from current TV and clear saved session",
                        buttonLabel = "Disconnect",
                        tint = ErrorRed
                    ) {
                        viewModel.disconnect()
                    }
                }
            }

            // About
            item {
                Text("About", style = MaterialTheme.typography.titleSmall, color = OnSurfaceMuted, modifier = Modifier.padding(top = 4.dp))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            colors = listOf(GoldPrimary.copy(alpha = 0.4f), RoyalBlue.copy(alpha = 0.3f), GoldPrimary.copy(alpha = 0.4f))
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.logo_ued),
                                contentDescription = "UED Logo",
                                modifier = Modifier
                                    .size(48.dp)
                                    .shadow(4.dp, CircleShape)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("Nhóm 5 UED", style = MaterialTheme.typography.titleMedium, color = GoldPrimary, fontWeight = FontWeight.Bold)
                                Text("Universal TV Remote v1.0", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Universal TV Remote supporting Samsung, LG, Sony, Roku, Android TV, Vizio, Hisense VIDAA, TCL, Xiaomi, Panasonic, Fire TV and more. Features: auto-reconnect, touchpad, app launcher, media cast, and Wake-on-LAN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SettingsActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    buttonLabel: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tint.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = tint.copy(alpha = 0.15f), contentColor = tint),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(buttonLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun SavedDeviceCard(
    device: com.ued.universaltvremote.model.TvDevice,
    onConnect: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.1f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Tv, null, tint = CyanAccent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Text(device.ip, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorRed.copy(alpha = 0.7f))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = OnSurfaceMuted
                )
            }

            if (expanded) {
                Divider(color = OnSurfaceMuted.copy(alpha = 0.1f))
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsInfoRow("IP Address", device.ip)
                    if (device.macAddress.isNotBlank()) SettingsInfoRow("MAC Address", device.macAddress)
                    SettingsInfoRow("Port", device.port.toString())
                    if (device.modelYear.isNotBlank()) SettingsInfoRow("Model Year", device.modelYear)
                    
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = NavyBackground)
                    ) {
                        Text("Connect", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
