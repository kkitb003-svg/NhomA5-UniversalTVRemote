package com.ued.universaltvremote.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetworkAddressResolver {

    fun getPreferredIpv4Address(
        context: Context,
        remoteIp: String? = null
    ): String? {
        val candidates = linkedSetOf<String>()

        candidates += activeNetworkAddresses(context)
        wifiAddress(context)?.let(candidates::add)
        candidates += interfaceAddresses()

        return candidates
            .filter { it.isNotBlank() }
            .sortedByDescending { score(it, remoteIp) }
            .firstOrNull()
    }

    private fun activeNetworkAddresses(context: Context): List<String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = connectivityManager.activeNetwork ?: return emptyList()
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return emptyList()

        val isPreferredTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (!isPreferredTransport && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return emptyList()
        }

        return connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.mapNotNull { address ->
                address.hostAddress?.takeIf { address.isSiteLocalAddress }
            }
            .orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun wifiAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ip = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return listOf(
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        ).joinToString(".")
    }

    private fun interfaceAddresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { address ->
                    address.hostAddress?.takeIf { address.isSiteLocalAddress }
                }
        }.getOrDefault(emptyList())
    }

    private fun score(candidate: String, remoteIp: String?): Int {
        var score = 0
        if (candidate.startsWith("192.168.") || candidate.startsWith("10.") || candidate.startsWith("172.")) {
            score += 10
        }
        if (remoteIp != null && same24Subnet(candidate, remoteIp)) {
            score += 100
        }
        return score
    }

    private fun same24Subnet(left: String, right: String): Boolean {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        if (leftParts.size != 4 || rightParts.size != 4) return false
        return leftParts[0] == rightParts[0] &&
            leftParts[1] == rightParts[1] &&
            leftParts[2] == rightParts[2]
    }
}
