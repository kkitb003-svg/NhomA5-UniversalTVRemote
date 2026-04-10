package com.ued.universaltvremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.network.ScreenMirrorServer
import com.ued.universaltvremote.network.ScreenMirrorService
import com.ued.universaltvremote.ui.theme.CyanAccent
import com.ued.universaltvremote.ui.theme.ErrorRed
import com.ued.universaltvremote.ui.theme.NavyBackground
import com.ued.universaltvremote.ui.theme.OnSurfaceMuted
import com.ued.universaltvremote.ui.theme.SuccessGreen
import com.ued.universaltvremote.ui.theme.SurfaceDark
import com.ued.universaltvremote.ui.theme.SurfaceVariantDark
import com.ued.universaltvremote.util.LocalNetworkAddressResolver
import com.ued.universaltvremote.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MirrorScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val device = (connectionState as? ConnectionState.Connected)?.device

    var mirrorServer by remember { mutableStateOf<ScreenMirrorServer?>(null) }
    var isMirroring by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var isStarting by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mirrorServer?.stop()
            ScreenMirrorService.stop(context)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isStarting = false
        errorMessage = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            scope.launch {
                try {
                    // Start foreground service first
                    ScreenMirrorService.start(context)
                    waitForForegroundMirrorService()

                    // Create and start mirror server
                    val server = ScreenMirrorServer()
                    mirrorServer = server
                    server.startProjection(result.resultCode, result.data!!, context)

                    val localIp = LocalNetworkAddressResolver.getPreferredIpv4Address(
                        context = context,
                        remoteIp = device?.ip
                    ) ?: throw IllegalStateException("Could not resolve phone's Wi-Fi IP address")

                    streamUrl = "http://$localIp:8889/viewer"
                    isMirroring = true
                    val opened = viewModel.openUrlOnTv(streamUrl)
                    if (!opened) {
                        viewModel.showSnackbar("Mirror server started. Open browser on TV at: $streamUrl")
                    } else {
                        viewModel.showSnackbar("Screen mirroring started")
                    }
                } catch (e: SecurityException) {
                    errorMessage = "Screen capture permission was denied. Please allow screen capture to use mirroring."
                    viewModel.showSnackbar("Screen capture permission denied")
                    mirrorServer?.stop()
                    ScreenMirrorService.stop(context)
                } catch (e: IllegalStateException) {
                    errorMessage = e.message ?: "Failed to start mirroring"
                    viewModel.showSnackbar("Mirror error: ${e.message}")
                    mirrorServer?.stop()
                    ScreenMirrorService.stop(context)
                } catch (e: Exception) {
                    errorMessage = "Mirror error: ${e.message}"
                    viewModel.showSnackbar("Failed to start screen mirroring: ${e.message}")
                    mirrorServer?.stop()
                    ScreenMirrorService.stop(context)
                }
            }
        } else {
            // User cancelled
            ScreenMirrorService.stop(context)
            viewModel.showSnackbar("Screen capture cancelled")
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
                Text(
                    "Screen Mirror",
                    style = MaterialTheme.typography.titleLarge,
                    color = CyanAccent,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Info,
                    "Help",
                    tint = CyanAccent.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }

            if (!isConnected) {
                NotConnectedPlaceholder(subtitle = "Connect to a TV to start screen mirroring")
                return@Column
            }

            Spacer(Modifier.height(8.dp))

            // Error message card
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = ErrorRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Mirror instructions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "How it works:",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "1. Tap 'Start Mirroring' - grant screen capture permission",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                    Text(
                        "2. The TV browser opens and displays your screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                    Text(
                        "3. Both devices must be on the same Wi-Fi network",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isMirroring) SuccessGreen.copy(0.5f) else CyanAccent.copy(0.2f)
                )
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                if (isMirroring) SuccessGreen.copy(0.1f) else CyanAccent.copy(0.1f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isMirroring) Icons.Default.ScreenShare else Icons.Default.DesktopWindows,
                            null,
                            tint = if (isMirroring) SuccessGreen else CyanAccent,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        if (isMirroring) "Mirroring active" else "Ready to mirror",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(4.dp))

                    if (isMirroring) {
                        Text(
                            "Stream: $streamUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "Stream your phone screen to the TV browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    if (!isMirroring) {
                        Button(
                            onClick = {
                                errorMessage = null
                                isStarting = true
                                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            },
                            enabled = !isStarting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanAccent,
                                contentColor = NavyBackground
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isStarting) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = NavyBackground,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.ScreenShare, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (isStarting) "Starting..." else "Start Mirroring",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                mirrorServer?.stop()
                                ScreenMirrorService.stop(context)
                                isMirroring = false
                                streamUrl = ""
                                viewModel.showSnackbar("Mirroring stopped")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ErrorRed.copy(alpha = 0.15f),
                                contentColor = ErrorRed
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stop Mirroring")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark.copy(alpha = 0.5f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Specifications", style = MaterialTheme.typography.titleSmall, color = CyanAccent)
                    Spacer(Modifier.height(8.dp))
                    SpecRow("Resolution", "Adaptive up to 1280px")
                    SpecRow("FPS", "~15 fps")
                    SpecRow("Format", "MJPEG over HTTP")
                    SpecRow("Port", "8889")
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

private suspend fun waitForForegroundMirrorService() {
    repeat(30) {
        if (ScreenMirrorService.isRunning) return
        delay(100L)
    }
    throw IllegalStateException("Foreground mirror service did not start in time")
}
