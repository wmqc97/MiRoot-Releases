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

    /**
     * 为 true 时仅 {@code am start --display N} 背屏直启（设置页「开始投屏」）；
     * 不回退主屏占位迁屏。广播/自动投屏勿带此 Extra。
     */
    public static final String EXTRA_MUSIC_PROJECTION_DIRECT_REAR_ONLY =
            "com.wmqc.miroot.lyrics.EXTRA_MUSIC_PROJECTION_DIRECT_REAR_ONLY";

    /**
     * 查询当前音乐投屏（背屏歌词界面）是否处于活跃状态；不启动或停止投屏。
     * 回执见 {@link #ACTION_MUSIC_PROJECTION_STATUS_RESULT}，可通过 {@link #EXTRA_MUSIC_PROJECTION_REPLY_ACTION} 自定义。
     */
    public static final String ACTION_QUERY_MUSIC_PROJECTION_STATUS =
            "com.wmqc.miroot.lyrics.ACTION_QUERY_MUSIC_PROJECTION_STATUS";

    /** 查询回执的默认 Action（与车控 {@code ACTION_VEHICLE_STATUS_RESULT} 用法类似）。 */
    public static final String ACTION_MUSIC_PROJECTION_STATUS_RESULT =
            "com.wmqc.miroot.lyrics.ACTION_MUSIC_PROJECTION_STATUS_RESULT";

    /**
     * 投屏状态变化时 MiRoot 主动发出的广播（开始/结束背屏歌词界面时）。
     * Extra：{@link #EXTRA_MUSIC_PROJECTION_RUNNING}，值为字符串 {@code true}/{@code false}。
     */
    public static final String ACTION_MUSIC_PROJECTION_STATE_CHANGED =
            "com.wmqc.miroot.lyrics.ACTION_MUSIC_PROJECTION_STATE_CHANGED";

    /** 与车控查询一致：可选，回执中原样带回。 */
    public static final String EXTRA_MUSIC_PROJECTION_REQUEST_ID = "requestId";

    /** 可选；自定义回执 Action，不传则使用 {@link #ACTION_MUSIC_PROJECTION_STATUS_RESULT}。 */
    public static final String EXTRA_MUSIC_PROJECTION_REPLY_ACTION = "replyAction";

    /**
     * 查询回执与 {@link #ACTION_MUSIC_PROJECTION_STATE_CHANGED} 中：背屏歌词投屏是否活跃。
     * 值为字符串 {@code true}/{@code false}（与车控 {@code success} 一致，便于 MAML 等）。
     */
    public static final String EXTRA_MUSIC_PROJECTION_RUNNING = "running";

    public static final String ACTION_SCREENSHOT_SAVED =
            "com.wmqc.miroot.lyrics.ACTION_SCREENSHOT_SAVED";

    public static final String RELOAD_AUTO_PROJECTION_SETTINGS =
            "com.wmqc.miroot.lyrics.RELOAD_AUTO_PROJECTION_SETTINGS";
}
