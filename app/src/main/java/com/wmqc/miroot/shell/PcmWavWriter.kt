package com.wmqc.miroot.shell

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 将 16-bit little-endian PCM 写成 WAV，供 Media3 作为 [androidx.media3.common.MediaItem] 读取。
 */
object PcmWavWriter {

    const val SAMPLE_RATE = 44100
    const val CHANNELS = 1
    const val BITS = 16

    fun pcmToWav(pcmFile: File, wavOut: File): Boolean =
        pcmToWav(pcmFile, wavOut, SAMPLE_RATE, CHANNELS)

    /**
     * @param sampleRate 如 44100 / 48000
     * @param channels 1 或 2（与 PCM 交错格式一致）
     */
    fun pcmToWav(pcmFile: File, wavOut: File, sampleRate: Int, channels: Int): Boolean {
        if (channels != 1 && channels != 2) return false
        if (!pcmFile.isFile || pcmFile.length() <= 0L) return false
        val dataSize = pcmFile.length().toInt()
        if (dataSize <= 0) return false
        val byteRate = sampleRate * channels * BITS / 8
        val headerSize = 44
        wavOut.parentFile?.mkdirs()
        FileOutputStream(wavOut).use { fos ->
            val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * BITS / 8).toShort())
            header.putShort(BITS.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())
            FileInputStream(pcmFile).use { it.copyTo(fos) }
        }
        return wavOut.isFile && wavOut.length() > headerSize.toLong()
    }
}
