package com.ued.universaltvremote.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ued.universaltvremote.model.Macro
import com.ued.universaltvremote.model.MacroAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MacroManager(private val macrosFile: File) {

    private val gson = Gson()

    suspend fun loadMacros(): List<Macro> = withContext(Dispatchers.IO) {
        if (!macrosFile.exists()) return@withContext emptyList()
        runCatching {
            val type = object : TypeToken<List<Macro>>() {}.type
            gson.fromJson<List<Macro>>(macrosFile.readText(), type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun saveMacros(macros: List<Macro>) = withContext(Dispatchers.IO) {
        macrosFile.writeText(gson.toJson(macros))
    }

    suspend fun addMacro(macro: Macro) {
        val current = loadMacros().toMutableList()
        current.removeAll { it.id == macro.id }
        current.add(macro)
        saveMacros(current)
    }

    suspend fun deleteMacro(macroId: String) {
        val current = loadMacros().toMutableList()
        current.removeAll { it.id == macroId }
        saveMacros(current)
    }
}
