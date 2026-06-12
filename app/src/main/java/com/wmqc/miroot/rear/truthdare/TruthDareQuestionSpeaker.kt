package com.wmqc.miroot.rear.truthdare

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 背屏出题结果页系统 TTS：先读玩家名，停顿后再读题目。 */
class TruthDareQuestionSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var pendingRound: PendingRound? = null
    private var pendingQuestion: String? = null
    private var pauseRunnable: Runnable? = null

    private data class PendingRound(
        val playerName: String?,
        val question: String,
    )

    init {
        tts =
            TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                val engine = tts ?: return@TextToSpeech
                val locale = Locale.SIMPLIFIED_CHINESE
                if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = locale
                }
                engine.setSpeechRate(1.0f)
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            onUtteranceFinished(utteranceId)
                        }

                        override fun onDone(utteranceId: String?) {
                            onUtteranceFinished(utteranceId)
                        }
                    },
                )
                ready.set(true)
                flushPendingRound(engine)
            }
    }

    fun speakRound(playerName: String?, question: String) {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) return
        val trimmedPlayer = playerName?.trim()?.takeIf { it.isNotEmpty() }
        val engine = tts
        if (!ready.get() || engine == null) {
            pendingRound = PendingRound(trimmedPlayer, trimmedQuestion)
            return
        }
        speakRoundNow(engine, trimmedPlayer, trimmedQuestion)
    }

    fun stop() {
        cancelPause()
        pendingQuestion = null
        pendingRound = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        if (utteranceId != UTTERANCE_PLAYER) return
        val question = pendingQuestion ?: return
        cancelPause()
        pauseRunnable =
            Runnable {
                pauseRunnable = null
                pendingQuestion = null
                tts?.speak(question, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_QUESTION)
            }
        mainHandler.postDelayed(pauseRunnable!!, PAUSE_MS)
    }

    private fun speakRoundNow(
        engine: TextToSpeech,
        playerName: String?,
        question: String,
    ) {
        cancelPause()
        pendingQuestion = null
        engine.stop()
        if (playerName == null) {
            engine.speak(question, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_QUESTION)
            return
        }
        pendingQuestion = question
        engine.speak(playerName, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_PLAYER)
    }

    private fun flushPendingRound(engine: TextToSpeech) {
        val round = pendingRound ?: return
        pendingRound = null
        speakRoundNow(engine, round.playerName, round.question)
    }

    private fun cancelPause() {
        pauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pauseRunnable = null
    }

    private companion object {
        private const val PAUSE_MS = 400L
        private const val UTTERANCE_PLAYER = "truth_dare_player"
        private const val UTTERANCE_QUESTION = "truth_dare_question"
    }
}
