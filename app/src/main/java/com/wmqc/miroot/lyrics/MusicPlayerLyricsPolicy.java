package com.wmqc.miroot.lyrics;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;

import com.wmqc.miroot.service.MiRootNotificationListenerService;

import java.util.List;

/**
 * 按正在播放的 App 包名，决定网络歌词主源（汽水 qsgc 或酷狗曲库）。
 * <p>
 * 智能切换 / 网络歌词：汽水音乐先汽水 API；网易云、酷狗、酷我、QQ 及其他先酷狗 API。
 */
public final class MusicPlayerLyricsPolicy {
    /** 汽水音乐：优先 qsgc（汽水 API）精确直连 */
    private static final String[] QSGC_PRIORITY_PREFIXES = {
        "com.luna.music",
    };

    /** 网易云、酷狗、酷我、QQ 等：优先酷狗搜歌 + krc */
    private static final String[] KUGOU_PRIORITY_PREFIXES = {
        "com.netease.cloudmusic",
        "com.kugou.android",
        "cn.kuwo.",
        "com.tencent.qqmusic",
    };

    public enum PrimaryStrategy {
        PREFER_QSGC,
        PREFER_KUGOU
    }

    /**
     * 行级逐字融合比对严格度。汽水曲库与 SuperLyric 偏差大，错配会导致整首错位且无法走单句兜底。
     */
    public enum LineMatchStrictness {
        DEFAULT,
        /** 汽水：仅 EXACT/强 STRONG、禁止 WEAK 与全局模糊兜底 */
        STRICTEST
    }

    private MusicPlayerLyricsPolicy() {
    }

    /** 是否为汽水音乐包名（与歌词来源模式无关，仅标识 App）。 */
    public static boolean isQishuiMusicPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        String pkg = packageName.trim().toLowerCase();
        for (String prefix : QSGC_PRIORITY_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 汽水 + 智能切换（MIXED）：最严行级逐字融合、先模块单句后完整歌词；网络链仍 qsgc→酷狗且严格匹配。
     */
    public static boolean appliesQishuiMixedStrictPolicy(String packageName, boolean mixedLyricsSourceMode) {
        return mixedLyricsSourceMode && isQishuiMusicPackage(packageName);
    }

    /**
     * 汽水网络主源链是否跳过酷狗次源（已废弃：汽水接受酷狗严格命中，恒为 false）。
     */
    public static boolean skipsKugouSecondaryInPrimaryChain(String packageName, boolean mixedLyricsSourceMode) {
        return false;
    }

    /**
     * 汽水 + 网络歌词（NETWORK_ONLY）：歌名+歌手严格匹配；主源 qsgc→酷狗，不预加载单句。
     */
    public static boolean appliesQishuiNetworkOnlyStrictMatch(String packageName,
                                                              boolean networkOnlyLyricsSourceMode) {
        return networkOnlyLyricsSourceMode && isQishuiMusicPackage(packageName);
    }

    /** 汽水任意网络拉词模式：严格匹配、不模糊搜索（含智能切换与网络歌词）。 */
    public static boolean appliesQishuiStrictNetworkFetch(String packageName,
                                                        boolean mixedLyricsSourceMode,
                                                        boolean networkOnlyLyricsSourceMode) {
        return isQishuiMusicPackage(packageName)
            && (mixedLyricsSourceMode || networkOnlyLyricsSourceMode);
    }

    /** 网络拉词是否启用歌名+歌手严格匹配（酷狗候选校验）。 */
    public static boolean requiresStrictTitleArtistNetworkMatch(String packageName,
                                                                boolean mixedLyricsSourceMode,
                                                                boolean networkOnlyLyricsSourceMode) {
        return appliesQishuiStrictNetworkFetch(
            packageName, mixedLyricsSourceMode, networkOnlyLyricsSourceMode);
    }

    /** @deprecated 请使用 {@link #appliesQishuiMixedStrictPolicy(String, boolean)} */
    public static boolean requiresStrictestLyricsMatching(String packageName) {
        return isQishuiMusicPackage(packageName);
    }

    /**
     * 汽水 + 智能切换：网络 API 优先；等待期间超时后才允许 SuperLyric 单行兜底。
     */
    public static boolean allowsQishuiSuperLyricPreviewWhileNetworkPending(String packageName,
                                                                          boolean mixedLyricsSourceMode) {
        return appliesQishuiMixedStrictPolicy(packageName, mixedLyricsSourceMode);
    }

    /** @deprecated 请使用 {@link #allowsQishuiSuperLyricPreviewWhileNetworkPending(String, boolean)} */
    public static boolean prefersSuperLyricFirstWhileNetworkPending(String packageName,
                                                                    boolean mixedLyricsSourceMode) {
        return allowsQishuiSuperLyricPreviewWhileNetworkPending(packageName, mixedLyricsSourceMode);
    }

    /**
     * SuperLyric 单行兜底升级到网络完整 LRC 时保留播放锚点（避免跳屏）。
     */
    public static boolean prefersNetworkLyricsSmoothUpgrade(String sourcePackageName,
                                                            boolean mixedLyricsSourceMode,
                                                            boolean wasSuperLyricFallbackActive) {
        return wasSuperLyricFallbackActive && mixedLyricsSourceMode;
    }

    public static LineMatchStrictness resolveLineMatchStrictness(String packageName,
                                                                 boolean mixedLyricsSourceMode) {
        if (appliesQishuiMixedStrictPolicy(packageName, mixedLyricsSourceMode)) {
            return LineMatchStrictness.STRICTEST;
        }
        return LineMatchStrictness.DEFAULT;
    }

    /**
     * 解析用于网络 API 拉词的播放器包名：优先调用方显式传入，否则尝试当前 MediaSession。
     */
    public static String resolveNetworkLyricsPackageName(Context context, String explicitPackageName) {
        if (explicitPackageName != null && !explicitPackageName.trim().isEmpty()) {
            return explicitPackageName.trim();
        }
        return queryActiveMediaPackageName(context);
    }

    public static PrimaryStrategy resolvePrimaryStrategy(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return PrimaryStrategy.PREFER_KUGOU;
        }
        String pkg = packageName.trim().toLowerCase();
        for (String prefix : QSGC_PRIORITY_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return PrimaryStrategy.PREFER_QSGC;
            }
        }
        for (String prefix : KUGOU_PRIORITY_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return PrimaryStrategy.PREFER_KUGOU;
            }
        }
        if (pkg.contains("kugou")) {
            return PrimaryStrategy.PREFER_KUGOU;
        }
        return PrimaryStrategy.PREFER_KUGOU;
    }

    public static String strategyDisplayLabel(PrimaryStrategy strategy) {
        if (strategy == PrimaryStrategy.PREFER_QSGC) {
            return "汽水优先";
        }
        return "酷狗优先";
    }

    private static String queryActiveMediaPackageName(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "";
        }
        try {
            MediaSessionManager sessionManager =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sessionManager == null) {
                return "";
            }
            ComponentName listener = new ComponentName(context, MiRootNotificationListenerService.class);
            List<MediaController> controllers = sessionManager.getActiveSessions(listener);
            if (controllers == null || controllers.isEmpty()) {
                return "";
            }
            String pkg = controllers.get(0).getPackageName();
            return pkg != null ? pkg : "";
        } catch (Exception e) {
            LogHelper.w("MusicPlayerLyricsPolicy", "读取 MediaSession 包名失败: " + e.getMessage());
            return "";
        }
    }

    private static boolean matchesPackagePrefix(String pkg, String prefix) {
        if (prefix.endsWith(".")) {
            return pkg.startsWith(prefix);
        }
        return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }
}
