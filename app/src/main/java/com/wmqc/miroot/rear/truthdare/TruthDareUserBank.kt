package com.wmqc.miroot.rear.truthdare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 用户自定义题库：本地持久化，背屏游戏实时读取。 */
object TruthDareUserBank {

    private const val KEY_USER_BANK = "user_bank"

    fun load(context: Context): List<TruthDareBankItem> {
        migrateLegacyIfNeeded(context)
        val raw =
            TruthDarePrefs.prefs(context).getString(KEY_USER_BANK, null)
                ?: return emptyList()
        return runCatching { decodeJson(raw) }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<TruthDareBankItem>) {
        TruthDarePrefs.prefs(context)
            .edit()
            .putString(KEY_USER_BANK, encodeJson(items))
            .apply()
    }

    fun filter(items: List<TruthDareBankItem>, filter: TruthDareBankFilter): List<TruthDareBankItem> =
        items.filter { filter.matches(it) }

    fun search(items: List<TruthDareBankItem>, query: String): List<TruthDareBankItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return items.filter { item ->
            item.content.contains(q, ignoreCase = true) ||
                item.metaLabel().contains(q, ignoreCase = true)
        }
    }

    fun mergeDedupe(
        existing: List<TruthDareBankItem>,
        incoming: List<TruthDareBankItem>,
    ): Pair<List<TruthDareBankItem>, Int> {
        val seen = existing.map { it.content.trim() }.toMutableSet()
        val result = existing.toMutableList()
        var skipped = 0
        for (item in incoming) {
            val key = item.content.trim()
            if (key.isEmpty()) {
                skipped++
                continue
            }
            if (key in seen) {
                skipped++
                continue
            }
            seen.add(key)
            result.add(item.copy(id = "bank_${System.currentTimeMillis()}_${result.size}"))
        }
        return result to skipped
    }

    fun removeMatching(items: List<TruthDareBankItem>, filter: TruthDareBankFilter): List<TruthDareBankItem> =
        items.filterNot { filter.matches(it) }

    private fun migrateLegacyIfNeeded(context: Context) {
        val prefs = TruthDarePrefs.prefs(context)
        if (prefs.contains(KEY_USER_BANK)) return
        val truth = TruthDarePrefs.readLegacyCustomTruth(context)
        val dare = TruthDarePrefs.readLegacyCustomDare(context)
        if (truth.isEmpty() && dare.isEmpty()) return
        val migrated = buildList {
            truth.forEach { q ->
                add(
                    TruthDareBankItem(
                        id = q.id,
                        type = TruthDareType.TRUTH,
                        content = q.content,
                        theme = categoryKeyToTheme(q.category),
                        difficulty = difficultyLevelToTier(migrateLegacyDifficulty(q.difficulty)),
                    ),
                )
            }
            dare.forEach { q ->
                add(
                    TruthDareBankItem(
                        id = q.id,
                        type = TruthDareType.DARE,
                        content = q.content,
                        theme = categoryKeyToTheme(q.category),
                        difficulty = difficultyLevelToTier(migrateLegacyDifficulty(q.difficulty)),
                    ),
                )
            }
        }
        save(context, migrated)
        prefs.edit()
            .remove("custom_truth")
            .remove("custom_dare")
            .apply()
    }

    private fun decodeStoredDifficulty(raw: String): TruthDareDifficulty {
        if (raw.equals("MIXED", ignoreCase = true)) {
            return TruthDareDifficulty.STANDARD
        }
        return runCatching { TruthDareDifficulty.valueOf(raw) }
            .getOrDefault(TruthDareDifficulty.STANDARD)
    }

    private fun decodeStoredTheme(raw: String): TruthDareTheme {
        if (raw.equals("OTHER", ignoreCase = true)) {
            return TruthDareTheme.EMOTION
        }
        val theme =
            runCatching { TruthDareTheme.valueOf(raw) }
                .getOrDefault(TruthDareTheme.EMOTION)
        return if (theme == TruthDareTheme.MIXED) TruthDareTheme.EMOTION else theme
    }

    private fun encodeJson(items: List<TruthDareBankItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type.name)
                    .put("content", item.content)
                    .put("theme", item.theme.name)
                    .put("difficulty", item.difficulty.name),
            )
        }
        return arr.toString()
    }

    private fun decodeJson(raw: String): List<TruthDareBankItem> {
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val content = o.optString("content").trim()
                if (content.isEmpty()) continue
                val type =
                    runCatching { TruthDareType.valueOf(o.optString("type")) }
                        .getOrNull() ?: continue
                val theme = decodeStoredTheme(o.optString("theme"))
                val difficulty = decodeStoredDifficulty(o.optString("difficulty"))
                add(
                    TruthDareBankItem(
                        id = o.optString("id", "bank_$i"),
                        type = type,
                        content = content,
                        theme = theme,
                        difficulty = difficulty,
                    ),
                )
            }
        }
    }
}
