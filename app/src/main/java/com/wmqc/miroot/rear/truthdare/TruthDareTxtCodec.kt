package com.wmqc.miroot.rear.truthdare

/**
 * TXT 题库编解码。
 *
 * 格式（可含多个分类块，块之间可空行）：
 * 首行：类型|难度|主题|题库开始
 * 中间：每行一题
 * 末行：类型|难度|主题|题库结束（须与首行分类一致）
 */
object TruthDareTxtCodec {

    const val START_MARKER = "题库开始"
    const val END_MARKER = "题库结束"

    data class ImportResult(
        val items: List<TruthDareBankItem> = emptyList(),
        val skippedDuplicates: Int = 0,
        val error: String? = null,
    )

    fun parse(text: String, idSeed: Long = System.currentTimeMillis()): ImportResult {
        val rawLines = text.lines()
        if (rawLines.isEmpty() || rawLines.all { it.trim().isEmpty() }) {
            return ImportResult(error = "文件为空")
        }

        val allItems = mutableListOf<TruthDareBankItem>()
        var lineIndex = 0
        var blockIndex = 0
        var globalQuestionIndex = 0

        while (lineIndex < rawLines.size) {
            while (lineIndex < rawLines.size && rawLines[lineIndex].trim().isEmpty()) {
                lineIndex++
            }
            if (lineIndex >= rawLines.size) {
                break
            }

            val startLineNumber = lineIndex + 1
            val startLine = rawLines[lineIndex].trim()
            val startParts = startLine.split("|").map { it.trim() }
            if (startParts.size != 4 || startParts[3] != START_MARKER) {
                return ImportResult(
                    error = "第 $startLineNumber 行格式错误，应为：类型|难度|主题|题库开始",
                )
            }

            val type =
                TruthDareType.fromLabel(startParts[0])
                    ?: return ImportResult(error = "第 $startLineNumber 行类型无效：${startParts[0]}（支持：真心话、大冒险）")
            val difficulty =
                TruthDareDifficulty.fromLabel(startParts[1])
                    ?: return ImportResult(error = "第 $startLineNumber 行难度无效：${startParts[1]}（支持：轻松、标准、挑战）")
            if (difficulty == TruthDareDifficulty.MIXED) {
                return ImportResult(error = "第 $startLineNumber 行难度不能使用「混合」，请指定具体难度")
            }
            val theme =
                TruthDareTheme.fromLabel(startParts[2])
                    ?: return ImportResult(error = "第 $startLineNumber 行主题无效：${startParts[2]}")
            if (theme == TruthDareTheme.MIXED) {
                return ImportResult(error = "第 $startLineNumber 行主题不能使用「混合」，请指定具体主题")
            }

            lineIndex++
            val contents = mutableListOf<String>()
            var foundEnd = false
            var endLineNumber = lineIndex + 1

            while (lineIndex < rawLines.size) {
                val line = rawLines[lineIndex].trim()
                endLineNumber = lineIndex + 1
                lineIndex++
                if (line.isEmpty()) {
                    continue
                }

                val parts = line.split("|").map { it.trim() }
                if (parts.size == 4 && parts[3] == END_MARKER) {
                    if (parts[0] != startParts[0] || parts[1] != startParts[1] || parts[2] != startParts[2]) {
                        return ImportResult(error = "第 $endLineNumber 行结束分类与起始行不一致")
                    }
                    foundEnd = true
                    break
                }
                contents.add(line)
            }

            if (!foundEnd) {
                return ImportResult(
                    error = "未找到结束行：${startParts[0]}|${startParts[1]}|${startParts[2]}|$END_MARKER",
                )
            }
            if (contents.isEmpty()) {
                return ImportResult(
                    error = "分类 ${startParts[0]}|${startParts[1]}|${startParts[2]} 中没有有效题目",
                )
            }

            contents.forEach { content ->
                allItems.add(
                    TruthDareBankItem(
                        id = "txt_${idSeed}_${blockIndex}_$globalQuestionIndex",
                        type = type,
                        content = content,
                        theme = theme,
                        difficulty = difficulty,
                    ),
                )
                globalQuestionIndex++
            }
            blockIndex++
        }

        if (allItems.isEmpty()) {
            return ImportResult(error = "未找到有效题库块，请检查首行是否为：类型|难度|主题|题库开始")
        }
        return ImportResult(items = allItems)
    }

    fun export(items: List<TruthDareBankItem>): String {
        if (items.isEmpty()) return ""
        val groups =
            items.groupBy { Triple(it.type, it.difficulty, it.theme) }
                .toList()
                .sortedBy { (key, _) -> "${key.first}${key.second}${key.third}" }
        return buildString {
            groups.forEachIndexed { index, (key, group) ->
                if (index > 0) appendLine()
                val (type, difficulty, theme) = key
                appendLine("${type.displayLabel()}|${difficulty.displayLabel()}|${theme.displayLabel()}|$START_MARKER")
                group.forEach { appendLine(it.content) }
                appendLine("${type.displayLabel()}|${difficulty.displayLabel()}|${theme.displayLabel()}|$END_MARKER")
            }
        }.trimEnd()
    }

    fun exampleText(): String =
        buildCategoryBlock(
            type = TruthDareType.TRUTH,
            difficulty = TruthDareDifficulty.EASY,
            theme = TruthDareTheme.EMOTION,
            questions = listOf("你最近一次心动是什么时候？", "说一件你最后悔的事"),
        )

    /** 含格式规则、分类枚举与一组示例，便于复制给 AI 批量生成题库。 */
    fun aiGenerationExampleText(): String =
        buildString {
            appendLine("请按以下 TXT 格式生成真心话大冒险题库，直接输出可导入文本：")
            appendLine()
            appendLine("【格式规则】")
            appendLine("每个分类块：")
            appendLine("首行：类型|难度|主题|题库开始")
            appendLine("中间：每行一题（不要编号、不要空行）")
            appendLine("末行：类型|难度|主题|题库结束（须与首行分类完全一致）")
            appendLine("多个分类块之间空一行。")
            appendLine()
            appendLine("【可选分类】")
            appendLine("类型：${TruthDareType.entries.joinToString("、") { it.displayLabel() }}")
            appendLine("难度：${TruthDareDifficulty.bankDifficulties.joinToString("、") { it.displayLabel() }}")
            appendLine("主题：${TruthDareTheme.bankThemes.joinToString("、") { it.displayLabel() }}")
            appendLine()
            appendLine("【分类组合说明】")
            appendLine("每个分类块只能使用一种「类型 + 难度 + 主题」组合。")
            appendLine("需要覆盖多档难度或多类主题时，请拆成多个分类块分别生成。")
            appendLine()
            appendLine("【格式示例（请替换为真实题目）】")
            append(
                buildCategoryBlock(
                    type = TruthDareType.TRUTH,
                    difficulty = TruthDareDifficulty.EASY,
                    theme = TruthDareTheme.EMOTION,
                    questions = listOf("你最近一次心动是什么时候？", "说一件你最后悔的事"),
                ),
            )
            appendLine()
            appendLine()
            appendLine("【生成要求】")
            appendLine("- 难度不可填「混合」，主题不可填「混合」")
            appendLine("- 每题单独一行，首末行分类必须与中间题目所属分类一致")
            appendLine("- 请按我指定的类型、难度、主题与数量生成；未指定的组合可跳过")
        }.trimEnd()

    private fun buildCategoryBlock(
        type: TruthDareType,
        difficulty: TruthDareDifficulty,
        theme: TruthDareTheme,
        questions: List<String>,
    ): String {
        val header = "${type.displayLabel()}|${difficulty.displayLabel()}|${theme.displayLabel()}"
        return buildString {
            appendLine("$header|$START_MARKER")
            questions.forEach { appendLine(it) }
            appendLine("$header|$END_MARKER")
        }.trimEnd()
    }
}
