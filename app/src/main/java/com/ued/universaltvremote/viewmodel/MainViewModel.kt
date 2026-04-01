package com.ued.universaltvremote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ued.universaltvremote.data.MacroManager
import com.ued.universaltvremote.data.SessionManager
import com.ued.universaltvremote.model.Macro
import com.ued.universaltvremote.model.TvApp
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import com.ued.universaltvremote.network.ConnectionState
import com.ued.universaltvremote.network.SamsungRemoteRepository
import com.ued.universaltvremote.network.TvDeviceProbe
import com.ued.universaltvremote.network.TvDiscoveryService
import com.ued.universaltvremote.network.TvRemoteRepository
import com.ued.universaltvremote.network.TvRepositoryFactory
import com.ued.universaltvremote.util.WakeOnLanUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var repository: TvRemoteRepository? = null
    private var repositoryJob: Job? = null
    private var connectJob: Job? = null
    private var scanJob: Job? = null

    private val sessionManager = SessionManager(application)
    private val discoveryService = TvDiscoveryService(application)
    private val deviceProbe = TvDeviceProbe()
    val macroManager = MacroManager(File(application.filesDir, "macros.json"))

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val savedDevices: StateFlow<List<TvDevice>> = sessionManager.savedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _installedApps = MutableStateFlow<List<TvApp>>(emptyList())
    val installedApps: StateFlow<List<TvApp>> = _installedApps.asStateFlow()

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var pendingConnectDevice: TvDevice? = null

    init {
        viewModelScope.launch {
            connectionState.drop(1).collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        showSnackbar("Connected: ${state.device.name}")
                        sessionManager.saveDevice(state.device)
                        pendingConnectDevice = null
                    }

                    is ConnectionState.Error -> {
                        val name = pendingConnectDevice?.name ?: "TV"
                        showSnackbar("Connection error $name: ${state.message}")
                        pendingConnectDevice = null
                    }

                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            sessionManager.lastDevice.first()?.let { lastDevice ->
                pendingConnectDevice = lastDevice
                connect(lastDevice)
            }
        }

        viewModelScope.launch {
            _macros.value = macroManager.loadMacros()
        }
    }

    fun clearInstalledApps() {
        _installedApps.value = emptyList()
    }

    fun connect(device: TvDevice) {
        connectJob?.cancel()
        pendingConnectDevice = device
        showSnackbar("Connecting to ${device.name}...")

        connectJob = viewModelScope.launch {
            try {
                // Only resolve if brand is UNKNOWN or port is 0 (need auto-detection)
                // If user explicitly provided brand and port, skip probing
                val needsProbe = device.brand == TvBrand.UNKNOWN || device.port <= 0
                val resolvedDevice = if (needsProbe) resolveDevice(device) else device
                pendingConnectDevice = resolvedDevice
                connectResolved(resolvedDevice)
            } catch (e: Exception) {
                showSnackbar("Connection failed: ${e.message}")
                _connectionState.value = ConnectionState.Error("Connection error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectJob?.cancel()
            repositoryJob?.cancel()
            repository?.disconnect()
            sessionManager.clearDevice()
            _installedApps.value = emptyList()
            showSnackbar("Disconnected")
        }
    }

    fun removeSavedDevice(device: TvDevice) {
        viewModelScope.launch {
            sessionManager.removeSavedDevice(device)
            showSnackbar("Removed TV from history")
        }
    }

    fun sendKey(keyCode: String) {
        repository?.sendKey(keyCode)
    }

    fun sendText(text: String) {
        viewModelScope.launch {
            if (repository?.sendText(text) == true) {
                return@launch
            }
            text.forEach { char ->
                val keyCode = charToKeyCode(char)
                if (keyCode != null) {
                    sendKey(keyCode)
                    delay(200)
                }
            }
            sendKey("KEY_ENTER")
        }
    }

    fun wakeOnLan() {
        viewModelScope.launch {
            val device = (connectionState.value as? ConnectionState.Connected)?.device
                ?: sessionManager.lastDevice.first()

            if (device == null || device.macAddress.isBlank()) {
                showSnackbar("No MAC address. Connect once before using Wake-on-LAN.")
                return@launch
            }

            showSnackbar("Sending Wake-on-LAN...")
            val result = WakeOnLanUtil.sendMagicPacket(device.macAddress)
            if (result.isSuccess) {
                showSnackbar("Magic packet sent to ${device.macAddress}")
            } else {
                showSnackbar("Wake-on-LAN failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return

        _isScanning.value = true
        _discoveredDevices.value = emptyList()

        scanJob = viewModelScope.launch {
            try {
                discoveryService.discoverTvs().collect { device ->
                    upsertDiscoveredDevice(device)
                }
            } catch (e: Exception) {
                showSnackbar("Scan error: ${e.message}")
            } finally {
                _isScanning.value = false
                scanJob = null
            }
        }
    }

    fun fetchApps() {
        if (connectionState.value !is ConnectionState.Connected) {
            showSnackbar("TV not connected")
            return
        }

        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                val apps = repository?.fetchInstalledApps() ?: emptyList()
                _installedApps.value = apps
                if (apps.isEmpty()) {
                    showSnackbar("No apps found or this TV rejected the app list request")
                }
            } catch (e: Exception) {
                _installedApps.value = emptyList()
                showSnackbar("Failed to load apps: ${e.message}")
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun launchApp(appId: String) {
        if (connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            repository?.launchApp(appId)
        }
    }

    fun playMacro(macro: Macro) {
        viewModelScope.launch {
            macro.actions.forEach { action ->
                sendKey(action.keyCode)
                delay(action.delayMs)
            }
            showSnackbar("Macro '${macro.name}' finished")
        }
    }

    fun saveMacro(macro: Macro) {
        viewModelScope.launch {
            macroManager.addMacro(macro)
            _macros.value = macroManager.loadMacros()
        }
    }

    fun deleteMacro(macroId: String) {
        viewModelScope.launch {
            macroManager.deleteMacro(macroId)
            _macros.value = macroManager.loadMacros()
        }
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun connectToIp(ipInput: String, port: Int = 0, brand: TvBrand = TvBrand.UNKNOWN) {
        val ip = ipInput.trim()
        if (ip.isBlank()) return
        val resolvedBrand = if (brand != TvBrand.UNKNOWN) brand else TvBrand.ANDROID_TV
        val resolvedPort = if (port > 0) port else 0
        connect(TvDevice(name = "TV ($ip)", ip = ip, port = resolvedPort, brand = resolvedBrand))
    }

    fun openUrlOnTv(url: String): Boolean {
        if (connectionState.value !is ConnectionState.Connected) {
            showSnackbar("TV chưa kết nối")
            return false
        }

        var success = false
        runCatching {
            kotlinx.coroutines.runBlocking {
                success = repository?.openUrlViaBrowser(url) == true
            }
        }
        if (!success) {
            showSnackbar("TV này không hỗ trợ mở URL trực tiếp")
        }
        return success
    }

    override fun onCleared() {
        scanJob?.cancel()
        connectJob?.cancel()
        repositoryJob?.cancel()
        repository?.disconnect()
        super.onCleared()
    }

    private fun charToKeyCode(char: Char): String? = when {
        char.isDigit() -> "KEY_${char}"
        char.isLetter() -> "KEY_${char.uppercaseChar()}"
        char == ' ' -> "KEY_SPACE"
        char == '\n' -> "KEY_ENTER"
        else -> null
    }

    private suspend fun resolveDevice(device: TvDevice): TvDevice {
        val needsResolution = when (device.brand) {
            TvBrand.UNKNOWN -> true
            TvBrand.LG -> device.port != 3000 && device.port != 3001
            TvBrand.ROKU -> device.port != 8060
            TvBrand.SONY -> device.port <= 0 || device.port == 8001
            else -> device.port <= 0
        }

        if (!needsResolution) {
            return device
        }

        return deviceProbe.detectDevice(
            ip = device.ip,
            candidatePort = device.port.takeIf { it > 0 },
            hintedBrand = device.brand.takeUnless { it == TvBrand.UNKNOWN }
        ) ?: device
    }

    private fun connectResolved(device: TvDevice) {
        repository?.disconnect()
        repositoryJob?.cancel()
        _installedApps.value = emptyList()

        val newRepository = TvRepositoryFactory.create(device.brand, getApplication())
        repository = newRepository

        repositoryJob = viewModelScope.launch {
            try {
                newRepository.connectionState.collect { _connectionState.value = it }
            } catch (_: Exception) {
                // Connection closed
            }
        }
        try {
            newRepository.connect(device)
        } catch (e: Exception) {
            showSnackbar("Connect error: ${e.message}")
            _connectionState.value = ConnectionState.Error("Connection error: ${e.message}")
        }
    }

    private fun upsertDiscoveredDevice(device: TvDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.ip == device.ip }

        if (existingIndex == -1) {
            current.add(device)
        } else {
            val existing = current[existingIndex]
            val shouldReplace =
                existing.brand == TvBrand.UNKNOWN && device.brand != TvBrand.UNKNOWN ||
                    existing.macAddress.isBlank() && device.macAddress.isNotBlank() ||
                    existing.name.startsWith("TV (") && !device.name.startsWith("TV (")

            if (shouldReplace) {
                current[existingIndex] = device
            }
        }

        _discoveredDevices.value = current
    }
}
