package com.wmqc.miroot.lyrics;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SuperLyricWordTimestampsTest {

    @Test
    public void fusionAnchorPrefersModuleWhenNetworkLineLate() {
        List<EnhancedLRCParser.WordTimestamp> source = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 120_100L, 120_300L),
            new EnhancedLRCParser.WordTimestamp("好", 120_300L, 120_600L)
        );
        assertEquals(120_100L, SuperLyricWordTimestamps.resolveFusionAnchorMs(120_500L, 120_100L, source));
        assertTrue(SuperLyricWordTimestamps.shouldUseModuleTimelineForFusion(120_500L, 120_100L, source));
        List<EnhancedLRCParser.WordTimestamp> out = SuperLyricWordTimestamps.alignAndMapToLineText(
            120_500L, "你好", source, 122_000L, 120_100L
        );
        assertEquals(120_100L, out.get(0).startTime);
        assertEquals(120_300L, out.get(0).endTime);
        assertEquals(120_300L, out.get(1).startTime);
        assertEquals(120_600L, out.get(1).endTime);
    }

    @Test
    public void absoluteWordsAnchoredToPlaybackLine() {
        List<EnhancedLRCParser.WordTimestamp> source = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 120_100L, 120_300L),
            new EnhancedLRCParser.WordTimestamp("好", 120_300L, 120_600L)
        );
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricWordTimestamps.alignToLineTime(120_040L, source);
        assertEquals(120_040L, out.get(0).startTime);
        assertEquals(120_240L, out.get(0).endTime);
        assertEquals(120_240L, out.get(1).startTime);
        assertEquals(120_540L, out.get(1).endTime);
    }

    @Test
    public void lineRelativeWordsMappedToLineTime() {
        List<EnhancedLRCParser.WordTimestamp> source = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("A", 100L, 400L),
            new EnhancedLRCParser.WordTimestamp("B", 400L, 800L)
        );
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricWordTimestamps.alignToLineTime(5_000L, source);
        assertEquals(5_000L, out.get(0).startTime);
        assertEquals(5_300L, out.get(0).endTime);
        assertEquals(5_300L, out.get(1).startTime);
        assertEquals(5_700L, out.get(1).endTime);
    }

    @Test
    public void mapToLineTextInsertsPunctuationFromNetworkLine() {
        List<EnhancedLRCParser.WordTimestamp> aligned = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("我", 5_000L, 5_200L),
            new EnhancedLRCParser.WordTimestamp("记得", 5_200L, 5_500L),
            new EnhancedLRCParser.WordTimestamp("那一天", 5_500L, 5_900L),
            new EnhancedLRCParser.WordTimestamp("你转身说再见", 5_900L, 6_400L)
        );
        String network = "我记得那一天，你转身说再见";
        List<EnhancedLRCParser.WordTimestamp> mapped =
            SuperLyricWordTimestamps.mapToLineText(network, aligned);
        assertEquals(network.length(), mapped.size());
        int commaIndex = network.indexOf('，');
        assertTrue(commaIndex >= 0);
        assertEquals("，", mapped.get(commaIndex).word);
        assertEquals(5_900L, mapped.get(commaIndex).startTime);
    }

    @Test
    public void skipCharMappingDoesNotPadLastChar() {
        List<EnhancedLRCParser.WordTimestamp> source = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 100L, 280L),
            new EnhancedLRCParser.WordTimestamp("好", 280L, 420L)
        );
        List<EnhancedLRCParser.WordTimestamp> out = SuperLyricWordTimestamps.alignAndMapToLineText(
            10_000L, "你好", source, 12_000L
        );
        assertEquals(10_320L, out.get(1).endTime);
        assertTrue(out.get(1).endTime < 10_500L);
    }

    @Test
    public void skipCharMappingWhenNormalizedTextMatches() {
        List<EnhancedLRCParser.WordTimestamp> aligned = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 5_000L, 5_200L),
            new EnhancedLRCParser.WordTimestamp("好", 5_200L, 5_500L)
        );
        assertTrue(SuperLyricWordTimestamps.canSkipCharMapping("你好", aligned));
        List<EnhancedLRCParser.WordTimestamp> out = SuperLyricWordTimestamps.alignAndMapToLineText(
            5_000L, "你好", aligned
        );
        assertEquals(2, out.size());
        assertEquals("你", out.get(0).word);
    }

    @Test
    public void padLastCharEndIsBounded() {
        ArrayList<EnhancedLRCParser.WordTimestamp> words = new ArrayList<>(Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 10_000L, 10_180L),
            new EnhancedLRCParser.WordTimestamp("好", 10_180L, 10_320L)
        ));
        SuperLyricWordTimestamps.padLastMeaningfulCharEnd(words, 10_800L);
        // naturalEnd=10320, +400 pad → 10720, gap fill min(0.22*480, gap-96)=105 capped at next-80
        assertEquals(10_720L, words.get(1).endTime);
        assertTrue(words.get(1).endTime < 10_800L);
        assertTrue(words.get(1).endTime >= 10_500L);
    }

    @Test
    public void stretchShortSuperTimelineTowardNextLine() {
        ArrayList<EnhancedLRCParser.WordTimestamp> words = new ArrayList<>(Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 10_000L, 10_200L),
            new EnhancedLRCParser.WordTimestamp("好", 10_200L, 10_380L)
        ));
        SuperLyricWordTimestamps.stretchWordTimelineTowardNextLine(words, 10_000L, 12_000L);
        assertTrue(words.get(1).endTime > 10_700L);
        assertTrue(words.get(1).endTime < 12_000L);
    }

    @Test
    public void materialWordTimingChangeDetectsMiddleWordShift() {
        List<EnhancedLRCParser.WordTimestamp> previous = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 10_000L, 10_200L),
            new EnhancedLRCParser.WordTimestamp("好", 10_200L, 10_400L)
        );
        List<EnhancedLRCParser.WordTimestamp> next = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 10_000L, 10_200L),
            new EnhancedLRCParser.WordTimestamp("好", 10_200L, 10_500L)
        );
        assertTrue(SuperLyricWordTimestamps.hasMaterialWordTimingChange(previous, next));
        assertFalse(SuperLyricWordTimestamps.hasMaterialWordTimingChange(previous, previous));
    }

    @Test
    public void singleLineDisplayTimelineUsesLineBounds() {
        List<EnhancedLRCParser.WordTimestamp> module = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 0, 400),
            new EnhancedLRCParser.WordTimestamp("好", 400, 900)
        );
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricWordTimestamps.buildSingleLineDisplayTimeline("你好", module, 0L, 900L);
        assertEquals(2, out.size());
        assertEquals(0L, out.get(0).startTime);
        assertEquals(400L, out.get(1).startTime);
        assertEquals(900L, out.get(1).endTime);
    }

    @Test
    public void singleLineDisplayTimelineMapsPunctuation() {
        List<EnhancedLRCParser.WordTimestamp> module = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("记得", 0, 500),
            new EnhancedLRCParser.WordTimestamp("那一天", 500, 900)
        );
        String text = "记得那一天，再见";
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricWordTimestamps.buildSingleLineDisplayTimeline(text, module, 10_000L, 10_900L);
        assertEquals(text.length(), out.size());
        int comma = text.indexOf('，');
        assertTrue(comma >= 0);
        assertEquals("，", out.get(comma).word);
    }

    @Test
    public void buildSingleLineDoesNotCompressWhenModuleLineEndTooShort() {
        List<EnhancedLRCParser.WordTimestamp> module = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 0, 400),
            new EnhancedLRCParser.WordTimestamp("好", 400, 1_200),
            new EnhancedLRCParser.WordTimestamp("啊", 1_200, 2_400)
        );
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricWordTimestamps.buildSingleLineDisplayTimeline(
                "你好啊", module, 10_000L, 10_500L
            );
        assertEquals(3, out.size());
        assertEquals(2_400L, out.get(2).endTime);
    }

    @Test
    public void resolveSingleLineAnchorPrefersModuleLineStart() {
        assertEquals(120_000L,
            SuperLyricWordTimestamps.resolveSingleLineSentenceAnchorMs(120_000L, 120_850L));
        assertEquals(95_000L,
            SuperLyricWordTimestamps.resolveSingleLineSentenceAnchorMs(95_000L, 0L));
    }

    @Test
    public void earlySongAbsoluteTimelineNotSentenceRelative() {
        List<EnhancedLRCParser.WordTimestamp> fused = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 60_100L, 60_300L),
            new EnhancedLRCParser.WordTimestamp("好", 60_300L, 60_600L)
        );
        assertFalse(SuperLyricWordTimestamps.usesSentenceRelativeTimeline(fused));
    }

    @Test
    public void mixedFusionEarlyLineAnchoredToLrcTimeIsAbsolute() {
        List<EnhancedLRCParser.WordTimestamp> fused = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 5_100L, 5_300L),
            new EnhancedLRCParser.WordTimestamp("好", 5_300L, 5_600L)
        );
        assertFalse(SuperLyricWordTimestamps.usesSentenceRelativeTimeline(fused, 5_000L));
        assertTrue(SuperLyricWordTimestamps.usesSentenceRelativeTimeline(fused, 0L));
    }

    @Test
    public void sentenceRelativeTimelineStillDetectedForSingleLine() {
        List<EnhancedLRCParser.WordTimestamp> relative = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 0L, 400L),
            new EnhancedLRCParser.WordTimestamp("好", 400L, 900L)
        );
        assertTrue(SuperLyricWordTimestamps.usesSentenceRelativeTimeline(relative));
    }

    @Test
    public void alignAndMapProducesPerCharTimeline() {
        List<EnhancedLRCParser.WordTimestamp> source = Arrays.asList(
            new EnhancedLRCParser.WordTimestamp("你", 100L, 300L),
            new EnhancedLRCParser.WordTimestamp("好", 300L, 500L)
        );
        List<EnhancedLRCParser.WordTimestamp> out = SuperLyricWordTimestamps.alignAndMapToLineText(
            10_000L, "你好", source
        );
        assertEquals(2, out.size());
        assertEquals("你", out.get(0).word);
        assertEquals("好", out.get(1).word);
        assertEquals(10_000L, out.get(0).startTime);
    }
}
