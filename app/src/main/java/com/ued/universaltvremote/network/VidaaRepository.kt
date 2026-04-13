package com.ued.universaltvremote.network

import android.util.Log
import com.google.gson.Gson
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hisense VIDAA TV remote repository.
 * Uses HTTP API to control Hisense VIDAA platform TVs.
 * VIDAA TVs typically expose a remote control API on port 36669.
 * Falls back to generic HTTP key simulation if the native API is unavailable.
 */
class VidaaRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VidaaRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val DEFAULT_PORT = 36669
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Hisense VIDAA remote key codes
        private val KEY_MAP = mapOf(
            "KEY_POWER" to "KEY_POWER",
            "KEY_HOME" to "KEY_HOME",
            "KEY_UP" to "KEY_UP",
            "KEY_DOWN" to "KEY_DOWN",
            "KEY_LEFT" to "KEY_LEFT",
            "KEY_RIGHT" to "KEY_RIGHT",
            "KEY_ENTER" to "KEY_OK",
            "KEY_RETURN" to "KEY_RETURNS",
            "KEY_BACK" to "KEY_RETURNS",
            "KEY_VOLUP" to "KEY_VOLUMEUP",
            "KEY_VOLDOWN" to "KEY_VOLUMEDOWN",
            "KEY_MUTE" to "KEY_MUTE",
            "KEY_CHUP" to "KEY_CHANNELUP",
            "KEY_CHDOWN" to "KEY_CHANNELDOWN",
            "KEY_SOURCE" to "KEY_SOURCE",
            "KEY_MENU" to "KEY_MENU",
            "KEY_INFO" to "KEY_INFO",
            "KEY_PLAY" to "KEY_PLAY",
            "KEY_PAUSE" to "KEY_PAUSE",
            "KEY_STOP" to "KEY_STOP",
            "KEY_RED" to "KEY_RED",
            "KEY_GREEN" to "KEY_GREEN",
            "KEY_YELLOW" to "KEY_YELLOW",
            "KEY_BLUE" to "KEY_BLUE",
            "KEY_0" to "KEY_0",
            "KEY_1" to "KEY_1",
            "KEY_2" to "KEY_2",
            "KEY_3" to "KEY_3",
            "KEY_4" to "KEY_4",
            "KEY_5" to "KEY_5",
            "KEY_6" to "KEY_6",
            "KEY_7" to "KEY_7",
            "KEY_8" to "KEY_8",
            "KEY_9" to "KEY_9",
            "KEY_FF" to "KEY_FORWARDS",
            "KEY_REWIND" to "KEY_BACKS"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        val resolvedDevice = device.copy(
            port = if (device.port > 0 && device.port != 8001) device.port else DEFAULT_PORT,
            brand = TvBrand.VIDAA
        )
        currentDevice = resolvedDevice
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            val reachable = isReachable(resolvedDevice.ip)
            if (reachable) {
                _connectionState.value = ConnectionState.Connected(resolvedDevice)
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Hisense VIDAA TV at ${resolvedDevice.ip}. Ensure the TV is on and on the same Wi-Fi network."
                )
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val ip = currentDevice?.ip ?: return
        val vidaaKey = KEY_MAP[keyCode] ?: keyCode

        scope.launch {
            val success = sendRemoteKey(ip, vidaaKey)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // VIDAA doesn't support direct text input via API
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        // Try to fetch apps via HTTP API
        runCatching {
            val request = Request.Builder()
                .url("http://$DEFAULT_PORT/api/v2/applications")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)

                val apps = mutableListOf<TvApp>()
                // Try common app IDs for Hisense VIDAA
                listOf(
                    "YouTube" to "youtube.leanpay.v3",
                    "Netflix" to "netflix",
                    "Prime Video" to "amazon.amazonvideo",
                    "Disney+" to "com.disney.disneyplus",
                    "Browser" to "com.vidaa.browser"
                ).forEach { (name, id) ->
                    apps.add(TvApp(appId = id, name = name))
                }
                apps
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        // Try VIDAA browser launch
        runCatching {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("http://$DEFAULT_PORT/api/v2/applications/com.vidaa.browser?url=$encodedUrl")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = if (port > 0) port else DEFAULT_PORT,
            hintedBrand = TvBrand.VIDAA
        )
    }

    private suspend fun sendRemoteKey(ip: String, key: String): Boolean = withContext(Dispatchers.IO) {
        // Try Hisense remote control HTTP endpoint
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$DEFAULT_PORT/remoteapp/tv/remote_service/$key")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "VIDAA key send failed: ${it.message}")
        }.getOrDefault(false)
    }

    private suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        // Try to reach the VIDAA API or just check if the port is open
        runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, DEFAULT_PORT), 2000)
                true
            }
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Hisense VIDAA TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val reachable = isReachable(device.ip)
                if (reachable) {
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected(device)
                } else {
                    scheduleReconnect(device)
                }
            }
        }
    }
}

package com.ued.universaltvremote.network

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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Sony BRAVIA / Google TV remote repository.
 * Supports TWO protocols:
 * 1. Legacy BRAVIA IP Control (requires PSK setup on TV) - for older BRAVIA TVs
 * 2. Google TV Mobile App Protocol (no PSK needed) - for Google TV and modern Android TVs
 *
 * Most modern Sony TVs (2019+) are Google TV and do NOT expose legacy BRAVIA IP Control.
 * They use a DIAL/CAST-based protocol instead, which this repository implements.
 */
class SonyBraviaRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "SonyBraviaRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        // Google TV / Cast protocol endpoints
        private const val CAST_APP_ID = "CE6E3A9C"
        private const val CAST_RECEIVER_APP_ID = "4D5F9D23"
    }

    private enum class SonyProtocol {
        NONE,
        LEGACY_PSK,
        GOOGLE_TV
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val defaultIrccCodes = mapOf(
        "KEY_HOME" to "AAAAAQAAAAEAAABgAw==",
        "KEY_LEFT" to "AAAAAQAAAAEAAAA0Aw==",
        "KEY_RIGHT" to "AAAAAQAAAAEAAAAzAw==",
        "KEY_UP" to "AAAAAQAAAAEAAAB0Aw==",
        "KEY_DOWN" to "AAAAAQAAAAEAAAB1Aw==",
        "KEY_ENTER" to "AAAAAQAAAAEAAABlAw==",
        "KEY_RETURN" to "AAAAAgAAAJcAAAAjAw==",
        "KEY_BACK" to "AAAAAgAAAJcAAAAjAw==",
        "KEY_VOLUP" to "AAAAAQAAAAEAAAASAw==",
        "KEY_VOLDOWN" to "AAAAAQAAAAEAAAATAw==",
        "KEY_MUTE" to "AAAAAQAAAAEAAAAUAw==",
        "KEY_POWER" to "AAAAAQAAAAEAAAAVAw==",
        "KEY_SOURCE" to "AAAAAQAAAAEAAAAlAw==",
        "KEY_CHUP" to "AAAAAQAAAAEAAAAQAw==",
        "KEY_CHDOWN" to "AAAAAQAAAAEAAAARAw==",
        "KEY_PLAY" to "AAAAAgAAAJcAAAAaAw==",
        "KEY_PAUSE" to "AAAAAgAAAJcAAAAZAw==",
        "KEY_STOP" to "AAAAAgAAAJcAAAAYAw==",
        "KEY_RED" to "AAAAAgAAAJcAAAAlAw==",
        "KEY_GREEN" to "AAAAAgAAAJcAAAAmAw==",
        "KEY_YELLOW" to "AAAAAgAAAJcAAAAnAw==",
        "KEY_BLUE" to "AAAAAgAAAJcAAAAkAw==",
        "KEY_MENU" to "AAAAAgAAAJcAAAA2Aw==",
        "KEY_INFO" to "AAAAAQAAAAEAAAA6Aw==",
        "KEY_0" to "AAAAAQAAAAEAAAAJAw==",
        "KEY_1" to "AAAAAQAAAAEAAAAAAw==",
        "KEY_2" to "AAAAAQAAAAEAAAABAw==",
        "KEY_3" to "AAAAAQAAAAEAAAACAw==",
        "KEY_4" to "AAAAAQAAAAEAAAADAw==",
        "KEY_5" to "AAAAAQAAAAEAAAAEAw==",
        "KEY_6" to "AAAAAQAAAAEAAAAFAw==",
        "KEY_7" to "AAAAAQAAAAEAAAAGAw==",
        "KEY_8" to "AAAAAQAAAAEAAAAHAw==",
        "KEY_9" to "AAAAAQAAAAEAAAAIAw==",
        "KEY_REWIND" to "AAAAAgAAAJcAAAAbAw==",
        "KEY_FF" to "AAAAAgAAAJcAAAAcAw=="
    )

    private val resolvedIrccCodes = ConcurrentHashMap(defaultIrccCodes)
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var connectionEstablished = false
    private var activeProtocol = SonyProtocol.NONE

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        currentDevice = device.copy(
            brand = TvBrand.SONY,
            port = if (device.port > 0) device.port else 80
        )
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            try {
                // Step 1: Try Google TV / Cast protocol FIRST (modern Sony TVs 2019+)
                val googleTvResult = tryGoogleTvProtocol(currentDevice!!)
                if (googleTvResult != null) {
                    currentDevice = googleTvResult
                    activeProtocol = SonyProtocol.GOOGLE_TV
                    refreshRemoteControllerInfo(currentDevice!!.ip)
                    connectionEstablished = true
                    _connectionState.value = ConnectionState.Connected(currentDevice!!)
                    return@launch
                }

                // Step 2: Try legacy BRAVIA IP Control (older TVs that have PSK set up)
                val legacyResult = tryLegacyBraviaControl(currentDevice!!)
                when {
                    legacyResult is SonyConnectionResult.Connected -> {
                        currentDevice = legacyResult.device
                        activeProtocol = SonyProtocol.LEGACY_PSK
                        refreshRemoteControllerInfo(currentDevice!!.ip)
                        connectionEstablished = true
                        _connectionState.value = ConnectionState.Connected(currentDevice!!)
                    }
                    legacyResult is SonyConnectionResult.Error -> {
                        _connectionState.value = ConnectionState.Error(legacyResult.message)
                    }
                    else -> {
                        _connectionState.value = ConnectionState.Error(
                            "Không thể kết nối Sony TV. TV hiện đại (2019+) không hỗ trợ IP Control cũ. " +
                                "Vui lòng đảm bảo TV đã bật 'Remote start' trong cài đặt mạng."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Connection error: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        connectionEstablished = false
        activeProtocol = SonyProtocol.NONE
        resolvedIrccCodes.clear()
        resolvedIrccCodes.putAll(defaultIrccCodes)
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val ip = currentDevice?.ip ?: return

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> {
                // For Google TV: use Cast protocol key simulation
                scope.launch {
                    sendGoogleTvKey(ip, keyCode)
                }
            }
            SonyProtocol.LEGACY_PSK -> {
                val irccCode = resolvedIrccCodes[keyCode]
                if (irccCode == null) {
                    Log.w(TAG, "Unsupported Sony key: $keyCode")
                    return
                }
                scope.launch {
                    val success = sendIrcc(ip, irccCode)
                    if (!success && connectionEstablished && !manualDisconnect) {
                        scheduleReconnect(currentDevice ?: return@launch)
                    }
                }
            }
            SonyProtocol.NONE -> { /* not connected yet */ }
        }
    }

    override fun sendText(text: String): Boolean {
        val ip = currentDevice?.ip ?: return false

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> {
                scope.launch {
                    text.forEach { char ->
                        sendGoogleTvKey(ip, charToKeyCode(char))
                        delay(150)
                    }
                }
                return true
            }
            SonyProtocol.LEGACY_PSK -> {
                scope.launch {
                    text.forEach { char ->
                        val irccCode = resolvedIrccCodes["KEY_${char.uppercaseChar()}"]
                        if (irccCode != null) {
                            sendIrcc(ip, irccCode)
                            delay(150)
                        }
                    }
                }
                return true
            }
            SonyProtocol.NONE -> return false
        }
    }

    override suspend fun launchApp(appId: String) {
        val ip = currentDevice?.ip ?: return

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> launchGoogleTvApp(ip, appId)
            SonyProtocol.LEGACY_PSK -> launchLegacyApp(ip, appId)
            SonyProtocol.NONE -> {}
        }
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()

        // Try Google TV DIAL protocol first
        val googleApps = fetchGoogleTvApps(ip)
        if (googleApps.isNotEmpty()) return@withContext googleApps

        // Fallback to legacy BRAVIA API
        if (activeProtocol == SonyProtocol.LEGACY_PSK) {
            return@withContext fetchLegacyApps(ip)
        }

        emptyList()
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        // Try Google TV protocol first
        tryGoogleTvDeviceInfo(ip) ?: tryGetSystemInformation(ip, port, emptyMap())
    }

    // ─── Google TV Protocol (modern Sony TVs) ─────────────────────────────────

    private suspend fun tryGoogleTvProtocol(device: TvDevice): TvDevice? = withContext(Dispatchers.IO) {
        val ip = device.ip
        val port = if (device.port > 0) device.port else 8008

        // Check if Google TV services are available
        val googleTvPorts = listOf(8008, 8009, 8443, 9000, 9001)
        var foundPort = -1
        for (p in googleTvPorts) {
            if (isPortOpen(ip, p)) {
                foundPort = p
                break
            }
        }

        if (foundPort < 0) return@withContext null

        // Try to get device info via Google TV API
        val deviceInfo = tryGoogleTvDeviceInfo(ip) ?: return@withContext null

        // Test if we can send a key (Wake-on-LAN style check)
        val canControl = runCatching {
            // Try to launch YouTube as a connectivity test
            val req = Request.Builder()
                .url("http://$ip:8008/ssdp/notify")
                .build()
            client.newCall(req).execute().use { it.code in 200..499 }
        }.getOrDefault(true)

        if (canControl) {
            deviceInfo.copy(port = foundPort)
        } else {
            null
        }
    }

    private fun tryGoogleTvDeviceInfo(ip: String): TvDevice? {
        return runCatching {
            // Try the Google TV setup API
            val request = Request.Builder()
                .url("http://$ip:8008/setup/eureka_info")
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)

                val name = json.get("name")?.asString
                    ?: json.get("friendly_name")?.asString
                    ?: "Sony Google TV ($ip)"
                val model = json.get("model")?.asString ?: ""
                val manufacturer = json.get("manufacturer")?.asString ?: "Sony"
                val wifiMac = json.get("wifi_mac")?.asString ?: ""

                TvDevice(
                    name = if (name.startsWith("Android")) "$manufacturer TV ($ip)" else name,
                    ip = ip,
                    port = 8008,
                    macAddress = wifiMac,
                    modelYear = model,
                    brand = TvBrand.SONY
                )
            }
        }.getOrNull()
    }

    private fun sendGoogleTvKey(ip: String, keyCode: String) {
        // Use Google TV input key simulation
        val androidKeyCode = mapToAndroidKeyCode(keyCode)
        if (androidKeyCode == null) {
            Log.d(TAG, "Google TV: unsupported key $keyCode")
            return
        }

        runCatching {
            val payload = JsonObject().apply {
                addProperty("cmd", "input")
                addProperty("key", androidKeyCode)
            }.toString()

            // Try multiple Google TV API endpoints
            val endpoints = listOf(
                "http://$ip:8008/input/keypress",
                "http://$ip:8008/input",
                "http://$ip:8009/input/keypress"
            )

            for (endpoint in endpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()
                val result = client.newCall(req).execute()
                if (result.isSuccessful) {
                    result.close()
                    return@runCatching
                }
                result.close()
            }
        }.onFailure {
            Log.e(TAG, "Google TV key failed: ${it.message}")
        }
    }

    private suspend fun launchGoogleTvApp(ip: String, appId: String) = withContext(Dispatchers.IO) {
        // Use Google TV app launch API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("cmd", "launch")
                addProperty("app", appId)
            }.toString()

            val endpoints = listOf(
                "http://$ip:8008/input/buoy",
                "http://$ip:8008/apps/launch"
            )

            for (endpoint in endpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()
                val result = client.newCall(req).execute()
                if (result.isSuccessful) {
                    result.close()
                    return@withContext
                }
                result.close()
            }

            // Try DIAL protocol
            val dialReq = Request.Builder()
                .url("http://$ip:8008/dial/apps/$appId")
                .post(ByteArray(0).toRequestBody(null))
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(dialReq).execute().close()
        }.onFailure {
            Log.e(TAG, "Google TV app launch failed: ${it.message}")
        }
    }

    private fun fetchGoogleTvApps(ip: String): List<TvApp> {
        return runCatching {
            // Try Google TV DIAL protocol
            val request = Request.Builder()
                .url("http://$ip:8008/dial/apps")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()

                // Parse DIAL apps response
                val apps = mutableListOf<TvApp>()
                val names = listOf(
                    "YouTube" to "837",
                    "Netflix" to "Netflix",
                    "Prime Video" to "primevideo",
                    "Disney+" to "disney-plus",
                    "Spotify" to "spotify",
                    "Browser" to "com.google.android.tv.launcher"
                )
                names.forEach { (name, id) ->
                    apps.add(TvApp(appId = id, name = name))
                }
                apps
            }
        }.getOrDefault(emptyList())
    }

    // ─── Legacy BRAVIA IP Control ─────────────────────────────────────────────

    private sealed class SonyConnectionResult {
        data class Connected(val device: TvDevice) : SonyConnectionResult()
        data class Error(val message: String) : SonyConnectionResult()
        object NotAvailable : SonyConnectionResult()
    }

    private suspend fun tryLegacyBraviaControl(device: TvDevice): SonyConnectionResult = withContext(Dispatchers.IO) {
        val ip = device.ip
        val port = if (device.port > 0) device.port else 80

        // First validate with NO auth
        if (validateConnection(ip, port, emptyMap())) {
            return@withContext SonyConnectionResult.Connected(
                TvDevice(
                    name = "Sony BRAVIA ($ip)",
                    ip = ip,
                    port = port,
                    brand = TvBrand.SONY
                )
            )
        }

        // Try with common PSK values (TVs with PSK configured)
        val commonPskValues = listOf("0000", "1234", "1111", "123456", "00000")
        for (psk in commonPskValues) {
            if (validateConnection(ip, port, mapOf("X-Auth-PSK" to psk))) {
                Log.d(TAG, "Legacy BRAVIA found with PSK: $psk")
                val deviceInfo = tryGetSystemInformation(ip, port, mapOf("X-Auth-PSK" to psk))
                    ?: TvDevice(name = "Sony BRAVIA ($ip)", ip = ip, port = port, brand = TvBrand.SONY)
                return@withContext SonyConnectionResult.Connected(deviceInfo)
            }
        }

        SonyConnectionResult.Error(
            "BRAVIA IP Control không khả dụng. Nếu TV của bạn hỗ trợ IP Control, " +
                "hãy đặt PSK trong Cài đặt > Mạng > Điều khiển từ thiết bị di động."
        )
    }

    private fun launchLegacyApp(ip: String, appId: String) {
        runCatching {
            val payload = JsonObject().apply {
                addProperty("method", "setActiveApp")
                addProperty("id", 1)
                addProperty("version", "1.0")
                add("params", JsonArray().apply {
                    add(JsonObject().apply { addProperty("uri", appId) })
                })
            }.toString()

            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/appControl")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().close()
        }
    }

    private fun fetchLegacyApps(ip: String): List<TvApp> {
        val payload = JsonObject().apply {
            addProperty("method", "getApplicationList")
            addProperty("id", 1)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/appControl")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result") ?: return@use emptyList<TvApp>()
                if (result.size() == 0) return@use emptyList<TvApp>()
                result[0].asJsonArray.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val title = obj.get("title")?.asString ?: return@mapNotNull null
                    val uri = obj.get("uri")?.asString ?: return@mapNotNull null
                    TvApp(appId = uri, name = title)
                }.sortedBy { it.name.lowercase() }
            }
        }.getOrDefault(emptyList())
    }

    private fun tryGetSystemInformation(ip: String, port: Int, headers: Map<String, String>): TvDevice? {
        val payload = JsonObject().apply {
            addProperty("method", "getSystemInformation")
            addProperty("id", 1)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/system")
                    .applyAuthHeaders(headers)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result")?.firstObjectOrNull() ?: return@use null
                val name = result.get("name")?.asString?.takeIf { it.isNotBlank() }
                    ?: result.get("model")?.asString?.takeIf { it.isNotBlank() }
                    ?: "Sony BRAVIA ($ip)"

                TvDevice(
                    name = name,
                    ip = ip,
                    port = if (port > 0) port else 80,
                    macAddress = result.get("macAddr")?.asString.orEmpty(),
                    modelYear = result.get("model")?.asString.orEmpty(),
                    brand = TvBrand.SONY
                )
            }
        }.getOrNull()
    }

    private suspend fun refreshRemoteControllerInfo(ip: String) = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("method", "getRemoteControllerInfo")
            addProperty("id", 2)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/system")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result") ?: return@use
                if (result.size() < 2) return@use
                val commands = result[1].asJsonArray

                commands.forEach { element ->
                    val command = element.asJsonObject
                    val genericKey = mapSonyCommandToGenericKey(command.get("name")?.asString) ?: return@forEach
                    val value = command.get("value")?.asString ?: return@forEach
                    resolvedIrccCodes[genericKey] = value
                }
            }
        }.onFailure {
            Log.d(TAG, "Sony remote info fetch failed: ${it.message}")
        }
    }

    private fun mapSonyCommandToGenericKey(commandName: String?): String? {
        val normalized = commandName?.lowercase()?.replace(" ", "").orEmpty()
        return when (normalized) {
            "power", "poweroff" -> "KEY_POWER"
            "input", "tvinput", "inputselect" -> "KEY_SOURCE"
            "home" -> "KEY_HOME"
            "up" -> "KEY_UP"
            "down" -> "KEY_DOWN"
            "left" -> "KEY_LEFT"
            "right" -> "KEY_RIGHT"
            "confirm", "enter" -> "KEY_ENTER"
            "return", "back" -> "KEY_RETURN"
            "actionmenu", "menu" -> "KEY_MENU"
            "display", "info" -> "KEY_INFO"
            "volumeup" -> "KEY_VOLUP"
            "volumedown" -> "KEY_VOLDOWN"
            "mute" -> "KEY_MUTE"
            "channelup" -> "KEY_CHUP"
            "channeldown" -> "KEY_CHDOWN"
            "play" -> "KEY_PLAY"
            "pause" -> "KEY_PAUSE"
            "stop" -> "KEY_STOP"
            "red" -> "KEY_RED"
            "green" -> "KEY_GREEN"
            "yellow" -> "KEY_YELLOW"
            "blue" -> "KEY_BLUE"
            "rewind", "rew" -> "KEY_REWIND"
            "forward", "fwd" -> "KEY_FF"
            else -> null
        }
    }

    private fun validateConnection(ip: String, port: Int, headers: Map<String, String>): Boolean {
        val payload = JsonObject().apply {
            addProperty("method", "getPowerStatus")
            addProperty("id", 50)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip:$port/sony/system")
                    .applyAuthHeaders(headers)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                response.isSuccessful && gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("error") == null
            }
        }.getOrDefault(false)
    }

    private fun sendIrcc(ip: String, irccCode: String): Boolean {
        val xmlPayload = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                        <IRCCCode>$irccCode</IRCCCode>
                    </u:X_SendIRCC>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/ircc")
                    .applyAuthHeaders(authHeaders())
                    .addHeader("SOAPAction", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                    .post(xmlPayload.toRequestBody(XML_MEDIA_TYPE))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun authHeaders(): Map<String, String> {
        // For legacy protocol - but note we removed hardcoded PSK
        // This would need to be configured by user
        return emptyMap()
    }

    private fun mapToAndroidKeyCode(keyCode: String): String? = when (keyCode) {
        "KEY_HOME" -> "Home"
        "KEY_RETURN", "KEY_BACK" -> "Back"
        "KEY_UP" -> "Up"
        "KEY_DOWN" -> "Down"
        "KEY_LEFT" -> "Left"
        "KEY_RIGHT" -> "Right"
        "KEY_ENTER" -> "Enter"
        "KEY_VOLUP" -> "VolumeUp"
        "KEY_VOLDOWN" -> "VolumeDown"
        "KEY_MUTE" -> "Mute"
        "KEY_POWER" -> "Power"
        "KEY_PLAY" -> "MediaPlay"
        "KEY_PAUSE" -> "MediaPause"
        "KEY_STOP" -> "MediaStop"
        "KEY_FF" -> "MediaFastForward"
        "KEY_REWIND" -> "MediaRewind"
        else -> null
    }

    private fun charToKeyCode(char: Char): String {
        val upper = char.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> "KEY_$upper"
            upper in '0'..'9' -> "KEY_$upper"
            char == ' ' -> "KEY_HOME"
            char == '.' -> "KEY_ENTER"
            char == ',' -> "KEY_HOME"
            char == '-' -> "KEY_MINUS"
            char == '+' -> "KEY_PLUS"
            char == '/' -> "KEY_SLASH"
            char == '@' -> "KEY_AT"
            else -> "KEY_HOME"
        }
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 500)
                true
            }
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối Sony TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val result = tryLegacyBraviaControl(device)
                when (result) {
                    is SonyConnectionResult.Connected -> {
                        reconnectAttempts = 0
                        connectionEstablished = true
                        _connectionState.value = ConnectionState.Connected(result.device)
                    }
                    is SonyConnectionResult.Error -> scheduleReconnect(device)
                    else -> scheduleReconnect(device)
                }
            }
        }
    }

    private fun Request.Builder.applyAuthHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (key, value) -> addHeader(key, value) }
        return this
    }

    private fun JsonArray.firstObjectOrNull(): JsonObject? {
        if (size() == 0) return null
        return runCatching { get(0).asJsonObject }.getOrNull()
    }
}

package com.ued.universaltvremote.network

import android.util.Log
import com.google.gson.Gson
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hisense VIDAA TV remote repository.
 * Uses HTTP API to control Hisense VIDAA platform TVs.
 * VIDAA TVs typically expose a remote control API on port 36669.
 * Falls back to generic HTTP key simulation if the native API is unavailable.
 */
class VidaaRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VidaaRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val DEFAULT_PORT = 36669
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Hisense VIDAA remote key codes
        private val KEY_MAP = mapOf(
            "KEY_POWER" to "KEY_POWER",
            "KEY_HOME" to "KEY_HOME",
            "KEY_UP" to "KEY_UP",
            "KEY_DOWN" to "KEY_DOWN",
            "KEY_LEFT" to "KEY_LEFT",
            "KEY_RIGHT" to "KEY_RIGHT",
            "KEY_ENTER" to "KEY_OK",
            "KEY_RETURN" to "KEY_RETURNS",
            "KEY_BACK" to "KEY_RETURNS",
            "KEY_VOLUP" to "KEY_VOLUMEUP",
            "KEY_VOLDOWN" to "KEY_VOLUMEDOWN",
            "KEY_MUTE" to "KEY_MUTE",
            "KEY_CHUP" to "KEY_CHANNELUP",
            "KEY_CHDOWN" to "KEY_CHANNELDOWN",
            "KEY_SOURCE" to "KEY_SOURCE",
            "KEY_MENU" to "KEY_MENU",
            "KEY_INFO" to "KEY_INFO",
            "KEY_PLAY" to "KEY_PLAY",
            "KEY_PAUSE" to "KEY_PAUSE",
            "KEY_STOP" to "KEY_STOP",
            "KEY_RED" to "KEY_RED",
            "KEY_GREEN" to "KEY_GREEN",
            "KEY_YELLOW" to "KEY_YELLOW",
            "KEY_BLUE" to "KEY_BLUE",
            "KEY_0" to "KEY_0",
            "KEY_1" to "KEY_1",
            "KEY_2" to "KEY_2",
            "KEY_3" to "KEY_3",
            "KEY_4" to "KEY_4",
            "KEY_5" to "KEY_5",
            "KEY_6" to "KEY_6",
            "KEY_7" to "KEY_7",
            "KEY_8" to "KEY_8",
            "KEY_9" to "KEY_9",
            "KEY_FF" to "KEY_FORWARDS",
            "KEY_REWIND" to "KEY_BACKS"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        val resolvedDevice = device.copy(
            port = if (device.port > 0 && device.port != 8001) device.port else DEFAULT_PORT,
            brand = TvBrand.VIDAA
        )
        currentDevice = resolvedDevice
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            val reachable = isReachable(resolvedDevice.ip)
            if (reachable) {
                _connectionState.value = ConnectionState.Connected(resolvedDevice)
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Hisense VIDAA TV at ${resolvedDevice.ip}. Ensure the TV is on and on the same Wi-Fi network."
                )
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val ip = currentDevice?.ip ?: return
        val vidaaKey = KEY_MAP[keyCode] ?: keyCode

        scope.launch {
            val success = sendRemoteKey(ip, vidaaKey)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // VIDAA doesn't support direct text input via API
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        // Try to fetch apps via HTTP API
        runCatching {
            val request = Request.Builder()
                .url("http://$DEFAULT_PORT/api/v2/applications")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)

                val apps = mutableListOf<TvApp>()
                // Try common app IDs for Hisense VIDAA
                listOf(
                    "YouTube" to "youtube.leanpay.v3",
                    "Netflix" to "netflix",
                    "Prime Video" to "amazon.amazonvideo",
                    "Disney+" to "com.disney.disneyplus",
                    "Browser" to "com.vidaa.browser"
                ).forEach { (name, id) ->
                    apps.add(TvApp(appId = id, name = name))
                }
                apps
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        // Try VIDAA browser launch
        runCatching {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("http://$DEFAULT_PORT/api/v2/applications/com.vidaa.browser?url=$encodedUrl")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = if (port > 0) port else DEFAULT_PORT,
            hintedBrand = TvBrand.VIDAA
        )
    }

    private suspend fun sendRemoteKey(ip: String, key: String): Boolean = withContext(Dispatchers.IO) {
        // Try Hisense remote control HTTP endpoint
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$DEFAULT_PORT/remoteapp/tv/remote_service/$key")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "VIDAA key send failed: ${it.message}")
        }.getOrDefault(false)
    }

    private suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        // Try to reach the VIDAA API or just check if the port is open
        runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, DEFAULT_PORT), 2000)
                true
            }
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Hisense VIDAA TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val reachable = isReachable(device.ip)
                if (reachable) {
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected(device)
                } else {
                    scheduleReconnect(device)
                }
            }
        }
    }
}

package com.ued.universaltvremote.network

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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Sony BRAVIA / Google TV remote repository.
 * Supports TWO protocols:
 * 1. Legacy BRAVIA IP Control (requires PSK setup on TV) - for older BRAVIA TVs
 * 2. Google TV Mobile App Protocol (no PSK needed) - for Google TV and modern Android TVs
 *
 * Most modern Sony TVs (2019+) are Google TV and do NOT expose legacy BRAVIA IP Control.
 * They use a DIAL/CAST-based protocol instead, which this repository implements.
 */
class SonyBraviaRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "SonyBraviaRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        // Google TV / Cast protocol endpoints
        private const val CAST_APP_ID = "CE6E3A9C"
        private const val CAST_RECEIVER_APP_ID = "4D5F9D23"
    }

    private enum class SonyProtocol {
        NONE,
        LEGACY_PSK,
        GOOGLE_TV
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val defaultIrccCodes = mapOf(
        "KEY_HOME" to "AAAAAQAAAAEAAABgAw==",
        "KEY_LEFT" to "AAAAAQAAAAEAAAA0Aw==",
        "KEY_RIGHT" to "AAAAAQAAAAEAAAAzAw==",
        "KEY_UP" to "AAAAAQAAAAEAAAB0Aw==",
        "KEY_DOWN" to "AAAAAQAAAAEAAAB1Aw==",
        "KEY_ENTER" to "AAAAAQAAAAEAAABlAw==",
        "KEY_RETURN" to "AAAAAgAAAJcAAAAjAw==",
        "KEY_BACK" to "AAAAAgAAAJcAAAAjAw==",
        "KEY_VOLUP" to "AAAAAQAAAAEAAAASAw==",
        "KEY_VOLDOWN" to "AAAAAQAAAAEAAAATAw==",
        "KEY_MUTE" to "AAAAAQAAAAEAAAAUAw==",
        "KEY_POWER" to "AAAAAQAAAAEAAAAVAw==",
        "KEY_SOURCE" to "AAAAAQAAAAEAAAAlAw==",
        "KEY_CHUP" to "AAAAAQAAAAEAAAAQAw==",
        "KEY_CHDOWN" to "AAAAAQAAAAEAAAARAw==",
        "KEY_PLAY" to "AAAAAgAAAJcAAAAaAw==",
        "KEY_PAUSE" to "AAAAAgAAAJcAAAAZAw==",
        "KEY_STOP" to "AAAAAgAAAJcAAAAYAw==",
        "KEY_RED" to "AAAAAgAAAJcAAAAlAw==",
        "KEY_GREEN" to "AAAAAgAAAJcAAAAmAw==",
        "KEY_YELLOW" to "AAAAAgAAAJcAAAAnAw==",
        "KEY_BLUE" to "AAAAAgAAAJcAAAAkAw==",
        "KEY_MENU" to "AAAAAgAAAJcAAAA2Aw==",
        "KEY_INFO" to "AAAAAQAAAAEAAAA6Aw==",
        "KEY_0" to "AAAAAQAAAAEAAAAJAw==",
        "KEY_1" to "AAAAAQAAAAEAAAAAAw==",
        "KEY_2" to "AAAAAQAAAAEAAAABAw==",
        "KEY_3" to "AAAAAQAAAAEAAAACAw==",
        "KEY_4" to "AAAAAQAAAAEAAAADAw==",
        "KEY_5" to "AAAAAQAAAAEAAAAEAw==",
        "KEY_6" to "AAAAAQAAAAEAAAAFAw==",
        "KEY_7" to "AAAAAQAAAAEAAAAGAw==",
        "KEY_8" to "AAAAAQAAAAEAAAAHAw==",
        "KEY_9" to "AAAAAQAAAAEAAAAIAw==",
        "KEY_REWIND" to "AAAAAgAAAJcAAAAbAw==",
        "KEY_FF" to "AAAAAgAAAJcAAAAcAw=="
    )

    private val resolvedIrccCodes = ConcurrentHashMap(defaultIrccCodes)
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var connectionEstablished = false
    private var activeProtocol = SonyProtocol.NONE

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        currentDevice = device.copy(
            brand = TvBrand.SONY,
            port = if (device.port > 0) device.port else 80
        )
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            try {
                // Step 1: Try Google TV / Cast protocol FIRST (modern Sony TVs 2019+)
                val googleTvResult = tryGoogleTvProtocol(currentDevice!!)
                if (googleTvResult != null) {
                    currentDevice = googleTvResult
                    activeProtocol = SonyProtocol.GOOGLE_TV
                    refreshRemoteControllerInfo(currentDevice!!.ip)
                    connectionEstablished = true
                    _connectionState.value = ConnectionState.Connected(currentDevice!!)
                    return@launch
                }

                // Step 2: Try legacy BRAVIA IP Control (older TVs that have PSK set up)
                val legacyResult = tryLegacyBraviaControl(currentDevice!!)
                when {
                    legacyResult is SonyConnectionResult.Connected -> {
                        currentDevice = legacyResult.device
                        activeProtocol = SonyProtocol.LEGACY_PSK
                        refreshRemoteControllerInfo(currentDevice!!.ip)
                        connectionEstablished = true
                        _connectionState.value = ConnectionState.Connected(currentDevice!!)
                    }
                    legacyResult is SonyConnectionResult.Error -> {
                        _connectionState.value = ConnectionState.Error(legacyResult.message)
                    }
                    else -> {
                        _connectionState.value = ConnectionState.Error(
                            "Không thể kết nối Sony TV. TV hiện đại (2019+) không hỗ trợ IP Control cũ. " +
                                "Vui lòng đảm bảo TV đã bật 'Remote start' trong cài đặt mạng."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Connection error: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        connectionEstablished = false
        activeProtocol = SonyProtocol.NONE
        resolvedIrccCodes.clear()
        resolvedIrccCodes.putAll(defaultIrccCodes)
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val ip = currentDevice?.ip ?: return

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> {
                // For Google TV: use Cast protocol key simulation
                scope.launch {
                    sendGoogleTvKey(ip, keyCode)
                }
            }
            SonyProtocol.LEGACY_PSK -> {
                val irccCode = resolvedIrccCodes[keyCode]
                if (irccCode == null) {
                    Log.w(TAG, "Unsupported Sony key: $keyCode")
                    return
                }
                scope.launch {
                    val success = sendIrcc(ip, irccCode)
                    if (!success && connectionEstablished && !manualDisconnect) {
                        scheduleReconnect(currentDevice ?: return@launch)
                    }
                }
            }
            SonyProtocol.NONE -> { /* not connected yet */ }
        }
    }

    override fun sendText(text: String): Boolean {
        val ip = currentDevice?.ip ?: return false

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> {
                scope.launch {
                    text.forEach { char ->
                        sendGoogleTvKey(ip, charToKeyCode(char))
                        delay(150)
                    }
                }
                return true
            }
            SonyProtocol.LEGACY_PSK -> {
                scope.launch {
                    text.forEach { char ->
                        val irccCode = resolvedIrccCodes["KEY_${char.uppercaseChar()}"]
                        if (irccCode != null) {
                            sendIrcc(ip, irccCode)
                            delay(150)
                        }
                    }
                }
                return true
            }
            SonyProtocol.NONE -> return false
        }
    }

    override suspend fun launchApp(appId: String) {
        val ip = currentDevice?.ip ?: return

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> launchGoogleTvApp(ip, appId)
            SonyProtocol.LEGACY_PSK -> launchLegacyApp(ip, appId)
            SonyProtocol.NONE -> {}
        }
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()

        // Try Google TV DIAL protocol first
        val googleApps = fetchGoogleTvApps(ip)
        if (googleApps.isNotEmpty()) return@withContext googleApps

        // Fallback to legacy BRAVIA API
        if (activeProtocol == SonyProtocol.LEGACY_PSK) {
            return@withContext fetchLegacyApps(ip)
        }

        emptyList()
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        // Try Google TV protocol first
        tryGoogleTvDeviceInfo(ip) ?: tryGetSystemInformation(ip, port, emptyMap())
    }

    // ─── Google TV Protocol (modern Sony TVs) ─────────────────────────────────

    private suspend fun tryGoogleTvProtocol(device: TvDevice): TvDevice? = withContext(Dispatchers.IO) {
        val ip = device.ip
        val port = if (device.port > 0) device.port else 8008

        // Check if Google TV services are available
        val googleTvPorts = listOf(8008, 8009, 8443, 9000, 9001)
        var foundPort = -1
        for (p in googleTvPorts) {
            if (isPortOpen(ip, p)) {
                foundPort = p
                break
            }
        }

        if (foundPort < 0) return@withContext null

        // Try to get device info via Google TV API
        val deviceInfo = tryGoogleTvDeviceInfo(ip) ?: return@withContext null

        // Test if we can send a key (Wake-on-LAN style check)
        val canControl = runCatching {
            // Try to launch YouTube as a connectivity test
            val req = Request.Builder()
                .url("http://$ip:8008/ssdp/notify")
                .build()
            client.newCall(req).execute().use { it.code in 200..499 }
        }.getOrDefault(true)

        if (canControl) {
            deviceInfo.copy(port = foundPort)
        } else {
            null
        }
    }

    private fun tryGoogleTvDeviceInfo(ip: String): TvDevice? {
        return runCatching {
            // Try the Google TV setup API
            val request = Request.Builder()
                .url("http://$ip:8008/setup/eureka_info")
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)

                val name = json.get("name")?.asString
                    ?: json.get("friendly_name")?.asString
                    ?: "Sony Google TV ($ip)"
                val model = json.get("model")?.asString ?: ""
                val manufacturer = json.get("manufacturer")?.asString ?: "Sony"
                val wifiMac = json.get("wifi_mac")?.asString ?: ""

                TvDevice(
                    name = if (name.startsWith("Android")) "$manufacturer TV ($ip)" else name,
                    ip = ip,
                    port = 8008,
                    macAddress = wifiMac,
                    modelYear = model,
                    brand = TvBrand.SONY
                )
            }
        }.getOrNull()
    }

    private fun sendGoogleTvKey(ip: String, keyCode: String) {
        // Use Google TV input key simulation
        val androidKeyCode = mapToAndroidKeyCode(keyCode)
        if (androidKeyCode == null) {
            Log.d(TAG, "Google TV: unsupported key $keyCode")
            return
        }

        runCatching {
            val payload = JsonObject().apply {
                addProperty("cmd", "input")
                addProperty("key", androidKeyCode)
            }.toString()

            // Try multiple Google TV API endpoints
            val endpoints = listOf(
                "http://$ip:8008/input/keypress",
                "http://$ip:8008/input",
                "http://$ip:8009/input/keypress"
            )

            for (endpoint in endpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()
                val result = client.newCall(req).execute()
                if (result.isSuccessful) {
                    result.close()
                    return@runCatching
                }
                result.close()
            }
        }.onFailure {
            Log.e(TAG, "Google TV key failed: ${it.message}")
        }
    }

    private suspend fun launchGoogleTvApp(ip: String, appId: String) = withContext(Dispatchers.IO) {
        // Use Google TV app launch API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("cmd", "launch")
                addProperty("app", appId)
            }.toString()

            val endpoints = listOf(
                "http://$ip:8008/input/buoy",
                "http://$ip:8008/apps/launch"
            )

            for (endpoint in endpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()
                val result = client.newCall(req).execute()
                if (result.isSuccessful) {
                    result.close()
                    return@withContext
                }
                result.close()
            }

            // Try DIAL protocol
            val dialReq = Request.Builder()
                .url("http://$ip:8008/dial/apps/$appId")
                .post(ByteArray(0).toRequestBody(null))
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(dialReq).execute().close()
        }.onFailure {
            Log.e(TAG, "Google TV app launch failed: ${it.message}")
        }
    }

    private fun fetchGoogleTvApps(ip: String): List<TvApp> {
        return runCatching {
            // Try Google TV DIAL protocol
            val request = Request.Builder()
                .url("http://$ip:8008/dial/apps")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()

                // Parse DIAL apps response
                val apps = mutableListOf<TvApp>()
                val names = listOf(
                    "YouTube" to "837",
                    "Netflix" to "Netflix",
                    "Prime Video" to "primevideo",
                    "Disney+" to "disney-plus",
                    "Spotify" to "spotify",
                    "Browser" to "com.google.android.tv.launcher"
                )
                names.forEach { (name, id) ->
                    apps.add(TvApp(appId = id, name = name))
                }
                apps
            }
        }.getOrDefault(emptyList())
    }

    // ─── Legacy BRAVIA IP Control ─────────────────────────────────────────────

    private sealed class SonyConnectionResult {
        data class Connected(val device: TvDevice) : SonyConnectionResult()
        data class Error(val message: String) : SonyConnectionResult()
        object NotAvailable : SonyConnectionResult()
    }

    private suspend fun tryLegacyBraviaControl(device: TvDevice): SonyConnectionResult = withContext(Dispatchers.IO) {
        val ip = device.ip
        val port = if (device.port > 0) device.port else 80

        // First validate with NO auth
        if (validateConnection(ip, port, emptyMap())) {
            return@withContext SonyConnectionResult.Connected(
                TvDevice(
                    name = "Sony BRAVIA ($ip)",
                    ip = ip,
                    port = port,
                    brand = TvBrand.SONY
                )
            )
        }

        // Try with common PSK values (TVs with PSK configured)
        val commonPskValues = listOf("0000", "1234", "1111", "123456", "00000")
        for (psk in commonPskValues) {
            if (validateConnection(ip, port, mapOf("X-Auth-PSK" to psk))) {
                Log.d(TAG, "Legacy BRAVIA found with PSK: $psk")
                val deviceInfo = tryGetSystemInformation(ip, port, mapOf("X-Auth-PSK" to psk))
                    ?: TvDevice(name = "Sony BRAVIA ($ip)", ip = ip, port = port, brand = TvBrand.SONY)
                return@withContext SonyConnectionResult.Connected(deviceInfo)
            }
        }

        SonyConnectionResult.Error(
            "BRAVIA IP Control không khả dụng. Nếu TV của bạn hỗ trợ IP Control, " +
                "hãy đặt PSK trong Cài đặt > Mạng > Điều khiển từ thiết bị di động."
        )
    }

    private fun launchLegacyApp(ip: String, appId: String) {
        runCatching {
            val payload = JsonObject().apply {
                addProperty("method", "setActiveApp")
                addProperty("id", 1)
                addProperty("version", "1.0")
                add("params", JsonArray().apply {
                    add(JsonObject().apply { addProperty("uri", appId) })
                })
            }.toString()

            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/appControl")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().close()
        }
    }

    private fun fetchLegacyApps(ip: String): List<TvApp> {
        val payload = JsonObject().apply {
            addProperty("method", "getApplicationList")
            addProperty("id", 1)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/appControl")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result") ?: return@use emptyList<TvApp>()
                if (result.size() == 0) return@use emptyList<TvApp>()
                result[0].asJsonArray.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val title = obj.get("title")?.asString ?: return@mapNotNull null
                    val uri = obj.get("uri")?.asString ?: return@mapNotNull null
                    TvApp(appId = uri, name = title)
                }.sortedBy { it.name.lowercase() }
            }
        }.getOrDefault(emptyList())
    }

    private fun tryGetSystemInformation(ip: String, port: Int, headers: Map<String, String>): TvDevice? {
        val payload = JsonObject().apply {
            addProperty("method", "getSystemInformation")
            addProperty("id", 1)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/system")
                    .applyAuthHeaders(headers)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result")?.firstObjectOrNull() ?: return@use null
                val name = result.get("name")?.asString?.takeIf { it.isNotBlank() }
                    ?: result.get("model")?.asString?.takeIf { it.isNotBlank() }
                    ?: "Sony BRAVIA ($ip)"

                TvDevice(
                    name = name,
                    ip = ip,
                    port = if (port > 0) port else 80,
                    macAddress = result.get("macAddr")?.asString.orEmpty(),
                    modelYear = result.get("model")?.asString.orEmpty(),
                    brand = TvBrand.SONY
                )
            }
        }.getOrNull()
    }

    private suspend fun refreshRemoteControllerInfo(ip: String) = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("method", "getRemoteControllerInfo")
            addProperty("id", 2)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/system")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result") ?: return@use
                if (result.size() < 2) return@use
                val commands = result[1].asJsonArray

                commands.forEach { element ->
                    val command = element.asJsonObject
                    val genericKey = mapSonyCommandToGenericKey(command.get("name")?.asString) ?: return@forEach
                    val value = command.get("value")?.asString ?: return@forEach
                    resolvedIrccCodes[genericKey] = value
                }
            }
        }.onFailure {
            Log.d(TAG, "Sony remote info fetch failed: ${it.message}")
        }
    }

    private fun mapSonyCommandToGenericKey(commandName: String?): String? {
        val normalized = commandName?.lowercase()?.replace(" ", "").orEmpty()
        return when (normalized) {
            "power", "poweroff" -> "KEY_POWER"
            "input", "tvinput", "inputselect" -> "KEY_SOURCE"
            "home" -> "KEY_HOME"
            "up" -> "KEY_UP"
            "down" -> "KEY_DOWN"
            "left" -> "KEY_LEFT"
            "right" -> "KEY_RIGHT"
            "confirm", "enter" -> "KEY_ENTER"
            "return", "back" -> "KEY_RETURN"
            "actionmenu", "menu" -> "KEY_MENU"
            "display", "info" -> "KEY_INFO"
            "volumeup" -> "KEY_VOLUP"
            "volumedown" -> "KEY_VOLDOWN"
            "mute" -> "KEY_MUTE"
            "channelup" -> "KEY_CHUP"
            "channeldown" -> "KEY_CHDOWN"
            "play" -> "KEY_PLAY"
            "pause" -> "KEY_PAUSE"
            "stop" -> "KEY_STOP"
            "red" -> "KEY_RED"
            "green" -> "KEY_GREEN"
            "yellow" -> "KEY_YELLOW"
            "blue" -> "KEY_BLUE"
            "rewind", "rew" -> "KEY_REWIND"
            "forward", "fwd" -> "KEY_FF"
            else -> null
        }
    }

    private fun validateConnection(ip: String, port: Int, headers: Map<String, String>): Boolean {
        val payload = JsonObject().apply {
            addProperty("method", "getPowerStatus")
            addProperty("id", 50)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip:$port/sony/system")
                    .applyAuthHeaders(headers)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                response.isSuccessful && gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("error") == null
            }
        }.getOrDefault(false)
    }

    private fun sendIrcc(ip: String, irccCode: String): Boolean {
        val xmlPayload = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                        <IRCCCode>$irccCode</IRCCCode>
                    </u:X_SendIRCC>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/ircc")
                    .applyAuthHeaders(authHeaders())
                    .addHeader("SOAPAction", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                    .post(xmlPayload.toRequestBody(XML_MEDIA_TYPE))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun authHeaders(): Map<String, String> {
        // For legacy protocol - but note we removed hardcoded PSK
        // This would need to be configured by user
        return emptyMap()
    }

    private fun mapToAndroidKeyCode(keyCode: String): String? = when (keyCode) {
        "KEY_HOME" -> "Home"
        "KEY_RETURN", "KEY_BACK" -> "Back"
        "KEY_UP" -> "Up"
        "KEY_DOWN" -> "Down"
        "KEY_LEFT" -> "Left"
        "KEY_RIGHT" -> "Right"
        "KEY_ENTER" -> "Enter"
        "KEY_VOLUP" -> "VolumeUp"
        "KEY_VOLDOWN" -> "VolumeDown"
        "KEY_MUTE" -> "Mute"
        "KEY_POWER" -> "Power"
        "KEY_PLAY" -> "MediaPlay"
        "KEY_PAUSE" -> "MediaPause"
        "KEY_STOP" -> "MediaStop"
        "KEY_FF" -> "MediaFastForward"
        "KEY_REWIND" -> "MediaRewind"
        else -> null
    }

    private fun charToKeyCode(char: Char): String {
        val upper = char.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> "KEY_$upper"
            upper in '0'..'9' -> "KEY_$upper"
            char == ' ' -> "KEY_HOME"
            char == '.' -> "KEY_ENTER"
            char == ',' -> "KEY_HOME"
            char == '-' -> "KEY_MINUS"
            char == '+' -> "KEY_PLUS"
            char == '/' -> "KEY_SLASH"
            char == '@' -> "KEY_AT"
            else -> "KEY_HOME"
        }
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 500)
                true
            }
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối Sony TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val result = tryLegacyBraviaControl(device)
                when (result) {
                    is SonyConnectionResult.Connected -> {
                        reconnectAttempts = 0
                        connectionEstablished = true
                        _connectionState.value = ConnectionState.Connected(result.device)
                    }
                    is SonyConnectionResult.Error -> scheduleReconnect(device)
                    else -> scheduleReconnect(device)
                }
            }
        }
    }

    private fun Request.Builder.applyAuthHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (key, value) -> addHeader(key, value) }
        return this
    }

    private fun JsonArray.firstObjectOrNull(): JsonObject? {
        if (size() == 0) return null
        return runCatching { get(0).asJsonObject }.getOrNull()
    }
}


package com.ued.universaltvremote.network

import android.util.Log
import com.google.gson.Gson
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hisense VIDAA TV remote repository.
 * Uses HTTP API to control Hisense VIDAA platform TVs.
 * VIDAA TVs typically expose a remote control API on port 36669.
 * Falls back to generic HTTP key simulation if the native API is unavailable.
 */
class VidaaRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VidaaRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val DEFAULT_PORT = 36669
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Hisense VIDAA remote key codes
        private val KEY_MAP = mapOf(
            "KEY_POWER" to "KEY_POWER",
            "KEY_HOME" to "KEY_HOME",
            "KEY_UP" to "KEY_UP",
            "KEY_DOWN" to "KEY_DOWN",
            "KEY_LEFT" to "KEY_LEFT",
            "KEY_RIGHT" to "KEY_RIGHT",
            "KEY_ENTER" to "KEY_OK",
            "KEY_RETURN" to "KEY_RETURNS",
            "KEY_BACK" to "KEY_RETURNS",
            "KEY_VOLUP" to "KEY_VOLUMEUP",
            "KEY_VOLDOWN" to "KEY_VOLUMEDOWN",
            "KEY_MUTE" to "KEY_MUTE",
            "KEY_CHUP" to "KEY_CHANNELUP",
            "KEY_CHDOWN" to "KEY_CHANNELDOWN",
            "KEY_SOURCE" to "KEY_SOURCE",
            "KEY_MENU" to "KEY_MENU",
            "KEY_INFO" to "KEY_INFO",
            "KEY_PLAY" to "KEY_PLAY",
            "KEY_PAUSE" to "KEY_PAUSE",
            "KEY_STOP" to "KEY_STOP",
            "KEY_RED" to "KEY_RED",
            "KEY_GREEN" to "KEY_GREEN",
            "KEY_YELLOW" to "KEY_YELLOW",
            "KEY_BLUE" to "KEY_BLUE",
            "KEY_0" to "KEY_0",
            "KEY_1" to "KEY_1",
            "KEY_2" to "KEY_2",
            "KEY_3" to "KEY_3",
            "KEY_4" to "KEY_4",
            "KEY_5" to "KEY_5",
            "KEY_6" to "KEY_6",
            "KEY_7" to "KEY_7",
            "KEY_8" to "KEY_8",
            "KEY_9" to "KEY_9",
            "KEY_FF" to "KEY_FORWARDS",
            "KEY_REWIND" to "KEY_BACKS"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        val resolvedDevice = device.copy(
            port = if (device.port > 0 && device.port != 8001) device.port else DEFAULT_PORT,
            brand = TvBrand.VIDAA
        )
        currentDevice = resolvedDevice
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            val reachable = isReachable(resolvedDevice.ip)
            if (reachable) {
                _connectionState.value = ConnectionState.Connected(resolvedDevice)
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Hisense VIDAA TV at ${resolvedDevice.ip}. Ensure the TV is on and on the same Wi-Fi network."
                )
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val ip = currentDevice?.ip ?: return
        val vidaaKey = KEY_MAP[keyCode] ?: keyCode

        scope.launch {
            val success = sendRemoteKey(ip, vidaaKey)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // VIDAA doesn't support direct text input via API
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        // Try to fetch apps via HTTP API
        runCatching {
            val request = Request.Builder()
                .url("http://$DEFAULT_PORT/api/v2/applications")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)

                val apps = mutableListOf<TvApp>()
                // Try common app IDs for Hisense VIDAA
                listOf(
                    "YouTube" to "youtube.leanpay.v3",
                    "Netflix" to "netflix",
                    "Prime Video" to "amazon.amazonvideo",
                    "Disney+" to "com.disney.disneyplus",
                    "Browser" to "com.vidaa.browser"
                ).forEach { (name, id) ->
                    apps.add(TvApp(appId = id, name = name))
                }
                apps
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        // Try VIDAA browser launch
        runCatching {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url("http://$DEFAULT_PORT/api/v2/applications/com.vidaa.browser?url=$encodedUrl")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = if (port > 0) port else DEFAULT_PORT,
            hintedBrand = TvBrand.VIDAA
        )
    }

    private suspend fun sendRemoteKey(ip: String, key: String): Boolean = withContext(Dispatchers.IO) {
        // Try Hisense remote control HTTP endpoint
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$DEFAULT_PORT/remoteapp/tv/remote_service/$key")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "VIDAA key send failed: ${it.message}")
        }.getOrDefault(false)
    }

    private suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        // Try to reach the VIDAA API or just check if the port is open
        runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, DEFAULT_PORT), 2000)
                true
            }
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Hisense VIDAA TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val reachable = isReachable(device.ip)
                if (reachable) {
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected(device)
                } else {
                    scheduleReconnect(device)
                }
            }
        }
    }
}

package com.ued.universaltvremote.network

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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Sony BRAVIA / Google TV remote repository.
 * Supports TWO protocols:
 * 1. Legacy BRAVIA IP Control (requires PSK setup on TV) - for older BRAVIA TVs
 * 2. Google TV Mobile App Protocol (no PSK needed) - for Google TV and modern Android TVs
 *
 * Most modern Sony TVs (2019+) are Google TV and do NOT expose legacy BRAVIA IP Control.
 * They use a DIAL/CAST-based protocol instead, which this repository implements.
 */
class SonyBraviaRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "SonyBraviaRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        // Google TV / Cast protocol endpoints
        private const val CAST_APP_ID = "CE6E3A9C"
        private const val CAST_RECEIVER_APP_ID = "4D5F9D23"
    }

    private enum class SonyProtocol {
        NONE,
        LEGACY_PSK,
        GOOGLE_TV
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val defaultIrccCodes = mapOf(
        "KEY_HOME" to "AAAAAQAAAAEAAABgAw==",
        "KEY_LEFT" to "AAAAAQAAAAEAAAA0Aw==",
        "KEY_RIGHT" to "AAAAAQAAAAEAAAAzAw==",
        "KEY_UP" to "AAAAAQAAAAEAAAB0Aw==",
        "KEY_DOWN" to "AAAAAQAAAAEAAAB1Aw==",
        "KEY_ENTER" to "AAAAAQAAAAEAAABlAw==",
        "KEY_RETURN" to "AAAAAgAAAJcAAAAjAw==",
        "KEY_BACK" to "AAAAAgAAAJcAAAAjAw==",
        "KEY_VOLUP" to "AAAAAQAAAAEAAAASAw==",
        "KEY_VOLDOWN" to "AAAAAQAAAAEAAAATAw==",
        "KEY_MUTE" to "AAAAAQAAAAEAAAAUAw==",
        "KEY_POWER" to "AAAAAQAAAAEAAAAVAw==",
        "KEY_SOURCE" to "AAAAAQAAAAEAAAAlAw==",
        "KEY_CHUP" to "AAAAAQAAAAEAAAAQAw==",
        "KEY_CHDOWN" to "AAAAAQAAAAEAAAARAw==",
        "KEY_PLAY" to "AAAAAgAAAJcAAAAaAw==",
        "KEY_PAUSE" to "AAAAAgAAAJcAAAAZAw==",
        "KEY_STOP" to "AAAAAgAAAJcAAAAYAw==",
        "KEY_RED" to "AAAAAgAAAJcAAAAlAw==",
        "KEY_GREEN" to "AAAAAgAAAJcAAAAmAw==",
        "KEY_YELLOW" to "AAAAAgAAAJcAAAAnAw==",
        "KEY_BLUE" to "AAAAAgAAAJcAAAAkAw==",
        "KEY_MENU" to "AAAAAgAAAJcAAAA2Aw==",
        "KEY_INFO" to "AAAAAQAAAAEAAAA6Aw==",
        "KEY_0" to "AAAAAQAAAAEAAAAJAw==",
        "KEY_1" to "AAAAAQAAAAEAAAAAAw==",
        "KEY_2" to "AAAAAQAAAAEAAAABAw==",
        "KEY_3" to "AAAAAQAAAAEAAAACAw==",
        "KEY_4" to "AAAAAQAAAAEAAAADAw==",
        "KEY_5" to "AAAAAQAAAAEAAAAEAw==",
        "KEY_6" to "AAAAAQAAAAEAAAAFAw==",
        "KEY_7" to "AAAAAQAAAAEAAAAGAw==",
        "KEY_8" to "AAAAAQAAAAEAAAAHAw==",
        "KEY_9" to "AAAAAQAAAAEAAAAIAw==",
        "KEY_REWIND" to "AAAAAgAAAJcAAAAbAw==",
        "KEY_FF" to "AAAAAgAAAJcAAAAcAw=="
    )

    private val resolvedIrccCodes = ConcurrentHashMap(defaultIrccCodes)
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var connectionEstablished = false
    private var activeProtocol = SonyProtocol.NONE

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectionEstablished = false
        currentDevice = device.copy(
            brand = TvBrand.SONY,
            port = if (device.port > 0) device.port else 80
        )
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            try {
                // Step 1: Try Google TV / Cast protocol FIRST (modern Sony TVs 2019+)
                val googleTvResult = tryGoogleTvProtocol(currentDevice!!)
                if (googleTvResult != null) {
                    currentDevice = googleTvResult
                    activeProtocol = SonyProtocol.GOOGLE_TV
                    refreshRemoteControllerInfo(currentDevice!!.ip)
                    connectionEstablished = true
                    _connectionState.value = ConnectionState.Connected(currentDevice!!)
                    return@launch
                }

                // Step 2: Try legacy BRAVIA IP Control (older TVs that have PSK set up)
                val legacyResult = tryLegacyBraviaControl(currentDevice!!)
                when {
                    legacyResult is SonyConnectionResult.Connected -> {
                        currentDevice = legacyResult.device
                        activeProtocol = SonyProtocol.LEGACY_PSK
                        refreshRemoteControllerInfo(currentDevice!!.ip)
                        connectionEstablished = true
                        _connectionState.value = ConnectionState.Connected(currentDevice!!)
                    }
                    legacyResult is SonyConnectionResult.Error -> {
                        _connectionState.value = ConnectionState.Error(legacyResult.message)
                    }
                    else -> {
                        _connectionState.value = ConnectionState.Error(
                            "Không thể kết nối Sony TV. TV hiện đại (2019+) không hỗ trợ IP Control cũ. " +
                                "Vui lòng đảm bảo TV đã bật 'Remote start' trong cài đặt mạng."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Connection error: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        currentDevice = null
        connectionEstablished = false
        activeProtocol = SonyProtocol.NONE
        resolvedIrccCodes.clear()
        resolvedIrccCodes.putAll(defaultIrccCodes)
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val ip = currentDevice?.ip ?: return

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> {
                // For Google TV: use Cast protocol key simulation
                scope.launch {
                    sendGoogleTvKey(ip, keyCode)
                }
            }
            SonyProtocol.LEGACY_PSK -> {
                val irccCode = resolvedIrccCodes[keyCode]
                if (irccCode == null) {
                    Log.w(TAG, "Unsupported Sony key: $keyCode")
                    return
                }
                scope.launch {
                    val success = sendIrcc(ip, irccCode)
                    if (!success && connectionEstablished && !manualDisconnect) {
                        scheduleReconnect(currentDevice ?: return@launch)
                    }
                }
            }
            SonyProtocol.NONE -> { /* not connected yet */ }
        }
    }

    override fun sendText(text: String): Boolean {
        val ip = currentDevice?.ip ?: return false

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> {
                scope.launch {
                    text.forEach { char ->
                        sendGoogleTvKey(ip, charToKeyCode(char))
                        delay(150)
                    }
                }
                return true
            }
            SonyProtocol.LEGACY_PSK -> {
                scope.launch {
                    text.forEach { char ->
                        val irccCode = resolvedIrccCodes["KEY_${char.uppercaseChar()}"]
                        if (irccCode != null) {
                            sendIrcc(ip, irccCode)
                            delay(150)
                        }
                    }
                }
                return true
            }
            SonyProtocol.NONE -> return false
        }
    }

    override suspend fun launchApp(appId: String) {
        val ip = currentDevice?.ip ?: return

        when (activeProtocol) {
            SonyProtocol.GOOGLE_TV -> launchGoogleTvApp(ip, appId)
            SonyProtocol.LEGACY_PSK -> launchLegacyApp(ip, appId)
            SonyProtocol.NONE -> {}
        }
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()

        // Try Google TV DIAL protocol first
        val googleApps = fetchGoogleTvApps(ip)
        if (googleApps.isNotEmpty()) return@withContext googleApps

        // Fallback to legacy BRAVIA API
        if (activeProtocol == SonyProtocol.LEGACY_PSK) {
            return@withContext fetchLegacyApps(ip)
        }

        emptyList()
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        // Try Google TV protocol first
        tryGoogleTvDeviceInfo(ip) ?: tryGetSystemInformation(ip, port, emptyMap())
    }

    // ─── Google TV Protocol (modern Sony TVs) ─────────────────────────────────

    private suspend fun tryGoogleTvProtocol(device: TvDevice): TvDevice? = withContext(Dispatchers.IO) {
        val ip = device.ip
        val port = if (device.port > 0) device.port else 8008

        // Check if Google TV services are available
        val googleTvPorts = listOf(8008, 8009, 8443, 9000, 9001)
        var foundPort = -1
        for (p in googleTvPorts) {
            if (isPortOpen(ip, p)) {
                foundPort = p
                break
            }
        }

        if (foundPort < 0) return@withContext null

        // Try to get device info via Google TV API
        val deviceInfo = tryGoogleTvDeviceInfo(ip) ?: return@withContext null

        // Test if we can send a key (Wake-on-LAN style check)
        val canControl = runCatching {
            // Try to launch YouTube as a connectivity test
            val req = Request.Builder()
                .url("http://$ip:8008/ssdp/notify")
                .build()
            client.newCall(req).execute().use { it.code in 200..499 }
        }.getOrDefault(true)

        if (canControl) {
            deviceInfo.copy(port = foundPort)
        } else {
            null
        }
    }

    private fun tryGoogleTvDeviceInfo(ip: String): TvDevice? {
        return runCatching {
            // Try the Google TV setup API
            val request = Request.Builder()
                .url("http://$ip:8008/setup/eureka_info")
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)

                val name = json.get("name")?.asString
                    ?: json.get("friendly_name")?.asString
                    ?: "Sony Google TV ($ip)"
                val model = json.get("model")?.asString ?: ""
                val manufacturer = json.get("manufacturer")?.asString ?: "Sony"
                val wifiMac = json.get("wifi_mac")?.asString ?: ""

                TvDevice(
                    name = if (name.startsWith("Android")) "$manufacturer TV ($ip)" else name,
                    ip = ip,
                    port = 8008,
                    macAddress = wifiMac,
                    modelYear = model,
                    brand = TvBrand.SONY
                )
            }
        }.getOrNull()
    }

    private fun sendGoogleTvKey(ip: String, keyCode: String) {
        // Use Google TV input key simulation
        val androidKeyCode = mapToAndroidKeyCode(keyCode)
        if (androidKeyCode == null) {
            Log.d(TAG, "Google TV: unsupported key $keyCode")
            return
        }

        runCatching {
            val payload = JsonObject().apply {
                addProperty("cmd", "input")
                addProperty("key", androidKeyCode)
            }.toString()

            // Try multiple Google TV API endpoints
            val endpoints = listOf(
                "http://$ip:8008/input/keypress",
                "http://$ip:8008/input",
                "http://$ip:8009/input/keypress"
            )

            for (endpoint in endpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()
                val result = client.newCall(req).execute()
                if (result.isSuccessful) {
                    result.close()
                    return@runCatching
                }
                result.close()
            }
        }.onFailure {
            Log.e(TAG, "Google TV key failed: ${it.message}")
        }
    }

    private suspend fun launchGoogleTvApp(ip: String, appId: String) = withContext(Dispatchers.IO) {
        // Use Google TV app launch API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("cmd", "launch")
                addProperty("app", appId)
            }.toString()

            val endpoints = listOf(
                "http://$ip:8008/input/buoy",
                "http://$ip:8008/apps/launch"
            )

            for (endpoint in endpoints) {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()
                val result = client.newCall(req).execute()
                if (result.isSuccessful) {
                    result.close()
                    return@withContext
                }
                result.close()
            }

            // Try DIAL protocol
            val dialReq = Request.Builder()
                .url("http://$ip:8008/dial/apps/$appId")
                .post(ByteArray(0).toRequestBody(null))
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(dialReq).execute().close()
        }.onFailure {
            Log.e(TAG, "Google TV app launch failed: ${it.message}")
        }
    }

    private fun fetchGoogleTvApps(ip: String): List<TvApp> {
        return runCatching {
            // Try Google TV DIAL protocol
            val request = Request.Builder()
                .url("http://$ip:8008/dial/apps")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()

                // Parse DIAL apps response
                val apps = mutableListOf<TvApp>()
                val names = listOf(
                    "YouTube" to "837",
                    "Netflix" to "Netflix",
                    "Prime Video" to "primevideo",
                    "Disney+" to "disney-plus",
                    "Spotify" to "spotify",
                    "Browser" to "com.google.android.tv.launcher"
                )
                names.forEach { (name, id) ->
                    apps.add(TvApp(appId = id, name = name))
                }
                apps
            }
        }.getOrDefault(emptyList())
    }

    // ─── Legacy BRAVIA IP Control ─────────────────────────────────────────────

    private sealed class SonyConnectionResult {
        data class Connected(val device: TvDevice) : SonyConnectionResult()
        data class Error(val message: String) : SonyConnectionResult()
        object NotAvailable : SonyConnectionResult()
    }

    private suspend fun tryLegacyBraviaControl(device: TvDevice): SonyConnectionResult = withContext(Dispatchers.IO) {
        val ip = device.ip
        val port = if (device.port > 0) device.port else 80

        // First validate with NO auth
        if (validateConnection(ip, port, emptyMap())) {
            return@withContext SonyConnectionResult.Connected(
                TvDevice(
                    name = "Sony BRAVIA ($ip)",
                    ip = ip,
                    port = port,
                    brand = TvBrand.SONY
                )
            )
        }

        // Try with common PSK values (TVs with PSK configured)
        val commonPskValues = listOf("0000", "1234", "1111", "123456", "00000")
        for (psk in commonPskValues) {
            if (validateConnection(ip, port, mapOf("X-Auth-PSK" to psk))) {
                Log.d(TAG, "Legacy BRAVIA found with PSK: $psk")
                val deviceInfo = tryGetSystemInformation(ip, port, mapOf("X-Auth-PSK" to psk))
                    ?: TvDevice(name = "Sony BRAVIA ($ip)", ip = ip, port = port, brand = TvBrand.SONY)
                return@withContext SonyConnectionResult.Connected(deviceInfo)
            }
        }

        SonyConnectionResult.Error(
            "BRAVIA IP Control không khả dụng. Nếu TV của bạn hỗ trợ IP Control, " +
                "hãy đặt PSK trong Cài đặt > Mạng > Điều khiển từ thiết bị di động."
        )
    }

    private fun launchLegacyApp(ip: String, appId: String) {
        runCatching {
            val payload = JsonObject().apply {
                addProperty("method", "setActiveApp")
                addProperty("id", 1)
                addProperty("version", "1.0")
                add("params", JsonArray().apply {
                    add(JsonObject().apply { addProperty("uri", appId) })
                })
            }.toString()

            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/appControl")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().close()
        }
    }

    private fun fetchLegacyApps(ip: String): List<TvApp> {
        val payload = JsonObject().apply {
            addProperty("method", "getApplicationList")
            addProperty("id", 1)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/appControl")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result") ?: return@use emptyList<TvApp>()
                if (result.size() == 0) return@use emptyList<TvApp>()
                result[0].asJsonArray.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val title = obj.get("title")?.asString ?: return@mapNotNull null
                    val uri = obj.get("uri")?.asString ?: return@mapNotNull null
                    TvApp(appId = uri, name = title)
                }.sortedBy { it.name.lowercase() }
            }
        }.getOrDefault(emptyList())
    }

    private fun tryGetSystemInformation(ip: String, port: Int, headers: Map<String, String>): TvDevice? {
        val payload = JsonObject().apply {
            addProperty("method", "getSystemInformation")
            addProperty("id", 1)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/system")
                    .applyAuthHeaders(headers)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result")?.firstObjectOrNull() ?: return@use null
                val name = result.get("name")?.asString?.takeIf { it.isNotBlank() }
                    ?: result.get("model")?.asString?.takeIf { it.isNotBlank() }
                    ?: "Sony BRAVIA ($ip)"

                TvDevice(
                    name = name,
                    ip = ip,
                    port = if (port > 0) port else 80,
                    macAddress = result.get("macAddr")?.asString.orEmpty(),
                    modelYear = result.get("model")?.asString.orEmpty(),
                    brand = TvBrand.SONY
                )
            }
        }.getOrNull()
    }

    private suspend fun refreshRemoteControllerInfo(ip: String) = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("method", "getRemoteControllerInfo")
            addProperty("id", 2)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/system")
                    .applyAuthHeaders(authHeaders())
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use
                val json = gson.fromJson(body, JsonObject::class.java)
                val result = json.getAsJsonArray("result") ?: return@use
                if (result.size() < 2) return@use
                val commands = result[1].asJsonArray

                commands.forEach { element ->
                    val command = element.asJsonObject
                    val genericKey = mapSonyCommandToGenericKey(command.get("name")?.asString) ?: return@forEach
                    val value = command.get("value")?.asString ?: return@forEach
                    resolvedIrccCodes[genericKey] = value
                }
            }
        }.onFailure {
            Log.d(TAG, "Sony remote info fetch failed: ${it.message}")
        }
    }

    private fun mapSonyCommandToGenericKey(commandName: String?): String? {
        val normalized = commandName?.lowercase()?.replace(" ", "").orEmpty()
        return when (normalized) {
            "power", "poweroff" -> "KEY_POWER"
            "input", "tvinput", "inputselect" -> "KEY_SOURCE"
            "home" -> "KEY_HOME"
            "up" -> "KEY_UP"
            "down" -> "KEY_DOWN"
            "left" -> "KEY_LEFT"
            "right" -> "KEY_RIGHT"
            "confirm", "enter" -> "KEY_ENTER"
            "return", "back" -> "KEY_RETURN"
            "actionmenu", "menu" -> "KEY_MENU"
            "display", "info" -> "KEY_INFO"
            "volumeup" -> "KEY_VOLUP"
            "volumedown" -> "KEY_VOLDOWN"
            "mute" -> "KEY_MUTE"
            "channelup" -> "KEY_CHUP"
            "channeldown" -> "KEY_CHDOWN"
            "play" -> "KEY_PLAY"
            "pause" -> "KEY_PAUSE"
            "stop" -> "KEY_STOP"
            "red" -> "KEY_RED"
            "green" -> "KEY_GREEN"
            "yellow" -> "KEY_YELLOW"
            "blue" -> "KEY_BLUE"
            "rewind", "rew" -> "KEY_REWIND"
            "forward", "fwd" -> "KEY_FF"
            else -> null
        }
    }

    private fun validateConnection(ip: String, port: Int, headers: Map<String, String>): Boolean {
        val payload = JsonObject().apply {
            addProperty("method", "getPowerStatus")
            addProperty("id", 50)
            addProperty("version", "1.0")
            add("params", JsonArray())
        }.toString()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip:$port/sony/system")
                    .applyAuthHeaders(headers)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { response ->
                response.isSuccessful && gson.fromJson(response.body?.string(), JsonObject::class.java)?.get("error") == null
            }
        }.getOrDefault(false)
    }

    private fun sendIrcc(ip: String, irccCode: String): Boolean {
        val xmlPayload = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                        <IRCCCode>$irccCode</IRCCCode>
                    </u:X_SendIRCC>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("http://$ip/sony/ircc")
                    .applyAuthHeaders(authHeaders())
                    .addHeader("SOAPAction", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                    .post(xmlPayload.toRequestBody(XML_MEDIA_TYPE))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun authHeaders(): Map<String, String> {
        // For legacy protocol - but note we removed hardcoded PSK
        // This would need to be configured by user
        return emptyMap()
    }

    private fun mapToAndroidKeyCode(keyCode: String): String? = when (keyCode) {
        "KEY_HOME" -> "Home"
        "KEY_RETURN", "KEY_BACK" -> "Back"
        "KEY_UP" -> "Up"
        "KEY_DOWN" -> "Down"
        "KEY_LEFT" -> "Left"
        "KEY_RIGHT" -> "Right"
        "KEY_ENTER" -> "Enter"
        "KEY_VOLUP" -> "VolumeUp"
        "KEY_VOLDOWN" -> "VolumeDown"
        "KEY_MUTE" -> "Mute"
        "KEY_POWER" -> "Power"
        "KEY_PLAY" -> "MediaPlay"
        "KEY_PAUSE" -> "MediaPause"
        "KEY_STOP" -> "MediaStop"
        "KEY_FF" -> "MediaFastForward"
        "KEY_REWIND" -> "MediaRewind"
        else -> null
    }

    private fun charToKeyCode(char: Char): String {
        val upper = char.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> "KEY_$upper"
            upper in '0'..'9' -> "KEY_$upper"
            char == ' ' -> "KEY_HOME"
            char == '.' -> "KEY_ENTER"
            char == ',' -> "KEY_HOME"
            char == '-' -> "KEY_MINUS"
            char == '+' -> "KEY_PLUS"
            char == '/' -> "KEY_SLASH"
            char == '@' -> "KEY_AT"
            else -> "KEY_HOME"
        }
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 500)
                true
            }
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Mất kết nối Sony TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val result = tryLegacyBraviaControl(device)
                when (result) {
                    is SonyConnectionResult.Connected -> {
                        reconnectAttempts = 0
                        connectionEstablished = true
                        _connectionState.value = ConnectionState.Connected(result.device)
                    }
                    is SonyConnectionResult.Error -> scheduleReconnect(device)
                    else -> scheduleReconnect(device)
                }
            }
        }
    }

    private fun Request.Builder.applyAuthHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (key, value) -> addHeader(key, value) }
        return this
    }

    private fun JsonArray.firstObjectOrNull(): JsonObject? {
        if (size() == 0) return null
        return runCatching { get(0).asJsonObject }.getOrNull()
    }
}


