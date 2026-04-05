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


