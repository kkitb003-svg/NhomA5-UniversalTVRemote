package com.ued.universaltvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ued.universaltvremote.model.TvDevice
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.ui.*
import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewModel

// ── Navigation ────────────────────────────────────────────────────────────────

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Remote   : Screen("remote",   "Remote",  Icons.Default.LiveTv)
    object Apps     : Screen("apps",     "Apps",    Icons.Default.GridView)
    object Cast     : Screen("cast",     "Cast",    Icons.Default.Cast)
    object Mirror   : Screen("mirror",   "Mirror",  Icons.Default.ScreenShare)
    object Macro    : Screen("macro",    "Macro",   Icons.Default.QueueMusic)
    object Settings : Screen("settings", "More",    Icons.Default.Settings)
}

val navItems = listOf(
    Screen.Remote,
    Screen.Apps,
    Screen.Cast,
    Screen.Mirror,
    Screen.Macro,
    Screen.Settings
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UniversalTvRemoteTheme {
                TvRemoteApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvRemoteApp() {
    val viewModel: MainViewModel = viewModel()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val connectionState by viewModel.connectionState.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Sheet state: expanded=full scan page, collapsed=slim bar
    var sheetExpanded by remember { mutableStateOf(true) }



    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        containerColor = NavyBackground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            colors = listOf(GoldPrimary.copy(alpha = 0.6f), RoyalBlue.copy(alpha = 0.4f), GoldPrimary.copy(alpha = 0.6f))
                        )
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.horizontalGradient(colors = listOf(SurfaceDark, SurfaceVariantDark, SurfaceDark)))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(data.visuals.message, color = OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        data.visuals.actionLabel?.let { actionLabel ->
                            TextButton(onClick = { data.performAction() }) {
                                Text(actionLabel, color = GoldPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            // ── Bottom Navigation Bar only ────────────────────────────────
            NavigationBar(
                containerColor = SurfaceDark,
                modifier = Modifier.shadow(8.dp)
            ) {
                navItems.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (sheetExpanded) sheetExpanded = false
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (screen in listOf(Screen.Remote, Screen.Apps) &&
                                        connectionState is ConnectionState.Connected && !selected
                                    ) { Badge(containerColor = SuccessGreen, modifier = Modifier.size(6.dp)) {} }
                                }
                            ) { Icon(screen.icon, screen.label) }
                        },
                        label = { Text(screen.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GoldPrimary,
                            selectedTextColor = GoldPrimary,
                            indicatorColor = GoldPrimary.copy(alpha = 0.12f),
                            unselectedIconColor = OnSurfaceMuted,
                            unselectedTextColor = OnSurfaceMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        // ── Outer Box: NavHost behind, ScanSheet overlay on top ──────────
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Collapsed scan bar always at TOP ──────────────────────
                CollapsedBar(
                    connectionState = connectionState,
                    expanded = sheetExpanded,
                    onToggle = { sheetExpanded = !sheetExpanded }
                )
                // ── Tab content below ─────────────────────────────────────
                NavHost(
                    navController = navController,
                    startDestination = Screen.Remote.route,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(Screen.Remote.route)   { RemoteScreen(viewModel = viewModel) }
                    composable(Screen.Apps.route)     { AppsScreen(viewModel = viewModel) }
                    composable(Screen.Cast.route)     { CastScreen(viewModel = viewModel) }
                    composable(Screen.Mirror.route)   { MirrorScreen(viewModel = viewModel) }
                    composable(Screen.Macro.route)    { MacroScreen(viewModel = viewModel) }
                    composable(Screen.Settings.route) { SettingsScreen(viewModel = viewModel) }
                }
            }

            // ── Expanded scan sheet OVERLAYS content (like a modal bottom sheet from top) ──
            AnimatedVisibility(
                visible = sheetExpanded,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.Top
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ScanSheetContent(
                    viewModel = viewModel,
                    onClose = { sheetExpanded = false },
                    connectionState = connectionState
                )
            }
        }
    }
}


// ── ScanSheetContent: full scan UI shown as overlay ──────────────────────────

@Composable
fun ScanSheetContent(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    connectionState: ConnectionState
) {
    // We add swipe-to-dismiss logic (swipe UP to close)
    var dragOffset by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.96f)  // Takes 96% of screen height
            .graphicsLayer { translationY = dragOffset }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffset < -150f) onClose()
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                    onVerticalDrag = { change, dragAmount -> // swipe up = negative dragAmount
                        if (dragAmount < 0 || dragOffset < 0) {
                            dragOffset += dragAmount
                            change.consume()
                        }
                    }
                )
            }
            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .background(NavyBackground)
            .border(
                1.dp,
                GoldPrimary.copy(alpha = 0.3f),
                RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                ScanScreen(viewModel = viewModel, onConnected = onClose)
            }
            
            // Bottom "swipe up / close" indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .clickable(onClick = onClose)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Close",
                    tint = GoldPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}



@Composable
fun CollapsedBar(
    connectionState: ConnectionState,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val connectedDevice = (connectionState as? ConnectionState.Connected)?.device

    val pulse = rememberInfiniteTransition(label = "bar_pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "dot"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(SurfaceDark)
            .border(
                1.dp,
                GoldPrimary.copy(alpha = 0.15f),
                RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Scan/connected icon
        Icon(
            imageVector = if (isConnected) Icons.Default.LiveTv else Icons.Default.Search,
            contentDescription = null,
            tint = if (isConnected) SuccessGreen else GoldPrimary,
            modifier = Modifier.size(18.dp)
        )

        // Status text
        Column(modifier = Modifier.weight(1f)) {
            if (isConnected && connectedDevice != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(SuccessGreen.copy(alpha = dotAlpha), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(connectedDevice.name, style = MaterialTheme.typography.bodyMedium, color = GoldLight, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(connectedDevice.ip, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
            } else {
                Text("Tap to scan for Smart TV", style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            }
        }

        // Arrow indicator (Down when collapsed, Up when expanded)
        val arrowRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
            label = "arrow_rot"
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = GoldPrimary.copy(alpha = 0.7f),
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = arrowRotation }
        )
    }
}

@Composable
fun ScanSheetHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // UED logo placeholder circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.radialGradient(colors = listOf(GoldPrimary.copy(alpha = 0.2f), Color.Transparent)),
                    CircleShape
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Tv, null, tint = GoldPrimary, modifier = Modifier.size(24.dp))
        }
        Column {
            Text("Universal TV Remote", style = MaterialTheme.typography.titleMedium, color = GoldPrimary, fontWeight = FontWeight.Bold)
            Text("Nhóm 5 UED — Find your Smart TV", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        }
    }
}

@Composable
fun InlineRadarSection(isScanning: Boolean, onScan: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "radar")
    val scale by pulse.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = if (isScanning) infiniteRepeatable(tween(900), RepeatMode.Reverse) else infiniteRepeatable(tween(999999)),
        label = "radar_scale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = if (isScanning) infiniteRepeatable(tween(900), RepeatMode.Reverse) else infiniteRepeatable(tween(999999)),
        label = "radar_alpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Status
        Column {
            Text(
                text = if (isScanning) "Scanning network…" else "Tap to scan",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isScanning) GoldPrimary else OnSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (isScanning) {
                Text("Searching for Smart TVs on Wi-Fi", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
        }
        // Scan button
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer { scaleX = if (isScanning) scale else 1f; scaleY = if (isScanning) scale else 1f }
                .shadow(8.dp, CircleShape)
                .background(
                    Brush.radialGradient(colors = listOf(GoldPrimary.copy(alpha = if (isScanning) alpha * 0.4f else 1f), GoldDark)),
                    CircleShape
                )
                .pointerInput(Unit) { detectTapGestures(onTap = { if (!isScanning) onScan() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Search, null, tint = NavyBackground, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun FoundDeviceRow(device: TvDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(colors = listOf(SurfaceVariantDark, SurfaceDark)),
                    RoundedCornerShape(12.dp)
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(GoldPrimary.copy(alpha = 0.1f), CircleShape)
                    .border(1.dp, GoldPrimary.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LiveTv, null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Text("${device.ip}  •  ${device.macAddress}", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
            }
            Icon(Icons.Default.ChevronRight, null, tint = GoldPrimary.copy(alpha = 0.5f))
        }
    }
}