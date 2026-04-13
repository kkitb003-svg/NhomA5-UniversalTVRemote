package com.ued.universaltvremote.network

import android.content.Context
import android.util.Base64
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
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungRemoteRepository(context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "SamsungRemote"
        private const val PREFS_NAME = "samsung_remote_prefs"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(
            TlsVersion.TLS_1_3,
            TlsVersion.TLS_1_2,
            TlsVersion.TLS_1_1,
            TlsVersion.TLS_1_0
        )
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val appName = Base64.encodeToString("UniversalTvRemote".toByteArray(), Base64.NO_WRAP)

    private var webSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var activeConnectionId = 0
    private var connectionWasEstablished = false
    @Volatile
    private var pendingInstalledAppsCallback: ((List<TvApp>) -> Unit)? = null

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.SAMSUNG)
        startSocketConnection(currentDevice!!, emitConnecting = true)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        activeConnectionId += 1
        closeCurrentSocket()
        currentDevice = null
        connectionWasEstablished = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val socket = webSocket ?: return
        val accepted = socket.send(buildKeyPayload(keyCode))
        if (!accepted) {
            currentDevice?.let { device ->
                if (!manualDisconnect) {
                    scheduleReconnect(device, "Remote session dropped.")
                }
            }
        }
    }

    override fun sendText(text: String): Boolean {
        val socket = webSocket ?: return false
        val encodedText = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = """{"method":"ms.remote.control","params":{"Cmd":"$encodedText","DataOfCmd":"$encodedText","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        requestInstalledAppsOverWebSocket()
            .ifEmpty {
                val device = currentDevice ?: return@withContext emptyList()
                val base = apiBase(device)
                tryFetchApps("$base/api/v2/applications")
                    .ifEmpty { tryFetchApps("$base/api/v2/applications/") }
            }
            .also { apps ->
                if (apps.isEmpty()) {
                    Log.w(TAG, "Samsung fetchInstalledApps: no apps found via WebSocket or REST API")
                }
            }
    }

    private fun tryFetchApps(url: String): List<TvApp> {
        return runCatching {
            client.newCall(
                Request.Builder().url(url).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Samsung apps endpoint failed ${response.code} at $url")
                    return@use emptyList()
                }
                val body = response.body?.string() ?: return@use emptyList()
                Log.d(TAG, "Samsung apps response: ${body.take(500)}")
                
                val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                
                // Try "data" array (older Samsung API)
                val dataArr = json?.getAsJsonArray("data")
                if (dataArr != null && dataArr.size() > 0) {
                    return@use dataArr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }
                
                // Try "apps" array (newer Samsung API) 
                val appsArr = json?.getAsJsonArray("apps")
                if (appsArr != null && appsArr.size() > 0) {
                    return@use appsArr.mapNotNull { element ->
                        val obj = element.asJsonObject
                        TvApp(
                            appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null,
                            name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: return@mapNotNull null,
                            visible = obj.get("visible")?.asBoolean ?: true
                        )
                    }.filter { it.appId.isNotBlank() }
                }
                
                // Try parsing as direct JSON array
                val arr = runCatching { gson.fromJson(body, com.google.gson.JsonArray::class.java) }.getOrNull()
                if (arr != null && arr.size() > 0) {
                    return@use arr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }

                // Some Samsung models return an object keyed by app id.
                if (json != null && json.entrySet().isNotEmpty()) {
                    val objectApps = json.entrySet().mapNotNull { (key, value) ->
                        val obj = runCatching { value.asJsonObject }.getOrNull() ?: return@mapNotNull null
                        val appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: key
                        val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: key
                        appId.takeIf { it.isNotBlank() }?.let {
                            TvApp(
                                appId = it,
                                name = name,
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        }
                    }
                    if (objectApps.isNotEmpty()) {
                        return@use objectApps.sortedBy { it.name.lowercase() }
                    }
                }
                
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun launchApp(appId: String) {
        val device = currentDevice ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${apiBase(device)}/api/v2/applications/$appId")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                ).execute().close()
            }
        }
    }

    fun openUrl(url: String): Boolean {
        val socket = webSocket ?: return false
        val payload =
            """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$url","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ws = webSocket ?: return@withContext false
        val payload = JsonObject().apply {
            addProperty("method", "ms.channel.emit")
            add(
                "params",
                JsonObject().apply {
                    addProperty("event", "ed.apps.launch")
                    addProperty("to", "host")
                    add(
                        "data",
                        JsonObject().apply {
                            addProperty("action_type", "NATIVE_LAUNCH")
                            addProperty("appId", "org.tizen.browser")
                            addProperty("metaTag", url)
                        }
                    )
                }
            )
        }.toString()

        if (ws.send(payload)) {
            return@withContext true
        }

        val device = currentDevice ?: return@withContext false
        runCatching {
            val body = """{"appId":"org.tizen.browser","action_type":"NATIVE_LAUNCH","metaTag":"$url"}"""
            client.newCall(
                Request.Builder()
                    .url("${apiBase(device)}/api/v2/applications/org.tizen.browser")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url("http://$ip:8001/api/v2/").build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val deviceInfo = json.getAsJsonObject("device") ?: return@use null
                val name = deviceInfo.get("name")?.asString ?: "Samsung TV ($ip)"
                val mac = deviceInfo.get("wifiMac")?.asString.orEmpty()
                val modelYear = deviceInfo.get("modelYear")?.asString.orEmpty()
                val wsPort = if ((modelYear.toIntOrNull() ?: 0) >= 2016) 8002 else 8001

                TvDevice(
                    name = name,
                    ip = ip,
                    port = wsPort,
                    macAddress = mac,
                    modelYear = modelYear,
                    brand = TvBrand.SAMSUNG
                )
            }
        }.getOrNull()
    }

    private fun startSocketConnection(device: TvDevice, emitConnecting: Boolean) {
        val connectionId = ++activeConnectionId
        closeCurrentSocket()
        connectionWasEstablished = false
        if (emitConnecting) {
            _connectionState.value = ConnectionState.Connecting
        }

        val useSecure = device.port == 8002
        val tokenQuery = prefs.getString(tokenPrefKey(device.ip), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { "&token=$it" }
            .orEmpty()
        val protocol = if (useSecure) "wss" else "ws"
        val wsUrl =
            "$protocol://${device.ip}:${device.port}/api/v2/channels/samsung.remote.control?name=$appName$tokenQuery"

        Log.d(TAG, "Connecting to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionId != activeConnectionId) {
                    webSocket.cancel()
                    return
                }
                reconnectJob?.cancel()
                reconnectAttempts = 0
                connectionWasEstablished = true
                _connectionState.value = ConnectionState.Connected(device)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionId != activeConnectionId) return
                parseAndPersistToken(device.ip, text)
                parseInstalledAppsResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionId != activeConnectionId) return

                val message = t.message ?: response?.message ?: "Connection failed"
                Log.e(TAG, "Samsung socket failure: $message")

                if (!useSecure && shouldRetryOnSecurePort(message)) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableSocketProblem(message)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                    return
                }

                _connectionState.value = ConnectionState.Error(userFacingMessage(message))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return
                runCatching { webSocket.close(1000, "Client closing") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return

                if (manualDisconnect) {
                    _connectionState.value = ConnectionState.Disconnected
                    return
                }

                if (!useSecure && code == 1005) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableCloseCode(code)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun scheduleReconnect(device: TvDevice, fallbackMessage: String) {
        if (manualDisconnect) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error(fallbackMessage)
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts += 1
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectDelayMs(reconnectAttempts))
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                startSocketConnection(device, emitConnecting = true)
            }
        }
    }

    private fun closeCurrentSocket() {
        webSocket?.cancel()
        webSocket = null
    }

    private fun parseAndPersistToken(ip: String, text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            val event = json.get("event")?.asString
            if (event == "ms.channel.connect") {
                val token = json.getAsJsonObject("data")
                    ?.get("token")
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                if (token != null) {
                    prefs.edit().putString(tokenPrefKey(ip), token).apply()
                }
            }
        }
    }

    private suspend fun requestInstalledAppsOverWebSocket(): List<TvApp> = withContext(Dispatchers.IO) {
        val socket = webSocket ?: return@withContext emptyList()
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingInstalledAppsCallback = { apps ->
                if (continuation.isActive) {
                    continuation.resume(apps, onCancellation = null)
                }
            }

            val payload = JsonObject().apply {
                addProperty("method", "ms.channel.emit")
                add(
                    "params",
                    JsonObject().apply {
                        addProperty("event", "ed.installedApp.get")
                        addProperty("to", "host")
                    }
                )
            }.toString()

            val sent = socket.send(payload)
            if (!sent) {
                pendingInstalledAppsCallback = null
                continuation.resume(emptyList(), onCancellation = null)
                return@suspendCancellableCoroutine
            }

            scope.launch {
                delay(4000L)
                if (continuation.isActive) {
                    pendingInstalledAppsCallback = null
                    continuation.resume(emptyList(), onCancellation = null)
                }
            }
        }
    }

    private fun parseInstalledAppsResponse(text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            if (json.get("event")?.asString != "ed.installedApp.get") {
                return
            }

            val appsArray = json.getAsJsonObject("data")
                ?.getAsJsonArray("data")
                ?: JsonArray()

            val apps = appsArray.mapNotNull { element ->
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val appId = obj.get("appId")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: appId
                TvApp(
                    appId = appId,
                    name = name,
                    version = obj.get("version")?.asString.orEmpty(),
                    visible = obj.get("visible")?.asBoolean ?: true
                )
            }.sortedBy { it.name.lowercase() }

            pendingInstalledAppsCallback?.also { callback ->
                pendingInstalledAppsCallback = null
                callback(apps)
            }
        }.onFailure {
            Log.d(TAG, "Failed to parse Samsung installed apps event: ${it.message}")
        }
    }

    private fun buildKeyPayload(keyCode: String): String {
        return """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$keyCode","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
    }

    private fun apiBase(device: TvDevice): String = "http://${device.ip}:8001"

    private fun tokenPrefKey(ip: String): String = "token_$ip"

    private fun shouldRetryOnSecurePort(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "protocol_error" in normalized ||
            "connection reset" in normalized
    }

    private fun isRecoverableSocketProblem(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "reserved and may not be used" in normalized ||
            "connection reset" in normalized ||
            "broken pipe" in normalized ||
            "socket closed" in normalized ||
            "eof" in normalized ||
            "timeout" in normalized ||
            "canceled" in normalized
    }

    private fun isRecoverableCloseCode(code: Int): Boolean {
        return code == 1001 || code == 1005 || code == 1006 || code == 1011
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 1200L
            2 -> 2500L
            3 -> 4000L
            4 -> 6000L
            else -> 8000L
        }
    }

    private fun userFacingMessage(message: String): String {
        val normalized = message.lowercase()
        return when {
            "1005" in normalized -> "TV closed the remote socket unexpectedly."
            "handshake" in normalized || "ssl" in normalized ->
                "Secure handshake failed. Accept the permission prompt on the TV."
            "timeout" in normalized ->
                "Connection timed out. Make sure the phone and TV are on the same Wi-Fi."
            else -> message
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Base64
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
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungRemoteRepository(context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "SamsungRemote"
        private const val PREFS_NAME = "samsung_remote_prefs"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(
            TlsVersion.TLS_1_3,
            TlsVersion.TLS_1_2,
            TlsVersion.TLS_1_1,
            TlsVersion.TLS_1_0
        )
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val appName = Base64.encodeToString("UniversalTvRemote".toByteArray(), Base64.NO_WRAP)

    private var webSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var activeConnectionId = 0
    private var connectionWasEstablished = false
    @Volatile
    private var pendingInstalledAppsCallback: ((List<TvApp>) -> Unit)? = null

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.SAMSUNG)
        startSocketConnection(currentDevice!!, emitConnecting = true)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        activeConnectionId += 1
        closeCurrentSocket()
        currentDevice = null
        connectionWasEstablished = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val socket = webSocket ?: return
        val accepted = socket.send(buildKeyPayload(keyCode))
        if (!accepted) {
            currentDevice?.let { device ->
                if (!manualDisconnect) {
                    scheduleReconnect(device, "Remote session dropped.")
                }
            }
        }
    }

    override fun sendText(text: String): Boolean {
        val socket = webSocket ?: return false
        val encodedText = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = """{"method":"ms.remote.control","params":{"Cmd":"$encodedText","DataOfCmd":"$encodedText","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        requestInstalledAppsOverWebSocket()
            .ifEmpty {
                val device = currentDevice ?: return@withContext emptyList()
                val base = apiBase(device)
                tryFetchApps("$base/api/v2/applications")
                    .ifEmpty { tryFetchApps("$base/api/v2/applications/") }
            }
            .also { apps ->
                if (apps.isEmpty()) {
                    Log.w(TAG, "Samsung fetchInstalledApps: no apps found via WebSocket or REST API")
                }
            }
    }

    private fun tryFetchApps(url: String): List<TvApp> {
        return runCatching {
            client.newCall(
                Request.Builder().url(url).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Samsung apps endpoint failed ${response.code} at $url")
                    return@use emptyList()
                }
                val body = response.body?.string() ?: return@use emptyList()
                Log.d(TAG, "Samsung apps response: ${body.take(500)}")
                
                val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                
                // Try "data" array (older Samsung API)
                val dataArr = json?.getAsJsonArray("data")
                if (dataArr != null && dataArr.size() > 0) {
                    return@use dataArr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }
                
                // Try "apps" array (newer Samsung API) 
                val appsArr = json?.getAsJsonArray("apps")
                if (appsArr != null && appsArr.size() > 0) {
                    return@use appsArr.mapNotNull { element ->
                        val obj = element.asJsonObject
                        TvApp(
                            appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null,
                            name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: return@mapNotNull null,
                            visible = obj.get("visible")?.asBoolean ?: true
                        )
                    }.filter { it.appId.isNotBlank() }
                }
                
                // Try parsing as direct JSON array
                val arr = runCatching { gson.fromJson(body, com.google.gson.JsonArray::class.java) }.getOrNull()
                if (arr != null && arr.size() > 0) {
                    return@use arr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }

                // Some Samsung models return an object keyed by app id.
                if (json != null && json.entrySet().isNotEmpty()) {
                    val objectApps = json.entrySet().mapNotNull { (key, value) ->
                        val obj = runCatching { value.asJsonObject }.getOrNull() ?: return@mapNotNull null
                        val appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: key
                        val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: key
                        appId.takeIf { it.isNotBlank() }?.let {
                            TvApp(
                                appId = it,
                                name = name,
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        }
                    }
                    if (objectApps.isNotEmpty()) {
                        return@use objectApps.sortedBy { it.name.lowercase() }
                    }
                }
                
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun launchApp(appId: String) {
        val device = currentDevice ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${apiBase(device)}/api/v2/applications/$appId")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                ).execute().close()
            }
        }
    }

    fun openUrl(url: String): Boolean {
        val socket = webSocket ?: return false
        val payload =
            """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$url","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ws = webSocket ?: return@withContext false
        val payload = JsonObject().apply {
            addProperty("method", "ms.channel.emit")
            add(
                "params",
                JsonObject().apply {
                    addProperty("event", "ed.apps.launch")
                    addProperty("to", "host")
                    add(
                        "data",
                        JsonObject().apply {
                            addProperty("action_type", "NATIVE_LAUNCH")
                            addProperty("appId", "org.tizen.browser")
                            addProperty("metaTag", url)
                        }
                    )
                }
            )
        }.toString()

        if (ws.send(payload)) {
            return@withContext true
        }

        val device = currentDevice ?: return@withContext false
        runCatching {
            val body = """{"appId":"org.tizen.browser","action_type":"NATIVE_LAUNCH","metaTag":"$url"}"""
            client.newCall(
                Request.Builder()
                    .url("${apiBase(device)}/api/v2/applications/org.tizen.browser")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url("http://$ip:8001/api/v2/").build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val deviceInfo = json.getAsJsonObject("device") ?: return@use null
                val name = deviceInfo.get("name")?.asString ?: "Samsung TV ($ip)"
                val mac = deviceInfo.get("wifiMac")?.asString.orEmpty()
                val modelYear = deviceInfo.get("modelYear")?.asString.orEmpty()
                val wsPort = if ((modelYear.toIntOrNull() ?: 0) >= 2016) 8002 else 8001

                TvDevice(
                    name = name,
                    ip = ip,
                    port = wsPort,
                    macAddress = mac,
                    modelYear = modelYear,
                    brand = TvBrand.SAMSUNG
                )
            }
        }.getOrNull()
    }

    private fun startSocketConnection(device: TvDevice, emitConnecting: Boolean) {
        val connectionId = ++activeConnectionId
        closeCurrentSocket()
        connectionWasEstablished = false
        if (emitConnecting) {
            _connectionState.value = ConnectionState.Connecting
        }

        val useSecure = device.port == 8002
        val tokenQuery = prefs.getString(tokenPrefKey(device.ip), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { "&token=$it" }
            .orEmpty()
        val protocol = if (useSecure) "wss" else "ws"
        val wsUrl =
            "$protocol://${device.ip}:${device.port}/api/v2/channels/samsung.remote.control?name=$appName$tokenQuery"

        Log.d(TAG, "Connecting to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionId != activeConnectionId) {
                    webSocket.cancel()
                    return
                }
                reconnectJob?.cancel()
                reconnectAttempts = 0
                connectionWasEstablished = true
                _connectionState.value = ConnectionState.Connected(device)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionId != activeConnectionId) return
                parseAndPersistToken(device.ip, text)
                parseInstalledAppsResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionId != activeConnectionId) return

                val message = t.message ?: response?.message ?: "Connection failed"
                Log.e(TAG, "Samsung socket failure: $message")

                if (!useSecure && shouldRetryOnSecurePort(message)) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableSocketProblem(message)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                    return
                }

                _connectionState.value = ConnectionState.Error(userFacingMessage(message))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return
                runCatching { webSocket.close(1000, "Client closing") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return

                if (manualDisconnect) {
                    _connectionState.value = ConnectionState.Disconnected
                    return
                }

                if (!useSecure && code == 1005) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableCloseCode(code)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun scheduleReconnect(device: TvDevice, fallbackMessage: String) {
        if (manualDisconnect) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error(fallbackMessage)
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts += 1
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectDelayMs(reconnectAttempts))
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                startSocketConnection(device, emitConnecting = true)
            }
        }
    }

    private fun closeCurrentSocket() {
        webSocket?.cancel()
        webSocket = null
    }

    private fun parseAndPersistToken(ip: String, text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            val event = json.get("event")?.asString
            if (event == "ms.channel.connect") {
                val token = json.getAsJsonObject("data")
                    ?.get("token")
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                if (token != null) {
                    prefs.edit().putString(tokenPrefKey(ip), token).apply()
                }
            }
        }
    }

    private suspend fun requestInstalledAppsOverWebSocket(): List<TvApp> = withContext(Dispatchers.IO) {
        val socket = webSocket ?: return@withContext emptyList()
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingInstalledAppsCallback = { apps ->
                if (continuation.isActive) {
                    continuation.resume(apps, onCancellation = null)
                }
            }

            val payload = JsonObject().apply {
                addProperty("method", "ms.channel.emit")
                add(
                    "params",
                    JsonObject().apply {
                        addProperty("event", "ed.installedApp.get")
                        addProperty("to", "host")
                    }
                )
            }.toString()

            val sent = socket.send(payload)
            if (!sent) {
                pendingInstalledAppsCallback = null
                continuation.resume(emptyList(), onCancellation = null)
                return@suspendCancellableCoroutine
            }

            scope.launch {
                delay(4000L)
                if (continuation.isActive) {
                    pendingInstalledAppsCallback = null
                    continuation.resume(emptyList(), onCancellation = null)
                }
            }
        }
    }

    private fun parseInstalledAppsResponse(text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            if (json.get("event")?.asString != "ed.installedApp.get") {
                return
            }

            val appsArray = json.getAsJsonObject("data")
                ?.getAsJsonArray("data")
                ?: JsonArray()

            val apps = appsArray.mapNotNull { element ->
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val appId = obj.get("appId")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: appId
                TvApp(
                    appId = appId,
                    name = name,
                    version = obj.get("version")?.asString.orEmpty(),
                    visible = obj.get("visible")?.asBoolean ?: true
                )
            }.sortedBy { it.name.lowercase() }

            pendingInstalledAppsCallback?.also { callback ->
                pendingInstalledAppsCallback = null
                callback(apps)
            }
        }.onFailure {
            Log.d(TAG, "Failed to parse Samsung installed apps event: ${it.message}")
        }
    }

    private fun buildKeyPayload(keyCode: String): String {
        return """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$keyCode","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
    }

    private fun apiBase(device: TvDevice): String = "http://${device.ip}:8001"

    private fun tokenPrefKey(ip: String): String = "token_$ip"

    private fun shouldRetryOnSecurePort(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "protocol_error" in normalized ||
            "connection reset" in normalized
    }

    private fun isRecoverableSocketProblem(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "reserved and may not be used" in normalized ||
            "connection reset" in normalized ||
            "broken pipe" in normalized ||
            "socket closed" in normalized ||
            "eof" in normalized ||
            "timeout" in normalized ||
            "canceled" in normalized
    }

    private fun isRecoverableCloseCode(code: Int): Boolean {
        return code == 1001 || code == 1005 || code == 1006 || code == 1011
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 1200L
            2 -> 2500L
            3 -> 4000L
            4 -> 6000L
            else -> 8000L
        }
    }

    private fun userFacingMessage(message: String): String {
        val normalized = message.lowercase()
        return when {
            "1005" in normalized -> "TV closed the remote socket unexpectedly."
            "handshake" in normalized || "ssl" in normalized ->
                "Secure handshake failed. Accept the permission prompt on the TV."
            "timeout" in normalized ->
                "Connection timed out. Make sure the phone and TV are on the same Wi-Fi."
            else -> message
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Base64
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
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungRemoteRepository(context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "SamsungRemote"
        private const val PREFS_NAME = "samsung_remote_prefs"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(
            TlsVersion.TLS_1_3,
            TlsVersion.TLS_1_2,
            TlsVersion.TLS_1_1,
            TlsVersion.TLS_1_0
        )
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val appName = Base64.encodeToString("UniversalTvRemote".toByteArray(), Base64.NO_WRAP)

    private var webSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var activeConnectionId = 0
    private var connectionWasEstablished = false
    @Volatile
    private var pendingInstalledAppsCallback: ((List<TvApp>) -> Unit)? = null

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.SAMSUNG)
        startSocketConnection(currentDevice!!, emitConnecting = true)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        activeConnectionId += 1
        closeCurrentSocket()
        currentDevice = null
        connectionWasEstablished = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val socket = webSocket ?: return
        val accepted = socket.send(buildKeyPayload(keyCode))
        if (!accepted) {
            currentDevice?.let { device ->
                if (!manualDisconnect) {
                    scheduleReconnect(device, "Remote session dropped.")
                }
            }
        }
    }

    override fun sendText(text: String): Boolean {
        val socket = webSocket ?: return false
        val encodedText = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = """{"method":"ms.remote.control","params":{"Cmd":"$encodedText","DataOfCmd":"$encodedText","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        requestInstalledAppsOverWebSocket()
            .ifEmpty {
                val device = currentDevice ?: return@withContext emptyList()
                val base = apiBase(device)
                tryFetchApps("$base/api/v2/applications")
                    .ifEmpty { tryFetchApps("$base/api/v2/applications/") }
            }
            .also { apps ->
                if (apps.isEmpty()) {
                    Log.w(TAG, "Samsung fetchInstalledApps: no apps found via WebSocket or REST API")
                }
            }
    }

    private fun tryFetchApps(url: String): List<TvApp> {
        return runCatching {
            client.newCall(
                Request.Builder().url(url).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Samsung apps endpoint failed ${response.code} at $url")
                    return@use emptyList()
                }
                val body = response.body?.string() ?: return@use emptyList()
                Log.d(TAG, "Samsung apps response: ${body.take(500)}")
                
                val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                
                // Try "data" array (older Samsung API)
                val dataArr = json?.getAsJsonArray("data")
                if (dataArr != null && dataArr.size() > 0) {
                    return@use dataArr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }
                
                // Try "apps" array (newer Samsung API) 
                val appsArr = json?.getAsJsonArray("apps")
                if (appsArr != null && appsArr.size() > 0) {
                    return@use appsArr.mapNotNull { element ->
                        val obj = element.asJsonObject
                        TvApp(
                            appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null,
                            name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: return@mapNotNull null,
                            visible = obj.get("visible")?.asBoolean ?: true
                        )
                    }.filter { it.appId.isNotBlank() }
                }
                
                // Try parsing as direct JSON array
                val arr = runCatching { gson.fromJson(body, com.google.gson.JsonArray::class.java) }.getOrNull()
                if (arr != null && arr.size() > 0) {
                    return@use arr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }

                // Some Samsung models return an object keyed by app id.
                if (json != null && json.entrySet().isNotEmpty()) {
                    val objectApps = json.entrySet().mapNotNull { (key, value) ->
                        val obj = runCatching { value.asJsonObject }.getOrNull() ?: return@mapNotNull null
                        val appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: key
                        val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: key
                        appId.takeIf { it.isNotBlank() }?.let {
                            TvApp(
                                appId = it,
                                name = name,
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        }
                    }
                    if (objectApps.isNotEmpty()) {
                        return@use objectApps.sortedBy { it.name.lowercase() }
                    }
                }
                
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun launchApp(appId: String) {
        val device = currentDevice ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${apiBase(device)}/api/v2/applications/$appId")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                ).execute().close()
            }
        }
    }

    fun openUrl(url: String): Boolean {
        val socket = webSocket ?: return false
        val payload =
            """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$url","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ws = webSocket ?: return@withContext false
        val payload = JsonObject().apply {
            addProperty("method", "ms.channel.emit")
            add(
                "params",
                JsonObject().apply {
                    addProperty("event", "ed.apps.launch")
                    addProperty("to", "host")
                    add(
                        "data",
                        JsonObject().apply {
                            addProperty("action_type", "NATIVE_LAUNCH")
                            addProperty("appId", "org.tizen.browser")
                            addProperty("metaTag", url)
                        }
                    )
                }
            )
        }.toString()

        if (ws.send(payload)) {
            return@withContext true
        }

        val device = currentDevice ?: return@withContext false
        runCatching {
            val body = """{"appId":"org.tizen.browser","action_type":"NATIVE_LAUNCH","metaTag":"$url"}"""
            client.newCall(
                Request.Builder()
                    .url("${apiBase(device)}/api/v2/applications/org.tizen.browser")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url("http://$ip:8001/api/v2/").build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val deviceInfo = json.getAsJsonObject("device") ?: return@use null
                val name = deviceInfo.get("name")?.asString ?: "Samsung TV ($ip)"
                val mac = deviceInfo.get("wifiMac")?.asString.orEmpty()
                val modelYear = deviceInfo.get("modelYear")?.asString.orEmpty()
                val wsPort = if ((modelYear.toIntOrNull() ?: 0) >= 2016) 8002 else 8001

                TvDevice(
                    name = name,
                    ip = ip,
                    port = wsPort,
                    macAddress = mac,
                    modelYear = modelYear,
                    brand = TvBrand.SAMSUNG
                )
            }
        }.getOrNull()
    }

    private fun startSocketConnection(device: TvDevice, emitConnecting: Boolean) {
        val connectionId = ++activeConnectionId
        closeCurrentSocket()
        connectionWasEstablished = false
        if (emitConnecting) {
            _connectionState.value = ConnectionState.Connecting
        }

        val useSecure = device.port == 8002
        val tokenQuery = prefs.getString(tokenPrefKey(device.ip), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { "&token=$it" }
            .orEmpty()
        val protocol = if (useSecure) "wss" else "ws"
        val wsUrl =
            "$protocol://${device.ip}:${device.port}/api/v2/channels/samsung.remote.control?name=$appName$tokenQuery"

        Log.d(TAG, "Connecting to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionId != activeConnectionId) {
                    webSocket.cancel()
                    return
                }
                reconnectJob?.cancel()
                reconnectAttempts = 0
                connectionWasEstablished = true
                _connectionState.value = ConnectionState.Connected(device)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionId != activeConnectionId) return
                parseAndPersistToken(device.ip, text)
                parseInstalledAppsResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionId != activeConnectionId) return

                val message = t.message ?: response?.message ?: "Connection failed"
                Log.e(TAG, "Samsung socket failure: $message")

                if (!useSecure && shouldRetryOnSecurePort(message)) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableSocketProblem(message)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                    return
                }

                _connectionState.value = ConnectionState.Error(userFacingMessage(message))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return
                runCatching { webSocket.close(1000, "Client closing") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return

                if (manualDisconnect) {
                    _connectionState.value = ConnectionState.Disconnected
                    return
                }

                if (!useSecure && code == 1005) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableCloseCode(code)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun scheduleReconnect(device: TvDevice, fallbackMessage: String) {
        if (manualDisconnect) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error(fallbackMessage)
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts += 1
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectDelayMs(reconnectAttempts))
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                startSocketConnection(device, emitConnecting = true)
            }
        }
    }

    private fun closeCurrentSocket() {
        webSocket?.cancel()
        webSocket = null
    }

    private fun parseAndPersistToken(ip: String, text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            val event = json.get("event")?.asString
            if (event == "ms.channel.connect") {
                val token = json.getAsJsonObject("data")
                    ?.get("token")
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                if (token != null) {
                    prefs.edit().putString(tokenPrefKey(ip), token).apply()
                }
            }
        }
    }

    private suspend fun requestInstalledAppsOverWebSocket(): List<TvApp> = withContext(Dispatchers.IO) {
        val socket = webSocket ?: return@withContext emptyList()
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingInstalledAppsCallback = { apps ->
                if (continuation.isActive) {
                    continuation.resume(apps, onCancellation = null)
                }
            }

            val payload = JsonObject().apply {
                addProperty("method", "ms.channel.emit")
                add(
                    "params",
                    JsonObject().apply {
                        addProperty("event", "ed.installedApp.get")
                        addProperty("to", "host")
                    }
                )
            }.toString()

            val sent = socket.send(payload)
            if (!sent) {
                pendingInstalledAppsCallback = null
                continuation.resume(emptyList(), onCancellation = null)
                return@suspendCancellableCoroutine
            }

            scope.launch {
                delay(4000L)
                if (continuation.isActive) {
                    pendingInstalledAppsCallback = null
                    continuation.resume(emptyList(), onCancellation = null)
                }
            }
        }
    }

    private fun parseInstalledAppsResponse(text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            if (json.get("event")?.asString != "ed.installedApp.get") {
                return
            }

            val appsArray = json.getAsJsonObject("data")
                ?.getAsJsonArray("data")
                ?: JsonArray()

            val apps = appsArray.mapNotNull { element ->
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val appId = obj.get("appId")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: appId
                TvApp(
                    appId = appId,
                    name = name,
                    version = obj.get("version")?.asString.orEmpty(),
                    visible = obj.get("visible")?.asBoolean ?: true
                )
            }.sortedBy { it.name.lowercase() }

            pendingInstalledAppsCallback?.also { callback ->
                pendingInstalledAppsCallback = null
                callback(apps)
            }
        }.onFailure {
            Log.d(TAG, "Failed to parse Samsung installed apps event: ${it.message}")
        }
    }

    private fun buildKeyPayload(keyCode: String): String {
        return """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$keyCode","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
    }

    private fun apiBase(device: TvDevice): String = "http://${device.ip}:8001"

    private fun tokenPrefKey(ip: String): String = "token_$ip"

    private fun shouldRetryOnSecurePort(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "protocol_error" in normalized ||
            "connection reset" in normalized
    }

    private fun isRecoverableSocketProblem(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "reserved and may not be used" in normalized ||
            "connection reset" in normalized ||
            "broken pipe" in normalized ||
            "socket closed" in normalized ||
            "eof" in normalized ||
            "timeout" in normalized ||
            "canceled" in normalized
    }

    private fun isRecoverableCloseCode(code: Int): Boolean {
        return code == 1001 || code == 1005 || code == 1006 || code == 1011
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 1200L
            2 -> 2500L
            3 -> 4000L
            4 -> 6000L
            else -> 8000L
        }
    }

    private fun userFacingMessage(message: String): String {
        val normalized = message.lowercase()
        return when {
            "1005" in normalized -> "TV closed the remote socket unexpectedly."
            "handshake" in normalized || "ssl" in normalized ->
                "Secure handshake failed. Accept the permission prompt on the TV."
            "timeout" in normalized ->
                "Connection timed out. Make sure the phone and TV are on the same Wi-Fi."
            else -> message
        }
    }
}

package com.ued.universaltvremote.network

import android.content.Context
import android.util.Base64
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
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungRemoteRepository(context: Context) : TvRemoteRepository {

    companion object {
        private const val TAG = "SamsungRemote"
        private const val PREFS_NAME = "samsung_remote_prefs"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }

    private val permissiveSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
        .tlsVersions(
            TlsVersion.TLS_1_3,
            TlsVersion.TLS_1_2,
            TlsVersion.TLS_1_1,
            TlsVersion.TLS_1_0
        )
        .allEnabledCipherSuites()
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(permissiveSpec, ConnectionSpec.CLEARTEXT))
        .build()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val appName = Base64.encodeToString("UniversalTvRemote".toByteArray(), Base64.NO_WRAP)

    private var webSocket: WebSocket? = null
    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var activeConnectionId = 0
    private var connectionWasEstablished = false
    @Volatile
    private var pendingInstalledAppsCallback: ((List<TvApp>) -> Unit)? = null

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        currentDevice = device.copy(brand = TvBrand.SAMSUNG)
        startSocketConnection(currentDevice!!, emitConnecting = true)
    }

    override fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        activeConnectionId += 1
        closeCurrentSocket()
        currentDevice = null
        connectionWasEstablished = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendKey(keyCode: String) {
        val socket = webSocket ?: return
        val accepted = socket.send(buildKeyPayload(keyCode))
        if (!accepted) {
            currentDevice?.let { device ->
                if (!manualDisconnect) {
                    scheduleReconnect(device, "Remote session dropped.")
                }
            }
        }
    }

    override fun sendText(text: String): Boolean {
        val socket = webSocket ?: return false
        val encodedText = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = """{"method":"ms.remote.control","params":{"Cmd":"$encodedText","DataOfCmd":"$encodedText","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        requestInstalledAppsOverWebSocket()
            .ifEmpty {
                val device = currentDevice ?: return@withContext emptyList()
                val base = apiBase(device)
                tryFetchApps("$base/api/v2/applications")
                    .ifEmpty { tryFetchApps("$base/api/v2/applications/") }
            }
            .also { apps ->
                if (apps.isEmpty()) {
                    Log.w(TAG, "Samsung fetchInstalledApps: no apps found via WebSocket or REST API")
                }
            }
    }

    private fun tryFetchApps(url: String): List<TvApp> {
        return runCatching {
            client.newCall(
                Request.Builder().url(url).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Samsung apps endpoint failed ${response.code} at $url")
                    return@use emptyList()
                }
                val body = response.body?.string() ?: return@use emptyList()
                Log.d(TAG, "Samsung apps response: ${body.take(500)}")
                
                val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                
                // Try "data" array (older Samsung API)
                val dataArr = json?.getAsJsonArray("data")
                if (dataArr != null && dataArr.size() > 0) {
                    return@use dataArr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }
                
                // Try "apps" array (newer Samsung API) 
                val appsArr = json?.getAsJsonArray("apps")
                if (appsArr != null && appsArr.size() > 0) {
                    return@use appsArr.mapNotNull { element ->
                        val obj = element.asJsonObject
                        TvApp(
                            appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: return@mapNotNull null,
                            name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: return@mapNotNull null,
                            visible = obj.get("visible")?.asBoolean ?: true
                        )
                    }.filter { it.appId.isNotBlank() }
                }
                
                // Try parsing as direct JSON array
                val arr = runCatching { gson.fromJson(body, com.google.gson.JsonArray::class.java) }.getOrNull()
                if (arr != null && arr.size() > 0) {
                    return@use arr.mapNotNull {
                        runCatching { gson.fromJson(it, TvApp::class.java) }.getOrNull()
                    }.filter { it.appId.isNotBlank() && it.name.isNotBlank() }
                }

                // Some Samsung models return an object keyed by app id.
                if (json != null && json.entrySet().isNotEmpty()) {
                    val objectApps = json.entrySet().mapNotNull { (key, value) ->
                        val obj = runCatching { value.asJsonObject }.getOrNull() ?: return@mapNotNull null
                        val appId = obj.get("appId")?.asString ?: obj.get("id")?.asString ?: key
                        val name = obj.get("name")?.asString ?: obj.get("label")?.asString ?: key
                        appId.takeIf { it.isNotBlank() }?.let {
                            TvApp(
                                appId = it,
                                name = name,
                                visible = obj.get("visible")?.asBoolean ?: true
                            )
                        }
                    }
                    if (objectApps.isNotEmpty()) {
                        return@use objectApps.sortedBy { it.name.lowercase() }
                    }
                }
                
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun launchApp(appId: String) {
        val device = currentDevice ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${apiBase(device)}/api/v2/applications/$appId")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                ).execute().close()
            }
        }
    }

    fun openUrl(url: String): Boolean {
        val socket = webSocket ?: return false
        val payload =
            """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$url","Option":"false","TypeOfRemote":"SendInputString"}}"""
        return socket.send(payload)
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ws = webSocket ?: return@withContext false
        val payload = JsonObject().apply {
            addProperty("method", "ms.channel.emit")
            add(
                "params",
                JsonObject().apply {
                    addProperty("event", "ed.apps.launch")
                    addProperty("to", "host")
                    add(
                        "data",
                        JsonObject().apply {
                            addProperty("action_type", "NATIVE_LAUNCH")
                            addProperty("appId", "org.tizen.browser")
                            addProperty("metaTag", url)
                        }
                    )
                }
            )
        }.toString()

        if (ws.send(payload)) {
            return@withContext true
        }

        val device = currentDevice ?: return@withContext false
        runCatching {
            val body = """{"appId":"org.tizen.browser","action_type":"NATIVE_LAUNCH","metaTag":"$url"}"""
            client.newCall(
                Request.Builder()
                    .url("${apiBase(device)}/api/v2/applications/org.tizen.browser")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url("http://$ip:8001/api/v2/").build()
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val json = gson.fromJson(body, JsonObject::class.java)
                val deviceInfo = json.getAsJsonObject("device") ?: return@use null
                val name = deviceInfo.get("name")?.asString ?: "Samsung TV ($ip)"
                val mac = deviceInfo.get("wifiMac")?.asString.orEmpty()
                val modelYear = deviceInfo.get("modelYear")?.asString.orEmpty()
                val wsPort = if ((modelYear.toIntOrNull() ?: 0) >= 2016) 8002 else 8001

                TvDevice(
                    name = name,
                    ip = ip,
                    port = wsPort,
                    macAddress = mac,
                    modelYear = modelYear,
                    brand = TvBrand.SAMSUNG
                )
            }
        }.getOrNull()
    }

    private fun startSocketConnection(device: TvDevice, emitConnecting: Boolean) {
        val connectionId = ++activeConnectionId
        closeCurrentSocket()
        connectionWasEstablished = false
        if (emitConnecting) {
            _connectionState.value = ConnectionState.Connecting
        }

        val useSecure = device.port == 8002
        val tokenQuery = prefs.getString(tokenPrefKey(device.ip), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { "&token=$it" }
            .orEmpty()
        val protocol = if (useSecure) "wss" else "ws"
        val wsUrl =
            "$protocol://${device.ip}:${device.port}/api/v2/channels/samsung.remote.control?name=$appName$tokenQuery"

        Log.d(TAG, "Connecting to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionId != activeConnectionId) {
                    webSocket.cancel()
                    return
                }
                reconnectJob?.cancel()
                reconnectAttempts = 0
                connectionWasEstablished = true
                _connectionState.value = ConnectionState.Connected(device)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionId != activeConnectionId) return
                parseAndPersistToken(device.ip, text)
                parseInstalledAppsResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionId != activeConnectionId) return

                val message = t.message ?: response?.message ?: "Connection failed"
                Log.e(TAG, "Samsung socket failure: $message")

                if (!useSecure && shouldRetryOnSecurePort(message)) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableSocketProblem(message)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                    return
                }

                _connectionState.value = ConnectionState.Error(userFacingMessage(message))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return
                runCatching { webSocket.close(1000, "Client closing") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return

                if (manualDisconnect) {
                    _connectionState.value = ConnectionState.Disconnected
                    return
                }

                if (!useSecure && code == 1005) {
                    connect(device.copy(port = 8002))
                    return
                }

                if (connectionWasEstablished && isRecoverableCloseCode(code)) {
                    scheduleReconnect(device, "TV closed the remote session.")
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        })
    }

    private fun scheduleReconnect(device: TvDevice, fallbackMessage: String) {
        if (manualDisconnect) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error(fallbackMessage)
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts += 1
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectDelayMs(reconnectAttempts))
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                startSocketConnection(device, emitConnecting = true)
            }
        }
    }

    private fun closeCurrentSocket() {
        webSocket?.cancel()
        webSocket = null
    }

    private fun parseAndPersistToken(ip: String, text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            val event = json.get("event")?.asString
            if (event == "ms.channel.connect") {
                val token = json.getAsJsonObject("data")
                    ?.get("token")
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                if (token != null) {
                    prefs.edit().putString(tokenPrefKey(ip), token).apply()
                }
            }
        }
    }

    private suspend fun requestInstalledAppsOverWebSocket(): List<TvApp> = withContext(Dispatchers.IO) {
        val socket = webSocket ?: return@withContext emptyList()
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            pendingInstalledAppsCallback = { apps ->
                if (continuation.isActive) {
                    continuation.resume(apps, onCancellation = null)
                }
            }

            val payload = JsonObject().apply {
                addProperty("method", "ms.channel.emit")
                add(
                    "params",
                    JsonObject().apply {
                        addProperty("event", "ed.installedApp.get")
                        addProperty("to", "host")
                    }
                )
            }.toString()

            val sent = socket.send(payload)
            if (!sent) {
                pendingInstalledAppsCallback = null
                continuation.resume(emptyList(), onCancellation = null)
                return@suspendCancellableCoroutine
            }

            scope.launch {
                delay(4000L)
                if (continuation.isActive) {
                    pendingInstalledAppsCallback = null
                    continuation.resume(emptyList(), onCancellation = null)
                }
            }
        }
    }

    private fun parseInstalledAppsResponse(text: String) {
        runCatching {
            val json = gson.fromJson(text, JsonObject::class.java)
            if (json.get("event")?.asString != "ed.installedApp.get") {
                return
            }

            val appsArray = json.getAsJsonObject("data")
                ?.getAsJsonArray("data")
                ?: JsonArray()

            val apps = appsArray.mapNotNull { element ->
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val appId = obj.get("appId")?.asString ?: return@mapNotNull null
                val name = obj.get("name")?.asString ?: appId
                TvApp(
                    appId = appId,
                    name = name,
                    version = obj.get("version")?.asString.orEmpty(),
                    visible = obj.get("visible")?.asBoolean ?: true
                )
            }.sortedBy { it.name.lowercase() }

            pendingInstalledAppsCallback?.also { callback ->
                pendingInstalledAppsCallback = null
                callback(apps)
            }
        }.onFailure {
            Log.d(TAG, "Failed to parse Samsung installed apps event: ${it.message}")
        }
    }

    private fun buildKeyPayload(keyCode: String): String {
        return """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$keyCode","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
    }

    private fun apiBase(device: TvDevice): String = "http://${device.ip}:8001"

    private fun tokenPrefKey(ip: String): String = "token_$ip"

    private fun shouldRetryOnSecurePort(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "ssl" in normalized ||
            "handshake" in normalized ||
            "protocol_error" in normalized ||
            "connection reset" in normalized
    }

    private fun isRecoverableSocketProblem(message: String): Boolean {
        val normalized = message.lowercase()
        return "1005" in normalized ||
            "reserved and may not be used" in normalized ||
            "connection reset" in normalized ||
            "broken pipe" in normalized ||
            "socket closed" in normalized ||
            "eof" in normalized ||
            "timeout" in normalized ||
            "canceled" in normalized
    }

    private fun isRecoverableCloseCode(code: Int): Boolean {
        return code == 1001 || code == 1005 || code == 1006 || code == 1011
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        return when (attempt) {
            1 -> 1200L
            2 -> 2500L
            3 -> 4000L
            4 -> 6000L
            else -> 8000L
        }
    }

    private fun userFacingMessage(message: String): String {
        val normalized = message.lowercase()
        return when {
            "1005" in normalized -> "TV closed the remote socket unexpectedly."
            "handshake" in normalized || "ssl" in normalized ->
                "Secure handshake failed. Accept the permission prompt on the TV."
            "timeout" in normalized ->
                "Connection timed out. Make sure the phone and TV are on the same Wi-Fi."
            else -> message
        }
    }
}

