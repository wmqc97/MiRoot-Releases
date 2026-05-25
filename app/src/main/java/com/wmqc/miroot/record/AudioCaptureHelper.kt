package com.wmqc.miroot.record

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 系统内录参考 [FluxRecorder AudioRecorder](https://github.com/Icradle-Innovations-Ltd/FluxRecorder)：
 * - [AudioPlaybackCaptureConfiguration]：`USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`
 * - **立体声** `CHANNEL_IN_STEREO`、44100Hz、16-bit PCM
 * - `bufferSize = getMinBufferSize(...) * 2`（与 Flux 一致）
 *
 * 若立体声建链失败则回退单声道（部分 ROM 兼容性）。
 * 合成仍走 [com.wmqc.miroot.shell.ShellMedia3Export]（Media3），[pcmChannelCount] 供合并时使用。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureHelper(
    private val mediaProjection: MediaProjection,
) {
    private data class CaptureProfile(
        val sampleRate: Int,
        val channelMask: Int,
        val channelCount: Int,
    )

    private val running = AtomicBoolean(false)
    private val bytesCaptured = AtomicLong(0)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    var pcmSampleRate: Int = SAMPLE_RATE
        private set

    @Volatile
    var pcmChannelCount: Int = 2
        private set

    private fun minValidBytesForChannels(ch: Int): Long =
        pcmSampleRate * 2L * ch.coerceAtLeast(1) // 约 1s 16-bit

    @SuppressLint("MissingPermission")
    fun start(path: String): Boolean {
        if (running.get()) return false
        bytesCaptured.set(0)
        // 优先 44.1kHz Mono（与旧版一致，兼容酷我等车载音乐 App），再尝试高配回退。
        val profileOrder = listOf(
            CaptureProfile(sampleRate = SAMPLE_RATE, channelMask = AudioFormat.CHANNEL_IN_MONO, channelCount = 1),
            CaptureProfile(sampleRate = SAMPLE_RATE, channelMask = AudioFormat.CHANNEL_IN_STEREO, channelCount = 2),
            CaptureProfile(sampleRate = 48_000, channelMask = AudioFormat.CHANNEL_IN_MONO, channelCount = 1),
            CaptureProfile(sampleRate = 48_000, channelMask = AudioFormat.CHANNEL_IN_STEREO, channelCount = 2),
        )
        for (profile in profileOrder) {
            val record = buildPlaybackRecord(profile) ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }
            pcmSampleRate = profile.sampleRate
            pcmChannelCount = profile.channelCount
            audioRecord = record
            running.set(true)
            captureThread = thread(name = "MiRoot-AudioCap") {
                captureLoop(path)
            }
            RecordSynthDebugLog.diagI(
                "audio playback_capture path=$path ${pcmSampleRate}Hz ch=$pcmChannelCount",
            )
            return true
        }
        RecordSynthDebugLog.diagW("audio: all playback_capture profiles failed")
        return false
    }

    /** 由 [start] 调用；内录权限由录屏/投屏流程在更上层保证。 */
    @SuppressLint("MissingPermission")
    private fun buildPlaybackRecord(profile: CaptureProfile): AudioRecord? {
        return try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            }
            val config = configBuilder
                .build()

            var minBuf = AudioRecord.getMinBufferSize(
                profile.sampleRate,
                profile.channelMask,
                AUDIO_FORMAT,
            )
            if (minBuf <= 0) {
                minBuf = profile.sampleRate * 2 * profile.channelCount
            }
            // FluxRecorder：bufferSize = getMinBufferSize * 2
            val bufferSize = minBuf * 2

            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(profile.sampleRate)
                        .setChannelMask(profile.channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            RecordSynthDebugLog.d(
                "playback_capture build failed ${profile.sampleRate}Hz ch=${profile.channelCount}: ${e.message}",
            )
            null
        }
    }

    private fun captureLoop(path: String) {
        var total = 0L
        val ch = pcmChannelCount
        try {
            FileOutputStream(path).use { fos ->
                val buf = ByteArray(8192)
                audioRecord?.startRecording()
                while (running.get() &&
                    audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    val n = audioRecord?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: -1
                    if (n > 0) {
                        fos.write(buf, 0, n)
                        total += n
                        bytesCaptured.set(total)
                    }
                }
                audioRecord?.let { r ->
                    try {
                        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            r.stop()
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        while (true) {
                            val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            total += n
                            bytesCaptured.set(total)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            val minOk = minValidBytesForChannels(ch)
            if (total < minOk) {
                RecordSynthDebugLog.diagW("audio capture short total=$total (min=$minOk ch=$ch)")
            }
            releaseRecord()
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        captureThread?.join(1200)
        captureThread = null
    }

    private fun releaseRecord() {
        try {
            audioRecord?.let { r ->
                try {
                    if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        r.stop()
                    }
                } catch (_: Exception) {
                }
                r.release()
            }
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /**
         * 合并前判定「有足够内录」：按立体声 1s 计（与 Flux 默认内录声道一致）。
         * 实际会话为单声道时，同等时长字节数更少，[MIN_MERGE_ATTEMPT_BYTES] 仍允许短段尝试合并。
         */
        val MIN_VALID_BYTES: Long = SAMPLE_RATE * 2L * 2L

        const val MIN_MERGE_ATTEMPT_BYTES: Long = 4096L
    }
}
