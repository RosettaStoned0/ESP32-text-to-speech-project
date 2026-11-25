package com.rosettastoned.esp32cam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class Esp32ImageServer(
    private val onImage: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start(port: Int) {
        stop()
        scope.launch {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = socket
                onStatus("listening")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                onStatus("error: ${e.message}")
            }
        }
    }

    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
    }

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    private fun handleClient(client: Socket) {
        scope.launch {
            client.use { sock ->
                sock.soTimeout = 15000
                val input = BufferedInputStream(sock.getInputStream())
                val output = BufferedOutputStream(sock.getOutputStream())

                var contentLength = -1
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                }

                if (contentLength > 0) {
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(body, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read > 0 && isValidJpeg(body)) {
                        onImage(body.copyOf(read))
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                    } else {
                        output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    output.flush()
                }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun isValidJpeg(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
    }
}
