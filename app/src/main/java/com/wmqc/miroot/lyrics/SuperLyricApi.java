package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;

import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricHelper;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SuperLyricApi 适配层：
 * 统一收敛可能的歌词字段，优先从会话 extras / metadata 抽取，再交给现有解析器兜底。
 */
public final class SuperLyricApi {
    private static final String TAG = "SuperLyricApi";
    private static final long SUPER_LYRIC_WAIT_MS = 900L;
    /** 仅 SuperLyric 模块来源：首包可能晚于网络 API，等待更久再判「未命中」。 */
    private static final long SUPER_LYRIC_MODULE_ONLY_WAIT_MS = 3_200L;
    private static final ExecutorService PARSE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SuperLyricParse");
        thread.setDaemon(true);
        // 与显示链路衔接：略提高优先级，缩短 Binder 回调 → 逐字融合首包延迟。
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private static final AtomicInteger PARSE_GENERATION = new AtomicInteger(0);
    /** Binder 高频推送时只保留最新一包，解析线程 drain 后处理，避免队列堆积导致逐字滞后。 */
    private static final AtomicReference<PendingLyricEvent> PENDING_LYRIC_EVENT = new AtomicReference<>(null);
    private static volatile boolean parseDrainScheduled = false;
    private static final long SUPER_LYRIC_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(12);
    private static final long SUPER_LYRIC_FRESH_ACCEPT_MS = TimeUnit.SECONDS.toMillis(4);
    private static final Object SUPER_LYRIC_LOCK = new Object();
    private static final String[] EXTRA_LYRIC_KEYS = new String[] {
        "super_lyric",
        "superLyric",
        "superlyric",
        "lyric",
        "lyrics",
        "lrc",
        "AUDIO_LYRIC"
    };
    private static final AtomicReference<CachedLyric> LAST_SUPER_LYRIC = new AtomicReference<>(null);
    private static volatile boolean superLyricReceiverRegistered = false;
    private static volatile long superLyricLastEventAt = 0L;

    /**
     * 应用内实时回调：让宿主可以用「SuperLyric 推送」驱动 UI，而不是轮询 fetchFallbackPayload。
     * 使用 WeakReference 避免 Activity 泄漏。
     */
    public interface RealtimeListener {
        void onRealtimeLyric(String publisher,
                             String title,
                             String artist,
                             String text,
                             SuperLyricFallbackPayload payload,
                             long atMs);
    }

    private static final CopyOnWriteArrayList<WeakReference<RealtimeListener>> REALTIME_LISTENERS =
        new CopyOnWriteArrayList<>();

    public static void addRealtimeListener(RealtimeListener listener) {
        if (listener == null) return;
        cleanupRealtimeListeners();
        REALTIME_LISTENERS.add(new WeakReference<>(listener));
        // 与 HChenX/SuperLyricApi 接收端一致：有 UI 监听即应完成 registerReceiver，否则 MIXED 下网络先命中时
        // 永远不会走 fetchFallbackPayload，Binder 未注册会导致逐字融合整段无推送。
        ensureReceiverRegistered();
        replayCachedRealtimeLyricToListener(listener);
    }

    /**
     * 读取已缓存的逐字 payload（不阻塞等待 Binder），供 MIXED 在网络歌词就绪后立即融合。
     */
    public static SuperLyricFallbackPayload peekCachedFallbackPayload(String title,
                                                                    String artist,
                                                                    String sourcePackageName) {
        return getMatchedCachedFallbackPayload(title, artist, sourcePackageName);
    }

    /**
     * 仅模块来源：曲目元数据与 SuperLyric 回调不一致时，仍可用最新 Binder 缓存占位。
     */
    public static SuperLyricFallbackPayload peekLatestFallbackPayload() {
        CachedLyric cached = LAST_SUPER_LYRIC.get();
        if (cached == null || cached.fallbackPayload == null) {
            return null;
        }
        long age = System.currentTimeMillis() - cached.timestampMs;
        if (age > SUPER_LYRIC_CACHE_TTL_MS) {
            return null;
        }
        return cached.fallbackPayload;
    }

    /** 与官方 Demo {@code SuperLyricHelper.isAvailable()} 一致。 */
    public static boolean isServiceAvailable() {
        try {
            return SuperLyricHelper.isAvailable();
        } catch (Throwable t) {
            LogHelper.w(TAG, "SuperLyric isAvailable 失败: " + t.getMessage());
            return false;
        }
    }

    private static void replayCachedRealtimeLyricToListener(RealtimeListener listener) {
        if (listener == null) {
            return;
        }
        CachedLyric cached = LAST_SUPER_LYRIC.get();
        if (cached == null || TextUtils.isEmpty(cached.lyrics)) {
            return;
        }
        long age = System.currentTimeMillis() - cached.timestampMs;
        if (age > SUPER_LYRIC_CACHE_TTL_MS) {
            return;
        }
        try {
            listener.onRealtimeLyric(
                cached.publisher,
                cached.title,
                cached.artist,
                cached.lyrics,
                cached.fallbackPayload,
                cached.timestampMs
            );
        } catch (Throwable ignored) {
        }
    }

    public static void removeRealtimeListener(RealtimeListener listener) {
        if (listener == null) return;
        for (WeakReference<RealtimeListener> ref : REALTIME_LISTENERS) {
            RealtimeListener l = ref != null ? ref.get() : null;
            if (l == null || l == listener) {
                REALTIME_LISTENERS.remove(ref);
            }
        }
    }

    private static void cleanupRealtimeListeners() {
        for (WeakReference<RealtimeListener> ref : REALTIME_LISTENERS) {
            if (ref == null || ref.get() == null) {
                REALTIME_LISTENERS.remove(ref);
            }
        }
    }

    private static void notifyRealtimeListeners(CachedLyric lyric) {
        if (lyric == null || TextUtils.isEmpty(lyric.lyrics)) return;
        cleanupRealtimeListeners();
        for (WeakReference<RealtimeListener> ref : REALTIME_LISTENERS) {
            RealtimeListener l = ref != null ? ref.get() : null;
            if (l == null) continue;
            try {
                l.onRealtimeLyric(
                    lyric.publisher,
                    lyric.title,
                    lyric.artist,
                    lyric.lyrics,
                    lyric.fallbackPayload,
                    lyric.timestampMs
                );
            } catch (Throwable ignored) {
            }
        }
    }
    private static final ISuperLyricReceiver SUPER_LYRIC_RECEIVER = new ISuperLyricReceiver.Stub() {
        @Override
        public void onLyric(String publisher, SuperLyricData data) throws RemoteException {
            if (data == null) {
                return;
            }
            final String publisherSafe = safeTrim(publisher);
            final int generation = PARSE_GENERATION.incrementAndGet();
            PENDING_LYRIC_EVENT.set(new PendingLyricEvent(publisherSafe, data, generation));
            scheduleParseDrain();
        }

        @Override
        public void onStop(String publisher, SuperLyricData data) throws RemoteException {
            // 保留最近一次歌词缓存，避免 stop 瞬间导致界面闪断。
        }
    };

    private SuperLyricApi() {
    }

    private static void scheduleParseDrain() {
        if (parseDrainScheduled) {
            return;
        }
        parseDrainScheduled = true;
        PARSE_EXECUTOR.execute(SuperLyricApi::drainPendingLyricEvents);
    }

    /**
     * 合并 Binder 高频回调：始终处理最新 {@link PendingLyricEvent}，避免旧包排队拖慢逐字刷新。
     */
    private static void drainPendingLyricEvents() {
        try {
            for (;;) {
                PendingLyricEvent event = PENDING_LYRIC_EVENT.getAndSet(null);
                if (event == null) {
                    return;
                }
                handleLyricEvent(event.publisher, event.data, event.generation);
                if (PENDING_LYRIC_EVENT.get() == null) {
                    return;
                }
            }
        } finally {
            parseDrainScheduled = false;
            if (PENDING_LYRIC_EVENT.get() != null) {
                scheduleParseDrain();
            }
        }
    }

    /**
     * Binder 线程仅入队；逐字解析与 LRC 拼装在工作线程完成，避免阻塞 IPC 回调。
     */
    private static void handleLyricEvent(String publisher, SuperLyricData data, int generation) {
        if (generation != PARSE_GENERATION.get()) {
            return;
        }
        SuperLyricFallbackPayload fallbackPayload = buildFallbackPayloadFromData(data);
        String title = data.hasTitle() ? safeTrim(data.getTitle()) : "";
        String artist = data.hasArtist() ? safeTrim(data.getArtist()) : "";

        String normalized = buildCacheLyricsText(data, fallbackPayload);
        if (TextUtils.isEmpty(normalized)) {
            return;
        }

        CachedLyric previous = LAST_SUPER_LYRIC.get();
        CachedLyric next = new CachedLyric(
            publisher,
            title,
            artist,
            normalized,
            fallbackPayload,
            System.currentTimeMillis()
        );
        if (shouldKeepPreviousLyric(previous, next)) {
            superLyricLastEventAt = System.currentTimeMillis();
            synchronized (SUPER_LYRIC_LOCK) {
                SUPER_LYRIC_LOCK.notifyAll();
            }
            LogHelper.d(TAG, "⏭ 忽略低价值 SuperLyric 覆盖: " + normalized);
            return;
        }
        if (generation != PARSE_GENERATION.get()) {
            return;
        }
        LAST_SUPER_LYRIC.set(next);
        superLyricLastEventAt = System.currentTimeMillis();
        synchronized (SUPER_LYRIC_LOCK) {
            SUPER_LYRIC_LOCK.notifyAll();
        }
        notifyRealtimeListeners(next);
        int wordCount = fallbackPayload != null && fallbackPayload.hasValidWords()
            ? fallbackPayload.wordTimestamps.size() : 0;
        LogHelper.d(TAG, "🎯 SuperLyric 回调命中: publisher=" + publisher
            + ", title=" + title + ", artist=" + artist + ", len=" + normalized.length()
            + ", words=" + wordCount);
    }

    /**
     * 有逐字 payload 时优先用单行文本入缓存，跳过 extras/metadata 全量拼装与 JSON 探测。
     */
    private static String buildCacheLyricsText(SuperLyricData data, SuperLyricFallbackPayload payload) {
        if (payload != null && !TextUtils.isEmpty(payload.text)) {
            String line = payload.text.trim();
            if (payload.lineStartMs > 0L) {
                return formatLrcTime(payload.lineStartMs) + line;
            }
            return line;
        }
        String raw = buildLyricsFromData(data);
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        String normalized = normalizeLyrics(raw);
        if (!TextUtils.isEmpty(normalized)) {
            return normalized;
        }
        return raw.trim();
    }

    public static String fetchLyrics(Context context,
                                     MediaController controller,
                                     String title,
                                     String artist,
                                     String sourcePackageName) {
        String fromBinder = fetchFromSuperLyricService(title, artist, sourcePackageName);
        if (!TextUtils.isEmpty(fromBinder)) {
            LogHelper.d(TAG, "✅ SuperLyric Binder 命中歌词，len=" + fromBinder.length());
            return fromBinder;
        }

        List<String> candidates = new ArrayList<>();

        tryCollectFromExtras(controller, candidates);
        tryCollectFromMetadata(controller, candidates);

        for (String candidate : candidates) {
            String normalized = normalizeLyrics(candidate);
            if (!TextUtils.isEmpty(normalized)) {
                LogHelper.d(TAG, "✅ SuperLyricApi 命中歌词，len=" + normalized.length());
                return normalized;
            }
        }

        LogHelper.d(TAG, "SuperLyricApi 未命中，title=" + (title != null ? title : "")
            + ", artist=" + (artist != null ? artist : "")
            + ", pkg=" + (sourcePackageName != null ? sourcePackageName : ""));
        return "";
    }

    /**
     * 仅 SuperLyric 模块来源模式：只读 Binder 缓存的整曲 LRC，不回落 MediaSession extras/元数据。
     */
    public static String fetchLyricsFromModuleBinderOnly(String title,
                                                        String artist,
                                                        String sourcePackageName) {
        String fromBinder = fetchFromSuperLyricService(title, artist, sourcePackageName);
        if (!TextUtils.isEmpty(fromBinder)) {
            LogHelper.d(TAG, "✅ SuperLyric 模块整曲 Binder 命中，len=" + fromBinder.length());
            return fromBinder;
        }
        LogHelper.d(TAG, "SuperLyric 模块整曲未命中，title=" + (title != null ? title : "")
            + ", artist=" + (artist != null ? artist : "")
            + ", pkg=" + (sourcePackageName != null ? sourcePackageName : ""));
        return "";
    }

    public static SuperLyricFallbackPayload fetchFallbackPayload(String title,
                                                                 String artist,
                                                                 String sourcePackageName) {
        return fetchFallbackPayload(title, artist, sourcePackageName, SUPER_LYRIC_WAIT_MS);
    }

    public static SuperLyricFallbackPayload fetchFallbackPayloadForModuleOnly(String title,
                                                                              String artist,
                                                                              String sourcePackageName) {
        return fetchFallbackPayload(title, artist, sourcePackageName, SUPER_LYRIC_MODULE_ONLY_WAIT_MS);
    }

    public static SuperLyricFallbackPayload fetchFallbackPayload(String title,
                                                                 String artist,
                                                                 String sourcePackageName,
                                                                 long waitMs) {
        if (!isServiceAvailable()) {
            return null;
        }

        try {
            ensureSuperLyricReceiverRegistered();
            SuperLyricFallbackPayload fromCache = getMatchedCachedFallbackPayload(title, artist, sourcePackageName);
            if (fromCache != null) {
                return fromCache;
            }

            long waitUntil = System.currentTimeMillis() + Math.max(0L, waitMs);
            while (System.currentTimeMillis() < waitUntil) {
                synchronized (SUPER_LYRIC_LOCK) {
                    SUPER_LYRIC_LOCK.wait(Math.min(220L, Math.max(80L, waitMs / 8)));
                }
                fromCache = getMatchedCachedFallbackPayload(title, artist, sourcePackageName);
                if (fromCache != null) {
                    return fromCache;
                }
            }
            if (waitMs >= SUPER_LYRIC_MODULE_ONLY_WAIT_MS) {
                return peekLatestFallbackPayload();
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "读取 SuperLyric 兜底数据失败: " + t.getMessage());
        }
        return null;
    }

    private static String fetchFromSuperLyricService(String title, String artist, String sourcePackageName) {
        try {
            if (!SuperLyricHelper.isAvailable()) {
                LogHelper.d(TAG, "SuperLyric 服务不可用");
                return "";
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "检测 SuperLyric 服务可用性失败: " + t.getMessage());
            return "";
        }

        try {
            ensureSuperLyricReceiverRegistered();
            String fromCache = getMatchedCachedLyrics(title, artist, sourcePackageName);
            if (!TextUtils.isEmpty(fromCache)) {
                return fromCache;
            }

            long waitUntil = System.currentTimeMillis() + SUPER_LYRIC_WAIT_MS;
            while (System.currentTimeMillis() < waitUntil) {
                synchronized (SUPER_LYRIC_LOCK) {
                    SUPER_LYRIC_LOCK.wait(180L);
                }
                fromCache = getMatchedCachedLyrics(title, artist, sourcePackageName);
                if (!TextUtils.isEmpty(fromCache)) {
                    return fromCache;
                }
            }
            LogHelper.d(TAG, "SuperLyric 等待超时，最近事件距今(ms)="
                + Math.max(0L, System.currentTimeMillis() - superLyricLastEventAt));
            return "";
        } catch (Throwable t) {
            LogHelper.w(TAG, "读取 SuperLyric Binder 失败: " + t.getMessage());
            return "";
        }
    }

    /**
     * 预先注册 {@link ISuperLyricReceiver}（幂等）。
     * 与官方示例在启动时 {@code SuperLyricHelper.registerReceiver} 对齐；MiRoot 在添加实时监听或歌词页启动时调用，
     * 避免仅依赖「拉整曲/兜底」路径才注册导致的首包延迟与 MIXED 模式下无 Binder 连接。
     *
     * @see <a href="https://github.com/HChenX/SuperLyricApi">SuperLyricApi</a>
     */
    public static void ensureReceiverRegistered() {
        try {
            if (!SuperLyricHelper.isAvailable()) {
                return;
            }
        } catch (Throwable t) {
            LogHelper.w(TAG, "SuperLyric 预热：isAvailable 失败: " + t.getMessage());
            return;
        }
        try {
            ensureSuperLyricReceiverRegistered();
        } catch (Throwable t) {
            LogHelper.w(TAG, "SuperLyric 预热：registerReceiver 失败: " + t.getMessage());
        }
    }

    private static void ensureSuperLyricReceiverRegistered() {
        synchronized (SUPER_LYRIC_LOCK) {
            boolean alreadyRegistered = false;
            if (superLyricReceiverRegistered) {
                try {
                    alreadyRegistered = SuperLyricHelper.isReceiverRegistered(SUPER_LYRIC_RECEIVER);
                } catch (Throwable ignored) {
                    // 查询失败时按未注册处理，继续尝试重绑。
                }
            }
            if (alreadyRegistered) {
                return;
            }
            try {
                SuperLyricHelper.registerReceiver(SUPER_LYRIC_RECEIVER);
                superLyricReceiverRegistered = true;
                LogHelper.d(TAG, "✅ SuperLyric 常驻接收器已注册");
            } catch (Throwable t) {
                superLyricReceiverRegistered = false;
                throw t;
            }
        }
    }

    /**
     * 与官方 Demo 的 {@code unregisterReceiver} 对应；进程级常驻接收建议只移除 {@link RealtimeListener}，
     * 勿在投屏 Activity 销毁时调用，否则「仅 SuperLyric」模式会整段收不到 Binder 推送。
     */
    public static void releaseReceiver() {
        synchronized (SUPER_LYRIC_LOCK) {
            if (!superLyricReceiverRegistered) {
                return;
            }
            try {
                SuperLyricHelper.unregisterReceiver(SUPER_LYRIC_RECEIVER);
                LogHelper.d(TAG, "✅ SuperLyric 接收器已注销");
            } catch (Throwable t) {
                LogHelper.w(TAG, "注销 SuperLyric 接收器失败: " + t.getMessage());
            } finally {
                superLyricReceiverRegistered = false;
                LAST_SUPER_LYRIC.set(null);
                PENDING_LYRIC_EVENT.set(null);
                PARSE_GENERATION.incrementAndGet();
            }
        }
    }

    private static void tryCollectFromExtras(MediaController controller, List<String> out) {
        if (controller == null) return;
        try {
            Bundle extras = controller.getExtras();
            if (extras == null) return;
            String[] keys = new String[] {
                "super_lyric",
                "superLyric",
                "superlyric",
                "lyric",
                "lyrics",
                "lrc",
                "AUDIO_LYRIC"
            };
            for (String key : keys) {
                try {
                    String value = extras.getString(key);
                    if (!TextUtils.isEmpty(value)) {
                        out.add(value);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "读取 MediaSession extras 失败: " + e.getMessage());
        }
    }

    private static void tryCollectFromMetadata(MediaController controller, List<String> out) {
        if (controller == null) return;
        try {
            MediaMetadata metadata = controller.getMetadata();
            if (metadata == null) return;
            String[] keys = new String[] {
                "android.media.metadata.LYRICS",
                "android.media.metadata.DISPLAY_SUBTITLE",
                "android.media.metadata.DISPLAY_DESCRIPTION",
                "lyrics",
                "lyric",
                "lrc"
            };
            for (String key : keys) {
                try {
                    String value = metadata.getString(key);
                    if (!TextUtils.isEmpty(value)) {
                        out.add(value);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "读取 MediaMetadata 失败: " + e.getMessage());
        }
    }

    private static String normalizeLyrics(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";

        try {
            String parsed = QishuiLyricsJsonParser.INSTANCE.extractLyricContent(text);
            if (!TextUtils.isEmpty(parsed)) {
                return parsed.trim();
            }
        } catch (Exception ignored) {
        }

        if (looksLikeLyrics(text)) {
            return text;
        }
        return "";
    }

    private static boolean looksLikeLyrics(String s) {
        if (TextUtils.isEmpty(s)) return false;
        if (s.contains("\n")) return true;
        return s.matches(".*\\[\\d{1,2}:\\d{2}(\\.\\d{2,3})?].*");
    }

    private static String getMatchedCachedLyrics(String title, String artist, String sourcePackageName) {
        CachedLyric cached = LAST_SUPER_LYRIC.get();
        if (cached == null) return "";
        long age = System.currentTimeMillis() - cached.timestampMs;
        if (age > SUPER_LYRIC_CACHE_TTL_MS) {
            return "";
        }
        if (age <= SUPER_LYRIC_FRESH_ACCEPT_MS) {
            // 切歌瞬间允许先用最新回调，降低“收到回调但匹配字段缺失”导致的漏词概率。
            LogHelper.d(TAG, "⚡ 使用最新 SuperLyric 缓存(宽松), ageMs=" + age);
            return cached.lyrics;
        }
        if (!isLikelyTrackMatched(title, artist, sourcePackageName, cached)) {
            LogHelper.d(TAG, "SuperLyric 缓存未匹配: req=" + safeTrim(sourcePackageName)
                + " / " + safeTrim(title) + " - " + safeTrim(artist)
                + ", cached=" + cached.publisher + " / " + cached.title + " - " + cached.artist
                + ", ageMs=" + age);
            return "";
        }
        return cached.lyrics;
    }

    private static SuperLyricFallbackPayload getMatchedCachedFallbackPayload(String title, String artist, String sourcePackageName) {
        CachedLyric cached = LAST_SUPER_LYRIC.get();
        if (cached == null || cached.fallbackPayload == null) return null;
        long age = System.currentTimeMillis() - cached.timestampMs;
        if (age > SUPER_LYRIC_CACHE_TTL_MS) {
            return null;
        }
        if (age <= SUPER_LYRIC_FRESH_ACCEPT_MS) {
            return cached.fallbackPayload;
        }
        if (!isLikelyTrackMatched(title, artist, sourcePackageName, cached)) {
            return null;
        }
        return cached.fallbackPayload;
    }

    private static boolean isLikelyTrackMatched(String title, String artist, String sourcePackageName, CachedLyric cached) {
        if (cached == null) return false;
        if (TextUtils.isEmpty(cached.lyrics)) return false;

        // publisher 仅软匹配：部分 SuperLyric 发布链路不是音乐 App 本包，不能强拦截。
        boolean publisherMismatch = !TextUtils.isEmpty(sourcePackageName)
            && !TextUtils.isEmpty(cached.publisher)
            && !sourcePackageName.equalsIgnoreCase(cached.publisher);

        boolean titleOk = softEquals(title, cached.title);
        boolean artistOk = softEquals(artist, cached.artist);

        if (titleOk && (artistOk || TextUtils.isEmpty(artist) || TextUtils.isEmpty(cached.artist))) {
            return true;
        }
        // 当标题/歌手在回调中缺失时，允许使用最新缓存兜底；若只有 publisher 不一致也不直接拒绝。
        if ((TextUtils.isEmpty(cached.title) || TextUtils.isEmpty(cached.artist)) && !publisherMismatch) {
            return true;
        }
        // 最后兜底：仅 publisher 一致时接受，避免切歌瞬间完全拿不到词。
        return !publisherMismatch;
    }

    private static boolean softEquals(String left, String right) {
        String l = normalizeToken(left);
        String r = normalizeToken(right);
        if (TextUtils.isEmpty(l) || TextUtils.isEmpty(r)) return false;
        return l.equals(r) || l.contains(r) || r.contains(l);
    }

    private static String normalizeToken(String value) {
        if (value == null) return "";
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        return s
            .replace(" ", "")
            .replace("\t", "")
            .replace("-", "")
            .replace("_", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "");
    }

    private static boolean shouldKeepPreviousLyric(CachedLyric previous, CachedLyric incoming) {
        if (previous == null || incoming == null) return false;
        if (TextUtils.isEmpty(previous.lyrics) || TextUtils.isEmpty(incoming.lyrics)) return false;
        if (!isLikelyTrackMatched(incoming.title, incoming.artist, incoming.publisher, previous)) {
            return false;
        }
        boolean incomingHasWords = incoming.fallbackPayload != null && incoming.fallbackPayload.hasValidWords();
        boolean previousHasWords = previous.fallbackPayload != null && previous.fallbackPayload.hasValidWords();
        // 新回调带逐字而旧缓存无逐字：必须更新（各音乐 App 常见）
        if (incomingHasWords && !previousHasWords) {
            return false;
        }
        // 同句文本下刷新逐字时间轴
        if (incomingHasWords && previousHasWords
            && TextUtils.equals(normalizeToken(previous.lyrics), normalizeToken(incoming.lyrics))) {
            return false;
        }
        // 同曲目下，避免把“作词/作曲”等短元信息覆盖成仅 1 行展示。
        return isLowValueLyric(incoming.lyrics) && isRicherLyric(previous.lyrics, incoming.lyrics);
    }

    private static boolean isRicherLyric(String base, String candidate) {
        if (TextUtils.isEmpty(base)) return false;
        if (TextUtils.isEmpty(candidate)) return true;
        int baseLines = countLines(base);
        int candidateLines = countLines(candidate);
        if (baseLines > candidateLines) return true;
        return base.length() > candidate.length() * 2;
    }

    private static int countLines(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private static boolean isLowValueLyric(String lyric) {
        if (TextUtils.isEmpty(lyric)) return true;
        String text = lyric.trim();
        if (text.isEmpty()) return true;
        if (text.contains("\n")) return false;
        String normalized = text.replace(" ", "");
        if (normalized.matches(".*\\[\\d{1,2}:\\d{2}(\\.\\d{2,3})?].*")) return false;
        if (normalized.length() <= 12 && (
            normalized.startsWith("作词") ||
            normalized.startsWith("作曲") ||
            normalized.startsWith("编曲") ||
            normalized.startsWith("监制") ||
            normalized.startsWith("制作人") ||
            normalized.startsWith("词：") ||
            normalized.startsWith("曲：") ||
            normalized.startsWith("原唱")
        )) {
            return true;
        }
        // 不能按「短句长度」直接判低价值：
        // 真实歌词里大量存在 2~6 字短句（尤其中文副歌），误判会导致实时回调被吞掉，出现“偶发不更新”。
        return false;
    }

    private static String buildLyricsFromData(SuperLyricData data) {
        if (data == null) return "";
        List<String> lines = new ArrayList<>();
        appendLine(lines, data.hasLyric() ? data.getLyric() : null);
        appendLine(lines, data.hasSecondary() ? data.getSecondary() : null);
        appendLine(lines, data.hasTranslation() ? data.getTranslation() : null);
        if (lines.isEmpty() && data.hasExtra()) {
            appendLyricsFromExtra(lines, data.getExtra());
        }
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static void appendLine(List<String> out, SuperLyricLine line) {
        if (line == null) return;
        String text = SuperLyricPayloadParser.resolveLineText(line);
        if (TextUtils.isEmpty(text)) return;
        long start = SuperLyricPayloadParser.resolveLineStartMs(line);
        if (start > 0L) {
            out.add(formatLrcTime(start) + text);
        } else {
            out.add(text);
        }
    }

    private static SuperLyricFallbackPayload buildFallbackPayloadFromData(SuperLyricData data) {
        return SuperLyricPayloadParser.buildFallbackPayload(data);
    }

    private static void appendLyricsFromExtra(List<String> out, Bundle extra) {
        if (extra == null) return;
        for (String key : EXTRA_LYRIC_KEYS) {
            String value = readStringFromBundle(extra, key);
            if (!TextUtils.isEmpty(value)) {
                out.add(value);
            }
        }
    }

    private static String readStringFromBundle(Bundle bundle, String key) {
        if (bundle == null || TextUtils.isEmpty(key)) return "";
        try {
            String str = bundle.getString(key);
            if (!TextUtils.isEmpty(str)) {
                return str;
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cs = bundle.getCharSequence(key);
            if (cs != null) {
                return safeTrim(cs.toString());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String formatLrcTime(long timeMs) {
        long safe = Math.max(0L, timeMs);
        long totalSeconds = safe / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long centiseconds = (safe % 1000L) / 10L;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class PendingLyricEvent {
        final String publisher;
        final SuperLyricData data;
        final int generation;

        PendingLyricEvent(String publisher, SuperLyricData data, int generation) {
            this.publisher = publisher;
            this.data = data;
            this.generation = generation;
        }
    }

    private static final class CachedLyric {
        final String publisher;
        final String title;
        final String artist;
        final String lyrics;
        final SuperLyricFallbackPayload fallbackPayload;
        final long timestampMs;

        CachedLyric(String publisher,
                    String title,
                    String artist,
                    String lyrics,
                    SuperLyricFallbackPayload fallbackPayload,
                    long timestampMs) {
            this.publisher = publisher;
            this.title = title;
            this.artist = artist;
            this.lyrics = lyrics;
            this.fallbackPayload = fallbackPayload;
            this.timestampMs = timestampMs;
        }
    }

    public static final class SuperLyricFallbackPayload {
        public final String text;
        /** {@link SuperLyricLine#getStartTime()} 行开始（毫秒）。 */
        public final long lineStartMs;
        /** {@link SuperLyricLine#getEndTime()} 行结束（毫秒），≤{@code lineStartMs} 时视为未知。 */
        public final long lineEndMs;
        /** 由 {@link SuperLyricWord} 解析的模块逐字轴（融合路径用绝对/行相对毫秒）。 */
        public final List<EnhancedLRCParser.WordTimestamp> wordTimestamps;

        SuperLyricFallbackPayload(String text,
                                 long lineStartMs,
                                 long lineEndMs,
                                 List<EnhancedLRCParser.WordTimestamp> wordTimestamps) {
            this.text = safeTrim(text);
            this.lineStartMs = Math.max(0L, lineStartMs);
            this.lineEndMs = Math.max(this.lineStartMs, lineEndMs);
            this.wordTimestamps = wordTimestamps != null
                ? Collections.unmodifiableList(new ArrayList<>(wordTimestamps))
                : Collections.emptyList();
        }

        public boolean hasValidWords() {
            return wordTimestamps != null && !wordTimestamps.isEmpty();
        }
    }
}
