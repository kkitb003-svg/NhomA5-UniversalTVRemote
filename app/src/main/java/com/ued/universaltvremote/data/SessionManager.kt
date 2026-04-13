package com.ued.universaltvremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tv_session")

class SessionManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        private val KEY_LAST_IP = stringPreferencesKey("last_ip")
        private val KEY_LAST_NAME = stringPreferencesKey("last_name")
        private val KEY_LAST_MAC = stringPreferencesKey("last_mac")
        private val KEY_LAST_PORT = stringPreferencesKey("last_port")
        private val KEY_LAST_DEVICE_JSON = stringPreferencesKey("last_device_json")
        private val KEY_SAVED_DEVICES = stringPreferencesKey("saved_devices")
    }

    val lastDevice: Flow<TvDevice?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_DEVICE_JSON]?.let { json ->
            runCatching { gson.fromJson(json, TvDevice::class.java) }.getOrNull()
        } ?: run {
        val ip = prefs[KEY_LAST_IP] ?: return@map null
        val name = prefs[KEY_LAST_NAME] ?: ip
        val mac = prefs[KEY_LAST_MAC] ?: ""
        val port = prefs[KEY_LAST_PORT]?.toIntOrNull() ?: 8001
        TvDevice(name = name, ip = ip, port = port, macAddress = mac)
        }
    }

    val savedDevices: Flow<List<TvDevice>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_SAVED_DEVICES] ?: "[]"
        try {
            val type = object : TypeToken<List<TvDevice>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveDevice(device: TvDevice) {
        val currentList = savedDevices.first().toMutableList()
        // Remove old entry with same IP or MAC to update it
        currentList.removeAll { 
            (it.ip == device.ip) || (device.macAddress.isNotBlank() && it.macAddress == device.macAddress) 
        }
        currentList.add(0, device) // Add to top of history
        
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_IP] = device.ip
            prefs[KEY_LAST_NAME] = device.name
            prefs[KEY_LAST_MAC] = device.macAddress
            prefs[KEY_LAST_PORT] = device.port.toString()
            prefs[KEY_LAST_DEVICE_JSON] = gson.toJson(device)
            prefs[KEY_SAVED_DEVICES] = gson.toJson(currentList)
        }
    }

    suspend fun clearDevice() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_IP)
            prefs.remove(KEY_LAST_NAME)
            prefs.remove(KEY_LAST_MAC)
            prefs.remove(KEY_LAST_PORT)
            prefs.remove(KEY_LAST_DEVICE_JSON)
        }
    }

    suspend fun removeSavedDevice(device: TvDevice) {
        val currentList = savedDevices.first().toMutableList()
        currentList.removeAll { it.ip == device.ip }
        context.dataStore.edit { prefs ->
            prefs[KEY_SAVED_DEVICES] = gson.toJson(currentList)
            // If we remove the last selected device, also clean it from auto-reconnect
            if (prefs[KEY_LAST_IP] == device.ip) {
                prefs.remove(KEY_LAST_IP)
                prefs.remove(KEY_LAST_NAME)
                prefs.remove(KEY_LAST_MAC)
                prefs.remove(KEY_LAST_PORT)
                prefs.remove(KEY_LAST_DEVICE_JSON)
            }
        }
    }
}
