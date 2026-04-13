package com.ued.universaltvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewMo


import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.network.ConnectionState
del

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val device = (connectionState as? ConnectionState.Connected)?.device
    var lastDeviceIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isConnected, device?.ip) {
        if (isConnected && device != null) {
            // Refresh apps when connecting to a (new) device
            if (lastDeviceIp != device.ip) {
                lastDeviceIp = device.ip
                viewModel.clearInstalledApps()
                viewModel.fetchApps()
            }
        } else {
            lastDeviceIp = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Apps", style = MaterialTheme.typography.titleLarge, color = CyanAccent, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        viewModel.clearInstalledApps()
                        viewModel.fetchApps()
                    },
                    modifier = Modifier.background(SurfaceVariantDark, CircleShape)
                ) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = CyanAccent)
                }
            }

            if (!isConnected) {
                NotConnectedPlaceholder(subtitle = "Connect to a TV to see installed apps")
                return@Column
            }

            if (isLoadingApps) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CyanAccent)
                        Spacer(Modifier.height(12.dp))
                        Text("Loading apps from TV...", color = OnSurfaceMuted)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Please wait, this may take a few seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted.copy(alpha = 0.6f)
                        )
                    }
                }
                return@Column
            }

            if (installedApps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.GridView, null, tint = OnSurfaceMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No apps found", color = OnSurfaceMuted, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This TV may not support app listing,\nor apps may not be available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                viewModel.clearInstalledApps()
                                viewModel.fetchApps()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = NavyBackground)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
                return@Column
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(installedApps) { app ->
                    AppGridItem(
                        app = app,
                        iconUrl = device?.let { app.iconUrl(it.ip, it.port) },
                        onClick = { viewModel.launchApp(app.appId) }
                    )
                }
            }
        }
    }
}

@Composable
fun AppGridItem(app: TvApp, iconUrl: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(SurfaceDark)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(SurfaceVariantDark, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (iconUrl != null) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = app.name,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Icon(Icons.Default.GridView, null, tint = CyanAccent.copy(alpha = 0.3f), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
