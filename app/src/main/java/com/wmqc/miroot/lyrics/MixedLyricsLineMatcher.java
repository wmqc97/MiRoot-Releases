package com.wmqc.miroot.lyrics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 网络 API · 逐字融合（MIXED）：将 SuperLyric 逐字时间轴对齐到网络 API 完整 LRC 的某一行。
 * <p>
 * 原则：结构（多行文本 + 行时间）以网络歌词为准；仅当文本比对通过后才写入 wordTimestamps。
 */
public final class MixedLyricsLineMatcher {
    private static final String TAG = "MixedLyricsLineMatcher";

    /** 以播放进度锚点向两侧搜索的最大行数。 */
    private static final int SEARCH_RADIUS = 4;
    /** SuperLyric 行起始时间与 LRC 行时间的最大可接受偏差（用于加权，非硬拒绝）。 */
    private static final long TIME_ALIGN_SOFT_MS = 8_000L;
    private static final long TIME_ALIGN_TIGHT_MS = 2_500L;

    private static final double SIMILARITY_EXACT = 0.97;
    private static final double SIMILARITY_STRONG = 0.82;
    private static final double SIMILARITY_WEAK = 0.68;
    private static final double WORD_COVERAGE_STRONG = 0.85;
    private static final double WORD_COVERAGE_WEAK = 0.72;
    private static final double COMPOSITE_ACCEPT = 0.72;

    private static final double SIMILARITY_EXACT_STRICTEST = 0.98;
    private static final double SIMILARITY_STRONG_STRICTEST = 0.90;
    private static final double WORD_COVERAGE_STRONG_STRICTEST = 0.92;
    private static final double COMPOSITE_ACCEPT_STRICTEST = 0.85;

    private MixedLyricsLineMatcher() {
    }

    public enum MatchTier {
        EXACT,
        STRONG,
        WEAK,
        REJECTED
    }

    public static final class MatchResult {
        public final int lineIndex;
        public final double score;
        public final double textSimilarity;
        public final double wordCoverage;
        public final MatchTier tier;
        public final boolean accepted;
        public final String reason;

        MatchResult(int lineIndex,
                    double score,
                    double textSimilarity,
                    double wordCoverage,
                    MatchTier tier,
                    boolean accepted,
                    String reason) {
            this.lineIndex = lineIndex;
            this.score = score;
            this.textSimilarity = textSimilarity;
            this.wordCoverage = wordCoverage;
            this.tier = tier;
            this.accepted = accepted;
            this.reason = reason != null ? reason : "";
        }

        static MatchResult rejected(String reason) {
            return new MatchResult(-1, 0.0, 0.0, 0.0, MatchTier.REJECTED, false, reason);
        }
    }

    /**
     * @param lines              网络 API 解析出的完整歌词
     * @param superLineText      SuperLyric 当前句文本
     * @param superWords         SuperLyric 逐字时间戳（可为空）
     * @param superLineStartMs   SuperLyric 行起始时间（可能为句内相对时间）
     * @param playbackPositionMs 当前播放进度
     * @param anchorIndexHint    由播放进度推算的当前行索引（&lt;0 时内部重算）
     */
    public static MatchResult match(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                    String superLineText,
                                    List<EnhancedLRCParser.WordTimestamp> superWords,
                                    long superLineStartMs,
                                    long playbackPositionMs,
                                    int anchorIndexHint) {
        return match(
            lines,
            superLineText,
            superWords,
            superLineStartMs,
            playbackPositionMs,
            anchorIndexHint,
            MusicPlayerLyricsPolicy.LineMatchStrictness.DEFAULT
        );
    }

    public static MatchResult match(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                    String superLineText,
                                    List<EnhancedLRCParser.WordTimestamp> superWords,
                                    long superLineStartMs,
                                    long playbackPositionMs,
                                    int anchorIndexHint,
                                    MusicPlayerLyricsPolicy.LineMatchStrictness strictness) {
        final boolean strictest = strictness == MusicPlayerLyricsPolicy.LineMatchStrictness.STRICTEST;
        final double similarityExact = strictest ? SIMILARITY_EXACT_STRICTEST : SIMILARITY_EXACT;
        final double similarityStrong = strictest ? SIMILARITY_STRONG_STRICTEST : SIMILARITY_STRONG;
        final double wordCoverageStrong = strictest ? WORD_COVERAGE_STRONG_STRICTEST : WORD_COVERAGE_STRONG;
        final double compositeAccept = strictest ? COMPOSITE_ACCEPT_STRICTEST : COMPOSITE_ACCEPT;

        if (lines == null || lines.isEmpty()) {
            return MatchResult.rejected("无网络歌词结构");
        }
        String superText = resolveSuperLineText(superLineText, superWords);
        if (isEmpty(superText)) {
            return MatchResult.rejected("SuperLyric 文本为空");
        }

        int anchor = anchorIndexHint;
        if (anchor < 0 || anchor >= lines.size()) {
            anchor = findAnchorIndex(lines, playbackPositionMs);
        }
        if (anchor < 0) {
            anchor = 0;
        }

        Set<Integer> candidates = buildCandidateIndices(lines, anchor, superLineStartMs);
        MatchResult best = null;
        for (int index : candidates) {
            if (index < 0 || index >= lines.size()) continue;
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(index);
            if (line == null) continue;
            MatchResult candidate = scoreCandidate(
                index,
                line,
                superText,
                superWords,
                superLineStartMs,
                anchor,
                useAbsoluteTimeHeuristic(lines, superLineStartMs),
                strictest,
                similarityExact,
                similarityStrong,
                wordCoverageStrong,
                compositeAccept
            );
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }

        if (best != null && best.accepted) {
            return best;
        }

        // 全局精确兜底：汽水严格模式禁用，避免错句漂移
        if (!strictest) {
            MatchResult global = globalHighConfidenceMatch(lines, superText, superWords, superLineStartMs, anchor);
            if (global != null && global.accepted) {
                return global;
            }
        }

        if (best != null) {
            return MatchResult.rejected("最佳候选未达阈值: " + best.reason);
        }
        return MatchResult.rejected("无候选行");
    }

    private static String resolveSuperLineText(String superLineText,
                                               List<EnhancedLRCParser.WordTimestamp> superWords) {
        String text = superLineText != null ? superLineText.trim() : "";
        if (!isEmpty(text)) {
            return text;
        }
        return concatWordText(superWords);
    }

    static String concatWordText(List<EnhancedLRCParser.WordTimestamp> words) {
        if (words == null || words.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EnhancedLRCParser.WordTimestamp word : words) {
            if (word == null || isEmpty(word.word)) continue;
            sb.append(word.word.trim());
        }
        return sb.toString().trim();
    }

    private static Set<Integer> buildCandidateIndices(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                      int anchor,
                                                      long superLineStartMs) {
        Set<Integer> out = new LinkedHashSet<>();
        int from = Math.max(0, anchor - SEARCH_RADIUS);
        int to = Math.min(lines.size() - 1, anchor + SEARCH_RADIUS);
        for (int i = from; i <= to; i++) {
            out.add(i);
        }
        if (superLineStartMs > 0L && useAbsoluteTimeHeuristic(lines, superLineStartMs)) {
            for (int i = 0; i < lines.size(); i++) {
                EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
                if (line == null) continue;
                long delta = Math.abs(line.time - superLineStartMs);
                if (delta <= TIME_ALIGN_SOFT_MS) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    private static boolean useAbsoluteTimeHeuristic(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                    long superLineStartMs) {
        if (superLineStartMs <= 0L || lines == null || lines.isEmpty()) {
            return false;
        }
        // 句内相对时间通常远小于 60s；与 LRC 行时间量级接近才参与时间加权
        if (superLineStartMs < 60_000L) {
            for (EnhancedLRCParser.EnhancedLyricLine line : lines) {
                if (line == null) continue;
                if (Math.abs(line.time - superLineStartMs) <= TIME_ALIGN_SOFT_MS) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static int findAnchorIndex(List<EnhancedLRCParser.EnhancedLyricLine> lines, long playbackPositionMs) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        long position = Math.max(0L, playbackPositionMs);
        if (position <= 0L) {
            return 0;
        }
        int lastIndex = lines.size() - 1;
        if (position >= lines.get(lastIndex).time) {
            return lastIndex;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
            if (line == null) continue;
            long end = i + 1 < lines.size() ? lines.get(i + 1).time : line.time + 3_000L;
            if (position >= line.time && position < end) {
                return i;
            }
        }
        return 0;
    }

    private static MatchResult scoreCandidate(int index,
                                              EnhancedLRCParser.EnhancedLyricLine line,
                                              String superText,
                                              List<EnhancedLRCParser.WordTimestamp> superWords,
                                              long superLineStartMs,
                                              int anchorIndex,
                                              boolean absoluteTime,
                                              boolean strictest,
                                              double similarityExact,
                                              double similarityStrong,
                                              double wordCoverageStrong,
                                              double compositeAccept) {
        String lineText = line.text != null ? line.text.trim() : "";
        String normSuper = LyricsMatcher.normalize(superText);
        String normLine = LyricsMatcher.normalize(lineText);

        double textSim = LyricsMatcher.similarity(superText, lineText);
        double wordCov = wordCoverage(superWords, superText, lineText);
        boolean exactNorm = !normSuper.isEmpty() && normSuper.equals(normLine);
        boolean mutualContains = containsNormalized(normSuper, normLine);

        double timeScore = 0.0;
        if (absoluteTime && superLineStartMs > 0L) {
            long delta = Math.abs(line.time - superLineStartMs);
            if (delta <= TIME_ALIGN_TIGHT_MS) {
                timeScore = 1.0;
            } else if (delta <= TIME_ALIGN_SOFT_MS) {
                timeScore = 1.0 - (double) (delta - TIME_ALIGN_TIGHT_MS) / (TIME_ALIGN_SOFT_MS - TIME_ALIGN_TIGHT_MS);
            }
        }

        int anchorDistance = Math.abs(index - anchorIndex);
        double positionScore = Math.max(0.0, 1.0 - anchorDistance / (double) (SEARCH_RADIUS + 1));

        double composite = textSim * 0.62 + wordCov * 0.28 + timeScore * 0.07 + positionScore * 0.03;

        MatchTier tier = MatchTier.REJECTED;
        String reason;

        if (exactNorm || textSim >= similarityExact) {
            tier = MatchTier.EXACT;
            reason = "exact";
        } else if (!strictest
            && wordCov >= 0.90
            && anchorDistance <= 2
            && (mutualContains || textSim >= 0.55)) {
            // 酷狗等：SuperLyric 逐字拼接与网络行高度一致时，允许标点/空格差异
            tier = MatchTier.STRONG;
            reason = String.format(Locale.US, "wordAnchor sim=%.2f word=%.2f", textSim, wordCov);
        } else if (textSim >= similarityStrong
            && (wordCov >= wordCoverageStrong || mutualContains)
            && (strictest
                ? (timeScore > 0.35 && anchorDistance <= 1)
                : (timeScore > 0.2 || anchorDistance <= 1))) {
            tier = MatchTier.STRONG;
            reason = String.format(Locale.US, "strong sim=%.2f word=%.2f", textSim, wordCov);
        } else if (!strictest
            && textSim >= SIMILARITY_WEAK
            && wordCov >= WORD_COVERAGE_WEAK
            && (timeScore >= 0.35 || anchorDistance <= 2)) {
            tier = MatchTier.WEAK;
            reason = String.format(Locale.US, "weak sim=%.2f word=%.2f", textSim, wordCov);
        } else {
            reason = String.format(Locale.US, "reject sim=%.2f word=%.2f time=%.2f dist=%d",
                textSim, wordCov, timeScore, anchorDistance);
        }

        boolean accepted = tier != MatchTier.REJECTED && composite >= compositeAccept;
        if (tier == MatchTier.WEAK && textSim < 0.72) {
            accepted = false;
            reason = reason + " (weak+lowSim)";
        }

        return new MatchResult(index, composite, textSim, wordCov, tier, accepted, reason);
    }

    private static MatchResult globalHighConfidenceMatch(List<EnhancedLRCParser.EnhancedLyricLine> lines,
                                                         String superText,
                                                         List<EnhancedLRCParser.WordTimestamp> superWords,
                                                         long superLineStartMs,
                                                         int anchorIndex) {
        int bestIndex = -1;
        double bestSim = 0.0;
        for (int i = 0; i < lines.size(); i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lines.get(i);
            if (line == null) continue;
            double sim = LyricsMatcher.similarity(superText, line.text);
            if (sim > bestSim) {
                bestSim = sim;
                bestIndex = i;
            }
        }
        if (bestIndex < 0 || bestSim < SIMILARITY_EXACT) {
            return null;
        }
        double wordCov = wordCoverage(superWords, superText, lines.get(bestIndex).text);
        if (wordCov < WORD_COVERAGE_STRONG) {
            return null;
        }
        // 全局命中必须与锚点相距不太远，避免错句
        if (Math.abs(bestIndex - anchorIndex) > SEARCH_RADIUS + 2) {
            return null;
        }
        return new MatchResult(
            bestIndex,
            bestSim,
            bestSim,
            wordCov,
            MatchTier.EXACT,
            true,
            String.format(Locale.US, "globalExact sim=%.2f", bestSim)
        );
    }

    static double wordCoverage(List<EnhancedLRCParser.WordTimestamp> words,
                               String superLineText,
                               String lineText) {
        String fromWords = LyricsMatcher.normalize(concatWordText(words));
        String fromLine = LyricsMatcher.normalize(lineText != null ? lineText : "");
        String fromSuper = LyricsMatcher.normalize(superLineText != null ? superLineText : "");

        String probe = !fromWords.isEmpty() ? fromWords : fromSuper;
        if (probe.isEmpty() || fromLine.isEmpty()) {
            return 0.0;
        }
        if (probe.equals(fromLine) || fromLine.contains(probe) || probe.contains(fromLine)) {
            return 1.0;
        }
        return LyricsMatcher.similarity(probe, fromLine);
    }

    private static boolean containsNormalized(String a, String b) {
        if (isEmpty(a) || isEmpty(b)) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
