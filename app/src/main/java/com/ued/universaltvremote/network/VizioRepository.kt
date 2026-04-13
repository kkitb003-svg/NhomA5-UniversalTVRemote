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
import java.util.concurrent.TimeUnit

/**
 * Vizio SmartCast TV remote repository.
 * Uses the SmartCast HTTP REST API to control Vizio TVs.
 * Default port: 7345 (HTTPS) or 9000 (HTTP)
 * Auth: AUTH_TOKEN header with pairing token
 */
class VizioRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VizioRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SMARTCAST_PORTS = listOf(7345, 9000)

        // Vizio SmartCast key codes (codeset, code)
        private val KEY_MAP = mapOf(
            "KEY_POWER" to Pair(11, 0),
            "KEY_POWEROFF" to Pair(11, 0),
            "KEY_HOME" to Pair(4, 3),
            "KEY_UP" to Pair(3, 8),
            "KEY_DOWN" to Pair(3, 0),
            "KEY_LEFT" to Pair(3, 1),
            "KEY_RIGHT" to Pair(3, 7),
            "KEY_ENTER" to Pair(3, 2),
            "KEY_RETURN" to Pair(4, 0),
            "KEY_BACK" to Pair(4, 0),
            "KEY_VOLUP" to Pair(5, 1),
            "KEY_VOLDOWN" to Pair(5, 0),
            "KEY_MUTE" to Pair(5, 3),
            "KEY_CHUP" to Pair(8, 1),
            "KEY_CHDOWN" to Pair(8, 0),
            "KEY_SOURCE" to Pair(7, 1),
            "KEY_MENU" to Pair(4, 8),
            "KEY_INFO" to Pair(4, 6),
            "KEY_PLAY" to Pair(2, 3),
            "KEY_PAUSE" to Pair(2, 2),
            "KEY_STOP" to Pair(2, 0),
            "KEY_FF" to Pair(2, 1),
            "KEY_REWIND" to Pair(2, 4)
        )
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var authToken: String = ""
    private var smartCastPort: Int = 7345
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.VIZIO)
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            // Try to find the SmartCast port
            val port = findSmartCastPort(device.ip)
            if (port != null) {
                smartCastPort = port
                _connectionState.value = ConnectionState.Connected(device.copy(brand = TvBrand.VIZIO, port = port))
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Vizio SmartCast API. Ensure the TV is on and on the same Wi-Fi network."
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
        val keyPair = KEY_MAP[keyCode]
        if (keyPair == null) {
            Log.w(TAG, "Unsupported Vizio key: $keyCode")
            return
        }

        scope.launch {
            val payload = JsonObject().apply {
                add("KEYLIST", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("CODESET", keyPair.first)
                        addProperty("CODE", keyPair.second)
                        addProperty("ACTION", "KEYPRESS")
                    })
                })
            }.toString()

            val success = httpPut("http://$ip:$smartCastPort/key_command/", payload)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // Vizio SmartCast doesn't support text input directly
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$smartCastPort/app/current")
                .apply { if (authToken.isNotBlank()) addHeader("AUTH", authToken) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = json.getAsJsonArray("ITEMS") ?: return@use emptyList<TvApp>()
                items.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("NAME")?.asString ?: return@mapNotNull null
                    val value = obj.get("VALUE")?.asJsonObject
                    val appId = value?.get("NAME_SPACE")?.asString ?: name
                    TvApp(appId = appId, name = name)
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext false
        // Try SmartCast browser API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("URL", url)
            }.toString()
            httpPost("http://$ip:$smartCastPort/browser/open", payload)
        }.getOrDefault(false)
    }

    private suspend fun httpPost(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = port,
            hintedBrand = TvBrand.VIZIO
        )
    }

    private suspend fun findSmartCastPort(ip: String): Int? = withContext(Dispatchers.IO) {
        SMARTCAST_PORTS.forEach { port ->
            val reachable = runCatching {
                val request = Request.Builder()
                    .url("http://$ip:$port/state/device/deviceinfo")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachable) return@withContext port
        }
        null
    }

    private suspend fun httpPut(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "Vizio PUT failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Vizio TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val port = findSmartCastPort(device.ip)
                if (port != null) {
                    smartCastPort = port
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
import java.util.concurrent.TimeUnit

/**
 * Vizio SmartCast TV remote repository.
 * Uses the SmartCast HTTP REST API to control Vizio TVs.
 * Default port: 7345 (HTTPS) or 9000 (HTTP)
 * Auth: AUTH_TOKEN header with pairing token
 */
class VizioRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VizioRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SMARTCAST_PORTS = listOf(7345, 9000)

        // Vizio SmartCast key codes (codeset, code)
        private val KEY_MAP = mapOf(
            "KEY_POWER" to Pair(11, 0),
            "KEY_POWEROFF" to Pair(11, 0),
            "KEY_HOME" to Pair(4, 3),
            "KEY_UP" to Pair(3, 8),
            "KEY_DOWN" to Pair(3, 0),
            "KEY_LEFT" to Pair(3, 1),
            "KEY_RIGHT" to Pair(3, 7),
            "KEY_ENTER" to Pair(3, 2),
            "KEY_RETURN" to Pair(4, 0),
            "KEY_BACK" to Pair(4, 0),
            "KEY_VOLUP" to Pair(5, 1),
            "KEY_VOLDOWN" to Pair(5, 0),
            "KEY_MUTE" to Pair(5, 3),
            "KEY_CHUP" to Pair(8, 1),
            "KEY_CHDOWN" to Pair(8, 0),
            "KEY_SOURCE" to Pair(7, 1),
            "KEY_MENU" to Pair(4, 8),
            "KEY_INFO" to Pair(4, 6),
            "KEY_PLAY" to Pair(2, 3),
            "KEY_PAUSE" to Pair(2, 2),
            "KEY_STOP" to Pair(2, 0),
            "KEY_FF" to Pair(2, 1),
            "KEY_REWIND" to Pair(2, 4)
        )
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var authToken: String = ""
    private var smartCastPort: Int = 7345
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.VIZIO)
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            // Try to find the SmartCast port
            val port = findSmartCastPort(device.ip)
            if (port != null) {
                smartCastPort = port
                _connectionState.value = ConnectionState.Connected(device.copy(brand = TvBrand.VIZIO, port = port))
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Vizio SmartCast API. Ensure the TV is on and on the same Wi-Fi network."
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
        val keyPair = KEY_MAP[keyCode]
        if (keyPair == null) {
            Log.w(TAG, "Unsupported Vizio key: $keyCode")
            return
        }

        scope.launch {
            val payload = JsonObject().apply {
                add("KEYLIST", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("CODESET", keyPair.first)
                        addProperty("CODE", keyPair.second)
                        addProperty("ACTION", "KEYPRESS")
                    })
                })
            }.toString()

            val success = httpPut("http://$ip:$smartCastPort/key_command/", payload)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // Vizio SmartCast doesn't support text input directly
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$smartCastPort/app/current")
                .apply { if (authToken.isNotBlank()) addHeader("AUTH", authToken) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = json.getAsJsonArray("ITEMS") ?: return@use emptyList<TvApp>()
                items.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("NAME")?.asString ?: return@mapNotNull null
                    val value = obj.get("VALUE")?.asJsonObject
                    val appId = value?.get("NAME_SPACE")?.asString ?: name
                    TvApp(appId = appId, name = name)
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext false
        // Try SmartCast browser API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("URL", url)
            }.toString()
            httpPost("http://$ip:$smartCastPort/browser/open", payload)
        }.getOrDefault(false)
    }

    private suspend fun httpPost(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = port,
            hintedBrand = TvBrand.VIZIO
        )
    }

    private suspend fun findSmartCastPort(ip: String): Int? = withContext(Dispatchers.IO) {
        SMARTCAST_PORTS.forEach { port ->
            val reachable = runCatching {
                val request = Request.Builder()
                    .url("http://$ip:$port/state/device/deviceinfo")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachable) return@withContext port
        }
        null
    }

    private suspend fun httpPut(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "Vizio PUT failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Vizio TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val port = findSmartCastPort(device.ip)
                if (port != null) {
                    smartCastPort = port
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
import java.util.concurrent.TimeUnit

/**
 * Vizio SmartCast TV remote repository.
 * Uses the SmartCast HTTP REST API to control Vizio TVs.
 * Default port: 7345 (HTTPS) or 9000 (HTTP)
 * Auth: AUTH_TOKEN header with pairing token
 */
class VizioRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VizioRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SMARTCAST_PORTS = listOf(7345, 9000)

        // Vizio SmartCast key codes (codeset, code)
        private val KEY_MAP = mapOf(
            "KEY_POWER" to Pair(11, 0),
            "KEY_POWEROFF" to Pair(11, 0),
            "KEY_HOME" to Pair(4, 3),
            "KEY_UP" to Pair(3, 8),
            "KEY_DOWN" to Pair(3, 0),
            "KEY_LEFT" to Pair(3, 1),
            "KEY_RIGHT" to Pair(3, 7),
            "KEY_ENTER" to Pair(3, 2),
            "KEY_RETURN" to Pair(4, 0),
            "KEY_BACK" to Pair(4, 0),
            "KEY_VOLUP" to Pair(5, 1),
            "KEY_VOLDOWN" to Pair(5, 0),
            "KEY_MUTE" to Pair(5, 3),
            "KEY_CHUP" to Pair(8, 1),
            "KEY_CHDOWN" to Pair(8, 0),
            "KEY_SOURCE" to Pair(7, 1),
            "KEY_MENU" to Pair(4, 8),
            "KEY_INFO" to Pair(4, 6),
            "KEY_PLAY" to Pair(2, 3),
            "KEY_PAUSE" to Pair(2, 2),
            "KEY_STOP" to Pair(2, 0),
            "KEY_FF" to Pair(2, 1),
            "KEY_REWIND" to Pair(2, 4)
        )
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var authToken: String = ""
    private var smartCastPort: Int = 7345
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.VIZIO)
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            // Try to find the SmartCast port
            val port = findSmartCastPort(device.ip)
            if (port != null) {
                smartCastPort = port
                _connectionState.value = ConnectionState.Connected(device.copy(brand = TvBrand.VIZIO, port = port))
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Vizio SmartCast API. Ensure the TV is on and on the same Wi-Fi network."
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
        val keyPair = KEY_MAP[keyCode]
        if (keyPair == null) {
            Log.w(TAG, "Unsupported Vizio key: $keyCode")
            return
        }

        scope.launch {
            val payload = JsonObject().apply {
                add("KEYLIST", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("CODESET", keyPair.first)
                        addProperty("CODE", keyPair.second)
                        addProperty("ACTION", "KEYPRESS")
                    })
                })
            }.toString()

            val success = httpPut("http://$ip:$smartCastPort/key_command/", payload)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // Vizio SmartCast doesn't support text input directly
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$smartCastPort/app/current")
                .apply { if (authToken.isNotBlank()) addHeader("AUTH", authToken) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = json.getAsJsonArray("ITEMS") ?: return@use emptyList<TvApp>()
                items.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("NAME")?.asString ?: return@mapNotNull null
                    val value = obj.get("VALUE")?.asJsonObject
                    val appId = value?.get("NAME_SPACE")?.asString ?: name
                    TvApp(appId = appId, name = name)
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext false
        // Try SmartCast browser API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("URL", url)
            }.toString()
            httpPost("http://$ip:$smartCastPort/browser/open", payload)
        }.getOrDefault(false)
    }

    private suspend fun httpPost(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = port,
            hintedBrand = TvBrand.VIZIO
        )
    }

    private suspend fun findSmartCastPort(ip: String): Int? = withContext(Dispatchers.IO) {
        SMARTCAST_PORTS.forEach { port ->
            val reachable = runCatching {
                val request = Request.Builder()
                    .url("http://$ip:$port/state/device/deviceinfo")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachable) return@withContext port
        }
        null
    }

    private suspend fun httpPut(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "Vizio PUT failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Vizio TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val port = findSmartCastPort(device.ip)
                if (port != null) {
                    smartCastPort = port
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
import java.util.concurrent.TimeUnit

/**
 * Vizio SmartCast TV remote repository.
 * Uses the SmartCast HTTP REST API to control Vizio TVs.
 * Default port: 7345 (HTTPS) or 9000 (HTTP)
 * Auth: AUTH_TOKEN header with pairing token
 */
class VizioRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "VizioRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SMARTCAST_PORTS = listOf(7345, 9000)

        // Vizio SmartCast key codes (codeset, code)
        private val KEY_MAP = mapOf(
            "KEY_POWER" to Pair(11, 0),
            "KEY_POWEROFF" to Pair(11, 0),
            "KEY_HOME" to Pair(4, 3),
            "KEY_UP" to Pair(3, 8),
            "KEY_DOWN" to Pair(3, 0),
            "KEY_LEFT" to Pair(3, 1),
            "KEY_RIGHT" to Pair(3, 7),
            "KEY_ENTER" to Pair(3, 2),
            "KEY_RETURN" to Pair(4, 0),
            "KEY_BACK" to Pair(4, 0),
            "KEY_VOLUP" to Pair(5, 1),
            "KEY_VOLDOWN" to Pair(5, 0),
            "KEY_MUTE" to Pair(5, 3),
            "KEY_CHUP" to Pair(8, 1),
            "KEY_CHDOWN" to Pair(8, 0),
            "KEY_SOURCE" to Pair(7, 1),
            "KEY_MENU" to Pair(4, 8),
            "KEY_INFO" to Pair(4, 6),
            "KEY_PLAY" to Pair(2, 3),
            "KEY_PAUSE" to Pair(2, 2),
            "KEY_STOP" to Pair(2, 0),
            "KEY_FF" to Pair(2, 1),
            "KEY_REWIND" to Pair(2, 4)
        )
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceProbe = TvDeviceProbe()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var authToken: String = ""
    private var smartCastPort: Int = 7345
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.VIZIO)
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            // Try to find the SmartCast port
            val port = findSmartCastPort(device.ip)
            if (port != null) {
                smartCastPort = port
                _connectionState.value = ConnectionState.Connected(device.copy(brand = TvBrand.VIZIO, port = port))
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Vizio SmartCast API. Ensure the TV is on and on the same Wi-Fi network."
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
        val keyPair = KEY_MAP[keyCode]
        if (keyPair == null) {
            Log.w(TAG, "Unsupported Vizio key: $keyCode")
            return
        }

        scope.launch {
            val payload = JsonObject().apply {
                add("KEYLIST", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("CODESET", keyPair.first)
                        addProperty("CODE", keyPair.second)
                        addProperty("ACTION", "KEYPRESS")
                    })
                })
            }.toString()

            val success = httpPut("http://$ip:$smartCastPort/key_command/", payload)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        // Vizio SmartCast doesn't support text input directly
        return false
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:$smartCastPort/app/current")
                .apply { if (authToken.isNotBlank()) addHeader("AUTH", authToken) }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = json.getAsJsonArray("ITEMS") ?: return@use emptyList<TvApp>()
                items.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val name = obj.get("NAME")?.asString ?: return@mapNotNull null
                    val value = obj.get("VALUE")?.asJsonObject
                    val appId = value?.get("NAME_SPACE")?.asString ?: name
                    TvApp(appId = appId, name = name)
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext false
        // Try SmartCast browser API
        runCatching {
            val payload = JsonObject().apply {
                addProperty("URL", url)
            }.toString()
            httpPost("http://$ip:$smartCastPort/browser/open", payload)
        }.getOrDefault(false)
    }

    private suspend fun httpPost(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = port,
            hintedBrand = TvBrand.VIZIO
        )
    }

    private suspend fun findSmartCastPort(ip: String): Int? = withContext(Dispatchers.IO) {
        SMARTCAST_PORTS.forEach { port ->
            val reachable = runCatching {
                val request = Request.Builder()
                    .url("http://$ip:$port/state/device/deviceinfo")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachable) return@withContext port
        }
        null
    }

    private suspend fun httpPut(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .put(body.toRequestBody(JSON_MEDIA_TYPE))
            if (authToken.isNotBlank()) builder.addHeader("AUTH", authToken)
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "Vizio PUT failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Vizio TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val port = findSmartCastPort(device.ip)
                if (port != null) {
                    smartCastPort = port
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected(device)
                } else {
                    scheduleReconnect(device)
                }
            }
        }
    }
}
