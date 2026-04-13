package com.ued.universaltvremote.model

import com.google.gson.annotations.SerializedName

enum class TvBrand {
    SAMSUNG, LG, SONY, TCL, XIAOMI, CASPER, PANASONIC, ANDROID_TV, ROKU, VIZIO, VIDAA, FIRE_TV, UNKNOWN
}

data class TvDevice(
    val name: String,
    val ip: String,
    val port: Int = 8001,
    val macAddress: String = "",
    val modelYear: String = "",
    val brand: TvBrand = TvBrand.UNKNOWN
)

data class TvApp(
    @SerializedName("appId") val appId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("version") val version: String = "",
    @SerializedName("running") val running: Boolean = false,
    @SerializedName("visible") val visible: Boolean = true
) {
    fun iconUrl(ip: String, port: Int = 8001) =
        "http://$ip:$port/api/v2/applications/$appId/icon"
}

data class MacroAction(
    val keyCode: String,
    val delayMs: Long = 300L
)

data class Macro(
    val id: String,
    val name: String,
    val actions: List<MacroAction>
)
