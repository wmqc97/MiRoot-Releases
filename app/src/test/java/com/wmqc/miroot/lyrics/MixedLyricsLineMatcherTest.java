package com.wmqc.miroot.lyrics;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MixedLyricsLineMatcherTest {

    private static EnhancedLRCParser.EnhancedLyricLine line(long time, String text) {
        return new EnhancedLRCParser.EnhancedLyricLine(time, text);
    }

    @Test
    public void exactMatchAtAnchor() {
        List<EnhancedLRCParser.EnhancedLyricLine> lines = Arrays.asList(
            line(0, "第一句"),
            line(5000, "第二句歌词"),
            line(10000, "第三句")
        );
        MixedLyricsLineMatcher.MatchResult r = MixedLyricsLineMatcher.match(
            lines,
            "第二句歌词",
            null,
            5000L,
            6000L,
            1
        );
        assertTrue(r.accepted);
        assertEquals(1, r.lineIndex);
        assertEquals(MixedLyricsLineMatcher.MatchTier.EXACT, r.tier);
    }

    @Test
    public void rejectMismatchedLine() {
        List<EnhancedLRCParser.EnhancedLyricLine> lines = Arrays.asList(
            line(0, "完全不同的内容甲"),
            line(5000, "完全不同的内容乙"),
            line(10000, "完全不同的内容丙")
        );
        MixedLyricsLineMatcher.MatchResult r = MixedLyricsLineMatcher.match(
            lines,
            "SuperLyric推送的一句",
            null,
            5000L,
            6000L,
            1
        );
        assertFalse(r.accepted);
    }

    @Test
    public void wordCoverageFromTimestamps() {
        List<EnhancedLRCParser.WordTimestamp> words = new ArrayList<>();
        words.add(new EnhancedLRCParser.WordTimestamp("你", 0, 100));
        words.add(new EnhancedLRCParser.WordTimestamp("好", 100, 200));
        words.add(new EnhancedLRCParser.WordTimestamp("世界", 200, 400));
        double cov = MixedLyricsLineMatcher.wordCoverage(words, "", "你好世界");
        assertTrue(cov >= 0.99);
    }

    @Test
    public void wordCoverageAnchorMatchWithPunctuationDiff() {
        List<EnhancedLRCParser.EnhancedLyricLine> lines = Arrays.asList(
            line(0, "前奏"),
            line(5000, "我记得那一天，你转身说再见"),
            line(10000, "后奏")
        );
        List<EnhancedLRCParser.WordTimestamp> words = new ArrayList<>();
        words.add(new EnhancedLRCParser.WordTimestamp("我", 0, 100));
        words.add(new EnhancedLRCParser.WordTimestamp("记得", 100, 300));
        words.add(new EnhancedLRCParser.WordTimestamp("那一天", 300, 600));
        words.add(new EnhancedLRCParser.WordTimestamp("你转身说再见", 600, 1200));
        MixedLyricsLineMatcher.MatchResult r = MixedLyricsLineMatcher.match(
            lines,
            "我记得那一天你转身说再见",
            words,
            5100L,
            5500L,
            1
        );
        assertTrue(r.accepted);
        assertEquals(1, r.lineIndex);
    }

    @Test
    public void strictestRejectsWeakFuzzyMatch() {
        List<EnhancedLRCParser.EnhancedLyricLine> lines = Arrays.asList(
            line(0, "前奏"),
            line(4000, "我记得那一天"),
            line(9000, "你转身说再见")
        );
        MixedLyricsLineMatcher.MatchResult r = MixedLyricsLineMatcher.match(
            lines,
            "我记得那一天",
            Collections.emptyList(),
            4100L,
            4500L,
            1,
            MusicPlayerLyricsPolicy.LineMatchStrictness.STRICTEST
        );
        assertTrue(r.accepted);
        assertEquals(1, r.lineIndex);
        assertEquals(MixedLyricsLineMatcher.MatchTier.EXACT, r.tier);
    }

    @Test
    public void strictestRejectsUnrelatedLine() {
        List<EnhancedLRCParser.EnhancedLyricLine> lines = Arrays.asList(
            line(0, "甲"),
            line(5000, "乙"),
            line(10000, "丙")
        );
        MixedLyricsLineMatcher.MatchResult r = MixedLyricsLineMatcher.match(
            lines,
            "SuperLyric完全不同的一句",
            null,
            5000L,
            6000L,
            1,
            MusicPlayerLyricsPolicy.LineMatchStrictness.STRICTEST
        );
        assertFalse(r.accepted);
    }

    @Test
    public void fuzzyMatchWithinWindow() {
        List<EnhancedLRCParser.EnhancedLyricLine> lines = Arrays.asList(
            line(0, "前奏"),
            line(4000, "我记得那一天"),
            line(9000, "你转身说再见")
        );
        MixedLyricsLineMatcher.MatchResult r = MixedLyricsLineMatcher.match(
            lines,
            "我记得那一天",
            Collections.emptyList(),
            4100L,
            4500L,
            1
        );
        assertTrue(r.accepted);
        assertEquals(1, r.lineIndex);
    }
}
