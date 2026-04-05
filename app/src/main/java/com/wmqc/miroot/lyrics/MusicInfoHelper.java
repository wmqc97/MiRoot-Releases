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
import android.service.notification.StatusBarNotification;
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
                        "lyrics",
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
                
                LogHelper.d(TAG, "元数据: title=" + info.title + ", artist=" + info.artist);
            }
            
            // 获取播放状态
            android.media.session.PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                int state = playbackState.getState();
                info.isPlaying = (state == android.media.session.PlaybackState.STATE_PLAYING);
                info.position = playbackState.getPosition();
                info.duration = playbackState.getBufferedPosition();
                
                LogHelper.d(TAG, "播放状态: isPlaying=" + info.isPlaying + ", position=" + info.position);
            }
            
        } catch (SecurityException e) {
            LogHelper.e(TAG, "权限不足，无法获取MediaController: " + e.getMessage());
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
            String title = notification.extras.getString(Notification.EXTRA_TITLE, "");
            String text = notification.extras.getString(Notification.EXTRA_TEXT, "");
            String bigText = notification.extras.getString(Notification.EXTRA_BIG_TEXT, "");
            String subText = notification.extras.getString(Notification.EXTRA_SUB_TEXT, "");
            
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
    public static String getLyricsFromAPI(Context context, String title, String artist, String musixmatchApiKey) {
        if (context == null || title == null || title.isEmpty()) {
            LogHelper.w(TAG, "通过API获取歌词失败: 参数无效");
            return "";
        }
        
        LogHelper.d(TAG, "通过API获取歌词: " + title + " - " + artist);
        
        // 使用标准API接口 /lyrics/search 获取歌词
        List<LyricsMatcher.Candidate> candidates = LyricsAPIClient.searchLyrics(
            context, title, artist, "", 0.0, musixmatchApiKey);
        
        if (candidates != null && !candidates.isEmpty()) {
            // 获取分数最高的候选结果
            LyricsMatcher.Candidate bestCandidate = candidates.get(0);
            LogHelper.d(TAG, "✅ 找到最佳歌词候选: " + bestCandidate.title + " - " + bestCandidate.artist + 
                      " (分数: " + String.format("%.2f", bestCandidate.score) + ", ID: " + bestCandidate.id + ")");
            
            // 使用 /lyrics/by-id 获取完整歌词内容
            if (bestCandidate.id != null && !bestCandidate.id.isEmpty()) {
                LyricsAPIClient.LyricsResult result = LyricsAPIClient.getLyricsById(
                    context, bestCandidate.id, bestCandidate.provider);
                
                if (result.success && result.lyrics != null && !result.lyrics.isEmpty()) {
                    LogHelper.d(TAG, "✅ 成功获取歌词内容，长度: " + result.lyrics.length());
                    return result.lyrics;
                } else {
                    LogHelper.w(TAG, "❌ 获取歌词内容失败: " + result.error);
                }
            } else {
                LogHelper.w(TAG, "⚠️ 候选结果ID为空，无法获取歌词内容");
            }
            return "";
        } else {
            LogHelper.w(TAG, "❌ 搜索歌词失败: 未找到候选结果");
            return "";
        }
    }
}

