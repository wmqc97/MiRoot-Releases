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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.BlurMaskFilter;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
    
    // 配置参数 - V3.17: 背屏优化
    private float CURRENT_TEXT_SIZE = 72f;        // 当前行字体大小（背屏优化：比普通行大2-3号）
    private float NORMAL_TEXT_SIZE = 60f;         // 普通行字体大小
    private float backgroundTextureSizeScale = 1.3f;  // 底图文本大小倍数（相对于当前行字体）
    private float LINE_SPACING = 160f;             // 行间距（背屏：随字号自动算；主屏横屏可由 setLineSpacing 固定）
    /** true 表示行距由外部 setLineSpacing 固定（如主屏横屏），false 时随字号/翻译行重算 */
    private boolean lineSpacingLockedByExternal = false;
    private static final float LINE_SPACING_MIN_GAP = 28f; // 行与行之间的留白
    private static final float LETTER_SPACING = 2f;            // 字间距（背屏优化：放大1-2px）
    // 随机颜色相关
    private int currentTextColor = 0xFFFF0000;  // 当前文字颜色（红色，默认）
    private int targetTextColor = 0xFFFF0000;  // 目标文字颜色
    private int colorTransitionStart = 0xFFFF0000;  // 颜色过渡起始颜色
    private float colorTransitionProgress = 0f;  // 颜色过渡进度（0-1）
    private long lastColorChangeTime = 0;        // 上次颜色变化时间
    private static final long COLOR_CHANGE_INTERVAL = 1500;  // 颜色变化间隔（1.5秒）
    private long lastColorUpdateTime = 0;        // 上次颜色更新时间（用于节流）
    private static final long COLOR_UPDATE_THROTTLE_MS = 33;  // 颜色更新节流（约30fps，减轻与跑马灯同屏时的 CPU）
    
    private int normalLyricsAlpha = 30;  // 未唱歌词透明度(0-100%)
    private int backgroundTextureAlpha = 20;  // 底图歌词透明度(0-100%)
    
    /**
     * 根据透明度百分比计算颜色值
     */
    private int getNormalLyricsColor() {
        int alpha = (int)(normalLyricsAlpha * 255 / 100f);
        return (alpha << 24) | 0x00FFFFFF;  // ARGB格式，白色
    }
    
    private int getDimLyricsColor() {
        // DIM颜色使用normalLyricsAlpha的2/3
        int alpha = (int)(normalLyricsAlpha * 255 / 100f * 2 / 3);
        return (alpha << 24) | 0x00FFFFFF;  // ARGB格式，白色
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
    private static final int VISIBLE_LINES = 7;                // 可见行数
    private static final float STROKE_WIDTH = 1f;              // 描边宽度(1px)
    private static final int STROKE_COLOR = 0x4D000000;       // 描边颜色(30%透明度黑色)
    private static final float TEXT_OUTLINE_WIDTH = 1.5f;    // 文字轮廓描边宽度(1.5px)
    private static final int TEXT_OUTLINE_COLOR = 0x80000000; // 文字轮廓描边颜色(50%透明度黑色)
    private static final float SCALE_MIN = 0.98f;              // 缩放最小值
    private static final float SCALE_MAX = 1.1f;               // 缩放最大值（从1.02调整为1.1）
    private static final float DISPLACEMENT_OFFSET = 2.5f;     // 微位移偏移(2-3px)
    private static final long WORD_FADE_DURATION = 100;        // 逐字淡入动画时长(0.1s)
    
    // 数据
    private List<EnhancedLRCParser.EnhancedLyricLine> lyricLines = new ArrayList<>();
    private int currentLineIndex = -1;
    private long currentPosition = 0;
    private float scrollY = 0f;
    private float targetScrollY = 0f;
    private long timeAdjustOffset = 0; // 时间调整偏移量（毫秒），V3.16: 已禁用时间快进，设置为0
    
    // 绘图相关
    private TextPaint currentPaint;
    private TextPaint normalPaint;
    private TextPaint dimPaint;
    private TextPaint translationPaint;
    private Paint progressPaint;
    private Paint bgPaint;  // 纯黑背景，避免 onDraw 每帧 new Paint
    private Paint strokePaint;  // 描边画笔
    private TextPaint backgroundTexturePaint;  // V3.17: 背景纹理画笔
    private Paint glowPaint;  // 发光效果画笔
    private TextPaint outlinePaint;  // 文字轮廓描边画笔（用于增强可读性）
    
    // 动画
    private ValueAnimator scrollAnimator;
    private ValueAnimator scaleAnimator;  // 缩放动画（呼吸动画）
    private float currentScale = 1.0f;    // 当前缩放值
    private float currentDisplacement = 0f; // 当前位移值
    private long baseScaleAnimationDuration = 2000;  // 基础缩放动画时长（毫秒）
    private long currentScaleAnimationDuration = 2000;  // 当前缩放动画时长（关联音谱）
    
    
    // 手势
    private GestureDetector gestureDetector;
    private boolean isDragging = false;
    private float lastTouchY = 0f;
    /** 本次按下以来 onScroll 累计位移；超过 touchSlop 则视为滑动，不再在 UP 时当作点按切行 */
    private float tapAccumulatedPanDistance = 0f;
    
    // 用户操作检测和自动居中
    private Handler autoCenterHandler;
    private Runnable autoCenterRunnable;
    private static final long AUTO_CENTER_DELAY_MS = 1500; // 1.5秒后自动居中
    private long lastUserInteractionTime = 0; // 最后用户操作时间
    
    // 配置选项
    private boolean showTranslation = true;     // 是否显示翻译
    private boolean enableWordByWord = true;    // 是否启用逐字高亮
    private boolean showProgress = false;       // V3.17: 禁用进度条显示
    private boolean enableGesture = true;       // 是否启用手势交互
    private boolean showBackgroundTexture = true; // V3.17: 是否显示背景纹理
    private boolean showBackgroundGradient = false; // V3.17: 禁用背景渐变，使用纯黑背景
    
    // 调试 / 节流
    private long lastDrawTime = 0;
    private long lastOffCenterLogTime = 0;
    /** 逐字模式下行内进度刷新：限制 postInvalidate 频率 */
    private long lastLyricProgressInvalidateTime = 0;
    private static final long LYRIC_PROGRESS_INVALIDATE_MS = 33L;
    
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
        translationPaint.setTextSize(NORMAL_TEXT_SIZE * 0.7f);
        translationPaint.setColor(TRANSLATION_COLOR);
        translationPaint.setTextAlign(Paint.Align.CENTER);
        translationPaint.setTypeface(lyricTypeface);
        
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.FILL);

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
        
        // 发光效果画笔（用于文字发光）
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(8f);
        glowPaint.setMaskFilter(new BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL));
        
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
                // 1.5秒后自动居中到当前行
                if (currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
                    float targetScrollY = currentLineIndex * LINE_SPACING;
                    if (Math.abs(scrollY - targetScrollY) > 5f) {
                        animateScroll(scrollY, targetScrollY);
                        LogHelper.d(TAG, "⏰ 用户无操作1.5秒，自动居中到当前行: " + currentLineIndex);
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
    }

    /**
     * 按当前行/普通行/翻译行的字高计算行距，使 scrollY = index * spacing 时各行锚点与字号匹配，当前行几何中心落在屏中心。
     */
    private float computeAutoLineSpacing() {
        Paint.FontMetrics cm = currentPaint.getFontMetrics();
        Paint.FontMetrics nm = normalPaint.getFontMetrics();
        float mainH = Math.max(cm.descent - cm.ascent, nm.descent - nm.ascent);
        if (showTranslation) {
            Paint.FontMetrics tm = translationPaint.getFontMetrics();
            float gap = normalPaint.getTextSize() * 0.12f;
            mainH += gap + (tm.descent - tm.ascent);
        }
        return mainH + LINE_SPACING_MIN_GAP;
    }

    private void applyAutoLineSpacingFromFontIfNeeded() {
        if (lineSpacingLockedByExternal || currentPaint == null || normalPaint == null) {
            return;
        }
        LINE_SPACING = computeAutoLineSpacing();
        syncScrollToCurrentLineAfterSpacingChange();
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
     * 当前行/占位应处的垂直中心（本 View 内坐标）。
     * 父级为「歌名 + 歌词」竖直 LinearLayout 时，整块内容区的竖中线在 parentHeight/2，
     * 换算到歌词区域为 parentHeight/2 - getTop()，即减掉歌名（及歌名上方 margin）占用的空间；
     * 若无法换算则退回本 View 高度的一半。
     */
    private float getLyricsBandVerticalCenterY() {
        int h = getHeight();
        if (h <= 0) {
            return 0f;
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
            return h / 2f;
        }
        return cy;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制深色背景
            drawBackgroundGradient(canvas);
        
        // 更新随机颜色
        updateRandomColor();
        
        if (lyricLines.isEmpty()) {
            // 显示占位文本
            drawPlaceholder(canvas);
            return;
        }
        
        float centerY = getLyricsBandVerticalCenterY();
        // V3.17: 歌词向右偏移100px，和歌名一样
        int centerX = getWidth() / 2 + 100;
        
        // V3.17: 绘制背景歌词纹理（当前歌词的模糊版）- 保留
        if (showBackgroundTexture && currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
            drawBackgroundTexture(canvas, lyricLines.get(currentLineIndex), centerX, centerY);
        }
        
        // 不再强制居中，允许用户自由滚动
        // 用户停止操作1.5秒后会自动居中（由autoCenterRunnable处理）
        
        // 添加绘制调试日志（每秒最多1次）
        long now = System.currentTimeMillis();
        if (now - lastDrawTime > 1000) {
            LogHelper.d(TAG, "🎨 绘制歌词: 行数=" + lyricLines.size() + 
                      ", 当前行=" + currentLineIndex + 
                      ", 位置=" + currentPosition + "ms" +
                      ", scrollY=" + scrollY);
            lastDrawTime = now;
        }
        
        // 绘制每一行歌词（lineAnchorY 为该行主歌词的垂直中心，与字号无关）
        for (int i = 0; i < lyricLines.size(); i++) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);
            
            float lineAnchorY = centerY + (i * LINE_SPACING) - scrollY;
            
            // V3.17: 只绘制可见区域的歌词，只显示三行（当前行、上一行、下一行）
            float distanceFromCenter = Math.abs(lineAnchorY - centerY);
            if (distanceFromCenter > LINE_SPACING * 1.5f) {
                continue;
            }
            
            float halfFade = Math.max(1f, Math.max(centerY, getHeight() - centerY));
            float alpha = 1f - (distanceFromCenter / halfFade);
            alpha = Math.max(0.2f, Math.min(1f, alpha));
            
            if (i == currentLineIndex) {
                if (Math.abs(lineAnchorY - centerY) > 5f && now - lastOffCenterLogTime > 2000L) {
                    lastOffCenterLogTime = now;
                    LogHelper.d(TAG, "📊 当前行锚点偏离中心: lineAnchorY=" + lineAnchorY + ", centerY=" + centerY +
                              ", scrollY=" + scrollY + ", currentLineIndex=" + currentLineIndex);
                }
                drawCurrentLine(canvas, line, centerX, lineAnchorY);
            } else {
                drawNormalLine(canvas, line, centerX, lineAnchorY, alpha);
            }
            
            if (showTranslation && line.translation != null && !line.translation.isEmpty()) {
                TextPaint mainPaint = (i == currentLineIndex) ? currentPaint : normalPaint;
                Paint.FontMetrics mf = mainPaint.getFontMetrics();
                float mainBaseline = verticalCenterToBaseline(mainPaint, lineAnchorY);
                float bottomOfMain = mainBaseline + mf.descent;
                Paint.FontMetrics tf = translationPaint.getFontMetrics();
                float transBaseline = bottomOfMain + mainPaint.getTextSize() * 0.12f - tf.ascent;
                drawTranslation(canvas, line.translation, centerX, transBaseline, alpha);
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
        
        // 在屏幕中心绘制模糊的歌词文本（低透明度）
        canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(1.3f, 1.3f);  // 缩放倍数调整为1.3f
        canvas.translate(-centerX, -centerY);
        
        // 使用低透明度绘制（centerY 为垂直中心，非 baseline）
        int alpha = (int)(backgroundTextureAlpha * 255 / 100f);
        backgroundTexturePaint.setAlpha(alpha);
        float texBaseline = verticalCenterToBaseline(backgroundTexturePaint, centerY);
        canvas.drawText(line.text, centerX, texBaseline, backgroundTexturePaint);
        
        canvas.restore();
    }
    
    /**
     * 绘制占位文本
     */
    private void drawPlaceholder(Canvas canvas) {
        String text = "暂无歌词";
        float bandCenterY = getLyricsBandVerticalCenterY();
        float baseline = verticalCenterToBaseline(normalPaint, bandCenterY);
        float centerX = getWidth() / 2f + 100;
        canvas.drawText(text, centerX, baseline, normalPaint);
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
        if (enableWordByWord) {
            // 逐字高亮模式：开启逐字显示时，无论是否有逐字时间戳，都使用逐字显示模式
            // 如果有逐字时间戳，使用精确的逐字时间戳；如果没有，使用行进度模拟逐字效果
            drawWordByWord(canvas, line, centerX, finalY);
        } else {
            // 整行高亮模式：关闭逐字显示后，当前行完全高亮（progress=1）
            float progress = 1.0f; // 关闭逐字显示时，当前行完全高亮
            drawLineWithProgress(canvas, line.text, centerX, finalY, progress);
        }
        
        // 恢复画布状态
        canvas.restore();
        
        // V3.17: 已禁用进度条显示
        // if (showProgress) {
        //     float progress = calculateLineProgress(line);
        //     drawProgressBar(canvas, centerX, finalY + CURRENT_TEXT_SIZE * 0.8f, progress);
        // }
        
        // V3.17: 启动缩放动画（如果还没有运行）
        if (scaleAnimator == null || !scaleAnimator.isRunning()) {
            startScaleAnimation();
        }
    }
    
    /**
     * 启动缩放动画（微位移+缩放效果）
     * V3.17: 背屏优化 - 0.5倍速缩放动画，从0.98到1.1
     */
    private void startScaleAnimation() {
        if (scaleAnimator != null && scaleAnimator.isRunning()) {
            return;
        }
        
        // 初始化缩放值
        currentScale = SCALE_MIN;
        currentDisplacement = 0f;
        
        // 创建往返动画：0.98 -> 1.1 -> 0.98
        scaleAnimator = ValueAnimator.ofFloat(SCALE_MIN, SCALE_MAX, SCALE_MIN);
        scaleAnimator.setDuration(currentScaleAnimationDuration); // 根据音谱动态调整速度
        scaleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scaleAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        scaleAnimator.addUpdateListener(animation -> {
            currentScale = (float) animation.getAnimatedValue();
            // 同时更新微位移（轻微向上位移2-3px）
            currentDisplacement = (currentScale - 1.0f) * DISPLACEMENT_OFFSET * 2f;
            postInvalidate();
        });
        scaleAnimator.start();
    }
    
    /**
     * 更新呼吸动画速度（根据音乐节奏）
     * 关联音谱，动态调整动画时长以匹配音乐节奏
     */
    private void updateScaleAnimationSpeed() {
        if (scaleAnimator == null || !scaleAnimator.isRunning()) {
            return;
        }
        
        // 获取当前动画进度
        float currentProgress = (Float) scaleAnimator.getAnimatedValue();
        
        // 取消当前动画
        scaleAnimator.cancel();
        
        // 计算新的起始值和结束值（保持连续性）
        float newStart = currentProgress;
        float newEnd = currentProgress >= SCALE_MAX ? SCALE_MIN : SCALE_MAX;
        float newMid = currentProgress < SCALE_MAX ? SCALE_MAX : SCALE_MIN;
        
        // 创建新动画，从当前进度继续
        if (currentProgress < SCALE_MAX) {
            // 正在上升阶段
            scaleAnimator = ValueAnimator.ofFloat(newStart, newMid, SCALE_MIN);
        } else {
            // 正在下降阶段
            scaleAnimator = ValueAnimator.ofFloat(newStart, newMid, SCALE_MAX);
        }
        
        scaleAnimator.setDuration(currentScaleAnimationDuration);
        scaleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scaleAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        scaleAnimator.addUpdateListener(animation -> {
            currentScale = (float) animation.getAnimatedValue();
            // 同时更新微位移（轻微向上位移2-3px）
            currentDisplacement = (currentScale - 1.0f) * DISPLACEMENT_OFFSET * 2f;
            postInvalidate();
        });
        
        scaleAnimator.start();
    }
    
    /**
     * 截断文本以适应显示范围
     * V3.17: 确保文本不超出屏幕，不添加省略号
     */
    private String truncateText(TextPaint paint, String text, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        float textWidth = paint.measureText(text);
        if (textWidth <= maxWidth) {
            return text;
        }
        
        // 文本太长，需要截断（不添加省略号）
        // 二分查找合适的截断位置
        int start = 0;
        int end = text.length();
        String result = text;
        
        while (start < end) {
            int mid = (start + end + 1) / 2;
            String candidate = text.substring(0, mid);
            float candidateWidth = paint.measureText(candidate);
            
            if (candidateWidth <= maxWidth) {
                start = mid;
                result = candidate;
            } else {
                end = mid - 1;
            }
        }
        
        return result;  // 直接返回截断后的文本，不添加省略号
    }
    
    /**
     * 逐字高亮绘制（使用渐变效果，基于小张桌面的实现）
     * V3.17: 背屏优化 - 添加渐变色和描边效果，确保文本不超出显示范围
     */
    private void drawWordByWord(Canvas canvas, EnhancedLRCParser.EnhancedLyricLine line, float centerX, float y) {
        // 计算可用宽度（考虑左右边距）
        float maxRight = getWidth() - 20; // 右边距20像素
        float minLeft = 20; // 左边距20像素
        float availableWidth = maxRight - minLeft;
        
        // 截断文本以适应显示范围
        String displayText = truncateText(currentPaint, line.text, availableWidth);
        
        // 测量文本总宽度
        float textWidth = currentPaint.measureText(displayText);
        
        // 如果文本会超出边界，调整centerX
        float adjustedCenterX = centerX;
        if (centerX + textWidth / 2f > maxRight) {
            adjustedCenterX = maxRight - textWidth / 2f;
        }
        if (centerX - textWidth / 2f < minLeft) {
            adjustedCenterX = minLeft + textWidth / 2f;
        }
        
        // V3.16: 计算基于逐字时间戳的整体进度（类似小张桌面的实现）
        float progress = 0f;
        
        if (line.wordTimestamps != null && !line.wordTimestamps.isEmpty()) {
            // 找到最后一个字的结束时间
            long lastWordEndTime = 0;
            for (EnhancedLRCParser.WordTimestamp word : line.wordTimestamps) {
                if (word.endTime > lastWordEndTime) {
                    lastWordEndTime = word.endTime;
                }
            }
            
            // 如果当前时间在第一个字之前，进度为0
            if (currentPosition < line.wordTimestamps.get(0).startTime) {
                progress = 0f;
            } 
            // 如果当前时间在最后一个字之后，进度为1
            else if (lastWordEndTime > 0 && currentPosition >= lastWordEndTime) {
                progress = 1f;
            } 
            // 否则，根据当前时间计算进度
            else {
                // 找到当前正在播放的字
                long lineStartTime = line.wordTimestamps.get(0).startTime;
                long lineEndTime = lastWordEndTime > 0 ? lastWordEndTime : line.time + 5000; // 默认5秒
                
                long elapsed = currentPosition - lineStartTime;
                long duration = lineEndTime - lineStartTime;
                
                if (duration > 0) {
                    progress = (float) elapsed / (float) duration;
                    progress = Math.max(0f, Math.min(1f, progress));
                } else {
                    progress = 1f;
                }
            }
        } else {
            // 没有逐字时间戳，使用行进度
            progress = calculateLineProgress(line);
        }
        
        // V3.16: 使用LinearGradient实现渐变效果（基于小张桌面的实现）
        // 渐变从0到progress*textWidth，已播放部分高亮，未播放部分普通
        float startX = adjustedCenterX - textWidth / 2f;
        float endX = adjustedCenterX + textWidth / 2f;
        
        if (progress > 0f && progress < 1f) {
            // 使用随机颜色渐变
            LinearGradient colorGradient = new LinearGradient(
                startX, 0, endX, 0,
                new int[]{currentTextColor, currentTextColor},
                new float[]{0f, 1.0f},
                Shader.TileMode.CLAMP
            );
            
            // 创建进度渐变：已播放部分使用随机颜色，未播放部分使用普通色
            int normalColor = getNormalLyricsColor();
            LinearGradient progressGradient = new LinearGradient(
                startX, 0, endX, 0,
                new int[]{currentTextColor, currentTextColor, normalColor, normalColor},
                new float[]{0f, progress, progress, 1.0f},
                Shader.TileMode.CLAMP
            );
            
            TextPaint gradientPaint = new TextPaint(currentPaint);
            gradientPaint.setShader(progressGradient);
            
            // 先绘制发光效果
            glowPaint.setColor(currentTextColor);
            glowPaint.setTextSize(currentPaint.getTextSize());
            glowPaint.setTextAlign(Paint.Align.CENTER);
            glowPaint.setTypeface(currentPaint.getTypeface());
            glowPaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, glowPaint);
            
            // 绘制文字轮廓描边（增强可读性）
            outlinePaint.setTextSize(currentPaint.getTextSize());
            outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, outlinePaint);
            
            // 再绘制文字（随机颜色）
            canvas.drawText(displayText, adjustedCenterX, y, gradientPaint);
        } else if (progress >= 1f) {
            // 全部播放完成，使用随机颜色
            TextPaint colorPaint = new TextPaint(currentPaint);
            colorPaint.setColor(currentTextColor);
            
            // 先绘制发光效果
            glowPaint.setColor(currentTextColor);
            glowPaint.setTextSize(currentPaint.getTextSize());
            glowPaint.setTextAlign(Paint.Align.CENTER);
            glowPaint.setTypeface(currentPaint.getTypeface());
            glowPaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, glowPaint);
            
            // 绘制文字轮廓描边（增强可读性）
            outlinePaint.setTextSize(currentPaint.getTextSize());
            outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, outlinePaint);
            
            // 再绘制文字（随机颜色）
            canvas.drawText(displayText, adjustedCenterX, y, colorPaint);
        } else {
            // 未开始播放，使用普通色
            canvas.drawText(displayText, adjustedCenterX, y, normalPaint);
        }
    }
    
    /**
     * 带进度的行绘制（渐变效果，基于小张桌面的实现）
     * V3.17: 确保文本不超出显示范围
     */
    private void drawLineWithProgress(Canvas canvas, String text, float centerX, float y, float progress) {
        // 计算可用宽度（考虑左右边距）
        float maxRight = getWidth() - 20; // 右边距20像素
        float minLeft = 20; // 左边距20像素
        float availableWidth = maxRight - minLeft;
        
        // 截断文本以适应显示范围
        String displayText = truncateText(currentPaint, text, availableWidth);
        
        // 创建渐变着色器
        float textWidth = currentPaint.measureText(displayText);
        
        // 如果文本会超出边界，调整centerX
        float adjustedCenterX = centerX;
        if (centerX + textWidth / 2f > maxRight) {
            adjustedCenterX = maxRight - textWidth / 2f;
        }
        if (centerX - textWidth / 2f < minLeft) {
            adjustedCenterX = minLeft + textWidth / 2f;
        }
        
        // V3.16: 使用与小张桌面相同的渐变实现
        // 渐变从0到progress*textWidth，已播放部分高亮，未播放部分普通
        float startX = adjustedCenterX - textWidth / 2f;
        float endX = adjustedCenterX + textWidth / 2f;
        
        if (progress > 0f && progress < 1f) {
            // 使用随机颜色渐变
            LinearGradient colorGradient = new LinearGradient(
                startX, 0, endX, 0,
                new int[]{currentTextColor, currentTextColor},
                new float[]{0f, 1.0f},
                Shader.TileMode.CLAMP
            );
            
            // 创建进度渐变：已播放部分使用随机颜色，未播放部分使用普通色
            int normalColor = getNormalLyricsColor();
            LinearGradient progressGradient = new LinearGradient(
                startX, 0, endX, 0,
                new int[]{currentTextColor, currentTextColor, normalColor, normalColor},
                new float[]{0f, progress, progress, 1.0f},
                Shader.TileMode.CLAMP
            );
            
            TextPaint gradientPaint = new TextPaint(currentPaint);
            gradientPaint.setShader(progressGradient);
            
            // 先绘制发光效果
            glowPaint.setColor(currentTextColor);
            glowPaint.setTextSize(currentPaint.getTextSize());
            glowPaint.setTextAlign(Paint.Align.CENTER);
            glowPaint.setTypeface(currentPaint.getTypeface());
            glowPaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, glowPaint);
            
            // 绘制文字轮廓描边（增强可读性）
            outlinePaint.setTextSize(currentPaint.getTextSize());
            outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, outlinePaint);
            
            // 再绘制文字（随机颜色）
            canvas.drawText(displayText, adjustedCenterX, y, gradientPaint);
        } else if (progress >= 1f) {
            // 全部播放完成，使用随机颜色
            TextPaint colorPaint = new TextPaint(currentPaint);
            colorPaint.setColor(currentTextColor);
            
            // 先绘制发光效果
            glowPaint.setColor(currentTextColor);
            glowPaint.setTextSize(currentPaint.getTextSize());
            glowPaint.setTextAlign(Paint.Align.CENTER);
            glowPaint.setTypeface(currentPaint.getTypeface());
            glowPaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, glowPaint);
            
            // 绘制文字轮廓描边（增强可读性）
            outlinePaint.setTextSize(currentPaint.getTextSize());
            outlinePaint.setLetterSpacing(currentPaint.getLetterSpacing());
            canvas.drawText(displayText, adjustedCenterX, y, outlinePaint);
            
            // 再绘制文字（随机颜色）
            canvas.drawText(displayText, adjustedCenterX, y, colorPaint);
        } else {
            // 未开始播放，使用普通色
            canvas.drawText(displayText, adjustedCenterX, y, normalPaint);
        }
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
        
        // 计算可用宽度（考虑左右边距）
        float maxRight = getWidth() - 20; // 右边距20像素
        float minLeft = 20; // 左边距20像素
        float availableWidth = maxRight - minLeft;
        
        // 截断文本以适应显示范围
        String displayText = truncateText(paint, line.text, availableWidth);
        
        // 测量文本总宽度
        float textWidth = paint.measureText(displayText);
        
        // 如果文本会超出边界，调整centerX
        float adjustedCenterX = centerX;
        if (centerX + textWidth / 2f > maxRight) {
            adjustedCenterX = maxRight - textWidth / 2f;
        }
        if (centerX - textWidth / 2f < minLeft) {
            adjustedCenterX = minLeft + textWidth / 2f;
        }
        
        float baseline = verticalCenterToBaseline(paint, lineCenterY);
        canvas.drawText(displayText, adjustedCenterX, baseline, paint);
        
        // 恢复透明度
        paint.setAlpha(255);
    }
    
    /**
     * 绘制翻译
     * V3.17: 确保文本不超出显示范围
     */
    private void drawTranslation(Canvas canvas, String translation, float centerX, float y, float alpha) {
        int alphaInt = (int)(alpha * 255);
        translationPaint.setAlpha(alphaInt);
        
        // 计算可用宽度（考虑左右边距）
        float maxRight = getWidth() - 20; // 右边距20像素
        float minLeft = 20; // 左边距20像素
        float availableWidth = maxRight - minLeft;
        
        // 截断文本以适应显示范围
        String displayText = truncateText(translationPaint, translation, availableWidth);
        
        // 测量文本总宽度
        float textWidth = translationPaint.measureText(displayText);
        
        // 如果文本会超出边界，调整centerX
        float adjustedCenterX = centerX;
        if (centerX + textWidth / 2f > maxRight) {
            adjustedCenterX = maxRight - textWidth / 2f;
        }
        if (centerX - textWidth / 2f < minLeft) {
            adjustedCenterX = minLeft + textWidth / 2f;
        }
        
        canvas.drawText(displayText, adjustedCenterX, y, translationPaint);
        
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
    
    /**
     * 平滑滚动动画
     */
    private void animateScroll(float from, float to) {
        if (scrollAnimator != null && scrollAnimator.isRunning()) {
            scrollAnimator.cancel();
        }
        
        scrollAnimator = ValueAnimator.ofFloat(from, to);
        scrollAnimator.setDuration(SCROLL_ANIMATION_DURATION);
        scrollAnimator.setInterpolator(new DecelerateInterpolator());
        scrollAnimator.addUpdateListener(animation -> {
            scrollY = (float) animation.getAnimatedValue();
            postInvalidate();  // 使用 postInvalidate 确保在任何线程都能触发重绘
        });
        scrollAnimator.start();
        LogHelper.d(TAG, "✅ 滚动动画已启动: " + from + " -> " + to);
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
    
    /**
     * 生成随机颜色（鲜艳的颜色）
     */
    private int generateRandomColor() {
        // 使用 HSV 生成“高饱和 + 高亮度”的随机色，覆盖更广且更鲜艳
        float hue = random.nextFloat() * 360f;                 // 0-360
        float saturation = 0.85f + random.nextFloat() * 0.15f; // 0.85-1.00
        float value = 0.85f + random.nextFloat() * 0.15f;      // 0.85-1.00
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }
    
    // 颜色联动相关
    private boolean colorSyncEnabled = true;  // 是否启用颜色联动（默认启用）
    private ColorSyncCallback colorSyncCallback;  // 颜色同步回调接口
    
    /**
     * 颜色同步回调接口（用于从外部获取颜色）
     */
    public interface ColorSyncCallback {
        int getSyncColor();  // 获取同步颜色
    }
    
    /**
     * 设置颜色同步回调（用于与跑马灯颜色联动）
     */
    public void setColorSyncCallback(ColorSyncCallback callback) {
        this.colorSyncCallback = callback;
    }
    
    /**
     * 启用/禁用颜色联动
     */
    public void setColorSyncEnabled(boolean enabled) {
        this.colorSyncEnabled = enabled;
    }
    
    /**
     * 更新随机颜色（平滑过渡）
     * 如果启用了颜色联动，则使用外部提供的颜色
     */
    private void updateRandomColor() {
        // 节流机制：限制颜色更新频率，避免频繁重绘导致卡顿
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastColorUpdateTime < COLOR_UPDATE_THROTTLE_MS) {
            // 如果距离上次更新不足16ms，直接使用当前颜色，不重新计算
            return;
        }
        lastColorUpdateTime = currentTime;
        
        // 如果启用了颜色联动且有回调，使用外部颜色
        if (colorSyncEnabled && colorSyncCallback != null) {
            int syncColor = colorSyncCallback.getSyncColor();
            // 如果同步颜色与目标颜色不同，更新目标颜色
            if (syncColor != targetTextColor) {
                // 保存当前颜色作为新的起始颜色（用于平滑过渡）
                colorTransitionStart = currentTextColor;
                targetTextColor = syncColor;
                lastColorChangeTime = currentTime;
                colorTransitionProgress = 0f;
            }
        } else {
            // 使用原有的随机颜色生成逻辑
            // 检查是否需要生成新的目标颜色
            if (currentTime - lastColorChangeTime >= COLOR_CHANGE_INTERVAL) {
                // 保存当前颜色作为新的起始颜色
                colorTransitionStart = currentTextColor;
                // 生成新的目标颜色
                targetTextColor = generateRandomColor();
                lastColorChangeTime = currentTime;
                colorTransitionProgress = 0f;
            }
        }
        
        // 计算颜色过渡进度
        long elapsed = currentTime - lastColorChangeTime;
        colorTransitionProgress = Math.min(1.0f, (float) elapsed / COLOR_CHANGE_INTERVAL);
        
        // 从起始颜色平滑过渡到目标颜色
        currentTextColor = interpolateColor(colorTransitionStart, targetTextColor, colorTransitionProgress);
        
        // 更新画笔颜色
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
     * 用户操作时调用，停止操作1.5秒后自动居中
     */
    private void resetAutoCenterTimer() {
        // 更新用户操作时间
        lastUserInteractionTime = System.currentTimeMillis();
        
        // 移除之前的定时器
        if (autoCenterHandler != null && autoCenterRunnable != null) {
            autoCenterHandler.removeCallbacks(autoCenterRunnable);
        }
        
        // 启动新的定时器：1.5秒后自动居中
        if (autoCenterHandler != null && autoCenterRunnable != null) {
            autoCenterHandler.postDelayed(autoCenterRunnable, AUTO_CENTER_DELAY_MS);
        }
    }
    
    /**
     * 检查并执行自动居中（不更新用户操作时间）
     * 在updatePosition中调用，用于实时检查是否需要居中
     */
    private void checkAndAutoCenter() {
        // 检查用户操作状态
        long timeSinceLastInteraction = System.currentTimeMillis() - lastUserInteractionTime;
        
        if (timeSinceLastInteraction > AUTO_CENTER_DELAY_MS) {
            // 用户已经停止操作超过1.5秒，检查是否需要居中
            if (currentLineIndex >= 0 && currentLineIndex < lyricLines.size()) {
                float targetScrollY = currentLineIndex * LINE_SPACING;
                if (Math.abs(scrollY - targetScrollY) > 5f) {
                    // 位置不一致，自动居中到当前行
                    animateScroll(scrollY, targetScrollY);
                    LogHelper.d(TAG, "✅ 用户无操作，自动居中到当前行: " + currentLineIndex);
                }
            }
        }
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
        
        LogHelper.d(TAG, "📝 设置歌词: " + this.lyricLines.size() + " 行");
        if (!this.lyricLines.isEmpty()) {
            LogHelper.d(TAG, "   第一行: " + this.lyricLines.get(0).text);
            LogHelper.d(TAG, "   第一行时间: " + this.lyricLines.get(0).time + "ms");
            
            // 如果有当前播放位置，立即查找并设置当前行
            // 但需要检查位置值是否合理（不超过1小时，即3600000ms）
            if (currentPosition > 0 && currentPosition <= 3600000) {
                int initialIndex = findCurrentLineIndex(currentPosition);
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
        
        postInvalidate();
    }
    
    /**
     * 更新播放位置
     */
    public void updatePosition(long position) {
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
        
        this.currentPosition = position;
        
        // 查找当前行
        int newIndex = findCurrentLineIndex(position);
        
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
                      " (位置: " + position + "ms)");
            int oldIndex = currentLineIndex;
            currentLineIndex = newIndex;
            
            // V3.17: 行切换时重置缩放动画
            if (scaleAnimator != null && scaleAnimator.isRunning()) {
                scaleAnimator.cancel();
            }
            currentScale = 1.0f;
            currentDisplacement = 0f;
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
            // 用户已经停止操作超过1.5秒，检查并居中
            checkAndAutoCenter();
        }
        // 注意：这里不调用resetAutoCenterTimer()，因为updatePosition不是用户操作
        // resetAutoCenterTimer()只在用户真正操作时调用（onTouchEvent、onScroll等）

        // 非逐字模式且当前行未变时，画面仅随呼吸缩放动画刷新，无需随 MediaSession 每次回调 postInvalidate
        long nowMs = System.currentTimeMillis();
        if (lineChanged) {
            lastLyricProgressInvalidateTime = nowMs;
            postInvalidate();
        } else if (enableWordByWord) {
            if (nowMs - lastLyricProgressInvalidateTime >= LYRIC_PROGRESS_INVALIDATE_MS) {
                lastLyricProgressInvalidateTime = nowMs;
                postInvalidate();
            }
        }
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
            
            // 计算这一行的有效结束时间
            long lineEndTime;
            
            // V3.6: 只有当启用逐字模式且该行有逐字时间戳时，才使用逐字模式逻辑
            // 否则，即使有逐字时间戳但未启用逐字模式，也使用非逐字模式（timeAdjustOffset提前显示）
            boolean useWordByWordMode = enableWordByWord && 
                                       line.wordTimestamps != null && 
                                       !line.wordTimestamps.isEmpty();
            
            if (useWordByWordMode) {
                // V3.15: 逐字模式：使用最后一个字的结束时间，确保逐字动画完成才切换
                // 修复提前跳转问题：必须等到最后一个字播放完成才切换到下一行
                long lastWordEndTime = 0;
                long lastWordStartTime = 0;
                
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
                
                // 逐字模式：检查位置是否在当前行的开始和结束时间之间
                // 不使用timeAdjustOffset，确保逐字动画完整播放
                // V3.15: 修复提前跳转：必须等到 position >= lineEndTime 才切换到下一行
                if (position >= line.time && position < lineEndTime) {
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
        long lastLineTime = lyricLines.get(lyricLines.size() - 1).time;
        if (position > lastLineTime + 5000) {
            LogHelper.w(TAG, "⚠ 位置值(" + position + "ms)远大于最后一行时间(" + lastLineTime + "ms)，返回第一行");
            return 0;
        }
        
        // V3.16: 回退逻辑不使用timeAdjustOffset，直接使用position
        // 从后往前查找，找到第一个时间小于等于位置的行
        for (int i = lyricLines.size() - 1; i >= 0; i--) {
            EnhancedLRCParser.EnhancedLyricLine line = lyricLines.get(i);
            
            // 计算行的结束时间
            long lineEndTime;
            if (i + 1 < lyricLines.size()) {
                lineEndTime = lyricLines.get(i + 1).time;
            } else {
                lineEndTime = line.time + 3000; // 默认3秒
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
            animateScroll(scrollY, targetScrollY);
        }
    }
    
    // ========== 配置方法 ==========
    
    public void setShowTranslation(boolean show) {
        this.showTranslation = show;
        applyAutoLineSpacingFromFontIfNeeded();
        invalidate();
    }
    
    public void setEnableWordByWord(boolean enable) {
        boolean oldValue = this.enableWordByWord;
        this.enableWordByWord = enable;
        LogHelper.d(TAG, "🔄 逐字显示已" + (enable ? "启用" : "禁用") + " (从 " + oldValue + " 改为 " + enable + ")");
        
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
        
        // 强制重绘，确保绘制方式立即生效
        // 使用 postInvalidate 确保在 UI 线程中重绘
        postInvalidate();
        
        // 额外触发一次 onDraw 确保立即更新
        invalidate();
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
        if (glowPaint != null) {
            glowPaint.setTypeface(lyricTypeface);
        }
        applyAutoLineSpacingFromFontIfNeeded();
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
            translationPaint.setTextSize(NORMAL_TEXT_SIZE * 0.7f);
        }
        if (strokePaint != null) {
            strokePaint.setTextSize(CURRENT_TEXT_SIZE);
            strokePaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);
        }
        if (backgroundTexturePaint != null) {
            backgroundTexturePaint.setTextSize(CURRENT_TEXT_SIZE * backgroundTextureSizeScale);
        }
        if (outlinePaint != null) {
            outlinePaint.setTextSize(CURRENT_TEXT_SIZE);
            outlinePaint.setLetterSpacing(LETTER_SPACING / CURRENT_TEXT_SIZE);
        }

        applyAutoLineSpacingFromFontIfNeeded();
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
     * @param offset 偏移量（毫秒），正数表示提前，负数表示延后
     */
    public void setTimeAdjustOffset(long offset) {
        this.timeAdjustOffset = offset;
        LogHelper.d(TAG, "⏰ 时间调整偏移量已设置为: " + offset + "ms");
        // 如果当前有歌词，重新计算当前行
        if (!lyricLines.isEmpty() && currentPosition > 0) {
            int newIndex = findCurrentLineIndex(currentPosition);
            if (newIndex != currentLineIndex) {
                currentLineIndex = newIndex;
                targetScrollY = currentLineIndex * LINE_SPACING;
                postInvalidate();
            }
        }
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
    
    /**
     * 设置是否显示背景纹理
     * @param show 是否显示
     */
    public void setShowBackgroundTexture(boolean show) {
        this.showBackgroundTexture = show;
        invalidate();
        LogHelper.d(TAG, "🖼️ 背景纹理显示已" + (show ? "启用" : "禁用"));
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
        
        // 清理自动居中定时器，避免内存泄漏
        if (autoCenterHandler != null && autoCenterRunnable != null) {
            autoCenterHandler.removeCallbacks(autoCenterRunnable);
        }
    }
    
    
}

