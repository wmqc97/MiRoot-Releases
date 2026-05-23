package com.wmqc.miroot.lyrics;

import android.content.ComponentName;
import android.content.Context;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Build;

import java.util.function.Consumer;

/**
 * 通过系统 {@link MediaBrowser} 连接酷我车载 {@code KwMediaSessionService}，再构造 {@link MediaController}。
 * 见《第三方应用获取歌词_MediaSession集成》。
 */
public class KuwoCarMediaSessionHelper {

    public static final String KUWO_PACKAGE = "cn.kuwo.kwmusiccar";
    public static final String KUWO_SESSION_SERVICE_CLASS = "cn.kuwo.mod.mediaSession.KwMediaSessionService";

    private final Context appContext;
    private MediaBrowser browser;

    public KuwoCarMediaSessionHelper(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void disconnect() {
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (Throwable ignored) {
            }
            browser = null;
        }
    }

    /**
     * @param onConnected 成功回调（主线程）
     * @param onFailed    失败或挂起（主线程）
     */
    public void connect(Consumer<MediaController> onConnected, Runnable onFailed) {
        disconnect();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            onFailed.run();
            return;
        }
        ComponentName cn = new ComponentName(KUWO_PACKAGE, KUWO_SESSION_SERVICE_CLASS);
        browser = new MediaBrowser(appContext, cn, new MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                MediaBrowser b = browser;
                if (b == null) {
                    onFailed.run();
                    return;
                }
                try {
                    MediaSession.Token token = b.getSessionToken();
                    MediaController controller = new MediaController(appContext, token);
                    onConnected.accept(controller);
                } catch (Exception e) {
                    LogHelper.w("KuwoCarMediaSession", "创建 MediaController 失败: " + e.getMessage());
                    onFailed.run();
                }
            }

            @Override
            public void onConnectionSuspended() {
                LogHelper.d("KuwoCarMediaSession", "onConnectionSuspended");
                onFailed.run();
            }

            @Override
            public void onConnectionFailed() {
                LogHelper.w("KuwoCarMediaSession", "onConnectionFailed（请确认已安装酷我车载且服务可绑定）");
                onFailed.run();
            }
        }, null);
        browser.connect();
    }
}
