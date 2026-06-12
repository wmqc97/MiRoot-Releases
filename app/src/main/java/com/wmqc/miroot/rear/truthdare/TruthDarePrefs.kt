package com.wmqc.miroot.rear.truthdare

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object TruthDarePrefs {
    const val PREFS_NAME = "miroot_truth_dare"
    private const val PREFS = PREFS_NAME
    private const val KEY_PLAYERS = "players"
    private const val KEY_PLAYER_PICK = "player_pick_mode"
    private const val KEY_CHALLENGE_MODE = "challenge_mode"
    private const val KEY_DIFFICULTY = "difficulty"
    private const val KEY_THEME = "theme"
    private const val KEY_SPEAK_QUESTIONS = "speak_questions"
    private const val KEY_CUSTOM_TRUTH = "custom_truth"
    private const val KEY_CUSTOM_DARE = "custom_dare"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readConfig(context: Context): TruthDareConfig {
        val p = prefs(context)
        val players = readPlayers(p.getString(KEY_PLAYERS, null))
        return TruthDareConfig(
            players = players,
            playerPickMode =
                runCatching {
                    TruthDarePlayerPickMode.valueOf(p.getString(KEY_PLAYER_PICK, TruthDarePlayerPickMode.WHEEL.name)!!)
                }.getOrDefault(TruthDarePlayerPickMode.WHEEL),
            challengeMode = decodeChallengeMode(p.getString(KEY_CHALLENGE_MODE, TruthDareChallengeMode.WHEEL.name)!!),
            difficulty = TruthDareDifficulty.fromKey(p.getString(KEY_DIFFICULTY, TruthDareDifficulty.STANDARD.name)!!),
            theme = decodeConfigTheme(p.getString(KEY_THEME, TruthDareTheme.MIXED.name)!!),
            speakQuestions = p.getBoolean(KEY_SPEAK_QUESTIONS, true),
        )
    }

    fun writeConfig(context: Context, config: TruthDareConfig) {
        prefs(context)
            .edit()
            .putString(KEY_PLAYERS, JSONArray(config.players).toString())
            .putString(KEY_PLAYER_PICK, config.playerPickMode.name)
            .putString(KEY_CHALLENGE_MODE, config.challengeMode.name)
            .putString(KEY_DIFFICULTY, config.difficulty.name)
            .putString(KEY_THEME, config.theme.name)
            .putBoolean(KEY_SPEAK_QUESTIONS, config.speakQuestions)
            .apply()
    }

    private fun decodeChallengeMode(raw: String): TruthDareChallengeMode {
        if (raw.equals("DIRECT_RANDOM", ignoreCase = true)) {
            return TruthDareChallengeMode.PLAYER_CHOICE
        }
        return runCatching { TruthDareChallengeMode.valueOf(raw) }
            .getOrDefault(TruthDareChallengeMode.WHEEL)
    }

    private fun decodeConfigTheme(raw: String): TruthDareTheme {
        if (raw.equals("OTHER", ignoreCase = true)) {
            return TruthDareTheme.MIXED
        }
        return TruthDareTheme.fromKey(raw)
    }

    fun defaultPlayers(count: Int): List<String> =
        (1..count.coerceIn(2, 10)).map { "玩家$it" }

    /** @deprecated 仅用于迁移旧版自定义题库 */
    internal fun readLegacyCustomTruth(context: Context): List<TruthDareQuestion> =
        readLegacyCustomList(context, KEY_CUSTOM_TRUTH)

    /** @deprecated 仅用于迁移旧版自定义题库 */
    internal fun readLegacyCustomDare(context: Context): List<TruthDareQuestion> =
        readLegacyCustomList(context, KEY_CUSTOM_DARE)

    private fun readPlayers(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return defaultPlayers(4)
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isNotEmpty()) add(name)
                }
            }
        }.getOrDefault(defaultPlayers(4)).ifEmpty { defaultPlayers(4) }
    }

    private fun readLegacyCustomList(context: Context, key: String): List<TruthDareQuestion> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val content = o.optString("content").trim()
                    if (content.isEmpty()) continue
                    add(
                        TruthDareQuestion(
                            id = o.optString("id", "legacy_$i"),
                            content = content,
                            category = o.optString("category", "emotion"),
                            difficulty = migrateLegacyDifficulty(o.optInt("difficulty", 2)),
                            isCustom = true,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
