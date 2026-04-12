package com.ued.universaltvremote.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.network.LocalMediaServer
import com.ued.universaltvremote.ui.theme.CyanAccent
import com.ued.universaltvremote.ui.theme.ErrorRed
import com.ued.universaltvremote.ui.theme.NavyBackground
import com.ued.universaltvremote.ui.theme.OnSurfaceMuted
import com.ued.universaltvremote.ui.theme.SuccessGreen
import com.ued.universaltvremote.ui.theme.SurfaceDark
import com.ued.universaltvremote.ui.theme.SurfaceVariantDark
import com.ued.universaltvremote.util.LocalNetworkAddressResolver
import com.ued.universaltvremote.viewmodel.MainViewModel

@Composable
fun CastScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    val device = (connectionState as? ConnectionState.Connected)?.device

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedMimeType by remember { mutableStateOf("") }
    var isCasting by remember { mutableStateOf(false) }
    var castUrl by remember { mutableStateOf("") }
    var showCastHelp by remember { mutableStateOf(false) }

    val mediaServer = remember { LocalMediaServer(context.contentResolver) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedMimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            selectedFileName = getFileName(context, uri) ?: "media_file"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaServer.stop()
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
                    "Media Cast",
                    style = MaterialTheme.typography.titleLarge,
                    color = CyanAccent,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showCastHelp = !showCastHelp }) {
                    Icon(Icons.Default.Info, "Help", tint = CyanAccent.copy(alpha = 0.7f))
                }
                if (isCasting) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(SuccessGreen, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Casting", style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                }
            }

            if (showCastHelp) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("How to cast:", style = MaterialTheme.typography.labelMedium, color = CyanAccent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("1. Select a photo or video from your phone", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        Text("2. Tap 'Cast to TV' - the TV browser will open", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        Text("3. The TV must be on the same Wi-Fi network", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        Spacer(Modifier.height(4.dp))
                        Text("Note: Some TV brands may not support browser-based casting.", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted.copy(alpha = 0.7f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (!isConnected) {
                NotConnectedPlaceholder(subtitle = "Connect to a TV to cast media")
                return@Column
            }

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.2f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("1. Select Media File", style = MaterialTheme.typography.titleSmall, color = CyanAccent)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { mediaPicker.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                        ) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Image")
                        }
                        Button(
                            onClick = { mediaPicker.launch("video/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                        ) {
                            Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Video")
                        }
                    }
                    if (selectedUri != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (selectedMimeType.startsWith("video")) Icons.Default.Videocam else Icons.Default.Image,
                                null,
                                tint = CyanAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    selectedFileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    selectedMimeType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceMuted
                                )
                            }
                            IconButton(
                                onClick = {
                                    selectedUri = null
                                    selectedFileName = ""
                                    if (isCasting) {
                                        mediaServer.stop()
                                        isCasting = false
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Close, "Remove", tint = ErrorRed)
                            }
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
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.2f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("2. Cast to TV", style = MaterialTheme.typography.titleSmall, color = CyanAccent)
                    Spacer(Modifier.height(12.dp))
                    if (!isCasting) {
                        Button(
                            onClick = {
                                val uri = selectedUri ?: return@Button
                                val localIp = LocalNetworkAddressResolver.getPreferredIpv4Address(
                                    context = context,
                                    remoteIp = device?.ip
                                )
                                if (localIp == null) {
                                    viewModel.showSnackbar("Could not resolve the phone's Wi-Fi IP address")
                                    return@Button
                                }

                                mediaServer.setMedia(uri, selectedMimeType)
                                mediaServer.start()
                                // Preview URL that TV will open
                                castUrl = "http://$localIp:8888/preview"
                                isCasting = true

                                // Open preview page on TV via browser
                                val opened = viewModel.openUrlOnTv(castUrl)
                                if (!opened) {
                                    // Try alternative browser opening
                                    viewModel.openUrlOnTv("http://$localIp:8888/")
                                }
                                viewModel.showSnackbar("Casting media to TV...")
                            },
                            enabled = selectedUri != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanAccent,
                                contentColor = NavyBackground
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Cast, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Cast to TV", fontWeight = FontWeight.Bold)
                        }
                        if (device != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Opens TV browser at: http://phone-ip:8888/preview",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(SuccessGreen, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Casting: $selectedFileName",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SuccessGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Stream URL: $castUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    mediaServer.stop()
                                    isCasting = false
                                    castUrl = ""
                                    viewModel.showSnackbar("Cast stopped")
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ErrorRed.copy(alpha = 0.15f),
                                    contentColor = ErrorRed
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Stop Cast")
                            }
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
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = CyanAccent.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "The app opens the TV browser and streams the selected media from your phone. " +
                            "Both devices must stay on the same Wi-Fi network while casting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}
