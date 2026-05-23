package com.wmqc.miroot.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KuwoAudioLyricParserTest {

    @Test
    fun parse_nullOrBlank_returnsNull() {
        assertNull(KuwoAudioLyricParser.parse(null))
        assertNull(KuwoAudioLyricParser.parse(""))
        assertNull(KuwoAudioLyricParser.parse("   "))
    }

    @Test
    fun parse_wrongResultCode_returnsNull() {
        val json =
            """
            {"AUDIO_LYRIC":[{"startTime":0,"text":"a"}],"resultCode":0}
            """.trimIndent()
        assertNull(KuwoAudioLyricParser.parse(json))
    }

    @Test
    fun parse_emptyLyricArray_returnsNull() {
        val json = """{"AUDIO_LYRIC":[],"resultCode":20000}"""
        assertNull(KuwoAudioLyricParser.parse(json))
    }

    @Test
    fun parse_validJson_sortsByTimeAndKeepsLines() {
        val json =
            """{"AUDIO_LYRIC":[{"startTime":5000,"text":"line2"},{"startTime":0,"text":"line1"}],"resultCode":20000}"""
        val r = KuwoAudioLyricParser.parse(json)
        assertNotNull(r)
        assertEquals(2, r!!.lines.size)
        assertEquals(0L, r.lines[0].time)
        assertEquals("line1", r.lines[0].text)
        assertEquals(5000L, r.lines[1].time)
        assertEquals("line2", r.lines[1].text)
    }

    @Test
    fun parse_skipsEmptyTextLines() {
        val json =
            """{"AUDIO_LYRIC":[{"startTime":0,"text":""},{"startTime":100,"text":"ok"}],"resultCode":20000}"""
        val r = KuwoAudioLyricParser.parse(json)
        assertNotNull(r)
        assertEquals(1, r!!.lines.size)
        assertEquals(100L, r.lines[0].time)
        assertEquals("ok", r.lines[0].text)
    }
}
