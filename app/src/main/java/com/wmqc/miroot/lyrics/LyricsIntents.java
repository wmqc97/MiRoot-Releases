package com.wmqc.miroot.lyrics;

/**
 * 音乐投屏相关广播 Action / Extra（与外部主题、Tasker、Shell 等对接时使用统一字符串）。
 */
public final class LyricsIntents {

    private LyricsIntents() {}

    public static final String ACTION_OPEN_MUSIC_PROJECTION =
            "com.wmqc.miroot.lyrics.ACTION_OPEN_MUSIC_PROJECTION";

    /** 操作类型：{@link #VALUE_MUSIC_PROJECTION_OP_START} / {@link #VALUE_MUSIC_PROJECTION_OP_STOP}。 */
    public static final String EXTRA_MUSIC_PROJECTION_OP =
            "com.wmqc.miroot.lyrics.EXTRA_MUSIC_PROJECTION_OP";

    public static final String VALUE_MUSIC_PROJECTION_OP_START = "start";

    public static final String VALUE_MUSIC_PROJECTION_OP_STOP = "stop";

    public static final String ACTION_SCREENSHOT_SAVED =
            "com.wmqc.miroot.lyrics.ACTION_SCREENSHOT_SAVED";

    public static final String RELOAD_AUTO_PROJECTION_SETTINGS =
            "com.wmqc.miroot.lyrics.RELOAD_AUTO_PROJECTION_SETTINGS";
}
