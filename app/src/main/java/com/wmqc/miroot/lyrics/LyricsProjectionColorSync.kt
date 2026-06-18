package com.wmqc.miroot.lyrics

/**
 * 投屏歌词统一配色：歌词高亮、边框、跑马灯共用 LyricsColorManager。
 *
 * 固定色模式：所有组件直接使用用户选择的颜色。
 * 随机色模式：所有组件从高饱和色池中按时间间隔自然过渡。
 */
object LyricsProjectionColorSync {

    /** 已改为 LyricsColorManager 驱动，保留兼容。 */
    @JvmStatic
    fun prefetch(colorChangeIntervalMs: Long) {}

    @JvmStatic
    fun bindLyricsView(view: ModernLyricsView, randomColorSwitchEnabled: Boolean, @Suppress("UNUSED_PARAMETER") colorChangeIntervalMs: Long) {
        bindLyricsView(view, randomColorSwitchEnabled)
    }

    @JvmStatic
    fun bindMarqueeLight(view: MarqueeLightView, randomColorSwitchEnabled: Boolean, @Suppress("UNUSED_PARAMETER") colorChangeIntervalMs: Long) {
        bindMarqueeLight(view, randomColorSwitchEnabled)
    }

    @JvmStatic
    fun bindLyricsView(view: ModernLyricsView, randomColorSwitchEnabled: Boolean) {
        LyricsColorManager.INSTANCE.setRandomMode(randomColorSwitchEnabled)
        view.setColorSyncCallback(object : ModernLyricsView.ColorSyncCallback {
            override fun getSyncColor(): Int = LyricsColorManager.INSTANCE.getColor()
            override fun advanceColor() {}
        })
        view.setColorSyncEnabled(true)
    }

    @JvmStatic
    fun bindMarqueeLight(view: MarqueeLightView, randomColorSwitchEnabled: Boolean) {
        LyricsColorManager.INSTANCE.setRandomMode(randomColorSwitchEnabled)
        view.setColorSyncCallback { LyricsColorManager.INSTANCE.getColor() }
        view.setColorSyncEnabled(true)
    }
}
