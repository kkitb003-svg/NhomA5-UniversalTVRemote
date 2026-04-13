package com.ued.universaltvremote.network

import android.content.Context
import android.net.wifi.WifiManager
import com.ued.universaltvremote.model.TvBrand
import com.ued.universaltvremote.model.TvDevice
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class TvDiscoveryService(private val context: Context) {

    companion object {
        private const val TAG = "TvDiscoveryService"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DEFAULT_SCAN_DURATION_MS = 15_000L
        private const val SOCKET_TIMEOUT_MS = 600
        private val SEARCH_TARGETS = listOf(
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "roku:ecp",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:google.com:service:Android TV Remote:1",
            "urn:bravia-remote-control:service:RemoteControl:1"
        )
        private val PORT_PROBES = listOf(
            8001 to TvBrand.SAMSUNG,
            8002 to TvBrand.SAMSUNG,
            3001 to TvBrand.LG,
            3000 to TvBrand.LG,
            8060 to TvBrand.ROKU,
            80 to TvBrand.SONY,
            8008 to TvBrand.ANDROID_TV,
            8009 to TvBrand.ANDROID_TV,
            8443 to TvBrand.ANDROID_TV,
            9000 to TvBrand.ANDROID_TV,
            9001 to TvBrand.ANDROID_TV,
            6466 to TvBrand.ANDROID_TV,
            6467 to TvBrand.ANDROID_TV,
            7345 to TvBrand.VIZIO,
            36669 to TvBrand.VIDAA,
            8080 to TvBrand.FIRE_TV,
            5555 to TvBrand.ANDROID_TV
        )
    }

    private val deviceProbe = TvDeviceProbe()

    fun discoverTvs(scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS): Flow<TvDevice> = callbackFlow {
        val multicastLock = acquireMulticastLock()
        val socket = DatagramSocket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            broadcast = true
            reuseAddress = true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seenCandidates = ConcurrentHashMap.newKeySet<String>()

        fun enqueueProbe(
            ip: String,
            candidatePort: Int? = null,
            hintedBrand: TvBrand? = null,
            locationUrl: String? = null,
            headers: Map<String, String> = emptyMap()
        ) {
            if (ip.isBlank()) return
            val candidateKey = listOf(ip, candidatePort ?: -1, hintedBrand?.name.orEmpty(), locationUrl.orEmpty())
                .joinToString("|")
            if (!seenCandidates.add(candidateKey)) return

            scope.launch {
                val device = deviceProbe.detectDevice(
                    ip = ip,
                    candidatePort = candidatePort,
                    hintedBrand = hintedBrand,
                    locationUrl = locationUrl,
                    ssdpHeaders = headers
                ) ?: return@launch
                if (isActive) {
                    trySend(device)
                }
            }
        }

        val senderJob = scope.launch {
            repeat(2) {
                SEARCH_TARGETS.forEach { searchTarget ->
                    sendSearch(socket, searchTarget)
                    delay(150)
                }
                delay(900)
            }
        }

        val receiverJob = scope.launch {
            val deadline = System.currentTimeMillis() + scanDurationMs
            val buffer = ByteArray(8192)
            while (isActive && System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val rawResponse = String(packet.data, 0, packet.length)
                    val headers = parseSsdpHeaders(rawResponse)
                    if (headers.isEmpty()) continue

                    val locationUrl = headers["LOCATION"]
                    val hintedBrand = detectBrandFromText(
                        listOfNotNull(headers["ST"], headers["SERVER"], headers["USN"]).joinToString(" ")
                    )

                    enqueueProbe(
                        ip = locationUrl?.let(::hostFromUrl) ?: packet.address.hostAddress.orEmpty(),
                        candidatePort = locationUrl?.let(::portFromUrl),
                        hintedBrand = hintedBrand,
                        locationUrl = locationUrl,
                        headers = headers
                    )
                } catch (_: SocketTimeoutException) {
                    // Continue polling until the overall scan timeout expires.
                } catch (_: Exception) {
                    // Ignore malformed packets and keep scanning.
                }
            }
        }

        val subnetJob = scope.launch {
            withTimeoutOrNull(scanDurationMs) {
                scanCommonPorts { ip, port, brand ->
                    enqueueProbe(
                        ip = ip,
                        candidatePort = port,
                        hintedBrand = brand
                    )
                }
            }
        }

        scope.launch {
            joinAll(senderJob, receiverJob, subnetJob)
            channel.close()
        }

        awaitClose {
            scope.cancel()
            runCatching { socket.close() }
            multicastLock?.let { lock ->
                runCatching {
                    if (lock.isHeld) {
                        lock.release()
                    }
                }
            }
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return runCatching {
            wifiManager
                ?.createMulticastLock("tv_discovery_lock")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
    }

    private fun sendSearch(socket: DatagramSocket, searchTarget: String) {
        val payload = buildSearchPayload(searchTarget).toByteArray()
        val packet = DatagramPacket(
            payload,
            payload.size,
            InetAddress.getByName(SSDP_ADDRESS),
            SSDP_PORT
        )
        runCatching { socket.send(packet) }
    }

    private fun buildSearchPayload(searchTarget: String): String {
        return listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: $SSDP_ADDRESS:$SSDP_PORT",
            "MAN: \"ssdp:discover\"",
            "MX: 2",
            "ST: $searchTarget",
            "",
            ""
        ).joinToString("\r\n")
    }

    private suspend fun scanCommonPorts(onFound: suspend (ip: String, port: Int, brand: TvBrand) -> Unit) =
        withContext(Dispatchers.IO) {
            val localIp = getLocalIpAddress() ?: return@withContext
            val subnet = localIp.substringBeforeLast(".")
            val limiter = Semaphore(48)

            coroutineScope {
                val jobs = (1..254).map { host ->
                    launch {
                        val ip = "$subnet.$host"
                        if (ip == localIp) return@launch

                        PORT_PROBES.forEach { (port, brand) ->
                            limiter.withPermit {
                                if (isPortOpen(ip, port)) {
                                    onFound(ip, port, brand)
                                }
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
        }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 250)
                true
            }
        }.getOrDefault(false)
    }

    private fun getLocalIpAddress(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun hostFromUrl(url: String): String? {
        return runCatching { URI(url).host }.getOrNull()
    }

    private fun portFromUrl(url: String): Int? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> null
        }
    }
}
