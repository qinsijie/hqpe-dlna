package com.github.qinsijie.hqpedlna

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class LocalHttpServer(private val port: Int = 8088) {

    private val tag = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    private class ClientSubscriber(
        val socket: Socket,
        val outputStream: OutputStream,
        val isChunked: Boolean
    )

    private val subscribers = CopyOnWriteArrayList<ClientSubscriber>()
    private val crlf = "\r\n".toByteArray(Charsets.US_ASCII)

    @Synchronized
    fun start() {
        if (isRunning.get()) return

        try {
            val server = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            server.reuseAddress = true
            serverSocket = server
            isRunning.set(true)

            acceptThread = Thread({
                Log.i(tag, "HTTP Server started on 0.0.0.0:$port")
                while (isRunning.get() && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        Thread({ handleClient(clientSocket) }, "Http-Client-${clientSocket.port}").start()
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(tag, "Error accepting client connection", e)
                        }
                    }
                }
            }, "HttpServer-Accept").apply { start() }

        } catch (e: Exception) {
            Log.e(tag, "Failed to start HTTP server on port $port", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 65536
            try {
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {}

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val rawOut = socket.getOutputStream()
            val out = BufferedOutputStream(rawOut, 16384)

            val requestLine = reader.readLine() ?: run {
                socket.close()
                return
            }

            Log.i(tag, "Incoming request: $requestLine from ${socket.remoteSocketAddress}")

            var hasChunkedHeader = false
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val lower = line.lowercase()
                if (lower.contains("te:") && lower.contains("chunked")) {
                    hasChunkedHeader = true
                }
                line = reader.readLine()
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendNotFound(out)
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val path = parts[1]

            if (path == "/stream.wav" || path.startsWith("/stream.wav?")) {
                if (method == "HEAD") {
                    sendHeadResponse(out)
                    out.flush()
                    socket.close()
                    return
                }

                if (method == "GET") {
                    val isChunked = path.contains("chunked=1") || hasChunkedHeader
                    val wavHeader = createWavHeader(48000, 2, 16)
                    val subscriber = ClientSubscriber(socket, out, isChunked)

                    synchronized(subscriber.outputStream) {
                        if (isChunked) {
                            sendChunkedHeaders(out)
                            sendChunk(out, wavHeader, 0, wavHeader.size)
                        } else {
                            sendRawContinuousHeaders(out)
                            out.write(wavHeader)
                        }
                        out.flush()
                    }

                    subscribers.add(subscriber)
                    Log.i(tag, "Client subscribed to audio stream (chunked=$isChunked): ${socket.remoteSocketAddress}. Total clients: ${subscribers.size}")

                    // Keep socket open until disconnected
                    val input = socket.getInputStream()
                    val dummy = ByteArray(128)
                    while (isRunning.get() && !socket.isClosed) {
                        val read = input.read(dummy)
                        if (read == -1) {
                            break
                        }
                    }

                    subscribers.remove(subscriber)
                    try { socket.close() } catch (_: Exception) {}
                    Log.i(tag, "Client disconnected: ${socket.remoteSocketAddress}. Remaining clients: ${subscribers.size}")
                    return
                }
            }

            // Unknown endpoint
            sendNotFound(out)
            out.flush()
            socket.close()

        } catch (e: Exception) {
            Log.d(tag, "Client handling finished or aborted: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendRawContinuousHeaders(out: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/x-wav\r\n" +
                "Accept-Ranges: none\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "Server: HQPE-DLNA/1.0\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendChunkedHeaders(out: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/x-wav\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Accept-Ranges: none\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "Server: HQPE-DLNA/1.0\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendHeadResponse(out: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/x-wav\r\n" +
                "Accept-Ranges: none\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "Server: HQPE-DLNA/1.0\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendNotFound(out: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendChunk(out: OutputStream, data: ByteArray, offset: Int, length: Int) {
        val hexSize = (Integer.toHexString(length) + "\r\n").toByteArray(Charsets.US_ASCII)
        out.write(hexSize)
        out.write(data, offset, length)
        out.write(crlf)
        out.flush()
    }

    fun writeAudioData(buffer: ByteArray, offset: Int, length: Int) {
        if (!isRunning.get() || subscribers.isEmpty() || length <= 0) return

        for (subscriber in subscribers) {
            try {
                synchronized(subscriber.outputStream) {
                    if (subscriber.isChunked) {
                        sendChunk(subscriber.outputStream, buffer, offset, length)
                    } else {
                        subscriber.outputStream.write(buffer, offset, length)
                        subscriber.outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Failed to write audio data to ${subscriber.socket.remoteSocketAddress}, closing socket: ${e.message}")
                subscribers.remove(subscriber)
                try { subscriber.socket.close() } catch (_: Exception) {}
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket", e)
        }
        serverSocket = null

        for (subscriber in subscribers) {
            try {
                synchronized(subscriber.outputStream) {
                    if (subscriber.isChunked) {
                        subscriber.outputStream.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    }
                    subscriber.outputStream.flush()
                }
            } catch (_: Exception) {}
            try {
                subscriber.socket.close()
            } catch (_: Exception) {}
        }
        subscribers.clear()

        acceptThread?.interrupt()
        acceptThread = null
        Log.i(tag, "HTTP Server stopped")
    }

    companion object {
        fun createWavHeader(sampleRate: Int = 48000, channels: Int = 2, bitsPerSample: Int = 16): ByteArray {
            val byteRate = sampleRate * channels * (bitsPerSample / 8) // 192000
            val blockAlign = channels * (bitsPerSample / 8) // 4
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // 0-3: "RIFF" (ASCII Big-Endian)
            bb.put('R'.code.toByte())
            bb.put('I'.code.toByte())
            bb.put('F'.code.toByte())
            bb.put('F'.code.toByte())

            // 4-7: ChunkSize = 0x7FFFFFF7 (approx 2GB, in Little-Endian. Do NOT use 0 or PotPlayer aborts)
            bb.putInt(0x7FFFFFF7)

            // 8-11: "WAVE" (ASCII Big-Endian)
            bb.put('W'.code.toByte())
            bb.put('A'.code.toByte())
            bb.put('V'.code.toByte())
            bb.put('E'.code.toByte())

            // 12-15: "fmt " (ASCII Big-Endian)
            bb.put('f'.code.toByte())
            bb.put('m'.code.toByte())
            bb.put('t'.code.toByte())
            bb.put(' '.code.toByte())

            // 16-19: Subchunk1Size = 16 (for linear PCM, Little-Endian int)
            bb.putInt(16)

            // 20-21: AudioFormat = 1 (Linear PCM uncompressed, Little-Endian short)
            bb.putShort(1.toShort())

            // 22-23: NumChannels = 2 (Stereo, Little-Endian short)
            bb.putShort(channels.toShort())

            // 24-27: SampleRate = 48000 (Little-Endian int)
            bb.putInt(sampleRate)

            // 28-31: ByteRate = 192000 (Little-Endian int)
            bb.putInt(byteRate)

            // 32-33: BlockAlign = 4 (Little-Endian short)
            bb.putShort(blockAlign.toShort())

            // 34-35: BitsPerSample = 16 (Little-Endian short)
            bb.putShort(bitsPerSample.toShort())

            // 36-39: "data" (ASCII Big-Endian)
            bb.put('d'.code.toByte())
            bb.put('a'.code.toByte())
            bb.put('t'.code.toByte())
            bb.put('a'.code.toByte())

            // 40-43: Subchunk2Size = 0x7FFFFFF7 (approx 2GB data length, Little-Endian int)
            bb.putInt(0x7FFFFFF7)

            return header
        }
    }
}
