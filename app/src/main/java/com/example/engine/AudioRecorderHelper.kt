package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorderHelper {

    @Volatile
    private var isRecording = false

    @SuppressLint("MissingPermission")
    suspend fun recordWavUntilStopped(maxDurationMs: Long = 20_000L): ByteArray =
        withContext(Dispatchers.IO) {
            val sampleRate = 16_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBuf * 2
            )

            val pcmOut = ByteArrayOutputStream()
            val buffer = ByteArray(minBuf)
            isRecording = true
            val startTime = System.currentTimeMillis()

            try {
                recorder.startRecording()
                while (isActive && isRecording && (System.currentTimeMillis() - startTime) < maxDurationMs) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pcmOut.write(buffer, 0, read)
                    }
                }
            } finally {
                isRecording = false
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Exception) {}
                recorder.release()
            }

            wrapPcmAsWav(pcmOut.toByteArray(), sampleRate, 1, 16)
        }

    fun stop() {
        isRecording = false
    }

    private fun wrapPcmAsWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcmData.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmData.size)
        }.array()
        return header + pcmData
    }
}
