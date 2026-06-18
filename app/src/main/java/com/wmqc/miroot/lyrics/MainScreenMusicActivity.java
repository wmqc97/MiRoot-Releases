
package com.wmqc.miroot.lyrics;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;

import com.wmqc.miroot.MainActivity;
import com.wmqc.miroot.R;
import com.wmqc.miroot.license.OfflineActivationRepository;
import com.wmqc.miroot.RearDisplayInputHelper;
import com.wmqc.miroot.rear.ProjectionOngoingNotifications;
import com.wmqc.miroot.rear.ProjectionOnlyNotificationHelper;
import com.wmqc.miroot.service.MiRootNotificationListenerService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main screen landscape lyrics display, independent from RearScreenLyricsActivity.
 * Launched via QS tile on display 0 in landscape fullscreen.
 * Shares settings via LyricsSettingsRepository SharedPreferences.
 * Can run simultaneously with back screen RearScreenLyricsActivity.
 */
public class MainScreenMusicActivity extends ComponentActivity {
    private static final String TAG = "MainScreenMusicActivity";
    private static MainScreenMusicActivity currentInstance;
    public static MainScreenMusicActivity getCurrentInstance() { return currentInstance; }

    // 鈹€鈹€ Settings constants 鈹€鈹€
    private static final String PREFS_NAME = "LyricsSettings";
    private static final String KEY_TEXT_SIZE = "textSize";
    private static final String KEY_BACKGROUND_TEXTURE_SIZE = "backgroundTextureSize";
    private static final String KEY_NORMAL_LYRICS_ALPHA = "normalLyricsAlpha";
    private static final String KEY_BACKGROUND_TEXTURE_ALPHA = "backgroundTextureAlpha";
    private static final String KEY_WORD_BY_WORD = "wordByWord";
    private static final String KEY_SHUFFLE_SPLIT_EFFECT = "shuffleSplitEffect";
    private static final String KEY_SHUFFLE_SPLIT_MULTICOLOR = "shuffleSplitMulticolor";
    private static final String KEY_SHUFFLE_SPLIT_MODE = "shuffleSplitMode";
    private static final String KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE = "shuffleSplitOnlyCurrentLine";
    private static final String KEY_SHUFFLE_SPLIT_SCALE_VARIANCE = "shuffleSplitScaleVariance";
    private static final String KEY_SHUFFLE_SPLIT_PERFORMANCE_GUARD = "shuffleSplitPerformanceGuard";
    private static final String KEY_MARQUEE_LIGHT = "marqueeLight";
    private static final String KEY_NEON_DISPLAY = "neonDisplay";
    private static final String KEY_LYRICS_NEON_GLOW_LEGACY = "lyricsNeonGlow";
    private static final String KEY_NEON_BORDER = "neonBorder";
    private static final String KEY_MARQUEE_LIGHT_SIZE = "marqueeLightSize";
    private static final String KEY_MARQUEE_LIGHT_DURATION_MS = "marqueeLightDurationMs";
    private static final String KEY_GESTURE_CONTROL = "gestureControl";
    private static final String KEY_POWER_SAVING_MODE = "powerSavingMode";
    private static final String KEY_BORDER_PERFORMANCE_GUARD = "borderPerformanceGuard";
    private static final String KEY_BORDER_LIGHTWEIGHT_MODE = "borderLightweightMode";
    private static final String KEY_BACKGROUND_TEXTURE = "backgroundTexture";
    private static final String KEY_ALBUM_ART_BACKGROUND = "albumArtBackground";
    private static final String KEY_ALBUM_ART_ALPHA_PERCENT = "albumArtAlphaPercent";
    private static final String KEY_ALBUM_ART_BLUR_RADIUS = "albumArtBlurRadius";
    private static final String KEY_BREATHING_ENABLED = "breathingEnabled";
    private static final String KEY_BREATHING_BPM = "breathingBpm";
    private static final String KEY_BREATHING_SCALE_VARIANCE = "breathingScaleVariance";
    private static final String KEY_BREATHING_DISPLACEMENT_STRENGTH = "breathingDisplacementStrength";
    private static final String KEY_COLOR_CHANGE_INTERVAL_MS = "colorChangeIntervalMs";
    private static final String KEY_RANDOM_COLOR_SWITCH_ENABLED = "randomColorSwitchEnabled";
    private static final String KEY_PROJECTION_SYNC_OFFSET_MS = "projectionSyncOffsetMs";
    private static final String KEY_LYRICS_SOURCE_MODE = "lyricsSourceMode";
    private static final String KEY_ABYSSAL_MIRROR = "abyssalMirror";
    private static final String KEY_ABYSSAL_GYRO_SENSITIVITY = "abyssalGyroSensitivity";
    private static final String KEY_ABYSSAL_MOVABLE_RANGE = "abyssalMovableRange";
    private static final String KEY_PROJECTION_LYRICS_FONT = "projectionLyricsFont";
    private static final String KEY_PROJECTION_LYRICS_CUSTOM_PATH = "projectionLyricsCustomPath";

    private static final float DEFAULT_TEXT_SIZE = 65f;
    private static final float DEFAULT_BACKGROUND_TEXTURE_SIZE = 1.3f;
    private static final int DEFAULT_NORMAL_LYRICS_ALPHA = 30;
    private static final int DEFAULT_BACKGROUND_TEXTURE_ALPHA = 20;
    private static final int DEFAULT_MARQUEE_LIGHT_DURATION_MS = 5000;
    private static final int DEFAULT_ALBUM_ART_ALPHA_PERCENT = 35;
    private static final float DEFAULT_ALBUM_ART_BLUR_RADIUS_PX = 12f;
    private static final int DEFAULT_BREATHING_BPM = 15;
    private static final float DEFAULT_BREATHING_SCALE_VARIANCE = 0.10f;
    private static final float DEFAULT_BREATHING_DISPLACEMENT_STRENGTH = 1f;
    private static final int MIN_COLOR_CHANGE_INTERVAL_MS = 1000;
    private static final int MAX_COLOR_CHANGE_INTERVAL_MS = 30000;
    private static final int DEFAULT_COLOR_CHANGE_INTERVAL_MS = 5000;
    private static final int DEFAULT_PROJECTION_SYNC_OFFSET_MS = 0;
    private static final float DEFAULT_ABYSSAL_GYRO_SENSITIVITY = 1f;
    private static final float DEFAULT_ABYSSAL_MOVABLE_RANGE = 2.5f;
    /** 主屏横屏：跑马灯线条宽度相对设置值的倍率。 */
    private static final float MAIN_SCREEN_MARQUEE_LIGHT_SIZE_MULTIPLIER = 2f;
    private static final String VALUE_LYRICS_SOURCE_MIXED = "MIXED";
    private static final String VALUE_LYRICS_SOURCE_NETWORK_ONLY = "NETWORK_ONLY";
    private static final String VALUE_LYRICS_SOURCE_SUPER_LYRIC_ONLY = "SUPER_LYRIC_ONLY";

    // 鈹€鈹€ Settings state fields 鈹€鈹€
    private float currentTextSize = DEFAULT_TEXT_SIZE;
    private float backgroundTextureSize = DEFAULT_BACKGROUND_TEXTURE_SIZE;
    private int normalLyricsAlpha = DEFAULT_NORMAL_LYRICS_ALPHA;
    private int backgroundTextureAlpha = DEFAULT_BACKGROUND_TEXTURE_ALPHA;
    private boolean wordByWordEnabled = false;
    private boolean shuffleSplitEffectEnabled = false;
    private boolean shuffleSplitMulticolorEnabled = false;
    private String shuffleSplitMode = "WORD";
    private boolean shuffleSplitOnlyCurrentLine = true;
    private boolean marqueeLightEnabled = true;
    private boolean neonDisplayEnabled = true;
    private boolean neonBorderEnabled = true;
    private float marqueeLightSize = 18f;
    private int marqueeLightDurationMs = DEFAULT_MARQUEE_LIGHT_DURATION_MS;
    private boolean gestureControlEnabled = false;
    private boolean powerSavingModeEnabled = false;
    private boolean borderPerformanceGuardEnabled = false;
    private boolean borderLightweightModeEnabled = false;
    private boolean backgroundTextureEnabled = false;
    private boolean albumArtBackgroundEnabled = false;
    private int albumArtAlphaPercent = DEFAULT_ALBUM_ART_ALPHA_PERCENT;
    private float albumArtBlurRadiusPx = DEFAULT_ALBUM_ART_BLUR_RADIUS_PX;
    private boolean breathingEnabled = false;
    private int breathingBpm = DEFAULT_BREATHING_BPM;
    private float breathingScaleVariance = DEFAULT_BREATHING_SCALE_VARIANCE;
    private float breathingDisplacementStrength = DEFAULT_BREATHING_DISPLACEMENT_STRENGTH;
    private int colorChangeIntervalMs = DEFAULT_COLOR_CHANGE_INTERVAL_MS;
    private boolean randomColorSwitchEnabled = true;
    private int projectionSyncOffsetMs = DEFAULT_PROJECTION_SYNC_OFFSET_MS;
    private String lyricsSourceMode = VALUE_LYRICS_SOURCE_MIXED;
    private boolean abyssalMirrorEnabled = false;
    private float abyssalGyroSensitivity = DEFAULT_ABYSSAL_GYRO_SENSITIVITY;
    private float abyssalMovableRange = DEFAULT_ABYSSAL_MOVABLE_RANGE;
    private String projectionLyricsFontId = LyricsFontHelper.DEFAULT_ID;
    private String projectionLyricsCustomPath;
    private String lastAppliedSettingsFingerprint = "";

    // 鈹€鈹€ Views 鈹€鈹€
    private ModernLyricsView lyricsView;
    private MarqueeLightView marqueeLightView;
    private AbyssalMirrorLyricsViewGroup abyssalMirrorLyricsViewGroup;
    private AbyssalMirrorLyricsView abyssalMirrorLyricsView;
    private LyricsAnimator lyricsAnimator;
    private TextView songTitleText;
    private TextView artistText;
    private TextView hookSourceStatusText;
    private ImageView albumArtBackgroundView;
    private FrameLayout mainFrameLayout;
    private FrameLayout.LayoutParams lyricsViewLayoutParams;
    private LinearLayout buttonLayout;
    private Button playPauseButton;
    private FrameLayout.LayoutParams mediaButtonBarLayoutParams;
    private android.widget.FrameLayout gestureTitleLayer;
    private android.widget.FrameLayout.LayoutParams gestureTitleLayerLayoutParams;
    private static final int GESTURE_TITLE_LAYER_BASE_HEIGHT_DP = 64;
    private static final int GESTURE_TITLE_LAYER_EXTRA_TOP_DP = 32;
    private static final int GESTURE_TITLE_LAYER_EXTRA_BOTTOM_DP = 10;
    private boolean buttonsVisible = false;

    // 鈹€鈹€ Media session 鈹€鈹€
    private MediaController mediaController;
    private MediaController.Callback mediaControllerCallback;
    private boolean activeSessionsListenerRegistered = false;
    private MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener;
    private Runnable activeSessionsChangedDebouncedRunnable;
    private String lastBoundMediaPackage;
    private KuwoCarMediaSessionHelper kuwoCarMediaSessionHelper;
    private boolean kuwoCarLyricsSessionActive = false;
    private long lastMediaControllerRefreshCheckMs = 0L;
    private static final long MEDIA_CONTROLLER_REFRESH_CHECK_THROTTLE_MS = 3000L;

    // 鈹€鈹€ Animation & position sync 鈹€鈹€
    private Handler positionSyncHandler;
    private Runnable positionSyncRunnable;
    private boolean uiAnimationsCancelled = false;

    // 鈹€鈹€ TaskService 鈹€鈹€
    private ITaskService taskService;
    private boolean isOfficialGestureDisabled = false;
    private Runnable taskServiceRecheckRunnable;
    private Runnable taskServiceRebindRunnable;
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            taskService = ITaskService.Stub.asInterface(service);
            LogHelper.d(TAG, "TaskService connected");
            checkAndDisableOfficialGesture();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            LogHelper.w(TAG, "TaskService disconnected");
            taskService = null;
            if (taskServiceRebindRunnable != null) uiHandler.removeCallbacks(taskServiceRebindRunnable);
            taskServiceRebindRunnable = () -> { bindTaskService(); };
            uiHandler.postDelayed(taskServiceRebindRunnable, 1000);
        }
    };

    // 鈹€鈹€ System UI listener 鈹€鈹€
    private Handler systemUICheckHandler;
    private Runnable systemUICheckRunnable;
    private Runnable systemUiExitConfirmRunnable;
    private Runnable legacySystemUiExitConfirmRunnable;

    // 鈹€鈹€ Screen receiver 鈹€鈹€
    private android.content.BroadcastReceiver screenReceiver;

    // 鈹€鈹€ Other state 鈹€鈹€
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean isInForeground = false;
    private boolean projectionExitFlowStarted = false;
    private boolean finishRequestedByMiRoot = false;
    private int initialDisplayId = -1;
    private Bitmap currentAlbumArtBitmap;
    private ExecutorService lyricsParseExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService superLyricWordFusionExecutor = Executors.newSingleThreadExecutor();
    private java.util.concurrent.atomic.AtomicInteger wordFusionGeneration = new java.util.concurrent.atomic.AtomicInteger(0);
    private KuwoBroadcastLyricBridge kuwoBroadcastLyricBridge;
    private Runnable notifyStopRetryRunnable;
    private Runnable restoreLauncherRetryRunnable;

    // ======================================================
    //  onCreate
    // ======================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        if (!OfflineActivationRepository.INSTANCE.isActivated(this)) {
            try {
                com.wmqc.miroot.display.MainDisplayUi.showToast(this,
                    getString(R.string.activation_required_to_use), android.widget.Toast.LENGTH_SHORT);
            } catch (Throwable ignored) {}
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
            return;
        }

        currentInstance = this;
        LogHelper.d(TAG, "onCreate");

        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { displayId = getDisplay().getDisplayId(); } catch (Exception e) {
                LogHelper.e(TAG, "getDisplayId failed", e);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != 0) {
            LogHelper.w(TAG, "Not on main display (displayId=" + displayId + "), finishing");
            finishProjectionFromUser("wrong-display");
            return;
        }

        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        RearDisplayInputHelper.ensureApplicationWindowReceivesInput(this);
        RootTaskServiceConnector.prewarm(this);
        SuperLyricApi.init();

        setupWindow();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        getWindow().setAttributes(params);

        loadSettings();
        currentTextSize = currentTextSize * 2f;
        marqueeLightSize = marqueeLightSize * MAIN_SCREEN_MARQUEE_LIGHT_SIZE_MULTIPLIER;

        createUI();
        getWindow().getDecorView().post(this::hideSystemUIForMainScreen);
        initLyricsAnimator();
        setupMediaController();
        updateMediaInfo();
        updatePlaybackState();
        registerActiveSessionsChangedListener();
        bindTaskService();
        registerScreenReceiver();
        registerSystemUIVisibilityListener();
        initialDisplayId = 0;
        // 启动延迟重试初始歌词显示（若启动时 mediaController 暂不可用）
        uiHandler.postDelayed(() -> {
            if (mediaController == null || mediaController.getMetadata() == null) {
                // 第一次重试：如果仍无媒体数据，再等一会儿
                uiHandler.postDelayed(() -> {
                    if (mediaController != null && mediaController.getMetadata() != null) {
                        updateMediaInfo();
                    }
                }, 1000);
            }
        }, 500);

        LogHelper.d(TAG, "Init complete");
    }

    // ======================================================
    //  Window / settings
    // ======================================================

    private void setupWindow() {
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentTextSize = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
        backgroundTextureSize = prefs.getFloat(KEY_BACKGROUND_TEXTURE_SIZE, DEFAULT_BACKGROUND_TEXTURE_SIZE);
        normalLyricsAlpha = prefs.getInt(KEY_NORMAL_LYRICS_ALPHA, DEFAULT_NORMAL_LYRICS_ALPHA);
        backgroundTextureAlpha = prefs.getInt(KEY_BACKGROUND_TEXTURE_ALPHA, DEFAULT_BACKGROUND_TEXTURE_ALPHA);
        wordByWordEnabled = prefs.getBoolean(KEY_WORD_BY_WORD, false);
        shuffleSplitEffectEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_EFFECT, false);
        shuffleSplitMulticolorEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_MULTICOLOR, false);
        shuffleSplitMode = prefs.getString(KEY_SHUFFLE_SPLIT_MODE, "WORD");
        if (shuffleSplitMode == null) shuffleSplitMode = "WORD";
        shuffleSplitOnlyCurrentLine = prefs.getBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, true);
        if (shuffleSplitEffectEnabled) {
            wordByWordEnabled = false; gestureControlEnabled = false;
            shuffleSplitMode = "WORD"; shuffleSplitOnlyCurrentLine = true;
        }
        marqueeLightEnabled = prefs.getBoolean(KEY_MARQUEE_LIGHT, true);
        neonDisplayEnabled = prefs.getBoolean(KEY_NEON_DISPLAY, prefs.getBoolean(KEY_LYRICS_NEON_GLOW_LEGACY, true));
        neonBorderEnabled = prefs.getBoolean(KEY_NEON_BORDER, true);
        marqueeLightSize = prefs.getFloat(KEY_MARQUEE_LIGHT_SIZE, 18f);
        marqueeLightDurationMs = prefs.getInt(KEY_MARQUEE_LIGHT_DURATION_MS, DEFAULT_MARQUEE_LIGHT_DURATION_MS);
        gestureControlEnabled = prefs.getBoolean(KEY_GESTURE_CONTROL, false);
        abyssalMirrorEnabled = prefs.getBoolean(KEY_ABYSSAL_MIRROR, false);
        if (abyssalMirrorEnabled) { wordByWordEnabled = false; shuffleSplitEffectEnabled = false; }
        backgroundTextureEnabled = prefs.getBoolean(KEY_BACKGROUND_TEXTURE, false);
        albumArtBackgroundEnabled = prefs.getBoolean(KEY_ALBUM_ART_BACKGROUND, false);
        albumArtAlphaPercent = Math.max(0, Math.min(100, prefs.getInt(KEY_ALBUM_ART_ALPHA_PERCENT, DEFAULT_ALBUM_ART_ALPHA_PERCENT)));
        albumArtBlurRadiusPx = Math.max(0f, prefs.getFloat(KEY_ALBUM_ART_BLUR_RADIUS, DEFAULT_ALBUM_ART_BLUR_RADIUS_PX));
        powerSavingModeEnabled = prefs.getBoolean(KEY_POWER_SAVING_MODE, false);
        borderPerformanceGuardEnabled = prefs.getBoolean(KEY_BORDER_PERFORMANCE_GUARD, false);
        borderLightweightModeEnabled = prefs.getBoolean(KEY_BORDER_LIGHTWEIGHT_MODE, false);
        projectionSyncOffsetMs = prefs.getInt(KEY_PROJECTION_SYNC_OFFSET_MS, DEFAULT_PROJECTION_SYNC_OFFSET_MS);
        String rawMode = prefs.getString(KEY_LYRICS_SOURCE_MODE, VALUE_LYRICS_SOURCE_MIXED);
        lyricsSourceMode = normalizeLyricsSourceMode(rawMode);
        breathingEnabled = prefs.getBoolean(KEY_BREATHING_ENABLED, false);
        breathingBpm = prefs.getInt(KEY_BREATHING_BPM, DEFAULT_BREATHING_BPM);
        breathingScaleVariance = prefs.getFloat(KEY_BREATHING_SCALE_VARIANCE, DEFAULT_BREATHING_SCALE_VARIANCE);
        breathingDisplacementStrength = prefs.getFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, DEFAULT_BREATHING_DISPLACEMENT_STRENGTH);
        colorChangeIntervalMs = Math.max(MIN_COLOR_CHANGE_INTERVAL_MS,
            Math.min(MAX_COLOR_CHANGE_INTERVAL_MS, prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS)));
        randomColorSwitchEnabled = prefs.getBoolean(KEY_RANDOM_COLOR_SWITCH_ENABLED, true);
        // 同步到全局颜色管理器
        LyricsColorManager.INSTANCE.setRandomMode(randomColorSwitchEnabled);
        LyricsColorManager.INSTANCE.setColorChangeIntervalMs(colorChangeIntervalMs);
        abyssalGyroSensitivity = prefs.getFloat(KEY_ABYSSAL_GYRO_SENSITIVITY, DEFAULT_ABYSSAL_GYRO_SENSITIVITY);
        abyssalMovableRange = prefs.getFloat(KEY_ABYSSAL_MOVABLE_RANGE, DEFAULT_ABYSSAL_MOVABLE_RANGE);
        loadLyricsFontFieldsFromPrefs(prefs);
    }

    private String normalizeLyricsSourceMode(String mode) {
        if (mode == null) return VALUE_LYRICS_SOURCE_MIXED;
        if (mode.equals(VALUE_LYRICS_SOURCE_NETWORK_ONLY) || mode.equals(VALUE_LYRICS_SOURCE_SUPER_LYRIC_ONLY) || mode.equals(VALUE_LYRICS_SOURCE_MIXED)) return mode;
        return VALUE_LYRICS_SOURCE_MIXED;
    }

    private void loadLyricsFontFieldsFromPrefs(SharedPreferences prefs) {
        String rawFont = prefs.getString(KEY_PROJECTION_LYRICS_FONT, null);
        projectionLyricsFontId = LyricsFontHelper.normalizeFontId(rawFont);
        if (projectionLyricsFontId == null) projectionLyricsFontId = LyricsFontHelper.DEFAULT_ID;
        String rawPath = prefs.getString(KEY_PROJECTION_LYRICS_CUSTOM_PATH, null);
        projectionLyricsCustomPath = (projectionLyricsFontId.equals(LyricsFontHelper.ID_CUSTOM) && rawPath != null && !rawPath.isEmpty()) ? rawPath : null;
    }

    // ======================================================
    //  createUI
    // ======================================================

        private void createUI() {
        try {

            // 预填充共享同步颜色，确保首帧歌词和跑马灯颜色一致
            LyricsProjectionColorSync.prefetch(colorChangeIntervalMs);
            mainFrameLayout = new FrameLayout(this);
            mainFrameLayout.setBackgroundColor(0xFF000000);

            albumArtBackgroundView = new ImageView(this);
            albumArtBackgroundView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            albumArtBackgroundView.setAlpha(albumArtAlphaPercent / 100f);
            mainFrameLayout.addView(albumArtBackgroundView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            int leftMargin = dp(20);
            int rightMargin = dp(20);

            if (abyssalMirrorEnabled) {
                abyssalMirrorLyricsViewGroup = new AbyssalMirrorLyricsViewGroup(this, false);
                abyssalMirrorLyricsViewGroup.setMainScreenLandscapeMode(true);
                abyssalMirrorLyricsViewGroup.setTextSize(currentTextSize);
                abyssalMirrorLyricsViewGroup.setTextColor(randomColorSwitchEnabled ? randomHighSaturationColor() : 0xFFFFFFFF);
                abyssalMirrorLyricsViewGroup.setEnableGesture(gestureControlEnabled);
                abyssalMirrorLyricsViewGroup.setGyroSensitivityMultiplier(abyssalGyroSensitivity);
                abyssalMirrorLyricsViewGroup.setMovableRangeMultiplier(abyssalMovableRange);
                abyssalMirrorLyricsViewGroup.setColorChangeIntervalMs(colorChangeIntervalMs);
                abyssalMirrorLyricsViewGroup.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
                abyssalMirrorLyricsViewGroup.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
                abyssalMirrorLyricsViewGroup.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
                Typeface tf = LyricsFontHelper.resolveTypeface(this, projectionLyricsFontId, projectionLyricsCustomPath);
                if (tf != null) abyssalMirrorLyricsViewGroup.setLyricsTypeface(tf);
                mainFrameLayout.addView(abyssalMirrorLyricsViewGroup, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                abyssalMirrorLyricsViewGroup.post(() -> {
                    if (abyssalMirrorLyricsViewGroup != null) {
                        abyssalMirrorLyricsViewGroup.reevaluateCornerRadius();
                    }
                });
            } else {
                lyricsView = new ModernLyricsView(this);
                lyricsView.setBackgroundColor(0x00000000);
                lyricsView.setTextSize(currentTextSize);
                lyricsView.setNormalLyricsAlpha(normalLyricsAlpha);
                lyricsView.setBackgroundTextureSize(backgroundTextureSize);
                lyricsView.setBackgroundTextureAlpha(backgroundTextureAlpha);
                lyricsView.setEnableWordByWord(wordByWordEnabled);
                lyricsView.setCharJumpEnabled(wordByWordEnabled);
                lyricsView.setEnableGesture(gestureControlEnabled);
                lyricsView.setShowBackgroundTexture(backgroundTextureEnabled);
                lyricsView.setPowerSavingModeEnabled(powerSavingModeEnabled);
                lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
                // 歌词、边框、跑马灯共用同一配色源，主背屏同时显示时保持一致
                LyricsProjectionColorSync.bindLyricsView(lyricsView, randomColorSwitchEnabled, colorChangeIntervalMs);
                lyricsView.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
                lyricsView.setLyricsFont(projectionLyricsFontId, projectionLyricsCustomPath);
                lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
                lyricsView.setLyricsHorizontalInsetPx(0);
                lyricsView.setLineSpacing(160f * 1.5f);
                lyricsViewLayoutParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                mainFrameLayout.addView(lyricsView, lyricsViewLayoutParams);

            // Marquee light
            try {
                marqueeLightView = new MarqueeLightView(this, false);
                marqueeLightView.setMainScreenLandscapeMode(true);
                mainFrameLayout.addView(marqueeLightView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                boolean visible = !abyssalMirrorEnabled && (marqueeLightEnabled || neonBorderEnabled);
                marqueeLightView.setVisibility(visible ? View.VISIBLE : View.GONE);
                marqueeLightView.setBorderFrameEnabled(neonBorderEnabled);
                marqueeLightView.setNeonEffectsEnabled(neonDisplayEnabled);
                marqueeLightView.setMarqueeLightEnabled(marqueeLightEnabled);
                marqueeLightView.setLightSize(marqueeLightSize);
                marqueeLightView.setAnimationDuration(marqueeLightDurationMs);
                marqueeLightView.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
                marqueeLightView.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
                marqueeLightView.setColorChangeIntervalMs(colorChangeIntervalMs);
                marqueeLightView.setClickable(false);
                marqueeLightView.setFocusable(false);
                // 边框、跑马灯与歌词高亮同色
                LyricsProjectionColorSync.bindMarqueeLight(marqueeLightView, randomColorSwitchEnabled, colorChangeIntervalMs);
                
            } catch (Exception e) { LogHelper.e(TAG, "marqueeLight failed", e); }

            // 视图挂载后重新检测圆角半径（启动时 View 尚未 attach 可能导致检测失败）
            if (marqueeLightView != null) {
                marqueeLightView.post(() -> {
                    if (marqueeLightView != null) {
                        marqueeLightView.reevaluateCornerRadius();
                    }
                });
                marqueeLightView.postDelayed(() -> {
                    if (marqueeLightView != null) {
                        marqueeLightView.reevaluateCornerRadius();
                    }
                }, 350L);
            }

            // Song title with marquee scroll for long titles
            songTitleText = new TextView(this);
            songTitleText.setTextColor(0xFFFFFFFF);
            songTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f * 1.3f);
            songTitleText.setGravity(android.view.Gravity.CENTER | android.view.Gravity.BOTTOM);
            songTitleText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            songTitleText.setMaxLines(1);
            songTitleText.setSingleLine(true);
            songTitleText.setMarqueeRepeatLimit(-1);
            songTitleText.setSelected(true);
            int titleTopMargin = (int) (marqueeLightSize + dp(2));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            tl.setMargins(leftMargin, titleTopMargin, rightMargin, 0);
            tl.gravity = android.view.Gravity.TOP;
            mainFrameLayout.addView(songTitleText, tl);

            // Artist
            artistText = new TextView(this);
            artistText.setTextColor(0x80FFFFFF);
            artistText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f * 1.3f);
            artistText.setGravity(android.view.Gravity.CENTER);
            artistText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            artistText.setMaxLines(1);
            FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            al.setMargins(leftMargin, titleTopMargin + dp(28), rightMargin, 0);
            al.gravity = android.view.Gravity.TOP;
            mainFrameLayout.addView(artistText, al);

            // Hook source status
            hookSourceStatusText = new TextView(this);
            hookSourceStatusText.setTextColor(0x80FFFFFF);
            hookSourceStatusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f * 1.3f);
            hookSourceStatusText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            hookSourceStatusText.setPadding(0, 0, 0, 10);
            FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            hl.gravity = android.view.Gravity.BOTTOM;
            mainFrameLayout.addView(hookSourceStatusText, hl);

            // Control buttons (initially hidden, toggled by tap on song title)
            buttonLayout = new LinearLayout(this);
            buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
            buttonLayout.setGravity(android.view.Gravity.CENTER);
            buttonLayout.setPadding(dp(22), dp(14), dp(22), dp(16));
            buttonLayout.setVisibility(View.GONE);
            buttonsVisible = false;

            int btnGap = dp(8);
            final int compactBtnWidth = dp(44);
            final int compactBtnHeight = dp(34);

            Button prevBtn = new Button(this);
            prevBtn.setText("\u23EE");
            styleMediaButton(prevBtn);
            LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(compactBtnWidth, compactBtnHeight);
            prevParams.setMarginEnd(btnGap / 2);
            prevBtn.setLayoutParams(prevParams);
            prevBtn.setOnClickListener(v -> execMedia(false));
            buttonLayout.addView(prevBtn);

            Button ppBtn = new Button(this);
            playPauseButton = ppBtn;
            ppBtn.setText("\u25B6");
            styleMediaButton(ppBtn);
            LinearLayout.LayoutParams ppParams = new LinearLayout.LayoutParams(compactBtnWidth, compactBtnHeight);
            ppParams.setMarginStart(btnGap / 2);
            ppParams.setMarginEnd(btnGap / 2);
            ppBtn.setLayoutParams(ppParams);
            ppBtn.setOnClickListener(v -> execPp());
            buttonLayout.addView(ppBtn);

            Button nextBtn = new Button(this);
            nextBtn.setText("\u23ED");
            styleMediaButton(nextBtn);
            LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(compactBtnWidth, compactBtnHeight);
            nextParams.setMarginStart(btnGap / 2);
            nextBtn.setLayoutParams(nextParams);
            nextBtn.setOnClickListener(v -> execMedia(true));
            buttonLayout.addView(nextBtn);

            mediaButtonBarLayoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            mediaButtonBarLayoutParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            mainFrameLayout.addView(buttonLayout, mediaButtonBarLayoutParams);

            // Gesture title layer - transparent overlay for tap/swipe on song title area
            final int titleSwipeMinDistancePx = dp(60);
            final int titleSwipeMinVelocityPx = dp(36);
            final float titleSwipeDirectionRatio = 1.35f;
            final GestureDetector titleGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) { return true; }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    toggleButtonsVisibility();
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    marqueeLightEnabled = !marqueeLightEnabled;
                    if (marqueeLightView != null) {
                        boolean visible = !abyssalMirrorEnabled && (marqueeLightEnabled || neonBorderEnabled);
                        marqueeLightView.setVisibility(visible ? View.VISIBLE : View.GONE);
                        marqueeLightView.setMarqueeLightEnabled(marqueeLightEnabled);
                    }
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    float absDx = Math.abs(dx);
                    float absDy = Math.abs(dy);
                    if (absDx < titleSwipeMinDistancePx) return false;
                    if (absDx <= absDy * titleSwipeDirectionRatio) return false;
                    if (Math.abs(velocityX) < titleSwipeMinVelocityPx || Math.abs(velocityX) <= Math.abs(velocityY)) return false;
                    // 左滑下一首，右滑上一首，与深渊镜手势方向一致
                    if (dx < 0) { execMedia(true); }
                    else { execMedia(false); }
                    return true;
                }
            });

            gestureTitleLayer = new android.widget.FrameLayout(this);
            gestureTitleLayer.setBackgroundColor(0x00000000);
            gestureTitleLayer.setClickable(true);
            gestureTitleLayer.setFocusable(false);
            gestureTitleLayer.setOnTouchListener((v, event) -> titleGestureDetector.onTouchEvent(event));

            int gestureExtraTop = dp(GESTURE_TITLE_LAYER_EXTRA_TOP_DP);
            int titleTouchBaseHeight = dp(GESTURE_TITLE_LAYER_BASE_HEIGHT_DP);
            int gestureExtraBottom = dp(GESTURE_TITLE_LAYER_EXTRA_BOTTOM_DP);
            int gestureHeight = titleTouchBaseHeight + gestureExtraTop + gestureExtraBottom;
            int gestureTopMargin = Math.max(0, titleTopMargin - gestureExtraTop);
            gestureTitleLayerLayoutParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                gestureHeight
            );
            gestureTitleLayerLayoutParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            gestureTitleLayerLayoutParams.setMargins(leftMargin, gestureTopMargin, rightMargin, 0);
            mainFrameLayout.addView(gestureTitleLayer, gestureTitleLayerLayoutParams);
            if (abyssalMirrorEnabled) {
                gestureTitleLayer.setVisibility(View.GONE);
            }

            }
            setContentView(mainFrameLayout);
            applyProjectionBackgroundMode();
            LogHelper.d(TAG, "UI created");
            getWindow().getDecorView().post(this::hideSystemUIForMainScreen);

            // Initialize Kuwo broadcast lyrics bridge to feed lyrics to lyricsView
            if (lyricsView != null) {
                try {
                    kuwoBroadcastLyricBridge = new KuwoBroadcastLyricBridge(getApplicationContext(), lyricsView);
                    kuwoBroadcastLyricBridge.start();
                    LogHelper.d(TAG, "KuwoBroadcastLyricBridge started for main screen lyrics");
                } catch (Exception e) {
                    LogHelper.e(TAG, "Failed to start KuwoBroadcastLyricBridge", e);
                }
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "createUI failed", e);
            try { View v = new View(this); v.setBackgroundColor(0xFF000000); setContentView(v); } catch (Exception e2) {}
        }
    }



    private void applyProjectionBackgroundMode() {
        final boolean useAlbumArtBackground = !powerSavingModeEnabled
                && albumArtBackgroundEnabled && !abyssalMirrorEnabled;
        if (mainFrameLayout != null) {
            mainFrameLayout.setBackgroundColor(useAlbumArtBackground ? 0x00000000 : 0xFF000000);
        }
        if (lyricsView != null) {
            lyricsView.setOpaqueBackgroundEnabled(!useAlbumArtBackground);
            lyricsView.postInvalidateOnAnimation();
        }
        if (albumArtBackgroundView != null) {
            if (!useAlbumArtBackground) {
                albumArtBackgroundView.setVisibility(View.GONE);
            } else if (currentAlbumArtBitmap != null) {
                albumArtBackgroundView.setVisibility(View.VISIBLE);
                albumArtBackgroundView.setImageBitmap(currentAlbumArtBitmap);
                albumArtBackgroundView.setAlpha(albumArtAlphaPercent / 100f);
            } else {
                albumArtBackgroundView.setVisibility(View.GONE);
            }
        }
    }

        private void styleMediaButton(Button btn) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFF4A4A4A);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        btn.setPadding(dp(4), dp(6), dp(4), dp(6));
        btn.setMinHeight(0); btn.setMinWidth(0);
        btn.setBackground(bg); btn.setElevation(dp(2));
    }

    private void execMedia(boolean next) {
        if (!checkNlp()) { showPermToast(); return; }
        if (mediaController == null) setupMediaController();
        if (mediaController == null) return;
        try {
            if (next) mediaController.getTransportControls().skipToNext();
            else mediaController.getTransportControls().skipToPrevious();
        } catch (Exception e) { LogHelper.e(TAG, "skip " + next, e); }
    }

    private void execPp() {
        if (!checkNlp()) { showPermToast(); return; }
        if (mediaController == null) setupMediaController();
        if (mediaController == null) return;
        try {
            PlaybackState st = mediaController.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING)
                mediaController.getTransportControls().pause();
            else mediaController.getTransportControls().play();
        } catch (Exception e) { LogHelper.e(TAG, "pp", e); }
    }

    
    private void toggleButtonsVisibility() {
        if (buttonLayout == null) return;
        buttonsVisible = !buttonsVisible;
        buttonLayout.setVisibility(buttonsVisible ? View.VISIBLE : View.GONE);
        LogHelper.d(TAG, "Toggle buttons: " + (buttonsVisible ? "show" : "hide"));
    }

    private void setVisibilityIfChanged(View v, int visibility) {
        if (v != null && v.getVisibility() != visibility) v.setVisibility(visibility);
    }

private boolean checkNlp() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void showPermToast() {
        com.wmqc.miroot.display.MainDisplayUi.showToast(this,
            "Need notification listener", android.widget.Toast.LENGTH_LONG);
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    // ======================================================
    //  System UI
    // ======================================================

    private void hideSystemUIForMainScreen() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController ctrl = decor.getWindowInsetsController();
            if (ctrl != null) {
                ctrl.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private void registerSystemUIVisibilityListener() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            systemUICheckHandler = uiHandler;
            systemUICheckRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || !isInForeground) return;
                    try {
                        android.view.WindowInsets insets = decor.getRootWindowInsets();
                        if (insets != null) {
                            boolean sb = insets.isVisible(android.view.WindowInsets.Type.statusBars());
                            boolean nb = insets.isVisible(android.view.WindowInsets.Type.navigationBars());
                            if (sb || nb) {
                                if (systemUiExitConfirmRunnable != null) systemUICheckHandler.removeCallbacks(systemUiExitConfirmRunnable);
                                systemUiExitConfirmRunnable = () -> {
                                    if (!isFinishing()) {
                                        android.view.WindowInsets re = decor.getRootWindowInsets();
                                        if (re != null && (re.isVisible(android.view.WindowInsets.Type.statusBars())
                                            || re.isVisible(android.view.WindowInsets.Type.navigationBars()))) {
                                            finishProjectionTask(); return;
                                        }
                                    }
                                };
                                systemUICheckHandler.postDelayed(systemUiExitConfirmRunnable, 500);
                            }
                        }
                    } catch (Exception e) { LogHelper.w(TAG, "UI check: " + e.getMessage()); }
                    if (!isFinishing() && isInForeground) systemUICheckHandler.postDelayed(this, 500);
                }
            };
            systemUICheckHandler.post(systemUICheckRunnable);
        } else {
            decor.setOnSystemUiVisibilityChangeListener(vis -> {
                if (isFinishing()) return;
                boolean navVis = (vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
                boolean fsVis = (vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
                if (navVis || fsVis) {
                    if (legacySystemUiExitConfirmRunnable != null) uiHandler.removeCallbacks(legacySystemUiExitConfirmRunnable);
                    legacySystemUiExitConfirmRunnable = () -> {
                        if (!isFinishing()) {
                            int v = decor.getSystemUiVisibility();
                            if (((v & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) || ((v & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0))
                                finishProjectionTask();
                        }
                    };
                    uiHandler.postDelayed(legacySystemUiExitConfirmRunnable, 500);
                }
            });
        }
    }

    // ======================================================
    //  Media session
    // ======================================================

    private void registerActiveSessionsChangedListener() {
        if (activeSessionsListenerRegistered) return;
        if (!checkNlp()) return;
        MediaSessionManager sm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sm == null) return;
        activeSessionsChangedListener = controllers -> {
            if (isFinishing()) return;
            uiHandler.removeCallbacks(activeSessionsChangedDebouncedRunnable);
            activeSessionsChangedDebouncedRunnable = () -> { if (!isFinishing()) refreshMC(); };
            uiHandler.postDelayed(activeSessionsChangedDebouncedRunnable, 120);
        };
        try {
            sm.addOnActiveSessionsChangedListener(activeSessionsChangedListener,
                new ComponentName(this, MiRootNotificationListenerService.class));
            activeSessionsListenerRegistered = true;
        } catch (SecurityException e) {
            LogHelper.wThrottled(TAG, "session listener security", 600000L);
        } catch (Throwable t) {
            LogHelper.wThrottled(TAG, "session listener failed", 600000L);
        }
    }

    private void unregSessions() {
        if (!activeSessionsListenerRegistered || activeSessionsChangedListener == null) return;
        MediaSessionManager sm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sm != null) { try { sm.removeOnActiveSessionsChangedListener(activeSessionsChangedListener); } catch (Throwable ignored) {} }
        uiHandler.removeCallbacks(activeSessionsChangedDebouncedRunnable);
        activeSessionsChangedListener = null; activeSessionsListenerRegistered = false;
    }

    private void refreshMC() { setupMediaController(); updateMediaInfo(); updatePlaybackState(); }

    private void setupMediaController() {
        try {
            if (!checkNlp()) return;
            if (mediaController != null && mediaControllerCallback != null) {
                try { mediaController.unregisterCallback(mediaControllerCallback); } catch (Throwable ignored) {}
                mediaControllerCallback = null;
            }
            mediaController = null; kuwoCarLyricsSessionActive = false;
            if (kuwoCarMediaSessionHelper == null) kuwoCarMediaSessionHelper = new KuwoCarMediaSessionHelper(getApplicationContext());
            MediaSessionManager sm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sm == null) return;
            List<MediaController> controllers = sm.getActiveSessions(
                new ComponentName(this, MiRootNotificationListenerService.class));
            if (controllers.isEmpty()) return;
            MediaController first = KuwoCarLyricsPolicy.preferredActiveController(controllers);
            if (first == null) return;

            if (KuwoCarLyricsPolicy.shouldUseKuwoCarLyrics(first)) {
                kuwoCarMediaSessionHelper.disconnect();
                mediaController = first; kuwoCarLyricsSessionActive = true;
                mediaControllerCallback = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata m) { updateMediaInfo(); }
                    @Override public void onPlaybackStateChanged(PlaybackState s) { updatePlaybackState(); }
                };
                mediaController.registerCallback(mediaControllerCallback);
                lastBoundMediaPackage = first.getPackageName();
                kuwoCarMediaSessionHelper.connect(
                    controller -> { kuwoCarLyricsSessionActive = true; },
                    () -> {});
                return;
            }
            mediaController = first;
            mediaControllerCallback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { updateMediaInfo(); updateAlbumArtBackground(m); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { updatePlaybackState(); }
                @Override public void onSessionDestroyed() { mediaController = null; uiHandler.post(() -> refreshMC()); }
            };
            mediaController.registerCallback(mediaControllerCallback);
            lastBoundMediaPackage = first.getPackageName();
        } catch (SecurityException e) {
            LogHelper.wThrottled(TAG, "setupMC security", 600000L);
        }
    }

    private void updateMediaInfo() {
        if (mediaController == null) return;
        MediaMetadata meta = mediaController.getMetadata();
        if (meta == null) return;
        String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (title != null && songTitleText != null) songTitleText.setText(title);
        if (artist != null && artistText != null) artistText.setText(artist);
        updateAlbumArtBackground(meta);
        // Show initial lyrics from title when bridge hasn't delivered full lyrics yet.
        // Only set single-line placeholder if the view doesn't already have complete lyrics
        // (line count > 1 means bridge or previous full-lyric delivery populated it).
        if (lyricsView != null) {
            if (lyricsView.getLyricLineCount() <= 1) {
                String displayText = title != null ? title : "";
                if (artist != null && !artist.isEmpty()) displayText += " - " + artist;
                if (!displayText.isEmpty()) {
                    try {
                        List list = new java.util.ArrayList();
                        EnhancedLRCParser.EnhancedLyricLine line = new EnhancedLRCParser.EnhancedLyricLine(0L, displayText);
                        list.add(line);
                        lyricsView.setLyrics(list);
                        lyricsView.setTrackLoading(false);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void updateAlbumArtBackground(MediaMetadata meta) {
        if (powerSavingModeEnabled || !albumArtBackgroundEnabled || abyssalMirrorEnabled) {
            applyProjectionBackgroundMode();
            return;
        }
        Bitmap art = null;
        if (meta != null) {
            try { art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART); } catch (Exception ignored) {}
            if (art == null) { try { art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART); } catch (Exception ignored) {} }
        }
        if (art != null) {
            currentAlbumArtBitmap = art;
        }
        applyProjectionBackgroundMode();
    }

    private void updatePlaybackState() {
        if (mediaController == null) return;
        PlaybackState st = mediaController.getPlaybackState();
        if (st == null) return;
        boolean playing = st.getState() == PlaybackState.STATE_PLAYING;
        if (lyricsAnimator != null) {
            if (playing) { lyricsAnimator.calibratePosition(st.getPosition(), st.getLastPositionUpdateTime()); lyricsAnimator.resume(); }
            else { lyricsAnimator.pause(); }
        }
        if (lyricsView != null) lyricsView.setPlaybackActive(playing);
        if (playPauseButton != null) {
            playPauseButton.setText(playing ? "\u23F8" : "\u25B6");
        }
    }

    // ======================================================
    //  LyricsAnimator
    // ======================================================

    private void initLyricsAnimator() {
        if (lyricsAnimator != null) { try { lyricsAnimator.stop(); } catch (Throwable ignored) {} lyricsAnimator = null; }
        stopPositionSync();
        lyricsAnimator = new LyricsAnimator();
        lyricsAnimator.setOnUpdateListener(new LyricsAnimator.OnUpdateListener() {
            @Override public void onPositionUpdate(long pos) { updatePos(pos); }
            @Override public void onLineChanged(int li) {}
            @Override public void onPlaybackStateChanged(boolean p) {}
        });
        lyricsAnimator.start();
        startPositionSync();
    }

    private void startPositionSync() {
        if (positionSyncHandler == null) positionSyncHandler = new Handler(Looper.getMainLooper());
        if (positionSyncRunnable != null) return;
        positionSyncRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    maybeRefreshMC();
                    if (mediaController != null && mediaController.getPlaybackState() != null) {
                        PlaybackState st = mediaController.getPlaybackState();
                        if (st.getState() == PlaybackState.STATE_PLAYING && lyricsAnimator != null) {
                            lyricsAnimator.calibratePosition(st.getPosition(), st.getLastPositionUpdateTime());
                            if (positionSyncHandler != null) positionSyncHandler.postDelayed(this, 1000);
                        } else { if (positionSyncHandler != null) positionSyncHandler.postDelayed(this, 3000); }
                    } else { if (positionSyncHandler != null) positionSyncHandler.postDelayed(this, 2000); }
                } catch (Exception e) { LogHelper.e(TAG, "pos sync", e); if (positionSyncHandler != null) positionSyncHandler.postDelayed(this, 2000); }
            }
        };
        positionSyncHandler.post(positionSyncRunnable);
    }

    private void stopPositionSync() {
        if (positionSyncHandler != null && positionSyncRunnable != null) positionSyncHandler.removeCallbacks(positionSyncRunnable);
    }

    private void updatePos(long position) {
        if (lyricsView != null) lyricsView.updatePosition(position);
    }

    private void maybeRefreshMC() {
        long now = System.currentTimeMillis();
        if (now - lastMediaControllerRefreshCheckMs < MEDIA_CONTROLLER_REFRESH_CHECK_THROTTLE_MS) return;
        lastMediaControllerRefreshCheckMs = now;
        KuwoCarLyricsPolicy.maybeRefreshIfNeeded(this, mediaController, kuwoCarLyricsSessionActive, this::refreshMC);
    }

    // ======================================================
    //  TaskService
    // ======================================================

    private void bindTaskService() {
        if (taskService != null) return;
        try { bindService(new Intent(this, RootTaskService.class), taskServiceConnection, Context.BIND_AUTO_CREATE); }
        catch (Exception e) { LogHelper.e(TAG, "bindTaskService failed", e); }
    }

    private void unbindTaskService() {
        try { if (taskService != null) { unbindService(taskServiceConnection); taskService = null; } }
        catch (Exception e) { LogHelper.w(TAG, "unbind failed", e); }
    }

    private void checkAndDisableOfficialGesture() { disableOfficialGesture(); }

    private void disableOfficialGesture() {
        if (isOfficialGestureDisabled) return;
        ITaskService ts = taskService;
        if (ts == null) ts = RootTaskServiceConnector.getIfConnected();
        if (ts == null) return;
        try { if (ts.disableSubScreenLauncher()) { isOfficialGestureDisabled = true; if (taskService == null) taskService = ts; } }
        catch (Exception e) { LogHelper.w(TAG, "disableGesture failed", e); }
    }

    private void enableOfficialGesture() {
        if (!isOfficialGestureDisabled) return;
        if (taskService == null) { isOfficialGestureDisabled = false; return; }
        try { taskService.enableSubScreenLauncher(); } catch (Exception ignored) {}
        isOfficialGestureDisabled = false;
    }

    // ======================================================
    //  Screen receiver
    // ======================================================

    private void registerScreenReceiver() {
        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() == null) return;
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    uiHandler.post(() -> {
                        try { if (!isFinishing() && getWindow() != null)
                            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED); }
                        catch (Exception ignored) {}
                    });
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } else { registerReceiver(screenReceiver, filter); }
    }

    // ======================================================
    //  Settings apply
    // ======================================================

    private void applySettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String fp = String.valueOf(prefs.getAll().hashCode());
        if (fp.equals(lastAppliedSettingsFingerprint)) return;
        lastAppliedSettingsFingerprint = fp;

        float origTextSize = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
        float origMarquee = prefs.getFloat(KEY_MARQUEE_LIGHT_SIZE, 18f);
        marqueeLightDurationMs = prefs.getInt(KEY_MARQUEE_LIGHT_DURATION_MS, DEFAULT_MARQUEE_LIGHT_DURATION_MS);
        normalLyricsAlpha = prefs.getInt(KEY_NORMAL_LYRICS_ALPHA, DEFAULT_NORMAL_LYRICS_ALPHA);
        backgroundTextureAlpha = prefs.getInt(KEY_BACKGROUND_TEXTURE_ALPHA, DEFAULT_BACKGROUND_TEXTURE_ALPHA);
        wordByWordEnabled = prefs.getBoolean(KEY_WORD_BY_WORD, false);
        shuffleSplitEffectEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_EFFECT, false);
        shuffleSplitMulticolorEnabled = prefs.getBoolean(KEY_SHUFFLE_SPLIT_MULTICOLOR, false);
        shuffleSplitMode = prefs.getString(KEY_SHUFFLE_SPLIT_MODE, "WORD");
        shuffleSplitOnlyCurrentLine = prefs.getBoolean(KEY_SHUFFLE_SPLIT_ONLY_CURRENT_LINE, true);
        if (shuffleSplitEffectEnabled) { shuffleSplitMode = "WORD"; shuffleSplitOnlyCurrentLine = true; wordByWordEnabled = false; }
        boolean newAbyssal = prefs.getBoolean(KEY_ABYSSAL_MIRROR, false);
        marqueeLightEnabled = prefs.getBoolean(KEY_MARQUEE_LIGHT, true);
        neonDisplayEnabled = prefs.getBoolean(KEY_NEON_DISPLAY, prefs.getBoolean(KEY_LYRICS_NEON_GLOW_LEGACY, true));
        neonBorderEnabled = prefs.getBoolean(KEY_NEON_BORDER, true);
        gestureControlEnabled = prefs.getBoolean(KEY_GESTURE_CONTROL, true);
        if (shuffleSplitEffectEnabled) gestureControlEnabled = false;
        if (newAbyssal) { wordByWordEnabled = false; shuffleSplitEffectEnabled = false; }
        backgroundTextureEnabled = prefs.getBoolean(KEY_BACKGROUND_TEXTURE, false);
        albumArtBackgroundEnabled = prefs.getBoolean(KEY_ALBUM_ART_BACKGROUND, false);
        albumArtAlphaPercent = Math.max(0, Math.min(100, prefs.getInt(KEY_ALBUM_ART_ALPHA_PERCENT, DEFAULT_ALBUM_ART_ALPHA_PERCENT)));
        albumArtBlurRadiusPx = Math.max(0f, prefs.getFloat(KEY_ALBUM_ART_BLUR_RADIUS, DEFAULT_ALBUM_ART_BLUR_RADIUS_PX));
        projectionSyncOffsetMs = prefs.getInt(KEY_PROJECTION_SYNC_OFFSET_MS, DEFAULT_PROJECTION_SYNC_OFFSET_MS);
        breathingEnabled = prefs.getBoolean(KEY_BREATHING_ENABLED, false);
        breathingBpm = prefs.getInt(KEY_BREATHING_BPM, DEFAULT_BREATHING_BPM);
        breathingScaleVariance = prefs.getFloat(KEY_BREATHING_SCALE_VARIANCE, DEFAULT_BREATHING_SCALE_VARIANCE);
        breathingDisplacementStrength = prefs.getFloat(KEY_BREATHING_DISPLACEMENT_STRENGTH, DEFAULT_BREATHING_DISPLACEMENT_STRENGTH);
        colorChangeIntervalMs = Math.max(MIN_COLOR_CHANGE_INTERVAL_MS,
            Math.min(MAX_COLOR_CHANGE_INTERVAL_MS, prefs.getInt(KEY_COLOR_CHANGE_INTERVAL_MS, DEFAULT_COLOR_CHANGE_INTERVAL_MS)));
        randomColorSwitchEnabled = prefs.getBoolean(KEY_RANDOM_COLOR_SWITCH_ENABLED, true);
        powerSavingModeEnabled = prefs.getBoolean(KEY_POWER_SAVING_MODE, false);
        borderPerformanceGuardEnabled = prefs.getBoolean(KEY_BORDER_PERFORMANCE_GUARD, false);
        borderLightweightModeEnabled = prefs.getBoolean(KEY_BORDER_LIGHTWEIGHT_MODE, false);
        loadLyricsFontFieldsFromPrefs(prefs);
        currentTextSize = origTextSize * 2f;
        backgroundTextureSize = prefs.getFloat(KEY_BACKGROUND_TEXTURE_SIZE, DEFAULT_BACKGROUND_TEXTURE_SIZE);
        marqueeLightSize = origMarquee * MAIN_SCREEN_MARQUEE_LIGHT_SIZE_MULTIPLIER;

        if (newAbyssal != abyssalMirrorEnabled) { abyssalMirrorEnabled = newAbyssal; recreate(); return; }
        abyssalMirrorEnabled = newAbyssal;

        applyProjectionBackgroundMode();
        if (albumArtBackgroundView != null) albumArtBackgroundView.setAlpha(albumArtAlphaPercent / 100f);
        updateAlbumArtBackground(mediaController != null ? mediaController.getMetadata() : null);

        if (abyssalMirrorEnabled && abyssalMirrorLyricsViewGroup != null) {
            float gs = prefs.getFloat(KEY_ABYSSAL_GYRO_SENSITIVITY, DEFAULT_ABYSSAL_GYRO_SENSITIVITY);
            float mr = prefs.getFloat(KEY_ABYSSAL_MOVABLE_RANGE, DEFAULT_ABYSSAL_MOVABLE_RANGE);
            abyssalMirrorLyricsViewGroup.setTextSize(currentTextSize);
            abyssalMirrorLyricsViewGroup.setTextColor(randomColorSwitchEnabled ? randomHighSaturationColor() : 0xFFFFFFFF);
            abyssalMirrorLyricsViewGroup.setEnableGesture(gestureControlEnabled);
            abyssalMirrorLyricsViewGroup.setGyroSensitivityMultiplier(gs);
            abyssalMirrorLyricsViewGroup.setMovableRangeMultiplier(mr);
            abyssalMirrorLyricsViewGroup.setColorChangeIntervalMs(colorChangeIntervalMs);
            abyssalMirrorLyricsViewGroup.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
            abyssalMirrorLyricsViewGroup.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
            abyssalMirrorLyricsViewGroup.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
            Typeface tf = LyricsFontHelper.resolveTypeface(this, projectionLyricsFontId, projectionLyricsCustomPath);
            if (tf != null) abyssalMirrorLyricsViewGroup.setLyricsTypeface(tf);
        } else if (lyricsView != null) {
            boolean es = !powerSavingModeEnabled && shuffleSplitEffectEnabled;
            lyricsView.setEnableWordByWord(wordByWordEnabled);
            lyricsView.setCharJumpEnabled(wordByWordEnabled);
            lyricsView.setEnableShuffleSplitEffect(es);
            lyricsView.setShuffleSplitMode(es ? "WORD" : shuffleSplitMode);
            lyricsView.setShuffleOnlyCurrentLine(es || shuffleSplitOnlyCurrentLine);
            float sv = prefs.contains(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE)
                ? prefs.getFloat(KEY_SHUFFLE_SPLIT_SCALE_VARIANCE, 0.22f)
                : adaptiveShuffleSplitScaleVariance(currentTextSize);
            lyricsView.setShuffleSplitScaleVariance(sv);
            lyricsView.setTextSize(currentTextSize);
            lyricsView.setLineSpacing(160f * 1.5f);
            lyricsView.setBackgroundTextureSize(backgroundTextureSize);
            lyricsView.setNormalLyricsAlpha(normalLyricsAlpha);
            lyricsView.setBackgroundTextureAlpha(backgroundTextureAlpha);
            lyricsView.setEnableGesture(gestureControlEnabled);
            lyricsView.setShowBackgroundTexture(backgroundTextureEnabled);
            lyricsView.setPowerSavingModeEnabled(powerSavingModeEnabled);
            lyricsView.setBreathingRhythmMs(powerSavingModeEnabled ? 5000 : breathingBpmToRhythmMs(breathingBpm));
            lyricsView.setBreathingScaleVariance((powerSavingModeEnabled || !breathingEnabled) ? 0f : breathingScaleVariance);
            lyricsView.setBreathingDisplacementStrength((powerSavingModeEnabled || !breathingEnabled) ? 0f : breathingDisplacementStrength);
            lyricsView.setBreathingScaleEnabled(breathingEnabled && !powerSavingModeEnabled);
            lyricsView.setColorChangeIntervalMs(colorChangeIntervalMs);
            lyricsView.setRandomColorSwitchEnabled(randomColorSwitchEnabled);
            lyricsView.setShuffleSplitMulticolorEnabled(es && shuffleSplitMulticolorEnabled);
            lyricsView.setNeonLyricsEnabled(neonDisplayEnabled);
            lyricsView.setLyricsFont(projectionLyricsFontId, projectionLyricsCustomPath);
            lyricsView.setTimeAdjustOffset(projectionSyncOffsetMs);
            lyricsView.setLyricsHorizontalInsetPx(0);
            LyricsProjectionColorSync.bindLyricsView(lyricsView, randomColorSwitchEnabled, colorChangeIntervalMs);
            lyricsView.postInvalidateOnAnimation();
        }

                        if (marqueeLightView != null) {
            boolean v = marqueeLightEnabled || neonBorderEnabled;
            marqueeLightView.setVisibility(v ? View.VISIBLE : View.GONE);
            marqueeLightView.setBorderFrameEnabled(neonBorderEnabled);
            marqueeLightView.setNeonEffectsEnabled(neonDisplayEnabled);
            marqueeLightView.setMarqueeLightEnabled(marqueeLightEnabled);
            marqueeLightView.setLightSize(marqueeLightSize);
            marqueeLightView.setAnimationDuration(marqueeLightDurationMs);
            marqueeLightView.setPerformanceGuardEnabled(borderPerformanceGuardEnabled);
            marqueeLightView.setLightweightModeEnabled(powerSavingModeEnabled || borderLightweightModeEnabled);
            marqueeLightView.setColorChangeIntervalMs(colorChangeIntervalMs);
            LyricsProjectionColorSync.bindMarqueeLight(marqueeLightView, randomColorSwitchEnabled, colorChangeIntervalMs);
        }
    }

    private long breathingBpmToRhythmMs(int bpm) {
        return bpm <= 0 ? 5000L : Math.round(60000f / Math.max(1, bpm));
    }

    private float adaptiveShuffleSplitScaleVariance(float textSize) {
        float t = Math.max(0f, Math.min(1f, ((textSize / 2f) - 40f) / 100f));
        return 0.30f - (0.30f - 0.16f) * t;
    }

    private int randomHighSaturationColor() {
        java.util.Random r = new java.util.Random();
        return android.graphics.Color.HSVToColor(new float[]{r.nextFloat() * 360f, 0.85f + r.nextFloat() * 0.15f, 0.85f + r.nextFloat() * 0.15f});
    }

    // ======================================================
    //  Lifecycle
    // ======================================================

    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay().getDisplayId() != 0) {
                    finishProjectionFromUser("wrong-display-onResume");
                    return;
                }
            } catch (Exception ignored) {}
        }
        registerActiveSessionsChangedListener();
        setupMediaController(); updateMediaInfo(); updatePlaybackState();
        // Restore full lyrics from bridge cache if the view was reset to a single-line placeholder
        if (kuwoBroadcastLyricBridge != null) kuwoBroadcastLyricBridge.restoreCachedLyrics();
        applySettings();
        // 立即用当前播放位置刷新歌词，避免等待下一次 positionSync 回调（最多延迟 1 秒）
        if (mediaController != null && mediaController.getPlaybackState() != null) {
            long pos = mediaController.getPlaybackState().getPosition();
            if (pos >= 0) updatePos(pos);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInForeground = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        cancelUiAnim();
    }

    @Override
    protected void onDestroy() {
        isInForeground = false;
        unregSessions();
        stopPositionSync();
        if (lyricsAnimator != null) { try { lyricsAnimator.stop(); } catch (Throwable ignored) {} lyricsAnimator = null; }
        if (mediaController != null && mediaControllerCallback != null) {
            try { mediaController.unregisterCallback(mediaControllerCallback); } catch (Throwable ignored) {}
            mediaControllerCallback = null;
        }
        mediaController = null;
        if (kuwoCarMediaSessionHelper != null) kuwoCarMediaSessionHelper.disconnect();
        if (kuwoBroadcastLyricBridge != null) { try { kuwoBroadcastLyricBridge.stop(); } catch (Exception ignored) {} kuwoBroadcastLyricBridge = null; }
        if (screenReceiver != null) { try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {} screenReceiver = null; }
        try { lyricsParseExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { superLyricWordFusionExecutor.shutdownNow(); } catch (Exception ignored) {}
        enableOfficialGesture();
        unbindTaskService();
        if (systemUICheckHandler != null && systemUICheckRunnable != null) systemUICheckHandler.removeCallbacks(systemUICheckRunnable);
        if (systemUiExitConfirmRunnable != null) uiHandler.removeCallbacks(systemUiExitConfirmRunnable);
        if (legacySystemUiExitConfirmRunnable != null) uiHandler.removeCallbacks(legacySystemUiExitConfirmRunnable);
        if (taskServiceRecheckRunnable != null) uiHandler.removeCallbacks(taskServiceRecheckRunnable);
        if (taskServiceRebindRunnable != null) uiHandler.removeCallbacks(taskServiceRebindRunnable);
        if (notifyStopRetryRunnable != null) uiHandler.removeCallbacks(notifyStopRetryRunnable);
        if (restoreLauncherRetryRunnable != null) uiHandler.removeCallbacks(restoreLauncherRetryRunnable);
        super.onDestroy();
        if (currentInstance == this) { currentInstance = null; }
    }

    private void cancelUiAnim() {
        if (uiAnimationsCancelled) return;
        uiAnimationsCancelled = true;
        if (lyricsAnimator != null) { try { lyricsAnimator.pause(); } catch (Exception ignored) {} }
        if (lyricsView != null) { try { lyricsView.animate().cancel(); } catch (Exception ignored) {} }
        if (abyssalMirrorLyricsViewGroup != null) { try { abyssalMirrorLyricsViewGroup.animate().cancel(); } catch (Exception ignored) {} }
        if (abyssalMirrorLyricsView != null) { try { abyssalMirrorLyricsView.animate().cancel(); } catch (Exception ignored) {} }
        if (marqueeLightView != null) { try { marqueeLightView.animate().cancel(); } catch (Exception ignored) {} }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        try { if (!isFinishing() && getWindow() != null)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED); }
        catch (Exception ignored) {}
    }

    // ======================================================
    //  Finish
    // ======================================================

    private void finishProjectionFromUser(String reason) {
        if (projectionExitFlowStarted || isFinishing()) return;
        projectionExitFlowStarted = true;
        finishRequestedByMiRoot = true;
        LogHelper.d(TAG, "Finishing: " + reason);
        try { ProjectionOngoingNotifications.cancelAll(getApplicationContext()); } catch (Exception ignored) {}
        try { ProjectionOnlyNotificationHelper.cancelMusic(this); } catch (Exception ignored) {}
        finish();
    }

    @Override
    public void finish() {
        try {
            if (hasActiveLyricsUI() && getWindow() != null) {
                getWindow().getDecorView().setVisibility(View.GONE);
            }
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
            return;
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    private void finishProjectionTask() {
        if (finishRequestedByMiRoot || projectionExitFlowStarted) { finish(); return; }
        finishProjectionFromUser("system-ui-exit");
    }

    // ======================================================
    //  Helpers
    // ======================================================

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    public boolean hasActiveLyricsUI() {
        return (abyssalMirrorEnabled && (abyssalMirrorLyricsViewGroup != null || abyssalMirrorLyricsView != null))
            || (!abyssalMirrorEnabled && lyricsView != null);
    }

    public static void finishFromExternal(Context context) {
        MainScreenMusicActivity inst = currentInstance;
        if (inst != null && !inst.isFinishing()) {
            inst.finishProjectionFromUser("external-stop");
        }
    }
}