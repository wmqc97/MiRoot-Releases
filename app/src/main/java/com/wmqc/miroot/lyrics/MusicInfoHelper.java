/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.lyrics;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐信息获取工具类
 * 提供多种方法获取音乐播放状态和歌词信息
 */
public class MusicInfoHelper {
    private static final String TAG = "MusicInfoHelper";
    
    /**
     * 音乐信息数据类
     */
    public static class MusicInfo {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String lyrics = "";
        public boolean isPlaying = false;
        public long position = 0;
        public long duration = 0;
        public String packageName = "";
        
        @Override
        public String toString() {
            return String.format("MusicInfo{title='%s', artist='%s', album='%s', lyrics='%s', isPlaying=%s, position=%d, duration=%d, packageName='%s'}",
                title, artist, album, lyrics, isPlaying, position, duration, packageName);
        }
    }
    
    /**
     * 方法1: 通过MediaController获取音乐信息（最通用）
     * 需要NotificationListenerService权限
     */
    public static MusicInfo getMusicInfoFromMediaController(Context context, ComponentName notificationListener) {
        MusicInfo info = new MusicInfo();
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            LogHelper.w(TAG, "MediaController需要Android 5.0+");
            return info;
        }
        
        try {
            MediaSessionManager sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sessionManager == null || notificationListener == null) {
                LogHelper.w(TAG, "MediaSessionManager或NotificationListener为null");
                return info;
            }
            
            List<MediaController> controllers = sessionManager.getActiveSessions(notificationListener);
            if (controllers == null || controllers.isEmpty()) {
                LogHelper.d(TAG, "未找到活动的MediaController");
                return info;
            }
            
            // 使用第一个活动的MediaController
            MediaController controller = controllers.get(0);
            info.packageName = controller.getPackageName();
            LogHelper.d(TAG, "找到MediaController: " + info.packageName);
            
            // 获取元数据
            MediaMetadata metadata = controller.getMetadata();
            if (metadata != null) {
                String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
                
                info.title = title != null ? title : "";
                info.artist = artist != null ? artist : "";
                info.album = album != null ? album : "";
                
                // 尝试从MediaMetadata获取歌词（某些应用可能提供）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    String[] possibleLyricsKeys = {
                        "android.media.metadata.LYRICS",
                        "android.media.metadata.DESCRIPTION",
                        "android.media.metadata.DISPLAY_DESCRIPTION",
                        "android.media.metadata.DISPLAY_SUBTITLE",
                        "lyrics",
                        "lyric",
                        "lrc",
                        "AUDIO_LYRIC",
                        "LYRICS"
                    };
                    for (String key : possibleLyricsKeys) {
                        try {
                            String lyrics = metadata.getString(key);
                            if (lyrics != null && !lyrics.isEmpty()) {
                                info.lyrics = lyrics;
                                LogHelper.d(TAG, "从MediaMetadata获取到歌词: " + key);
                                break;
                            }
                        } catch (Exception e) {
                            // 忽略不存在的key
                        }
                    }
                }

                // 一些播放器会把歌词写进 MediaController extras，而不是 metadata。
                String extrasLyrics = readLyricsFromBundle(controller.getExtras());
                if (!TextUtils.isEmpty(extrasLyrics)) {
                    info.lyrics = extrasLyrics;
                    LogHelper.d(TAG, "从MediaController extras获取到歌词");
                }

                LogHelper.d(TAG, "元数据: title=" + info.title + ", artist=" + info.artist);
            }
            
            // 获取播放状态
            android.media.session.PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                int state = playbackState.getState();
                info.isPlaying = (state == android.media.session.PlaybackState.STATE_PLAYING);
                info.position = playbackState.getPosition();
                try {
                    if (metadata != null) {
                        info.duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    }
                } catch (Exception ignored) {
                    info.duration = 0;
                }
                
                LogHelper.d(TAG, "播放状态: isPlaying=" + info.isPlaying + ", position=" + info.position);
            }
            
        } catch (SecurityException e) {
            LogHelper.wThrottled(
                TAG,
                "权限不足，无法获取 MediaController（通知使用权/MIUI 后台限制？）: " + LogHelper.truncateForLog(e.toString(), 180),
                10 * 60 * 1000L
            );
        } catch (Exception e) {
            LogHelper.e(TAG, "获取MediaController信息失败", e);
        }
        
        return info;
    }
    
    /**
     * 方法2: 从通知中获取音乐信息（适用于在通知中显示歌词的应用）
     * 需要NotificationListenerService权限
     */
    public static MusicInfo getMusicInfoFromNotification(StatusBarNotification sbn) {
        MusicInfo info = new MusicInfo();
        
        if (sbn == null) {
            return info;
        }
        
        try {
            Notification notification = sbn.getNotification();
            if (notification == null) {
                return info;
            }
            
            info.packageName = sbn.getPackageName();
            
            // 获取通知标题和内容
            Bundle extras = notification.extras;
            String title = getCharSequenceAsString(extras, Notification.EXTRA_TITLE);
            String text = getCharSequenceAsString(extras, Notification.EXTRA_TEXT);
            String bigText = getCharSequenceAsString(extras, Notification.EXTRA_BIG_TEXT);
            String subText = getCharSequenceAsString(extras, Notification.EXTRA_SUB_TEXT);
            
            if (!title.isEmpty()) {
                info.title = title;
            }
            if (!text.isEmpty()) {
                if (info.artist.isEmpty()) {
                    info.artist = text;
                } else {
                    info.lyrics = text;
                }
            }
            if (!bigText.isEmpty()) {
                info.lyrics = bigText;
            }
            if (!subText.isEmpty()) {
                if (info.artist.isEmpty()) {
                    info.artist = subText;
                }
            }

            if (info.lyrics.isEmpty()) {
                String extrasLyrics = readLyricsFromBundle(extras);
                if (!TextUtils.isEmpty(extrasLyrics)) {
                    info.lyrics = extrasLyrics;
                    LogHelper.d(TAG, "从通知 extras 深度扫描到歌词");
                }
            }
            
            LogHelper.d(TAG, "从通知获取: title=" + info.title + ", artist=" + info.artist + ", lyrics=" + (info.lyrics.length() > 0 ? "有" : "无"));
            
        } catch (Exception e) {
            LogHelper.e(TAG, "从通知获取音乐信息失败", e);
        }
        
        return info;
    }
    
    /**
     * 方法3: 综合获取（优先MediaController，然后通知）
     */
    public static MusicInfo getMusicInfo(Context context, ComponentName notificationListener, StatusBarNotification sbn) {
        MusicInfo info = new MusicInfo();
        
        // 优先使用MediaController（更准确）
        MusicInfo controllerInfo = getMusicInfoFromMediaController(context, notificationListener);
        if (controllerInfo != null && !controllerInfo.title.isEmpty()) {
            info = controllerInfo;
            LogHelper.d(TAG, "使用MediaController获取的信息");
        }
        
        // 如果MediaController没有歌词，尝试从通知获取
        if (info.lyrics.isEmpty() && sbn != null) {
            MusicInfo notificationInfo = getMusicInfoFromNotification(sbn);
            if (!notificationInfo.lyrics.isEmpty()) {
                info.lyrics = notificationInfo.lyrics;
                LogHelper.d(TAG, "从通知补充歌词信息");
            }
            if (info.title.isEmpty() && !notificationInfo.title.isEmpty()) {
                info.title = notificationInfo.title;
            }
            if (info.artist.isEmpty() && !notificationInfo.artist.isEmpty()) {
                info.artist = notificationInfo.artist;
            }
        }
        
        return info;
    }
    
    /**
     * 通过第三方歌词API获取歌词
     */
    public static String getLyricsFromAPI(Context context, String title, String artist, long durationMs, String musixmatchApiKey) {
        return getLyricsFromAPI(context, title, artist, durationMs, musixmatchApiKey, null, false, true);
    }

    /**
     * 按播放器来源选择歌词匹配方案：
     * - 酷我：背屏优先专用解析链（AUDIO_LYRIC）；网络兜底按包名走酷狗优先策略
     * - 汽水：先 qsgc 再酷狗；网易云/酷狗/酷我/QQ/其他：先酷狗再 qsgc；均失败再走 lrclib / lyrics.ovh
     */
    public static String getLyricsFromAPI(Context context, String title, String artist, long durationMs, String musixmatchApiKey, String sourcePackageName) {
        return getLyricsFromAPI(context, title, artist, durationMs, musixmatchApiKey, sourcePackageName, false, true);
    }

    /**
     * 按来源模式可配置网络匹配策略：
     * - strictTitleArtistMatch=true：酷狗候选须歌名+歌手严格匹配（qsgc 精确直连仍会尝试）
     * - enableSecondaryFallback=false：禁用 lrclib / lyrics.ovh 开放兜底（混合模式首轮由上层再发起宽松请求）
     */
    public static String getLyricsFromAPI(Context context,
                                          String title,
                                          String artist,
                                          long durationMs,
                                          String musixmatchApiKey,
                                          String sourcePackageName,
                                          boolean strictTitleArtistMatch,
                                          boolean enableSecondaryFallback) {
        NetworkLyricsOrchestrator.Payload payload = fetchNetworkLyricsPayload(
            context,
            title,
            artist,
            durationMs,
            musixmatchApiKey,
            sourcePackageName,
            strictTitleArtistMatch,
            enableSecondaryFallback
        );
        return payload.success ? payload.lyrics : "";
    }

    /**
     * 智能网络拉词（含来源标识，供 UI Debug 与日志）。
     */
    public static NetworkLyricsOrchestrator.Payload fetchNetworkLyricsPayload(Context context,
                                                                              String title,
                                                                              String artist,
                                                                              long durationMs,
                                                                              String musixmatchApiKey,
                                                                              String sourcePackageName,
                                                                              boolean strictTitleArtistMatch,
                                                                              boolean enableSecondaryFallback) {
        if (context == null || title == null || title.isEmpty()) {
            LogHelper.w(TAG, "通过API获取歌词失败: 参数无效");
            NetworkLyricsOrchestrator.Payload empty = new NetworkLyricsOrchestrator.Payload();
            empty.error = "参数无效";
            return empty;
        }

        double durationSeconds = durationMs > 0 ? durationMs / 1000.0 : 0.0;
        String resolvedPackage = MusicPlayerLyricsPolicy.resolveNetworkLyricsPackageName(
            context, sourcePackageName);
        MusicPlayerLyricsPolicy.PrimaryStrategy strategy =
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy(resolvedPackage);
        LogHelper.d(TAG, "网络API拉词包名: explicit="
            + (sourcePackageName != null ? sourcePackageName : "")
            + ", resolved=" + resolvedPackage
            + ", strategy=" + strategy);

        return fetchNetworkLyricsPayload(
            context,
            title,
            artist,
            durationMs,
            musixmatchApiKey,
            sourcePackageName,
            strictTitleArtistMatch,
            enableSecondaryFallback,
            false
        );
    }

    public static NetworkLyricsOrchestrator.Payload fetchNetworkLyricsPayload(Context context,
                                                                              String title,
                                                                              String artist,
                                                                              long durationMs,
                                                                              String musixmatchApiKey,
                                                                              String sourcePackageName,
                                                                              boolean strictTitleArtistMatch,
                                                                              boolean enableSecondaryFallback,
                                                                              boolean qishuiQsgcOnlyPrimary) {
        if (context == null || title == null || title.isEmpty()) {
            LogHelper.w(TAG, "通过API获取歌词失败: 参数无效");
            NetworkLyricsOrchestrator.Payload empty = new NetworkLyricsOrchestrator.Payload();
            empty.error = "参数无效";
            return empty;
        }

        double durationSeconds = durationMs > 0 ? durationMs / 1000.0 : 0.0;
        String resolvedPackage = MusicPlayerLyricsPolicy.resolveNetworkLyricsPackageName(
            context, sourcePackageName);
        MusicPlayerLyricsPolicy.PrimaryStrategy strategy =
            MusicPlayerLyricsPolicy.resolvePrimaryStrategy(resolvedPackage);
        LogHelper.d(TAG, "网络API拉词包名: explicit="
            + (sourcePackageName != null ? sourcePackageName : "")
            + ", resolved=" + resolvedPackage
            + ", strategy=" + strategy
            + ", qishuiQsgcOnly=" + qishuiQsgcOnlyPrimary);

        return NetworkLyricsOrchestrator.fetch(
            context,
            title,
            artist,
            durationSeconds,
            musixmatchApiKey,
            resolvedPackage,
            strictTitleArtistMatch,
            enableSecondaryFallback,
            qishuiQsgcOnlyPrimary
        );
    }

    private static String getCharSequenceAsString(Bundle extras, String key) {
        if (extras == null || key == null) return "";
        try {
            CharSequence value = extras.getCharSequence(key);
            return value != null ? value.toString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readLyricsFromBundle(Bundle extras) {
        if (extras == null) return "";
        try {
            // 先走高概率 key，减少遍历开销。
            String[] keys = new String[] {
                "lyric", "lyrics", "lrc", "AUDIO_LYRIC",
                "android.media.metadata.LYRICS",
                "android.media.metadata.DISPLAY_SUBTITLE",
                "android.media.metadata.DISPLAY_DESCRIPTION",
                "music_lyric", "current_lyric", "lyric_content"
            };
            for (String key : keys) {
                String hit = getPotentialLyrics(extras, key);
                if (!TextUtils.isEmpty(hit)) {
                    return hit;
                }
            }

            // 再扫描所有 extras key（兼容厂商私有字段）。
            for (String key : extras.keySet()) {
                if (key == null) continue;
                String lower = key.toLowerCase();
                if (lower.contains("lyric") || lower.contains("lrc")) {
                    String hit = getPotentialLyrics(extras, key);
                    if (!TextUtils.isEmpty(hit)) {
                        return hit;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String getPotentialLyrics(Bundle extras, String key) {
        if (extras == null || key == null) return "";
        try {
            String value = extras.getString(key);
            if (looksLikeLyrics(value)) return value.trim();
        } catch (Exception ignored) {
        }
        try {
            CharSequence value = extras.getCharSequence(key);
            if (value != null && looksLikeLyrics(value.toString())) return value.toString().trim();
        } catch (Exception ignored) {
        }
        try {
            CharSequence[] lines = extras.getCharSequenceArray(key);
            if (lines != null && lines.length > 0) {
                List<String> valid = new ArrayList<>();
                for (CharSequence cs : lines) {
                    if (cs == null) continue;
                    String s = cs.toString();
                    if (looksLikeLyrics(s)) valid.add(s);
                }
                if (!valid.isEmpty()) {
                    return TextUtils.join("\n", valid);
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean looksLikeLyrics(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.isEmpty()) return false;
        if (s.contains("\n")) return true;
        if (s.matches(".*\\[\\d{1,2}:\\d{2}(\\.\\d{2,3})?].*")) return true;
        // 单行兜底：太长的普通文案不当歌词；短句可能是实时行歌词。
        return s.length() >= 6 && s.length() <= 120;
    }
}

