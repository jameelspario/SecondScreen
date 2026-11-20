package com.example.secondscreenandroid.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

private const val TAG = "VideoDecoder"
private const val MIME_TYPE = "video/avc"
private const val DEQUEUE_TIMEOUT_US = 10_000L

class VideoDecoder {

    private var codec: MediaCodec? = null
    @Volatile private var isRunning = false
    private var renderThread: Thread? = null
    private var surface: Surface? = null

    @Volatile private var hasReceivedKeyFrame = false

    var onFirstFrameRendered: (() -> Unit)? = null

    fun init(surface: Surface, width: Int = 1920, height: Int = 1080) {
        release()
        this.surface = surface
        this.hasReceivedKeyFrame = false

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                // 4MB maximum input size to safely support high-res 1080p/4K IDR intra-frames
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val c = MediaCodec.createDecoderByType(MIME_TYPE)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            isRunning = true

            renderThread = thread(name = "VideoDecoder-Render") {
                val bufferInfo = MediaCodec.BufferInfo()
                var frameCount = 0
                while (isRunning) {
                    val activeCodec = codec ?: break
                    try {
                        val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                        when {
                            outputIndex >= 0 -> {
                                activeCodec.releaseOutputBuffer(outputIndex, true)
                                frameCount++
                                if (frameCount == 1) {
                                    Log.i(TAG, "First video frame rendered to Surface!")
                                    onFirstFrameRendered?.invoke()
                                }
                                if (frameCount % 60 == 0) {
                                    Log.d(TAG, "Rendered $frameCount frames to Surface")
                                }
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.i(TAG, "MediaCodec output format changed: ${activeCodec.outputFormat}")
                            }
                            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                // Waiting for hardware decode
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(TAG, "Error dequeuing output buffer: ${e.message}")
                        // Reset codec if it entered unrecoverable error state
                        if (e is IllegalStateException) {
                            reinitCodec()
                            break
                        }
                    }
                }
                Log.d(TAG, "Render thread terminated")
            }

            Log.i(TAG, "VideoDecoder initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VideoDecoder", e)
            release()
        }
    }

    /**
     * Checks if the Annex-B packet contains an SPS (NAL unit type 7) or IDR (NAL unit type 5).
     */
    private fun containsSpsOrIdr(data: ByteArray): Boolean {
        var i = 0
        val len = data.size - 4
        while (i < len) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    val nalType = (data[i + 3].toInt() and 0x1F)
                    if (nalType == 7 || nalType == 5) return true
                    i += 3
                    continue
                } else if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() && i + 4 < data.size) {
                    val nalType = (data[i + 4].toInt() and 0x1F)
                    if (nalType == 7 || nalType == 5) return true
                    i += 4
                    continue
                }
            }
            i++
        }
        return false
    }

    private fun reinitCodec() {
        val s = surface ?: return
        Log.w(TAG, "Re-initializing MediaCodec after error...")
        init(s)
    }

    /**
     * Feeds Annex-B NAL units to MediaCodec.
     * Drops any non-keyframe packets until the first SPS/IDR arrives to prevent hardware decoder crash.
     */
    fun decode(data: ByteArray) {
        val c = codec ?: return
        if (!isRunning) return

        if (!hasReceivedKeyFrame) {
            if (containsSpsOrIdr(data)) {
                Log.i(TAG, "Found initial SPS/IDR keyframe (${data.size} bytes). Decoder unlocked!")
                hasReceivedKeyFrame = true
            } else {
                Log.d(TAG, "Skipping pre-keyframe slice (${data.size} bytes) waiting for SPS header")
                return
            }
        }

        try {
            val inputIndex = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) {
                Log.w(TAG, "Input buffer full/dropped frame (size ${data.size})")
                return
            }

            val inputBuffer = c.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()

            if (data.size > inputBuffer.capacity()) {
                Log.e(TAG, "Frame size ${data.size} exceeds buffer capacity ${inputBuffer.capacity()}! Dropping.")
                return
            }

            inputBuffer.put(data)
            c.queueInputBuffer(inputIndex, 0, data.size, 0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame: ${e.message}")
            if (e is IllegalStateException) {
                reinitCodec()
            }
        }
    }

    fun release() {
        isRunning = false
        hasReceivedKeyFrame = false
        renderThread?.interrupt()
        renderThread = null

        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }
}
