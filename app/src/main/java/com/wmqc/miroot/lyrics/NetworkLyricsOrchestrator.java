package com.wmqc.miroot.lyrics;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能网络歌词编排：按 App 优先级串行尝试主源（qsgc / 酷狗），全部失败后再走开放兜底。
 */
final class NetworkLyricsOrchestrator {
    private static final String TAG = "NetworkLyricsOrchestrator";
    private static final String PROVIDER_KUGOU = "kugou";
    private static final String PROVIDER_QSGC = "qsgc";
    private static final String PROVIDER_LRCLIB = "lrclib";
    private static final String PROVIDER_LYRICS_OVH = "lyrics.ovh";

    private static final int SECONDARY_PARALLEL_TIMEOUT_SEC = 12;

    /**
     * 兜底歌词并行拉取线程池：核心 0 线程，空闲 60s 后自动回收，避免常驻 2 个空闲线程浪费内存。
     */
    private static final ExecutorService SECONDARY_EXECUTOR = new ThreadPoolExecutor(
        0, 2,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        Executors.defaultThreadFactory()
    );

    private NetworkLyricsOrchestrator() {
    }

    static final class Payload {
        String lyrics = "";
        String provider = "";
        String sourcePackage = "";
        MusicPlayerLyricsPolicy.PrimaryStrategy strategy = MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_KUGOU;
        boolean success = false;
        String error = "";
    }

    static Payload fetch(Context context,
                         String title,
                         String artist,
                         double durationSeconds,
                         String musixmatchApiKey,
                         String sourcePackageName,
                         boolean strictTitleArtistMatch,
                         boolean enableOpenFallback) {
        return fetch(
            context,
            title,
            artist,
            durationSeconds,
            musixmatchApiKey,
            sourcePackageName,
            strictTitleArtistMatch,
            enableOpenFallback,
            false
        );
    }

    static Payload fetch(Context context,
                         String title,
                         String artist,
                         double durationSeconds,
                         String musixmatchApiKey,
                         String sourcePackageName,
                         boolean strictTitleArtistMatch,
                         boolean enableOpenFallback,
                         boolean qishuiQsgcOnlyPrimary) {
        Payload payload = new Payload();
        if (context == null || title == null || title.trim().isEmpty()) {
            payload.error = "参数无效";
            return payload;
        }

        String t = title.trim();
        String a = artist != null ? artist.trim() : "";
        String resolvedPkg = sourcePackageName != null ? sourcePackageName.trim() : "";
        MusicPlayerLyricsPolicy.PrimaryStrategy strategy =
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy(resolvedPkg);
        payload.sourcePackage = resolvedPkg;
        payload.strategy = strategy;

        LogHelper.d(TAG, "智能拉词: " + t + " - " + a
            + ", pkg=" + resolvedPkg
            + ", strategy=" + strategy
            + ", strict=" + strictTitleArtistMatch
            + ", openFallback=" + enableOpenFallback);

        Payload primary = fetchPrimarySequential(
            context, t, a, durationSeconds, musixmatchApiKey,
            strictTitleArtistMatch, enableOpenFallback, strategy, resolvedPkg, qishuiQsgcOnlyPrimary);
        primary.sourcePackage = resolvedPkg;
        primary.strategy = strategy;
        if (primary.success) {
            return primary;
        }

        if (!enableOpenFallback) {
            payload.error = primary.error != null && !primary.error.isEmpty()
                ? primary.error
                : "未开启开放兜底";
            return payload;
        }

        Payload open = fetchOpenFallbackParallel(context, t, a, payload, strictTitleArtistMatch);
        open.sourcePackage = resolvedPkg;
        open.strategy = strategy;
        if (open.success) {
            return open;
        }

        payload.error = joinErrors(primary.error, open.error);
        return payload;
    }

    /**
     * 按 App 策略串行拉词：优先源成功即返回；失败再试次源；仍失败才由上层走开放兜底。
     */
    private static Payload fetchPrimarySequential(Context context,
                                                  String title,
                                                  String artist,
                                                  double durationSeconds,
                                                  String musixmatchApiKey,
                                                  boolean strictTitleArtistMatch,
                                                  boolean enableOpenFallback,
                                                  MusicPlayerLyricsPolicy.PrimaryStrategy strategy,
                                                  String sourcePackageName,
                                                  boolean qishuiQsgcOnlyPrimary) {
        final Context appContext = context.getApplicationContext();
        final boolean hasArtist = artist != null && !artist.isEmpty();

        if (strategy == MusicPlayerLyricsPolicy.PrimaryStrategy.PREFER_QSGC) {
            if (qishuiQsgcOnlyPrimary) {
                LogHelper.d(TAG, "主源顺序: qsgc（仅主源，跳过酷狗次源）");
            } else {
                LogHelper.d(TAG, "主源顺序: qsgc → 酷狗 → (开放兜底)");
            }
            Payload qsgcFail = failedPayload(PROVIDER_QSGC, "跳过");
            if (hasArtist) {
                LyricsAPIClient.LyricsResult qsgc =
                    LyricsAPIClient.searchLyricsFromQsgc(appContext, title, artist);
                if (isUsable(qsgc)) {
                    if (strictTitleArtistMatch
                        && !isQsgcLyricsStrictMatch(title, artist, qsgc.lyrics)) {
                        LogHelper.d(TAG, "🚫 qsgc 严格匹配失败 → 尝试酷狗次源");
                        qsgcFail = failedPayload(PROVIDER_QSGC,
                            "严格匹配失败（歌名/歌手元数据不一致）");
                    } else {
                        LogHelper.d(TAG, "✅ qsgc 主源命中, 耗时=" + qsgc.costMs + "ms");
                        return toPayload(qsgc, PROVIDER_QSGC);
                    }
                } else {
                    qsgcFail = failedPayload(PROVIDER_QSGC,
                        qsgc != null && qsgc.error != null ? qsgc.error : "无结果");
                }
            } else {
                qsgcFail = failedPayload(PROVIDER_QSGC, "歌手为空");
            }
            if (qishuiQsgcOnlyPrimary) {
                return qsgcFail;
            }
            Payload kugou = tryKugou(appContext, title, artist, durationSeconds,
                musixmatchApiKey, strictTitleArtistMatch);
            if (kugou.success) {
                LogHelper.d(TAG, "✅ 酷狗次源命中（汽水优先策略）");
                return kugou;
            }
            return joinPrimaryFailures(qsgcFail, kugou);
        }

        LogHelper.d(TAG, "主源顺序: 酷狗 → qsgc → (开放兜底)");
        Payload kugou = tryKugou(appContext, title, artist, durationSeconds,
            musixmatchApiKey, strictTitleArtistMatch);
        if (kugou.success) {
            return kugou;
        }
        if (hasArtist) {
            LyricsAPIClient.LyricsResult qsgc =
                LyricsAPIClient.searchLyricsFromQsgc(appContext, title, artist);
            if (isUsable(qsgc)) {
                if (strictTitleArtistMatch
                    && !isQsgcLyricsStrictMatch(title, artist, qsgc.lyrics)) {
                    LogHelper.d(TAG, "🚫 qsgc 次源严格匹配失败");
                    Payload qsgcFail = failedPayload(PROVIDER_QSGC,
                        "严格匹配失败（歌名/歌手元数据不一致）");
                    return joinPrimaryFailures(kugou, qsgcFail);
                }
                LogHelper.d(TAG, "✅ qsgc 次源命中（酷狗优先策略）, 耗时=" + qsgc.costMs + "ms");
                return toPayload(qsgc, PROVIDER_QSGC);
            }
            Payload qsgcFail = failedPayload(PROVIDER_QSGC,
                qsgc != null && qsgc.error != null ? qsgc.error : "无结果");
            return joinPrimaryFailures(kugou, qsgcFail);
        }
        return kugou;
    }

    private static Payload joinPrimaryFailures(Payload first, Payload second) {
        Payload failed = new Payload();
        failed.error = joinErrors(
            first != null ? first.error : "",
            second != null ? second.error : ""
        );
        return failed;
    }

    private static Payload tryKugou(Context context,
                                    String title,
                                    String artist,
                                    double durationSeconds,
                                    String musixmatchApiKey,
                                    boolean strictTitleArtistMatch) {
        Payload payload = new Payload();
        List<LyricsMatcher.Candidate> candidates = LyricsAPIClient.searchLyrics(
            context, title, artist, "", durationSeconds, musixmatchApiKey);

        if (candidates == null || candidates.isEmpty()) {
            payload.error = "酷狗无候选";
            return payload;
        }

        LyricsMatcher.Candidate best = candidates.get(0);
        if (strictTitleArtistMatch && !isStrictTitleArtistMatch(title, artist, best)) {
            payload.error = "酷狗严格匹配失败";
            LogHelper.w(TAG, "❌ 酷狗严格匹配失败: req=" + title + " - " + artist
                + ", hit=" + best.title + " - " + best.artist);
            return payload;
        }

        if (best.id == null || best.id.isEmpty()) {
            payload.error = "酷狗候选 hash 为空";
            return payload;
        }

        LyricsAPIClient.LyricsResult result = LyricsAPIClient.getLyricsById(
            context, best.id, best.provider);
        if (!isUsable(result)) {
            payload.error = result != null && result.error != null ? result.error : "酷狗拉词失败";
            return payload;
        }

        LogHelper.d(TAG, "✅ 酷狗命中: " + best.title + " - " + best.artist);
        return toPayload(result, PROVIDER_KUGOU);
    }

    private static Payload fetchOpenFallbackParallel(Context context,
                                                     String title,
                                                     String artist,
                                                     Payload failureSink,
                                                     boolean strictTitleArtistMatch) {
        List<LyricsAPIClient.LyricsResult> results = new ArrayList<>(2);
        Future<LyricsAPIClient.LyricsResult> lrclibFuture = SECONDARY_EXECUTOR.submit(
            new Callable<LyricsAPIClient.LyricsResult>() {
                @Override
                public LyricsAPIClient.LyricsResult call() {
                    return LyricsAPIClient.searchLyricsFromLrclib(
                        context, title, artist, strictTitleArtistMatch);
                }
            }
        );
        Future<LyricsAPIClient.LyricsResult> ovhFuture = SECONDARY_EXECUTOR.submit(
            new Callable<LyricsAPIClient.LyricsResult>() {
                @Override
                public LyricsAPIClient.LyricsResult call() {
                    return LyricsAPIClient.searchLyricsFromLyricsOvh(context, title, artist);
                }
            }
        );

        LyricsAPIClient.LyricsResult lrclib = awaitResult(lrclibFuture, SECONDARY_PARALLEL_TIMEOUT_SEC, "lrclib");
        LyricsAPIClient.LyricsResult lyricsOvh = awaitResult(ovhFuture, SECONDARY_PARALLEL_TIMEOUT_SEC, "lyrics.ovh");
        if (isUsable(lrclib)) {
            results.add(lrclib);
        }
        if (isUsable(lyricsOvh)) {
            if (strictTitleArtistMatch
                && !isLyricsOvhStrictMatch(title, artist, lyricsOvh.lyrics)) {
                LogHelper.d(TAG, "🚫 lyrics.ovh 严格匹配失败，丢弃");
            } else {
                results.add(lyricsOvh);
            }
        }

        if (results.isEmpty()) {
            failureSink.error = joinErrors(
                lrclib != null ? lrclib.error : "lrclib=null",
                lyricsOvh != null ? lyricsOvh.error : "lyrics.ovh=null"
            );
            return failureSink;
        }

        LyricsAPIClient.LyricsResult best = pickBestQuality(results);
        String provider = best.source != null && !best.source.isEmpty()
            ? best.source
            : PROVIDER_LRCLIB;
        LogHelper.d(TAG, "✅ 开放兜底选中: " + provider + ", 质量分="
            + lyricsQualityScore(best.lyrics) + ", 耗时=" + best.costMs + "ms");
        return toPayload(best, provider);
    }

    private static Payload failedPayload(String label, String message) {
        Payload payload = new Payload();
        payload.error = label + ": " + message;
        return payload;
    }

    private static LyricsAPIClient.LyricsResult awaitResult(Future<LyricsAPIClient.LyricsResult> future,
                                                            int timeoutSec,
                                                            String label) {
        try {
            LyricsAPIClient.LyricsResult result = future.get(timeoutSec, TimeUnit.SECONDS);
            return result != null ? result : failedResult(label, "null");
        } catch (Exception e) {
            LogHelper.w(TAG, label + " 请求失败: " + e.getMessage());
            return failedResult(label, e.getMessage());
        }
    }

    private static LyricsAPIClient.LyricsResult failedResult(String label, String message) {
        LyricsAPIClient.LyricsResult failed = new LyricsAPIClient.LyricsResult();
        failed.source = label;
        failed.error = message;
        return failed;
    }

    private static LyricsAPIClient.LyricsResult pickBestQuality(List<LyricsAPIClient.LyricsResult> results) {
        LyricsAPIClient.LyricsResult best = results.get(0);
        int bestScore = lyricsQualityScore(best.lyrics);
        for (int i = 1; i < results.size(); i++) {
            LyricsAPIClient.LyricsResult candidate = results.get(i);
            int score = lyricsQualityScore(candidate.lyrics);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                continue;
            }
            if (score == bestScore && candidate.costMs > 0 && best.costMs > 0
                && candidate.costMs < best.costMs) {
                best = candidate;
            }
        }
        return best;
    }

    /** 带时间轴的 LRC 优先于纯文本。 */
    static int lyricsQualityScore(String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return 0;
        }
        int score = Math.min(lyrics.length() / 40, 80);
        if (lyrics.indexOf('[') >= 0 && lyrics.indexOf(':') > 0) {
            score += 120;
        } else if (lyrics.contains("\n")) {
            score += 30;
        }
        return score;
    }

    private static boolean isUsable(LyricsAPIClient.LyricsResult result) {
        return result != null && result.success
            && result.lyrics != null && !result.lyrics.trim().isEmpty();
    }

    private static Payload toPayload(LyricsAPIClient.LyricsResult result, String provider) {
        Payload payload = new Payload();
        payload.lyrics = result.lyrics;
        payload.provider = provider;
        payload.success = true;
        return payload;
    }

    /** LRC metadata tag pattern: [ti:Title], [ar:Artist] */
    private static final Pattern LRC_TAG_PATTERN =
        Pattern.compile("\\[(ti|ar):([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);

    /**
     * 对 qsgc 返回的 LRC 文本做歌名+歌手严格校验。
     * qsgc API 不返回结构化元数据，但多数 LRC 内含 [ti:...] / [ar:...] 标签。
     * 若能解析出元数据且与请求不匹配则拒绝，解析不到则放行（不做假阴性拦截）。
     */
    private static boolean isQsgcLyricsStrictMatch(String reqTitle,
                                                   String reqArtist,
                                                   String lrcContent) {
        if (lrcContent == null || lrcContent.isEmpty()) {
            return false;
        }
        String rt = LyricsMatcher.normalize(reqTitle);
        String ra = LyricsMatcher.normalize(reqArtist);
        String lrcTitleRaw = "";
        String lrcArtistRaw = "";
        String lrcTitle = "";
        String lrcArtist = "";
        Matcher m = LRC_TAG_PATTERN.matcher(lrcContent);
        while (m.find()) {
            String tag = m.group(1).toLowerCase();
            String value = m.group(2).trim();
            if ("ti".equals(tag) && lrcTitle.isEmpty()) {
                lrcTitleRaw = value;
                lrcTitle = LyricsMatcher.normalize(value);
            } else if ("ar".equals(tag) && lrcArtist.isEmpty()) {
                lrcArtistRaw = value;
                lrcArtist = LyricsMatcher.normalize(value);
            }
        }
        boolean hasLrcTitle = !lrcTitle.isEmpty();
        boolean hasLrcArtist = !lrcArtist.isEmpty();
        if (!hasLrcTitle && !hasLrcArtist) {
            // 无法校验，放行
            return true;
        }
        if (hasLrcTitle && !rt.isEmpty()) {
            boolean titleOk = rt.equals(lrcTitle) || rt.contains(lrcTitle) || lrcTitle.contains(rt);
            if (!titleOk) {
                LogHelper.w(TAG, "❌ qsgc 歌名不匹配: req=\""
                    + reqTitle + "\" vs lrc[ti]=\"" + lrcTitleRaw + "\"");
                return false;
            }
        }
        if (hasLrcArtist && !ra.isEmpty()) {
            boolean artistOk = ra.equals(lrcArtist) || ra.contains(lrcArtist) || lrcArtist.contains(ra);
            if (!artistOk) {
                LogHelper.w(TAG, "❌ qsgc 歌手不匹配: req=\""
                    + reqArtist + "\" vs lrc[ar]=\"" + lrcArtistRaw + "\"");
                return false;
            }
        }
        return true;
    }

    /**
     * lyrics.ovh 严格校验：先尝试 LRC 元数据标签，无标签时退化为标题文本出现检查。
     */
    private static boolean isLyricsOvhStrictMatch(String reqTitle,
                                                  String reqArtist,
                                                  String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return false;
        }
        // 先尝试 LRC 元数据标签
        String lrcTitle = "";
        String lrcArtist = "";
        Matcher m = LRC_TAG_PATTERN.matcher(lyrics);
        while (m.find()) {
            String tag = m.group(1).toLowerCase();
            String value = m.group(2).trim();
            if ("ti".equals(tag) && lrcTitle.isEmpty()) {
                lrcTitle = LyricsMatcher.normalize(value);
            } else if ("ar".equals(tag) && lrcArtist.isEmpty()) {
                lrcArtist = LyricsMatcher.normalize(value);
            }
        }
        if (!lrcTitle.isEmpty() || !lrcArtist.isEmpty()) {
            // 有 LRC 标签，走标签比对
            if (!lrcTitle.isEmpty()) {
                String rt = LyricsMatcher.normalize(reqTitle);
                if (!rt.isEmpty() && !rt.equals(lrcTitle)
                    && !rt.contains(lrcTitle) && !lrcTitle.contains(rt)) {
                    return false;
                }
            }
            if (!lrcArtist.isEmpty()) {
                String ra = LyricsMatcher.normalize(reqArtist);
                if (!ra.isEmpty() && !ra.equals(lrcArtist)
                    && !ra.contains(lrcArtist) && !lrcArtist.contains(ra)) {
                    return false;
                }
            }
            return true;
        }
        // 无 LRC 标签：退化为标题文本出现检查
        String rt = LyricsMatcher.normalize(reqTitle);
        if (rt.isEmpty()) {
            return true;
        }
        if (LyricsMatcher.normalize(lyrics).contains(rt)) {
            return true;
        }
        LogHelper.w(TAG, "❌ lyrics.ovh 严格匹配失败: req=\""
            + reqTitle + "\" 未出现在歌词中");
        return false;
    }

    private static boolean isStrictTitleArtistMatch(String reqTitle,
                                                    String reqArtist,
                                                    LyricsMatcher.Candidate candidate) {
        if (candidate == null) {
            return false;
        }
        String rt = LyricsMatcher.normalize(reqTitle);
        String ra = LyricsMatcher.normalize(reqArtist);
        String ct = LyricsMatcher.normalize(candidate.title);
        String ca = LyricsMatcher.normalize(candidate.artist);
        if (rt.isEmpty() || ra.isEmpty() || ct.isEmpty() || ca.isEmpty()) {
            return false;
        }
        boolean titleMatched = rt.equals(ct) || rt.contains(ct) || ct.contains(rt);
        boolean artistMatched = ra.equals(ca) || ra.contains(ca) || ca.contains(ra);
        return titleMatched && artistMatched;
    }

    private static String joinErrors(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b != null ? b : "";
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return a + "; " + b;
    }
}
