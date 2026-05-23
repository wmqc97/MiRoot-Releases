package com.wmqc.miroot.lyrics;

import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SuperLyricPayloadParserTest {

    @Test
    public void officialDemoRelativeTimeline() {
        SuperLyricWord[] words = new SuperLyricWord[] {
            new SuperLyricWord("你好", 0, 400),
            new SuperLyricWord("世界", 400, 900),
        };
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricPayloadParser.buildWordTimestamps(words, 0L, 900L);
        assertEquals(2, out.size());
        assertEquals(0L, out.get(0).startTime);
        assertEquals(400L, out.get(1).startTime);
    }

    @Test
    public void lineAbsoluteWithRelativeWords() {
        SuperLyricWord[] words = new SuperLyricWord[] {
            new SuperLyricWord("你", 100, 300),
            new SuperLyricWord("好", 300, 600),
        };
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricPayloadParser.buildWordTimestamps(words, 120_000L, 120_900L);
        assertEquals(120_100L, out.get(0).startTime);
        assertEquals(120_300L, out.get(1).startTime);
    }

    @Test
    public void legacyDelayOnlyWords() {
        SuperLyricWord[] words = new SuperLyricWord[] {
            new SuperLyricWord("A", 400L),
            new SuperLyricWord("B", 350L),
        };
        List<EnhancedLRCParser.WordTimestamp> out =
            SuperLyricPayloadParser.buildWordTimestamps(words, 5_000L, 5_750L);
        assertEquals(2, out.size());
        assertEquals(5_000L, out.get(0).startTime);
        assertEquals(5_400L, out.get(0).endTime);
        assertEquals(5_400L, out.get(1).startTime);
    }

    @Test
    public void pickLineWithMostWords() {
        SuperLyricData data = new SuperLyricData()
            .setLyric(new SuperLyricLine("主歌词无逐字", 0, 1000))
            .setSecondary(new SuperLyricLine(
                "副歌词有逐字",
                new SuperLyricWord[] {
                    new SuperLyricWord("副", 0, 200),
                    new SuperLyricWord("歌", 200, 400),
                    new SuperLyricWord("词", 400, 600),
                },
                0,
                600
            ));
        SuperLyricLine picked = SuperLyricPayloadParser.pickBestLyricLine(data);
        assertNotNull(picked);
        assertEquals("副歌词有逐字", picked.getText());
    }

    @Test
    public void resolveLineTextFromWordsOnly() {
        SuperLyricLine line = new SuperLyricLine(
            "",
            new SuperLyricWord[] { new SuperLyricWord("你", 0, 200), new SuperLyricWord("好", 200, 400) },
            0,
            400
        );
        assertEquals("你好", SuperLyricPayloadParser.resolveLineText(line));
    }
}
