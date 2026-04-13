package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * LG webOS Smart TV remote repository.
 *
 * IMPORTANT - First time pairing:
 * When you connect for the first time, the TV will show a popup asking
 * "Allow connection from [device name]?" - you MUST approve this on the TV.
 * If the popup doesn't appear, go to TV Settings > Connection > LG Connect Apps
 * and enable it.
 *
 * For older webOS 1.x TVs, the popup may not appear automatically and you need
 * to enable the setting first. For webOS 3.0+ (2017+), the pairing popup appears
 * automatically when a connection is attempted.
 */
class LgWebOsRepository(private val context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "LgWebOsRepo"
        private const val PORT_SECURE = 3001
        private const val PORT_LEGACY = 3000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val PAIRING_TIMEOUT_MS = 20_000L
    }

    private val gson = Gson()
    private val deviceProbe = TvDeviceProbe()
    private val prefs = context.getSharedPreferences("lg_remote_prefs", Context.MODE_PRIVATE)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reqId = 0
    private var activeAttemptId = 0
    private var manualDisconnect = false
    private var connectionEstablished = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pendingRequests = ConcurrentHashMap<String, (JsonObject?) -> Unit>()
    private var savedClientKey: String? = null
    private var isFirstConnection = true

    override fun connect(device: TvDevice) {
        disconnectSockets(clearState = false)
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        savedClientKey = prefs.getString("lg_key_${device.ip}", null)

        val preferredPort = when (device.port) {
            PORT_LEGACY, PORT_SECURE -> device.port
            else -> PORT_SECURE
        }
        val normalizedDevice = device.copy(port = preferredPort, brand = TvBrand.LG)
        currentDevice = normalizedDevice
        _connectionState.value = ConnectionState.Connecting
        isFirstConnection = savedClientKey == null

        val ports = buildList {
            add(preferredPort)
            add(PORT_SECURE)
            add(PORT_LEGACY)
        }.distinct()

        attemptConnection(normalizedDevice, ports, 0)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        disconnectSockets(clearState = true)
    }

    override fun sendKey(keyCode: String) {
        when (keyCode) {
            "KEY_VOLUP" -> sendUriRequest("ssap://audio/volumeUp")
            "KEY_VOLDOWN" -> sendUriRequest("ssap://audio/volumeDown")
            "KEY_MUTE" -> sendUriRequest(
                "ssap://audio/setMute",
                JsonObject().apply { addProperty("mute", true) }
            )
            "KEY_POWER" -> sendUriRequest("ssap://system/turnOff")
            "KEY_CHUP" -> sendUriRequest("ssap://tv/channelUp")
            "KEY_CHDOWN" -> sendUriRequest("ssap://tv/channelDown")
            "KEY_HOME" -> sendButtonOrPointer("HOME")
            "KEY_RETURN", "KEY_BACK" -> sendButtonOrPointer("BACK")
            "KEY_UP" -> sendButtonOrPointer("UP")
            "KEY_DOWN" -> sendButtonOrPointer("DOWN")
            "KEY_LEFT" -> sendButtonOrPointer("LEFT")
            "KEY_RIGHT" -> sendButtonOrPointer("RIGHT")
            "KEY_ENTER" -> sendButtonOrPointer("ENTER")
            "KEY_PLAY" -> sendButtonOrPointer("PLAY")
            "KEY_PAUSE" -> sendButtonOrPointer("PAUSE")
            "KEY_STOP" -> sendButtonOrPointer("STOP")
            "KEY_RED" -> sendButtonOrPointer("RED")
            "KEY_GREEN" -> sendButtonOrPointer("GREEN")
            "KEY_YELLOW" -> sendButtonOrPointer("YELLOW")
            "KEY_BLUE" -> sendButtonOrPointer("BLUE")
            "KEY_INFO" -> sendButtonOrPointer("INFO")
            "KEY_MENU" -> sendButtonOrPointer("MENU")
            "KEY_0" -> sendButtonOrPointer("0")
            "KEY_1" -> sendButtonOrPointer("1")
            "KEY_2" -> sendButtonOrPointer("2")
            "KEY_3" -> sendButtonOrPointer("3")
            "KEY_4" -> sendButtonOrPointer("4")
            "KEY_5" -> sendButtonOrPointer("5")
            "KEY_6" -> sendButtonOrPointer("6")
            "KEY_7" -> sendButtonOrPointer("7")
            "KEY_8" -> sendButtonOrPointer("8")
            "KEY_9" -> sendButtonOrPointer("9")
            "KEY_FF" -> sendButtonOrPointer("FASTFORWARD")
            "KEY_REWIND" -> sendButtonOrPointer("REWIND")
            "KEY_DEL" -> sendButtonOrPointer("DELETE")
            else -> sendButtonOrPointer(keyCode.removePrefix("KEY_"))
        }
    }

    override fun sendText(text: String): Boolean {
        return sendUriRequest(
            "ssap://com.webos.service.ime/insertText",
            JsonObject().apply { addProperty("text", text) }
        )
    }

    override suspend fun launchApp(appId: String) {
        sendUriRequest(
            "ssap://system.launcher/launch",
            JsonObject().apply { addProperty("id", appId) }
        )
    }

    override suspend fun fetchInstalledApps(): List<TvApp> {
        val socket = webSocket ?: return emptyList()
        val requestId = "req_${reqId++}"
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingRequests[requestId] = { responsePayload ->
                val apps = mutableListOf<TvApp>()
                runCatching {
                    // webOS 3.0+ returns "launchPoints"
                    val launchPoints = responsePayload?.getAsJsonArray("launchPoints")
                        ?: responsePayload?.getAsJsonArray("appList")
                    launchPoints?.forEach { element ->
                        val obj = element.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val title = obj.get("title")?.asString ?: id
                        apps.add(
                            TvApp(
                                appId = id,
                                name = title,
                                version = obj.get("version")?.asString.orEmpty(),
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        )
                    }
                }
                continuation.resumeWith(Result.success(apps.sortedBy { it.name.lowercase() }))
            }

            socket.send(
                JsonObject().apply {
                    addProperty("type", "request")
                    addProperty("id", requestId)
                    addProperty("uri", "ssap://com.webos.applicationManager/listLaunchPoints")
                }.toString()
            )

            scope.launch {
                delay(8000)
                pendingRequests.remove(requestId)?.let {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean {
        return sendUriRequest(
            "ssap://system.launcher/open",
            JsonObject().apply { addProperty("target", url) }
        )
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = when (port) {
                PORT_LEGACY, PORT_SECURE -> port
                else -> PORT_SECURE
            },
            hintedBrand = TvBrand.LG
        )?.copy(port = PORT_SECURE)
    }

    private fun attemptConnection(device: TvDevice, ports: List<Int>, index: Int) {
        if (index >= ports.size) {
            val message = if (isFirstConnection) {
                "Không thể kết nối LG TV.\n\n" +
                    "VUI LÒNG kiểm tra:\n" +
                    "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                    "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                    "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN\n" +
                    "4. Thử kết nối lại sau khi bật cài đặt"
            } else {
                "Mất kết nối LG TV. Vui lòng thử lại."
            }
            _connectionState.value = ConnectionState.Error(message)
            return
        }

        val port = ports[index]
        val secure = port == PORT_SECURE
        val protocol = if (secure) "wss" else "ws"
        val url = "$protocol://${device.ip}:$port"
        val attemptId = ++activeAttemptId
        val connectedDevice = device.copy(port = port)

        Log.d(TAG, "Trying LG connection $url (first=$isFirstConnection)")

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (attemptId != activeAttemptId) {
                        webSocket.cancel()
                        return
                    }
                    currentDevice = connectedDevice
                    sendRegisterPayload(webSocket, connectedDevice.ip)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (attemptId != activeAttemptId) return
                    Log.d(TAG, "LG RX: ${text.take(200)}")

                    runCatching {
                        val json = gson.fromJson(text, JsonObject::class.java)
                        val type = json.get("type")?.asString
                        val id = json.get("id")?.asString

                        when {
                            type == "registered" -> {
                                val clientKey = json.getAsJsonObject("payload")
                                    ?.get("client-key")
                                    ?.asString
                                if (!clientKey.isNullOrBlank()) {
                                    prefs.edit().putString("lg_key_${connectedDevice.ip}", clientKey).apply()
                                }
                                currentDevice = connectedDevice
                                connectionEstablished = true
                                reconnectAttempts = 0
                                _connectionState.value = ConnectionState.Connected(connectedDevice)
                                requestPointerSocket(webSocket)
                            }

                            id == "req_pointer" -> {
                                val socketPath = json.getAsJsonObject("payload")
                                    ?.get("socketPath")
                                    ?.asString
                                if (!socketPath.isNullOrBlank()) {
                                    connectPointerSocket(resolvePointerUrl(socketPath, connectedDevice))
                                }
                            }

                            type == "response" && id != null -> {
                                val payload = json.getAsJsonObject("payload")
                                pendingRequests.remove(id)?.invoke(payload)
                            }

                            type == "error" -> {
                                val errorCode = json.get("error")?.asString ?: "Unknown"
                                if (id == "req_register") {
                                    val friendlyMessage = when {
                                        errorCode.contains("403", ignoreCase = true) ||
                                        errorCode.contains("denied", ignoreCase = true) ||
                                        errorCode.contains("reject", ignoreCase = true) ->
                                            "TV từ chối kết nối.\n\nHãy nhấn 'Chấp nhận' trên TV khi có thông báo yêu cầu kết nối."
                                        errorCode.contains("timeout", ignoreCase = true) ->
                                            "Hết thời gian chờ ghép nối.\n\nVui lòng nhấn 'Chấp nhận' trên TV trong vòng 30 giây."
                                        else ->
                                            "Lỗi ghép nối LG: $errorCode\n\nĐảm bảo 'LG Connect Apps' đã được bật trong cài đặt TV."
                                    }
                                    _connectionState.value = ConnectionState.Error(friendlyMessage)
                                } else {
                                    pendingRequests.remove(id)?.invoke(null)
                                }
                            }

                            type == "hello" -> {
                                // Connection acknowledged, registration payload will follow
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    val message = t.message ?: response?.message ?: "Connection failed"
                    Log.e(TAG, "LG WebSocket failure on $url: $message")

                    if (shouldTryNextPort(message) && index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else {
                        val friendlyMessage = if (isFirstConnection && savedClientKey == null) {
                            "Không thể kết nối LG TV.\n\n" +
                                "VUI LÒNG kiểm tra:\n" +
                                "1. TV và điện thoại cùng mạng Wi-Fi\n" +
                                "2. Trên TV: Cài đặt > Kết nối > LG Connect Apps > Bật\n" +
                                "3. Nếu có thông báo trên TV, hãy nhấn CHẤP NHẬN"
                        } else {
                            "Lỗi kết nối: $message"
                        }
                        _connectionState.value = ConnectionState.Error(friendlyMessage)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (attemptId != activeAttemptId || manualDisconnect) return

                    if (connectionEstablished) {
                        scheduleReconnect(device)
                    } else if (index + 1 < ports.size) {
                        attemptConnection(device, ports, index + 1)
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        )
    }

    private fun sendRegisterPayload(ws: WebSocket, ip: String) {
        val clientKey = savedClientKey
        val permissions = JsonArray().apply {
            add("LAUNCH")
            add("LAUNCH_WEBAPP")
            add("APP_TO_APP")
            add("CONTROL_AUDIO")
            add("CONTROL_INPUT_MEDIA_PLAYBACK")
            add("CONTROL_POWER")
            add("READ_INSTALLED_APPS")
            add("CONTROL_DISPLAY")
            add("CONTROL_INPUT_JOYSTICK")
            add("CONTROL_INPUT_TV")
            add("READ_INPUT_DEVICE_LIST")
            add("READ_NETWORK_STATE")
            add("READ_TV_CHANNEL_LIST")
            add("WRITE_NOTIFICATION_TOAST")
            add("CONTROL_INPUT_TEXT")
            add("CONTROL_MOUSE_AND_KEYBOARD")
            add("READ_CURRENT_CHANNEL")
            add("READ_RUNNING_APPS")
        }

        val payload = JsonObject().apply {
            addProperty("forcePairing", false)
            addProperty("pairingType", "PROMPT")
            add(
                "manifest",
                JsonObject().apply {
                    addProperty("manifestVersion", 1)
                    addProperty("appVersion", "1.1")
                    add("permissions", permissions)
                }
            )
            if (!clientKey.isNullOrBlank()) {
                addProperty("client-key", clientKey)
            }
        }

        ws.send(
            JsonObject().apply {
                addProperty("type", "register")
                addProperty("id", "req_register")
                add("payload", payload)
            }.toString()
        )
    }

    private fun requestPointerSocket(ws: WebSocket) {
        ws.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_pointer")
                addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            }.toString()
        )
    }

    private fun connectPointerSocket(url: String) {
        pointerSocket?.cancel()
        pointerSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "LG pointer socket connected")
                }
            }
        )
    }

    private fun resolvePointerUrl(socketPath: String, device: TvDevice): String {
        return when {
            socketPath.startsWith("ws://") || socketPath.startsWith("wss://") -> socketPath
            socketPath.startsWith("/") -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}$socketPath"
            }
            else -> {
                val scheme = if (device.port == PORT_SECURE) "wss" else "ws"
                "$scheme://${device.ip}:${device.port}/$socketPath"
            }
        }
    }

    private fun sendButtonOrPointer(name: String) {
        val pointer = pointerSocket
        if (pointer != null) {
            pointer.send("type:button\nname:$name\n\n")
        } else {
            // Fallback via main WebSocket - some actions need specific URIs
            val clickUri = when (name) {
                "ENTER" -> "ssap://com.webos.service.ime/sendEnterKey"
                "DELETE", "BACKSPACE" -> "ssap://com.webos.service.ime/deleteCharacters"
                "HOME" -> "ssap://com.webos.service.ime/sendHomeKey"
                else -> null
            }

            if (clickUri != null) {
                sendUriRequest(clickUri)
            } else {
                // Generic pointer socket request
                val socket = webSocket
                if (socket != null) {
                    socket.send(
                        JsonObject().apply {
                            addProperty("type", "request")
                            addProperty("id", "req_btn_${reqId++}")
                            addProperty("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                        }.toString()
                    )
                } else {
                    Log.w(TAG, "LG: no socket available for $name")
                }
            }
        }
    }

    private fun sendUriRequest(uri: String, payload: JsonObject? = null): Boolean {
        val socket = webSocket ?: return false
        return socket.send(
            JsonObject().apply {
                addProperty("type", "request")
                addProperty("id", "req_${reqId++}")
                addProperty("uri", uri)
                if (payload != null) add("payload", payload)
            }.toString()
        )
    }

    private fun shouldTryNextPort(message: String): Boolean {
        val normalized = message.lowercase()
        return "connection reset" in normalized ||
            "eof" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "refused" in normalized ||
            "failed" in normalized
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối LG TV. Vui lòng kết nối lại.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val ports = buildList {
                    add(device.port)
                    add(PORT_SECURE)
                    add(PORT_LEGACY)
                }.distinct()
                attemptConnection(device, ports, 0)
            }
        }
    }

    private fun disconnectSockets(clearState: Boolean) {
        activeAttemptId += 1
        webSocket?.cancel()
        pointerSocket?.cancel()
        webSocket = null
        pointerSocket = null
        pendingRequests.clear()
        if (clearState) {
            currentDevice = null
            connectionEstablished = false
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}

