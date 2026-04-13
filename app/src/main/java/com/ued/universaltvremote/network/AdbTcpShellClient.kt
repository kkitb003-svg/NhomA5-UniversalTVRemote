package com.ued.universaltvremote.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal class AdbTcpShellClient {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val CONNECT_TIMEOUT_MS = 1800
        private val DEFAULT_PORTS = listOf(5555)
        private val HOST_BANNER = "host::UniversalTvRemote"
    }

    suspend fun probe(ip: String, ports: List<Int> = DEFAULT_PORTS): ProbeResult = withContext(Dispatchers.IO) {
        ports.distinct().forEach { port ->
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = CONNECT_TIMEOUT_MS
                    val handshake = handshake(socket.inputStream, socket.outputStream)
                    when (handshake) {
                        HandshakeResult.Connected -> ProbeResult(port = port, authRequired = false)
                        HandshakeResult.AuthRequired -> ProbeResult(port = port, authRequired = true)
                    }
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }
        ProbeResult(port = null, authRequired = false)
    }

    suspend fun execute(ip: String, port: Int, command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS

                when (handshake(socket.inputStream, socket.outputStream)) {
                    HandshakeResult.Connected -> executeShellCommand(
                        input = socket.inputStream,
                        output = socket.outputStream,
                        command = command
                    )

                    HandshakeResult.AuthRequired -> throw AdbAuthRequiredException()
                }
            }
        }
    }

    private fun handshake(input: InputStream, output: OutputStream): HandshakeResult {
        writePacket(
            output = output,
            command = A_CNXN,
            arg0 = VERSION,
            arg1 = MAX_DATA,
            payload = HOST_BANNER.toByteArray()
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_CNXN -> return HandshakeResult.Connected
                A_AUTH -> {
                    if (packet.arg0 == AUTH_TOKEN) {
                        return HandshakeResult.AuthRequired
                    }
                }
            }
        }
    }

    private fun executeShellCommand(input: InputStream, output: OutputStream, command: String): String {
        val localId = 1
        var remoteId = 0
        val payload = "shell:$command".toByteArray()
        val stdout = ByteArrayOutputStream()

        writePacket(
            output = output,
            command = A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = payload
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_OKAY -> {
                    if (packet.arg1 == localId) {
                        remoteId = packet.arg0
                    }
                }

                A_WRTE -> {
                    if (packet.arg1 == localId) {
                        if (remoteId == 0 && packet.arg0 != 0) {
                            remoteId = packet.arg0
                        }
                        stdout.write(packet.payload)
                        if (remoteId != 0) {
                            writePacket(
                                output = output,
                                command = A_OKAY,
                                arg0 = localId,
                                arg1 = remoteId,
                                payload = ByteArray(0)
                            )
                        }
                    }
                }

                A_CLSE -> {
                    if (packet.arg1 == localId || packet.arg0 == remoteId) {
                        return stdout.toString(Charsets.UTF_8.name()).trim()
                    }
                }
            }
        }
    }

    private fun writePacket(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(crc32(payload))
            .putInt(command xor -0x1)
            .array()

        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readPacket(input: InputStream): AdbPacket {
        val header = ByteArray(24)
        readFully(input, header)

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = headerBuffer.int
        val arg0 = headerBuffer.int
        val arg1 = headerBuffer.int
        val dataLength = headerBuffer.int
        val dataCrc = headerBuffer.int
        val magic = headerBuffer.int

        if (magic != (command xor -0x1)) {
            throw EOFException("ADB packet magic mismatch")
        }

        val payload = ByteArray(dataLength)
        if (dataLength > 0) {
            readFully(input, payload)
            if (crc32(payload) != dataCrc) {
                throw EOFException("ADB payload checksum mismatch")
            }
        }

        return AdbPacket(
            command = command,
            arg0 = arg0,
            arg1 = arg1,
            payload = payload
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                throw EOFException("Unexpected end of stream")
            }
            offset += read
        }
    }

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    internal data class ProbeResult(
        val port: Int?,
        val authRequired: Boolean
    )

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private enum class HandshakeResult {
        Connected,
        AuthRequired
    }
}

internal class AdbAuthRequiredException : IllegalStateException("ADB authorization required")

package com.ued.universaltvremote.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal class AdbTcpShellClient {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val CONNECT_TIMEOUT_MS = 1800
        private val DEFAULT_PORTS = listOf(5555)
        private val HOST_BANNER = "host::UniversalTvRemote"

                        HandshakeResult.Connected -> ProbeResult(port = port, authRequired = false)
                        HandshakeResult.AuthRequired -> ProbeResult(port = port, authRequired = true)
                    }
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }
        ProbeResult(port = null, authRequired = false)
    }

    suspend fun execute(ip: String, port: Int, command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS

                when (handshake(socket.inputStream, socket.outputStream)) {
                    HandshakeResult.Connected -> executeShellCommand(
                        input = socket.inputStream,
                        output = socket.outputStream,
                        command = command
                    )

                    HandshakeResult.AuthRequired -> throw AdbAuthRequiredException()
                }
            }
        }
    }


            payload = HOST_BANNER.toByteArray()
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_CNXN -> return HandshakeResult.Connected
                A_AUTH -> {
                    if (packet.arg0 == AUTH_TOKEN) {
                        return HandshakeResult.AuthRequired
                    }
                }
            }
        }
    }

    private fun executeShellCommand(input: InputStream, output: OutputStream, command: String): String {
        val localId = 1
        var remoteId = 0
        val payload = "shell:$command".toByteArray()
        val stdout = ByteArrayOutputStream()

        writePacket(
            output = output,
            command = A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = payload
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_OKAY -> {
                    if (packet.arg1 == localId) {
                        remoteId = packet.arg0
                    }
                }

                A_WRTE -> {
                    if (packet.arg1 == localId) {
                        if (remoteId == 0 && packet.arg0 != 0) {
                            remoteId = packet.arg0
                        }
                        stdout.write(packet.payload)
                        if (remoteId != 0) {
                            writePacket(
                                output = output,
                                command = A_OKAY,
                                arg0 = localId,
                                arg1 = remoteId,
                                payload = ByteArray(0)
                            )
                        }
                    }
                }

                A_CLSE -> {
                    if (packet.arg1 == localId || packet.arg0 == remoteId) {
                        return stdout.toString(Charsets.UTF_8.name()).trim()
                    }
                }
            }
        }
    }

    private fun writePacket(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(crc32(payload))
            .putInt(command xor -0x1)
            .array()

        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readPacket(input: InputStream): AdbPacket {
        val header = ByteArray(24)
        readFully(input, header)

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = headerBuffer.int
        val arg0 = headerBuffer.int
        val arg1 = headerBuffer.int
        val dataLength = headerBuffer.int
        val dataCrc = headerBuffer.int
        val magic = headerBuffer.int

        if (magic != (command xor -0x1)) {
            throw EOFException("ADB packet magic mismatch")
        }

        val payload = ByteArray(dataLength)
        if (dataLength > 0) {
            readFully(input, payload)
            if (crc32(payload) != dataCrc) {
                throw EOFException("ADB payload checksum mismatch")
            }
        }

        return AdbPacket(
            command = command,
            arg0 = arg0,
            arg1 = arg1,
            payload = payload
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                throw EOFException("Unexpected end of stream")
            }
            offset += read
        }
    }

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    internal data class ProbeResult(
        val port: Int?,
        val authRequired: Boolean
    )

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private enum class HandshakeResult {
        Connected,
        AuthRequired
    }
}

internal class AdbAuthRequiredException : IllegalStateException("ADB authorization required")

package com.ued.universaltvremote.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal class AdbTcpShellClient {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val CONNECT_TIMEOUT_MS = 1800
        private val DEFAULT_PORTS = listOf(5555)
        private val HOST_BANNER = "host::UniversalTvRemote"

                        HandshakeResult.Connected -> ProbeResult(port = port, authRequired = false)
                        HandshakeResult.AuthRequired -> ProbeResult(port = port, authRequired = true)
                    }
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }
        ProbeResult(port = null, authRequired = false)
    }

    suspend fun execute(ip: String, port: Int, command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS

                when (handshake(socket.inputStream, socket.outputStream)) {
                    HandshakeResult.Connected -> executeShellCommand(
                        input = socket.inputStream,
                        output = socket.outputStream,
                        command = command
                    )

                    HandshakeResult.AuthRequired -> throw AdbAuthRequiredException()
                }
            }
        }
    }


            payload = HOST_BANNER.toByteArray()
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_CNXN -> return HandshakeResult.Connected
                A_AUTH -> {
                    if (packet.arg0 == AUTH_TOKEN) {
                        return HandshakeResult.AuthRequired
                    }
                }
            }
        }
    }

    private fun executeShellCommand(input: InputStream, output: OutputStream, command: String): String {
        val localId = 1
        var remoteId = 0
        val payload = "shell:$command".toByteArray()
        val stdout = ByteArrayOutputStream()

        writePacket(
            output = output,
            command = A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = payload
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_OKAY -> {
                    if (packet.arg1 == localId) {
                        remoteId = packet.arg0
                    }
                }

                A_WRTE -> {
                    if (packet.arg1 == localId) {
                        if (remoteId == 0 && packet.arg0 != 0) {
                            remoteId = packet.arg0
                        }
                        stdout.write(packet.payload)
                        if (remoteId != 0) {
                            writePacket(
                                output = output,
                                command = A_OKAY,
                                arg0 = localId,
                                arg1 = remoteId,
                                payload = ByteArray(0)
                            )
                        }
                    }
                }

                A_CLSE -> {
                    if (packet.arg1 == localId || packet.arg0 == remoteId) {
                        return stdout.toString(Charsets.UTF_8.name()).trim()
                    }
                }
            }
        }
    }

    private fun writePacket(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(crc32(payload))
            .putInt(command xor -0x1)
            .array()

        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readPacket(input: InputStream): AdbPacket {
        val header = ByteArray(24)
        readFully(input, header)

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = headerBuffer.int
        val arg0 = headerBuffer.int
        val arg1 = headerBuffer.int
        val dataLength = headerBuffer.int
        val dataCrc = headerBuffer.int
        val magic = headerBuffer.int

        if (magic != (command xor -0x1)) {
            throw EOFException("ADB packet magic mismatch")
        }

        val payload = ByteArray(dataLength)
        if (dataLength > 0) {
            readFully(input, payload)
            if (crc32(payload) != dataCrc) {
                throw EOFException("ADB payload checksum mismatch")
            }
        }

        return AdbPacket(
            command = command,
            arg0 = arg0,
            arg1 = arg1,
            payload = payload
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                throw EOFException("Unexpected end of stream")
            }
            offset += read
        }
    }

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    internal data class ProbeResult(
        val port: Int?,
        val authRequired: Boolean
    )

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private enum class HandshakeResult {
        Connected,
        AuthRequired
    }
}

internal class AdbAuthRequiredException : IllegalStateException("ADB authorization required")

package com.ued.universaltvremote.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal class AdbTcpShellClient {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val CONNECT_TIMEOUT_MS = 1800
        private val DEFAULT_PORTS = listOf(5555)
        private val HOST_BANNER = "host::UniversalTvRemote"

                        HandshakeResult.Connected -> ProbeResult(port = port, authRequired = false)
                        HandshakeResult.AuthRequired -> ProbeResult(port = port, authRequired = true)
                    }
                }
            }.getOrNull()

            if (result != null) {
                return@withContext result
            }
        }
        ProbeResult(port = null, authRequired = false)
    }

    suspend fun execute(ip: String, port: Int, command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS

                when (handshake(socket.inputStream, socket.outputStream)) {
                    HandshakeResult.Connected -> executeShellCommand(
                        input = socket.inputStream,
                        output = socket.outputStream,
                        command = command
                    )

                    HandshakeResult.AuthRequired -> throw AdbAuthRequiredException()
                }
            }
        }
    }


            payload = HOST_BANNER.toByteArray()
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_CNXN -> return HandshakeResult.Connected
                A_AUTH -> {
                    if (packet.arg0 == AUTH_TOKEN) {
                        return HandshakeResult.AuthRequired
                    }
                }
            }
        }
    }

    private fun executeShellCommand(input: InputStream, output: OutputStream, command: String): String {
        val localId = 1
        var remoteId = 0
        val payload = "shell:$command".toByteArray()
        val stdout = ByteArrayOutputStream()

        writePacket(
            output = output,
            command = A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = payload
        )

        while (true) {
            val packet = readPacket(input)
            when (packet.command) {
                A_OKAY -> {
                    if (packet.arg1 == localId) {
                        remoteId = packet.arg0
                    }
                }

                A_WRTE -> {
                    if (packet.arg1 == localId) {
                        if (remoteId == 0 && packet.arg0 != 0) {
                            remoteId = packet.arg0
                        }
                        stdout.write(packet.payload)
                        if (remoteId != 0) {
                            writePacket(
                                output = output,
                                command = A_OKAY,
                                arg0 = localId,
                                arg1 = remoteId,
                                payload = ByteArray(0)
                            )
                        }
                    }
                }

                A_CLSE -> {
                    if (packet.arg1 == localId || packet.arg0 == remoteId) {
                        return stdout.toString(Charsets.UTF_8.name()).trim()
                    }
                }
            }
        }
    }

    private fun writePacket(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray
    ) {
        val header = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(crc32(payload))
            .putInt(command xor -0x1)
            .array()

        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readPacket(input: InputStream): AdbPacket {
        val header = ByteArray(24)
        readFully(input, header)

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = headerBuffer.int
        val arg0 = headerBuffer.int
        val arg1 = headerBuffer.int
        val dataLength = headerBuffer.int
        val dataCrc = headerBuffer.int
        val magic = headerBuffer.int

        if (magic != (command xor -0x1)) {
            throw EOFException("ADB packet magic mismatch")
        }

        val payload = ByteArray(dataLength)
        if (dataLength > 0) {
            readFully(input, payload)
            if (crc32(payload) != dataCrc) {
                throw EOFException("ADB payload checksum mismatch")
            }
        }

        return AdbPacket(
            command = command,
            arg0 = arg0,
            arg1 = arg1,
            payload = payload
        )
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                throw EOFException("Unexpected end of stream")
            }
            offset += read
        }
    }

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    internal data class ProbeResult(
        val port: Int?,
        val authRequired: Boolean
    )

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private enum class HandshakeResult {
        Connected,
        AuthRequired
    }
}

internal class AdbAuthRequiredException : IllegalStateException("ADB authorization required")
