package com.wmqc.miroot.lyrics;

import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 SuperLyricApi 3.4 {@link SuperLyricData} / {@link SuperLyricLine} / {@link SuperLyricWord} 解析逐字时间轴。
 * 访问可选字段前使用 {@code hasXxx()}；逐字显示见 {@link SuperLyricWordTimestamps#buildSingleLineDisplayTimeline}。
 *
 * @see <a href="https://github.com/HChenX/SuperLyricApi">SuperLyricApi</a>
 */
final class SuperLyricPayloadParser {
    private static final long DEFAULT_WORD_DURATION_MS = 220L;
    /** 行内相对时间通常远小于整曲绝对时间（毫秒）。 */
    private static final long ABSOLUTE_SONG_TIME_HINT_MS = 120_000L;

    private SuperLyricPayloadParser() {
    }

    static SuperLyricApi.SuperLyricFallbackPayload buildFallbackPayload(SuperLyricData data) {
        if (data == null) {
            return null;
        }
        SuperLyricLine line = pickBestLyricLineFast(data);
        if (line == null) {
            return null;
        }

        long lineStart = resolveLineStartMs(line);
        long lineEnd = resolveLineEndMs(line, lineStart);
        String text = resolveLineText(line);
        if (isEmpty(text)) {
            return null;
        }

        SuperLyricWord[] words = line.getWords();
        List<EnhancedLRCParser.WordTimestamp> moduleWords =
            buildWordTimestamps(words, lineStart, lineEnd);
        return new SuperLyricApi.SuperLyricFallbackPayload(text, lineStart, lineEnd, moduleWords);
    }

    /**
     * 实时回调快路径：主歌词行已带逐字则直接采用，避免对副/翻译通道全量打分。
     */
    static SuperLyricLine pickBestLyricLineFast(SuperLyricData data) {
        if (data == null) {
            return null;
        }
        if (data.hasLyric()) {
            SuperLyricLine primary = data.getLyric();
            if (primary != null && hasTimedWords(primary)) {
                return primary;
            }
        }
        return pickBestLyricLine(data);
    }

    /**
     * 优先含逐字数据的行；同分时主歌词 &gt; 副歌词 &gt; 翻译。
     */
    static SuperLyricLine pickBestLyricLine(SuperLyricData data) {
        SuperLyricLine best = null;
        int bestScore = Integer.MIN_VALUE;
        if (data.hasLyric()) {
            int s = scoreLyricLine(data.getLyric(), 300);
            if (s > bestScore) {
                bestScore = s;
                best = data.getLyric();
            }
        }
        if (data.hasSecondary()) {
            int s = scoreLyricLine(data.getSecondary(), 200);
            if (s > bestScore) {
                bestScore = s;
                best = data.getSecondary();
            }
        }
        if (data.hasTranslation()) {
            int s = scoreLyricLine(data.getTranslation(), 100);
            if (s > bestScore) {
                best = data.getTranslation();
            }
        }
        return best;
    }

    private static boolean hasTimedWords(SuperLyricLine line) {
        if (line == null) {
            return false;
        }
        SuperLyricWord[] words = line.getWords();
        if (words == null || words.length == 0) {
            return false;
        }
        for (SuperLyricWord word : words) {
            if (word != null && hasOfficialWordTiming(word)) {
                return true;
            }
        }
        return false;
    }

    private static int scoreLyricLine(SuperLyricLine line, int channelBias) {
        if (line == null) {
            return Integer.MIN_VALUE;
        }
        SuperLyricWord[] words = line.getWords();
        int wordCount = words != null ? words.length : 0;
        int timedWordCount = 0;
        if (words != null) {
            for (SuperLyricWord word : words) {
                if (word != null && hasOfficialWordTiming(word)) {
                    timedWordCount++;
                }
            }
        }
        String text = resolveLineText(line);
        int textLen = text != null ? text.length() : 0;
        if (wordCount == 0 && textLen == 0) {
            return Integer.MIN_VALUE;
        }
        return channelBias + timedWordCount * 1_000 + wordCount * 100 + textLen;
    }

    static String resolveLineText(SuperLyricLine line) {
        if (line == null) {
            return "";
        }
        String text = safeTrim(line.getText());
        if (!isEmpty(text)) {
            return text;
        }
        return buildTextFromWords(line.getWords());
    }

    static long resolveLineStartMs(SuperLyricLine line) {
        if (line == null) {
            return 0L;
        }
        return Math.max(0L, line.getStartTime());
    }

    static long resolveLineEndMs(SuperLyricLine line, long lineStartMs) {
        if (line == null) {
            return lineStartMs;
        }
        long end = Math.max(0L, line.getEndTime());
        if (end > lineStartMs) {
            return end;
        }
        long delay = line.getDelay();
        if (delay > 0L) {
            return lineStartMs + delay;
        }
        return lineStartMs;
    }

    /**
     * 将 {@link SuperLyricWord#getWord()}/{@link SuperLyricWord#getStartTime()}/{@link SuperLyricWord#getEndTime()}
     * 转为模块逐字轴（行内相对则加 {@code lineStartMs}，已是整曲绝对时间则保持）。
     */
    static List<EnhancedLRCParser.WordTimestamp> buildWordTimestamps(SuperLyricWord[] words,
                                                                     long lineStartMs,
                                                                     long lineEndMs) {
        if (words == null || words.length == 0) {
            return new ArrayList<>();
        }

        int capacity = Math.max(8, words.length);
        ArrayList<RelativeWord> relative = new ArrayList<>(capacity);
        long relativeCursor = 0L;
        long maxEnd = 0L;
        for (SuperLyricWord word : words) {
            if (word == null) {
                continue;
            }
            String token = safeTrim(word.getWord());
            if (isEmpty(token)) {
                continue;
            }
            RelativeWord parsed = parseWordTiming(word, relativeCursor);
            relative.add(parsed);
            relativeCursor = parsed.endMs;
            maxEnd = Math.max(maxEnd, parsed.endMs);
        }
        if (relative.isEmpty()) {
            return new ArrayList<>();
        }

        boolean treatAsRelative = shouldTreatWordsAsLineRelative(maxEnd, lineStartMs, lineEndMs);
        long anchor = Math.max(0L, lineStartMs);
        ArrayList<EnhancedLRCParser.WordTimestamp> out = new ArrayList<>(relative.size());
        for (RelativeWord rw : relative) {
            long startAbs = treatAsRelative ? anchor + rw.startMs : rw.startMs;
            long endAbs = treatAsRelative ? anchor + rw.endMs : rw.endMs;
            if (endAbs <= startAbs) {
                endAbs = startAbs + DEFAULT_WORD_DURATION_MS;
            }
            out.add(new EnhancedLRCParser.WordTimestamp(rw.text, startAbs, endAbs));
        }
        return out;
    }

    /**
     * 官方 3.4：{@link SuperLyricWord}(word, startTime, endTime)；兼容旧版仅 delay。
     */
    private static RelativeWord parseWordTiming(SuperLyricWord word, long relativeCursor) {
        long start = Math.max(0L, word.getStartTime());
        long end = Math.max(0L, word.getEndTime());
        long delay = word.getDelay();

        if (start == 0L && end == 0L && delay > 0L) {
            long endRel = relativeCursor + delay;
            return new RelativeWord(safeTrim(word.getWord()), relativeCursor, endRel);
        }

        if (end <= start) {
            if (delay > 0L) {
                end = start + delay;
            } else if (start > 0L) {
                end = start + DEFAULT_WORD_DURATION_MS;
            } else {
                end = relativeCursor + DEFAULT_WORD_DURATION_MS;
                start = relativeCursor;
            }
        }

        if (start == 0L && end == 0L) {
            end = relativeCursor + DEFAULT_WORD_DURATION_MS;
            start = relativeCursor;
        }

        return new RelativeWord(safeTrim(word.getWord()), start, end);
    }

    private static boolean hasOfficialWordTiming(SuperLyricWord word) {
        if (word == null) {
            return false;
        }
        if (word.getEndTime() > word.getStartTime()) {
            return true;
        }
        if (word.getStartTime() > 0L) {
            return true;
        }
        return word.getDelay() > 0L;
    }

    private static boolean shouldTreatWordsAsLineRelative(long maxEnd,
                                                          long lineStartMs,
                                                          long lineEndMs) {
        long lineDuration = lineEndMs > lineStartMs ? lineEndMs - lineStartMs : 0L;

        if (lineDuration > 0L && maxEnd <= lineDuration + 2_000L) {
            return true;
        }

        if (lineStartMs >= ABSOLUTE_SONG_TIME_HINT_MS && maxEnd < lineStartMs) {
            return true;
        }

        if (lineStartMs == 0L && maxEnd < ABSOLUTE_SONG_TIME_HINT_MS) {
            return true;
        }

        return false;
    }

    private static String buildTextFromWords(SuperLyricWord[] words) {
        if (words == null || words.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SuperLyricWord word : words) {
            if (word == null) {
                continue;
            }
            String token = safeTrim(word.getWord());
            if (!isEmpty(token)) {
                sb.append(token);
            }
        }
        return sb.toString().trim();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class RelativeWord {
        final String text;
        final long startMs;
        final long endMs;

        RelativeWord(String text, long startMs, long endMs) {
            this.text = text;
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs, endMs);
        }
    }
}
