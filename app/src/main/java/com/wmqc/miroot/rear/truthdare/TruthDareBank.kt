package com.wmqc.miroot.rear.truthdare

import android.content.Context
import org.json.JSONArray
import kotlin.random.Random

/** 背屏真心话大冒险左侧安全区（避让摄像头）。 */
internal const val TRUTH_DARE_SAFE_LEFT_PX = 270

/**
 * 题库适配自开源项目 pankehan11-afk Truth_or_Dare，assets 内 JSON + 用户自定义题目。
 */
object TruthDareBank {
    @Volatile
    private var defaultTruth: List<TruthDareQuestion>? = null

    @Volatile
    private var defaultDare: List<TruthDareQuestion>? = null

    fun loadTruth(context: Context): List<TruthDareQuestion> {
        ensureDefaults(context)
        val hidden = TruthDareHiddenBuiltin.load(context)
        val builtin = defaultTruth!!.filter { it.id !in hidden }
        val custom =
            TruthDareUserBank.load(context)
                .filter { it.type == TruthDareType.TRUTH }
                .map { it.toQuestion() }
        return builtin + custom
    }

    fun loadDare(context: Context): List<TruthDareQuestion> {
        ensureDefaults(context)
        val hidden = TruthDareHiddenBuiltin.load(context)
        val builtin = defaultDare!!.filter { it.id !in hidden }
        val custom =
            TruthDareUserBank.load(context)
                .filter { it.type == TruthDareType.DARE }
                .map { it.toQuestion() }
        return builtin + custom
    }

    fun loadBuiltinBankItems(
        context: Context,
        hiddenIds: Set<String>? = null,
    ): List<TruthDareBankItem> {
        ensureDefaults(context)
        val hidden = hiddenIds ?: TruthDareHiddenBuiltin.load(context)
        val truth =
            defaultTruth!!
                .filter { it.id !in hidden }
                .map { it.toBuiltinBankItem(TruthDareType.TRUTH) }
        val dare =
            defaultDare!!
                .filter { it.id !in hidden }
                .map { it.toBuiltinBankItem(TruthDareType.DARE) }
        return truth + dare
    }

    private fun TruthDareQuestion.toBuiltinBankItem(type: TruthDareType): TruthDareBankItem =
        TruthDareBankItem(
            id = id,
            type = type,
            content = content,
            theme = categoryKeyToTheme(category),
            difficulty = difficultyLevelToTier(difficulty),
            source = TruthDareBankSource.BUILTIN,
        )

    fun pickTruth(
        context: Context,
        difficulty: TruthDareDifficulty,
        theme: TruthDareTheme,
        usedIds: Set<String>,
    ): TruthDareQuestion {
        val all = loadTruth(context)
        val pickTheme = resolvePickTheme(theme, all)
        val pickDifficulty = resolvePickDifficulty(difficulty)
        val pool = filterPool(all, pickDifficulty, pickTheme, usedIds)
        val finalPool =
            if (pool.isNotEmpty()) {
                pool
            } else {
                filterPool(all, pickDifficulty, pickTheme, emptySet())
            }
        return finalPool.pickRandom(pickDifficulty, pickTheme)
    }

    fun pickDare(
        context: Context,
        difficulty: TruthDareDifficulty,
        theme: TruthDareTheme,
        usedIds: Set<String>,
    ): TruthDareQuestion {
        val all = loadDare(context)
        val pickTheme = resolvePickTheme(theme, all)
        val pickDifficulty = resolvePickDifficulty(difficulty)
        val pool = filterPool(all, pickDifficulty, pickTheme, usedIds)
        val finalPool =
            if (pool.isNotEmpty()) {
                pool
            } else {
                filterPool(all, pickDifficulty, pickTheme, emptySet())
            }
        return finalPool.pickRandom(pickDifficulty, pickTheme)
    }

    private fun resolvePickTheme(theme: TruthDareTheme, pool: List<TruthDareQuestion>): TruthDareTheme {
        if (theme != TruthDareTheme.MIXED) return theme
        val themesWithQuestions =
            TruthDareTheme.bankThemes.filter { candidate ->
                pool.any { it.category == candidate.toCategoryKey() }
            }
        return (themesWithQuestions.ifEmpty { TruthDareTheme.bankThemes }).random()
    }

    private fun resolvePickDifficulty(difficulty: TruthDareDifficulty): TruthDareDifficulty {
        if (difficulty != TruthDareDifficulty.MIXED) return difficulty
        return TruthDareDifficulty.bankDifficulties.random()
    }

    private fun filterPool(
        all: List<TruthDareQuestion>,
        difficulty: TruthDareDifficulty,
        theme: TruthDareTheme,
        usedIds: Set<String>,
    ): List<TruthDareQuestion> =
        all
            .filter { it.id !in usedIds }
            .filter { it.category == theme.toCategoryKey() }
            .filter { difficulty.matchesQuestionDifficulty(it.difficulty) }

    private fun ensureDefaults(context: Context) {
        if (defaultTruth != null && defaultDare != null) return
        synchronized(this) {
            if (defaultTruth == null) {
                defaultTruth = readAssetArray(context, "truthdare/truth_questions.json")
            }
            if (defaultDare == null) {
                defaultDare = readAssetArray(context, "truthdare/dare_questions.json")
            }
        }
    }

    private fun readAssetArray(context: Context, path: String): List<TruthDareQuestion> {
        val json = context.assets.open(path).bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    TruthDareQuestion(
                        id = o.getString("id"),
                        content = o.getString("content"),
                        category = o.optString("category", "mixed"),
                        difficulty = o.optInt("difficulty", 2).coerceIn(1, 3),
                        durationSec = o.optInt("duration", 0),
                        isCustom = false,
                    ),
                )
            }
        }
    }

    private fun List<TruthDareQuestion>.pickRandom(
        difficulty: TruthDareDifficulty,
        theme: TruthDareTheme,
    ): TruthDareQuestion =
        if (isEmpty()) {
            TruthDareQuestion(
                id = "fallback",
                content = "自由发挥，想说什么说什么～",
                category = theme.toCategoryKey(),
                difficulty = difficulty.toStoredLevel(),
            )
        } else {
            this[Random.nextInt(size)]
        }
}
