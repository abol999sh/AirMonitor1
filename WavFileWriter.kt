package com.example.airmonitor

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes raw 16-bit PCM mono audio to disk as a streaming WAV file.
 * A 44-byte placeholder header is written first; the real sizes are
 * patched in once recording stops (close()).
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataLength = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // placeholder header
    }

    @Synchronized
    fun write(buffer: ByteArray, length: Int) {
        raf.seek(raf.length())
        raf.write(buffer, 0, length)
        dataLength += length
    }

    fun close() {
        finalizeHeader()
        raf.close()
    }

    private fun finalizeHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = dataLength + 36

        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.write(intLE(totalDataLen.toInt()))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(intLE(16))
        raf.write(shortLE(1)) // PCM format
        raf.write(shortLE(channels))
        raf.write(intLE(sampleRate))
        raf.write(intLE(byteRate))
        raf.write(shortLE(blockAlign))
        raf.write(shortLE(bitsPerSample))
        raf.writeBytes("data")
        raf.write(intLE(dataLength.toInt()))
    }

    private fun intLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte()
    )
}
