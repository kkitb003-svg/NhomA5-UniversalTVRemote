package com.ued.universaltvremote.network

import android.util.Log
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidTvRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "AndroidTvRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val PROBE_PORTS = listOf(5555)
    }

    private val adbClient = AdbTcpShellClient()
    private val deviceProbe = TvDeviceProbe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var adbPort: Int? = null
    private var manualDisconnect = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Connecting
        scope.launch {
            val resolved = getDeviceInfo(device.ip, device.port) ?: device
            currentDevice = resolved
            val probePorts = buildProbePorts(resolved)
            val adbProbe = adbClient.probe(resolved.ip, probePorts)

            adbPort = adbProbe.port
            when {
                adbProbe.port != null && !adbProbe.authRequired -> {
                    _connectionState.value = ConnectionState.Connected(resolved)
                }

                adbProbe.port != null && adbProbe.authRequired -> {
                    _connectionState.value = ConnectionState.Error(
                        "TV found, but this repository only controls Android TV through ADB over network. " +
                            "Enable network debugging and approve this device on the TV first."
                    )
                }

                else -> {
                    _connectionState.value = ConnectionState.Error(
                        "Android/Google TV found, but this project still needs the native Google TV remote-service protocol. " +
                            "The current implementation only supports ADB over network."
                    )
                }
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        adbPort = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val device = currentDevice ?: return
        val port = adbPort ?: return
        val androidKeyCode = mapToAndroidKeyCode(keyCode) ?: run {
            Log.d(TAG, "Unsupported Android TV key: $keyCode")
            return
        }

        scope.launch {
            executeAdbCommand(device.ip, port, "input keyevent $androidKeyCode")
        }
    }

    override fun sendText(text: String): Boolean {
        val device = currentDevice ?: return false
        val port = adbPort ?: return false
        scope.launch {
            val escaped = text
                .replace(" ", "%s")
                .replace("\"", "\\\"")
            executeAdbCommand(device.ip, port, "input text \"$escaped\"")
            executeAdbCommand(device.ip, port, "input keyevent 66")
        }
        return true
    }

    override suspend fun launchApp(appId: String) {
        val device = currentDevice ?: return
        val port = adbPort ?: return
        withContext(Dispatchers.IO) {
            executeAdbCommand(
                device.ip,
                port,
                "monkey -p $appId -c android.intent.category.LAUNCHER 1"
            )
        }
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext emptyList()
        val port = adbPort ?: return@withContext emptyList()

        val output = executeAdbCommand(device.ip, port, "cmd package list packages -3")
            ?: executeAdbCommand(device.ip, port, "pm list packages -3")
            ?: return@withContext emptyList()

        output.lines()
            .mapNotNull { line ->
                val packageName = line.substringAfter("package:", "").trim()
                packageName.takeIf { it.isNotBlank() }?.let {
                    TvApp(appId = it, name = it.substringAfterLast('.'))
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext false
        val port = adbPort ?: return@withContext false

        val escapedUrl = url.replace("'", "'\\''")

        // Method 1: Try am start with VIEW intent
        val result = executeAdbCommand(
            device.ip,
            port,
            "am start -a android.intent.action.VIEW -d '$escapedUrl'"
        )
        if (result != null) return@withContext true

        // Method 2: Try opening via input + browser launch
        executeAdbCommand(
            device.ip,
            port,
            "am start -a android.intent.action.VIEW -d \"$escapedUrl\""
        )
        result != null
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        val hintedBrand = detectAndroidFamily(port)
        val detected = deviceProbe.detectDevice(
            ip = ip,
            candidatePort = port.takeIf { it > 0 },
            hintedBrand = hintedBrand
        )

        return detected?.copy(
            brand = detected.brand.takeUnless { it == TvBrand.UNKNOWN } ?: hintedBrand ?: TvBrand.ANDROID_TV
        ) ?: TvDevice(
            name = "Android TV ($ip)",
            ip = ip,
            port = if (port > 0) port else 8008,
            brand = hintedBrand ?: TvBrand.ANDROID_TV
        )
    }

    private fun buildProbePorts(device: TvDevice): List<Int> {
        return buildList {
            device.port.takeIf { it in 5550..5560 }?.let(::add)
            addAll(PROBE_PORTS)
        }.distinct()
    }

    private suspend fun executeAdbCommand(ip: String, port: Int, command: String): String? {
        val result = adbClient.execute(ip, port, command)
        return result.onFailure { throwable ->
            Log.e(TAG, "ADB command failed: ${throwable.message}")
            if (throwable is AdbAuthRequiredException) {
                _connectionState.value = ConnectionState.Error(
                    "ADB authorization required on the TV. Reconnect after approving this device."
                )
            } else if (!manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@onFailure)
            }
        }.getOrNull()
    }

    private fun mapToAndroidKeyCode(keyCode: String): Int? = when (keyCode) {
        "KEY_HOME" -> 3
        "KEY_RETURN", "KEY_BACK" -> 4
        "KEY_UP" -> 19
        "KEY_DOWN" -> 20
        "KEY_LEFT" -> 21
        "KEY_RIGHT" -> 22
        "KEY_ENTER" -> 23
        "KEY_VOLUP" -> 24
        "KEY_VOLDOWN" -> 25
        "KEY_POWER", "KEY_POWEROFF" -> 26
        "KEY_MENU" -> 82
        "KEY_PLAY" -> 85
        "KEY_STOP" -> 86
        "KEY_PAUSE" -> 127
        "KEY_MUTE" -> 164
        "KEY_INFO" -> 165
        "KEY_CHUP" -> 166
        "KEY_CHDOWN" -> 167
        "KEY_SOURCE" -> 178
        "KEY_RED" -> 183
        "KEY_GREEN" -> 184
        "KEY_YELLOW" -> 185
        "KEY_BLUE" -> 186
        "KEY_DEL" -> 67
        "KEY_SPACE" -> 62
        "KEY_0" -> 7
        "KEY_1" -> 8
        "KEY_2" -> 9
        "KEY_3" -> 10
        "KEY_4" -> 11
        "KEY_5" -> 12
        "KEY_6" -> 13
        "KEY_7" -> 14
        "KEY_8" -> 15
        "KEY_9" -> 16
        "KEY_REWIND" -> 89
        "KEY_FF" -> 90
        else -> null
    }

    private fun detectAndroidFamily(port: Int): TvBrand? {
        return when (port) {
            8008, 8009 -> TvBrand.ANDROID_TV
            else -> null
        }
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Android TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val probePorts = buildProbePorts(device)
                val adbProbe = adbClient.probe(device.ip, probePorts)
                adbPort = adbProbe.port
                if (adbProbe.port != null && !adbProbe.authRequired) {
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected(device)
                } else {
                    scheduleReconnect(device)
                }
            }
        }
    }
}
