package com.ued.universaltvremote.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

internal data class SsdpResponse(
    val senderIp: String,
    val headers: Map<String, String>
) {
    val locationUrl: String? get() = headers["LOCATION"]
}

internal fun parseSsdpHeaders(rawMessage: String): Map<String, String> {
    return rawMessage
        .split(Regex("\\r?\\n"))
        .drop(1)
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val key = line.substring(0, separator).trim().uppercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            key.takeIf { it.isNotEmpty() }?.let { it to value }
        }
        .toMap()
}

internal fun detectBrandFromText(text: String?): TvBrand? {
    val normalized = text
        ?.lowercase(Locale.US)
        ?.replace('_', ' ')
        ?.replace('-', ' ')
        ?.trim()
        .orEmpty()

    if (normalized.isBlank()) return null

    return when {
        "roku" in normalized -> TvBrand.ROKU
        "samsung" in normalized || "tizen" in normalized -> TvBrand.SAMSUNG
        "webos" in normalized || " lg " in " $normalized " || "lge" in normalized -> TvBrand.LG
        "bravia" in normalized || "sony" in normalized -> TvBrand.SONY
        "fire tv" in normalized || ("amazon" in normalized && "tv" in normalized) -> TvBrand.FIRE_TV
        "vidaa" in normalized || "hisense" in normalized -> TvBrand.VIDAA
        "smartcast" in normalized || "vizio" in normalized -> TvBrand.VIZIO
        "xiaomi" in normalized || "mi tv" in normalized -> TvBrand.XIAOMI
        "tcl" in normalized -> TvBrand.TCL
        "panasonic" in normalized -> TvBrand.PANASONIC
        "casper" in normalized -> TvBrand.CASPER
        "android tv" in normalized || "google tv" in normalized || "chromecast" in normalized || "google cast" in normalized -> TvBrand.ANDROID_TV
        else -> null
    }
}

private data class DeviceDescription(
    val friendlyName: String = "",
    val manufacturer: String = "",
    val modelName: String = "",
    val modelNumber: String = "",
    val serialNumber: String = "",
    val udn: String = "",
    val deviceType: String = "",
    val presentationUrl: String = "",
    val locationUrl: String = "",
    val macAddress: String = ""
)

class TvDeviceProbe {

    companion object {
        private const val TAG = "TvDeviceProbe"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DESCRIPTION_PATHS = listOf(
            "/description.xml",
            "/ssdp/device-desc.xml",
            "/dd.xml"
        )
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun detectDevice(
        ip: String,
        candidatePort: Int? = null,
        hintedBrand: TvBrand? = null,
        locationUrl: String? = null,
        ssdpHeaders: Map<String, String> = emptyMap()
    ): TvDevice? = withContext(Dispatchers.IO) {
        val description = resolveDescription(ip, candidatePort, locationUrl)
        val descriptionBrand = detectBrandFromDescription(description)
        val headerBrand = detectBrandFromText(ssdpHeaders.values.joinToString(" "))

        val brandsToTry = buildList {
            hintedBrand?.takeUnless { it == TvBrand.UNKNOWN }?.let(::add)
            descriptionBrand?.let(::add)
            headerBrand?.let(::add)
            add(TvBrand.SAMSUNG)
            add(TvBrand.ROKU)
            add(TvBrand.SONY)
            if (candidatePort == 3000 || candidatePort == 3001 || descriptionBrand == TvBrand.LG) add(TvBrand.LG)
            add(TvBrand.ANDROID_TV)
            add(TvBrand.TCL)
            add(TvBrand.XIAOMI)
            add(TvBrand.CASPER)
            add(TvBrand.PANASONIC)
            add(TvBrand.FIRE_TV)
            add(TvBrand.VIDAA)
            add(TvBrand.VIZIO)
        }.distinct()

        brandsToTry.forEach { brand ->
            val device = when (brand) {
                TvBrand.SAMSUNG -> probeSamsung(ip)
                TvBrand.ROKU -> probeRoku(ip)
                TvBrand.SONY -> probeSony(ip) ?: if (
                    description != null || hintedBrand == TvBrand.SONY
                ) {
                    buildDeviceFromDescription(ip, brand, description, candidatePort)
                } else {
                    null
                }
                TvBrand.LG -> probeLg(ip, description, candidatePort)
                TvBrand.ANDROID_TV,
                TvBrand.TCL,
                TvBrand.XIAOMI,
                TvBrand.CASPER,
                TvBrand.PANASONIC,
                TvBrand.FIRE_TV,
                TvBrand.VIDAA,
                TvBrand.VIZIO -> buildGenericDevice(ip, brand, description, candidatePort)
                TvBrand.UNKNOWN -> null
            }
            if (device != null) {
                return@withContext device
            }
        }

        buildDeviceFromDescription(
            ip = ip,
            brand = TvBrand.UNKNOWN,
            description = description,
            candidatePort = candidatePort
        )
    }

    private fun resolveDescription(ip: String, candidatePort: Int?, locationUrl: String?): DeviceDescription? {
        locationUrl?.let { fetchDeviceDescription(it)?.let { description -> return description } }

        val ports = buildList {
            candidatePort?.let(::add)
            add(80)
            add(8008)
            add(8009)
        }.filter { it > 0 }.distinct()

        ports.forEach { port ->
            DESCRIPTION_PATHS.forEach { path ->
                val url = "http://$ip:$port$path"
                fetchDeviceDescription(url)?.let { description -> return description }
            }
        }

        return null
    }

    private fun fetchDeviceDescription(url: String): DeviceDescription? {
        val body = httpGet(url) ?: return null
        val document = parseXml(body) ?: return null
        val device = document.getElementsByTagName("device")
            .item(0) as? Element
            ?: return null

        return DeviceDescription(
            friendlyName = device.findFirstText("friendlyname"),
            manufacturer = device.findFirstText("manufacturer"),
            modelName = device.findFirstText("modelname"),
            modelNumber = device.findFirstText("modelnumber"),
            serialNumber = device.findFirstText("serialnumber"),
            udn = device.findFirstText("udn"),
            deviceType = device.findFirstText("devicetype"),
            presentationUrl = device.findFirstText("presentationurl"),
            locationUrl = url,
            macAddress = device.findFirstText("macaddress")
        )
    }

    private fun probeSamsung(ip: String): TvDevice? {
        val body = httpGet("http://$ip:8001/api/v2/") ?: return null
        val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
        val deviceInfo = json.getAsJsonObject("device") ?: return null
        val name = deviceInfo.get("name")?.asString?.takeIf { it.isNotBlank() } ?: "Samsung TV ($ip)"
        val mac = deviceInfo.get("wifiMac")?.asString.orEmpty()
        val modelYear = deviceInfo.get("modelYear")?.asString.orEmpty()
        val wsPort = if ((modelYear.toIntOrNull() ?: 0) >= 2016) 8002 else 8001
        return TvDevice(
            name = name,
            ip = ip,
            port = wsPort,
            macAddress = mac,
            modelYear = modelYear,
            brand = TvBrand.SAMSUNG
        )
    }

    private fun probeRoku(ip: String): TvDevice? {
        val body = httpGet("http://$ip:8060/query/device-info") ?: return null
        val document = parseXml(body) ?: return null
        val root = document.documentElement ?: return null
        val name = root.findFirstText("user-device-name")
            .ifBlank { root.findFirstText("friendly-device-name") }
            .ifBlank { root.findFirstText("model-name") }
            .ifBlank { "Roku TV ($ip)" }
        val modelName = root.findFirstText("model-name")
        val mac = root.findFirstText("wifi-mac")

        return TvDevice(
            name = name,
            ip = ip,
            port = 8060,
            macAddress = mac,
            modelYear = modelName,
            brand = TvBrand.ROKU
        )
    }

    private fun probeSony(ip: String): TvDevice? {
        val payload = JsonObject().apply {
            addProperty("method", "getSystemInformation")
            addProperty("id", 1)
            add("params", JsonArray())
            addProperty("version", "1.0")
        }.toString()

        val headersToTry = listOf(
            emptyMap(),
            mapOf("X-Auth-PSK" to "0000")
        )

        headersToTry.forEach { headers ->
            val body = httpPostJson("http://$ip/sony/system", payload, headers) ?: return@forEach
            val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return@forEach
            val result = json.getAsJsonArray("result")?.firstObjectOrNull() ?: return@forEach
            val name = result.get("name")?.asString?.takeIf { it.isNotBlank() }
                ?: result.get("model")?.asString?.takeIf { it.isNotBlank() }
                ?: "Sony BRAVIA ($ip)"
            val model = result.get("model")?.asString.orEmpty()
            val mac = result.get("macAddr")?.asString.orEmpty()

            return TvDevice(
                name = name,
                ip = ip,
                port = 80,
                macAddress = mac,
                modelYear = model,
                brand = TvBrand.SONY
            )
        }

        return null
    }

    private fun probeLg(ip: String, description: DeviceDescription?, candidatePort: Int?): TvDevice? {
        if (candidatePort != 3000 && candidatePort != 3001 && detectBrandFromDescription(description) != TvBrand.LG) {
            return null
        }

        return buildDeviceFromDescription(
            ip = ip,
            brand = TvBrand.LG,
            description = description,
            candidatePort = candidatePort ?: 3001
        )
    }

    private fun buildGenericDevice(
        ip: String,
        brand: TvBrand,
        description: DeviceDescription?,
        candidatePort: Int?
    ): TvDevice? {
        if (description == null && !isLikelyPortForBrand(brand, candidatePort)) {
            return null
        }
        return buildDeviceFromDescription(ip, brand, description, candidatePort)
    }

    private fun buildDeviceFromDescription(
        ip: String,
        brand: TvBrand,
        description: DeviceDescription?,
        candidatePort: Int?
    ): TvDevice? {
        val inferredBrand = detectBrandFromDescription(description) ?: brand.takeUnless { it == TvBrand.UNKNOWN }
        if (description == null && inferredBrand == null) return null

        val finalBrand = inferredBrand ?: brand
        val name = sequenceOf(
            description?.friendlyName,
            listOf(description?.manufacturer, description?.modelName)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() },
            defaultDeviceName(finalBrand, ip)
        ).filterNotNull().first()

        val model = listOf(description?.modelName, description?.modelNumber)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()

        val port = when (finalBrand) {
            TvBrand.SAMSUNG -> if (candidatePort == 8002) 8002 else 8001
            TvBrand.LG -> candidatePort?.takeIf { it == 3000 || it == 3001 } ?: 3001
            TvBrand.ROKU -> 8060
            TvBrand.SONY -> 80
            else -> candidatePort?.takeIf { it > 0 }
                ?: description?.locationUrl?.let(::portFromUrl)
                ?: 80
        }

        return TvDevice(
            name = name,
            ip = ip,
            port = port,
            macAddress = description?.macAddress.orEmpty(),
            modelYear = model,
            brand = finalBrand
        )
    }

    private fun detectBrandFromDescription(description: DeviceDescription?): TvBrand? {
        if (description == null) return null
        val combined = listOf(
            description.friendlyName,
            description.manufacturer,
            description.modelName,
            description.modelNumber,
            description.deviceType,
            description.presentationUrl
        ).joinToString(" ")
        return detectBrandFromText(combined)
    }

    private fun defaultDeviceName(brand: TvBrand, ip: String): String {
        return when (brand) {
            TvBrand.SAMSUNG -> "Samsung TV ($ip)"
            TvBrand.LG -> "LG webOS TV ($ip)"
            TvBrand.SONY -> "Sony BRAVIA ($ip)"
            TvBrand.ROKU -> "Roku TV ($ip)"
            TvBrand.ANDROID_TV -> "Android TV ($ip)"
            TvBrand.TCL -> "TCL TV ($ip)"
            TvBrand.XIAOMI -> "Xiaomi TV ($ip)"
            TvBrand.CASPER -> "Casper TV ($ip)"
            TvBrand.PANASONIC -> "Panasonic TV ($ip)"
            TvBrand.VIZIO -> "Vizio SmartCast ($ip)"
            TvBrand.VIDAA -> "Vidaa TV ($ip)"
            TvBrand.FIRE_TV -> "Fire TV ($ip)"
            TvBrand.UNKNOWN -> "TV ($ip)"
        }
    }

    private fun isLikelyPortForBrand(brand: TvBrand, candidatePort: Int?): Boolean {
        return when (brand) {
            TvBrand.ANDROID_TV,
            TvBrand.TCL,
            TvBrand.XIAOMI,
            TvBrand.CASPER,
            TvBrand.PANASONIC,
            TvBrand.FIRE_TV -> candidatePort == 8008 || candidatePort == 8009
            else -> false
        }
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        return runCatching {
            val builder = Request.Builder().url(url)
            headers.forEach { (key, value) -> builder.addHeader(key, value) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.onFailure {
            Log.v(TAG, "GET failed for $url: ${it.message}")
        }.getOrNull()
    }

    private fun httpPostJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String? {
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
            headers.forEach { (key, value) -> builder.addHeader(key, value) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.onFailure {
            Log.v(TAG, "POST failed for $url: ${it.message}")
        }.getOrNull()
    }

    private fun parseXml(xml: String): Document? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val builder = factory.newDocumentBuilder()
            builder.parse(InputSource(StringReader(xml.trim())))
        }.getOrNull()
    }

    private fun portFromUrl(url: String): Int? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase(Locale.US)) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> null
        }
    }

    private fun JsonArray.firstObjectOrNull(): JsonObject? {
        if (size() == 0) return null
        return runCatching { get(0).asJsonObject }.getOrNull()
    }

    private fun Element.findFirstText(tagName: String): String {
        val wanted = tagName.lowercase(Locale.US)
        val allNodes = getElementsByTagName("*")
        for (index in 0 until allNodes.length) {
            val node = allNodes.item(index) as? Element ?: continue
            val localName = (node.localName ?: node.nodeName.substringAfter(':')).lowercase(Locale.US)
            if (localName == wanted) {
                return node.textContent?.trim().orEmpty()
            }
        }
        return ""
    }
}
