package com.wmqc.miroot.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.wmqc.miroot.record.RecordSynthDebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 使用 AndroidX Media3 [Transformer]：
 * - **带壳录屏**：黑底 + 视频置于 (x,y) + 外框底图 + **贴图**（与截图同一套 [DeviceGeometry] 与 [ShellStickerOverlay]）。
 * - **音视频合并**：PCM → 临时 WAV，与视频轨混合；前置静音由 [REAR_RECORD_AUDIO_LEAD_US] 等参数控制（背屏默认 0）。
 *
 * [Transformer] 须在主线程创建与 [Transformer.start]；本类会将 [start] 派发到主线程。
 */
@UnstableApi
object ShellMedia3Export {

    /**
     * 音轨相对视频的起始偏移（微秒）。旧版 FFmpeg 曾用约 1s；Media3 多序列下过大偏移易导致
     * 对齐/截断异常表现为整段无声，默认改为 0，必要时再调。
     */
    const val DEFAULT_AUDIO_DELAY_US: Long = 0L

    /**
     * 背屏内录合并/带壳合一导出时，音轨相对画面的前置静音（µs）。
     * 内录已在 screenrecord 启动后尽快开始，此处默认 **0**；若仍觉声早/画晚可改为 50_000～200_000 微调。
     */
    const val REAR_RECORD_AUDIO_LEAD_US: Long = 0L

    /**
     * Media3 [EditedMediaItemSequence.Builder.addGap] 要求 gap 时长 **> 0**；为 0 时不得调用 addGap。
     */
    private fun buildAudioSequenceWithOptionalLead(
        wavItem: EditedMediaItem,
        audioLeadDelayUs: Long,
    ): EditedMediaItemSequence {
        val b = EditedMediaItemSequence.Builder()
        if (audioLeadDelayUs > 0L) {
            b.addGap(audioLeadDelayUs)
        }
        return b.addItem(wavItem).build()
    }

    fun interface ExportCallback {
        fun onFinished(success: Boolean, outputOrNull: File?, error: String?)
    }

    private data class ShellOverlayAssets(
        val effects: Effects,
        /** 导出结束后需全部 recycle（含底图与全画布贴图层）。 */
        val bitmapsToRecycle: List<android.graphics.Bitmap>,
    )

    /**
     * 加载带壳缩放/底图叠加效果；失败时回收已解码的 Bitmap。
     */
    private fun loadShellOverlayAssets(app: Context): ShellOverlayAssets? {
        val phonePath = DeviceGeometry.resolvePhoneBackPath(app) ?: return null
        val phoneBmp = BitmapFactory.decodeFile(
            phonePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val canvasW = phoneBmp.width
        val canvasH = phoneBmp.height
        if (canvasW < 2 || canvasH < 2) {
            phoneBmp.recycle()
            return null
        }

        val xy = DeviceGeometry.compositeXY(app)
        val target = DeviceGeometry.targetScaleSize(app)
        val fixedTw = target[0]
        val fixedTh = target[1]

        val placement = ShellVideoPlacement(
            canvasW = canvasW,
            canvasH = canvasH,
            placeX = xy[0],
            placeY = xy[1],
            fixedTargetW = fixedTw,
            fixedTargetH = fixedTh,
        )
        val phoneOverlay = BitmapOverlay.createStaticBitmapOverlay(phoneBmp)
        val shellOverlays = mutableListOf(OverlayEffect(listOf(phoneOverlay)))
        val recycleList = mutableListOf(phoneBmp)
        val stickerLayer = ShellStickerOverlay.createFullCanvasStickerBitmap(app, canvasW, canvasH)
        if (stickerLayer != null) {
            recycleList.add(stickerLayer)
            val stickerOverlay = BitmapOverlay.createStaticBitmapOverlay(stickerLayer)
            shellOverlays.add(OverlayEffect(listOf(stickerOverlay)))
        }
        val effects = Effects(
            emptyList(),
            listOf(placement) + shellOverlays,
        )
        return ShellOverlayAssets(effects, recycleList)
    }

    /**
     * 带壳合成：输入为背屏录屏 MP4，输出为同布局 H.264/AAC MP4（保留原音轨；若无音轨则无音频）。
     */
    fun compositeShellOverVideo(
        context: Context,
        inputVideo: File,
        outputVideo: File,
        callback: ExportCallback,
    ) {
        val app = context.applicationContext
        val assets = loadShellOverlayAssets(app) ?: run {
            RecordSynthDebugLog.w("compositeShellOverVideo: no_phone_back_or_decode_failed")
            callback.onFinished(false, null, "no_phone_back_or_decode_failed")
            return
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(inputVideo))
            .setMimeType(MimeTypes.APPLICATION_MP4)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem).setEffects(assets.effects).build()
        val sequence = EditedMediaItemSequence.Builder(edited).build()
        val composition = Composition.Builder(sequence)
            .experimentalSetForceAudioTrack(true)
            .build()

        outputVideo.parentFile?.mkdirs()
        RecordSynthDebugLog.d(
            "compositeShellOverVideo: in=${inputVideo.absolutePath} len=${inputVideo.length()} out=${outputVideo.absolutePath}",
        )
        runExport(app, composition, outputVideo, assets.bitmapsToRecycle, callback)
    }

    /**
     * 将 PCM 合并进视频（视频轨保留，**丢弃**原音轨），输出 AAC。
     * @param audioLeadDelayUs 音频整体后移（先插入静音），用于减轻「声画不同步」。
     */
    fun mergePcmIntoMp4(
        context: Context,
        videoFile: File,
        pcmFile: File,
        outputVideo: File,
        audioLeadDelayUs: Long = DEFAULT_AUDIO_DELAY_US,
        pcmSampleRate: Int = PcmWavWriter.SAMPLE_RATE,
        pcmChannels: Int = PcmWavWriter.CHANNELS,
        callback: ExportCallback,
    ) {
        val app = context.applicationContext
        val wav = File(app.cacheDir, "miroot_pcm_${System.currentTimeMillis()}.wav")
        if (!PcmWavWriter.pcmToWav(pcmFile, wav, pcmSampleRate, pcmChannels)) {
            RecordSynthDebugLog.w("mergePcmIntoMp4: pcmToWav failed pcm=${pcmFile.length()} sr=$pcmSampleRate ch=$pcmChannels")
            callback.onFinished(false, null, "pcm_to_wav_failed")
            return
        }

        val videoItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(videoFile))
                .setMimeType(MimeTypes.APPLICATION_MP4)
                .build(),
        ).setRemoveAudio(true).build()

        val wavItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(wav))
                .setMimeType(MimeTypes.AUDIO_WAV)
                .build(),
        ).setRemoveVideo(true).build()

        val videoSequence = EditedMediaItemSequence.Builder(videoItem).build()
        val audioSequence = buildAudioSequenceWithOptionalLead(wavItem, audioLeadDelayUs)

        // 合并音轨时不强制 OpenGL HDR 色调映射，避免部分机型 Transformer 失败
        val composition = Composition.Builder(listOf(videoSequence, audioSequence))
            .experimentalSetForceAudioTrack(true)
            .build()

        outputVideo.parentFile?.mkdirs()
        RecordSynthDebugLog.d(
            "mergePcmIntoMp4: video=${videoFile.length()} pcm=${pcmFile.length()} wav=${wav.length()} delayUs=$audioLeadDelayUs sr=$pcmSampleRate ch=$pcmChannels out=${outputVideo.absolutePath}",
        )
        val cleanup = AtomicReference(wav)
        runExport(
            app = app,
            composition = composition,
            outputFile = outputVideo,
            bitmapsToRecycle = emptyList(),
            callback = object : ExportCallback {
                override fun onFinished(success: Boolean, outputOrNull: File?, error: String?) {
                    cleanup.get()?.delete()
                    cleanup.set(null)
                    callback.onFinished(success, outputOrNull, error)
                }
            },
        )
    }

    /**
     * 内录 PCM + 带壳：**单次** Transformer 导出（视频轨带壳 + 独立 WAV 音序）。
     * 避免「先 merge 再 overlay」两次导出时，第二步叠加效果导致音轨丢失。
     */
    fun mergePcmThenCompositeShell(
        context: Context,
        videoFile: File,
        pcmFile: File?,
        finalOutput: File,
        pcmSampleRate: Int = PcmWavWriter.SAMPLE_RATE,
        pcmChannels: Int = PcmWavWriter.CHANNELS,
        audioLeadDelayUs: Long = DEFAULT_AUDIO_DELAY_US,
        callback: ExportCallback,
    ) {
        val app = context.applicationContext
        val minPcm = com.wmqc.miroot.record.AudioCaptureHelper.MIN_MERGE_ATTEMPT_BYTES
        if (pcmFile == null || !pcmFile.isFile || pcmFile.length() < minPcm) {
            RecordSynthDebugLog.d(
                "mergePcmThenCompositeShell: pcm too small or missing (pcm=${pcmFile?.length()}) -> compositeShell only",
            )
            compositeShellOverVideo(app, videoFile, finalOutput, callback)
            return
        }

        val wav = File(app.cacheDir, "miroot_pcm_${System.currentTimeMillis()}.wav")
        if (!PcmWavWriter.pcmToWav(pcmFile, wav, pcmSampleRate, pcmChannels)) {
            RecordSynthDebugLog.w("mergePcmThenCompositeShell: pcmToWav failed")
            callback.onFinished(false, null, "pcm_to_wav_failed")
            return
        }

        val assets = loadShellOverlayAssets(app)
        if (assets == null) {
            wav.delete()
            RecordSynthDebugLog.w("mergePcmThenCompositeShell: loadShellOverlayAssets failed")
            callback.onFinished(false, null, "no_phone_back_or_decode_failed")
            return
        }

        val videoItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(videoFile))
                .setMimeType(MimeTypes.APPLICATION_MP4)
                .build(),
        )
            .setRemoveAudio(true)
            .setEffects(assets.effects)
            .build()

        val wavItem = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(wav))
                .setMimeType(MimeTypes.AUDIO_WAV)
                .build(),
        ).setRemoveVideo(true).build()

        val videoSequence = EditedMediaItemSequence.Builder(videoItem).build()
        val audioSequence = buildAudioSequenceWithOptionalLead(wavItem, audioLeadDelayUs)

        val composition = Composition.Builder(listOf(videoSequence, audioSequence))
            .experimentalSetForceAudioTrack(true)
            .build()

        finalOutput.parentFile?.mkdirs()
        RecordSynthDebugLog.d(
            "mergePcmThenCompositeShell: video=${videoFile.length()} pcm=${pcmFile.length()} wav=${wav.length()} " +
                "delayUs=$audioLeadDelayUs sr=$pcmSampleRate ch=$pcmChannels out=${finalOutput.absolutePath}",
        )
        val cleanup = AtomicReference(wav)
        runExport(
            app = app,
            composition = composition,
            outputFile = finalOutput,
            bitmapsToRecycle = assets.bitmapsToRecycle,
            callback = object : ExportCallback {
                override fun onFinished(success: Boolean, outputOrNull: File?, error: String?) {
                    cleanup.get()?.delete()
                    cleanup.set(null)
                    callback.onFinished(success, outputOrNull, error)
                }
            },
        )
    }

    private fun runExport(
        app: Context,
        composition: Composition,
        outputFile: File,
        bitmapsToRecycle: List<android.graphics.Bitmap>,
        callback: ExportCallback,
    ) {
        val main = Looper.getMainLooper()
        val recycle: () -> Unit = {
            for (b in bitmapsToRecycle) {
                if (!b.isRecycled) b.recycle()
            }
        }

        Handler(main).post {
            RecordSynthDebugLog.d("Transformer.start path=${outputFile.absolutePath}")
            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(10_000_000) // 10 Mbps for canvas-size composite output
                .build()
            val audioEncoderSettings = AudioEncoderSettings.Builder().setBitrate(96000).build()
            val encoderFactory = DefaultEncoderFactory.Builder(app)
                .setRequestedAudioEncoderSettings(audioEncoderSettings)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .build()
            val transformer = Transformer.Builder(app)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .build()

            transformer.addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer.removeListener(this)
                        recycle()
                        RecordSynthDebugLog.d(
                            "Transformer.onCompleted out=${outputFile.absolutePath} len=${outputFile.length()} result=$exportResult",
                        )
                        callback.onFinished(true, outputFile, null)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        transformer.removeListener(this)
                        recycle()
                        RecordSynthDebugLog.e(
                            "Transformer.onError result=$exportResult msg=${exportException.message}",
                            exportException,
                        )
                        callback.onFinished(
                            false,
                            null,
                            exportException.message ?: exportException.javaClass.simpleName,
                        )
                    }
                },
            )

            try {
                transformer.start(composition, outputFile.absolutePath)
            } catch (t: Throwable) {
                RecordSynthDebugLog.e("Transformer.start threw", t)
                recycle()
                callback.onFinished(false, null, t.message)
            }
        }
    }

    /**
     * 将输入视频帧映射到画布上的 [placeX],[placeY] 矩形；ProMax 使用固定 target，Pro 使用首帧输入尺寸。
     */
    private class ShellVideoPlacement(
        private val canvasW: Int,
        private val canvasH: Int,
        private val placeX: Int,
        private val placeY: Int,
        private val fixedTargetW: Int,
        private val fixedTargetH: Int,
    ) : MatrixTransformation {

        private val matrix = Matrix()

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            matrix.reset()
            val tw = if (fixedTargetW > 0) fixedTargetW else inputWidth
            val th = if (fixedTargetH > 0) fixedTargetH else inputHeight

            val leftN = 2f * placeX / canvasW - 1f
            val rightN = 2f * (placeX + tw) / canvasW - 1f
            val bottomN = 2f * (canvasH - placeY - th) / canvasH - 1f
            val topN = 2f * (canvasH - placeY) / canvasH - 1f

            val scaleX = (rightN - leftN) / 2f
            val scaleY = (topN - bottomN) / 2f
            val tx = (leftN + rightN) / 2f
            val ty = (bottomN + topN) / 2f
            matrix.postScale(scaleX, scaleY)
            matrix.postTranslate(tx, ty)
            return Size(canvasW, canvasH)
        }

        override fun getMatrix(presentationTimeUs: Long): Matrix = matrix
    }
}
