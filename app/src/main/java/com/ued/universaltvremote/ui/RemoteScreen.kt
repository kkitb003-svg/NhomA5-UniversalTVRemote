package com.ued.universaltvremote.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.ui.theme.*
import com.ued.universaltvremote.viewmodel.MainViewModel
import kotlin.math.abs

// ── Mode toggle ──────────────────────────────────────────────────────────────

enum class RemoteMode { NUMPAD, TOUCHPAD }

@Composable
fun RemoteScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected
    var mode by remember { mutableStateOf(RemoteMode.NUMPAD) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(NavyBackground, SurfaceDark, NavyBackground)
                )
            )
    ) {
        if (!isConnected) {
            NotConnectedPlaceholder()
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── FIXED TOP: always visible controls ───────────────────────────

            // Row 1: Power, Home, Back, Source, Menu
            GlassCard {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                ) {
                    EmbossedIconButton(icon = Icons.Default.PowerSettingsNew, label = "Power", tint = AccentRed) { viewModel.sendKey("KEY_POWER") }
                    EmbossedIconButton(icon = Icons.Default.Home, label = "Home", tint = GoldPrimary) { viewModel.sendKey("KEY_HOME") }
                    EmbossedIconButton(icon = Icons.Default.ArrowBack, label = "Back", tint = GoldPrimary) { viewModel.sendKey("KEY_RETURN") }
                    EmbossedIconButton(icon = Icons.Default.Sensors, label = "Source", tint = RoyalBlueLight) { viewModel.sendKey("KEY_SOURCE") }
                    EmbossedIconButton(icon = Icons.Default.Menu, label = "Menu", tint = RoyalBlueLight) { viewModel.sendKey("KEY_MENU") }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: Volume + D-Pad + Channel (always visible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmbossedSmallButton("+", GoldPrimary) { viewModel.sendKey("KEY_VOLUP") }
                    Spacer(Modifier.height(4.dp))
                    Text("VOL", style = MaterialTheme.typography.labelSmall, color = GoldPrimary.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    EmbossedSmallButton("−", GoldPrimary) { viewModel.sendKey("KEY_VOLDOWN") }
                    Spacer(Modifier.height(8.dp))
                    EmbossedIconButton(icon = Icons.Default.VolumeMute, label = "Mute", size = 44.dp, tint = AccentRed) { viewModel.sendKey("KEY_MUTE") }
                }
                DPad(viewModel)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmbossedSmallButton("▲", RoyalBlueLight) { viewModel.sendKey("KEY_CHUP") }
                    Spacer(Modifier.height(4.dp))
                    Text("CH", style = MaterialTheme.typography.labelSmall, color = RoyalBlueLight.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    EmbossedSmallButton("▼", RoyalBlueLight) { viewModel.sendKey("KEY_CHDOWN") }
                    Spacer(Modifier.height(8.dp))
                    EmbossedIconButton(icon = Icons.Default.Info, label = "Info", size = 44.dp, tint = OnSurfaceMuted) { viewModel.sendKey("KEY_INFO") }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Row 3: Color keys (always visible)
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                ColorButton("A", Color(0xFFE53935)) { viewModel.sendKey("KEY_RED") }
                ColorButton("B", Color(0xFF43A047)) { viewModel.sendKey("KEY_GREEN") }
                ColorButton("C", Color(0xFFFDD835)) { viewModel.sendKey("KEY_YELLOW") }
                ColorButton("D", Color(0xFF1E88E5)) { viewModel.sendKey("KEY_BLUE") }
            }

            Spacer(Modifier.height(8.dp))

            // ── BOTTOM: Toggle + swappable content ───────────────────────────
            // Separator
            HorizontalDivider(color = GoldPrimary.copy(alpha = 0.12f), thickness = 1.dp)
            Spacer(Modifier.height(6.dp))

            // Toggle in the CENTER at the top of the bottom section
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ModeToggle(current = mode, onToggle = { mode = it })
            }

            Spacer(Modifier.height(6.dp))

            // Swappable bottom section — only this part animates
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (slideInHorizontally(
                        animationSpec = tween(280),
                        initialOffsetX = { if (targetState == RemoteMode.NUMPAD) -it else it }
                    ) + fadeIn(tween(200))).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(280),
                            targetOffsetX = { if (targetState == RemoteMode.NUMPAD) it else -it }
                        ) + fadeOut(tween(180))
                    )
                },
                label = "bottom_mode",
                modifier = Modifier.weight(1f)
            ) { currentMode ->
                when (currentMode) {
                    RemoteMode.NUMPAD -> BottomNumpadContent(viewModel)
                    RemoteMode.TOUCHPAD -> BottomTouchpadContent(viewModel)
                }
            }
        }
    }
}

// ── Bottom numpad section only ────────────────────────────────────────────────

@Composable
fun BottomNumpadContent(viewModel: MainViewModel) {
    GlassCard {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            NumberPad(viewModel)
        }
    }
}

// ── Bottom touchpad section only ──────────────────────────────────────────────

@Composable
fun BottomTouchpadContent(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Keyboard toggle row
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "• Drag = navigate  • Tap = OK  • Double = Back",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showKeyboard = !showKeyboard },
                modifier = Modifier.background(
                    if (showKeyboard) GoldPrimary.copy(alpha = 0.2f) else SurfaceVariantDark,
                    CircleShape
                )
            ) {
                Icon(Icons.Default.KeyboardAlt, "Keyboard", tint = GoldPrimary)
            }
        }

        // Keyboard input
        AnimatedVisibility(visible = showKeyboard) {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type to send to TV…", color = OnSurfaceMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            cursorColor = GoldPrimary,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (keyboardText.isNotBlank()) { viewModel.sendText(keyboardText); keyboardText = "" }
                    }) {
                        Icon(Icons.Default.Send, "Send", tint = GoldPrimary)
                    }
                }
            }
        }

        // Touchpad area
        var accX by remember { mutableStateOf(0f) }
        var accY by remember { mutableStateOf(0f) }
        val threshold = 60f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    Brush.radialGradient(colors = listOf(SurfaceVariantDark, SurfaceDark)),
                    RoundedCornerShape(20.dp)
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_ENTER") },
                        onDoubleTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_RETURN") },
                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_MENU") }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { _, d ->
                        accX += d.x; accY += d.y
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
                Icon(Icons.Default.PanTool, null, tint = GoldPrimary.copy(alpha = 0.2f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(6.dp))
                Text("Slide to navigate", color = OnSurfaceMuted.copy(alpha = 0.4f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}



@Composable
fun ModeToggle(current: RemoteMode, onToggle: (RemoteMode) -> Unit) {
    Row(
        modifier = Modifier
            .background(SurfaceDark, RoundedCornerShape(50))
            .border(1.dp, GoldPrimary.copy(alpha = 0.2f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        ModeTab(
            label = "Numpad",
            icon = Icons.Default.GridView,
            selected = current == RemoteMode.NUMPAD,
            onClick = { onToggle(RemoteMode.NUMPAD) }
        )
        Spacer(Modifier.width(2.dp))
        ModeTab(
            label = "Touchpad",
            icon = Icons.Default.PanTool,
            selected = current == RemoteMode.TOUCHPAD,
            onClick = { onToggle(RemoteMode.TOUCHPAD) }
        )
    }
}

@Composable
fun ModeTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) GoldPrimary else Color.Transparent,
        animationSpec = tween(250),
        label = "tab_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) NavyBackground else OnSurfaceMuted,
        animationSpec = tween(250),
        label = "tab_text"
    )
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bgColor, contentColor = textColor),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

// ── NUMPAD mode ──────────────────────────────────────────────────────────────

@Composable
fun NumpadModeContent(viewModel: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1: Power, Home, Back, Source, Menu
        GlassCard {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                EmbossedIconButton(icon = Icons.Default.PowerSettingsNew, label = "Power", tint = AccentRed) { viewModel.sendKey("KEY_POWER") }
                EmbossedIconButton(icon = Icons.Default.Home, label = "Home", tint = GoldPrimary) { viewModel.sendKey("KEY_HOME") }
                EmbossedIconButton(icon = Icons.Default.ArrowBack, label = "Back", tint = GoldPrimary) { viewModel.sendKey("KEY_RETURN") }
                EmbossedIconButton(icon = Icons.Default.Sensors, label = "Source", tint = RoyalBlueLight) { viewModel.sendKey("KEY_SOURCE") }
                EmbossedIconButton(icon = Icons.Default.Menu, label = "Menu", tint = RoyalBlueLight) { viewModel.sendKey("KEY_MENU") }
            }
        }

        // Volume + D-Pad + Channel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmbossedSmallButton("+", GoldPrimary) { viewModel.sendKey("KEY_VOLUP") }
                Spacer(Modifier.height(4.dp))
                Text("VOL", style = MaterialTheme.typography.labelSmall, color = GoldPrimary.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                EmbossedSmallButton("−", GoldPrimary) { viewModel.sendKey("KEY_VOLDOWN") }
                Spacer(Modifier.height(8.dp))
                EmbossedIconButton(icon = Icons.Default.VolumeMute, label = "Mute", size = 44.dp, tint = AccentRed) { viewModel.sendKey("KEY_MUTE") }
            }
            DPad(viewModel)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmbossedSmallButton("▲", RoyalBlueLight) { viewModel.sendKey("KEY_CHUP") }
                Spacer(Modifier.height(4.dp))
                Text("CH", style = MaterialTheme.typography.labelSmall, color = RoyalBlueLight.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                EmbossedSmallButton("▼", RoyalBlueLight) { viewModel.sendKey("KEY_CHDOWN") }
                Spacer(Modifier.height(8.dp))
                EmbossedIconButton(icon = Icons.Default.Info, label = "Info", size = 44.dp, tint = OnSurfaceMuted) { viewModel.sendKey("KEY_INFO") }
            }
        }

        // Color keys
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ColorButton("A", Color(0xFFE53935)) { viewModel.sendKey("KEY_RED") }
            ColorButton("B", Color(0xFF43A047)) { viewModel.sendKey("KEY_GREEN") }
            ColorButton("C", Color(0xFFFDD835)) { viewModel.sendKey("KEY_YELLOW") }
            ColorButton("D", Color(0xFF1E88E5)) { viewModel.sendKey("KEY_BLUE") }
        }

        // Number pad
        GlassCard {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                NumberPad(viewModel)
            }
        }
    }
}

// ── TOUCHPAD mode ─────────────────────────────────────────────────────────────

@Composable
fun TouchpadModeContent(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top bar for keyboard toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "• Drag to navigate  • Tap = OK  • Double-tap = Back",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showKeyboard = !showKeyboard },
                modifier = Modifier.background(
                    if (showKeyboard) GoldPrimary.copy(alpha = 0.2f) else SurfaceVariantDark,
                    CircleShape
                )
            ) {
                Icon(Icons.Default.KeyboardAlt, "Keyboard", tint = GoldPrimary)
            }
        }

        // Keyboard input
        AnimatedVisibility(visible = showKeyboard) {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type to send to TV…", color = OnSurfaceMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            cursorColor = GoldPrimary,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (keyboardText.isNotBlank()) {
                            viewModel.sendText(keyboardText)
                            keyboardText = ""
                        }
                    }) {
                        Icon(Icons.Default.Send, "Send", tint = GoldPrimary)
                    }
                }
            }
        }

        // Main touchpad
        var accX by remember { mutableStateOf(0f) }
        var accY by remember { mutableStateOf(0f) }
        val threshold = 60f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SurfaceVariantDark, SurfaceDark)
                    ),
                    RoundedCornerShape(24.dp)
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_ENTER") },
                        onDoubleTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_RETURN") },
                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_MENU") }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { _, d ->
                        accX += d.x; accY += d.y
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
                Icon(Icons.Default.PanTool, null, tint = GoldPrimary.copy(alpha = 0.2f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Slide to navigate", color = OnSurfaceMuted.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Action buttons
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EmbossedIconButton(Icons.Default.ArrowBack, "Back") { viewModel.sendKey("KEY_RETURN") }
                EmbossedIconButton(Icons.Default.Home, "Home") { viewModel.sendKey("KEY_HOME") }
                EmbossedIconButton(Icons.Default.Menu, "Menu") { viewModel.sendKey("KEY_MENU") }
                EmbossedIconButton(Icons.Default.PlayArrow, "Play") { viewModel.sendKey("KEY_PLAY") }
                EmbossedIconButton(Icons.Default.PauseCircle, "Pause") { viewModel.sendKey("KEY_PAUSE") }
            }
        }
    }
}

// ── Shared widgets ─────────────────────────────────────────────────────────────

@Composable
fun ConnectedDeviceHeader(name: String, ip: String) {
    val pulse = rememberInfiniteTransition(label = "header_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse_alpha"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(SurfaceDark, SurfaceVariantDark, SurfaceDark)
                    )
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).background(SuccessGreen.copy(alpha = pulseAlpha), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = GoldLight, fontWeight = FontWeight.Bold)
                Text(ip, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
            Icon(Icons.Default.LiveTv, null, tint = GoldPrimary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceVariantDark.copy(alpha = 0.9f),
                            SurfaceDark.copy(alpha = 0.95f)
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
        ) {
            Column(content = content)
        }
    }
}

/**
 * Embossed (3D raised) icon button — gives physical depth.
 * Uses layered shadows and gradient for a tactile effect.
 */
@Composable
fun EmbossedIconButton(
    icon: ImageVector,
    label: String,
    tint: Color = GoldPrimary,
    size: Dp = 48.dp,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btn_scale"
    )
    val shadowElev by animateDpAsState(
        targetValue = if (pressed) 1.dp else 6.dp,
        animationSpec = tween(80),
        label = "btn_shadow"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .scale(scale)
                .shadow(shadowElev, CircleShape)
                .size(size)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceVariantDark.copy(alpha = 0.95f),
                            SurfaceDark
                        )
                    ),
                    CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(tint.copy(alpha = 0.5f), tint.copy(alpha = 0.1f))
                    ),
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            tryAwaitRelease()
                            pressed = false
                            onClick()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size((size.value * 0.44f).dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
fun EmbossedSmallButton(label: String, accentColor: Color = GoldPrimary, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "small_btn_scale"
    )
    val shadowElev by animateDpAsState(
        targetValue = if (pressed) 1.dp else 5.dp,
        animationSpec = tween(80),
        label = "small_btn_shadow"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .shadow(shadowElev, CircleShape)
            .size(44.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceVariantDark, SurfaceDark)
                ),
                CircleShape
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.45f), accentColor.copy(alpha = 0.05f))
                ),
                CircleShape
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DPad(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    Brush.radialGradient(colors = listOf(SurfaceVariantDark, SurfaceDark)),
                    CircleShape
                )
                .border(
                    2.dp,
                    Brush.sweepGradient(
                        colors = listOf(
                            GoldPrimary.copy(alpha = 0.6f),
                            RoyalBlue.copy(alpha = 0.3f),
                            GoldPrimary.copy(alpha = 0.1f),
                            RoyalBlue.copy(alpha = 0.3f),
                            GoldPrimary.copy(alpha = 0.6f)
                        )
                    ),
                    CircleShape
                )
                .shadow(6.dp, CircleShape)
        )
        RemoteDPadButton(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), Icons.Default.KeyboardArrowUp) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_UP")
        }
        RemoteDPadButton(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), Icons.Default.KeyboardArrowDown) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_DOWN")
        }
        RemoteDPadButton(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), Icons.Default.KeyboardArrowLeft) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_LEFT")
        }
        RemoteDPadButton(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), Icons.Default.KeyboardArrowRight) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_RIGHT")
        }
        // OK center
        var okPressed by remember { mutableStateOf(false) }
        val okScale by animateFloatAsState(
            targetValue = if (okPressed) 0.88f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "ok_scale"
        )
        val okShadow by animateDpAsState(
            targetValue = if (okPressed) 2.dp else 10.dp,
            animationSpec = tween(100),
            label = "ok_shadow"
        )
        
        Box(
            modifier = Modifier
                .scale(okScale)
                .size(64.dp)
                .shadow(okShadow, CircleShape)
                .background(
                    Brush.verticalGradient(colors = listOf(GoldLight, GoldPrimary)),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        okPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        okPressed = false
                        viewModel.sendKey("KEY_ENTER")
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text("OK", fontWeight = FontWeight.Black, fontSize = 16.sp, color = NavyBackground)
        }
    }
}

@Composable
fun RemoteDPadButton(modifier: Modifier, icon: ImageVector, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dpad_scale"
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .size(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                    onClick()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = GoldLight.copy(alpha = if (pressed) 0.5f else 0.9f), modifier = Modifier.size(32.dp))
    }
}

// Keep old names as aliases for backwards compatibility in other screens
@Composable
fun RemoteIconButton(icon: ImageVector, label: String, tint: Color = GoldPrimary, size: Dp = 48.dp, onClick: () -> Unit) {
    EmbossedIconButton(icon = icon, label = label, tint = tint, size = size, onClick = onClick)
}

@Composable
fun RemoteSmallButton(label: String, accentColor: Color = GoldPrimary, onClick: () -> Unit) {
    EmbossedSmallButton(label = label, accentColor = accentColor, onClick = onClick)
}

@Composable
fun ColorButton(label: String, color: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "color_btn_scale"
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .shadow(if (pressed) 2.dp else 8.dp, CircleShape)
            .size(50.dp)
            .background(
                Brush.verticalGradient(colors = listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.7f))),
                CircleShape
            )
            .border(1.dp, color.copy(0.5f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    tryAwaitRelease()
                    pressed = false
                    onClick()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
fun NumberPad(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    val numbers = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
    val keyCodes = mapOf(
        "1" to "KEY_1","2" to "KEY_2","3" to "KEY_3",
        "4" to "KEY_4","5" to "KEY_5","6" to "KEY_6",
        "7" to "KEY_7","8" to "KEY_8","9" to "KEY_9",
        "0" to "KEY_0","⌫" to "KEY_DEL"
    )
    Column {
        numbers.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { num ->
                    if (num.isBlank()) {
                        Spacer(Modifier.size(50.dp))
                    } else {
                        val isBack = num == "⌫"
                        val accent = if (isBack) AccentRed else GoldLight
                        var pressed by remember { mutableStateOf(false) }
                        val scale by animateFloatAsState(
                            targetValue = if (pressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "num_scale_$num"
                        )
                        Box(
                            modifier = Modifier
                                .scale(scale)
                                .shadow(if (pressed) 1.dp else 4.dp, RoundedCornerShape(12.dp))
                                .size(50.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(SurfaceVariantDark.copy(alpha = 0.95f), SurfaceDark)
                                    ),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    Brush.verticalGradient(
                                        colors = listOf(accent.copy(alpha = 0.4f), accent.copy(alpha = 0.05f))
                                    ),
                                    RoundedCornerShape(12.dp)
                                )
                                .pointerInput(num) {
                                    detectTapGestures(onPress = {
                                        pressed = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        tryAwaitRelease()
                                        pressed = false
                                        keyCodes[num]?.let { viewModel.sendKey(it) }
                                    })
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(num, color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}
// ── TOUCHPAD mode ─────────────────────────────────────────────────────────────

@Composable
fun TouchpadModeContent(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top bar for keyboard toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "• Drag to navigate  • Tap = OK  • Double-tap = Back",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showKeyboard = !showKeyboard },
                modifier = Modifier.background(
                    if (showKeyboard) GoldPrimary.copy(alpha = 0.2f) else SurfaceVariantDark,
                    CircleShape
                )
            ) {
                Icon(Icons.Default.KeyboardAlt, "Keyboard", tint = GoldPrimary)
            }
        }

        // Keyboard input
        AnimatedVisibility(visible = showKeyboard) {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type to send to TV…", color = OnSurfaceMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            cursorColor = GoldPrimary,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (keyboardText.isNotBlank()) {
                            viewModel.sendText(keyboardText)
                            keyboardText = ""
                        }
                    }) {
                        Icon(Icons.Default.Send, "Send", tint = GoldPrimary)
                    }
                }
            }
        }

        // Main touchpad
        var accX by remember { mutableStateOf(0f) }
        var accY by remember { mutableStateOf(0f) }
        val threshold = 60f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(SurfaceVariantDark, SurfaceDark)
                    ),
                    RoundedCornerShape(24.dp)
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_ENTER") },
                        onDoubleTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_RETURN") },
                        onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_MENU") }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { _, d ->
                        accX += d.x; accY += d.y
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
                Icon(Icons.Default.PanTool, null, tint = GoldPrimary.copy(alpha = 0.2f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Slide to navigate", color = OnSurfaceMuted.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Action buttons
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EmbossedIconButton(Icons.Default.ArrowBack, "Back") { viewModel.sendKey("KEY_RETURN") }
                EmbossedIconButton(Icons.Default.Home, "Home") { viewModel.sendKey("KEY_HOME") }
                EmbossedIconButton(Icons.Default.Menu, "Menu") { viewModel.sendKey("KEY_MENU") }
                EmbossedIconButton(Icons.Default.PlayArrow, "Play") { viewModel.sendKey("KEY_PLAY") }
                EmbossedIconButton(Icons.Default.PauseCircle, "Pause") { viewModel.sendKey("KEY_PAUSE") }
            }
        }
    }
}

// ── Shared widgets ─────────────────────────────────────────────────────────────

@Composable
fun ConnectedDeviceHeader(name: String, ip: String) {
    val pulse = rememberInfiniteTransition(label = "header_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse_alpha"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(SurfaceDark, SurfaceVariantDark, SurfaceDark)
                    )
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).background(SuccessGreen.copy(alpha = pulseAlpha), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = GoldLight, fontWeight = FontWeight.Bold)
                Text(ip, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
            Icon(Icons.Default.LiveTv, null, tint = GoldPrimary.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceVariantDark.copy(alpha = 0.9f),
                            SurfaceDark.copy(alpha = 0.95f)
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .border(1.dp, GoldPrimary.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
        ) {
            Column(content = content)
        }
    }
}

/**
 * Embossed (3D raised) icon button — gives physical depth.
 * Uses layered shadows and gradient for a tactile effect.
 */
@Composable
fun EmbossedIconButton(
    icon: ImageVector,
    label: String,
    tint: Color = GoldPrimary,
    size: Dp = 48.dp,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btn_scale"
    )
    val shadowElev by animateDpAsState(
        targetValue = if (pressed) 1.dp else 6.dp,
        animationSpec = tween(80),
        label = "btn_shadow"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .scale(scale)
                .shadow(shadowElev, CircleShape)
                .size(size)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceVariantDark.copy(alpha = 0.95f),
                            SurfaceDark
                        )
                    ),
                    CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(tint.copy(alpha = 0.5f), tint.copy(alpha = 0.1f))
                    ),
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            tryAwaitRelease()
                            pressed = false
                            onClick()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size((size.value * 0.44f).dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
fun EmbossedSmallButton(label: String, accentColor: Color = GoldPrimary, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "small_btn_scale"
    )
    val shadowElev by animateDpAsState(
        targetValue = if (pressed) 1.dp else 5.dp,
        animationSpec = tween(80),
        label = "small_btn_shadow"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .shadow(shadowElev, CircleShape)
            .size(44.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceVariantDark, SurfaceDark)
                ),
                CircleShape
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.45f), accentColor.copy(alpha = 0.05f))
                ),
                CircleShape
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DPad(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    Brush.radialGradient(colors = listOf(SurfaceVariantDark, SurfaceDark)),
                    CircleShape
                )
                .border(
                    2.dp,
                    Brush.sweepGradient(
                        colors = listOf(
                            GoldPrimary.copy(alpha = 0.6f),
                            RoyalBlue.copy(alpha = 0.3f),
                            GoldPrimary.copy(alpha = 0.1f),
                            RoyalBlue.copy(alpha = 0.3f),
                            GoldPrimary.copy(alpha = 0.6f)
                        )
                    ),
                    CircleShape
                )
                .shadow(6.dp, CircleShape)
        )
        RemoteDPadButton(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), Icons.Default.KeyboardArrowUp) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_UP")
        }
        RemoteDPadButton(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), Icons.Default.KeyboardArrowDown) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_DOWN")
        }
        RemoteDPadButton(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), Icons.Default.KeyboardArrowLeft) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_LEFT")
        }
        RemoteDPadButton(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), Icons.Default.KeyboardArrowRight) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendKey("KEY_RIGHT")
        }
        // OK center
        var okPressed by remember { mutableStateOf(false) }
        val okScale by animateFloatAsState(
            targetValue = if (okPressed) 0.88f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "ok_scale"
        )
        val okShadow by animateDpAsState(
            targetValue = if (okPressed) 2.dp else 10.dp,
            animationSpec = tween(100),
            label = "ok_shadow"
        )
        
        Box(
            modifier = Modifier
                .scale(okScale)
                .size(64.dp)
                .shadow(okShadow, CircleShape)
                .background(
                    Brush.verticalGradient(colors = listOf(GoldLight, GoldPrimary)),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        okPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        okPressed = false
                        viewModel.sendKey("KEY_ENTER")
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text("OK", fontWeight = FontWeight.Black, fontSize = 16.sp, color = NavyBackground)
        }
    }
}

@Composable
fun RemoteDPadButton(modifier: Modifier, icon: ImageVector, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dpad_scale"
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .size(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                    onClick()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = GoldLight.copy(alpha = if (pressed) 0.5f else 0.9f), modifier = Modifier.size(32.dp))
    }
}

// Keep old names as aliases for backwards compatibility in other screens
@Composable
fun RemoteIconButton(icon: ImageVector, label: String, tint: Color = GoldPrimary, size: Dp = 48.dp, onClick: () -> Unit) {
    EmbossedIconButton(icon = icon, label = label, tint = tint, size = size, onClick = onClick)
}

@Composable
fun RemoteSmallButton(label: String, accentColor: Color = GoldPrimary, onClick: () -> Unit) {
    EmbossedSmallButton(label = label, accentColor = accentColor, onClick = onClick)
}

@Composable
fun ColorButton(label: String, color: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "color_btn_scale"
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .shadow(if (pressed) 2.dp else 8.dp, CircleShape)
            .size(50.dp)
            .background(
                Brush.verticalGradient(colors = listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.7f))),
                CircleShape
            )
            .border(1.dp, color.copy(0.5f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    tryAwaitRelease()
                    pressed = false
                    onClick()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
fun NumberPad(viewModel: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    val numbers = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
    val keyCodes = mapOf(
        "1" to "KEY_1","2" to "KEY_2","3" to "KEY_3",
        "4" to "KEY_4","5" to "KEY_5","6" to "KEY_6",
        "7" to "KEY_7","8" to "KEY_8","9" to "KEY_9",
        "0" to "KEY_0","⌫" to "KEY_DEL"
    )
    Column {
        numbers.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { num ->
                    if (num.isBlank()) {
                        Spacer(Modifier.size(50.dp))
                    } else {
                        val isBack = num == "⌫"
                        val accent = if (isBack) AccentRed else GoldLight
                        var pressed by remember { mutableStateOf(false) }
                        val scale by animateFloatAsState(
                            targetValue = if (pressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "num_scale_$num"
                        )
                        Box(
                            modifier = Modifier
                                .scale(scale)
                                .shadow(if (pressed) 1.dp else 4.dp, RoundedCornerShape(12.dp))
                                .size(50.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(SurfaceVariantDark.copy(alpha = 0.95f), SurfaceDark)
                                    ),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    Brush.verticalGradient(
                                        colors = listOf(accent.copy(alpha = 0.4f), accent.copy(alpha = 0.05f))
                                    ),
                                    RoundedCornerShape(12.dp)
                                )
                                .pointerInput(num) {
                                    detectTapGestures(onPress = {
                                        pressed = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        tryAwaitRelease()
                                        pressed = false
                                        keyCodes[num]?.let { viewModel.sendKey(it) }
                                    })
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(num, color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}
