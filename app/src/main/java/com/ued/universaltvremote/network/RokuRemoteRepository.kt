package com.ued.universaltvremote.network

import android.util.Log
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class RokuRemoteRepository : TvRemoteRepository {

    companion object {
        private const val TAG = "RokuRemoteRepo"
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val deviceProbe = TvDeviceProbe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var currentDevice: TvDevice? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect = false
    private var reconnectAttempts = 0

    override fun connect(device: TvDevice) {
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectAttempts = 0
        val resolvedDevice = device.copy(
            port = if (device.port > 0) device.port else 8060,
            brand = TvBrand.ROKU
        )
        currentDevice = resolvedDevice
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            // Validate connection with a quick ping to the Roku ECP endpoint
            val reachable = isRokuReachable(resolvedDevice.ip)
            if (reachable) {
                _connectionState.value = ConnectionState.Connected(resolvedDevice)
            } else {
                _connectionState.value = ConnectionState.Error(
                    "Cannot reach Roku TV at ${resolvedDevice.ip}. Ensure the TV is on and on the same Wi-Fi network."
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

        val rokuKey = when (keyCode) {
            "KEY_HOME" -> "Home"
            "KEY_LEFT" -> "Left"
            "KEY_RIGHT" -> "Right"
            "KEY_UP" -> "Up"
            "KEY_DOWN" -> "Down"
            "KEY_ENTER" -> "Select"
            "KEY_RETURN", "KEY_BACK" -> "Back"
            "KEY_VOLUP" -> "VolumeUp"
            "KEY_VOLDOWN" -> "VolumeDown"
            "KEY_MUTE" -> "VolumeMute"
            "KEY_PLAY" -> "Play"
            "KEY_PAUSE" -> "Play" // Roku toggles play/pause
            "KEY_STOP" -> "Back"
            "KEY_POWER", "KEY_POWEROFF" -> "Power"
            "KEY_RED" -> "InputTuner"
            "KEY_GREEN" -> "InputHDMI1"
            "KEY_YELLOW" -> "InputHDMI2"
            "KEY_BLUE" -> "InputHDMI3"
            "KEY_INFO" -> "Info"
            "KEY_MENU" -> "Home"
            "KEY_SOURCE" -> "InputTuner"
            "KEY_FF" -> "Fwd"
            "KEY_REWIND" -> "Rev"
            else -> keyCode.removePrefix("KEY_").lowercase().replaceFirstChar { it.uppercase() }
        }

        scope.launch {
            val success = postKeypress(ip, rokuKey)
            if (!success && !manualDisconnect) {
                scheduleReconnect(currentDevice ?: return@launch)
            }
        }
    }

    override fun sendText(text: String): Boolean {
        val ip = currentDevice?.ip ?: return false
        scope.launch {
            text.forEach { char ->
                val encoded = java.net.URLEncoder.encode(char.toString(), "UTF-8")
                postKeypress(ip, "Lit_$encoded")
                delay(50)
            }
        }
        return true
    }

    override suspend fun launchApp(appId: String) {
        val ip = currentDevice?.ip ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("http://$ip:8060/launch/$appId")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                client.newCall(req).execute().close()
            }
        }
    }

    override suspend fun fetchInstalledApps(): List<TvApp> = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext emptyList()
        runCatching {
            val request = Request.Builder()
                .url("http://$ip:8060/query/apps")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use emptyList<TvApp>()
                parseRokuApps(body)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun openUrlViaBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        val ip = currentDevice?.ip ?: return@withContext false
        // Roku doesn't have a built-in browser, but we can try launching a web browser app
        runCatching {
            val req = Request.Builder()
                .url("http://$ip:8060/launch/837?url=$url")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun getDeviceInfo(ip: String, port: Int): TvDevice? {
        return deviceProbe.detectDevice(
            ip = ip,
            candidatePort = if (port > 0) port else 8060,
            hintedBrand = TvBrand.ROKU
        )
    }

    private suspend fun postKeypress(ip: String, key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://$ip:8060/keypress/$key")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.onFailure {
            Log.e(TAG, "Roku keypress failed: ${it.message}")
        }.getOrDefault(false)
    }

    private suspend fun isRokuReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("http://$ip:8060/query/device-info").build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun parseRokuApps(xml: String): List<TvApp> {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("app")
            val apps = mutableListOf<TvApp>()
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val id = element.getAttribute("id") ?: continue
                val name = element.textContent?.trim() ?: id
                val version = element.getAttribute("version") ?: ""
                apps.add(TvApp(appId = id, name = name, version = version))
            }
            apps.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun scheduleReconnect(device: TvDevice) {
        if (manualDisconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = ConnectionState.Error("Lost connection to Roku TV.")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempts++
        _connectionState.value = ConnectionState.Connecting

        reconnectJob = scope.launch {
            delay(reconnectAttempts * 2000L)
            if (!manualDisconnect && currentDevice?.ip == device.ip) {
                val reachable = isRokuReachable(device.ip)
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
