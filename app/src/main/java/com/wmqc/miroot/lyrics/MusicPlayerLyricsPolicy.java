package com.wmqc.miroot.lyrics;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;

import com.wmqc.miroot.service.MiRootNotificationListenerService;

import java.util.List;

/**
 * 按正在播放的 App 包名，决定网络歌词主源（汽水 qsgc、各平台曲库或酷狗兜底）。
 * <p>
 * 智能切换 / 网络歌词：汽水先 qsgc；网易 / QQ / 酷我先各自官方接口再酷狗；酷我车机版
 * {@code cn.kuwo.kwmusiccar} 仍优先广播 / AUDIO_LYRIC，网络 API 仅作兜底。
 */
public final class MusicPlayerLyricsPolicy {
    /** 汽水音乐：优先 qsgc（汽水 API）精确直连 */
    private static final String[] QSGC_PRIORITY_PREFIXES = {
        "com.luna.music",
    };

    private static final String[] NETEASE_PACKAGE_PREFIXES = {
        "com.netease.cloudmusic",
    };

    private static final String[] QQ_PACKAGE_PREFIXES = {
        "com.tencent.qqmusic",
    };

    /** 酷我手机版等（不含车机包名，车机走广播优先） */
    private static final String[] KUWO_PACKAGE_PREFIXES = {
        "cn.kuwo.",
    };

    private static final String[] KUGOU_PACKAGE_PREFIXES = {
        "com.kugou.android",
    };

    public enum PrimaryStrategy {
        PREFER_QSGC,
        PREFER_KUGOU
    }

    /**
     * 与播放 App 同源的平台曲库（搜歌 + 取词）。车机版酷我返回 {@link LyricsApiEndpoints#PROVIDER_KUWO}
     * 仅用于网络兜底，实时歌词仍走广播 / MediaSession。
     */
    public static String resolvePlatformProvider(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "";
        }
        String pkg = packageName.trim().toLowerCase();
        for (String prefix : NETEASE_PACKAGE_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return LyricsApiEndpoints.PROVIDER_NETEASE;
            }
        }
        for (String prefix : QQ_PACKAGE_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return LyricsApiEndpoints.PROVIDER_QQ;
            }
        }
        for (String prefix : KUWO_PACKAGE_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return LyricsApiEndpoints.PROVIDER_KUWO;
            }
        }
        for (String prefix : KUGOU_PACKAGE_PREFIXES) {
            if (matchesPackagePrefix(pkg, prefix)) {
                return LyricsApiEndpoints.PROVIDER_KUGOU;
            }
        }
        if (pkg.contains("kugou")) {
            return LyricsApiEndpoints.PROVIDER_KUGOU;
        }
        return "";
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

    /** 酷我车机版：走 AUDIO_LYRIC 专用解析，不参与网络 API 严格匹配。 */
    public static boolean isKuwoCarPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        return packageName.trim().equalsIgnoreCase("cn.kuwo.kwmusiccar");
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
        if (!resolvePlatformProvider(pkg).isEmpty()) {
            return PrimaryStrategy.PREFER_KUGOU;
        }
        return PrimaryStrategy.PREFER_KUGOU;
    }

    public static String strategyDisplayLabel(PrimaryStrategy strategy, String packageName) {
        if (strategy == PrimaryStrategy.PREFER_QSGC) {
            return "汽水优先";
        }
        String platform = resolvePlatformProvider(packageName);
        if (LyricsApiEndpoints.PROVIDER_NETEASE.equals(platform)) {
            return "网易优先";
        }
        if (LyricsApiEndpoints.PROVIDER_QQ.equals(platform)) {
            return "QQ优先";
        }
        if (LyricsApiEndpoints.PROVIDER_KUWO.equals(platform)) {
            return "酷我优先";
        }
        return "酷狗优先";
    }

    /** @deprecated 使用 {@link #strategyDisplayLabel(PrimaryStrategy, String)} */
    public static String strategyDisplayLabel(PrimaryStrategy strategy) {
        return strategyDisplayLabel(strategy, "");
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
