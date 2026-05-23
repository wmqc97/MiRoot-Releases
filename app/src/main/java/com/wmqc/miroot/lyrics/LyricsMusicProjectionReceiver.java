package com.wmqc.miroot.lyrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.wmqc.miroot.ui.music.MusicProjectionController;

/**
 * 外部广播：一键切换/显式启停音乐投屏，或查询投屏状态（背屏歌词界面）。
 * <p>
 * 启停：{@link LyricsIntents#ACTION_OPEN_MUSIC_PROJECTION}。未带 {@link LyricsIntents#EXTRA_MUSIC_PROJECTION_OP}
 * 或 extra 为空时：若背屏歌词界面已在运行则停止，否则启动（与 App 内按钮逻辑一致的状态判定）。
 * 显式传入 {@code start}/{@code stop} 时仍按原语义转发，兼容旧集成。
 * <p>
 * 查询：{@link LyricsIntents#ACTION_QUERY_MUSIC_PROJECTION_STATUS}；回执默认 {@link LyricsIntents#ACTION_MUSIC_PROJECTION_STATUS_RESULT}。
 */
public class LyricsMusicProjectionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (LyricsIntents.ACTION_QUERY_MUSIC_PROJECTION_STATUS.equals(action)) {
            sendQueryReply(context, intent);
            return;
        }
        if (!LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION.equals(action)) {
            return;
        }
        String op = intent.getStringExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP);
        if (op == null || op.isEmpty()) {
            boolean active = MusicProjectionController.INSTANCE.isMusicProjectionUiActive();
            op = active ? LyricsIntents.VALUE_MUSIC_PROJECTION_OP_STOP : LyricsIntents.VALUE_MUSIC_PROJECTION_OP_START;
        }
        Intent serviceIntent = new Intent(context.getApplicationContext(), MusicProjectionService.class);
        serviceIntent.setAction(LyricsIntents.ACTION_OPEN_MUSIC_PROJECTION);
        serviceIntent.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_OP, op);
        context.getApplicationContext().startService(serviceIntent);
    }

    /**
     * 回执不调用 {@link Intent#setPackage(String)}，以便第三方进程接收（与 {@link com.wmqc.miroot.car.VehicleStatusQueryService} 一致）。
     */
    private void sendQueryReply(Context context, Intent request) {
        boolean running = MusicProjectionController.INSTANCE.isMusicProjectionUiActive();
        String replyAction = request.getStringExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_REPLY_ACTION);
        if (replyAction == null || replyAction.isEmpty()) {
            replyAction = LyricsIntents.ACTION_MUSIC_PROJECTION_STATUS_RESULT;
        }
        String requestId = request.getStringExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_REQUEST_ID);
        if (requestId == null) {
            requestId = "";
        }
        Intent reply = new Intent(replyAction);
        reply.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_REQUEST_ID, requestId);
        reply.putExtra("success", "true");
        reply.putExtra(LyricsIntents.EXTRA_MUSIC_PROJECTION_RUNNING, running ? "true" : "false");
        context.getApplicationContext().sendBroadcast(reply);
    }
}
