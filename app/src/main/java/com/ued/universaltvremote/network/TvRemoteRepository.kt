package com.ued.universaltvremote.network

import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: TvDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

interface TvRemoteRepository {
    val connectionState: StateFlow<ConnectionState>
    
    fun connect(device: TvDevice)
    fun disconnect()
    fun sendKey(keyCode: String)
    fun sendText(text: String): Boolean = false
    
    suspend fun launchApp(appId: String) {}
    
    suspend fun fetchInstalledApps(): List<com.ued.universaltvremote.model.TvApp> = emptyList()
    
    suspend fun openUrlViaBrowser(url: String): Boolean = false
    
    suspend fun getDeviceInfo(ip: String, port: Int = 8001): TvDevice? = null
}
