package com.wmqc.miroot.lyrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 外部广播启动/停止音乐投屏（背屏歌词界面）。
 * <p>
 * Action：{@link LyricsIntents#ACTION_OPEN_MUSIC_PROJECTION}；可选 Extra {@link LyricsIntents#EXTRA_MUSIC_PROJECTION_OP}（start/stop），默认 start。
 */
public class LyricsMusicProjectionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION.equals(intent.getAction())) {
            return;
        }
        String op = intent.getStringExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP);
        Intent serviceIntent = new Intent(context.getApplicationContext(), MusicProjectionService.class);
        serviceIntent.setAction(LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION);
        if (op != null) {
            serviceIntent.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP, op);
        }
        context.getApplicationContext().startService(serviceIntent);
    }
}
