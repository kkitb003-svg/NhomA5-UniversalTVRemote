package com.ued.universaltvremote.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLanUtil {

    /**
     * Sends a Wake-on-LAN magic packet to the given MAC address.
     * The packet is sent to the broadcast address on port 9.
     * @param macAddress in format "AA:BB:CC:DD:EE:FF" or "AA-BB-CC-DD-EE-FF"
     * @param broadcastAddress defaults to 255.255.255.255
     */
    suspend fun sendMagicPacket(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanMac = macAddress.replace(":", "").replace("-", "")
            require(cleanMac.length == 12) { "Invalid MAC address: $macAddress" }

            val macBytes = ByteArray(6) { i ->
                cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            // Magic packet: 6 bytes of 0xFF followed by 16 repetitions of the MAC address
            val packet = ByteArray(102)
            for (i in 0..5) packet[i] = 0xFF.toByte()
            for (rep in 0..15) {
                for (b in 0..5) packet[6 + rep * 6 + b] = macBytes[b]
            }

            val address = InetAddress.getByName(broadcastAddress)
            val datagram = DatagramPacket(packet, packet.size, address, port)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(datagram)
            }
        }
    }
}
