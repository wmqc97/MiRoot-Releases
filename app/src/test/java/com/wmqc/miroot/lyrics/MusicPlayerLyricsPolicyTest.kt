package com.wmqc.miroot.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerLyricsPolicyTest {

    @Test
    fun qishui_prefersQsgc() {
        assertEquals(
            MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_QSGC,
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy("com.luna.music"),
        )
    }

    @Test
    fun qishui_mixedUsesStrictPolicy() {
        assertTrue(MusicPlayerLyricsPolicy.isQishuiMusicPackage("com.luna.music"))
        assertTrue(MusicPlayerLyricsPolicy.appliesQishuiMixedStrictPolicy("com.luna.music", true))
        assertTrue(
            MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending("com.luna.music", true),
        )
        assertTrue(MusicPlayerLyricsPolicy.prefersSuperLyricFirstWhileNetworkPending("com.luna.music", true))
        assertEquals(
            MusicPlayerLyricsPolicy.LineMatchStrictness.STRICTEST,
            MusicPlayerLyricsPolicy.resolveLineMatchStrictness("com.luna.music", true),
        )
    }

    @Test
    fun qishui_doesNotSkipKugouSecondary() {
        assertFalse(
            MusicPlayerLyricsPolicy.skipsKugouSecondaryInPrimaryChain("com.luna.music", true),
        )
        assertFalse(
            MusicPlayerLyricsPolicy.skipsKugouSecondaryInPrimaryChain("com.luna.music", false),
        )
    }

    @Test
    fun qishui_strictNetworkFetch_inMixedAndNetworkOnly() {
        assertTrue(
            MusicPlayerLyricsPolicy.appliesQishuiStrictNetworkFetch("com.luna.music", true, false),
        )
        assertTrue(
            MusicPlayerLyricsPolicy.appliesQishuiStrictNetworkFetch("com.luna.music", false, true),
        )
        assertFalse(
            MusicPlayerLyricsPolicy.appliesQishuiStrictNetworkFetch("com.luna.music", false, false),
        )
        assertFalse(
            MusicPlayerLyricsPolicy.appliesQishuiStrictNetworkFetch("com.netease.cloudmusic", true, true),
        )
    }

    @Test
    fun qishui_networkOnlyUsesStrictNetworkNotMixed() {
        assertTrue(MusicPlayerLyricsPolicy.isQishuiMusicPackage("com.luna.music"))
        assertFalse(MusicPlayerLyricsPolicy.appliesQishuiMixedStrictPolicy("com.luna.music", false))
        assertTrue(MusicPlayerLyricsPolicy.appliesQishuiNetworkOnlyStrictMatch("com.luna.music", true))
        assertFalse(MusicPlayerLyricsPolicy.appliesQishuiNetworkOnlyStrictMatch("com.luna.music", false))
        assertTrue(
            MusicPlayerLyricsPolicy.requiresStrictTitleArtistNetworkMatch(
                "com.luna.music",
                false,
                true,
            ),
        )
        assertFalse(MusicPlayerLyricsPolicy.prefersSuperLyricFirstWhileNetworkPending("com.luna.music", false))
        assertEquals(
            MusicPlayerLyricsPolicy.LineMatchStrictness.DEFAULT,
            MusicPlayerLyricsPolicy.resolveLineMatchStrictness("com.luna.music", false),
        )
    }

    @Test
    fun netease_mixedDoesNotPreferSuperLyricFirst() {
        assertFalse(
            MusicPlayerLyricsPolicy.allowsQishuiSuperLyricPreviewWhileNetworkPending(
                "com.netease.cloudmusic",
                true,
            ),
        )
        assertFalse(
            MusicPlayerLyricsPolicy.prefersSuperLyricFirstWhileNetworkPending(
                "com.netease.cloudmusic",
                true,
            ),
        )
    }

    @Test
    fun netease_usesDefaultLineMatching() {
        assertFalse(MusicPlayerLyricsPolicy.isQishuiMusicPackage("com.netease.cloudmusic"))
        assertEquals(
            MusicPlayerLyricsPolicy.LineMatchStrictness.DEFAULT,
            MusicPlayerLyricsPolicy.resolveLineMatchStrictness("com.netease.cloudmusic", true),
        )
    }

    @Test
    fun netease_prefersKugou() {
        assertEquals(
            MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_KUGOU,
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy("com.netease.cloudmusic"),
        )
    }

    @Test
    fun kugouKuwoQq_prefersKugou() {
        assertEquals(
            MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_KUGOU,
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy("com.kugou.android"),
        )
        assertEquals(
            MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_KUGOU,
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy("cn.kuwo.kwmusiccar"),
        )
        assertEquals(
            MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_KUGOU,
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy("com.tencent.qqmusic"),
        )
    }

    @Test
    fun unknownPackage_defaultsToKugou() {
        assertEquals(
            MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_KUGOU,
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy("com.example.player"),
        )
    }
}
