package com.example.secondscreenandroid.connection

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

private const val TAG = "StreamSocket"

class StreamSocket(private val host: String, private val port: Int = 7070) {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    /**
     * Connects and delivers Annex-B H.264 frame data via [onFrame].
     * Automatically retries connection if severed while coroutine is active.
     */
    suspend fun connect(
        onFrame: (ByteArray) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                Log.i(TAG, "Connecting to $host:$port...")
                val s = Socket()
                s.tcpNoDelay = true
                s.receiveBufferSize = 1048576 // 1MB buffer
                s.connect(InetSocketAddress(host, port), 4000)

                socket = s
                outputStream = s.getOutputStream()
                val input: InputStream = java.io.BufferedInputStream(s.getInputStream(), 262144)

                Log.i(TAG, "Connected successfully to $host:$port")
                withContext(Dispatchers.Main) { onConnected() }

                val lenBuf = ByteArray(4)
                var frameCount = 0

                while (isActive && !s.isClosed) {
                    var read = 0
                    while (read < 4) {
                        val r = input.read(lenBuf, read, 4 - read)
                        if (r < 0) throw java.io.EOFException("Socket input stream closed by Mac host")
                        read += r
                    }
                    val length = ByteBuffer.wrap(lenBuf).int
                    if (length <= 0 || length > 10_000_000) {
                        Log.w(TAG, "Ignored invalid frame size: $length")
                        continue
                    }

                    val payload = ByteArray(length)
                    var payloadRead = 0
                    while (payloadRead < length) {
                        val r = input.read(payload, payloadRead, length - payloadRead)
                        if (r < 0) throw java.io.EOFException("Socket input closed while reading payload")
                        payloadRead += r
                    }

                    frameCount++
                    if (frameCount <= 5 || frameCount % 60 == 0) {
                        Log.d(TAG, "Received frame #$frameCount (${payload.size} bytes)")
                    }
                    onFrame(payload)
                }
            } catch (e: Exception) {
                if (!isActive) break
                val msg = e.localizedMessage ?: "Connection error"
                Log.w(TAG, "Connection failed ($msg). Retrying in 2 seconds...")
                withContext(Dispatchers.Main) { onDisconnected(msg) }
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
                outputStream = null
                delay(2000)
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
                outputStream = null
            }
        }
    }

    /**
     * Sends a touch event to the Mac.
     * type: 0x01 down, 0x02 move, 0x03 up
     * x, y: normalized 0.0–1.0
     */
    fun sendTouchEvent(type: Byte, x: Float, y: Float) {
        val buf = ByteBuffer.allocate(9)
        buf.put(type)
        buf.putFloat(x)
        buf.putFloat(y)
        val bytes = buf.array()
        try {
            outputStream?.write(bytes)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send touch event: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        outputStream = null
    }
}
