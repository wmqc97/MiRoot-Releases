package com.wmqc.miroot.lyrics

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.huaban.analysis.jieba.JiebaSegmenter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object JiebaTokenizerEngine {
    private const val CUSTOM_DICT_ASSET = "jieba/user_dict_lyrics.txt"
    private const val EXTRA_DICT_ASSET = "jieba/dict_lyrics_ext.txt"
    private const val RUNTIME_DIR = "jieba"
    private const val RUNTIME_USER_DICT = "user_dict_lyrics.txt"

    private val initialized = AtomicBoolean(false)
    private val preloadStarted = AtomicBoolean(false)
    private val segmenterReady = AtomicBoolean(false)
    private val segmenter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { JiebaSegmenter() }
    private val customWords = linkedSetOf<String>()
    private val cache = object : LruCache<String, List<String>>(220) {}
    private val preloadExecutor = Executors.newSingleThreadExecutor()
    private val readyListeners = CopyOnWriteArraySet<Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var maxCustomWordLength: Int = 0

    @JvmStatic
    fun init(context: Context) {
        preloadAsync(context)
    }

    @JvmStatic
    fun preloadAsync(context: Context) {
        if (initialized.get()) return
        if (!preloadStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        preloadExecutor.execute {
            try {
                reload(app)
            } finally {
                if (!initialized.get()) {
                    preloadStarted.set(false)
                }
            }
        }
    }

    @JvmStatic
    fun reload(context: Context) {
        synchronized(this) {
            val app = context.applicationContext
            customWords.clear()
            customWords += loadWordsFromAsset(app, CUSTOM_DICT_ASSET)
            customWords += loadWordsFromAsset(app, EXTRA_DICT_ASSET)
            customWords += loadWordsFromFile(runtimeDictFile(app))
            maxCustomWordLength = customWords.maxOfOrNull { it.length } ?: 0
            synchronized(cache) { cache.evictAll() }
            initialized.set(true)
        }
        ensureSegmenterReady()
        dispatchReadyIfNeeded()
    }

    data class ImportResult(
        val mergedWords: Int,
        val totalWords: Int,
        val filePath: String,
    )

    @JvmStatic
    fun importUserDictionary(context: Context, sourceUri: Uri): ImportResult {
        val app = context.applicationContext
        val importedWords = runCatching {
            app.contentResolver.openInputStream(sourceUri)?.bufferedReader()?.useLines { lines ->
                lines.mapNotNull(::parseWord).filter { it.length >= 2 }.toList()
            } ?: emptyList()
        }.getOrDefault(emptyList())

        val targetFile = runtimeDictFile(app)
        val oldWords = loadWordsFromFile(targetFile)
        val merged = linkedSetOf<String>().apply {
            addAll(oldWords)
            addAll(importedWords)
        }
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(
            buildString {
                append("# runtime custom dictionary imported from local txt\n")
                merged.forEach { word ->
                    append(word).append('\n')
                }
            },
            Charsets.UTF_8,
        )
        reload(app)
        return ImportResult(
            mergedWords = (merged.size - oldWords.size).coerceAtLeast(0),
            totalWords = merged.size,
            filePath = targetFile.absolutePath,
        )
    }

    @JvmStatic
    fun tokenize(context: Context?, text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.trim()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (context != null && !initialized.get()) {
                preloadAsync(context.applicationContext)
            }
            synchronized(cache) {
                cache.get(normalized)?.let { return it }
            }
            return SimpleChineseTokenizer.tokenize(normalized)
        }

        synchronized(cache) {
            cache.get(normalized)?.let { return it }
        }

        if (context != null && !initialized.get()) {
            preloadAsync(context.applicationContext)
            return SimpleChineseTokenizer.tokenize(normalized)
        }
        if (!segmenterReady.get()) {
            ensureSegmenterReady()
            return SimpleChineseTokenizer.tokenize(normalized)
        }

        val result = runCatching {
            val raw = segmenter.process(normalized, JiebaSegmenter.SegMode.SEARCH)
                .map { it.word.trim() }
                .filter { it.isNotBlank() && !isOnlyPunctuation(it) }
            mergeCustomWords(raw)
        }.getOrElse {
            SimpleChineseTokenizer.tokenize(normalized)
        }.ifEmpty {
            SimpleChineseTokenizer.tokenize(normalized)
        }

        synchronized(cache) {
            cache.put(normalized, result)
        }
        return result
    }

    @JvmStatic
    fun clearCache() {
        synchronized(cache) { cache.evictAll() }
    }

    @JvmStatic
    fun isReady(): Boolean = initialized.get() && segmenterReady.get()

    @JvmStatic
    fun addOnReadyListener(listener: Runnable) {
        readyListeners.add(listener)
        if (isReady()) {
            mainHandler.post { runCatching { listener.run() } }
        }
    }

    @JvmStatic
    fun removeOnReadyListener(listener: Runnable) {
        readyListeners.remove(listener)
    }

    private fun ensureSegmenterReady() {
        if (segmenterReady.get()) return
        runCatching {
            segmenter
            segmenterReady.set(true)
        }.onFailure {
            segmenterReady.set(false)
        }
    }

    private fun dispatchReadyIfNeeded() {
        if (!isReady()) return
        if (readyListeners.isEmpty()) return
        readyListeners.forEach { listener ->
            mainHandler.post { runCatching { listener.run() } }
        }
    }

    private fun mergeCustomWords(tokens: List<String>): List<String> {
        if (tokens.isEmpty() || maxCustomWordLength < 2) return tokens
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var bestLen = 1
            var bestText: String? = null
            val maxTake = minOf(tokens.size - i, maxCustomWordLength)
            for (take in maxTake downTo 2) {
                val candidate = buildString {
                    for (idx in i until i + take) append(tokens[idx])
                }
                if (customWords.contains(candidate)) {
                    bestLen = take
                    bestText = candidate
                    break
                }
            }
            if (bestText != null) {
                out += bestText
            } else {
                out += tokens[i]
            }
            i += bestLen
        }
        return out
    }

    private fun loadWordsFromAsset(context: Context, assetPath: String): Set<String> {
        return runCatching {
            context.assets.open(assetPath).bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseWord).filter { it.length >= 2 }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    private fun loadWordsFromFile(file: File): Set<String> {
        if (!file.exists() || !file.isFile) return emptySet()
        return runCatching {
            file.bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseWord).filter { it.length >= 2 }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    private fun parseWord(raw: String): String? {
        val line = raw.substringBefore('#').trim()
        if (line.isBlank()) return null
        return line.split(Regex("\\s+"))[0].trim().ifBlank { null }
    }

    private fun runtimeDictFile(context: Context): File =
        File(File(context.filesDir, RUNTIME_DIR), RUNTIME_USER_DICT)

    private fun isOnlyPunctuation(token: String): Boolean {
        val punct = Regex("^[\\p{Punct}，。！？、；：“”‘’（）【】《》…—·]+$", RegexOption.IGNORE_CASE)
        return punct.matches(token.lowercase(Locale.ROOT))
    }
}
