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
