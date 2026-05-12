package com.wmqc.miroot.lyrics

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object LyricsWordTokenizer {
    @Volatile
    private var appContext: Context? = null
    private val tokenizerReadyListeners = CopyOnWriteArraySet<Runnable>()
    private val initialized = AtomicBoolean(false)
    private val jiebaReady = AtomicBoolean(false)
    private val engineReadyBridge = Runnable {
        jiebaReady.set(true)
        tokenizerReadyListeners.forEach { listener ->
            runCatching { listener.run() }
        }
    }

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            JiebaTokenizerEngine.addOnReadyListener(engineReadyBridge)
        }
        jiebaReady.set(JiebaTokenizerEngine.isReady())
        JiebaTokenizerEngine.preloadAsync(context.applicationContext)
    }

    @JvmStatic
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val cleaned = text.trim()
        val tokens = try {
            JiebaTokenizerEngine.tokenize(appContext, cleaned)
        } catch (_: Throwable) {
            fallbackTokenize(cleaned)
        }
        return tokens.ifEmpty { fallbackTokenize(cleaned) }
    }

    @JvmStatic
    fun clearCaches() {
        JiebaTokenizerEngine.clearCache()
    }

    @JvmStatic
    fun isJiebaReady(): Boolean = jiebaReady.get() || JiebaTokenizerEngine.isReady()

    @JvmStatic
    fun addOnTokenizerReadyListener(listener: Runnable) {
        tokenizerReadyListeners.add(listener)
        if (isJiebaReady()) {
            runCatching { listener.run() }
        }
    }

    @JvmStatic
    fun removeOnTokenizerReadyListener(listener: Runnable) {
        tokenizerReadyListeners.remove(listener)
    }

    private fun fallbackTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var currentIsCjk = false

        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }

        for (ch in text) {
            when {
                ch.isWhitespace() -> flush()
                ch.isPunctuationLike() -> {
                    flush()
                    result += ch.toString()
                }
                current.isEmpty() -> {
                    current.append(ch)
                    currentIsCjk = ch.isCjk()
                }
                currentIsCjk == ch.isCjk() -> current.append(ch)
                else -> {
                    flush()
                    current.append(ch)
                    currentIsCjk = ch.isCjk()
                }
            }
        }
        flush()
        return result.ifEmpty { text.filterNot(Char::isWhitespace).map { it.toString() } }
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA
    }

    private fun Char.isPunctuationLike(): Boolean = !isWhitespace() && toString().matches(Regex("\\p{Punct}"))
}
