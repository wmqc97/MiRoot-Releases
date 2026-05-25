/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.wmqc.miroot.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.view.animation.DecelerateInterpolator;
import com.wmqc.miroot.BuildConfig;
import com.wmqc.miroot.R;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 现代化歌词显示视图
 * 
 * 功能特性:
 * - 多行显示(5-7行可见)
 * - 平滑滚动动画
 * - 逐字高亮(如果有逐字时间戳)
 * - 双语歌词支持
 * - 当前行居中高亮
 * - 渐变透明度效果
 * - 手势拖拽定位
 * - 进度条显示
 */
public class ModernLyricsView extends View {
    private static final String TAG = "ModernLyricsView";
    private final Random random = new Random();
    private final ArrayList<String> wrappedLineBuffer = new ArrayList<>();
    private static final int LYRIC_DIM_BASE_WHITE = 0x00FFFFFF;
    
    // 配置参数 - V3.17: 背屏优化
    private float CURRENT_TEXT_SIZE = 72f;        // 当前行字体大小（背屏优化：比普通行大2-3号）
    private float NORMAL_TEXT_SIZE = 60f;         // 普通行字体大小
    private float backgroundTextureSizeScale = 1.3f;  // 底图文本大小倍数（相对于当前行字体）
    private float LINE_SPACING = 160f;             // 行间距（背屏：随字号自动算；主屏横屏可由 setLineSpacing 固定）
    /** true 表示行距由外部 setLineSpacing 固定（如主屏横屏），false 时随字号/翻译行重算 */
    private boolean lineSpacingLockedByExternal = false;
    private static final float LINE_SPACING_MIN_GAP = 28f; // 行与行之间的留白（作为上限参考，实际由 computeAutoLineSpacing 按字高比例计算）
    private static final float LINE_SPACING_GAP_RATIO = 0.35f; // 行间距中留白占主歌词块高的比例
    private static final float LINE_SPACING_GAP_MIN = 8f;  // 留白下限（小字不至于挤在一起）
    private static final float LINE_SPACING_GAP_MAX = 48f; // 留白上限（大字不至于太松散）
    /** 翻译字号相对普通行；略小于原 0.7，为背屏三行留出空间。 */
    private static final float TRANSLATION_TEXT_SIZE_RATIO = 0.58f;
    private static final int MAX_TRANSLATION_WRAP_LINES = 2;
    private static final String TRANSLATION_ELLIPSIS = "…";
    /** 三行模式可视跨度（中心 ±1.5 行距）。 */
    private static final float VIEWPORT_VISIBLE_LINE_SPAN = 3f;
    private static final float VIEWPORT_FILL_RATIO = 0.85f;
    private static final float HORIZONTAL_TEXT_MARGIN = 20f; // 歌词左右边距（在水平 inset 之内）

    /**
     * 背屏等场景：自屏幕左缘起整块区域不绘制歌词（与标题区左侧留白一致），由 Activity 设置；主屏为 0。
     */
    private int lyricsHorizontalInsetPx = 0;
    private static final float WRAPPED_SUBLINE_GAP = 10f; // 同一句歌词换行后的行间距
    private static final float LETTER_SPACING = 2f;            // 字间距（背屏优化：放大1-2px）
    // 随机颜色相关
    private int currentTextColor = 0xFFFF3D00;  // 当前文字颜色（高饱和色池）
    private int targetTextColor = 0xFFFF3D00;  // 目标文字颜色
    private int colorTransitionStart = 0xFFFF3D00;  // 颜色过渡起始颜色
    private float colorTransitionProgress = 0f;  // 颜色过渡进度（0-1）
    private long lastColorChangeTime = 0;        // 上次颜色变化时间
    private static final long COLOR_CHANGE_INTERVAL_DEFAULT_MS = 5000L;  // 颜色变化间隔（默认 5s）
    private boolean randomColorSwitchEnabled = true;
    /** 非逐字同句播放时节流 invalidate；与换色逻辑无关，勿复用于 updateRandomColor（逐字约 20ms 一帧，整段节流会导致换色周期偏离设置）。 */
    private static final long COLOR_UPDATE_THROTTLE_MS = 33;  // 约30fps
    // 逐字模式前向羽化：只作用于播放点前方未唱文本，避免硬切
    private static final float WORD_BY_WORD_FEATHER_PX = 40f;
    private static final float WORD_BY_WORD_FEATHER_MIN_FRACTION = 0.03f;
    private static final float WORD_BY_WORD_FEATHER_MAX_FRACTION = 0.08f;
    // 三行模式顶行退场淡化区：用于上一行滚出时先渐隐，避免硬切消失
    private static final float TOP_EXIT_FADE_ZONE_LINES = 0.45f;
    // 分词打乱/切句瞬间：新句子入场淡入，避免“闪整句”
    private static final long SHUFFLE_ENTRY_FADE_MS = 180L;
    
    private int normalLyricsAlpha = 30;  // 未唱歌词透明度(0-100%)
    private int backgroundTextureAlpha = 20;  // 底图歌词透明度(0-100%)
    
    /**
     * 根据透明度百分比计算颜色值
     */
    private int getNormalLyricsColor() {
        int alpha = (int)(normalLyricsAlpha * 255 / 100f);
        return (alpha << 24) | LYRIC_DIM_BASE_WHITE;  // 未到歌词改为白色淡化
    }
    
    private int getDimLyricsColor() {
        // DIM颜色使用normalLyricsAlpha的2/3
        int alpha = (int)(normalLyricsAlpha * 255 / 100f * 2 / 3);
        return (alpha << 24) | LYRIC_DIM_BASE_WHITE;  // 已过歌词改为白色淡化（更暗）
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blendColor(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
    
    /**
     * 更新未唱歌词颜色
     */
    private void updateNormalLyricsColor() {
        if (normalPaint != null) {
            normalPaint.setColor(getNormalLyricsColor());
        }
    }
    
    /**
     * 更新暗淡歌词颜色
     */
    private void updateDimLyricsColor() {
        if (dimPaint != null) {
            dimPaint.setColor(getDimLyricsColor());
        }
    }
    
    /**
     * 更新底图颜色
     */
    private void updateBackgroundTextureColor() {
        if (backgroundTexturePaint != null) {
            int alpha = (int)(backgroundTextureAlpha * 255 / 100f);
            backgroundTexturePaint.setColor((alpha << 24) | 0x00CCCCCC);  // ARGB格式，浅灰色
        }
    }
    private static final int TRANSLATION_COLOR = 0x66CCCCCC;   // 翻译颜色(浅灰色，适配黑色背景)
    private static final long SCROLL_ANIMATION_DURATION = 300; // 滚动动画时长(毫秒)
    /** 单句→完整 LRC 升级时的滚动过渡（略长、更自然） */
    private static final long SINGLE_LINE_UPGRADE_SCROLL_DURATION_MS = 420L;
    private static final long SCROLL_ANIMATION_THROTTLE_MS = 80L;
    private static final float SCROLL_ANIMATION_MIN_DELTA_PX = 6f;
    private static final long SHUFFLE_INVALIDATE_THROTTLE_MS = 66L; // 约15fps，进一步降低背屏分词模式重绘压力
    private static final int VISIBLE_LINES = 7;                // 可见行数
    private static final float STROKE_WIDTH = 1f;              // 描边宽度(1px)
    private static final int STROKE_COLOR = 0x4D000000;       // 描边颜色(30%透明度黑色)
    private static final float TEXT_OUTLINE_WIDTH = 1.5f;    // 文字轮廓描边宽度(1.5px)
    private static final int TEXT_OUTLINE_COLOR = 0x80000000; // 文字轮廓描边颜色(50%透明度黑色)
    // 呼吸缩放幅度中等：降低夸张感，保留可感知的字体浮动
    private static final float SCALE_MIN = 0.97f;              // 缩放最小值
    private static final float SCALE_MAX = 1.08f;              // 缩放最大值
    private static final long BREATHING_RHYTHM_MIN_MS = 600L;
    private static final long BREATHING_RHYTHM_MAX_MS = 5000L;
    private static final float BREATHING_SCALE_VARIANCE_MIN = 0f;
    private static final float BREATHING_SCALE_VARIANCE_MAX = 0.20f;
    private static final float BREATHING_DISPLACEMENT_STRENGTH_MIN = 0f;
    private static final float BREATHING_DISPLACEMENT_STRENGTH_MAX = 3.0f;
    private static final LinearInterpolator BREATHING_PHASE_INTERPOLATOR =
            new LinearInterpolator();
    private static final float BREATHING_PEAK_HOLD_FRACTION = 0.04f; // 峰值处短暂停留占比（每周期）
    private static final long COLOR_CHANGE_INTERVAL_MIN_MS = 1000L;
    private static final long COLOR_CHANGE_INTERVAL_MAX_MS = 10000L;
    private static final long SHUFFLE_LAYOUT_REBUILD_INTERVAL_MAX_MS = 300L;
    private static final float DISPLACEMENT_OFFSET = 2.2f;     // 微位移偏移
    private static final long WORD_FADE_DURATION = 100;        // 逐字淡入动画时长(0.1s)
    private static final float SHUFFLE_MAX_OFFSET_X = 28f;
    private static final float SHUFFLE_MAX_OFFSET_Y = 14f;
    private static final float SHUFFLE_MAX_ROTATION = 7f;
    private static final float SHUFFLE_MIN_SCALE = 0.92f;
    private static final float SHUFFLE_MAX_SCALE = 1.08f;
    private static final float SHUFFLE_CAMERA_AVOID_WIDTH_RATIO = 0.34f;
    private static final float SHUFFLE_CAMERA_AVOID_HEIGHT_RATIO = 0.22f;
    /** 分词飘字布局：在 {@link #lyricsHorizontalInsetPx} 之外再预留的边距（与 inset 叠加）。 */
    private static final float SHUFFLE_EXTRA_LEFT_MARGIN_PX = 0f;
    private static final float SHUFFLE_MIN_TOKEN_SPACING = 56f;
    private static final long WORD_PROGRESS_MAX_CATCHUP_PER_FRAME_MS = 42L;
    private static final long WORD_PROGRESS_SEEK_SNAP_THRESHOLD_MS = 480L;
    /** 播放进度小幅回退（MediaSession 抖动）低于此值不更新目标位置，避免高亮来回跳。 */
    private static final long WORD_PROGRESS_POSITION_JITTER_MS = 72L;
    /** 融合逐字时间轴相对 LRC 行进度最多允许领先的幅度，防止时间轴偏短导致整句秒满。 */
    private static final float WORD_PROGRESS_MAX_LEAD_OVER_LINE = 0.10f;
    private static final long WORD_PROGRESS_FRAME_ACTIVE_MS = 20L;
    private static final long WORD_PROGRESS_FRAME_POWER_SAVE_MS = 33L;
    private static final long WORD_PROGRESS_FRAME_PAUSED_MS = 250L;
    private static final float WORD_PROGRESS_MAX_STEP_PER_MS = 0.0030f;
    /** 逐字平滑单帧最大步进，避免融合刷新后整句追赶。 */
    private static final float WORD_PROGRESS_MAX_STEP_PER_FRAME = 0.055f;
    /** 无有效 end 时的单字默认时长（与 SuperLyric 解析默认一致）。 */
    private static final long WORD_TIMESTAMP_FALLBACK_DURATION_MS = 220L;
    
    // 数据
    private List<EnhancedLRCParser.EnhancedLyricLine> lyricLines = new ArrayList<>();
    private int currentLineIndex = -1;
    private long currentPosition = 0;
    private long targetPlaybackPosition = 0;
    /** 最近一次来自 MediaSession/动画器的原始播放位置（毫秒），不含 [timeAdjustOffset]。 */
    private long lastRawPlaybackPosition = 0;
    private long lastPositionFrameUptimeMs = 0L;
    private String lastWordProgressLineKey = "";
    private float lastWordProgressValue = 0f;
    private long lastWordProgressSampleUptimeMs = 0L;
    private int wordTimestampsRevision = 0;
    private int lastWordProgressRevision = -1;
    // Kuwo broadcast direct word hint — overrides time-based progress for accurate word-by-word
    private boolean mKuwoWordHintValid = false;
    private int mKuwoWordHintLineIndex = -1;   // line index from broadcast
    private int mKuwoWordHintCharStart = -1;   // wordCharStart from broadcast
    private int mKuwoWordHintCharEnd = -1;     // wordCharEnd from broadcast
    private long mKuwoWordHintTimestamp = 0L;  // uptimeMs when hint was set
    private float scrollY = 0f;
    private float targetScrollY = 0f;
    /** 时间调整偏移量（毫秒）：正数表示相对媒体进度提前显示（与歌词滞后于声音时调大）。 */
    private long timeAdjustOffset = 0;
    
    // 绘图相关
    private TextPaint currentPaint;
    private TextPaint normalPaint;
    private TextPaint dimPaint;
    private TextPaint translationPaint;
    private Paint progressPaint;
    private Paint bgPaint;  // 纯黑背景，避免 onDraw 每帧 new Paint
    private boolean opaqueBackgroundEnabled = true;
    private Paint strokePaint;  // 描边画笔
    private Paint shufflePaint; // 分词打乱绘制画笔
    private TextPaint backgroundTexturePaint;  // V3.17: 背景纹理画笔
    private TextPaint outlinePaint;  // 文字轮廓描边画笔（用于增强可读性）
    // neonGlow 画笔已移除：drawNeonTextGlow 为空实现，无需分配
    
    // 动画
    private ValueAnimator scrollAnimator;
    private long lastScrollAnimateStartMs = 0L;
    private float lastScrollAnimateTargetY = Float.NaN;
    private ValueAnimator scaleAnimator;  // 缩放动画（呼吸动画）
    private float currentScale = 1.0f;    // 当前缩放值
    private float currentDisplacement = 0f; // 当前位移值
    private long baseScaleAnimationDuration = 2000;  // 基础缩放动画时长（毫秒）
    private long currentScaleAnimationDuration = 2000;  // 当前缩放动画时长（关联音谱）
    private float breathingScaleVariance = 0.10f;
    private float breathingScaleMin = SCALE_MIN;
    private float breathingScaleMax = SCALE_MAX;
    private float breathingDisplacementStrength = 1.0f;
    
    private long colorChangeIntervalMs = COLOR_CHANGE_INTERVAL_DEFAULT_MS;
    
    
    // 手势
    private GestureDetector gestureDetector;
    private boolean isDragging = false;
    private float lastTouchY = 0f;
    /** 本次按下以来 onScroll 累计位移；超过 touchSlop 则视为滑动，不再在 UP 时当作点按切行 */
    private float tapAccumulatedPanDistance = 0f;
    
    // 用户操作检测和自动居中
    private Handler autoCenterHandler;
    private Runnable autoCenterRunnable;
    private static final long AUTO_CENTER_DELAY_MS = 3000; // 3秒后自动居中
    private long lastUserInteractionTime = 0; // 最后用户操作时间
    /** 防止同一行连续重复启动自动居中动画。 */
    private int lastAutoCenterAnimatedLineIndex = -1;
    
    // 配置选项
    private boolean showTranslation = true;     // 是否显示翻译
    private boolean enableWordByWord = true;    // 是否启用逐字高亮
    private boolean showProgress = false;       // V3.17: 禁用进度条显示
    private boolean enableGesture = true;       // 是否启用手势交互
    private boolean showBackgroundTexture = true; // V3.17: 是否显示背景纹理
    private boolean showBackgroundGradient = false; // V3.17: 禁用背景渐变，使用纯黑背景
    private boolean enableShuffleSplitEffect = false; // 当前行分词打乱效果（默认关闭，不影响原有歌词效果）
    private boolean shuffleSplitMulticolorEnabled = false; // 分词模式是否启用多色 token
    private boolean neonLyricsEnabled = true; // 歌词霓虹开关（由“霓虹效果”控制）
    private boolean shuffleOnlyCurrentLine = true; // 只显示当前行
    private String shuffleSplitMode = "WORD"; // CHAR / WORD / PHRASE
    private float shuffleSplitTiltRatio = 5f; // 0..20: 最大倾斜角度（度）
    private float shuffleSplitScaleVariance = 0.22f; // 0..0.4: 词组大小正负浮动强度
    private String stableShuffleSignature = "";
    private List<ShuffleToken> stableShuffleTokens = new ArrayList<>();
    private int lastShuffleLineCount = 0;
    private int lastShuffleTokenCount = 0;
    private OnShuffleDebugInfoListener shuffleDebugInfoListener;
    
    // 调试 / 节流
    private long lastDrawTime = 0;
    private long lastOffCenterLogTime = 0;
    private static final long DEBUG_DRAW_LOG_INTERVAL_MS = 5000L;
    private static final long DEBUG_OFFCENTER_LOG_INTERVAL_MS = 8000L;
    /** 逐字模式下行内进度刷新：限制 postInvalidate 频率 */
    private long lastLyricProgressInvalidateTime = 0;
    /**
     * 非逐字、当前行不变且正在播放时的重绘节流。
     * 若仅依赖呼吸缩放动画 invalidate，而呼吸未开启（默认），则 onDraw 不跑、颜色过渡/随机换色会卡在「只有切行才刷新」。
     */
    private long lastSameLinePlayingInvalidateMs = 0L;
    private static final long LYRIC_PROGRESS_INVALIDATE_MS = WORD_PROGRESS_FRAME_ACTIVE_MS;
    private static final long PAUSED_LYRIC_INVALIDATE_MS = WORD_PROGRESS_FRAME_PAUSED_MS; // 暂停时降为低帧刷新，减少耗电
    private boolean shufflePerformanceGuardEnabled = true;
    private boolean powerSavingModeEnabled = false;
    /** 省电模式下：颜色仅在「换行」时刷新（避免按节拍高频变色）。 */
    private int powerSavingColorBoundLineIndex = Integer.MIN_VALUE;
    private boolean breathingScaleEnabled = false; // 默认关闭呼吸动画，避免背屏低功耗场景额外重绘
    private boolean trackLoading = false;
    private boolean playbackActive = true;
    private long lastPausedInvalidateAtMs = 0L;
    private long shuffleLastFrameSampleMs = 0L;
    private float shuffleFpsEma = 60f;
    private int shufflePerfLevel = 0; // 0=全特效, 1=轻降级, 2=强降级
    private long shuffleLastLayoutBuildMs = 0L;
    private long shuffleLayoutBuildIntervalOverrideMs = -1L;
    private long shuffleLayoutBuildIntervalMs = 0L;

    // 分词/逐字切换入场淡入：记录最近一次行切换时间
    private long lastLineChangedAtMs = 0L;
    
    // 回调接口
    public interface OnLyricLineClickListener {
        void onLyricLineClick(int lineIndex);
    }
    
    // V3.8: 退出投屏回调接口
    public interface OnExitProjectionListener {
        void onExitProjection();
    }
    
    private OnLyricLineClickListener lineClickListener;
    private OnExitProjectionListener exitProjectionListener;
    private final ExecutorService tokenizeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lyrics-tokenize");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, List<TokenChunk>> tokenCache = new ConcurrentHashMap<>();
    private final Set<String> pendingTokenTasks = ConcurrentHashMap.newKeySet();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastShuffleInvalidateAtMs = 0L;
    private final Runnable tokenizerReadyListener = new Runnable() {
        @Override
        public void run() {
            clearTokenizationCache();
            refreshShuffleAfterWordTokenChange();
        }
    };

    private static class ShuffleToken {
        final String text;
        final float width;
        final float x;
        final float y;
        final float rotation;
        final float scale;
        final float textSize;

        ShuffleToken(String text, float width, float x, float y, float rotation, float scale, float textSize) {
            this.text = text;
            this.width = width;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.scale = scale;
            this.textSize = textSize;
        }
    }

    public interface OnShuffleDebugInfoListener {
        void onDebugInfo(float textSize, float variance, float angle, int lineCount, int tokenCount);
    }
    
    public ModernLyricsView(Context context) {
        this(context, null);
    }
    
    public ModernLyricsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public ModernLyricsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // 优先由 RenderThread 合成滚动动画，减轻 UI 线程同步绘制压力。
        setLayerType(LAYER_TYPE_HARDWARE, null);
        Typeface lyricTypeface = LyricsFontHelper.resolveTypeface(getContext(), LyricsFontHelper.DEFAULT_ID, null);

        // 初始化画笔 - V3.17: 背屏优化
        currentPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        currentPaint.setTextSize(CURRENT_TEXT_SIZE);
        currentPaint.setColor(currentTextColor);  // 使用随机颜色
        currentPaint.setTextAlign(Paint.Align.CENTER);
        currentPaint.setFakeBoldText(false);  // 使用正常粗细，不使用粗体
        currentPaint.setTypeface(lyricTypeface);
        currentPaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE); // 字间距（背屏优化）
        
        normalPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        normalPaint.setTextSize(NORMAL_TEXT_SIZE);
        updateNormalLyricsColor();
        normalPaint.setTextAlign(Paint.Align.CENTER);
        normalPaint.setTypeface(lyricTypeface);
        normalPaint.setLetterSpacing(LETTER_SPACING / NORMAL_TEXT_SIZE); // 字间距（背屏优化）
        
        dimPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        dimPaint.setTextSize(NORMAL_TEXT_SIZE);
        updateDimLyricsColor();
        dimPaint.setTextAlign(Paint.Align.CENTER);
        dimPaint.setTypeface(lyricTypeface);
        dimPaint.setLetterSpacing(LETTER_SPACING / NORMAL_TEXT_SIZE); // 字间距（背屏优化）
        
        translationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        translationPaint.setTextSize(NORMAL_TEXT_SIZE * TRANSLATION_TEXT_SIZE_RATIO);
        translationPaint.setColor(TRANSLATION_COLOR);
        translationPaint.setTextAlign(Paint.Align.CENTER);
        translationPaint.setTypeface(lyricTypeface);
        
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.FILL);

        shufflePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shufflePaint.setStyle(Paint.Style.FILL);
        shufflePaint.setTextAlign(Paint.Align.CENTER);
        shufflePaint.setTextSize(CURRENT_TEXT_SIZE);
        shufflePaint.setTypeface(lyricTypeface);

        bgPaint = new Paint();
        bgPaint.setColor(0xFF000000);
        
        // 描边画笔（用于当前歌词描边效果）
        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(STROKE_WIDTH);
        strokePaint.setColor(STROKE_COLOR);
        strokePaint.setTextAlign(Paint.Align.CENTER);
        strokePaint.setTextSize(CURRENT_TEXT_SIZE);
        strokePaint.setFakeBoldText(false);  // 使用正常粗细，不使用粗体
        strokePaint.setTypeface(lyricTypeface);
        strokePaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);
        
        // V3.17: 背景纹理画笔（低透明度模糊歌词纹理）
        backgroundTexturePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        backgroundTexturePaint.setTextSize(CURRENT_TEXT_SIZE * backgroundTextureSizeScale);
        updateBackgroundTextureColor();
        backgroundTexturePaint.setTextAlign(Paint.Align.CENTER);
        backgroundTexturePaint.setTypeface(lyricTypeface);
        backgroundTexturePaint.setFakeBoldText(false);  // 使用正常粗细，不使用粗体
        
        // 文字轮廓描边画笔（用于增强可读性）
        outlinePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(TEXT_OUTLINE_WIDTH);
        outlinePaint.setColor(TEXT_OUTLINE_COLOR);
        outlinePaint.setTextAlign(Paint.Align.CENTER);
        outlinePaint.setTextSize(CURRENT_TEXT_SIZE);
        outlinePaint.setTypeface(lyricTypeface);
        outlinePaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);

        // 初始化自动居中Handler
        autoCenterHandler = new Handler(Looper.getMainLooper());
        autoCenterRunnable = new Runnable() {
            @Override
            public void run() {
                // 3秒后自动居中到当前行（同一行只触发一次）
                if (currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
                    float targetScrollY = currentLineIndex * LINE_SPACING;
                    if (shouldAutoCenterCurrentLine(targetScrollY)) {
                        animateScroll(scrollY, targetScrollY);
                        lastAutoCenterAnimatedLineIndex = currentLineIndex;
                        if (BuildConfig.DEBUG) {
                            LogHelper.d(TAG, "⏰ 用户无操作3秒，自动居中到当前行: " + currentLineIndex);
                        }
                    }
                }
            }
        };
        
        // 初始化手势检测
        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (enableGesture && !lyricLines.isEmpty()) {
                    tapAccumulatedPanDistance += Math.abs(distanceX) + Math.abs(distanceY);
                    isDragging = true;
                    scrollY += distanceY;
                    // 限制滚动范围
                    float maxScroll = (lyricLines.size() - 1) * LINE_SPACING;
                    scrollY = Math.max(0, Math.min(scrollY, maxScroll));
                    
                    // 用户操作时，重置自动居中定时器
                    resetAutoCenterTimer();
                    
                    invalidate();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                // 可以添加惯性滚动效果
                return true;
            }
        });

        applyAutoLineSpacingFromFontIfNeeded();
        LyricsWordTokenizer.addOnTokenizerReadyListener(tokenizerReadyListener);
    }

    private float getMainTranslationGapPx() {
        return normalPaint != null ? normalPaint.getTextSize() * 0.12f : 0f;
    }

    /**
     * 按主歌词真实折行块高计算行距；翻译仅当前行显示，行距预留翻译区（最多两行）。
     */
    private float computeAutoLineSpacing() {
        float mainH = measureMaxMainLyricBlockHeight();
        float transReserve = 0f;
        if (showTranslation && hasAnyTranslationInLyrics()) {
            transReserve = getMainTranslationGapPx() + measureMaxTranslationBlockHeight();
        }
        float gap = Math.max(LINE_SPACING_GAP_MIN, Math.min(LINE_SPACING_GAP_MAX, mainH * LINE_SPACING_GAP_RATIO));
        return mainH + transReserve + gap;
    }

    private boolean hasAnyTranslationInLyrics() {
        for (EnhancedLRCParser.EnhancedLyricLine line : lyricLines) {
            if (line.translation != null && !line.translation.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private float measureMaxMainLyricBlockHeight() {
        float max = Math.max(getLineHeightPx(currentPaint), getLineHeightPx(normalPaint));
        for (EnhancedLRCParser.EnhancedLyricLine line : lyricLines) {
            String text = line.text != null ? line.text : "";
            if (text.isEmpty()) {
                continue;
            }
            max = Math.max(max, getTextBlockHeight(currentPaint, wrapTextLines(currentPaint, text).size()));
            max = Math.max(max, getTextBlockHeight(normalPaint, wrapTextLines(normalPaint, text).size()));
        }
        return max;
    }

    private float measureMaxTranslationBlockHeight() {
        float twoLineH = getTextBlockHeight(translationPaint, MAX_TRANSLATION_WRAP_LINES);
        float max = twoLineH;
        for (EnhancedLRCParser.EnhancedLyricLine line : lyricLines) {
            if (line.translation == null || line.translation.isEmpty()) {
                continue;
            }
            max = Math.max(max, getTranslationBlockHeight(line.translation));
        }
        return max;
    }

    private float getTranslationBlockHeight(String translation) {
        if (translation == null || translation.isEmpty()) {
            return 0f;
        }
        return getTextBlockHeight(translationPaint, wrapTranslationLines(translation).size());
    }

    /**
     * 视口兜底：三行总高度超出可视区时按比例压缩行距（不低于主歌词块高 + 最小留白）。
     */
    private float applyViewportAdaptiveSpacing(float rawSpacing) {
        int viewH = getHeight();
        if (viewH <= 0 || lineSpacingLockedByExternal) {
            return rawSpacing;
        }
        float budget = viewH * VIEWPORT_FILL_RATIO;
        float needed = rawSpacing * VIEWPORT_VISIBLE_LINE_SPAN;
        if (needed <= budget) {
            return rawSpacing;
        }
        float scale = budget / needed;
        float floor = measureMaxMainLyricBlockHeight() + Math.min(LINE_SPACING_MIN_GAP, 12f);
        return Math.max(rawSpacing * scale, floor);
    }

    private void applyAutoLineSpacingFromFontIfNeeded() {
        if (lineSpacingLockedByExternal || currentPaint == null || normalPaint == null) {
            return;
        }
        LINE_SPACING = applyViewportAdaptiveSpacing(computeAutoLineSpacing());
        syncScrollToCurrentLineAfterSpacingChange();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h != oldh) {
            applyAutoLineSpacingFromFontIfNeeded();
        }
    }

    private void syncScrollToCurrentLineAfterSpacingChange() {
        if (lyricLines.isEmpty() || currentLineIndex < 0) {
            return;
        }
        targetScrollY = currentLineIndex * LINE_SPACING;
        if (!isDragging && (scrollAnimator == null || !scrollAnimator.isRunning())) {
            scrollY = targetScrollY;
        } else if (Math.abs(scrollY - targetScrollY) > 4f) {
            animateScroll(scrollY, targetScrollY);
        }
    }

    /**
     * 取消 {@link #setLineSpacing(float)} 的固定行距，之后由字号自动计算（背屏或从主屏横屏切回时需先于 {@link #setTextSize(float)} 调用）。
     */
    public void clearFixedLineSpacing() {
        lineSpacingLockedByExternal = false;
    }

    /**
     * 将「行垂直中心」转为 drawText 所需的 baseline（与字号无关，保证当前行在背屏几何中心对齐）
     */
    private static float verticalCenterToBaseline(TextPaint paint, float verticalCenterY) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return verticalCenterY - (fm.ascent + fm.descent) / 2f;
    }

    /**
     * 当前行应处的垂直中心（本 View 内坐标）。
     * 优先用窗口根视图在屏幕上的垂直中点映射到本 View，保证「当前句」落在整屏高度正中（含背屏刘海/竖条区）；
     * 无法换算时再退回父 LinearLayout 的几何关系。
     */
    private float getLyricsBandVerticalCenterY() {
        int h = getHeight();
        if (h <= 0) {
            return 0f;
        }
        try {
            if (isAttachedToWindow()) {
                View root = getRootView();
                if (root != null && root.getHeight() > 0) {
                    int[] viewLoc = new int[2];
                    int[] rootLoc = new int[2];
                    getLocationOnScreen(viewLoc);
                    root.getLocationOnScreen(rootLoc);
                    float rootMidGlobalY = rootLoc[1] + root.getHeight() * 0.5f;
                    float yInView = rootMidGlobalY - viewLoc[1];
                    if (!Float.isNaN(yInView) && !Float.isInfinite(yInView)) {
                        return Math.max(0f, Math.min(h, yInView));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        ViewParent vp = getParent();
        if (!(vp instanceof View)) {
            return h / 2f;
        }
        View parent = (View) vp;
        int ph = parent.getHeight();
        if (ph <= 0) {
            return h / 2f;
        }
        float cy = ph / 2f - getTop();
        if (cy < 0f || cy > h) {
            return Math.max(0f, Math.min(h, cy));
        }
        return cy;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 默认绘制纯黑底；外层使用专辑图背景时可关闭，透出底层图层。
        if (opaqueBackgroundEnabled) {
            drawBackgroundGradient(canvas);
        }
        
        // 更新颜色：
        // - 随机颜色开启：保持原先随机色池效果（逐字也跟随随机色池）
        // - 随机颜色关闭：主色固定不透明白；逐字时未唱段用 getNormalLyricsColor()（透明度）区分
        if (!powerSavingModeEnabled && randomColorSwitchEnabled) {
            updateRandomColor();
        } else if (!randomColorSwitchEnabled) {
            applyReadableStaticColor();
        }
        syncRenderedPosition(false);
        
        if (lyricLines.isEmpty()) {
            // 显示占位文本
            drawPlaceholder(canvas);
            return;
        }
        
        float centerY = getLyricsBandVerticalCenterY();
        float centerX = getLyricsDrawCenterX();
        
        // 分词模式下禁用整行背景纹理，避免切句瞬间出现“整行歌词闪现”。
        if (showBackgroundTexture && !enableShuffleSplitEffect
                && currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
            drawBackgroundTexture(canvas, lyricLines.get(currentLineIndex), centerX, centerY);
        }
        
        // 不再强制居中，允许用户自由滚动
        // 用户停止操作3秒后会自动居中（由autoCenterRunnable处理）
        
        // 添加绘制调试日志（debug 下节流，避免高频刷屏）
        long now = System.currentTimeMillis();
        updateShufflePerformanceState(now);
        if (BuildConfig.DEBUG && now - lastDrawTime > DEBUG_DRAW_LOG_INTERVAL_MS) {
            LogHelper.d(TAG, "🎨 绘制歌词: 行数=" + lyricLines.size() + 
                      ", 当前行=" + currentLineIndex + 
                      ", 位置=" + currentPosition + "ms" +
                      ", scrollY=" + scrollY);
            lastDrawTime = now;
        }
        
        // 绘制每一行歌词（lineAnchorY 为该行主歌词的垂直中心，与字号无关）
        for (int i = 0; i < lyricLines.size(); i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);

            if (enableShuffleSplitEffect && shuffleOnlyCurrentLine && i != currentLineIndex) {
                continue;
            }

            float lineAnchorY = centerY + (i * LINE_SPACING) - scrollY;
            
            // V3.17: 只绘制可见区域的歌词，只显示三行（当前行、上一行、下一行）
            float distanceFromCenter = Math.abs(lineAnchorY - centerY);
            if (distanceFromCenter > LINE_SPACING * 1.5f) {
                continue;
            }
            
            float halfFade = Math.max(1f, Math.max(centerY, getHeight() - centerY));
            float alpha = 1f - (distanceFromCenter / halfFade);
            // 三行模式：顶部行向上滚出时需要更明显淡出；底部第三行淡入仍保留最小可见度。
            float alphaMin = (lineAnchorY < centerY) ? 0.0f : 0.2f;
            alpha = Math.max(alphaMin, Math.min(1f, alpha));
            // 顶行退场补偿：接近上侧可见边界时，额外做一次快速淡出，避免被裁剪时硬切。
            float visibleHalfLines = 1.5f;
            float topCutoffY = centerY - LINE_SPACING * visibleHalfLines;
            float topFadeZonePx = Math.max(1f, LINE_SPACING * TOP_EXIT_FADE_ZONE_LINES);
            if (lineAnchorY < centerY && lineAnchorY < topCutoffY + topFadeZonePx) {
                float exitFade = (lineAnchorY - topCutoffY) / topFadeZonePx;
                exitFade = Math.max(0f, Math.min(1f, exitFade));
                alpha *= exitFade;
            }
            
            if (i == currentLineIndex) {
                if (BuildConfig.DEBUG &&
                    Math.abs(lineAnchorY - centerY) > 5f &&
                    now - lastOffCenterLogTime > DEBUG_OFFCENTER_LOG_INTERVAL_MS) {
                    lastOffCenterLogTime = now;
                    LogHelper.d(TAG, "📊 当前行锚点偏离中心: lineAnchorY=" + lineAnchorY + ", centerY=" + centerY +
                              ", scrollY=" + scrollY + ", currentLineIndex=" + currentLineIndex);
                }
                drawCurrentLine(canvas, line, centerX, lineAnchorY);
            } else {
                drawNormalLine(canvas, line, centerX, lineAnchorY, alpha);
            }
            
            if (showTranslation && i == currentLineIndex
                    && line.translation != null && !line.translation.isEmpty()) {
                TextPaint mainPaint = currentPaint;
                String mainText = line.text != null ? line.text : "";
                float mainHalfH = getWrappedBlockHalfHeight(mainPaint, mainText);
                float transHalfH = getTranslationBlockHeight(line.translation) / 2f;
                float transCenterY = lineAnchorY + mainHalfH + getMainTranslationGapPx() + transHalfH;
                drawTranslation(canvas, line.translation, centerX, transCenterY, alpha);
            }
        }
    }
    
    /**
     * 绘制背景渐变底色
     * 改为深色背景（接近黑色）
     */
    private void drawBackgroundGradient(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
    }
    
    /**
     * 绘制背景歌词纹理（当前歌词的模糊版）
     * V3.17: 背屏优化 - 低透明度模糊歌词纹理
     */
    private void drawBackgroundTexture(Canvas canvas, EnhancedLRCParser.EnhancedLyricLine line, float centerX, float centerY) {
        if (line == null || line.text == null || line.text.isEmpty()) {
            return;
        }

        int alpha = (int) (backgroundTextureAlpha * 255 / 100f);
        backgroundTexturePaint.setAlpha(alpha);
        List<String> wrappedLines = wrapTextLines(backgroundTexturePaint, line.text, centerX);
        int lineCount = wrappedLines.size();
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(1.3f, 1.3f);
        canvas.translate(-centerX, -centerY);
        for (int i = 0; i < lineCount; i++) {
            String displayText = wrappedLines.get(i);
            float lineCenterY = getWrappedLineCenterY(backgroundTexturePaint, centerY, i, lineCount);
            float texBaseline = verticalCenterToBaseline(backgroundTexturePaint, lineCenterY);
            float textWidth = backgroundTexturePaint.measureText(displayText);
            float adjustedCenterX = clampCenterXForTextWidth(centerX, textWidth);
            canvas.drawText(displayText, adjustedCenterX, texBaseline, backgroundTexturePaint);
        }
        canvas.restore();
    }
    
    /**
     * 绘制占位文本
     */
    private void drawPlaceholder(Canvas canvas) {
        String text = getContext().getString(
            trackLoading ? R.string.music_loading_lyrics : R.string.music_no_lyrics);
        float bandCenterY = getLyricsBandVerticalCenterY();
        float centerX = getLyricsDrawCenterX();
        List<String> wrappedLines = wrapTextLines(normalPaint, text, centerX);
        int lineCount = wrappedLines.size();
        for (int i = 0; i < lineCount; i++) {
            String displayText = wrappedLines.get(i);
            float lineCenterY = getWrappedLineCenterY(normalPaint, bandCenterY, i, lineCount);
            float lineBaseline = verticalCenterToBaseline(normalPaint, lineCenterY);
            float textWidth = normalPaint.measureText(displayText);
            float adjustedCenterX = clampCenterXForTextWidth(centerX, textWidth);
            canvas.drawText(displayText, adjustedCenterX, lineBaseline, normalPaint);
        }
    }
    
    /**
     * 绘制当前行（支持逐字高亮）
     * @param lineCenterY 该行主歌词垂直中心（与背屏高度中心对齐，不随字号当 baseline 用）
     */
    private void drawCurrentLine(Canvas canvas, EnhancedLRCParser.EnhancedLyricLine line, float centerX, float lineCenterY) {
        float baseline = verticalCenterToBaseline(currentPaint, lineCenterY);
        float finalY = baseline - currentDisplacement;
        // 呼吸缩放必须以「行垂直中心」为轴心；以 baseline 为轴会在大字号下明显偏离屏中心
        float scalePivotY = lineCenterY - currentDisplacement;
        
        // 保存画布状态
        canvas.save();
        
        float combinedScale = currentScale;
        canvas.translate(centerX, scalePivotY);
        canvas.scale(combinedScale, combinedScale);
        canvas.translate(-centerX, -scalePivotY);
        
        // 根据逐字显示开关决定绘制方式
        if (enableShuffleSplitEffect) {
            drawShuffledSplitLine(canvas, line, centerX, finalY);
        } else if (enableWordByWord) {
            // 逐字高亮模式：开启逐字显示时，无论是否有逐字时间戳，都使用逐字显示模式
            // 如果有逐字时间戳，使用精确的逐字时间戳；如果没有，使用行进度模拟逐字效果
            drawWordByWord(canvas, line, centerX, scalePivotY);
        } else {
            // 整行高亮模式：关闭逐字显示后，当前行完全高亮（progress=1）
            float progress = 1.0f; // 关闭逐字显示时，当前行完全高亮
            drawLineWithProgress(canvas, line.text, centerX, scalePivotY, progress);
        }
        
        // 恢复画布状态
        canvas.restore();
        
        // V3.17: 已禁用进度条显示
        // if (showProgress) {
        //     float progress = calculateLineProgress(line);
        //     drawProgressBar(canvas, centerX, finalY + CURRENT_TEXT_SIZE * 0.8f, progress);
        // }
        
        // 呼吸缩放默认关闭；开启时才启动动画，且省电模式不启动，避免背屏额外重绘耗电
        if (breathingScaleEnabled
                && !powerSavingModeEnabled
                && (scaleAnimator == null || !scaleAnimator.isRunning())) {
            startScaleAnimation();
        }
    }
    
    private enum ShuffleSplitMode {
        CHAR,
        WORD,
        PHRASE
    }

    private static class TokenChunk {
        final String text;
        final boolean separator;

        TokenChunk(String text, boolean separator) {
            this.text = text;
            this.separator = separator;
        }
    }

    private List<TokenChunk> splitLyricTokens(String text, ShuffleSplitMode mode) {
        List<TokenChunk> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        if (mode == ShuffleSplitMode.CHAR) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c)) {
                    tokens.add(new TokenChunk(String.valueOf(c), false));
                }
            }
            return tokens;
        }
        if (mode == ShuffleSplitMode.WORD) {
            String cacheKey = "WORD|" + text;
            List<TokenChunk> cached = tokenCache.get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
            // 分词显示模式下若“兜底 token = 整行”，会导致先出现整行、随后被异步分词替换为多 token，
            // 体感就是“整行歌词闪现一下”。这里改为“稳定兜底”：优先用轻量中文词典分词，
            // 最差也拆成 2 字一组（保证不会退化成整行 1 token）。
            List<TokenChunk> stableFallback = buildStableWordFallbackTokens(text);
            if (!stableFallback.isEmpty()) {
                tokenCache.putIfAbsent(cacheKey, stableFallback);
            }

            // jieba 尚未 ready 时直接使用稳定兜底；等 ready 后会通过 readyListener clear cache 触发重建。
            if (!LyricsWordTokenizer.isJiebaReady()) {
                return stableFallback;
            }

            if (pendingTokenTasks.add(cacheKey)) {
                tokenizeExecutor.execute(() -> {
                    try {
                        List<TokenChunk> computed = new ArrayList<>();
                        for (String word : LyricsWordTokenizer.tokenize(text)) {
                            if (word != null && !word.isEmpty()) {
                                computed.add(new TokenChunk(word, false));
                            }
                        }
                        if (computed.isEmpty()) {
                            computed = stableFallback;
                        }

                        List<TokenChunk> before = tokenCache.get(cacheKey);
                        boolean meaningfulUpgrade = isMeaningfulTokenUpgrade(before, computed);
                        if (meaningfulUpgrade) {
                            tokenCache.put(cacheKey, computed);
                            refreshShuffleAfterWordTokenChange();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        pendingTokenTasks.remove(cacheKey);
                    }
                });
            }
            return stableFallback;
        }

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(new TokenChunk(current.toString(), false));
                    current.setLength(0);
                }
                continue;
            }
            if (isPhraseBoundary(c)) {
                if (current.length() > 0) {
                    tokens.add(new TokenChunk(current.toString(), false));
                    current.setLength(0);
                }
                tokens.add(new TokenChunk(String.valueOf(c), true));
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(new TokenChunk(current.toString(), false));
        }
        return tokens;
    }

    private List<TokenChunk> fallbackWordTokens(String text) {
        List<TokenChunk> fallback = new ArrayList<>();
        if (text == null || text.isEmpty()) return fallback;
        StringBuilder chunk = new StringBuilder();
        boolean chunkIsCjk = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (chunk.length() > 0) {
                    fallback.add(new TokenChunk(chunk.toString(), false));
                    chunk.setLength(0);
                }
                continue;
            }
            boolean isCjk = Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
            if (chunk.length() == 0) {
                chunk.append(c);
                chunkIsCjk = isCjk;
            } else if (chunkIsCjk == isCjk) {
                chunk.append(c);
            } else {
                fallback.add(new TokenChunk(chunk.toString(), false));
                chunk.setLength(0);
                chunk.append(c);
                chunkIsCjk = isCjk;
            }
        }
        if (chunk.length() > 0) {
            fallback.add(new TokenChunk(chunk.toString(), false));
        }
        return fallback;
    }

    private List<TokenChunk> buildStableWordFallbackTokens(String text) {
        List<TokenChunk> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        // 1) 优先使用轻量中文词典分词（同步、稳定）
        try {
            List<String> words = SimpleChineseTokenizer.INSTANCE.tokenize(text);
            if (words != null) {
                for (String w : words) {
                    if (w != null && !w.trim().isEmpty()) {
                        out.add(new TokenChunk(w.trim(), false));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) 若词典分词失败/过于粗糙（退化成“整行 1 token”），则使用更稳定的保底拆分
        if (out.isEmpty() || (out.size() == 1 && out.get(0).text != null && out.get(0).text.trim().equals(text.trim()))) {
            out.clear();
            if (containsHan(text)) {
                out.addAll(fallbackCjkBiGramTokens(text));
            } else {
                out.addAll(fallbackWordTokens(text));
            }
        }

        // 3) 最终兜底：确保至少能输出一些 token（避免空导致上层“等待 token”一直不画）
        if (out.isEmpty()) {
            out.addAll(fallbackWordTokens(text));
        }
        return out;
    }

    private boolean isMeaningfulTokenUpgrade(List<TokenChunk> before, List<TokenChunk> after) {
        if (after == null || after.isEmpty()) return false;
        if (tokensTextEquals(before, after)) return false;
        // 避免把结果降级成 1 token（会重新制造“整行闪现”或布局突变）
        if (after.size() == 1) return false;
        // 若之前为空或只有 1 个 token，而新结果拆分为多个 token，则认为是有意义升级
        if (before == null || before.isEmpty()) return true;
        if (before.size() <= 1 && after.size() > 1) return true;
        // 常规情况下：token 数变化较大才认为值得刷新，避免频繁重排造成抖动
        int delta = Math.abs(after.size() - before.size());
        return delta >= 2;
    }

    private boolean tokensTextEquals(List<TokenChunk> a, List<TokenChunk> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TokenChunk ta = a.get(i);
            TokenChunk tb = b.get(i);
            String sa = ta == null ? null : ta.text;
            String sb = tb == null ? null : tb.text;
            if (sa == null ? sb != null : !sa.equals(sb)) return false;
        }
        return true;
    }

    private boolean containsHan(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            if (script == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private List<TokenChunk> fallbackCjkBiGramTokens(String text) {
        List<TokenChunk> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            // 保留标点为单独 token，避免 2 字组把标点粘进去
            if (isPhraseBoundary(c)) {
                if (buf.length() > 0) {
                    out.add(new TokenChunk(buf.toString(), false));
                    buf.setLength(0);
                }
                out.add(new TokenChunk(String.valueOf(c), true));
                continue;
            }
            buf.append(c);
            if (buf.length() >= 2) {
                out.add(new TokenChunk(buf.toString(), false));
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            out.add(new TokenChunk(buf.toString(), false));
        }
        return out;
    }

    private void postShuffleInvalidateIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastShuffleInvalidateAtMs < SHUFFLE_INVALIDATE_THROTTLE_MS) {
            return;
        }
        lastShuffleInvalidateAtMs = now;
        mainHandler.post(this::postInvalidateOnAnimation);
    }

    private void refreshShuffleAfterWordTokenChange() {
        mainHandler.post(() -> {
            if (!enableShuffleSplitEffect) {
                return;
            }
            if (resolveShuffleSplitMode(null) != ShuffleSplitMode.WORD) {
                return;
            }
            stableShuffleSignature = "";
            stableShuffleTokens.clear();
            postShuffleInvalidateIfNeeded();
        });
    }


    private boolean isPhraseBoundary(char c) {
        return c == '，' || c == '。' || c == '！' || c == '？' || c == '、' || c == ',' || c == '.' || c == '!' || c == '?';
    }

    private boolean isPunctuation(char c) {
        return String.valueOf(c).matches("\\p{Punct}");
    }

    private float stableFloat(long seed, float maxAbs) {
        long mixed = seed;
        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= (mixed >>> 33);
        float normalized = ((mixed & 0x7fffffffL) / (float) 0x7fffffffL) * 2f - 1f;
        return normalized * maxAbs;
    }

    private float chooseScale(long seed, String tokenText) {
        int len = tokenText == null ? 1 : tokenText.length();
        float base = 1f;
        if (isCoreWord(tokenText)) {
            base += 0.14f;
        } else if (isConnectorWord(tokenText)) {
            base -= 0.12f;
        }
        if (len >= 4) {
            base -= 0.10f;
        } else if (len <= 1) {
            base += 0.12f;
        } else if (len == 2) {
            base += 0.06f;
        } else if (len == 3) {
            base += 0.03f;
        }
        // 词组“正负浮动”基准缩放：进一步拉大大小差异，再叠加整行呼吸缩放
        base += stableFloat(seed, shuffleSplitScaleVariance);
        return Math.max(0.66f, Math.min(1.44f, base));
    }

    private float chooseRotation(long seed) {
        float maxAngle = Math.max(0f, Math.min(20f, shuffleSplitTiltRatio));
        if (shufflePerformanceGuardEnabled && enableShuffleSplitEffect) {
            if (shufflePerfLevel >= 2) {
                maxAngle = Math.min(maxAngle, 8f);
            } else if (shufflePerfLevel == 1) {
                maxAngle = Math.min(maxAngle, 14f);
            }
        }
        if (powerSavingModeEnabled) {
            return 0f;
        }
        if (maxAngle <= 0.01f) {
            return 0f;
        }
        // 正/斜随机分配：一部分保持正向，其他词按 ±maxAngle 随机倾斜
        float uprightChance = 0.42f;
        float p = Math.abs(stableFloat(seed, 1f));
        if (p < uprightChance) {
            return 0f;
        }
        return stableFloat(seed + 1L, maxAngle);
    }

    private ShuffleSplitMode resolveShuffleSplitMode(String text) {
        if ("CHAR".equals(shuffleSplitMode)) {
            return ShuffleSplitMode.CHAR;
        }
        if ("PHRASE".equals(shuffleSplitMode)) {
            return ShuffleSplitMode.PHRASE;
        }
        return ShuffleSplitMode.WORD;
    }

    public void setShuffleSplitTiltRatio(float ratio) {
        if (ratio < 0f) ratio = 0f;
        if (ratio > 20f) ratio = 20f;
        this.shuffleSplitTiltRatio = ratio;
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();
        this.lastAutoCenterAnimatedLineIndex = -1;
        notifyShuffleDebugInfo();
        postInvalidate();
    }

    public void setShuffleSplitScaleVariance(float variance) {
        if (variance < 0f) variance = 0f;
        if (variance > 0.4f) variance = 0.4f;
        this.shuffleSplitScaleVariance = variance;
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();
        notifyShuffleDebugInfo();
        postInvalidate();
    }

    public void setShufflePerformanceGuardEnabled(boolean enabled) {
        this.shufflePerformanceGuardEnabled = enabled;
        this.shufflePerfLevel = 0;
        this.shuffleLayoutBuildIntervalMs = 0L;
        this.shuffleLastFrameSampleMs = 0L;
        this.shuffleFpsEma = 60f;
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();
        postInvalidate();
    }

    public void setPowerSavingModeEnabled(boolean enabled) {
        this.powerSavingModeEnabled = enabled;
        if (enabled) {
            this.shufflePerformanceGuardEnabled = true;
            this.enableWordByWord = false;
            this.showBackgroundTexture = false;
            this.currentScale = 1.0f;
            this.currentDisplacement = 0f;
            this.breathingScaleVariance = 0f;
            this.breathingScaleMin = 1f;
            this.breathingScaleMax = 1f;
            if (scaleAnimator != null && scaleAnimator.isRunning()) {
                scaleAnimator.cancel();
            }
            applyPowerSavingLineColorIfNeeded(/*force*/ true);
        }
        postInvalidate();
    }

    private void applyPowerSavingLineColorIfNeeded(boolean force) {
        if (!powerSavingModeEnabled) {
            return;
        }
        int idx = currentLineIndex;
        if (idx < 0) idx = 0;
        if (!force && idx == powerSavingColorBoundLineIndex) {
            return;
        }
        int lineHash = 0;
        if (idx >= 0 && idx < lyricLines.size()) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(idx);
            if (line != null && line.text != null) {
                lineHash = line.text.hashCode();
            }
        }
        long seed = 0x9E3779B97F4A7C15L ^ ((long) idx * 0x85EBCA77C2B2AE63L) ^ ((long) lineHash * 0xC2B2AE3D27D4EB4FL);
        int c = generateVividColorBySeed(seed);
        currentTextColor = c;
        targetTextColor = c;
        colorTransitionStart = c;
        colorTransitionProgress = 1f;
        lastColorChangeTime = System.currentTimeMillis();
        if (currentPaint != null) {
            currentPaint.setColor(currentTextColor);
        }
        powerSavingColorBoundLineIndex = idx;
    }

    public void setBreathingScaleEnabled(boolean enabled) {
        if (this.breathingScaleEnabled == enabled) {
            return;
        }
        this.breathingScaleEnabled = enabled;
        if (!enabled) {
            if (scaleAnimator != null) {
                scaleAnimator.cancel();
                scaleAnimator = null;
            }
            currentScale = 1.0f;
            currentDisplacement = 0f;
            postInvalidateOnAnimation();
            return;
        }
        if (!powerSavingModeEnabled && playbackActive) {
            startScaleAnimation();
            postInvalidateOnAnimation();
        }
    }

    private void ensureStableShuffleLayout(EnhancedLRCParser.EnhancedLyricLine line, String sourceText) {
        // 尺寸未稳定时（例如重建/切屏/布局的第一帧宽高可能为0），不要生成空布局，
        // 否则会触发上层“无 token -> 兜底整行绘制/或闪烁”的观感问题。
        // 等尺寸可用后再构建分词布局，确保切行直接进入分词显示。
        if (getWidth() < 10 || getHeight() < 10) {
            postShuffleInvalidateIfNeeded();
            return;
        }
        ShuffleSplitMode mode = resolveShuffleSplitMode(sourceText);
        String signature =
            line.time + "|" + sourceText + "|" + mode.name() + "|" + getWidth() + "|" + getHeight() +
                "|" + CURRENT_TEXT_SIZE + "|" + shuffleSplitScaleVariance;
        long nowMs = System.currentTimeMillis();
        if (shufflePerformanceGuardEnabled && enableShuffleSplitEffect && shuffleLayoutBuildIntervalMs > 0L) {
            boolean sameSignature = signature.equals(stableShuffleSignature);
            boolean withinCooldown = (nowMs - shuffleLastLayoutBuildMs) < shuffleLayoutBuildIntervalMs;
            if (!sameSignature && withinCooldown && !stableShuffleTokens.isEmpty()) {
                return;
            }
        }
        if (signature.equals(stableShuffleSignature)) {
            return;
        }
        stableShuffleTokens = new ArrayList<>();

        List<TokenChunk> chunks = splitLyricTokens(sourceText, mode);
        if (chunks.isEmpty()) {
            // 避免“构建失败但签名已更新”导致后续帧永远不再重建，从而出现空白不显示。
            stableShuffleSignature = "";
            return;
        }
        stableShuffleSignature = signature;

        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        // 与逐行歌词一致：背屏左侧整块避开，分词飘字也在右半可视区排布
        float safeLeft = Math.max(0f, lyricsHorizontalInsetPx + SHUFFLE_EXTRA_LEFT_MARGIN_PX);
        float left = Math.min(Math.max(safeLeft, 0f), Math.max(0f, w - 40f));
        float right = w - Math.max(16f, w * 0.02f);
        if (right <= left + 20f) {
            left = w * 0.35f;
            right = w - 16f;
        }
        float availableW = Math.max(40f, right - left);

        List<List<TokenChunk>> lines = buildSemanticLines(chunks, mode);
        lines = maybeReflowWordShuffleLinesForWidth(lines, chunks, mode, availableW);
        lastShuffleLineCount = lines.size();

        float top = h * 0.14f;
        float bottom = h * 0.86f;
        float avoidRight = left;
        float avoidBottom = Math.max(h * 0.30f, h * SHUFFLE_CAMERA_AVOID_HEIGHT_RATIO);

        int lineCount = lines.size();
        boolean twoLineLayout = lineCount == 2;
        float blockH = bottom - top;
        float rowHeight = blockH / Math.max(1, lineCount);
        float baseShuffleTextSize = CURRENT_TEXT_SIZE;
        if (lineCount >= 3) {
            // 三行及以上：若可显示高度不足，按比例缩小分词字号，避免挤不下
            float estimatedNeedHeight = lineCount * CURRENT_TEXT_SIZE * 1.18f;
            if (estimatedNeedHeight > blockH) {
                float fit = blockH / Math.max(1f, estimatedNeedHeight);
                baseShuffleTextSize = Math.max(CURRENT_TEXT_SIZE * 0.72f, CURRENT_TEXT_SIZE * fit);
            }
        }
        float measureScale = baseShuffleTextSize / Math.max(1f, CURRENT_TEXT_SIZE);
        ArrayList<float[]> placed = new ArrayList<>();

        for (int row = 0; row < lines.size(); row++) {
            List<TokenChunk> rowTokens = lines.get(row);
            if (rowTokens.isEmpty()) continue;

            float y;
            if (lineCount == 1) {
                // 单行：按整屏高度居中
                y = h * 0.5f;
            } else if (twoLineLayout) {
                // 两行：按可显示高度分布
                float twoLineRowHeight = blockH / 2f;
                y = top + twoLineRowHeight * (row + 0.5f);
            } else {
                // 三行及以上：按可显示高度区间均匀分布
                y = top + rowHeight * (row + 0.5f);
            }
            float centerX = (left + right) * 0.5f;
            float totalTextW = 0f;
            for (TokenChunk token : rowTokens) {
                float measured = currentPaint.measureText(token.text) * measureScale;
                totalTextW += Math.max(measured, baseShuffleTextSize * 0.92f);
            }
            float spacing = rowTokens.size() > 1
                    ? Math.max(18f, (availableW - totalTextW) / (rowTokens.size() - 1))
                    : 0f;
            spacing = Math.min(spacing, w * 0.05f);
            float rowWidth = totalTextW + Math.max(0, rowTokens.size() - 1) * spacing;
            float cursorX = Math.max(left, Math.min(centerX - rowWidth * 0.5f, right - rowWidth));

            for (int index = 0; index < rowTokens.size(); index++) {
                TokenChunk chunk = rowTokens.get(index);
                String tokenText = chunk.text;
                if (tokenText == null || tokenText.isEmpty()) continue;
                float measuredWidth = currentPaint.measureText(tokenText) * measureScale;
                float width = Math.max(measuredWidth, baseShuffleTextSize * 0.9f);
                float height = Math.max(baseShuffleTextSize * 1.18f, 20f);

                float x = cursorX + width * 0.5f;
                float yJitterFactor = twoLineLayout ? 0.15f : 0.08f;
                float adjustY = stableFloat((line.time + row * 31L + index * 17L) ^ tokenText.hashCode(), rowHeight * yJitterFactor);
                float adjustX = stableFloat((line.time + row * 17L + index * 11L) ^ (tokenText.hashCode() * 7L), w * 0.012f);
                x += adjustX;
                float finalY = y + adjustY;

                if (x < avoidRight && finalY < avoidBottom) {
                    x = Math.max(left + Math.min(16f, w * 0.01f), avoidRight + Math.min(44f, w * 0.04f));
                    finalY = Math.max(finalY, avoidBottom + rowHeight * 0.35f);
                }

                boolean collides = false;
                for (float[] p : placed) {
                    float dx = x - p[0];
                    float dy = finalY - p[1];
                    float minDx = Math.max(SHUFFLE_MIN_TOKEN_SPACING, (width + p[2]) * 0.55f);
                    float minDy = Math.max(rowHeight * 0.30f, (height + p[3]) * 0.30f);
                    if (Math.abs(dx) < minDx && Math.abs(dy) < minDy) {
                        collides = true;
                        break;
                    }
                }
                if (collides) {
                    x += width * 0.35f;
                    finalY += rowHeight * (twoLineLayout ? 0.14f : 0.08f);
                }

                long seed = line.time * 131L + row * 997L + index * 17L + tokenText.hashCode();
                float offsetX = stableFloat(seed, SHUFFLE_MAX_OFFSET_X * 0.03f);
                float offsetY = stableFloat(seed + 11L, SHUFFLE_MAX_OFFSET_Y * (twoLineLayout ? 0.08f : 0.03f));
                float rotation = chooseRotation(seed + 23L);
                float scale = chooseScale(seed + 37L, tokenText);
                stableShuffleTokens.add(new ShuffleToken(tokenText, width, x + offsetX, finalY + offsetY, rotation, scale, baseShuffleTextSize));

                cursorX += width + spacing;
            }
        }
        shuffleLastLayoutBuildMs = nowMs;
    }

    /**
     * 与 {@link #ensureStableShuffleLayout} 中单行排版一致的最小行宽估计（词宽 + 最小词间距），
     * 用于判断是否会超出可视区。
     */
    private float measureShuffleRowMinWidth(List<TokenChunk> rowTokens, float baseShuffleTextSize, float measureScale) {
        if (rowTokens == null || rowTokens.isEmpty()) {
            return 0f;
        }
        float totalTextW = 0f;
        for (TokenChunk token : rowTokens) {
            if (token == null || token.text == null || token.text.isEmpty()) {
                continue;
            }
            float measured = currentPaint.measureText(token.text) * measureScale;
            totalTextW += Math.max(measured, baseShuffleTextSize * 0.92f);
        }
        if (rowTokens.size() <= 1) {
            return totalTextW;
        }
        float minGap = 18f;
        return totalTextW + (rowTokens.size() - 1) * minGap;
    }

    /**
     * 将分词序列拆成两行，优先在「两行都不超宽」的断点中选宽度最均衡的；否则贪心再回退到中分。
     */
    private List<List<TokenChunk>> splitWordChunksIntoTwoLinesByWidth(
            List<TokenChunk> chunks,
            float capWidth,
            float baseShuffleTextSize,
            float measureScale) {
        List<List<TokenChunk>> out = new ArrayList<>();
        int n = chunks.size();
        if (n == 0) {
            return out;
        }
        if (n == 1) {
            out.add(new ArrayList<>(chunks));
            return out;
        }

        int bestSplit = -1;
        float bestScore = Float.MAX_VALUE;
        for (int split = 1; split < n; split++) {
            List<TokenChunk> a = new ArrayList<>(chunks.subList(0, split));
            List<TokenChunk> b = new ArrayList<>(chunks.subList(split, n));
            float wa = measureShuffleRowMinWidth(a, baseShuffleTextSize, measureScale);
            float wb = measureShuffleRowMinWidth(b, baseShuffleTextSize, measureScale);
            if (wa <= capWidth && wb <= capWidth) {
                float score = Math.abs(wa - wb);
                if (score < bestScore) {
                    bestScore = score;
                    bestSplit = split;
                }
            }
        }
        if (bestSplit > 0) {
            out.add(new ArrayList<>(chunks.subList(0, bestSplit)));
            out.add(new ArrayList<>(chunks.subList(bestSplit, n)));
            return out;
        }

        List<TokenChunk> row1 = new ArrayList<>();
        int i = 0;
        while (i < n) {
            List<TokenChunk> trial = new ArrayList<>(row1);
            trial.add(chunks.get(i));
            float tw = measureShuffleRowMinWidth(trial, baseShuffleTextSize, measureScale);
            if (tw <= capWidth || row1.isEmpty()) {
                row1.add(chunks.get(i));
                i++;
            } else {
                break;
            }
        }
        if (i >= n) {
            int mid = (n + 1) / 2;
            out.add(new ArrayList<>(chunks.subList(0, mid)));
            out.add(new ArrayList<>(chunks.subList(mid, n)));
            return out;
        }
        List<TokenChunk> row2 = new ArrayList<>(chunks.subList(i, n));
        if (measureShuffleRowMinWidth(row2, baseShuffleTextSize, measureScale) > capWidth && n >= 3) {
            int mid = (n + 1) / 2;
            out.add(new ArrayList<>(chunks.subList(0, mid)));
            out.add(new ArrayList<>(chunks.subList(mid, n)));
            return out;
        }
        out.add(row1);
        out.add(row2);
        return out;
    }

    /**
     * 分词（WORD）模式下：若任一行预估宽度超过可用宽度，则强制改为两行（音乐投屏避免单行裁切）。
     */
    private List<List<TokenChunk>> maybeReflowWordShuffleLinesForWidth(
            List<List<TokenChunk>> lines,
            List<TokenChunk> chunks,
            ShuffleSplitMode mode,
            float availableW) {
        if (mode != ShuffleSplitMode.WORD || chunks == null || chunks.size() < 2) {
            return lines;
        }
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        if (lines.size() > 2) {
            return lines;
        }
        float cap = availableW - Math.max(8f, availableW * 0.02f);
        float baseSz = CURRENT_TEXT_SIZE;
        float ms = 1f;
        for (List<TokenChunk> row : lines) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            if (measureShuffleRowMinWidth(row, baseSz, ms) > cap) {
                return splitWordChunksIntoTwoLinesByWidth(chunks, cap, baseSz, ms);
            }
        }
        return lines;
    }

    private List<List<TokenChunk>> buildSemanticLines(List<TokenChunk> chunks, ShuffleSplitMode mode) {
        List<List<TokenChunk>> result = new ArrayList<>();
        if (chunks.isEmpty()) return result;
        if (chunks.size() <= 3) {
            result.add(new ArrayList<>(chunks));
            return result;
        }

        int targetLines = Math.min(3, Math.max(2, (int) Math.ceil(chunks.size() / 4.0f)));
        // 需求：分词模式下当词组数 > 5 时，强制拆成两行，避免单行拥挤溢出。
        if (mode == ShuffleSplitMode.WORD && chunks.size() > 5) {
            targetLines = 2;
        }
        final int minWordsPerLineTwoLine = 3;
        int totalWeight = 0;
        for (TokenChunk c : chunks) {
            totalWeight += Math.max(1, semanticWeight(c.text));
        }
        int targetPerLine = Math.max(1, (int) Math.ceil(totalWeight / (float) targetLines));

        List<TokenChunk> current = new ArrayList<>();
        int currentWeight = 0;
        int lineIndex = 0;
        for (int i = 0; i < chunks.size(); i++) {
            TokenChunk c = chunks.get(i);
            int w = Math.max(1, semanticWeight(c.text));
            current.add(c);
            currentWeight += w;

            boolean hardBreak = isStrongSemanticBreak(c.text);
            boolean shouldBreak = false;
            if (hardBreak && lineIndex < targetLines - 1) {
                shouldBreak = true;
            } else if (currentWeight >= targetPerLine && lineIndex < targetLines - 1) {
                shouldBreak = true;
            } else if (i == chunks.size() - 1) {
                shouldBreak = true;
            }

            if (targetLines == 2 && lineIndex == 0 && shouldBreak && i < chunks.size() - 1) {
                int remainingCount = chunks.size() - (i + 1);
                int remainingWeight = totalWeight - currentWeight;
                boolean currentTooShort = current.size() < minWordsPerLineTwoLine;
                boolean remainingTooShort = remainingCount < minWordsPerLineTwoLine;

                // 两行模式下，强语义断点不能让第一行过短。
                if (hardBreak && currentTooShort) {
                    shouldBreak = false;
                }

                // 保证两行都有最小词组数，避免一行极空一行极挤。
                if (shouldBreak && (currentTooShort || remainingTooShort)) {
                    shouldBreak = false;
                }

                // 若第一行明显偏轻且第二行负担过重，则延后断行以平衡两行重量。
                if (shouldBreak && currentWeight < remainingWeight) {
                    int diff = Math.abs(currentWeight - remainingWeight);
                    int allowedDiff = Math.max(2, targetPerLine / 2);
                    if (diff > allowedDiff) {
                        shouldBreak = false;
                    }
                }
            }

            if (shouldBreak) {
                // 两行目标下，平衡逻辑会在 i < last 时把断行推迟；到最后一词时 shouldBreak 恒为 true，
                // 但「每行最少词数」检查被跳过，导致整句仍落在一行被裁切。此处强制拆成两行。
                boolean flushAllAsTwoLines = targetLines == 2
                        && lineIndex == 0
                        && i == chunks.size() - 1
                        && current.size() > 1;
                if (flushAllAsTwoLines) {
                    int split = (current.size() + 1) / 2;
                    result.add(new ArrayList<>(current.subList(0, split)));
                    result.add(new ArrayList<>(current.subList(split, current.size())));
                    current = new ArrayList<>();
                    currentWeight = 0;
                    lineIndex += 2;
                } else {
                    result.add(current);
                    current = new ArrayList<>();
                    currentWeight = 0;
                    lineIndex++;
                }
            }
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    private int semanticWeight(String text) {
        if (text == null || text.isEmpty()) return 1;
        if (isPunctuationChar(text)) return 1;
        if (text.length() >= 4) return 4;
        if (text.length() == 3) return 3;
        if (text.length() == 2) return 2;
        return 1;
    }

    private boolean isStrongSemanticBreak(String text) {
        if (text == null || text.isEmpty()) return false;
        if (isPunctuationChar(text)) return true;
        return text.equals("但") || text.equals("却") || text.equals("而") || text.equals("所以") || text.equals("然后") || text.equals("后来") || text.equals("再见") || text.equals("曾经") || text.equals("终于");
    }

    private boolean isCoreWord(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.equals("我") || text.equals("你") || text.equals("他") || text.equals("她") || text.equals("它") || text.equals("我们") || text.equals("他们") || text.equals("她们") || text.equals("它们") || text.equals("你们") || text.equals("咱们") || text.equals("自己") || text.equals("大家") || text.equals("心") || text.equals("爱") || text.equals("梦") || text.equals("你我");
    }

    private boolean isConnectorWord(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.equals("的") || text.equals("了") || text.equals("着") || text.equals("过") || text.equals("啊") || text.equals("吗") || text.equals("吧") || text.equals("呢") || text.equals("和") || text.equals("与") || text.equals("及") || text.equals("在");
    }

    private boolean isPunctuationChar(String text) {
        return text.length() == 1 && isPunctuation(text.charAt(0));
    }

    private void updateShufflePerformanceState(long nowMs) {
        if (!shufflePerformanceGuardEnabled || !enableShuffleSplitEffect) {
            shufflePerfLevel = 0;
            shuffleLayoutBuildIntervalMs = 0L;
            shuffleLastFrameSampleMs = nowMs;
            if (shuffleLayoutBuildIntervalOverrideMs >= 0L) {
                shuffleLayoutBuildIntervalMs = shuffleLayoutBuildIntervalOverrideMs;
            }
            return;
        }
        if (shuffleLastFrameSampleMs > 0L) {
            long dt = nowMs - shuffleLastFrameSampleMs;
            if (dt > 0L) {
                float fps = 1000f / Math.max(1f, dt);
                shuffleFpsEma = shuffleFpsEma * 0.85f + fps * 0.15f;
            }
        }
        shuffleLastFrameSampleMs = nowMs;

        if (shufflePerfLevel == 0) {
            if (shuffleFpsEma < 38f) {
                shufflePerfLevel = 2;
            } else if (shuffleFpsEma < 48f) {
                shufflePerfLevel = 1;
            }
        } else if (shufflePerfLevel == 1) {
            if (shuffleFpsEma < 36f) {
                shufflePerfLevel = 2;
            } else if (shuffleFpsEma > 54f) {
                shufflePerfLevel = 0;
            }
        } else {
            if (shuffleFpsEma > 46f) {
                shufflePerfLevel = 1;
            }
        }

        if (shufflePerfLevel >= 2) {
            shuffleLayoutBuildIntervalMs = 180L;
        } else if (shufflePerfLevel == 1) {
            shuffleLayoutBuildIntervalMs = 90L;
        } else {
            shuffleLayoutBuildIntervalMs = 0L;
        }
        if (shuffleLayoutBuildIntervalOverrideMs >= 0L) {
            shuffleLayoutBuildIntervalMs = shuffleLayoutBuildIntervalOverrideMs;
        }
    }

    private void drawShuffledSplitLine(Canvas canvas, EnhancedLRCParser.EnhancedLyricLine line, float centerX, float baseline) {
        String sourceText = line != null ? line.text : null;
        if (sourceText == null || sourceText.isEmpty()) {
            return;
        }
        ensureStableShuffleLayout(line, sourceText);
        if (stableShuffleTokens.isEmpty()) {
            // 分词显示模式下，切行/布局未就绪时禁止回退到「整行绘制」，
            // 否则会出现“整行闪现 -> 再进入分词”的明显闪烁。
            // 这里选择跳过本帧绘制，并请求下一帧等待 token 布局就绪后直接进入分词绘制。
            postShuffleInvalidateIfNeeded();
            return;
        }
        shufflePaint.setAlpha(255);

        long nowMs = System.currentTimeMillis();
        float entranceFade = 1f;
        if (lastLineChangedAtMs > 0L) {
            entranceFade = (nowMs - lastLineChangedAtMs) / (float) SHUFFLE_ENTRY_FADE_MS;
            entranceFade = Math.max(0f, Math.min(1f, entranceFade));
        }

        float lineProgress = resolveLineProgress(line);

        // 新句子入场淡入：避免刚切行时“整句瞬间出现”
        // 同时避免逐字时间戳存在且当前进度 < 首词时间时 lineProgress=0 导致整行完全透明（看起来像“不显示”）。
        float visibleProgress = 0.22f + 0.78f * lineProgress; // 即使进度为0，也保留少量可见度
        int tokenAlpha = (int) (visibleProgress * entranceFade * 255f);
        tokenAlpha = Math.max(0, Math.min(255, tokenAlpha));
        shufflePaint.setAlpha(tokenAlpha);
        long colorEpoch;
        if (powerSavingModeEnabled) {
            // 省电模式：按「当前行」换色，不按时间节拍抖动
            int idx = currentLineIndex;
            colorEpoch = Math.max(0L, (long) idx);
        } else {
            colorEpoch = colorChangeIntervalMs > 0L ? (nowMs / colorChangeIntervalMs) : 0L;
        }
        int layoutSalt = stableShuffleSignature.hashCode();
        for (int i = 0; i < stableShuffleTokens.size(); i++) {
            ShuffleToken token = stableShuffleTokens.get(i);
            // 多色分词：每 token 高饱和色，整句色板按「颜色变化节奏」与设置页一致切换；否则与主歌词同色。
            int tokenColor = shuffleSplitMulticolorEnabled
                    ? generateVividColorBySeed(
                            colorEpoch * 0x9E3779B97F4A7C15L
                                    + (long) i * 0x85EBCA77C2B2AE63L
                                    + (long) layoutSalt * 0xC2B2AE3D27D4EB4FL)
                    : currentTextColor;
            canvas.save();
            canvas.translate(token.x, token.y);
            canvas.rotate(token.rotation);
            canvas.scale(token.scale, token.scale);
            shufflePaint.setTextSize(token.textSize);
            shufflePaint.setColor(tokenColor);
            drawNeonTextGlow(canvas, token.text, 0f, 0f, tokenColor, shufflePaint);
            if (outlinePaint != null) {
                outlinePaint.setTextSize(token.textSize);
                outlinePaint.setStyle(Paint.Style.STROKE);
                outlinePaint.setStrokeWidth(3f);
                int outlineBase = 0xB3000000;
                int outlineAlpha = (outlineBase >>> 24) & 0xFF;
                int appliedOutlineAlpha = Math.max(0, Math.min(255, (int) (outlineAlpha * (tokenAlpha / 255f))));
                outlinePaint.setColor((appliedOutlineAlpha << 24) | (outlineBase & 0x00FFFFFF));
                canvas.drawText(token.text, 0f, 0f, outlinePaint);
                outlinePaint.setStyle(Paint.Style.FILL);
            }
            canvas.drawText(token.text, 0f, 0f, shufflePaint);
            canvas.restore();
        }
    }

    public void setShuffleSplitMulticolorEnabled(boolean enabled) {
        if (this.shuffleSplitMulticolorEnabled == enabled) {
            return;
        }
        this.shuffleSplitMulticolorEnabled = enabled;
        postInvalidateOnAnimation();
    }

    private int generateVividColorBySeed(long seed) {
        // 用稳定噪声生成 HSV：高饱和 + 高亮度，且跨帧可控切换
        float hue = (stableFloat(seed, 1f) + 1f) * 0.5f * 360f;
        float saturation = 0.85f + (stableFloat(seed + 11L, 1f) + 1f) * 0.5f * 0.15f;
        float value = 0.85f + (stableFloat(seed + 23L, 1f) + 1f) * 0.5f * 0.15f;
        saturation = Math.max(0.0f, Math.min(1.0f, saturation));
        value = Math.max(0.0f, Math.min(1.0f, value));
        return Color.HSVToColor(new float[]{hue, saturation, value}) | 0xFF000000;
    }

    /**
     * 启动缩放动画（微位移+缩放效果）
     * V3.17: 背屏优化 - 0.5倍速缩放动画，从0.98到1.1
     */
    private void startScaleAnimation() {
        startScaleAnimation(resolveCurrentBreathingPhase());
    }

    private void startScaleAnimation(float initialPhase) {
        if (powerSavingModeEnabled) {
            currentScale = 1f;
            currentDisplacement = 0f;
            return;
        }
        if (Math.abs(breathingScaleMax - breathingScaleMin) < 0.0001f) {
            currentScale = 1f;
            currentDisplacement = 0f;
            return;
        }
        if (scaleAnimator != null) {
            scaleAnimator.cancel();
            scaleAnimator = null;
        }
        float startScale = evaluateBreathingScaleByPhase(initialPhase);
        currentScale = Math.max(breathingScaleMin, Math.min(breathingScaleMax, startScale));
        currentDisplacement = (currentScale - 1.0f) * DISPLACEMENT_OFFSET * 2f * breathingDisplacementStrength;

        // 线性时间推进 + 手动正弦缓入缓出曲线，确保放大/缩小速率严格对称。
        scaleAnimator = ValueAnimator.ofFloat(0f, 1f);
        scaleAnimator.setDuration(currentScaleAnimationDuration);
        scaleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scaleAnimator.setInterpolator(BREATHING_PHASE_INTERPOLATOR);
        scaleAnimator.addUpdateListener(animation -> {
            float phase = (float) animation.getAnimatedValue();
            currentScale = evaluateBreathingScaleByPhase(phase);
            // 同时更新微位移（轻微向上位移2-3px）
            currentDisplacement = (currentScale - 1.0f) * DISPLACEMENT_OFFSET * 2f * breathingDisplacementStrength;
            if (shouldInvalidateForCurrentPlaybackState()) {
                postInvalidateOnAnimation();
            }
        });
        scaleAnimator.start();
        if (initialPhase > 0f) {
            long playTime = (long) (currentScaleAnimationDuration * Math.max(0f, Math.min(1f, initialPhase)));
            scaleAnimator.setCurrentPlayTime(playTime);
        }
    }
    
    /**
     * 更新呼吸动画速度（根据音乐节奏）
     * 关联音谱，动态调整动画时长以匹配音乐节奏
     */
    private void updateScaleAnimationSpeed() {
        if (powerSavingModeEnabled) {
            return;
        }
        if (scaleAnimator == null || !scaleAnimator.isRunning()) {
            return;
        }
        restartScaleAnimationKeepingPhase();
    }

    private void restartScaleAnimationKeepingPhase() {
        if (powerSavingModeEnabled) {
            return;
        }
        startScaleAnimation(resolveCurrentBreathingPhase());
    }

    private float resolveCurrentBreathingPhase() {
        if (scaleAnimator == null || !scaleAnimator.isRunning()) {
            return 0f;
        }
        long duration = Math.max(1L, currentScaleAnimationDuration);
        long playTime = scaleAnimator.getCurrentPlayTime();
        long normalized = playTime % duration;
        return normalized / (float) duration;
    }

    private float evaluateBreathingScaleByPhase(float phase) {
        float clampedPhase = Math.max(0f, Math.min(1f, phase));
        float hold = Math.max(0f, Math.min(0.2f, BREATHING_PEAK_HOLD_FRACTION));
        float halfHold = hold * 0.5f;
        float riseEnd = 0.5f - halfHold;
        float fallStart = 0.5f + halfHold;
        float envelope;
        if (clampedPhase <= riseEnd) {
            float t = riseEnd <= 0f ? 1f : (clampedPhase / riseEnd);
            envelope = easeInOutSine01(t);
        } else if (clampedPhase < fallStart) {
            envelope = 1f;
        } else {
            float span = Math.max(0.0001f, 1f - fallStart);
            float t = (clampedPhase - fallStart) / span;
            envelope = 1f - easeInOutSine01(t);
        }
        return breathingScaleMin + (breathingScaleMax - breathingScaleMin) * envelope;
    }

    private float easeInOutSine01(float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        return (float) (0.5d * (1d - Math.cos(Math.PI * clamped)));
    }
    
    public void setLyricsHorizontalInsetPx(int px) {
        int v = Math.max(0, px);
        if (v == lyricsHorizontalInsetPx) {
            return;
        }
        lyricsHorizontalInsetPx = v;
        invalidate();
    }

    /** 歌词可绘制的左边界（含边距）。 */
    private float getLyricContentLeftBound() {
        return lyricsHorizontalInsetPx + HORIZONTAL_TEXT_MARGIN;
    }

    /** 歌词可绘制的右边界（含边距）。 */
    private float getLyricContentRightBound() {
        return getWidth() > 0 ? (getWidth() - HORIZONTAL_TEXT_MARGIN) : HORIZONTAL_TEXT_MARGIN;
    }

    /** 歌词在可绘区水平居中（不再使用额外平移 offset）。 */
    private float getLyricsDrawCenterX() {
        if (getWidth() <= 0) {
            return 0f;
        }
        float left = getLyricContentLeftBound();
        float right = getLyricContentRightBound();
        if (right <= left + 1f) {
            return getWidth() / 2f;
        }
        return (left + right) * 0.5f;
    }

    /**
     * 折行用的最大文本宽度：可绘区宽度减去左右边距。
     */
    private float getAvailableTextWidth() {
        if (getWidth() <= 0) {
            return 1f;
        }
        float inner = getLyricContentRightBound() - getLyricContentLeftBound();
        return Math.max(1f, inner);
    }

    private StaticLayout buildWrappedStaticLayout(TextPaint paint, String text, float maxWidth) {
        int layoutWidth = Math.max(1, Math.round(maxWidth));
        StaticLayout.Builder builder = StaticLayout.Builder
            .obtain(text, 0, text.length(), paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return builder.build();
    }

    /** 无空格长句（如中文）在 StaticLayout 仍单行时，按字形宽度逐字折行。 */
    private void wrapTextByCharacterWidth(TextPaint paint, String text, float maxWidth) {
        wrappedLineBuffer.clear();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            String candidate = current.toString() + ch;
            if (current.length() > 0 && paint.measureText(candidate) > maxWidth) {
                wrappedLineBuffer.add(current.toString());
                current = new StringBuilder(ch);
            } else {
                current.append(ch);
            }
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            wrappedLineBuffer.add(current.toString());
        }
    }

    /**
     * 将单行歌词按可用宽度拆成多行（背屏超宽句自动换行，不截断）。
     */
    private List<String> wrapTextLines(TextPaint paint, String text) {
        return wrapTextLines(paint, text, getLyricsDrawCenterX());
    }

    private List<String> wrapTextLines(TextPaint paint, String text, float centerX) {
        wrappedLineBuffer.clear();
        if (text == null || text.isEmpty()) {
            return wrappedLineBuffer;
        }
        if (getWidth() <= 0) {
            wrappedLineBuffer.add(text);
            return wrappedLineBuffer;
        }
        float maxWidth = getAvailableTextWidth();
        if (paint.measureText(text) <= maxWidth) {
            wrappedLineBuffer.add(text);
            return wrappedLineBuffer;
        }
        StaticLayout layout = buildWrappedStaticLayout(paint, text, maxWidth);
        for (int i = 0; i < layout.getLineCount(); i++) {
            int start = layout.getLineStart(i);
            int end = layout.getLineEnd(i);
            while (end > start && text.charAt(end - 1) == '\n') {
                end--;
            }
            if (end <= start) {
                continue;
            }
            wrappedLineBuffer.add(text.substring(start, end));
        }
        // 仅当 StaticLayout 仍折不出多行、且整句确实超宽时，才按字宽兜底（避免误触逐字成行）
        if (wrappedLineBuffer.size() <= 1
                && layout.getLineCount() <= 1
                && paint.measureText(text) > maxWidth) {
            wrapTextByCharacterWidth(paint, text, maxWidth);
        }
        if (wrappedLineBuffer.isEmpty()) {
            wrappedLineBuffer.add(text);
        }
        return wrappedLineBuffer;
    }

    private float getLineHeightPx(TextPaint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return fm.descent - fm.ascent;
    }

    private float getTextBlockHeight(TextPaint paint, int wrappedLineCount) {
        if (wrappedLineCount <= 0) {
            return 0f;
        }
        float lineH = getLineHeightPx(paint);
        return wrappedLineCount * lineH + (wrappedLineCount - 1) * WRAPPED_SUBLINE_GAP;
    }

    private float getWrappedBlockHalfHeight(TextPaint paint, String text) {
        return getTextBlockHeight(paint, wrapTextLines(paint, text).size()) / 2f;
    }

    private float getWrappedLineCenterY(TextPaint paint, float blockCenterY, int lineIndex, int lineCount) {
        float blockH = getTextBlockHeight(paint, lineCount);
        float lineH = getLineHeightPx(paint);
        float firstLineCenter = blockCenterY - blockH / 2f + lineH / 2f;
        return firstLineCenter + lineIndex * (lineH + WRAPPED_SUBLINE_GAP);
    }

    private float clampCenterXForTextWidth(float centerX, float textWidth) {
        float maxRight = getLyricContentRightBound();
        float minLeft = getLyricContentLeftBound();
        float adjustedCenterX = centerX;
        if (centerX + textWidth / 2f > maxRight) {
            adjustedCenterX = maxRight - textWidth / 2f;
        }
        if (centerX - textWidth / 2f < minLeft) {
            adjustedCenterX = minLeft + textWidth / 2f;
        }
        return adjustedCenterX;
    }

    private float mapWrappedLineProgress(float globalProgress, int charOffset, int lineLength, int totalChars) {
        if (totalChars <= 0 || lineLength <= 0) {
            return globalProgress >= 1f ? 1f : 0f;
        }
        float start = (float) charOffset / (float) totalChars;
        float end = (float) (charOffset + lineLength) / (float) totalChars;
        if (globalProgress <= start) {
            return 0f;
        }
        if (globalProgress >= end) {
            return 1f;
        }
        return (globalProgress - start) / Math.max(0.0001f, end - start);
    }

    /**
     * 逐字高亮绘制（使用渐变效果，基于小张桌面的实现）
     * V3.17: 背屏优化 - 添加渐变色和描边效果，确保文本不超出显示范围
     */
    private void drawWordByWord(Canvas canvas, EnhancedLRCParser.EnhancedLyricLine line, float centerX, float blockCenterY) {
        String text = line.text != null ? line.text : "";
        List<String> wrappedLines = wrapTextLines(currentPaint, text);
        int lineCount = wrappedLines.size();
        float progress = resolveLineProgress(line);
        int totalChars = Math.max(1, text.length());
        int charOffset = 0;
        for (int i = 0; i < lineCount; i++) {
            String displayText = wrappedLines.get(i);
            float lineCenterY = getWrappedLineCenterY(currentPaint, blockCenterY, i, lineCount);
            float baseline = verticalCenterToBaseline(currentPaint, lineCenterY);
            float textWidth = currentPaint.measureText(displayText);
            float adjustedCenterX = clampCenterXForTextWidth(centerX, textWidth);
            float lineProgress = mapWrappedLineProgress(progress, charOffset, displayText.length(), totalChars);
            charOffset += displayText.length();

            float startX = adjustedCenterX - textWidth / 2f;
            float endX = adjustedCenterX + textWidth / 2f;

            if (lineProgress > 0f && lineProgress < 1f) {
                int normalColor = getNormalLyricsColor();
                int singingColor = currentTextColor;
                float featherFraction = WORD_BY_WORD_FEATHER_PX / Math.max(1f, textWidth);
                featherFraction = Math.max(WORD_BY_WORD_FEATHER_MIN_FRACTION,
                    Math.min(WORD_BY_WORD_FEATHER_MAX_FRACTION, featherFraction));
                float featherEnd = Math.min(1f, lineProgress + featherFraction);
                float nearFeather = Math.min(featherEnd, lineProgress + featherFraction * 0.35f);
                if (nearFeather <= lineProgress) {
                    nearFeather = featherEnd;
                }
                int nearUpcomingColor = blendColor(singingColor, normalColor, 0.35f);
                int nearAlphaFloor = Math.max(Color.alpha(normalColor), (int) (0.82f * 255f));
                nearUpcomingColor = withAlpha(nearUpcomingColor, nearAlphaFloor);

                LinearGradient progressGradient = new LinearGradient(
                    startX, 0, endX, 0,
                    new int[]{singingColor, singingColor, nearUpcomingColor, normalColor, normalColor},
                    new float[]{0f, lineProgress, nearFeather, featherEnd, 1f},
                    Shader.TileMode.CLAMP
                );

                TextPaint gradientPaint = new TextPaint(currentPaint);
                gradientPaint.setShader(progressGradient);
                drawNeonTextGlow(canvas, displayText, adjustedCenterX, baseline, singingColor, gradientPaint);
                outlinePaint.setTextSize(currentPaint.getTextSize());
                outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
                canvas.drawText(displayText, adjustedCenterX, baseline, outlinePaint);
                canvas.drawText(displayText, adjustedCenterX, baseline, gradientPaint);
            } else if (lineProgress >= 1f) {
                TextPaint colorPaint = new TextPaint(currentPaint);
                colorPaint.setColor(currentTextColor);
                drawNeonTextGlow(canvas, displayText, adjustedCenterX, baseline, currentTextColor, colorPaint);
                outlinePaint.setTextSize(currentPaint.getTextSize());
                outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
                canvas.drawText(displayText, adjustedCenterX, baseline, outlinePaint);
                canvas.drawText(displayText, adjustedCenterX, baseline, colorPaint);
            } else {
                TextPaint idlePaint = new TextPaint(currentPaint);
                idlePaint.setColor(getNormalLyricsColor());
                idlePaint.setShader(null);
                drawNeonTextGlow(canvas, displayText, adjustedCenterX, baseline, getNormalLyricsColor(), idlePaint);
                canvas.drawText(displayText, adjustedCenterX, baseline, idlePaint);
            }
        }
    }
    
    /**
     * 带进度的行绘制（渐变效果，基于小张桌面的实现）
     * V3.17: 确保文本不超出显示范围
     */
    private void drawLineWithProgress(Canvas canvas, String text, float centerX, float blockCenterY, float progress) {
        if (text == null) {
            text = "";
        }
        List<String> wrappedLines = wrapTextLines(currentPaint, text);
        int lineCount = wrappedLines.size();
        int totalChars = Math.max(1, text.length());
        int charOffset = 0;
        for (int i = 0; i < lineCount; i++) {
            String displayText = wrappedLines.get(i);
            float lineCenterY = getWrappedLineCenterY(currentPaint, blockCenterY, i, lineCount);
            float baseline = verticalCenterToBaseline(currentPaint, lineCenterY);
            float textWidth = currentPaint.measureText(displayText);
            float adjustedCenterX = clampCenterXForTextWidth(centerX, textWidth);
            float lineProgress = mapWrappedLineProgress(progress, charOffset, displayText.length(), totalChars);
            charOffset += displayText.length();

            float startX = adjustedCenterX - textWidth / 2f;
            float endX = adjustedCenterX + textWidth / 2f;

            if (lineProgress > 0f && lineProgress < 1f) {
                int normalColor = getNormalLyricsColor();
                LinearGradient progressGradient = new LinearGradient(
                    startX, 0, endX, 0,
                    new int[]{currentTextColor, currentTextColor, normalColor, normalColor},
                    new float[]{0f, lineProgress, lineProgress, 1.0f},
                    Shader.TileMode.CLAMP
                );

                TextPaint gradientPaint = new TextPaint(currentPaint);
                gradientPaint.setShader(progressGradient);
                drawNeonTextGlow(canvas, displayText, adjustedCenterX, baseline, currentTextColor, gradientPaint);
                outlinePaint.setTextSize(currentPaint.getTextSize());
                outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
                canvas.drawText(displayText, adjustedCenterX, baseline, outlinePaint);
                canvas.drawText(displayText, adjustedCenterX, baseline, gradientPaint);
            } else if (lineProgress >= 1f) {
                TextPaint colorPaint = new TextPaint(currentPaint);
                colorPaint.setColor(currentTextColor);
                drawNeonTextGlow(canvas, displayText, adjustedCenterX, baseline, currentTextColor, colorPaint);
                outlinePaint.setTextSize(currentPaint.getTextSize());
                outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
                canvas.drawText(displayText, adjustedCenterX, baseline, outlinePaint);
                canvas.drawText(displayText, adjustedCenterX, baseline, colorPaint);
            } else {
                TextPaint idlePaint = new TextPaint(currentPaint);
                idlePaint.setColor(getNormalLyricsColor());
                idlePaint.setShader(null);
                drawNeonTextGlow(canvas, displayText, adjustedCenterX, baseline, getNormalLyricsColor(), idlePaint);
                canvas.drawText(displayText, adjustedCenterX, baseline, idlePaint);
            }
        }
    }

    private void drawNeonTextGlow(Canvas canvas, String text, float x, float y, int baseColor, Paint referencePaint) {
        return;
    }
    
    /**
     * 绘制普通行
     * @param lineCenterY 该行主歌词垂直中心
     */
    private void drawNormalLine(Canvas canvas, EnhancedLRCParser.EnhancedLyricLine line, 
                               float centerX, float lineCenterY, float alpha) {
        TextPaint paint = alpha > 0.5f ? normalPaint : dimPaint;
        
        // 设置透明度
        int alphaInt = (int)(alpha * 255);
        paint.setAlpha(alphaInt);
        
        String text = line.text != null ? line.text : "";
        List<String> wrappedLines = wrapTextLines(paint, text);
        int lineCount = wrappedLines.size();
        for (int i = 0; i < lineCount; i++) {
            String displayText = wrappedLines.get(i);
            float rowCenterY = getWrappedLineCenterY(paint, lineCenterY, i, lineCount);
            float baseline = verticalCenterToBaseline(paint, rowCenterY);
            float textWidth = paint.measureText(displayText);
            float adjustedCenterX = clampCenterXForTextWidth(centerX, textWidth);
            canvas.drawText(displayText, adjustedCenterX, baseline, paint);
        }
        
        // 恢复透明度
        paint.setAlpha(255);
    }
    
    /**
     * 翻译折行并限制最多 {@link #MAX_TRANSLATION_WRAP_LINES} 行，超出部分末行省略号截断。
     */
    private List<String> wrapTranslationLines(String translation) {
        List<String> lines = wrapTextLines(translationPaint, translation);
        if (lines.size() <= MAX_TRANSLATION_WRAP_LINES) {
            return lines;
        }
        wrappedLineBuffer.clear();
        for (int i = 0; i < MAX_TRANSLATION_WRAP_LINES - 1; i++) {
            wrappedLineBuffer.add(lines.get(i));
        }
        StringBuilder rest = new StringBuilder();
        for (int i = MAX_TRANSLATION_WRAP_LINES - 1; i < lines.size(); i++) {
            rest.append(lines.get(i));
        }
        wrappedLineBuffer.add(ellipsizeTranslationLine(rest.toString()));
        return wrappedLineBuffer;
    }

    private String ellipsizeTranslationLine(String text) {
        if (text == null || text.isEmpty()) {
            return TRANSLATION_ELLIPSIS;
        }
        float maxWidth = getAvailableTextWidth();
        if (translationPaint.measureText(text) <= maxWidth) {
            return text;
        }
        for (int len = text.length(); len > 0; len--) {
            String candidate = text.substring(0, len) + TRANSLATION_ELLIPSIS;
            if (translationPaint.measureText(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return TRANSLATION_ELLIPSIS;
    }

    /**
     * 绘制翻译
     * V3.17: 确保文本不超出显示范围
     */
    private void drawTranslation(Canvas canvas, String translation, float centerX, float blockCenterY, float alpha) {
        int alphaInt = (int)(alpha * 255);
        translationPaint.setAlpha(alphaInt);
        
        List<String> wrappedLines = wrapTranslationLines(translation);
        int lineCount = wrappedLines.size();
        for (int i = 0; i < lineCount; i++) {
            String displayText = wrappedLines.get(i);
            float rowCenterY = getWrappedLineCenterY(translationPaint, blockCenterY, i, lineCount);
            float baseline = verticalCenterToBaseline(translationPaint, rowCenterY);
            float textWidth = translationPaint.measureText(displayText);
            float adjustedCenterX = clampCenterXForTextWidth(centerX, textWidth);
            canvas.drawText(displayText, adjustedCenterX, baseline, translationPaint);
        }
        
        translationPaint.setAlpha(255);
    }
    
    /**
     * 绘制进度条
     * V3.17: 背屏优化 - 细进度条，与当前歌词高亮色一致
     */
    private void drawProgressBar(Canvas canvas, float centerX, float y, float progress) {
        float barWidth = getWidth() * 0.5f;  // 稍微缩小宽度
        float barHeight = 2f;  // 更细的进度条
        float startX = centerX - barWidth / 2f;
        
        // 绘制背景（适配黑色背景）
        progressPaint.setColor(0x33FFFFFF);
        canvas.drawRoundRect(startX, y, startX + barWidth, y + barHeight, 
                           barHeight / 2f, barHeight / 2f, progressPaint);
        
        // V3.17: 绘制进度（使用随机颜色）
        if (progress > 0f) {
            LinearGradient progressGradient = new LinearGradient(
                startX, y, startX + barWidth * progress, y,
                new int[]{currentTextColor, currentTextColor},
                new float[]{0f, 1.0f},
                Shader.TileMode.CLAMP
            );
            progressPaint.setShader(progressGradient);
            float progressWidth = barWidth * progress;
            canvas.drawRoundRect(startX, y, startX + progressWidth, y + barHeight,
                               barHeight / 2f, barHeight / 2f, progressPaint);
            progressPaint.setShader(null);  // 清除shader，避免影响其他绘制
        }
    }
    
    /**
     * 计算当前行的播放进度
     */
    private float calculateLineProgress(EnhancedLRCParser.EnhancedLyricLine line) {
        if (currentPosition < line.time) {
            return 0f;
        }
        
        // 查找当前行在列表中的索引
        int lineIndex = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i) == line || 
                (lyricLines.get(i).time == line.time && lyricLines.get(i).text.equals(line.text))) {
                lineIndex = i;
                break;
            }
        }
        
        // 获取下一行的时间
        long nextTime = Long.MAX_VALUE;
        if (lineIndex >= 0 && lineIndex < lyricLines.size() - 1) {
            nextTime = lyricLines.get(lineIndex + 1).time;
        }
        
        if (nextTime == Long.MAX_VALUE || nextTime <= line.time) {
            nextTime = line.time + 3000; // 默认3秒
        }
        
        long duration = nextTime - line.time;
        if (duration <= 0) {
            return 1f;
        }
        
        long elapsed = currentPosition - line.time;
        float progress = (float) elapsed / (float) duration;
        
        // V3.16: 已移除提前进度逻辑，确保与音乐播放时间完全同步
        
        return Math.max(0f, Math.min(1f, progress));
    }

    private float resolveLineProgress(EnhancedLRCParser.EnhancedLyricLine line) {
        return advanceWordHighlightDisplay(computeLineProgressTarget(line), line);
    }

    /**
     * 将整曲播放位置换算为当前行逐字轴坐标：句内相对轴用 {@code position - line.time}，整曲绝对轴保持原值。
     */
    private long resolveEffectivePlaybackMs(EnhancedLRCParser.EnhancedLyricLine line, long positionMs) {
        if (line == null) {
            return Math.max(0L, positionMs);
        }
        // 智能切换融合：逐字轴已是整曲绝对毫秒，不得再减 line.time
        if (hasModuleWordHighlightTimeline(line)) {
            return Math.max(0L, positionMs);
        }
        if (hasActiveWordTimestamps(line)
            && SuperLyricWordTimestamps.usesSentenceRelativeTimeline(
                line.wordTimestamps, line.time)) {
            return Math.max(0L, positionMs - Math.max(0L, line.time));
        }
        return Math.max(0L, positionMs);
    }

    private boolean isSuperLyricSingleLineMode() {
        return lyricLines.size() == 1 && hasActiveWordTimestamps(lyricLines.get(0));
    }

    /** 逐字高亮用权威播放位置（不用插值中的 currentPosition，避免追赶/回弹导致进度来回跳）。 */
    private long wordProgressPositionMs(EnhancedLRCParser.EnhancedLyricLine line) {
        return resolveEffectivePlaybackMs(line, targetPlaybackPosition);
    }

    /**
     * 计算当前行高亮目标进度 [0,1]：
     * 1. 模块逐字轴（绝对毫秒）→ computeFusedWordHighlightTarget，按字时长加权
     * 2. 模块逐字轴（句内相对）/ LRC 时间戳 → computeLegacyWordTimestampProgress，按字计数等分
     * 3. 无逐字数据 → calculateLineProgress，按行时间区间平滑过渡
     */
    private float computeLineProgressTarget(EnhancedLRCParser.EnhancedLyricLine line) {
        if (line == null) {
            return 0f;
        }
        // Kuwo broadcast direct word hint: overrides time-based progress for pixel-accurate highlighting.
        // Only requires the hint itself and the target line — wordTimestamps on the line are not needed
        // because the hint carries character positions (wordCharStart/wordCharEnd) directly.
        if (mKuwoWordHintValid && line != null) {
            final long kuwoHintStaleMs = 800L; // broadcast fires ~100ms; 8× tolerance for reliability
            if (android.os.SystemClock.uptimeMillis() - mKuwoWordHintTimestamp < kuwoHintStaleMs) {
                int idx = lyricLines.indexOf(line);
                if (idx >= 0 && idx == mKuwoWordHintLineIndex) {
                    String text = line.text != null ? line.text : "";
                    if (text.length() > 0) {
                        // Position highlight at end of current word character.
                        // wordCharEnd is inclusive; +1 makes it the first un-sung char position.
                        float charProgress = (float)(mKuwoWordHintCharEnd + 1) / (float)text.length();
                        return Math.max(0f, Math.min(1f, charProgress));
                    }
                }
            } else {
                mKuwoWordHintValid = false; // auto-expire stale hint
            }
        }
        if (hasActiveWordTimestamps(line)) {
            if (hasModuleWordHighlightTimeline(line)) {
                float wordTarget = computeFusedWordHighlightTarget(
                    line.wordTimestamps,
                    wordProgressPositionMs(line),
                    line.text
                );
                if (wordTarget >= 0f) {
                    return Math.max(0f, Math.min(1f, wordTarget));
                }
            }
            long progressClockMs = isSuperLyricSingleLineMode()
                ? currentPosition
                : wordProgressPositionMs(line);
            return Math.max(0f, Math.min(1f, computeLegacyWordTimestampProgress(
                line.wordTimestamps, line.time, progressClockMs)));
        }
        return Math.max(0f, Math.min(1f, calculateLineProgress(line)));
    }

    /** 启用逐字且当前行带时间戳（含 SuperLyric 单句兜底）。 */
    private boolean hasActiveWordTimestamps(EnhancedLRCParser.EnhancedLyricLine line) {
        return enableWordByWord
            && line != null
            && line.wordTimestamps != null
            && !line.wordTimestamps.isEmpty();
    }

    /** 仅当该行逐字时间轴明确来自 SuperLyric 模块时才走句内相对轴换算。 */
    private static boolean hasModuleWordHighlightTimeline(EnhancedLRCParser.EnhancedLyricLine line) {
        return line != null
            && line.moduleWordTimeline
            && line.wordTimestamps != null
            && !line.wordTimestamps.isEmpty();
    }

    /**
     * 按绝对毫秒逐字轴计算已唱比例（单句 SuperLyric {@link SuperLyricWordTimestamps#alignToLineTime} 等路径）。
     */
    private static float computeLegacyWordTimestampProgress(List<EnhancedLRCParser.WordTimestamp> wordTimestamps,
                                                            long lineTimeMs,
                                                            long positionMs) {
        if (wordTimestamps == null || wordTimestamps.isEmpty()) {
            return 0f;
        }
        ArrayList<long[]> words = new ArrayList<>();
        for (EnhancedLRCParser.WordTimestamp word : wordTimestamps) {
            if (word == null) {
                continue;
            }
            long start = Math.max(0L, word.startTime);
            long end = Math.max(start, word.endTime);
            if (end == start) {
                end = start + WORD_TIMESTAMP_FALLBACK_DURATION_MS;
            }
            if (end > 0L) {
                words.add(new long[]{start, end});
            }
        }
        if (words.isEmpty()) {
            long fallbackStart = Math.max(0L, lineTimeMs);
            long fallbackEnd = fallbackStart + 5000L;
            if (positionMs <= fallbackStart) {
                return 0f;
            }
            if (positionMs >= fallbackEnd) {
                return 1f;
            }
            return Math.max(0f, Math.min(1f, (float) (positionMs - fallbackStart) / (float) (fallbackEnd - fallbackStart)));
        }

        long firstStart = words.get(0)[0];
        long lastEnd = Math.max(firstStart + WORD_TIMESTAMP_FALLBACK_DURATION_MS, words.get(words.size() - 1)[1]);
        if (positionMs <= firstStart) {
            return 0f;
        }
        if (positionMs >= lastEnd) {
            return 1f;
        }

        float wordCount = (float) words.size();
        long prevEnd = firstStart;
        for (int i = 0; i < words.size(); i++) {
            long start = Math.max(firstStart, words.get(i)[0]);
            long end = Math.max(start + 1L, words.get(i)[1]);
            if (positionMs < start && i > 0) {
                long gap = Math.max(1L, start - prevEnd);
                float gapProgress = Math.max(0f, Math.min(1f, (float) (positionMs - prevEnd) / (float) gap));
                return Math.max(0f, Math.min(1f, (i - 1 + gapProgress) / wordCount));
            }
            if (positionMs >= start && positionMs < end) {
                float inWord = Math.max(0f, Math.min(1f, (float) (positionMs - start) / (float) (end - start)));
                return Math.max(0f, Math.min(1f, (i + inWord) / wordCount));
            }
            prevEnd = end;
        }
        return 1f;
    }

    /**
     * 按逐字时间轴与播放位置计算已唱比例；与 {@link SuperLyricWordTimestamps#mapToLineText} 一字一戳一致时按字加权。
     *
     * @return &lt;0 表示无法计算
     */
    private float computeFusedWordHighlightTarget(List<EnhancedLRCParser.WordTimestamp> wordTimestamps,
                                                  long positionMs,
                                                  String lineText) {
        if (wordTimestamps == null || wordTimestamps.isEmpty()) {
            return -1f;
        }
        boolean perCharTimeline = lineText != null
            && lineText.length() == wordTimestamps.size()
            && !lineText.isEmpty();

        // V3.17+: 先遍历全部字计算总权重，再算已唱权重。之前 totalWeight 在循环中累加
        // 但 break 时后续未唱字未被计入分母，导致分母过小、进度虚高（首字唱到一半就显示 80%）。
        float totalWeight = 0f;
        for (int i = 0; i < wordTimestamps.size(); i++) {
            EnhancedLRCParser.WordTimestamp word = wordTimestamps.get(i);
            if (word == null) continue;
            totalWeight += resolveWordHighlightWeight(word, perCharTimeline ? lineText : null, i);
        }
        if (totalWeight <= 0f) {
            return -1f;
        }

        float sungWeight = 0f;
        long timelineStart = Long.MAX_VALUE;
        long timelineEnd = 0L;

        for (int i = 0; i < wordTimestamps.size(); i++) {
            EnhancedLRCParser.WordTimestamp word = wordTimestamps.get(i);
            if (word == null) {
                continue;
            }
            long start = Math.max(0L, word.startTime);
            long end = Math.max(start + 1L, word.endTime);
            timelineStart = Math.min(timelineStart, start);
            timelineEnd = Math.max(timelineEnd, end);

            if (positionMs >= end) {
                sungWeight += resolveWordHighlightWeight(word, perCharTimeline ? lineText : null, i);
            } else if (positionMs > start) {
                float inWord = (float) (positionMs - start) / (float) (end - start);
                sungWeight += resolveWordHighlightWeight(word, perCharTimeline ? lineText : null, i)
                    * Math.max(0f, Math.min(1f, inWord));
                break;
            } else {
                break;
            }
        }

        if (totalWeight <= 0f || timelineStart == Long.MAX_VALUE) {
            return -1f;
        }
        if (positionMs <= timelineStart) {
            return 0f;
        }
        if (positionMs >= timelineEnd) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, sungWeight / totalWeight));
    }

    private float resolveWordHighlightWeight(EnhancedLRCParser.WordTimestamp word,
                                             String lineText,
                                             int charIndex) {
        if (lineText != null && charIndex >= 0 && charIndex < lineText.length()) {
            char ch = lineText.charAt(charIndex);
            return SuperLyricWordTimestamps.isIgnorableLineChar(ch) ? 0.35f : 1f;
        }
        if (word == null || word.word == null || word.word.isEmpty()) {
            return 1f;
        }
        if (currentPaint == null) {
            return Math.max(1f, word.word.length());
        }
        return Math.max(1f, currentPaint.measureText(word.word));
    }

    private static String wordProgressLineKey(EnhancedLRCParser.EnhancedLyricLine line) {
        if (line == null) {
            return "";
        }
        return line.time + "|" + (line.text == null ? "" : line.text);
    }

    /**
     * 将显示进度逼近目标：SuperLyric 模块轴在同一句内只前进不回退；普通 LRC 行推算仍允许小幅回退纠错。
     */
    private float advanceWordHighlightDisplay(float target, EnhancedLRCParser.EnhancedLyricLine line) {
        float clamped = Math.max(0f, Math.min(1f, target));
        String key = wordProgressLineKey(line);
        long now = SystemClock.uptimeMillis();

        // Kuwo broadcast direct hint: accept progress immediately (monotonic-only guard)
        if (mKuwoWordHintValid && line != null) {
            int idx = lyricLines.indexOf(line);
            if (idx >= 0 && idx == mKuwoWordHintLineIndex) {
                if (clamped >= lastWordProgressValue || !key.equals(lastWordProgressLineKey)) {
                    lastWordProgressLineKey = key;
                    lastWordProgressValue = clamped;
                }
                lastWordProgressSampleUptimeMs = now;
                return lastWordProgressValue;
            }
        }

        if (!key.equals(lastWordProgressLineKey)) {
            lastWordProgressLineKey = key;
            lastWordProgressRevision = wordTimestampsRevision;
            lastWordProgressValue = clamped;
            lastWordProgressSampleUptimeMs = now;
            return clamped;
        }

        boolean moduleTimeline = hasModuleWordHighlightTimeline(line);
        boolean legacyWordTimeline = hasActiveWordTimestamps(line) && !moduleTimeline;
        boolean singleLineRelative = moduleTimeline
            && lyricLines.size() == 1
            && SuperLyricWordTimestamps.usesSentenceRelativeTimeline(
                line.wordTimestamps, line.time);

        if (moduleTimeline || legacyWordTimeline) {
            if (wordTimestampsRevision != lastWordProgressRevision) {
                lastWordProgressRevision = wordTimestampsRevision;
                if (clamped + 0.12f < lastWordProgressValue) {
                    lastWordProgressValue = clamped;
                }
            }
            if (singleLineRelative) {
                if (clamped > lastWordProgressValue) {
                    lastWordProgressValue = clamped;
                }
                lastWordProgressSampleUptimeMs = now;
                return lastWordProgressValue;
            }
            if (clamped >= 0.999f) {
                lastWordProgressValue = 1f;
                lastWordProgressSampleUptimeMs = now;
                return 1f;
            }
            long dt = Math.max(1L, now - lastWordProgressSampleUptimeMs);
            float maxStep = Math.min(
                WORD_PROGRESS_MAX_STEP_PER_FRAME,
                Math.max(0.016f, dt * WORD_PROGRESS_MAX_STEP_PER_MS)
            );
            if (clamped > lastWordProgressValue) {
                lastWordProgressValue = Math.min(clamped, lastWordProgressValue + maxStep);
            }
            lastWordProgressSampleUptimeMs = now;
            return lastWordProgressValue;
        }

        if (wordTimestampsRevision != lastWordProgressRevision) {
            lastWordProgressRevision = wordTimestampsRevision;
            if (clamped < lastWordProgressValue) {
                lastWordProgressValue = clamped;
            } else {
                clamped = Math.min(clamped, lastWordProgressValue + WORD_PROGRESS_MAX_STEP_PER_FRAME);
            }
        }

        if (clamped >= 0.999f) {
            lastWordProgressValue = 1f;
            lastWordProgressSampleUptimeMs = now;
            return 1f;
        }

        long dt = Math.max(1L, now - lastWordProgressSampleUptimeMs);
        float maxStep = Math.min(
            WORD_PROGRESS_MAX_STEP_PER_FRAME,
            Math.max(0.012f, dt * WORD_PROGRESS_MAX_STEP_PER_MS)
        );

        float next;
        if (clamped > lastWordProgressValue) {
            next = Math.min(clamped, lastWordProgressValue + maxStep);
        } else if (clamped + 0.02f < lastWordProgressValue) {
            next = Math.max(clamped, lastWordProgressValue - maxStep * 0.55f);
        } else {
            next = lastWordProgressValue;
        }
        lastWordProgressValue = next;
        lastWordProgressSampleUptimeMs = now;
        return next;
    }
    
    /**
     * 平滑滚动动画
     */
    private void animateScroll(float from, float to) {
        animateScroll(from, to, SCROLL_ANIMATION_DURATION);
    }

    private void animateScroll(float from, float to, long durationMs) {
        if (trackLoading) {
            return;
        }
        float delta = Math.abs(to - from);
        if (delta < SCROLL_ANIMATION_MIN_DELTA_PX) {
            scrollY = to;
            return;
        }
        long now = System.currentTimeMillis();
        if (!Float.isNaN(lastScrollAnimateTargetY)
            && Math.abs(lastScrollAnimateTargetY - to) < SCROLL_ANIMATION_MIN_DELTA_PX
            && (now - lastScrollAnimateStartMs) < SCROLL_ANIMATION_THROTTLE_MS) {
            return;
        }
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }

        scrollAnimator = ValueAnimator.ofFloat(from, to);
        scrollAnimator.setDuration(Math.max(120L, durationMs));
        scrollAnimator.setInterpolator(new DecelerateInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollY = (float) animation.getAnimatedValue();
            postInvalidate();  // 使用 postInvalidate 确保在任何线程都能触发重绘
        });
        scrollAnimator.start();
        lastScrollAnimateStartMs = now;
        lastScrollAnimateTargetY = to;
        if (BuildConfig.DEBUG) {
            LogHelper.d(TAG, "✅ 滚动动画已启动: " + from + " -> " + to);
        }
    }
    
    /**
     * 根据Y坐标获取行索引
     */
    private int getLineIndexFromY(float y) {
        float centerY = getLyricsBandVerticalCenterY();
        float relativeY = y - centerY + scrollY;
        int index = Math.round(relativeY / LINE_SPACING);
        return Math.max(0, Math.min(index, lyricLines.size() - 1));
    }
    
    /**
     * 颜色插值
     */
    private int interpolateColor(int colorStart, int colorEnd, float ratio) {
        int aStart = (colorStart >> 24) & 0xFF;
        int rStart = (colorStart >> 16) & 0xFF;
        int gStart = (colorStart >> 8) & 0xFF;
        int bStart = colorStart & 0xFF;
        
        int aEnd = (colorEnd >> 24) & 0xFF;
        int rEnd = (colorEnd >> 16) & 0xFF;
        int gEnd = (colorEnd >> 8) & 0xFF;
        int bEnd = colorEnd & 0xFF;
        
        int a = (int)(aStart + (aEnd - aStart) * ratio);
        int r = (int)(rStart + (rEnd - rStart) * ratio);
        int g = (int)(gStart + (gEnd - gStart) * ratio);
        int b = (int)(bStart + (bEnd - bStart) * ratio);
        
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private float normalizeHueDelta(float delta) {
        if (delta > 180f) {
            return delta - 360f;
        }
        if (delta < -180f) {
            return delta + 360f;
        }
        return delta;
    }

    private int interpolateVividColor(int colorStart, int colorEnd, float ratio) {
        float t = Math.max(0f, Math.min(1f, ratio));
        float[] startHsv = new float[3];
        float[] endHsv = new float[3];
        Color.colorToHSV(colorStart, startHsv);
        Color.colorToHSV(colorEnd, endHsv);
        float hueDelta = normalizeHueDelta(endHsv[0] - startHsv[0]);
        float hue = (startHsv[0] + hueDelta * t + 360f) % 360f;
        float saturation = startHsv[1] + (endHsv[1] - startHsv[1]) * t;
        float value = startHsv[2] + (endHsv[2] - startHsv[2]) * t;
        // 保持颜色过渡期间也有足够冲击力，避免 ARGB 线性插值导致“发灰”。
        saturation = Math.max(0.78f, Math.min(1f, saturation));
        value = Math.max(0.78f, Math.min(1f, value));
        int alpha = Math.round(Color.alpha(colorStart) + (Color.alpha(colorEnd) - Color.alpha(colorStart)) * t);
        return Color.HSVToColor(alpha, new float[]{hue, saturation, value});
    }
    
    /**
     * 生成随机颜色（鲜艳的颜色）
     */
    private int generateRandomColor() {
        // 连续 HSV 随机：相比离散色池更不容易出现“总在几种相近色打转”的观感
        float hue = random.nextFloat() * 360f;
        float saturation = 0.85f + random.nextFloat() * 0.15f;
        float value = 0.85f + random.nextFloat() * 0.15f;
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    /**
     * 生成与参考色有明显差异的随机颜色，避免“颜色在变但观感几乎没变”。
     */
    private int generateDistinctRandomColor(int referenceColor) {
        final int maxAttempts = 12;
        int candidate = generateRandomColor();
        float bestScore = -1f;
        int bestCandidate = candidate;
        for (int i = 0; i < maxAttempts; i++) {
            candidate = generateRandomColor();
            float score = colorDifferenceScore(referenceColor, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
            if (score >= 0.28f) {
                return candidate;
            }
        }
        return bestCandidate;
    }

    private float colorDifferenceScore(int a, int b) {
        float[] hsvA = new float[3];
        float[] hsvB = new float[3];
        Color.colorToHSV(a, hsvA);
        Color.colorToHSV(b, hsvB);
        float hueDelta = Math.abs(normalizeHueDelta(hsvB[0] - hsvA[0])) / 180f; // 0..1
        float satDelta = Math.abs(hsvB[1] - hsvA[1]); // 0..1
        float valueDelta = Math.abs(hsvB[2] - hsvA[2]); // 0..1
        return hueDelta * 0.72f + satDelta * 0.14f + valueDelta * 0.14f;
    }

    // 颜色联动相关（保持旧版兼容：可切换为外部颜色源）
    private boolean colorSyncEnabled = true;
    private ColorSyncCallback colorSyncCallback;
    
    /** 颜色同步回调接口（用于从外部获取颜色） */
    public interface ColorSyncCallback {
        int getSyncColor();  // 获取同步颜色
    }
    
    /** 设置颜色同步回调（用于与跑马灯颜色联动） */
    public void setColorSyncCallback(ColorSyncCallback callback) {
        this.colorSyncCallback = callback;
    }
    
    /** 启用/禁用颜色联动。 */
    public void setColorSyncEnabled(boolean enabled) {
        this.colorSyncEnabled = enabled;
    }

    /**
     * 随机色池模式：在两次 onDraw 间隔较大时仍按 {@link #colorChangeIntervalMs} 对齐节拍（多拍可在一帧内追完）。
     */
    private void advanceRandomColorTargetsWithCatchUp(long currentTime) {
        long interval = Math.max(1L, colorChangeIntervalMs);
        if (lastColorChangeTime <= 0L) {
            lastColorChangeTime = currentTime - interval;
        }
        int jumps = 0;
        while (jumps < 48 && currentTime - lastColorChangeTime >= interval) {
            currentTextColor = interpolateVividColor(colorTransitionStart, targetTextColor, 1f);
            colorTransitionStart = currentTextColor;
            targetTextColor = generateDistinctRandomColor(colorTransitionStart);
            lastColorChangeTime += interval;
            jumps++;
        }
        if (BuildConfig.DEBUG && jumps > 0) {
            LogHelper.d(TAG, "🎨 颜色目标切换[source=random, at=" + currentTime
                    + ", intervalMs=" + interval
                    + ", catchUpJumps=" + jumps
                    + ", from=#" + Integer.toHexString(colorTransitionStart)
                    + ", to=#" + Integer.toHexString(targetTextColor) + "]");
        }
    }
    
    /**
     * 更新随机颜色（平滑过渡）
     * 如果启用了颜色联动，则使用外部提供的颜色
     * 注意：不在此函数入口做整段节流；逐字模式帧间隔可短于 33ms，节流会跳过「已满 colorChangeIntervalMs」的判定，导致换色节奏不跟随设置。
     */
    private void updateRandomColor() {
        long currentTime = System.currentTimeMillis();

        if (colorSyncEnabled && colorSyncCallback != null) {
            try {
                int syncColor = colorSyncCallback.getSyncColor();
                if (syncColor != targetTextColor) {
                    colorTransitionStart = currentTextColor;
                    targetTextColor = syncColor;
                    lastColorChangeTime = currentTime;
                    colorTransitionProgress = 0f;
                    if (BuildConfig.DEBUG) {
                        LogHelper.d(TAG, "🎨 颜色目标切换[source=sync, at=" + currentTime
                                + ", intervalMs=" + colorChangeIntervalMs
                                + ", from=#" + Integer.toHexString(colorTransitionStart)
                                + ", to=#" + Integer.toHexString(targetTextColor) + "]");
                    }
                }
            } catch (Exception ignored) {
                // 与跑马灯联动异常时回退随机节奏（同样用节拍追赶，避免稀疏重绘拉长周期）
                advanceRandomColorTargetsWithCatchUp(currentTime);
            }
        } else {
            // 随机源模式：按设置页「颜色变化节奏」切换目标色。
            // 逐字模式常关呼吸动画，onDraw 可能仅 ~80ms 一拍；若每次换色把 lastColorChangeTime 设为「当下」
            // 会吃掉整段已过时间，体感上「颜色变化节奏」与设置不一致。这里用 +=interval 追赶错过的节拍。
            advanceRandomColorTargetsWithCatchUp(currentTime);
        }

        long elapsed = currentTime - lastColorChangeTime;
        colorTransitionProgress = Math.min(1.0f, (float) elapsed / Math.max(1f, colorChangeIntervalMs));
        currentTextColor = interpolateVividColor(colorTransitionStart, targetTextColor, colorTransitionProgress);

        if (currentPaint != null) {
            currentPaint.setColor(currentTextColor);
        }
    }
    
    /**
     * 获取当前歌词文字颜色（用于与霓虹灯边框颜色联动）
     */
    public int getCurrentTextColor() {
        return currentTextColor;
    }
    
    /**
     * 重置自动居中定时器
     * 用户操作时调用，停止操作3秒后自动居中
     */
    private void resetAutoCenterTimer() {
        // 更新用户操作时间
        lastUserInteractionTime = System.currentTimeMillis();
        // 用户有新操作后，允许当前行再次触发一次自动居中，避免手动拖拽后不再回中。
        lastAutoCenterAnimatedLineIndex = -1;
        
        // 移除之前的定时器
        if (autoCenterHandler != null && autoCenterRunnable != null) {
            autoCenterHandler.removeCallbacks(autoCenterRunnable);
        }
        
        // 启动新的定时器：3秒后自动居中
        if (autoCenterHandler != null && autoCenterRunnable != null) {
            autoCenterHandler.postDelayed(autoCenterRunnable, AUTO_CENTER_DELAY_MS);
        }
    }
    
    /**
     * 检查并执行自动居中（不更新用户操作时间）
     * 在updatePosition中调用，用于实时检查是否需要居中
     */
    private void checkAndAutoCenter() {
        if (trackLoading) {
            return;
        }
        // 检查用户操作状态
        long timeSinceLastInteraction = System.currentTimeMillis() - lastUserInteractionTime;
        
        if (timeSinceLastInteraction > AUTO_CENTER_DELAY_MS) {
            // 用户已经停止操作超过3秒，检查是否需要居中
            if (currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
                float targetScrollY = currentLineIndex * LINE_SPACING;
                if (shouldAutoCenterCurrentLine(targetScrollY)) {
                    // 位置不一致，自动居中到当前行（同一行只触发一次）
                    animateScroll(scrollY, targetScrollY);
                    lastAutoCenterAnimatedLineIndex = currentLineIndex;
                    if (BuildConfig.DEBUG) {
                        LogHelper.d(TAG, "✅ 用户无操作，自动居中到当前行: " + currentLineIndex);
                    }
                }
            }
        }
        lastShuffleTokenCount = stableShuffleTokens.size();
        notifyShuffleDebugInfo();
    }

    private boolean shouldAutoCenterCurrentLine(float targetScrollY) {
        if (isSuperLyricSingleLineMode()) {
            return false;
        }
        if (Math.abs(scrollY - targetScrollY) <= 5f) {
            return false;
        }
        return currentLineIndex != lastAutoCenterAnimatedLineIndex;
    }

    private void pinSingleLineScrollIfNeeded() {
        if (!isSuperLyricSingleLineMode()) {
            return;
        }
        targetScrollY = 0f;
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        scrollY = 0f;
        currentLineIndex = 0;
        lastAutoCenterAnimatedLineIndex = 0;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 如果禁用手势控制，不处理任何触摸事件
        if (!enableGesture) {
            return false;  // 让触摸事件穿透，不拦截
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tapAccumulatedPanDistance = 0f;
        }

        // 用户触摸时，重置自动居中定时器
        resetAutoCenterTimer();

        gestureDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                resetAutoCenterTimer();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                // 滑动开启时，轻微抖动只会走 onScroll 不会触发 onSingleTapUp；用累计位移识别「点按」以跳转歌词（2×slop 容忍手抖）
                float tapSlopBudget = Math.max(slop * 2f, 24f);
                if (lineClickListener != null && !lyricLines.isEmpty()
                        && tapAccumulatedPanDistance <= tapSlopBudget) {
                    int lineIndex = getLineIndexFromY(event.getY());
                    if (lineIndex >= 0 && lineIndex < lyricLines.size()) {
                        lineClickListener.onLyricLineClick(lineIndex);
                    }
                }
                tapAccumulatedPanDistance = 0f;
                resetAutoCenterTimer();
                break;
        }

        return true;
    }
    
    // ========== 公共API ==========
    
    /**
     * 设置歌词数据
     */
    public void setLyrics(List<EnhancedLRCParser.EnhancedLyricLine> lyrics) {
        this.lyricLines = lyrics != null ? new ArrayList<>(lyrics) : new ArrayList<>();
        this.currentLineIndex = -1;
        this.scrollY = 0f;
        this.targetScrollY = 0f;
        this.targetPlaybackPosition = this.currentPosition;
        this.lastPositionFrameUptimeMs = 0L;
        this.lastWordProgressLineKey = "";
        this.lastWordProgressValue = 0f;
        this.lastWordProgressSampleUptimeMs = 0L;
        this.wordTimestampsRevision = 0;
        this.lastWordProgressRevision = -1;
        clearTokenizationCache();
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();
        
        LogHelper.d(TAG, "📝 设置歌词: " + this.lyricLines.size() + " 行");
        if (!this.lyricLines.isEmpty()) {
            LogHelper.d(TAG, "   第一行: " + this.lyricLines.get(0).text);
            LogHelper.d(TAG, "   第一行时间: " + this.lyricLines.get(0).time + "ms");
            
            // 如果有当前播放位置，立即查找并设置当前行
            // 但需要检查位置值是否合理（不超过1小时，即3600000ms）
            if (currentPosition > 0 && currentPosition <= 3600000) {
                int initialIndex = findCurrentLineIndex(currentPosition);
                if (initialIndex >= lyricLines.size()) {
                    initialIndex = lyricLines.size() - 1;
                }
                if (initialIndex >= 0) {
                    currentLineIndex = initialIndex;
                    targetScrollY = currentLineIndex * LINE_SPACING;
                    scrollY = targetScrollY;
                    LogHelper.d(TAG, "📍 初始设置当前行: " + currentLineIndex + ", scrollY=" + scrollY + 
                              " (位置: " + currentPosition + "ms, 目标居中)");
                }
            } else if (currentPosition > 3600000) {
                LogHelper.w(TAG, "⚠ 初始位置值异常: " + currentPosition + "ms（超过1小时），重置为0");
                currentPosition = 0;
                targetPlaybackPosition = 0;
                currentLineIndex = 0;
                targetScrollY = 0f;
                scrollY = 0f;
            } else {
                // 位置为0或负数，设置为第一行，确保第一行居中
                currentLineIndex = 0;
                targetScrollY = 0f;
                scrollY = 0f;
                LogHelper.d(TAG, "📍 初始设置为第一行，scrollY=0，确保第一行居中");
            }
        }
        
        // 启动自动居中定时器（初始状态）
        resetAutoCenterTimer();

        applyAutoLineSpacingFromFontIfNeeded();
        postInvalidate();
    }

    /**
     * 从单句预览升级到完整 LRC：保留播放进度锚点，按 preferredLineIndex 或进度定位当前行，减少大范围跳屏。
     */
    public void setLyricsPreservingPlaybackAnchor(List<EnhancedLRCParser.EnhancedLyricLine> lyrics,
                                                  long positionMs,
                                                  int preferredLineIndex) {
        setLyricsPreservingPlaybackAnchor(lyrics, positionMs, preferredLineIndex, false);
    }

    /**
     * @param preserveWordHighlightProgress 为 true 时保留逐字高亮进度（汽水单句→完整 LRC 升级）
     */
    public void setLyricsPreservingPlaybackAnchor(List<EnhancedLRCParser.EnhancedLyricLine> lyrics,
                                                  long positionMs,
                                                  int preferredLineIndex,
                                                  boolean preserveWordHighlightProgress) {
        float preservedProgress = preserveWordHighlightProgress ? lastWordProgressValue : 0f;
        String preservedLineKey = preserveWordHighlightProgress ? lastWordProgressLineKey : "";
        int preservedRevision = preserveWordHighlightProgress ? wordTimestampsRevision : 0;
        this.lyricLines = lyrics != null ? new ArrayList<>(lyrics) : new ArrayList<>();
        long safePosition = Math.max(0L, positionMs);
        if (safePosition > 3600000L) {
            safePosition = 0L;
        }
        this.currentPosition = safePosition;
        this.targetPlaybackPosition = safePosition;
        this.lastPositionFrameUptimeMs = 0L;
        this.lastWordProgressLineKey = "";
        this.lastWordProgressValue = 0f;
        this.lastWordProgressSampleUptimeMs = 0L;
        this.wordTimestampsRevision = 0;
        this.lastWordProgressRevision = -1;
        clearTokenizationCache();
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();

        int initialIndex = -1;
        if (preferredLineIndex >= 0 && preferredLineIndex < this.lyricLines.size()) {
            initialIndex = preferredLineIndex;
        } else if (safePosition > 0L && !this.lyricLines.isEmpty()) {
            initialIndex = findCurrentLineIndex(safePosition);
        }
        if (initialIndex < 0 && !this.lyricLines.isEmpty()) {
            initialIndex = 0;
        }
        if (initialIndex >= 0) {
            currentLineIndex = initialIndex;
            targetScrollY = currentLineIndex * LINE_SPACING;
            float delta = Math.abs(scrollY - targetScrollY);
            if (delta >= LINE_SPACING * 2f) {
                animateScroll(scrollY, targetScrollY);
            } else {
                scrollY = targetScrollY;
            }
            if (preserveWordHighlightProgress
                && initialIndex < this.lyricLines.size()) {
                EnhancedLRCParser.EnhancedLyricLine anchorLine = this.lyricLines.get(initialIndex);
                if (anchorLine != null
                    && anchorLine.wordTimestamps != null
                    && !anchorLine.wordTimestamps.isEmpty()) {
                    lastWordProgressValue = preservedProgress;
                    lastWordProgressLineKey = wordProgressLineKey(anchorLine);
                    wordTimestampsRevision = preservedRevision + 1;
                    lastWordProgressRevision = preservedRevision;
                }
            }
        } else {
            currentLineIndex = -1;
            scrollY = 0f;
            targetScrollY = 0f;
        }
        resetAutoCenterTimer();
        applyAutoLineSpacingFromFontIfNeeded();
        postInvalidate();
    }

    /**
     * SuperLyric 单句预览 → 完整 LRC：保留播放位置与逐字进度，平滑展开多行布局。
     */
    public void upgradeFromSingleLinePreview(List<EnhancedLRCParser.EnhancedLyricLine> lyrics,
                                             long positionMs,
                                             int preferredLineIndex) {
        this.lyricLines = lyrics != null ? new ArrayList<>(lyrics) : new ArrayList<>();
        long safePosition = Math.max(0L, positionMs);
        if (safePosition > 3600000L) {
            safePosition = 0L;
        }
        this.currentPosition = safePosition;
        this.targetPlaybackPosition = safePosition;
        this.lastPositionFrameUptimeMs = SystemClock.uptimeMillis();
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();
        clearTokenizationCache();

        int anchor = preferredLineIndex;
        if (anchor < 0 || anchor >= this.lyricLines.size()) {
            anchor = !this.lyricLines.isEmpty() ? findCurrentLineIndex(safePosition) : -1;
        }
        if (anchor < 0 && !this.lyricLines.isEmpty()) {
            anchor = 0;
        }
        if (anchor >= 0) {
            currentLineIndex = anchor;
            targetScrollY = currentLineIndex * LINE_SPACING;
            animateScroll(scrollY, targetScrollY, SINGLE_LINE_UPGRADE_SCROLL_DURATION_MS);
        } else {
            currentLineIndex = -1;
            scrollY = 0f;
            targetScrollY = 0f;
        }
        resetAutoCenterTimer();
        applyAutoLineSpacingFromFontIfNeeded();
        snapWordHighlightToPosition(safePosition);
    }

    /**
     * 按当前播放位置立即对齐逐字高亮（升级/融合后避免进度回零或轴切换跳变）。
     */
    public void snapWordHighlightToPosition(long positionMs) {
        long safePosition = Math.max(0L, positionMs);
        if (safePosition > 3600000L) {
            safePosition = 0L;
        }
        currentPosition = safePosition;
        targetPlaybackPosition = safePosition;
        lastPositionFrameUptimeMs = SystemClock.uptimeMillis();
        lastLyricProgressInvalidateTime = 0L;
        lastSameLinePlayingInvalidateMs = 0L;

        int idx = currentLineIndex;
        if (idx < 0 || idx >= lyricLines.size()) {
            idx = lyricLines.isEmpty() ? -1 : findCurrentLineIndex(safePosition);
            if (idx >= 0 && idx < lyricLines.size()) {
                currentLineIndex = idx;
                targetScrollY = idx * LINE_SPACING;
            }
        }
        if (idx >= 0 && idx < lyricLines.size()) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(idx);
            if (line != null
                && line.wordTimestamps != null
                && !line.wordTimestamps.isEmpty()) {
                wordTimestampsRevision++;
                float target = computeLineProgressTarget(line);
                lastWordProgressValue = target;
                lastWordProgressLineKey = wordProgressLineKey(line);
                lastWordProgressRevision = wordTimestampsRevision;
                lastWordProgressSampleUptimeMs = SystemClock.uptimeMillis();
            }
        }
        postInvalidateOnAnimation();
        invalidate();
    }

    /**
     * 重置歌词渲染状态（用于数据源切换时彻底解锁 UI 状态）
     */
    public void resetLyricsState() {
        currentLineIndex = -1;
        scrollY = 0f;
        targetScrollY = 0f;
        currentPosition = 0L;
        targetPlaybackPosition = 0L;
        lastRawPlaybackPosition = 0L;
        lastPositionFrameUptimeMs = 0L;
        lastAutoCenterAnimatedLineIndex = -1;
        lastLineChangedAtMs = 0L;
        lastLyricProgressInvalidateTime = 0L;
        lastWordProgressLineKey = "";
        lastWordProgressValue = 0f;
        lastWordProgressSampleUptimeMs = 0L;
        wordTimestampsRevision = 0;
        lastWordProgressRevision = -1;
        stableShuffleSignature = "";
        stableShuffleTokens.clear();
        clearTokenizationCache();
        if (scaleAnimator != null && scaleAnimator.isRunning()) {
            scaleAnimator.cancel();
        }
        currentScale = 1.0f;
        currentDisplacement = 0f;
    }

    /**
     * 刷新所有歌词行缓存与绘制状态
     */
    public void refreshAllLines() {
        stableShuffleSignature = "";
        stableShuffleTokens.clear();
        clearTokenizationCache();
        notifyShuffleDebugInfo();
        postInvalidateOnAnimation();
        invalidate();
    }

    /**
     * 强制刷新当前行（用于兜底歌词句子/逐字数据更新后立即重绘）
     */
    public void refreshCurrentLine() {
        if (lyricLines.isEmpty()) {
            postInvalidateOnAnimation();
            invalidate();
            return;
        }
        pinSingleLineScrollIfNeeded();

        int newIndex = findCurrentLineIndex(currentPosition);
        if (newIndex < 0 || newIndex >= lyricLines.size()) {
            newIndex = Math.max(0, Math.min(currentLineIndex, lyricLines.size() - 1));
        }

        if (newIndex != currentLineIndex) {
            currentLineIndex = newIndex;
            targetScrollY = currentLineIndex * LINE_SPACING;
            lastAutoCenterAnimatedLineIndex = -1;
            lastLineChangedAtMs = System.currentTimeMillis();
            stableShuffleSignature = "";
            stableShuffleTokens.clear();
            // 切句时保留当前呼吸相位，避免重置到固定缩放值导致视觉突变。
            applyPowerSavingLineColorIfNeeded(/*force*/ true);
            if (!isSuperLyricSingleLineMode()) {
                if (!isDragging && Math.abs(scrollY - targetScrollY) > 4f) {
                    animateScroll(scrollY, targetScrollY);
                } else if (!isDragging) {
                    scrollY = targetScrollY;
                }
            } else {
                pinSingleLineScrollIfNeeded();
            }
        }

        // 重置逐字节流，确保新的 word 时间轴立即驱动高亮进度。
        lastLyricProgressInvalidateTime = 0L;
        postInvalidateOnAnimation();
        invalidate();
    }

    /**
     * 仅更新某一行的逐字时间轴（智能切换融合）：不重置 scrollY、不触发 setLyrics，避免当前行闪到屏中。
     */
    public void notifyWordTimestampsChanged(int lineIndex) {
        if (lyricLines.isEmpty()) {
            postInvalidateOnAnimation();
            invalidate();
            return;
        }
        pinSingleLineScrollIfNeeded();
        wordTimestampsRevision++;
        lastWordProgressRevision = -1;
        if (lineIndex >= 0 && lineIndex < lyricLines.size()) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(lineIndex);
            lastWordProgressLineKey = wordProgressLineKey(line);
        }
        lastLyricProgressInvalidateTime = 0L;
        postInvalidateOnAnimation();
        invalidate();
    }

    /**
     * 立即将当前行居中（不等待 AUTO_CENTER_DELAY_MS）。
     * 用于 SuperLyric 兜底实时句子更新时，保证切句后马上居中显示。
     */
    public void centerCurrentLineImmediately() {
        if (lyricLines.isEmpty()) {
            postInvalidateOnAnimation();
            invalidate();
            return;
        }
        if (isSuperLyricSingleLineMode()) {
            pinSingleLineScrollIfNeeded();
            postInvalidateOnAnimation();
            invalidate();
            return;
        }
        long anchorMs = Math.max(0L, targetPlaybackPosition);
        if (anchorMs > 3600000L) {
            anchorMs = Math.max(0L, currentPosition);
        }
        int idx = findCurrentLineIndex(anchorMs);
        if (idx < 0 || idx >= lyricLines.size()) {
            idx = currentLineIndex >= 0 && currentLineIndex < lyricLines.size() ? currentLineIndex : 0;
        }
        currentLineIndex = idx;
        targetScrollY = currentLineIndex * LINE_SPACING;
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        scrollY = targetScrollY;
        lastAutoCenterAnimatedLineIndex = currentLineIndex;
        postInvalidateOnAnimation();
        invalidate();
    }

    /**
     * 将渲染进度立即对齐到目标播放位置（充电视频层结束等场景，避免逐字高亮长时间追赶）。
     */
    public void snapPlaybackPositionToTarget() {
        currentPosition = targetPlaybackPosition;
        lastPositionFrameUptimeMs = SystemClock.uptimeMillis();
        lastWordProgressLineKey = "";
        lastWordProgressValue = 0f;
        lastWordProgressSampleUptimeMs = 0L;
        lastLyricProgressInvalidateTime = 0L;
        lastSameLinePlayingInvalidateMs = 0L;
        postInvalidateOnAnimation();
    }

    /**
     * 更新播放位置
     */
    public void updatePosition(long position) {
        pinSingleLineScrollIfNeeded();
        // 检查位置值是否合理（不超过1小时，即3600000ms）
        // 如果位置值看起来像系统时间戳的一部分，直接忽略
        if (position > 3600000) {
            LogHelper.w(TAG, "⚠ 位置值异常: " + position + "ms（超过1小时），忽略此次更新");
            return; // 忽略异常的位置更新
        }
        
        // 确保位置不为负数
        if (position < 0) {
            position = 0;
        }
        lastRawPlaybackPosition = position;
        long adjusted = position + timeAdjustOffset;
        if (adjusted < 0) {
            adjusted = 0;
        }
        if (playbackActive
            && targetPlaybackPosition > 0L
            && adjusted < targetPlaybackPosition
            && targetPlaybackPosition - adjusted < WORD_PROGRESS_POSITION_JITTER_MS) {
            adjusted = targetPlaybackPosition;
        }
        this.targetPlaybackPosition = adjusted;
        syncRenderedPosition(false);

        if (trackLoading) {
            postInvalidateOnAnimation();
            return;
        }
        
        // 查找当前行（使用含偏移后的时间，逐字/行同步与设置一致）
        int newIndex = findCurrentLineIndex(adjusted);
        
        // 验证索引是否合理
        if (lyricLines.isEmpty()) {
            // 如果没有歌词，保持当前状态
            return;
        }
        
        if (newIndex < 0 || newIndex >= lyricLines.size()) {
            // 只在索引确实不合理且与当前索引不同时记录警告
            if (newIndex != currentLineIndex) {
                LogHelper.w(TAG, "⚠ 计算出的行索引(" + newIndex + ")不合理，保持当前行: " + currentLineIndex);
            }
            newIndex = currentLineIndex >= 0 ? currentLineIndex : 0;
        }
        
        boolean lineChanged = (newIndex != currentLineIndex);
        
        if (lineChanged) {
            LogHelper.d(TAG, "🎵 歌词行变化: " + currentLineIndex + " -> " + newIndex + 
                      " (位置: " + adjusted + "ms, 原始: " + lastRawPlaybackPosition + "ms)");
            currentLineIndex = newIndex;
            lastAutoCenterAnimatedLineIndex = -1;
            lastLineChangedAtMs = System.currentTimeMillis();
            applyPowerSavingLineColorIfNeeded(/*force*/ true);
            // 播放推进换行时立即跟随当前行滚动（与 refreshCurrentLine 一致），
            // 不等待 AUTO_CENTER_DELAY_MS，避免切歌/新歌词到达后长时间偏离屏中。
            if (!isSuperLyricSingleLineMode()) {
                targetScrollY = currentLineIndex * LINE_SPACING;
                if (!isDragging) {
                    if (!trackLoading && Math.abs(scrollY - targetScrollY) > 4f) {
                        animateScroll(scrollY, targetScrollY);
                    } else {
                        scrollY = targetScrollY;
                    }
                }
            }
        }
        
        // 更新targetScrollY（无论行是否变化）
        if (currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
            targetScrollY = currentLineIndex * LINE_SPACING;
        }
        
        // 检查用户操作状态，决定是否自动居中
        // 用户无操作时：立即检查并居中（不更新用户操作时间）
        // 用户正在操作时：不居中，等待用户停止操作
        long timeSinceLastInteraction = System.currentTimeMillis() - lastUserInteractionTime;
        if (timeSinceLastInteraction > AUTO_CENTER_DELAY_MS) {
            // 用户已经停止操作超过3秒，检查并居中
            checkAndAutoCenter();
        }
        // 注意：这里不调用resetAutoCenterTimer()，因为updatePosition不是用户操作
        // resetAutoCenterTimer()只在用户真正操作时调用（onTouchEvent、onScroll等）

        // 非逐字且当前行未变：有呼吸动画时由其驱动重绘；否则用下方节流 invalidate，否则颜色/渐变不会随时间前进。
        long nowMs = System.currentTimeMillis();
        if (lineChanged) {
            stableShuffleSignature = "";
            stableShuffleTokens.clear();
            lastWordProgressLineKey = "";
            lastWordProgressValue = 0f;
            lastWordProgressSampleUptimeMs = 0L;
            lastLyricProgressInvalidateTime = nowMs;
            lastSameLinePlayingInvalidateMs = 0L;
            postInvalidateOnAnimation();
        } else if (enableWordByWord) {
            long intervalMs = playbackActive
                ? (powerSavingModeEnabled
                    ? WORD_PROGRESS_FRAME_POWER_SAVE_MS
                    : Math.min(LYRIC_PROGRESS_INVALIDATE_MS, Math.max(16L, colorChangeIntervalMs / 30)))
                : PAUSED_LYRIC_INVALIDATE_MS;
            if (nowMs - lastLyricProgressInvalidateTime >= intervalMs) {
                lastLyricProgressInvalidateTime = nowMs;
                postInvalidateOnAnimation();
            }
        } else if (!playbackActive && nowMs - lastPausedInvalidateAtMs >= PAUSED_LYRIC_INVALIDATE_MS) {
            // 非逐字模式在暂停时也允许低帧刷新，避免上层频繁回调导致无意义重绘。
            lastPausedInvalidateAtMs = nowMs;
            postInvalidateOnAnimation();
        } else if (playbackActive) {
            // 整行高亮：重绘节拍与「颜色变化节奏」挂钩，避免仅按固定 33ms 节流时慢节奏下过渡欠采样、快滑块时欠跟手。
            long intervalMs = powerSavingModeEnabled
                    ? 200L
                    : Math.min(COLOR_UPDATE_THROTTLE_MS, Math.max(16L, colorChangeIntervalMs / 30));
            if (nowMs - lastSameLinePlayingInvalidateMs >= intervalMs) {
                lastSameLinePlayingInvalidateMs = nowMs;
                postInvalidateOnAnimation();
            }
        }
    }

    private void syncRenderedPosition(boolean forceSnap) {
        long now = SystemClock.uptimeMillis();
        if (lastPositionFrameUptimeMs <= 0L) {
            currentPosition = targetPlaybackPosition;
            lastPositionFrameUptimeMs = now;
            return;
        }
        long dt = Math.max(1L, now - lastPositionFrameUptimeMs);
        long delta = targetPlaybackPosition - currentPosition;
        boolean seekOrReset = Math.abs(delta) >= WORD_PROGRESS_SEEK_SNAP_THRESHOLD_MS;
        if (forceSnap || seekOrReset || !playbackActive) {
            currentPosition = targetPlaybackPosition;
        } else if (delta > 0L) {
            long maxStep = Math.max(1L, Math.min(delta, Math.max(WORD_PROGRESS_MAX_CATCHUP_PER_FRAME_MS, dt + 8L)));
            currentPosition += maxStep;
        }
        lastPositionFrameUptimeMs = now;
    }
    
    /**
     * 查找当前行索引
     * V3.6: 修复提前跳转问题
     * - 所有模式都等到当前行真正结束（到达下一行开始时间）才切换
     * - 逐字模式：等待当前行最后一个字播放完成才切换
     * - 非逐字模式：timeAdjustOffset只用于提前显示，不影响切换时机
     */
    private int findCurrentLineIndex(long position) {
        if (lyricLines.isEmpty()) {
            return -1;
        }
        if (isSuperLyricSingleLineMode()) {
            return 0;
        }
        
        // 首先检查位置值是否合理（不超过1小时，即3600000ms）
        // 如果位置值看起来像系统时间戳的一部分，直接返回第一行
        if (position > 3600000) {
            LogHelper.w(TAG, "⚠ 位置值异常: " + position + "ms（超过1小时），重置为0");
            position = 0;
        }
        
        // 如果位置为0或负数，返回第一行（索引0）
        if (position <= 0) {
            return 0;
        }
        
        // 从后往前查找，找到第一个时间小于等于位置的行
        for (int i = lyricLines.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);
            if (line == null) {
                continue;
            }
            
            // 计算这一行的有效结束时间
            long lineEndTime;
            
            // V3.6: 只有当启用逐字模式且该行有逐字时间戳时，才使用逐字模式逻辑
            // 否则，即使有逐字时间戳但未启用逐字模式，也使用非逐字模式（timeAdjustOffset提前显示）
            boolean useWordByWordMode = hasActiveWordTimestamps(line);
            
            if (useWordByWordMode) {
                // V3.15: 逐字模式：使用最后一个字的结束时间，确保逐字动画完成才切换
                // 修复提前跳转问题：必须等到最后一个字播放完成才切换到下一行
                long lastWordEndTime = 0;
                long lastWordStartTime = 0;
                boolean moduleFusionTimeline = hasModuleWordHighlightTimeline(line);
                boolean sentenceRelative = !moduleFusionTimeline
                    && SuperLyricWordTimestamps.usesSentenceRelativeTimeline(
                        line.wordTimestamps, line.time);
                long effectivePos = resolveEffectivePlaybackMs(line, position);
                
                // 找到最后一个字的开始和结束时间
                for (EnhancedLRCParser.WordTimestamp word : line.wordTimestamps) {
                    if (word.startTime > lastWordStartTime) {
                        lastWordStartTime = word.startTime;
                    }
                    if (word.endTime > 0 && word.endTime > lastWordEndTime) {
                        lastWordEndTime = word.endTime;
                    }
                }
                
                // 计算行的结束时间
                if (lastWordEndTime > 0) {
                    // 如果有有效的最后一个字的结束时间，使用它
                    lineEndTime = lastWordEndTime;
                    // V3.15: 确保至少给最后一个字一些显示时间（至少200ms）
                    // 如果最后一个字的结束时间太早，延长到至少200ms后
                    if (lastWordStartTime > 0 && lastWordEndTime <= lastWordStartTime + 100) {
                        lineEndTime = lastWordStartTime + 200;
                        LogHelper.d(TAG, String.format("⚠ 最后一个字结束时间太短，延长到: %dms", lineEndTime));
                    }
                } else {
                    // 如果没有有效的结束时间，使用下一行的开始时间或默认值
                    if (i + 1 < lyricLines.size()) {
                        lineEndTime = lyricLines.get(i + 1).time;
                    } else {
                        // 最后一行：使用最后一个字的开始时间 + 默认持续时间
                        if (lastWordStartTime > 0) {
                            lineEndTime = lastWordStartTime + 500; // 最后一个字至少显示500ms
                        } else {
                            lineEndTime = line.time + 3000; // 默认3秒
                        }
                    }
                }
                
                // V3.15: 确保行的结束时间不会早于最后一个字的开始时间
                // 这样可以确保最后一个字有足够的时间显示
                if (lastWordStartTime > 0 && lineEndTime < lastWordStartTime + 200) {
                    lineEndTime = lastWordStartTime + 200;
                    LogHelper.d(TAG, String.format("⚠ 行结束时间早于最后一个字，调整为: %dms", lineEndTime));
                }
                
                // 逐字模式：句内相对轴用 effectivePos，整曲绝对轴用 position 与 line.time
                if (sentenceRelative) {
                    if (effectivePos >= 0 && effectivePos < lineEndTime) {
                        return i;
                    }
                } else if (position >= line.time && position < lineEndTime) {
                    return i;
                }
            } else {
                // 非逐字模式：不使用时间调整偏移量，与音乐播放时间完全同步
                // V3.16: 已移除timeAdjustOffset，确保歌词显示与音乐播放时间完全同步
                
                // 计算行的结束时间（使用下一行的开始时间）
                if (i + 1 < lyricLines.size()) {
                    lineEndTime = lyricLines.get(i + 1).time;
                } else {
                    lineEndTime = line.time + 3000; // 默认3秒
                }
                
                // V3.16: 不使用timeAdjustOffset，直接使用position判断
                // 必须等到position >= line.time才开始显示，position < lineEndTime才保持显示
                if (position >= line.time && position < lineEndTime) {
                    return i;
                }
            }
        }
        
        // 如果没找到，使用回退逻辑（非逐字模式的回退）
        // V3.16: 已移除timeAdjustOffset，直接使用position
        int lastIndex = lyricLines.size() - 1;
        EnhancedLRCParser.EnhancedLyricLine lastLine = lyricLines.get(lastIndex);
        if (lastLine != null && position >= lastLine.time) {
            return lastIndex;
        }
        
        // V3.16: 回退逻辑不使用timeAdjustOffset，直接使用position
        // 从后往前查找，找到第一个时间小于等于位置的行
        for (int i = lyricLines.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);
            if (line == null) {
                continue;
            }
            
            // 计算行的结束时间
            long lineEndTime;
            if (i + 1 < lyricLines.size()) {
                EnhancedLRCParser.EnhancedLyricLine next = lyricLines.get(i + 1);
                lineEndTime = next != null ? next.time : line.time + 3000;
            } else {
                lineEndTime = line.time + 3000; // 默认3秒
            }
            if (lineEndTime <= line.time) {
                lineEndTime = line.time + 3000;
            }
            
            // 只有当position >= line.time才开始显示，且position < lineEndTime才保持显示
            if (position >= line.time && position < lineEndTime) {
                if (i >= lyricLines.size()) {
                    LogHelper.w(TAG, "⚠ 计算出的索引(" + i + ")超出范围，返回第一行");
                    return 0;
                }
                return i;
            }
        }
        
        // 如果所有行的时间都大于调整后的位置，返回第一行
        return 0;
    }
    
    /**
     * 跳转到指定行
     */
    public void seekToLine(int lineIndex) {
        if (lineIndex >= 0 && lineIndex < lyricLines.size()) {
            currentLineIndex = lineIndex;
            targetScrollY = lineIndex * LINE_SPACING;
            stableShuffleSignature = "";
            stableShuffleTokens.clear();
            animateScroll(scrollY, targetScrollY);
        }
    }
    
    // ========== 配置方法 ==========
    
    public void setShowTranslation(boolean show) {
        this.showTranslation = show;
        applyAutoLineSpacingFromFontIfNeeded();
        invalidate();
    }

    /**
     * Kuwo broadcast LYRIC_PROGRESS provides exact word index and char positions.
     * This hint overrides the time-based word progress calculation for accurate highlighting.
     *
     * @param lineIndex     current singing line index from broadcast
     * @param wordCharStart inclusive char start index of current word
     * @param wordCharEnd   inclusive char end index of current word
     */
    public void setKuwoWordHighlightHint(int lineIndex, int wordCharStart, int wordCharEnd) {
        if (lineIndex < 0 || wordCharStart < 0 || wordCharEnd < 0) {
            this.mKuwoWordHintValid = false;
            return;
        }
        this.mKuwoWordHintValid = true;
        this.mKuwoWordHintLineIndex = lineIndex;
        this.mKuwoWordHintCharStart = wordCharStart;
        this.mKuwoWordHintCharEnd = wordCharEnd;
        this.mKuwoWordHintTimestamp = android.os.SystemClock.uptimeMillis();

        // 酷我广播驱动：收到逐字位置时自动启用逐字模式
        if (!enableWordByWord) {
            setEnableWordByWord(true);
        }

        // Line change from broadcast: update current line and scroll
        if (lineIndex != currentLineIndex && lineIndex >= 0 && lineIndex < lyricLines.size()) {
            currentLineIndex = lineIndex;
            targetScrollY = currentLineIndex * LINE_SPACING;
            lastAutoCenterAnimatedLineIndex = -1;
            lastLineChangedAtMs = System.currentTimeMillis();
            stableShuffleSignature = "";
            stableShuffleTokens.clear();
            applyPowerSavingLineColorIfNeeded(true);
            if (!isDragging && Math.abs(scrollY - targetScrollY) > 4f) {
                animateScroll(scrollY, targetScrollY);
            } else if (!isDragging) {
                scrollY = targetScrollY;
            }
        }

        postInvalidate();
    }

    public void setEnableWordByWord(boolean enable) {
        boolean oldValue = this.enableWordByWord;
        this.enableWordByWord = enable;
        LogHelper.d(TAG, "🔄 逐字显示已" + (enable ? "启用" : "禁用") + " (从 " + oldValue + " 改为 " + enable + ")");
        if (!randomColorSwitchEnabled) {
            applyReadableStaticColor();
        }
        
        // 如果当前有歌词，重新计算当前行（因为逐字模式会影响行切换逻辑）
        if (!lyricLines.isEmpty() && currentPosition > 0) {
            int oldIndex = currentLineIndex;
            int newIndex = findCurrentLineIndex(currentPosition);
            LogHelper.d(TAG, "📊 逐字显示改变后重新计算当前行: 位置=" + currentPosition + "ms, 旧索引=" + oldIndex + ", 新索引=" + newIndex);
            
            // 无论索引是否改变，都强制更新，因为绘制方式已经改变
            if (newIndex != currentLineIndex) {
                currentLineIndex = newIndex;
                targetScrollY = newIndex * LINE_SPACING;
                animateScroll(scrollY, targetScrollY);
                LogHelper.d(TAG, "✅ 当前行已更新: " + oldIndex + " -> " + newIndex);
            } else {
                // 即使索引没变，绘制方式已经改变，需要强制重绘
                LogHelper.d(TAG, "🔄 当前行索引未变，但绘制方式已改变，强制重绘");
            }
        }
        
        // 使用一次 postInvalidate 即可，避免同一帧重复请求重绘导致背屏渲染抖动
        postInvalidate();
    }

    public void setShowProgress(boolean show) {
        this.showProgress = show;
        invalidate();
    }
    
    /**
     * 投屏歌词字体，id 见 {@link LyricsFontHelper}；自定义字体时传入文件绝对路径。
     */
    public void setLyricsFont(String fontId, String customFontPath) {
        Typeface lyricTypeface = LyricsFontHelper.resolveTypeface(getContext(), fontId, customFontPath);
        currentPaint.setTypeface(lyricTypeface);
        normalPaint.setTypeface(lyricTypeface);
        dimPaint.setTypeface(lyricTypeface);
        translationPaint.setTypeface(lyricTypeface);
        strokePaint.setTypeface(lyricTypeface);
        backgroundTexturePaint.setTypeface(lyricTypeface);
        outlinePaint.setTypeface(lyricTypeface);
        if (shufflePaint != null) {
            shufflePaint.setTypeface(lyricTypeface);
        }
        applyAutoLineSpacingFromFontIfNeeded();
        stableShuffleSignature = "";
        stableShuffleTokens.clear();
        notifyShuffleDebugInfo();
        postInvalidate();
    }

    /** @see #setLyricsFont(String, String) */
    public void setLyricsFontId(String fontId) {
        setLyricsFont(fontId, null);
    }

    /**
     * 设置歌词文本大小
     * @param textSize 当前行字体大小（普通行会自动计算为当前行的83%）
     */

    public void setTextSize(float textSize) {
        CURRENT_TEXT_SIZE = textSize;
        NORMAL_TEXT_SIZE = textSize * 0.83f;  // 普通行约为当前行的83%
        
        // 更新所有画笔的字体大小
        if (currentPaint != null) {
            currentPaint.setTextSize(CURRENT_TEXT_SIZE);
            currentPaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);
        }
        if (normalPaint != null) {
            normalPaint.setTextSize(NORMAL_TEXT_SIZE);
            normalPaint.setLetterSpacing(LETTER_SPACING / NORMAL_TEXT_SIZE);
        }
        if (dimPaint != null) {
            dimPaint.setTextSize(NORMAL_TEXT_SIZE);
            dimPaint.setLetterSpacing(LETTER_SPACING / NORMAL_TEXT_SIZE);
        }
        if (translationPaint != null) {
            translationPaint.setTextSize(NORMAL_TEXT_SIZE * TRANSLATION_TEXT_SIZE_RATIO);
        }
        if (strokePaint != null) {
            strokePaint.setTextSize(CURRENT_TEXT_SIZE);
            strokePaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);
        }
        if (backgroundTexturePaint != null) {
            backgroundTexturePaint.setTextSize(CURRENT_TEXT_SIZE * backgroundTextureSizeScale);
        }
        if (shufflePaint != null) {
            shufflePaint.setTextSize(CURRENT_TEXT_SIZE);
        }
        if (outlinePaint != null) {
            outlinePaint.setTextSize(CURRENT_TEXT_SIZE);
            outlinePaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);
        }

        applyAutoLineSpacingFromFontIfNeeded();
        stableShuffleSignature = "";
        stableShuffleTokens.clear();
        notifyShuffleDebugInfo();
        invalidate();
        LogHelper.d(TAG, "📝 字体大小已更新: 当前行=" + CURRENT_TEXT_SIZE + "px, 普通行=" + NORMAL_TEXT_SIZE + "px, 行距=" + LINE_SPACING + "px");
    }
    
    /**
     * 设置行间距（用于主屏横屏模式时调大间距）
     */
    public void setLineSpacing(float spacing) {
        if (spacing < 0) {
            LogHelper.w(TAG, "⚠️ 行间距不能为负数，使用默认值160");
            spacing = 160f;
        }
        lineSpacingLockedByExternal = true;
        LINE_SPACING = spacing;
        syncScrollToCurrentLineAfterSpacingChange();
        invalidate();
        LogHelper.d(TAG, "📝 行间距已更新（固定）: " + LINE_SPACING + "px");
    }
    
    /**
     * 获取当前行间距
     */
    public float getLineSpacing() {
        return LINE_SPACING;
    }
    
    /**
     * 设置底图文本大小倍数
     * @param scale 倍数（相对于当前行字体大小，例如1.3表示当前行的1.3倍）
     */
    public void setBackgroundTextureSize(float scale) {
        if (scale < 0.5f || scale > 2.0f) {
            LogHelper.w(TAG, "⚠️ 底图文本大小倍数超出范围(0.5-2.0)，使用默认值1.3");
            scale = 1.3f;
        }
        
        backgroundTextureSizeScale = scale;
        
        // 更新底图画笔的字体大小
        if (backgroundTexturePaint != null) {
            backgroundTexturePaint.setTextSize(CURRENT_TEXT_SIZE * backgroundTextureSizeScale);
            invalidate();
        }
        
        LogHelper.d(TAG, "📝 底图文本大小已更新: " + backgroundTextureSizeScale + "x (当前行=" + CURRENT_TEXT_SIZE + "px)");
    }
    
    /**
     * 设置未唱歌词透明度
     * @param alpha 透明度百分比(0-100)
     */
    public void setNormalLyricsAlpha(int alpha) {
        if (alpha < 0 || alpha > 100) {
            LogHelper.w(TAG, "⚠️ 未唱歌词透明度超出范围(0-100)，使用默认值30");
            alpha = 30;
        }
        
        normalLyricsAlpha = alpha;
        updateNormalLyricsColor();
        updateDimLyricsColor();
        invalidate();
        
        LogHelper.d(TAG, "📝 未唱歌词透明度已更新: " + normalLyricsAlpha + "%");
    }
    
    /**
     * 设置底图歌词透明度
     * @param alpha 透明度百分比(0-100)
     */
    public void setBackgroundTextureAlpha(int alpha) {
        if (alpha < 0 || alpha > 100) {
            LogHelper.w(TAG, "⚠️ 底图歌词透明度超出范围(0-100)，使用默认值20");
            alpha = 20;
        }
        
        backgroundTextureAlpha = alpha;
        updateBackgroundTextureColor();
        invalidate();
        
        LogHelper.d(TAG, "📝 底图歌词透明度已更新: " + backgroundTextureAlpha + "%");
    }
    
    /**
     * 设置时间调整偏移量
     * @param offset 偏移量（毫秒），正数表示相对媒体进度提前显示，负数表示延后
     */
    public void setTimeAdjustOffset(long offset) {
        this.timeAdjustOffset = offset;
        LogHelper.d(TAG, "⏰ 时间调整偏移量已设置为: " + offset + "ms");
        long adjusted = lastRawPlaybackPosition + timeAdjustOffset;
        if (adjusted < 0) {
            adjusted = 0;
        }
        this.targetPlaybackPosition = adjusted;
        syncRenderedPosition(true);
        if (lyricLines.isEmpty()) {
            return;
        }
        int newIndex = findCurrentLineIndex(adjusted);
        if (newIndex < 0 || newIndex >= lyricLines.size()) {
            newIndex = currentLineIndex >= 0 ? currentLineIndex : 0;
        }
        currentLineIndex = newIndex;
        if (currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
            targetScrollY = currentLineIndex * LINE_SPACING;
        }
        postInvalidate();
    }
    
    /**
     * 获取时间调整偏移量
     */
    public long getTimeAdjustOffset() {
        return timeAdjustOffset;
    }
    
    public void setEnableGesture(boolean enable) {
        this.enableGesture = enable;
    }

    public void setNeonLyricsEnabled(boolean enabled) {
        this.neonLyricsEnabled = enabled;
        invalidate();
    }
    
    /**
     * 设置是否显示背景纹理
     * @param show 是否显示
     */
    public void setShowBackgroundTexture(boolean show) {
        this.showBackgroundTexture = show;
        invalidate();
        LogHelper.d(TAG, "🖼️ 背景纹理显示已" + (show ? "启用" : "禁用"));
    }

    public void setOpaqueBackgroundEnabled(boolean enabled) {
        this.opaqueBackgroundEnabled = enabled;
        if (!randomColorSwitchEnabled) {
            applyReadableStaticColor();
        }
        invalidate();
    }

    public void setRandomColorSwitchEnabled(boolean enabled) {
        if (this.randomColorSwitchEnabled == enabled) {
            return;
        }
        this.randomColorSwitchEnabled = enabled;
        if (enabled) {
            colorTransitionStart = currentTextColor;
            targetTextColor = generateDistinctRandomColor(colorTransitionStart);
            lastColorChangeTime = System.currentTimeMillis();
            colorTransitionProgress = 0f;
        } else {
            applyReadableStaticColor();
        }
        postInvalidateOnAnimation();
    }

    private void applyReadableStaticColor() {
        int readableColor = 0xFFFFFFFF;
        currentTextColor = readableColor;
        targetTextColor = readableColor;
        colorTransitionStart = readableColor;
        colorTransitionProgress = 1f;
        lastColorChangeTime = System.currentTimeMillis();
        if (currentPaint != null) {
            currentPaint.setColor(readableColor);
        }
    }

    /**
     * 切歌与异步拉词期间临时冻结自动滚动，避免空歌词阶段反复触发动画。
     */
    public boolean isTrackLoading() {
        return trackLoading;
    }

    public void setTrackLoading(boolean loading) {
        this.trackLoading = loading;
        if (loading && scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        if (loading) {
            // 切歌加载期间暂时回收硬件层，避免短时叠层。
            setLayerType(LAYER_TYPE_NONE, null);
        } else {
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }
    }

    /**
     * 同步播放状态；暂停时立即停止呼吸动画，恢复播放时再继续。
     */
    public void setPlaybackActive(boolean active) {
        if (this.playbackActive == active) {
            return;
        }
        this.playbackActive = active;
        this.lastPausedInvalidateAtMs = 0L;
        if (active) {
            if (breathingScaleEnabled && !powerSavingModeEnabled) {
                startScaleAnimation();
            }
            postInvalidateOnAnimation();
        } else {
            if (scaleAnimator != null && scaleAnimator.isRunning()) {
                scaleAnimator.cancel();
            }
            currentScale = 1.0f;
            currentDisplacement = 0f;
            postInvalidateOnAnimation();
        }
    }

    private boolean shouldInvalidateForCurrentPlaybackState() {
        return playbackActive;
    }

    public void setEnableShuffleSplitEffect(boolean enable) {
        boolean oldValue = this.enableShuffleSplitEffect;
        this.enableShuffleSplitEffect = enable;
        if (!enable) {
            stableShuffleSignature = "";
            stableShuffleTokens.clear();
            lastShuffleLineCount = 0;
            lastShuffleTokenCount = 0;
            tokenCache.clear();
            pendingTokenTasks.clear();
        }
        if (powerSavingModeEnabled && enable) {
            this.shufflePerformanceGuardEnabled = true;
        }
        notifyShuffleDebugInfo();
        LogHelper.d(TAG, "🌀 分词打乱效果已" + (enable ? "启用" : "禁用") + " (从 " + oldValue + " 改为 " + enable + ")");
        postInvalidate();
        invalidate();
    }

    public void clearTokenizationCache() {
        tokenCache.clear();
        pendingTokenTasks.clear();
        stableShuffleSignature = "";
        stableShuffleTokens.clear();
        lastShuffleLineCount = 0;
        lastShuffleTokenCount = 0;
    }

    public void setBreathingRhythmMs(long durationMs) {
        long clamped = Math.max(BREATHING_RHYTHM_MIN_MS, Math.min(BREATHING_RHYTHM_MAX_MS, durationMs));
        this.baseScaleAnimationDuration = clamped;
        this.currentScaleAnimationDuration = clamped;
        if (breathingScaleEnabled && scaleAnimator != null && scaleAnimator.isRunning()) {
            restartScaleAnimationKeepingPhase();
        }
    }

    public void setBreathingScaleVariance(float variance) {
        float clamped = Math.max(BREATHING_SCALE_VARIANCE_MIN, Math.min(BREATHING_SCALE_VARIANCE_MAX, variance));
        this.breathingScaleVariance = clamped;
        this.breathingScaleMin = 1f - clamped;
        this.breathingScaleMax = 1f + clamped;
        if (breathingScaleEnabled && scaleAnimator != null && scaleAnimator.isRunning()) {
            restartScaleAnimationKeepingPhase();
        }
    }

    public void setBreathingDisplacementStrength(float strength) {
        float clamped = Math.max(BREATHING_DISPLACEMENT_STRENGTH_MIN, Math.min(BREATHING_DISPLACEMENT_STRENGTH_MAX, strength));
        this.breathingDisplacementStrength = clamped;
    }

    public void setColorChangeIntervalMs(long intervalMs) {
        long clamped = Math.max(COLOR_CHANGE_INTERVAL_MIN_MS, Math.min(COLOR_CHANGE_INTERVAL_MAX_MS, intervalMs));
        this.colorChangeIntervalMs = clamped;
        // 调试页改节奏后立即生效，避免保留旧周期造成“跟随慢半拍”。
        long now = System.currentTimeMillis();
        this.lastColorChangeTime = now - clamped;
        this.colorTransitionProgress = 0f;
        postInvalidateOnAnimation();
    }

    public void setShuffleLayoutRebuildIntervalMs(long intervalMs) {
        long clamped = Math.max(0L, Math.min(SHUFFLE_LAYOUT_REBUILD_INTERVAL_MAX_MS, intervalMs));
        this.shuffleLayoutBuildIntervalOverrideMs = clamped;
        this.shuffleLayoutBuildIntervalMs = clamped;
        this.shuffleLastLayoutBuildMs = 0L;
    }

    public void setShuffleSplitMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return;
        this.shuffleSplitMode = mode.trim().toUpperCase();
        if ("WORD".equals(this.shuffleSplitMode)) {
            clearTokenizationCache();
        }
        this.stableShuffleSignature = "";
        this.stableShuffleTokens.clear();
        notifyShuffleDebugInfo();
        postInvalidate();
        invalidate();
    }

    public void setShuffleOnlyCurrentLine(boolean onlyCurrentLine) {
        this.shuffleOnlyCurrentLine = onlyCurrentLine;
        notifyShuffleDebugInfo();
        postInvalidate();
        invalidate();
    }

    public void setOnShuffleDebugInfoListener(OnShuffleDebugInfoListener listener) {
        this.shuffleDebugInfoListener = listener;
        notifyShuffleDebugInfo();
    }

    private void notifyShuffleDebugInfo() {
        if (shuffleDebugInfoListener == null) {
            return;
        }
        shuffleDebugInfoListener.onDebugInfo(
            CURRENT_TEXT_SIZE,
            shuffleSplitScaleVariance,
            shuffleSplitTiltRatio,
            lastShuffleLineCount,
            lastShuffleTokenCount
        );
    }
    
    public void setOnLyricLineClickListener(OnLyricLineClickListener listener) {
        this.lineClickListener = listener;
    }
    
    /**
     * 获取当前行索引
     */
    public int getCurrentLineIndex() {
        return currentLineIndex;
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        if (scaleAnimator != null) {
            scaleAnimator.cancel();
            scaleAnimator = null;
        }
        setLayerType(LAYER_TYPE_NONE, null);
        
        // 清理自动居中定时器，避免内存泄漏
        if (autoCenterHandler != null && autoCenterRunnable != null) {
            autoCenterHandler.removeCallbacks(autoCenterRunnable);
        }
        LyricsWordTokenizer.removeOnTokenizerReadyListener(tokenizerReadyListener);
        tokenCache.clear();
        pendingTokenTasks.clear();
        // 关闭分词线程池，释放后台线程
        tokenizeExecutor.shutdownNow();
    }
    
    
}

