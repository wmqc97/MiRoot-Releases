package com.wmqc.miroot.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 SuperLyric 逐字时间轴对齐到单行 {@link EnhancedLRCParser.EnhancedLyricLine#time}，
 * 并映射到网络行文本字符，使高亮进度与 {@code line.text} 一致。
 */
final class SuperLyricWordTimestamps {
    private static final long DEFAULT_WORD_DURATION_MS = 220L;
    /** 句末最后一字最短展示时长（过短会导致高亮只走一半，与 SuperLyric 单句完整走完一致）。 */
    private static final long LAST_CHAR_MIN_DURATION_MS = 360L;
    /** 在 SuperLyric 原结束时间之上最多补长的时长（有下一行时另受行间隙约束）。 */
    private static final long LAST_CHAR_MAX_PAD_MS = 400L;
    /** 利用「当前行结束 ~ 下一行 LRC」间隙时，最多向间隙内延伸的比例（避免整句一次铺满）。 */
    private static final float LAST_CHAR_GAP_FILL_RATIO = 0.22f;
    /** Super 时间轴明显短于 LRC 行间隙时，整句按比例微拉伸的下限（低于此不拉伸）。 */
    private static final float LINE_TIMELINE_MIN_FILL_RATIO = 0.58f;
    /** 最多拉伸到 LRC 行间隙的比例（不拉满，避免与行切换抢拍）。 */
    private static final float LINE_TIMELINE_MAX_FILL_RATIO = 0.76f;
    /** 映射到网络行时，至少命中的「可比对字符」占比，否则退回仅时间对齐结果。 */
    private static final double MAP_MIN_MATCH_RATIO = 0.45;
    /** 同句重复回调时，单字时间变化超过该阈值视为需要刷新逐字轴。 */
    private static final long MATERIAL_WORD_TIMING_DELTA_MS = 36L;
    /** 网络 LRC 行时间比模块行起点晚超过该值时，逐字对齐改用模块时间（常见约半拍）。 */
    private static final long FUSION_NETWORK_LINE_LAG_MS = 80L;
    /**
     * 智能切换：首字时间与 LRC 行时刻对齐（±该容差）时视为整曲绝对轴，避免前几分钟被当成句内 0 基轴。
     */
    private static final long FUSION_LINE_ANCHOR_SLACK_MS = 500L;
    /** 行内相对时间通常远小于整曲绝对时间（毫秒）。 */
    private static final long ABSOLUTE_SONG_TIME_HINT_MS = 120_000L;
    /**
     * 模块 {@link com.hchen.superlyricapi.SuperLyricLine#getEndTime()} 明显短于逐字跨度时，信任逐字轴
     * （部分发布者行 end 仅为首包延迟，会把整句压成几百毫秒导致高亮秒走完）。
     */
    private static final float LINE_DURATION_TRUST_WORD_SPAN_RATIO = 0.75f;

    private SuperLyricWordTimestamps() {
    }

    /**
     * 用于句内裁剪的有效行时长：取「行 end−start」与逐字自然跨度中较可信者，避免错误行 end 压短时间轴。
     */
    static long resolveEffectiveLineDurationMs(long lineStartMs,
                                               long lineEndMs,
                                               List<EnhancedLRCParser.WordTimestamp> words) {
        long naturalSpan = timelineSpanMs(words);
        long lineStart = Math.max(0L, lineStartMs);
        long fromLine = lineEndMs > lineStart ? lineEndMs - lineStart : 0L;
        if (fromLine <= 0L) {
            if (naturalSpan > 0L) {
                return naturalSpan;
            }
            return Math.max(LAST_CHAR_MIN_DURATION_MS, DEFAULT_WORD_DURATION_MS * 2L);
        }
        if (naturalSpan > 0L && fromLine < (long) (naturalSpan * LINE_DURATION_TRUST_WORD_SPAN_RATIO)) {
            return naturalSpan;
        }
        return Math.max(fromLine, naturalSpan);
    }

    /** 句内逐字轴跨度（末字 end − 首字 start），用于单句模式行时长兜底。 */
    static long timelineSpanMs(List<EnhancedLRCParser.WordTimestamp> words) {
        if (words == null || words.isEmpty()) {
            return 0L;
        }
        long minStart = Long.MAX_VALUE;
        long maxEnd = 0L;
        for (EnhancedLRCParser.WordTimestamp word : words) {
            if (word == null) {
                continue;
            }
            minStart = Math.min(minStart, Math.max(0L, word.startTime));
            maxEnd = Math.max(maxEnd, Math.max(word.startTime + 1L, word.endTime));
        }
        if (minStart == Long.MAX_VALUE) {
            return 0L;
        }
        return Math.max(0L, maxEnd - minStart);
    }

    /**
     * 是否为句内 0 基逐字轴（单句 SuperLyric 兜底）。
     * 勿仅用「&lt; 2min」判定：智能切换融合后的整曲绝对轴在歌曲前 2 分钟也会被误判，导致高亮坐标错乱。
     */
    static boolean usesSentenceRelativeTimeline(List<EnhancedLRCParser.WordTimestamp> source) {
        return usesSentenceRelativeTimeline(source, 0L);
    }

    /**
     * @param lineTimeMs 当前 LRC 行时间；智能切换融合后首字与行时刻对齐时按整曲绝对轴处理。
     */
    static boolean usesSentenceRelativeTimeline(List<EnhancedLRCParser.WordTimestamp> source,
                                                long lineTimeMs) {
        if (source == null || source.isEmpty()) {
            return true;
        }
        long minStart = minWordStartMs(source);
        long maxEnd = maxWordEndMs(source);
        if (minStart >= ABSOLUTE_SONG_TIME_HINT_MS) {
            return false;
        }
        long span = Math.max(0L, maxEnd - minStart);
        // 典型句内轴：起点贴近 0，跨度在一行合理时长内
        if (minStart < 8_000L && span <= 90_000L) {
            long lineMs = Math.max(0L, lineTimeMs);
            if (lineMs > 0L && minStart + FUSION_LINE_ANCHOR_SLACK_MS >= lineMs) {
                return false;
            }
            return true;
        }
        // 如 60s 处的整曲绝对轴：minStart 大、跨度短
        return false;
    }

    /**
     * 「仅 SuperLyric」单句逐字显示：按官方行/字时间生成与 {@code lineText} 等长的句内 0 基轴。
     * 高亮进度 = 播放位置 − 行锚点（见 {@link com.wmqc.miroot.lyrics.ModernLyricsView#wordProgressPositionMs}）。
     */
    static ArrayList<EnhancedLRCParser.WordTimestamp> buildSingleLineDisplayTimeline(
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> moduleWords,
        long moduleLineStartMs,
        long moduleLineEndMs
    ) {
        if (moduleWords == null || moduleWords.isEmpty()) {
            return new ArrayList<>();
        }
        long lineStart = Math.max(0L, moduleLineStartMs);
        long lineDuration = resolveEffectiveLineDurationMs(lineStart, moduleLineEndMs, moduleWords);

        ArrayList<EnhancedLRCParser.WordTimestamp> sentenceRelative =
            normalizeModuleWordsToSentenceRelative(moduleWords, lineStart, lineDuration);
        if (sentenceRelative.isEmpty()) {
            return sentenceRelative;
        }
        lineDuration = resolveEffectiveLineDurationMs(lineStart, moduleLineEndMs, sentenceRelative);

        String text = lineText != null ? lineText.trim() : "";
        if (text.isEmpty()) {
            ensureMonotonicWordEnds(sentenceRelative);
            return sentenceRelative;
        }

        ArrayList<EnhancedLRCParser.WordTimestamp> display;
        if (text.length() == sentenceRelative.size()
            && canSkipCharMapping(text, sentenceRelative)) {
            display = expandMultiCharTokensToSingleChar(text, sentenceRelative);
        } else if (canSkipCharMapping(text, sentenceRelative)) {
            display = copyList(sentenceRelative);
        } else {
            display = mapToLineText(text, sentenceRelative);
        }
        clampTimelineToLineDuration(display, lineDuration);
        ensureMonotonicWordEnds(display);
        return display;
    }

    /** @deprecated 使用 {@link #buildSingleLineDisplayTimeline} */
    static ArrayList<EnhancedLRCParser.WordTimestamp> alignForSingleLineFallback(
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> source
    ) {
        return buildSingleLineDisplayTimeline(lineText, source, 0L, 0L);
    }

    /**
     * 模块逐字轴 → 句内 0 基：行内相对或整曲绝对（减行起点），供单句模式高亮。
     */
    private static ArrayList<EnhancedLRCParser.WordTimestamp> normalizeModuleWordsToSentenceRelative(
        List<EnhancedLRCParser.WordTimestamp> moduleWords,
        long lineStartMs,
        long lineDurationMs
    ) {
        if (moduleWords == null || moduleWords.isEmpty()) {
            return new ArrayList<>();
        }
        long minStart = minWordStartMs(moduleWords);
        boolean sentenceRelative = usesSentenceRelativeTimeline(moduleWords);
        long anchor;
        if (sentenceRelative) {
            anchor = minStart;
        } else if (lineStartMs > 0L) {
            anchor = lineStartMs;
        } else {
            anchor = minStart;
        }

        ArrayList<EnhancedLRCParser.WordTimestamp> out = new ArrayList<>(moduleWords.size());
        for (EnhancedLRCParser.WordTimestamp word : moduleWords) {
            if (word == null) {
                continue;
            }
            long relStart = Math.max(0L, word.startTime - anchor);
            long relEnd = Math.max(relStart + 1L, word.endTime - anchor);
            out.add(new EnhancedLRCParser.WordTimestamp(word.word, relStart, relEnd));
        }
        if (lineDurationMs > 0L) {
            clampTimelineToLineDuration(out, lineDurationMs);
        }
        return out;
    }

    /** 多字 {@link SuperLyricWord} 拆成与行文本等长的一字一戳。 */
    private static ArrayList<EnhancedLRCParser.WordTimestamp> expandMultiCharTokensToSingleChar(
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> tokens
    ) {
        if (lineText == null || lineText.isEmpty() || tokens == null || tokens.isEmpty()) {
            return copyList(tokens);
        }
        if (lineText.length() == tokens.size()) {
            boolean allSingleChar = true;
            for (EnhancedLRCParser.WordTimestamp word : tokens) {
                if (word != null && word.word != null && word.word.length() > 1) {
                    allSingleChar = false;
                    break;
                }
            }
            if (allSingleChar) {
                return copyList(tokens);
            }
        }
        return mapToLineText(lineText, tokens);
    }

    private static void clampTimelineToLineDuration(List<EnhancedLRCParser.WordTimestamp> words,
                                                  long lineDurationMs) {
        if (words == null || words.isEmpty() || lineDurationMs <= 0L) {
            return;
        }
        long naturalSpan = timelineSpanMs(words);
        if (naturalSpan > 0L && lineDurationMs < (long) (naturalSpan * LINE_DURATION_TRUST_WORD_SPAN_RATIO)) {
            return;
        }
        int lastIdx = indexOfLastMeaningfulChar(words);
        if (lastIdx < 0) {
            return;
        }
        EnhancedLRCParser.WordTimestamp last = words.get(lastIdx);
        if (last == null) {
            return;
        }
        long maxEnd = lineDurationMs;
        if (last.endTime > maxEnd) {
            long shift = last.endTime - maxEnd;
            for (EnhancedLRCParser.WordTimestamp word : words) {
                if (word == null) {
                    continue;
                }
                word.startTime = Math.max(0L, word.startTime - shift);
                word.endTime = Math.max(word.startTime + 1L, word.endTime - shift);
            }
        }
        if (last.endTime < maxEnd && last.endTime > last.startTime) {
            last.endTime = Math.min(maxEnd, last.endTime + Math.min(120L, maxEnd - last.endTime));
        }
    }

    /**
     * 智能切换融合：网络行时间负责换行/滚动，逐字高亮锚点优先模块 {@code lineStartMs}，
     * 避免 {@link #alignToLineTime} 把已是整曲绝对时间的轴重挂到偏晚的网络 LRC 行上。
     */
    static long resolveFusionAnchorMs(long networkLineMs,
                                      long moduleLineStartMs,
                                      List<EnhancedLRCParser.WordTimestamp> source) {
        long network = Math.max(0L, networkLineMs);
        long module = Math.max(0L, moduleLineStartMs);
        if (module <= 0L) {
            return network;
        }
        if (network <= 0L) {
            return module;
        }
        if (module + FUSION_NETWORK_LINE_LAG_MS < network) {
            return module;
        }
        long minWordStart = minWordStartMs(source);
        if (minWordStart >= ABSOLUTE_SONG_TIME_HINT_MS
            && minWordStart + FUSION_NETWORK_LINE_LAG_MS < network
            && Math.abs(minWordStart - module) < 2_000L) {
            return minWordStart;
        }
        return network;
    }

    static boolean shouldUseModuleTimelineForFusion(long networkLineMs,
                                                    long moduleLineStartMs,
                                                    List<EnhancedLRCParser.WordTimestamp> source) {
        long network = Math.max(0L, networkLineMs);
        long module = Math.max(0L, moduleLineStartMs);
        if (module <= 0L || network <= 0L) {
            return false;
        }
        if (module + FUSION_NETWORK_LINE_LAG_MS < network) {
            return true;
        }
        long minWordStart = minWordStartMs(source);
        return minWordStart > 0L
            && minWordStart + FUSION_NETWORK_LINE_LAG_MS < network
            && Math.abs(minWordStart - module) < 2_000L;
    }

    /**
     * 判断融合后的逐字轴是否相对已有数据发生实质变化（用于同句高频刷新，避免只比较首尾导致中间字滞后）。
     */
    static boolean hasMaterialWordTimingChange(
        List<EnhancedLRCParser.WordTimestamp> previous,
        List<EnhancedLRCParser.WordTimestamp> next
    ) {
        if (next == null || next.isEmpty()) {
            return false;
        }
        if (previous == null || previous.isEmpty()) {
            return true;
        }
        if (previous.size() != next.size()) {
            return true;
        }
        for (int i = 0; i < previous.size(); i++) {
            EnhancedLRCParser.WordTimestamp prev = previous.get(i);
            EnhancedLRCParser.WordTimestamp neu = next.get(i);
            if (prev == null || neu == null) {
                return true;
            }
            if (!safeWordTextEquals(prev.word, neu.word)) {
                return true;
            }
            if (Math.abs(prev.startTime - neu.startTime) > MATERIAL_WORD_TIMING_DELTA_MS
                || Math.abs(prev.endTime - neu.endTime) > MATERIAL_WORD_TIMING_DELTA_MS) {
                return true;
            }
        }
        return false;
    }

    private static boolean safeWordTextEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    static ArrayList<EnhancedLRCParser.WordTimestamp> alignToLineTime(
        long lineTimeMs,
        List<EnhancedLRCParser.WordTimestamp> source
    ) {
        ArrayList<EnhancedLRCParser.WordTimestamp> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        long lineTime = Math.max(0L, lineTimeMs);
        long base = Long.MAX_VALUE;
        for (EnhancedLRCParser.WordTimestamp word : source) {
            if (word == null) {
                continue;
            }
            base = Math.min(base, Math.max(0L, word.startTime));
        }
        if (base == Long.MAX_VALUE) {
            base = 0L;
        }
        for (EnhancedLRCParser.WordTimestamp word : source) {
            if (word == null) {
                continue;
            }
            long relStart = Math.max(0L, word.startTime - base);
            long relEnd = Math.max(relStart, word.endTime - base);
            if (relEnd <= relStart) {
                relEnd = relStart + DEFAULT_WORD_DURATION_MS;
            }
            out.add(new EnhancedLRCParser.WordTimestamp(
                word.word,
                lineTime + relStart,
                lineTime + relEnd
            ));
        }
        return out;
    }

    /**
     * 单句兜底已对齐的整曲绝对毫秒轴，平移到网络 LRC 行时间（升级时保持逐字进度连续）。
     */
    static ArrayList<EnhancedLRCParser.WordTimestamp> reanchorAlignedWordsToLineTime(
        List<EnhancedLRCParser.WordTimestamp> alignedWords,
        long fromLineTimeMs,
        long toLineTimeMs
    ) {
        ArrayList<EnhancedLRCParser.WordTimestamp> out = new ArrayList<>();
        if (alignedWords == null || alignedWords.isEmpty()) {
            return out;
        }
        long delta = Math.max(0L, toLineTimeMs) - Math.max(0L, fromLineTimeMs);
        for (EnhancedLRCParser.WordTimestamp word : alignedWords) {
            if (word == null) {
                continue;
            }
            long start = Math.max(0L, word.startTime + delta);
            long end = Math.max(start + 1L, word.endTime + delta);
            out.add(new EnhancedLRCParser.WordTimestamp(word.word, start, end));
        }
        return out;
    }

    /**
     * 智能切换融合：在已对齐行时间的基础上，把逐字轴挂到网络行 {@code lineText} 上。
     * 网络行中的标点/空白保留，时间沿用相邻字或句内插值。
     */
    static ArrayList<EnhancedLRCParser.WordTimestamp> mapToLineText(
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> timeAligned
    ) {
        if (lineText == null || lineText.isEmpty() || timeAligned == null || timeAligned.isEmpty()) {
            return copyList(timeAligned);
        }
        List<CharTiming> superChars = expandToCharTimings(timeAligned);
        if (superChars.isEmpty()) {
            return copyList(timeAligned);
        }

        ArrayList<EnhancedLRCParser.WordTimestamp> mapped = new ArrayList<>(lineText.length());
        int superIndex = 0;
        long lastEnd = superChars.get(0).start;
        int comparableInLine = 0;
        int matchedComparable = 0;

        for (int i = 0; i < lineText.length(); i++) {
            char lineChar = lineText.charAt(i);
            if (isIgnorableLineChar(lineChar)) {
                long start = lastEnd;
                long end = Math.max(start + 1L, peekNextSuperStart(superChars, superIndex, lastEnd));
                mapped.add(new EnhancedLRCParser.WordTimestamp(String.valueOf(lineChar), start, end));
                continue;
            }
            comparableInLine++;

            int consumed = tryConsumeSuperChar(lineChar, superChars, superIndex);
            if (consumed > 0) {
                CharTiming timing = superChars.get(superIndex + consumed - 1);
                mapped.add(new EnhancedLRCParser.WordTimestamp(
                    String.valueOf(lineChar),
                    timing.start,
                    Math.max(timing.start + 1L, timing.end)
                ));
                lastEnd = Math.max(lastEnd, timing.end);
                superIndex += consumed;
                matchedComparable++;
                continue;
            }

            long start = lastEnd;
            long end = start + DEFAULT_WORD_DURATION_MS;
            mapped.add(new EnhancedLRCParser.WordTimestamp(String.valueOf(lineChar), start, end));
            lastEnd = end;
        }

        if (comparableInLine == 0) {
            return copyList(timeAligned);
        }
        double ratio = (double) matchedComparable / (double) comparableInLine;
        if (ratio < MAP_MIN_MATCH_RATIO) {
            return copyList(timeAligned);
        }
        return mapped;
    }

    /** 先对齐行时间，再映射到网络行文本。 */
    static ArrayList<EnhancedLRCParser.WordTimestamp> alignAndMapToLineText(
        long lineTimeMs,
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> source
    ) {
        return alignAndMapToLineText(lineTimeMs, lineText, source, 0L, 0L);
    }

    /**
     * 对齐并映射到网络行文本；可选传入下一行时间用于句末小幅补长（见 {@link #padLastMeaningfulCharEnd}）。
     *
     * @param nextLineTimeMs 下一行 LRC 时间，≤0 则仅按最小时长补全
     */
    static ArrayList<EnhancedLRCParser.WordTimestamp> alignAndMapToLineText(
        long lineTimeMs,
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> source,
        long nextLineTimeMs
    ) {
        return alignAndMapToLineText(lineTimeMs, lineText, source, nextLineTimeMs, 0L);
    }

    /**
     * 对齐并映射到网络行文本。
     *
     * @param moduleLineStartMs SuperLyric 模块行起点；网络行偏晚时用于逐字锚点（见 {@link #resolveFusionAnchorMs}）
     */
    static ArrayList<EnhancedLRCParser.WordTimestamp> alignAndMapToLineText(
        long lineTimeMs,
        String lineText,
        List<EnhancedLRCParser.WordTimestamp> source,
        long nextLineTimeMs,
        long moduleLineStartMs
    ) {
        boolean moduleTimeline = shouldUseModuleTimelineForFusion(lineTimeMs, moduleLineStartMs, source);
        long wordAnchorMs = resolveFusionAnchorMs(lineTimeMs, moduleLineStartMs, source);
        ArrayList<EnhancedLRCParser.WordTimestamp> aligned = moduleTimeline
            ? copyList(source)
            : alignToLineTime(wordAnchorMs, source);
        if (lineText == null || lineText.trim().isEmpty()) {
            ensureMonotonicWordEnds(aligned);
            return aligned;
        }
        // 与 SuperLyric 单句兜底一致：文本已对齐时仅挂行时间，不拉伸、不句末补长。
        if (canSkipCharMapping(lineText, aligned)) {
            ensureMonotonicWordEnds(aligned);
            return aligned;
        }
        ArrayList<EnhancedLRCParser.WordTimestamp> mapped = mapToLineText(lineText, aligned);
        if (!moduleTimeline) {
            stretchWordTimelineTowardNextLine(mapped, lineTimeMs, nextLineTimeMs);
        }
        padLastMeaningfulCharEnd(mapped, nextLineTimeMs);
        ensureMonotonicWordEnds(mapped);
        return mapped;
    }

    /**
     * SuperLyric 逐字轴偏短于网络 LRC 行间隙时，按字序等比微拉伸，避免前半句很快走完而歌声仍在唱。
     */
    static void stretchWordTimelineTowardNextLine(
        List<EnhancedLRCParser.WordTimestamp> words,
        long lineTimeMs,
        long nextLineTimeMs
    ) {
        if (words == null || words.isEmpty() || nextLineTimeMs <= 0L) {
            return;
        }
        long anchor = lineTimeMs > 0L ? lineTimeMs : words.get(0).startTime;
        long firstStart = Long.MAX_VALUE;
        for (EnhancedLRCParser.WordTimestamp word : words) {
            if (word == null) {
                continue;
            }
            firstStart = Math.min(firstStart, Math.max(0L, word.startTime));
        }
        if (firstStart == Long.MAX_VALUE) {
            firstStart = anchor;
        }
        int lastIdx = indexOfLastMeaningfulChar(words);
        if (lastIdx < 0) {
            return;
        }
        EnhancedLRCParser.WordTimestamp last = words.get(lastIdx);
        if (last == null) {
            return;
        }
        long lastEnd = Math.max(last.endTime, last.startTime + 1L);
        long lrcSpan = nextLineTimeMs - Math.max(anchor, firstStart);
        long sungSpan = lastEnd - firstStart;
        if (lrcSpan < 480L || sungSpan < 120L) {
            return;
        }
        if (sungSpan >= lrcSpan * LINE_TIMELINE_MIN_FILL_RATIO) {
            return;
        }
        long targetEnd = Math.max(anchor, firstStart) + (long) (lrcSpan * LINE_TIMELINE_MAX_FILL_RATIO);
        targetEnd = Math.min(targetEnd, nextLineTimeMs - 96L);
        if (targetEnd <= lastEnd + 80L) {
            return;
        }
        float scale = (float) (targetEnd - firstStart) / (float) sungSpan;
        if (scale <= 1.02f) {
            return;
        }
        for (EnhancedLRCParser.WordTimestamp word : words) {
            if (word == null) {
                continue;
            }
            long relStart = Math.max(0L, word.startTime - firstStart);
            long relEnd = Math.max(relStart + 1L, word.endTime - firstStart);
            word.startTime = firstStart + (long) (relStart * scale);
            word.endTime = firstStart + (long) (relEnd * scale);
            if (word.endTime <= word.startTime) {
                word.endTime = word.startTime + 1L;
            }
        }
    }

    /**
     * 仅对句末最后一个可唱字做有限补长：保证最短演唱时长，且不超过 SuperLyric 原结束时间 + {@link #LAST_CHAR_MAX_PAD_MS}，
     * 并不得越过下一行 LRC 前缘。避免把结束时间拉到整行末尾导致逐字进度一次铺满。
     */
    static void padLastMeaningfulCharEnd(List<EnhancedLRCParser.WordTimestamp> words, long nextLineTimeMs) {
        if (words == null || words.isEmpty()) {
            return;
        }
        int lastMeaningful = -1;
        for (int i = words.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.WordTimestamp word = words.get(i);
            if (word == null || word.word == null || word.word.isEmpty()) {
                continue;
            }
            char ch = word.word.charAt(word.word.length() - 1);
            if (!isIgnorableLineChar(ch)) {
                lastMeaningful = i;
                break;
            }
        }
        if (lastMeaningful < 0) {
            lastMeaningful = words.size() - 1;
        }
        EnhancedLRCParser.WordTimestamp last = words.get(lastMeaningful);
        if (last == null) {
            return;
        }
        long naturalEnd = Math.max(last.endTime, last.startTime + 1L);
        long minEnd = last.startTime + LAST_CHAR_MIN_DURATION_MS;
        long paddedEnd = naturalEnd + LAST_CHAR_MAX_PAD_MS;
        if (nextLineTimeMs > naturalEnd + 160L) {
            long gap = nextLineTimeMs - naturalEnd;
            long gapStretch = (long) (gap * LAST_CHAR_GAP_FILL_RATIO);
            gapStretch = Math.min(gapStretch, gap - 96L);
            gapStretch = Math.max(0L, gapStretch);
            paddedEnd = Math.max(paddedEnd, naturalEnd + gapStretch);
        }
        if (nextLineTimeMs > last.startTime + 120L) {
            paddedEnd = Math.min(paddedEnd, nextLineTimeMs - 80L);
        }
        paddedEnd = Math.max(naturalEnd, paddedEnd);
        last.endTime = Math.max(minEnd, paddedEnd);
    }

    static void ensureMonotonicWordEnds(List<EnhancedLRCParser.WordTimestamp> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        long prevEnd = 0L;
        for (EnhancedLRCParser.WordTimestamp word : words) {
            if (word == null) {
                continue;
            }
            if (word.startTime < prevEnd) {
                word.startTime = prevEnd;
            }
            if (word.endTime <= word.startTime) {
                word.endTime = word.startTime + 1L;
            }
            prevEnd = word.endTime;
        }
    }

    static int indexOfLastMeaningfulChar(List<EnhancedLRCParser.WordTimestamp> words) {
        if (words == null || words.isEmpty()) {
            return -1;
        }
        for (int i = words.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.WordTimestamp word = words.get(i);
            if (word == null || word.word == null || word.word.isEmpty()) {
                continue;
            }
            char ch = word.word.charAt(word.word.length() - 1);
            if (!isIgnorableLineChar(ch)) {
                return i;
            }
        }
        return words.size() - 1;
    }

    /**
     * 逐字拼接与网络行归一化后一致时，跳过逐字符映射（常见同句二次回调）。
     */
    static boolean canSkipCharMapping(String lineText, List<EnhancedLRCParser.WordTimestamp> timeAligned) {
        if (lineText == null || timeAligned == null || timeAligned.isEmpty()) {
            return false;
        }
        String fromWords = MixedLyricsLineMatcher.concatWordText(timeAligned);
        if (fromWords.isEmpty()) {
            return false;
        }
        return LyricsMatcher.normalize(lineText).equals(LyricsMatcher.normalize(fromWords));
    }

    /**
     * 仅 SuperLyric 单句：行锚点应对齐模块 {@code lineStartMs}，与句内 0 基逐字轴一致。
     */
    static long resolveSingleLineSentenceAnchorMs(long payloadLineStartMs, long currentPlaybackMs) {
        long payloadStart = Math.max(0L, payloadLineStartMs);
        long playback = Math.max(0L, currentPlaybackMs);
        if (payloadStart > 0L) {
            if (playback <= 0L) {
                return payloadStart;
            }
            if (playback >= payloadStart && playback - payloadStart < 45_000L) {
                return payloadStart;
            }
            if (playback < payloadStart && payloadStart - playback < 1_500L) {
                return payloadStart;
            }
        }
        return playback > 0L ? playback : payloadStart;
    }

    private static final class CharTiming {
        final char ch;
        final long start;
        final long end;

        CharTiming(char ch, long start, long end) {
            this.ch = ch;
            this.start = start;
            this.end = end;
        }
    }

    private static List<CharTiming> expandToCharTimings(List<EnhancedLRCParser.WordTimestamp> words) {
        ArrayList<CharTiming> out = new ArrayList<>();
        for (EnhancedLRCParser.WordTimestamp word : words) {
            if (word == null || word.word == null) {
                continue;
            }
            String text = word.word;
            if (text.isEmpty()) {
                continue;
            }
            long start = Math.max(0L, word.startTime);
            long end = Math.max(start + 1L, word.endTime);
            if (text.length() == 1) {
                out.add(new CharTiming(text.charAt(0), start, end));
                continue;
            }
            long span = Math.max(1L, end - start);
            for (int i = 0; i < text.length(); i++) {
                long charStart = start + span * i / text.length();
                long charEnd = start + span * (i + 1) / text.length();
                if (charEnd <= charStart) {
                    charEnd = charStart + 1L;
                }
                out.add(new CharTiming(text.charAt(i), charStart, charEnd));
            }
        }
        return out;
    }

    /** 在 super 流上滑动匹配当前行字符，最多跳过 {@code maxSkip} 个 Super 字。 */
    private static int tryConsumeSuperChar(char lineChar,
                                           List<CharTiming> superChars,
                                           int superIndex) {
        final int maxSkip = 3;
        for (int skip = 0; skip <= maxSkip && superIndex + skip < superChars.size(); skip++) {
            if (charsMatch(lineChar, superChars.get(superIndex + skip).ch)) {
                return skip + 1;
            }
        }
        return 0;
    }

    private static long peekNextSuperStart(List<CharTiming> superChars, int superIndex, long fallback) {
        if (superIndex < superChars.size()) {
            return Math.max(fallback, superChars.get(superIndex).start);
        }
        return fallback + DEFAULT_WORD_DURATION_MS;
    }

    private static boolean charsMatch(char lineChar, char superChar) {
        if (lineChar == superChar) {
            return true;
        }
        if (lineChar < 128 && superChar < 128) {
            return Character.toLowerCase(lineChar) == Character.toLowerCase(superChar);
        }
        return false;
    }

    static boolean isIgnorableLineChar(char c) {
        if (Character.isWhitespace(c)) {
            return true;
        }
        switch (c) {
            case '，': case '。': case '、': case '；': case '：': case '？': case '！':
            case '"': case '\u201c': case '\u201d': case '\'':
            case ',': case '.': case ';': case ':': case '?': case '!':
            case '（': case '）': case '(': case ')':
            case '《': case '》': case '【': case '】': case '[': case ']':
            case '…': case '—': case '-': case '~': case '·':
            case '「': case '」': case '『': case '』':
                return true;
            default:
                return false;
        }
    }

    private static long minWordStartMs(List<EnhancedLRCParser.WordTimestamp> source) {
        if (source == null || source.isEmpty()) {
            return 0L;
        }
        long min = Long.MAX_VALUE;
        for (EnhancedLRCParser.WordTimestamp word : source) {
            if (word == null) {
                continue;
            }
            min = Math.min(min, Math.max(0L, word.startTime));
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    private static long maxWordEndMs(List<EnhancedLRCParser.WordTimestamp> source) {
        if (source == null || source.isEmpty()) {
            return 0L;
        }
        long max = 0L;
        for (EnhancedLRCParser.WordTimestamp word : source) {
            if (word == null) {
                continue;
            }
            max = Math.max(max, Math.max(word.startTime + 1L, word.endTime));
        }
        return max;
    }

    private static ArrayList<EnhancedLRCParser.WordTimestamp> copyList(List<EnhancedLRCParser.WordTimestamp> source) {
        ArrayList<EnhancedLRCParser.WordTimestamp> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (EnhancedLRCParser.WordTimestamp word : source) {
            if (word == null) {
                continue;
            }
            out.add(new EnhancedLRCParser.WordTimestamp(word.word, word.startTime, word.endTime));
        }
        return out;
    }
}
