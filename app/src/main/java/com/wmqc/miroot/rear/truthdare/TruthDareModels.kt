package com.wmqc.miroot.rear.truthdare

enum class TruthDarePlayerPickMode {
    WHEEL,
    ROUND_ROBIN,
    RANDOM,
}

enum class TruthDareChallengeMode {
    /** 转盘随机真心话或大冒险 */
    WHEEL,
    /** 玩家点击大按钮自选类型 */
    PLAYER_CHOICE,
}

enum class TruthDareDifficulty {
    /** 游戏筛选用：每轮随机难度档位 */
    MIXED,
    EASY,
    STANDARD,
    HARD,
    ;

    companion object {
        fun fromKey(key: String): TruthDareDifficulty =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: STANDARD

        fun fromLabel(label: String): TruthDareDifficulty? =
            when (label.trim()) {
                "混合", "mixed", "MIXED" -> MIXED
                "轻松", "easy", "EASY" -> EASY
                "标准", "standard", "STANDARD" -> STANDARD
                "挑战", "hard", "HARD" -> HARD
                else -> null
            }

        /** 可写入题库的难度（不含「混合」） */
        val bankDifficulties: List<TruthDareDifficulty> =
            entries.filter { it != MIXED }
    }
}

enum class TruthDareTheme {
    /** 游戏筛选用：每轮随机主题 */
    MIXED,
    EMOTION,
    FUNNY,
    SCHOOL,
    WORK,
    COUPLE,
    ;

    companion object {
        fun fromKey(key: String): TruthDareTheme =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: MIXED

        fun fromLabel(label: String): TruthDareTheme? =
            when (label.trim()) {
                "混合", "mixed", "MIXED" -> MIXED
                "情感", "emotion", "EMOTION" -> EMOTION
                "搞笑", "funny", "FUNNY" -> FUNNY
                "校园", "school", "SCHOOL" -> SCHOOL
                "职场", "work", "WORK" -> WORK
                "情侣", "couple", "COUPLE" -> COUPLE
                else -> null
            }

        /** 可写入题库的分类主题（不含「全部」） */
        val bankThemes: List<TruthDareTheme> =
            entries.filter { it != MIXED }
    }
}

enum class TruthDareType {
    TRUTH,
    DARE,
    ;

    companion object {
        fun fromLabel(label: String): TruthDareType? =
            when (label.trim()) {
                "真心话", "truth", "TRUTH" -> TRUTH
                "大冒险", "dare", "DARE", "冒险" -> DARE
                else -> null
            }
    }
}

fun TruthDareTheme.toCategoryKey(): String =
    when (this) {
        TruthDareTheme.MIXED -> "mixed"
        TruthDareTheme.EMOTION -> "emotion"
        TruthDareTheme.FUNNY -> "funny"
        TruthDareTheme.SCHOOL -> "school"
        TruthDareTheme.WORK -> "work"
        TruthDareTheme.COUPLE -> "couple"
    }

fun categoryKeyToTheme(key: String): TruthDareTheme {
    val k = key.trim().lowercase()
    if (k == "other") return TruthDareTheme.EMOTION
    return TruthDareTheme.entries.firstOrNull { it.toCategoryKey() == k }
        ?: TruthDareTheme.EMOTION
}

fun TruthDareType.displayLabel(): String =
    when (this) {
        TruthDareType.TRUTH -> "真心话"
        TruthDareType.DARE -> "大冒险"
    }

fun TruthDareDifficulty.displayLabel(): String =
    when (this) {
        TruthDareDifficulty.MIXED -> "混合"
        TruthDareDifficulty.EASY -> "轻松"
        TruthDareDifficulty.STANDARD -> "标准"
        TruthDareDifficulty.HARD -> "挑战"
    }

fun TruthDareTheme.displayLabel(): String =
    when (this) {
        TruthDareTheme.MIXED -> "混合"
        TruthDareTheme.EMOTION -> "情感"
        TruthDareTheme.FUNNY -> "搞笑"
        TruthDareTheme.SCHOOL -> "校园"
        TruthDareTheme.WORK -> "职场"
        TruthDareTheme.COUPLE -> "情侣"
    }

/** 三档难度存储值：轻松=1、标准=2、挑战=3。 */
fun TruthDareDifficulty.toStoredLevel(): Int =
    when (this) {
        TruthDareDifficulty.MIXED -> 2
        TruthDareDifficulty.EASY -> 1
        TruthDareDifficulty.STANDARD -> 2
        TruthDareDifficulty.HARD -> 3
    }

/** 旧版自定义题库 1–5 级归并为 1–3：1–2→1，3→2，4–5→3。 */
fun migrateLegacyDifficulty(level: Int): Int =
    when (level.coerceIn(1, 5)) {
        in 1..2 -> 1
        3 -> 2
        else -> 3
    }

/** 1–3 存储值对应档位。 */
fun difficultyLevelToTier(level: Int): TruthDareDifficulty =
    when (level.coerceIn(1, 3)) {
        1 -> TruthDareDifficulty.EASY
        2 -> TruthDareDifficulty.STANDARD
        else -> TruthDareDifficulty.HARD
    }

fun TruthDareDifficulty.matchesQuestionDifficulty(level: Int): Boolean =
    this != TruthDareDifficulty.MIXED && toStoredLevel() == level.coerceIn(1, 3)

enum class TruthDareBankSource {
    BUILTIN,
    CUSTOM,
}

data class TruthDareBankItem(
    val id: String,
    val type: TruthDareType,
    val content: String,
    val theme: TruthDareTheme,
    val difficulty: TruthDareDifficulty,
    val source: TruthDareBankSource = TruthDareBankSource.CUSTOM,
) {
    fun toQuestion(): TruthDareQuestion =
        TruthDareQuestion(
            id = id,
            content = content,
            category = theme.toCategoryKey(),
            difficulty = difficulty.toStoredLevel(),
            isCustom = true,
        )

    fun metaLabel(): String {
        val prefix =
            when (source) {
                TruthDareBankSource.BUILTIN -> "内置 · "
                TruthDareBankSource.CUSTOM -> ""
            }
        return "$prefix${type.displayLabel()} · ${difficulty.displayLabel()} · ${theme.displayLabel()}"
    }

    val isBuiltin: Boolean get() = source == TruthDareBankSource.BUILTIN
}

data class TruthDareQuestion(
    val id: String,
    val content: String,
    val category: String,
    val difficulty: Int,
    val durationSec: Int = 0,
    val isCustom: Boolean = false,
)

data class TruthDareConfig(
    val players: List<String>,
    val playerPickMode: TruthDarePlayerPickMode,
    val challengeMode: TruthDareChallengeMode,
    val difficulty: TruthDareDifficulty,
    val theme: TruthDareTheme,
    val speakQuestions: Boolean = true,
)

enum class TruthDareRearScreen {
    SETUP,
    PLAYING,
    RESULT,
}

data class TruthDareRoundResult(
    val playerName: String,
    val type: TruthDareType,
    val question: TruthDareQuestion,
)

data class TruthDareBankFilter(
    val type: TruthDareType? = null,
    val difficulty: TruthDareDifficulty? = null,
    val theme: TruthDareTheme? = null,
) {
    fun matches(item: TruthDareBankItem): Boolean {
        if (type != null && item.type != type) return false
        if (difficulty != null && item.difficulty != difficulty) return false
        if (theme != null && item.theme != theme) return false
        return true
    }
}
