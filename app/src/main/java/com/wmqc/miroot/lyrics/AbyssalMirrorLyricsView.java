/*
 * 深渊镜歌词视图
 * 实现跟随传感器旋转的3D深渊镜效果
 */

package com.wmqc.miroot.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Camera;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 深渊镜歌词视图
 * 在ModernLyricsView基础上添加深渊镜效果
 */
public class AbyssalMirrorLyricsView extends View {
    private static final String TAG = "AbyssalMirrorLyrics";
    
    // 传感器管理
    private AbyssalMirrorSensorManager sensorManager;
    
    // 旋转数据
    private float[] eulerDiff = new float[]{0, 0, 0};
    
    // 歌词数据（从ModernLyricsView获取或直接管理）
    private List<EnhancedLRCParser.EnhancedLyricLine> lyricLines = new ArrayList<>();
    private int currentLineIndex = -1;
    
    // 绘制参数
    private float textSize = 72f;
    private int textColor = 0xFFFFFFFF;
    private Typeface typeface;
    
    // 画笔
    private TextPaint textPaint;
    private Paint[] layerPaints = new Paint[3];
    
    // 模糊画笔（用于后层模糊效果）
    private Paint[] blurPaints = new Paint[3];
    
    // 边框画笔（矩形和圆形）- 参考霓虹边框的多层叠加实现
    private Paint rectBorderPaint;  // 矩形主边框
    private Paint rectBorderGlowPaint1;  // 矩形边框外散光效1（最外层）
    private Paint rectBorderGlowPaint2;  // 矩形边框外散光效2（中间层）
    private Paint rectBorderGlowPaint3;  // 矩形边框外散光效3（内层）
    private Paint circleBorderPaint;  // 圆形主边框
    private Paint circleBorderGlowPaint1;  // 圆形边框外散光效1（最外层）
    private Paint circleBorderGlowPaint2;  // 圆形边框外散光效2（中间层）
    private Paint circleBorderGlowPaint3;  // 圆形边框外散光效3（内层）
    
    // Choreographer用于60fps刷新
    private android.view.Choreographer.FrameCallback frameCallback;
    
    // 重绘控制标志
    private boolean needsRedraw = false;  // 是否需要重绘
    private long lastRedrawTime = 0;  // 上次重绘时间
    private static final long MIN_REDRAW_INTERVAL_MS = 16;  // 最小重绘间隔（约60fps）
    
    // 调试日志时间戳
    private long lastRotationLogTime = 0;
    
    // 系统圆角半径
    private float systemCornerRadius = 0f;  // 系统圆角半径
    private static final float DEFAULT_CORNER_RADIUS = 100f;  // 默认圆角半径（参考MarqueeLightView）
    private static final float EDGE_MARGIN = 0f;  // 边缘边距（参考霓虹灯）
    
    // 层配置（参考深渊镜项目：3层实例，Z轴偏移）
    private static final int LAYER_COUNT = 3;
    // 参考项目：textBase=0.8, textOff=0.8，instanceOffsets = [textBase+textOff*2, textBase+textOff*1, textBase+textOff*0]
    // 即：[0.8+0.8*2, 0.8+0.8*1, 0.8+0.8*0] = [2.4, 1.6, 0.8]
    // 但为了更好的视觉效果，调整为更小的偏移
    private static final float TEXT_BASE = 0.8f;
    private static final float TEXT_OFF = 0.8f;
    private static final float[] LAYER_OFFSETS = {
        TEXT_BASE + TEXT_OFF * 2,  // 最远层（背景）
        TEXT_BASE + TEXT_OFF * 1,  // 中间层
        TEXT_BASE + TEXT_OFF * 0   // 最近层（前景）
    };
    
    // 透明度配置：第一层清楚，第二第三层慢慢变淡
    // 从远到近：最远层20%，中间层50%，最近层100%
    // 后层会通过深度雾化进一步衰减
    private static final float[] LAYER_ALPHA = {1.0f, 0.5f, 0.2f};
    
    // 缩放配置（参考项目：不使用缩放，所有层大小相同）
    private static final float[] LAYER_SCALE = {1.0f, 1.0f, 1.0f};
    
    // 深度效果参数（参考项目）
    private static final float FOG_START_Z = 3.0f;  // 参考项目：text_fog_start_z_init = 3.0
    private static final float FOG_K = 1.5f;  // 参考项目：ScreenEdgeFogK = 1.5
    private static final float EYE_Z = 3.0f;  // 参考项目：EyePos = (0, 0, 3)
    private static final float RENDER_PLANE_Z = 1.56f;  // 参考项目：text_render_plane_z_init = 1.56
    
    // 旋转强度（可调节，参考项目使用原始值）
    private float rotationIntensity = 1.0f;  // 使用完整旋转强度
    
    public AbyssalMirrorLyricsView(Context context) {
        super(context);
        init();
    }
    
    public AbyssalMirrorLyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public AbyssalMirrorLyricsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // 初始化传感器管理器
        try {
            sensorManager = new AbyssalMirrorSensorManager(getContext());
            if (sensorManager != null) {
                sensorManager.setOnRotationChangedListener(new AbyssalMirrorSensorManager.OnRotationChangedListener() {
                    @Override
                    public void onRotationChanged(float[] euler) {
                        if (euler != null && euler.length >= 3) {
                            // 检查是否有变化
                            boolean hasChange = false;
                            if (eulerDiff == null || eulerDiff.length < 3) {
                                hasChange = true;
                            } else {
                                hasChange = Math.abs(euler[0] - eulerDiff[0]) > 0.001f ||
                                           Math.abs(euler[1] - eulerDiff[1]) > 0.001f ||
                                           Math.abs(euler[2] - eulerDiff[2]) > 0.001f;
                            }
                            
                            if (hasChange) {
                                eulerDiff = euler.clone();
                                // 添加调试日志（每秒最多1次）
                                long now = System.currentTimeMillis();
                                if (now - lastRotationLogTime > 1000) {
                                    LogHelper.d(TAG, "🔄 旋转数据更新: eulerDiff=[" + 
                                              eulerDiff[0] + "," + eulerDiff[1] + "," + eulerDiff[2] + "]");
                                    lastRotationLogTime = now;
                                }
                                // 标记需要重绘（由Choreographer统一处理，避免频繁重绘）
                                needsRedraw = true;
                            }
                        } else {
                            LogHelper.w(TAG, "⚠️ 旋转数据无效: euler=" + (euler == null ? "null" : "length=" + euler.length));
                        }
                    }
                });
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 初始化传感器管理器失败", e);
            sensorManager = null;
        }
        
        // 初始化字体
        try {
            typeface = Typeface.createFromAsset(getContext().getAssets(), "fonts/MFGeHei-Regular.ttf");
        } catch (Exception e) {
            typeface = Typeface.DEFAULT;
            LogHelper.w(TAG, "⚠️ 加载字体失败，使用默认字体");
        }
        
        // 初始化主画笔
        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(textSize);
        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(typeface);
        
        // 初始化多层画笔（参考项目：所有层使用相同大小）
        for (int i = 0; i < LAYER_COUNT; i++) {
            layerPaints[i] = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            layerPaints[i].setTextSize(textSize); // 所有层使用相同大小
            layerPaints[i].setColor(textColor);
            layerPaints[i].setTextAlign(Paint.Align.CENTER);
            layerPaints[i].setTypeface(typeface);
            // 透明度在绘制时动态设置
            
            // 初始化模糊画笔（后层需要模糊效果）
            blurPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            blurPaints[i].setTextSize(textSize);
            blurPaints[i].setColor(textColor);
            blurPaints[i].setTextAlign(Paint.Align.CENTER);
            blurPaints[i].setTypeface(typeface);
            // 根据层索引设置不同的模糊半径（越远的层越模糊）
            // 参考项目：后层应该有轻微的模糊效果，但不要太模糊
            // 优化：减小模糊度，使后层更清晰可见
            float blurRadius = i == 2 ? 8.0f : (i == 1 ? 4.0f : 0f); // 最远层8px（减小），中间层4px（减小），最近层不模糊
            if (blurRadius > 0) {
                blurPaints[i].setMaskFilter(new android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
        }
        
        // 初始化矩形边框画笔（参考霓虹边框的多层叠加实现）
        // 参考MarqueeLightView：NEON_BORDER_WIDTH = 3.5f，但为了在深渊镜中更明显，使用稍大的值
        float borderWidth = 4f;  // 主边框宽度（参考霓虹灯3.5f，稍大一点）
        
        // 矩形主边框
        rectBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectBorderPaint.setStyle(Paint.Style.STROKE);
        rectBorderPaint.setStrokeWidth(borderWidth);
        rectBorderPaint.setColor(0xFFFFFFFF); // 白色边框
        rectBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        rectBorderPaint.setStrokeJoin(Paint.Join.ROUND);
        
        // 矩形边框外散光效1（最外层，最淡、最宽、最模糊）- 参考霓虹边框
        rectBorderGlowPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectBorderGlowPaint1.setStyle(Paint.Style.STROKE);
        rectBorderGlowPaint1.setStrokeWidth(borderWidth * 8f);
        rectBorderGlowPaint1.setColor(0xFFFFFFFF);
        rectBorderGlowPaint1.setStrokeCap(Paint.Cap.ROUND);
        rectBorderGlowPaint1.setStrokeJoin(Paint.Join.ROUND);
        rectBorderGlowPaint1.setMaskFilter(new android.graphics.BlurMaskFilter(25f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        
        // 矩形边框外散光效2（中间层）
        rectBorderGlowPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectBorderGlowPaint2.setStyle(Paint.Style.STROKE);
        rectBorderGlowPaint2.setStrokeWidth(borderWidth * 5f);
        rectBorderGlowPaint2.setColor(0xFFFFFFFF);
        rectBorderGlowPaint2.setStrokeCap(Paint.Cap.ROUND);
        rectBorderGlowPaint2.setStrokeJoin(Paint.Join.ROUND);
        rectBorderGlowPaint2.setMaskFilter(new android.graphics.BlurMaskFilter(15f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        
        // 矩形边框外散光效3（内层）
        rectBorderGlowPaint3 = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectBorderGlowPaint3.setStyle(Paint.Style.STROKE);
        rectBorderGlowPaint3.setStrokeWidth(borderWidth * 3f);
        rectBorderGlowPaint3.setColor(0xFFFFFFFF);
        rectBorderGlowPaint3.setStrokeCap(Paint.Cap.ROUND);
        rectBorderGlowPaint3.setStrokeJoin(Paint.Join.ROUND);
        rectBorderGlowPaint3.setMaskFilter(new android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        
        // 初始化圆形边框画笔（参考霓虹边框的多层叠加实现）
        // 圆形主边框
        circleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circleBorderPaint.setStyle(Paint.Style.STROKE);
        circleBorderPaint.setStrokeWidth(borderWidth);
        circleBorderPaint.setColor(0xFFFFFFFF); // 白色边框
        circleBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        circleBorderPaint.setStrokeJoin(Paint.Join.ROUND);
        
        // 圆形边框外散光效1（最外层，最淡、最宽、最模糊）- 参考霓虹边框
        circleBorderGlowPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
        circleBorderGlowPaint1.setStyle(Paint.Style.STROKE);
        circleBorderGlowPaint1.setStrokeWidth(borderWidth * 8f);
        circleBorderGlowPaint1.setColor(0xFFFFFFFF);
        circleBorderGlowPaint1.setStrokeCap(Paint.Cap.ROUND);
        circleBorderGlowPaint1.setStrokeJoin(Paint.Join.ROUND);
        circleBorderGlowPaint1.setMaskFilter(new android.graphics.BlurMaskFilter(25f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        
        // 圆形边框外散光效2（中间层）
        circleBorderGlowPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        circleBorderGlowPaint2.setStyle(Paint.Style.STROKE);
        circleBorderGlowPaint2.setStrokeWidth(borderWidth * 5f);
        circleBorderGlowPaint2.setColor(0xFFFFFFFF);
        circleBorderGlowPaint2.setStrokeCap(Paint.Cap.ROUND);
        circleBorderGlowPaint2.setStrokeJoin(Paint.Join.ROUND);
        circleBorderGlowPaint2.setMaskFilter(new android.graphics.BlurMaskFilter(15f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        
        // 圆形边框外散光效3（内层）
        circleBorderGlowPaint3 = new Paint(Paint.ANTI_ALIAS_FLAG);
        circleBorderGlowPaint3.setStyle(Paint.Style.STROKE);
        circleBorderGlowPaint3.setStrokeWidth(borderWidth * 3f);
        circleBorderGlowPaint3.setColor(0xFFFFFFFF);
        circleBorderGlowPaint3.setStrokeCap(Paint.Cap.ROUND);
        circleBorderGlowPaint3.setStrokeJoin(Paint.Join.ROUND);
        circleBorderGlowPaint3.setMaskFilter(new android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        
        // 启用硬件加速
        setLayerType(LAYER_TYPE_HARDWARE, null);
        
        // 初始化Choreographer回调（60fps刷新，16.66ms）
        // 优化：只在有实际变化时才重绘，避免不必要的闪烁
        frameCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long currentTime = System.currentTimeMillis();
                
                // 只在需要重绘且距离上次重绘时间足够长时才重绘
                if (needsRedraw && (currentTime - lastRedrawTime >= MIN_REDRAW_INTERVAL_MS)) {
                    postInvalidateOnAnimation();
                    needsRedraw = false;  // 重置标志
                    lastRedrawTime = currentTime;
                }
                
                // 继续下一帧（无论是否重绘，都继续监听）
                android.view.Choreographer.getInstance().postFrameCallback(this);
            }
        };
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 检测系统圆角半径
        detectSystemCornerRadius();
        // 注意：边框路径在drawBorders中基于屏幕尺寸动态计算，不在这里计算
        // 视图尺寸变化时需要重绘
        needsRedraw = true;
    }
    
    /**
     * 公共方法：启动传感器监听
     * 用于在onResume等场景中确保传感器被启动
     */
    public void startSensor() {
        if (sensorManager != null) {
            try {
                sensorManager.start();
                LogHelper.d(TAG, "✅ 传感器已启动（手动调用）");
                // 验证传感器是否真的启动了
                float[] currentEuler = sensorManager.getEulerDiff();
                if (currentEuler != null) {
                    LogHelper.d(TAG, "✅ 传感器当前旋转数据: [" + currentEuler[0] + "," + currentEuler[1] + "," + currentEuler[2] + "]");
                }
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 启动传感器失败", e);
            }
        } else {
            LogHelper.w(TAG, "⚠️ 传感器管理器为null，无法启动传感器");
        }
        // 启动Choreographer回调（60fps刷新）
        if (frameCallback != null) {
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
            LogHelper.d(TAG, "✅ Choreographer已启动（60fps）");
        } else {
            LogHelper.w(TAG, "⚠️ frameCallback为null，无法启动Choreographer");
        }
    }
    
    /**
     * 公共方法：停止传感器监听
     * 用于在onPause等场景中停止传感器
     */
    public void stopSensor() {
        // 停止传感器监听
        if (sensorManager != null) {
            try {
                sensorManager.stop();
                LogHelper.d(TAG, "✅ 传感器已停止（手动调用）");
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 停止传感器失败", e);
            }
        }
        // 停止Choreographer回调
        if (frameCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
            LogHelper.d(TAG, "✅ Choreographer已停止");
        }
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 标记需要初始重绘
        needsRedraw = true;
        // 开始传感器监听
        startSensor();
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 停止传感器监听
        stopSensor();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制黑色背景（参考项目：纯黑色背景）
        canvas.drawColor(0xFF000000);
        
        // 只显示当前行歌词（参考项目：只显示时间，这里只显示当前行）
        if (lyricLines.isEmpty() || currentLineIndex < 0 || currentLineIndex >= lyricLines.size()) {
            // 显示占位文本
            LogHelper.d(TAG, "⚠️ 歌词为空或索引无效: 行数=" + lyricLines.size() + ", 当前索引=" + currentLineIndex);
            drawPlaceholder(canvas);
            drawBorders(canvas); // 即使没有歌词也绘制边框
            return;
        }
        
        // 获取当前歌词（只显示一行）
        EnhancedLRCParser.EnhancedLyricLine currentLine = lyricLines.get(currentLineIndex);
        String lyricText = currentLine != null ? currentLine.text : "";
        
        if (lyricText == null || lyricText.isEmpty()) {
            LogHelper.d(TAG, "⚠️ 当前行歌词文本为空");
            drawPlaceholder(canvas);
            drawBorders(canvas); // 即使没有歌词也绘制边框
            return;
        }
        
        // 添加调试日志（每秒最多1次）
        long now = System.currentTimeMillis();
        if (now - lastRotationLogTime > 1000) {
            LogHelper.d(TAG, "✅ 绘制歌词: " + lyricText + ", 索引=" + currentLineIndex + ", eulerDiff=[" + 
                      (eulerDiff != null && eulerDiff.length >= 3 ? 
                       eulerDiff[0] + "," + eulerDiff[1] + "," + eulerDiff[2] : "null") + "]");
            lastRotationLogTime = now;
        }
        
        // 绘制深渊镜效果（3层实例渲染）
        drawAbyssalMirrorLayers(canvas, lyricText);
        
        // 绘制边框装饰（矩形和圆形）
        drawBorders(canvas);
    }
    
    /**
     * 绘制深渊镜多层效果
     * 严格按照参考项目shader逻辑实现：
     * vertex shader (abyssal_mirror_text_vert.glsl):
     *   eye = rotateEuler(EyePos, CameraVectorRotation)
     *   ray_dir = normalize(world_p - eye)
     *   denom = dot(vec3(0,0,1), ray_dir)
     *   t = dot(vec3(0,0,0.01) - eye, vec3(0,0,1)) / denom
     *   plane_p = eye + t * ray_dir
     *   plane_p.x *= uResolution.y / uResolution.x
     *   gl_Position = vec4(plane_p, 1)
     * 
     * fragment shader (abyssal_mirror_text_frag.glsl):
     *   l = clamp(dot(NORMAL, -VRayDir), 0.0, 1.0) * LightIntensity + 0.1
     *   fogAmount = 1.0 - exp(-(VDepth-(camZ-1.0))*k)
     *   VDepth = plane_p.z - world_p.z
     */
    private void drawAbyssalMirrorLayers(Canvas canvas, String text) {
        if (getWidth() == 0 || getHeight() == 0) {
            return; // 视图尺寸未确定，跳过绘制
        }
        
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        // 参考项目：uResolution = (viewWidth, viewHeight)
        // aspectRatio = uResolution.y / uResolution.x = viewHeight / viewWidth
        float aspectRatio = viewHeight / viewWidth;
        
        // 确保eulerDiff有效
        if (eulerDiff == null || eulerDiff.length < 3) {
            eulerDiff = new float[]{0, 0, 0};
            LogHelper.w(TAG, "⚠️ eulerDiff无效，使用默认值[0,0,0]");
        }
        
        // 如果传感器管理器存在，尝试获取最新的旋转数据（作为备用）
        if (sensorManager != null) {
            try {
                float[] latestEuler = sensorManager.getEulerDiff();
                if (latestEuler != null && latestEuler.length >= 3) {
                    // 检查是否有变化（避免不必要的更新）
                    if (Math.abs(latestEuler[0] - eulerDiff[0]) > 0.001f ||
                        Math.abs(latestEuler[1] - eulerDiff[1]) > 0.001f ||
                        Math.abs(latestEuler[2] - eulerDiff[2]) > 0.001f) {
                        eulerDiff = latestEuler.clone();
                    }
                }
            } catch (Exception e) {
                LogHelper.w(TAG, "⚠️ 获取传感器数据失败", e);
            }
        }
        
        // 参考项目：EyePos = vec3(0, 0, 3)
        float[] eyePos = new float[]{0, 0, EYE_Z};
        
        // 参考项目：从后往前绘制（最远层先绘制）
        // 绘制顺序：i=2(最远) -> i=1(中间) -> i=0(最近)
        // 参考项目：instanceOffsets = [textBase+textOff*2, textBase+textOff*1, textBase+textOff*0]
        // 即：[0.8+0.8*2, 0.8+0.8*1, 0.8+0.8*0] = [2.4, 1.6, 0.8]
        for (int i = LAYER_COUNT - 1; i >= 0; i--) {
            canvas.save();
            
            try {
                // 参考项目：Z轴偏移通过instanceOffsets控制
                // world_p.z = (uModelMatrix * vec4(...)).z - RenderPlaneZ - instanceOffsets[gl_InstanceID]
                float offsetZ = LAYER_OFFSETS[i];
                
                // 参考项目shader：计算3D投影
                // 1. 旋转eye位置：eye = rotateEuler(EyePos, CameraVectorRotation)
                float[] rotatedEye = rotateEuler(eyePos, eulerDiff);
                
                // 2. 计算world_p（文本在3D空间中的位置）
                // 参考项目：textTf.setLocalPosition(0.7, 0, 0) - 这是模型空间坐标
                // 在shader中，经过uModelMatrix变换后，文本中心在世界空间的位置
                // 参考项目：RenderPlaneZ = text_render_plane_z_init = 1.56
                // world_p.z = RenderPlaneZ - instanceOffsets = 1.56 - offsetZ
                float worldZ = RENDER_PLANE_Z - offsetZ;
                // 参考项目：文本在X=0.7位置（模型空间），但经过变换后可能不同
                // 为了简化，假设文本中心在世界空间(0, 0, worldZ)
                float[] worldP = new float[]{0, 0, worldZ};
                
                // 3. 计算射线方向：ray_dir = normalize(world_p - eye)
                // 严格按照shader逻辑
                float[] rayDir = new float[]{
                    worldP[0] - rotatedEye[0],
                    worldP[1] - rotatedEye[1],
                    worldP[2] - rotatedEye[2]
                };
                float rayLength = (float) Math.sqrt(
                    rayDir[0] * rayDir[0] + 
                    rayDir[1] * rayDir[1] + 
                    rayDir[2] * rayDir[2]
                );
                if (rayLength > 0.0001f) {
                    rayDir[0] /= rayLength;
                    rayDir[1] /= rayLength;
                    rayDir[2] /= rayLength;
                } else {
                    // 如果射线长度为0，使用默认方向
                    rayDir = new float[]{0, 0, 1};
                }
                
                // 4. 计算射线与平面的交点（严格按照shader公式）
                // 参考项目shader：
                //   denom = dot(vec3(0.0, 0.0, 1.0), ray_dir)
                //   t = dot(vec3(0.0, 0.0, 0.01) - eye, vec3(0.0, 0.0, 1.0)) / denom
                float[] planeNormal = new float[]{0, 0, 1}; // vec3(0, 0, 1)
                float denom = planeNormal[0] * rayDir[0] + planeNormal[1] * rayDir[1] + planeNormal[2] * rayDir[2];
                
                float t = 0;
                if (Math.abs(denom) > 0.0001f) {
                    // 平面点：vec3(0, 0, 0.01)
                    float[] planePoint = new float[]{0, 0, 0.01f};
                    float[] toPlane = new float[]{
                        planePoint[0] - rotatedEye[0],
                        planePoint[1] - rotatedEye[1],
                        planePoint[2] - rotatedEye[2]
                    };
                    // numerator = dot(toPlane, planeNormal)
                    float numerator = toPlane[0] * planeNormal[0] + toPlane[1] * planeNormal[1] + toPlane[2] * planeNormal[2];
                    t = numerator / denom;
                }
                
                // 5. 计算投影点：plane_p = eye + t * ray_dir
                float[] planeP = new float[]{
                    rotatedEye[0] + t * rayDir[0],
                    rotatedEye[1] + t * rayDir[1],
                    rotatedEye[2] + t * rayDir[2]
                };
                
                // 6. 应用宽高比调整：plane_p.x *= uResolution.y / uResolution.x
                planeP[0] *= aspectRatio;
                
                // 7. 将NDC坐标转换为屏幕坐标
                // 参考项目：gl_Position = vec4(plane_p, 1)
                // 在OpenGL中，gl_Position的xy分量是NDC坐标（-1到1），会自动映射到屏幕
                // 在Canvas中，需要手动映射：
                //   screenX = centerX + planeP[0] * (viewWidth / 2)
                //   screenY = centerY - planeP[1] * (viewHeight / 2)  // Y轴翻转
                float screenX = centerX + planeP[0] * (viewWidth / 2.0f);
                float screenY = centerY - planeP[1] * (viewHeight / 2.0f); // Y轴翻转（OpenGL Y向上，Canvas Y向下）
                
                // 8. 计算深度：VDepth = plane_p.z - world_p.z
                float vDepth = planeP[2] - worldZ;
                
                // 9. 参考项目fragment shader：光照强度计算
                // l = clamp(dot(NORMAL, -VRayDir), 0.0, 1.0) * LightIntensity + 0.1
                // NORMAL = vec3(0, 0, 1)（文本法向量指向相机）
                float[] normal = new float[]{0, 0, 1};
                float dotProduct = normal[0] * (-rayDir[0]) + normal[1] * (-rayDir[1]) + normal[2] * (-rayDir[2]);
                dotProduct = Math.max(0, Math.min(1, dotProduct));
                
                float lightIntensity = 1.5f; // 参考项目：text_lightness_init = 1.5
                float brightness = dotProduct * lightIntensity + 0.1f;
                brightness = Math.max(0.1f, Math.min(1.0f, brightness));
                
                // 10. 参考项目fragment shader：雾化效果
                // fogAmount = 1.0 - exp(-(VDepth-(camZ-1.0))*k)
                // 其中 camZ = EYE_Z = 3.0, k = FOG_K = 1.5
                float fogAmount = 1.0f - (float)Math.exp(-(vDepth - (EYE_Z - 1.0f)) * FOG_K);
                fogAmount = Math.max(0, Math.min(1, fogAmount));
                
                // 11. 应用雾化效果（颜色变暗）
                // 参考项目：fogColor = vec3(0.0, 0.0, 0.0)（黑色雾）
                int originalColor = textColor;
                int r = (originalColor >> 16) & 0xFF;
                int g = (originalColor >> 8) & 0xFF;
                int b = originalColor & 0xFF;
                // 混合黑色雾
                r = (int) (r * (1.0f - fogAmount));
                g = (int) (g * (1.0f - fogAmount));
                b = (int) (b * (1.0f - fogAmount));
                r = Math.min(255, r);
                g = Math.min(255, g);
                b = Math.min(255, b);
                
                // 12. 应用亮度
                r = (int) (r * brightness);
                g = (int) (g * brightness);
                b = (int) (b * brightness);
                r = Math.min(255, r);
                g = Math.min(255, g);
                b = Math.min(255, b);
                
                int finalColor = (originalColor & 0xFF000000) | (r << 16) | (g << 8) | b;
                
                // 13. 根据层索引计算最终透明度
                // 参考项目：后层应该逐渐变透明
                float baseAlpha = LAYER_ALPHA[i];
                // 后层（i > 0）应用额外的深度衰减
                if (i > 0) {
                    // 深度越大，透明度越低
                    baseAlpha *= (1.0f - fogAmount * 0.5f); // 深度雾化最多衰减50%
                }
                int alpha = (int) (baseAlpha * 255);
                alpha = Math.max(0, Math.min(255, alpha));
                
                // 14. 绘制文本（使用计算出的屏幕坐标）
                // 注意：drawText的y坐标是基线位置，需要调整
                Paint.FontMetrics fm = layerPaints[i].getFontMetrics();
                float textY = screenY - (fm.ascent + fm.descent) / 2; // 垂直居中
                
                // 对于后层（i > 0），只绘制模糊版本，不绘制清晰版本
                // 这样后层会逐渐模糊隐藏
                if (i > 0 && blurPaints[i] != null && blurPaints[i].getMaskFilter() != null) {
                    // 只绘制模糊版本（后层逐渐模糊隐藏）
                    blurPaints[i].setColor(finalColor);
                    blurPaints[i].setAlpha(alpha);
                    canvas.drawText(text, screenX, textY, blurPaints[i]);
                } else {
                    // 最近层（i == 0）绘制清晰版本
                    layerPaints[i].setColor(finalColor);
                    layerPaints[i].setAlpha(alpha);
                    canvas.drawText(text, screenX, textY, layerPaints[i]);
                }
                
                // 恢复原始颜色和透明度（避免影响下一层）
                layerPaints[i].setColor(originalColor);
                layerPaints[i].setAlpha(255);
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 绘制层失败: " + i, e);
            } finally {
                canvas.restore();
            }
        }
    }
    
    /**
     * 绘制边框装饰（矩形和圆形）
     * 参考深渊镜主题：rect光圈和circle光圈，应用深渊镜3D效果
     * 
     * 修正：边框路径基于当前 View 的 getWidth()/getHeight() 计算，确保与 Canvas 坐标系一致，
     * 避免旧逻辑使用 DisplayInfo/根视图/getLocationOnScreen 等导致的尺寸错位、不贴边问题。
     */
    private void drawBorders(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        
        // 确保 eulerDiff 有效
        if (eulerDiff == null || eulerDiff.length < 3) {
            eulerDiff = new float[]{0, 0, 0};
        }
        
        // 边框路径严格使用当前 View 的宽高，与 Canvas 坐标系一致，避免错位
        float borderWidth = 4f;
        Path screenBorderPath = calculateBorderPath(viewWidth, viewHeight, borderWidth);
        float viewAspectRatio = viewHeight / viewWidth;
        float drawCenterX = viewWidth / 2.0f;
        float drawCenterY = viewHeight / 2.0f;
        
        drawRectBorders(canvas, screenBorderPath, drawCenterX, drawCenterY, viewWidth, viewHeight, viewAspectRatio);
    }
    
    /**
     * 检测系统圆角半径（参考MarqueeLightView的实现）
     */
    private void detectSystemCornerRadius() {
        try {
            // 尝试使用Android S (API 31+) 的WindowInsets获取圆角信息
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.view.WindowInsets rootWindowInsets = getRootWindowInsets();
                if (rootWindowInsets != null) {
                    try {
                        java.lang.reflect.Method method = rootWindowInsets.getClass().getMethod("getRoundedCorners");
                        Object result = method.invoke(rootWindowInsets);
                        if (result != null && result instanceof java.util.List) {
                            @SuppressWarnings("unchecked")
                            java.util.List<Object> roundedCorners = (java.util.List<Object>) result;
                            if (!roundedCorners.isEmpty()) {
                                Object corner = roundedCorners.get(0);
                                if (corner != null) {
                                    java.lang.reflect.Method getRadiusMethod = corner.getClass().getMethod("getRadius");
                                    Object radiusObj = getRadiusMethod.invoke(corner);
                                    if (radiusObj instanceof Integer) {
                                        int radius = (Integer) radiusObj;
                                        if (radius > 0) {
                                            systemCornerRadius = radius;
                                            LogHelper.d(TAG, "✅ 检测到系统圆角半径: " + systemCornerRadius + "px");
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LogHelper.w(TAG, "⚠️ 反射调用getRoundedCorners失败: " + e.getMessage());
                    }
                }
            }
            
            // 如果无法获取，使用默认值
            systemCornerRadius = DEFAULT_CORNER_RADIUS;
            LogHelper.d(TAG, "⚠️ 无法检测系统圆角，使用默认值: " + systemCornerRadius + "px");
        } catch (Exception e) {
            systemCornerRadius = DEFAULT_CORNER_RADIUS;
            LogHelper.w(TAG, "⚠️ 检测系统圆角失败，使用默认值: " + systemCornerRadius + "px");
        }
    }
    
    /**
     * 计算圆角矩形边框路径（第一层对齐屏幕外围，976x596px）
     * 第一层需要减去自身宽度，确保边框贴着屏幕外围
     */
    private Path calculateBorderPath(float width, float height, float borderWidth) {
        Path path = new Path();
        
        // 第一层需要贴着外围，但需要减去自身宽度
        // STROKE模式会从路径中心向外绘制，所以路径需要向内偏移线条宽度的一半
        // 这样线条中心会在屏幕边缘，线条会有一半在屏幕内，一半在屏幕外
        // 为了确保边框贴着屏幕外围，使用 borderWidth / 2f 作为偏移
        // 参考MarqueeLightView的实现，使用更小的偏移让边框更贴边
        float strokeOffset = borderWidth / 2f - 0.5f;  // 减小0.5px，让边框更贴边
        
        // 使用完整尺寸，减去自身宽度，确保边框贴着屏幕外围（976x596px）
        float left = strokeOffset;
        float top = strokeOffset;
        float right = width - strokeOffset;
        float bottom = height - strokeOffset;
        
        // V3.17: 使用RectF和圆角矩形路径，适配系统圆角
        // 使用检测到的系统圆角半径，确保圆角半径不超过可用空间
        float maxRadius = Math.min(right - left, bottom - top) / 2f;
        float actualRadius = Math.min(systemCornerRadius, maxRadius);
        // 微调：如果实际半径接近最大半径，使用最大半径让圆角更贴边
        if (actualRadius > maxRadius * 0.9f) {
            actualRadius = maxRadius;
        }
        
        // 如果系统圆角半径未检测到或为0，使用默认值
        if (systemCornerRadius <= 0) {
            actualRadius = Math.min(DEFAULT_CORNER_RADIUS, maxRadius);
        }
        
        // 确保圆角半径不会导致路径无效
        if (actualRadius < 0) {
            actualRadius = 0;
        }
        
        RectF rect = new RectF(left, top, right, bottom);
        
        // 创建圆角矩形路径（顺时针方向）
        path.addRoundRect(rect, actualRadius, actualRadius, Path.Direction.CW);
        
        LogHelper.d(TAG, "✅ 计算边框路径: 尺寸=" + width + "x" + height +
                  ", strokeOffset=" + strokeOffset + ", rect=[" + left + "," + top + "," + right + "," + bottom + "], actualRadius=" + actualRadius);
        
        return path;
    }
    
    /**
     * 计算向内缩进的圆角矩形边框路径
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param borderWidth 边框宽度
     * @param inset 向内缩进的距离（像素，已经包含基础偏移）
     */
    private Path calculateBorderPathWithInset(float width, float height, float borderWidth, float inset) {
        Path path = new Path();
        
        // inset已经包含了基础偏移（borderWidth/2），直接使用
        float strokeOffset = inset;
        
        // 使用完整尺寸，向内缩进
        float left = strokeOffset;
        float top = strokeOffset;
        float right = width - strokeOffset;
        float bottom = height - strokeOffset;
        
        // 确保矩形有效
        if (right <= left || bottom <= top) {
            // 如果缩进过大，使用最小尺寸（borderWidth/2）
            float minOffset = borderWidth / 2f;
            left = minOffset;
            top = minOffset;
            right = width - minOffset;
            bottom = height - minOffset;
        }
        
        // V3.17: 使用RectF和圆角矩形路径，适配系统圆角
        float maxRadius = Math.min(right - left, bottom - top) / 2f;
        float actualRadius = Math.min(systemCornerRadius, maxRadius);
        if (actualRadius > maxRadius * 0.9f) {
            actualRadius = maxRadius;
        }
        if (systemCornerRadius <= 0) {
            actualRadius = Math.min(DEFAULT_CORNER_RADIUS, maxRadius);
        }
        if (actualRadius < 0) {
            actualRadius = 0;
        }
        
        RectF rect = new RectF(left, top, right, bottom);
        path.addRoundRect(rect, actualRadius, actualRadius, Path.Direction.CW);
        
        return path;
    }
    
    /**
     * 绘制矩形边框（使用圆角矩形路径，应用深渊镜3D效果）
     * 为边框创建多层，每层应用3D投影，实现深渊镜效果
     */
    private void drawRectBorders(Canvas canvas, Path screenBorderPath, 
                                 float centerX, float centerY, 
                                 float screenWidth, float screenHeight, float aspectRatio) {
        // 如果路径未计算，直接返回
        if (screenBorderPath == null) {
            return;
        }
        
        // 确保eulerDiff有效
        if (eulerDiff == null || eulerDiff.length < 3) {
            eulerDiff = new float[]{0, 0, 0};
        }
        
        // 边框使用4层，所有层使用相同的尺寸，通过3D投影的Z轴偏移产生层次感
        // 边框宽度（与calculateBorderPath中使用的相同）
        float borderWidth = 4f;  // 主边框宽度
        // 第一层的基础偏移（减去自身宽度，确保边框贴着屏幕外围）
        float baseInset = borderWidth / 2f;
        int borderLayerCount = 4;
        float[] borderLayerOffsets = {
            TEXT_BASE + TEXT_OFF * 3,  // 最远层（第4层）
            TEXT_BASE + TEXT_OFF * 2,  // 第3层
            TEXT_BASE + TEXT_OFF * 1,  // 第2层
            0f                          // 第1层：Z轴偏移为0，不应用3D投影，确保对齐屏幕外围
        };
        float[] borderLayerAlpha = {0.15f, 0.3f, 0.6f, 1.0f};  // 从远到近：15%, 30%, 60%, 100%
        
        // 计算路径的实际边界，用于调试和验证
        android.graphics.RectF pathBounds = new android.graphics.RectF();
        screenBorderPath.computeBounds(pathBounds, true);
        
        // 视图中心（用于NDC坐标转换）
        // 路径是基于screenWidth和screenHeight创建的，所以使用这些值作为NDC转换的基准
        float viewCenterX = screenWidth / 2.0f;
        float viewCenterY = screenHeight / 2.0f;
        
        LogHelper.d(TAG, "🎨 边框3D投影: 视图尺寸=" + screenWidth + "x" + screenHeight + 
                  ", 中心=(" + viewCenterX + "," + viewCenterY + "), 绘制中心=(" + centerX + "," + centerY + 
                  "), 路径边界=" + pathBounds.toString());
        
        // 从远到近绘制边框层（第4层->第3层->第2层->第1层）
        for (int layerIdx = borderLayerCount - 1; layerIdx >= 0; layerIdx--) {
            canvas.save();
            
            try {
                // 计算当前层的Z轴偏移
                float offsetZ = borderLayerOffsets[layerIdx];
                float renderPlaneZ = 0f;  // 参考项目：rect_render_plane_z_init = 0
                float worldZ = renderPlaneZ - offsetZ;
                
                // 为当前层创建边框路径
                // 所有层都使用相同的尺寸（screenWidth和screenHeight），确保大小一致
                // 通过3D投影的Z轴偏移产生层次感，而不是通过路径缩进
                Path layerBorderPath = screenBorderPath;  // 所有层都使用相同的路径，确保大小一致
                
                Path transformedPath;
                
                // 第一层直接使用原始路径，不应用3D投影，确保对齐屏幕外围
                // 第二三四层应用3D投影，使用相同的路径尺寸，通过Z轴偏移产生层次感
                if (layerIdx == 0) {
                    // 第一层直接使用原始路径，不进行3D变换，确保对齐屏幕外围
                    transformedPath = new Path(layerBorderPath);
                } else {
                    // 第二三四层应用3D投影，跟随陀螺仪产生3D效果
                    // 使用与第一层相同的路径尺寸，确保大小一致
                    // 对边框路径的关键点进行3D投影
                    android.graphics.PathMeasure pathMeasure = new android.graphics.PathMeasure(layerBorderPath, false);
                    float pathLength = pathMeasure.getLength();
                    
                    if (pathLength <= 0) {
                        continue;
                    }
                    
                    // 采样路径上的点（每10像素采样一个点，保证圆角处也平滑）
                    int sampleCount = Math.max(50, (int)(pathLength / 10f));
                    transformedPath = new Path();
                    boolean isFirstPoint = true;
                    
                    for (int i = 0; i <= sampleCount; i++) {
                        float distance = (i / (float)sampleCount) * pathLength;
                        float[] pos = new float[2];
                        float[] tan = new float[2];
                        if (!pathMeasure.getPosTan(distance, pos, tan)) {
                            continue;
                        }
                        
                        // 将视图坐标转换为NDC坐标（-1到1）
                        // 注意：pos是路径坐标，路径是基于screenWidth和screenHeight创建的
                        // 路径的坐标范围是[strokeOffset, screenWidth - strokeOffset]，中心在screenWidth/2
                        // 所以需要使用screenWidth和screenHeight进行NDC转换
                        float ndcX = (pos[0] - viewCenterX) / (screenWidth / 2.0f);
                        float ndcY = -(pos[1] - viewCenterY) / (screenHeight / 2.0f);  // Y轴翻转
                        
                        // 将NDC坐标转换为3D世界坐标（假设在Z=worldZ平面上）
                        float[] worldP = new float[]{ndcX, ndcY, worldZ};
                        
                        // 应用3D投影（与歌词文本相同的算法）
                        float[] eyePos = new float[]{0, 0, EYE_Z};
                        float[] rotatedEye = rotateEuler(eyePos, eulerDiff);
                        
                        // 计算射线方向
                        float[] rayDir = new float[]{
                            worldP[0] - rotatedEye[0],
                            worldP[1] - rotatedEye[1],
                            worldP[2] - rotatedEye[2]
                        };
                        float rayLength = (float) Math.sqrt(
                            rayDir[0] * rayDir[0] + 
                            rayDir[1] * rayDir[1] + 
                            rayDir[2] * rayDir[2]
                        );
                        if (rayLength > 0.0001f) {
                            rayDir[0] /= rayLength;
                            rayDir[1] /= rayLength;
                            rayDir[2] /= rayLength;
                        } else {
                            rayDir = new float[]{0, 0, 1};
                        }
                        
                        // 计算射线与平面的交点
                        float[] planeNormal = new float[]{0, 0, 1};
                        float denom = planeNormal[0] * rayDir[0] + planeNormal[1] * rayDir[1] + planeNormal[2] * rayDir[2];
                        
                        float t = 0;
                        if (Math.abs(denom) > 0.0001f) {
                            float[] planePoint = new float[]{0, 0, 0.01f};
                            float[] toPlane = new float[]{
                                planePoint[0] - rotatedEye[0],
                                planePoint[1] - rotatedEye[1],
                                planePoint[2] - rotatedEye[2]
                            };
                            float numerator = toPlane[0] * planeNormal[0] + toPlane[1] * planeNormal[1] + toPlane[2] * planeNormal[2];
                            t = numerator / denom;
                        }
                        
                        // 计算投影点
                        float[] planeP = new float[]{
                            rotatedEye[0] + t * rayDir[0],
                            rotatedEye[1] + t * rayDir[1],
                            rotatedEye[2] + t * rayDir[2]
                        };
                        planeP[0] *= aspectRatio;
                        
                        // 转换为视图坐标系中的屏幕坐标
                        // 注意：需要将NDC坐标转换回视图坐标系，使用视图中心作为基准
                        // 直接使用3D投影后的坐标，让边框跟随陀螺仪产生3D效果
                        float viewX = viewCenterX + planeP[0] * (screenWidth / 2.0f);
                        float viewY = viewCenterY - planeP[1] * (screenHeight / 2.0f);
                        
                        // 添加到路径
                        if (isFirstPoint) {
                            transformedPath.moveTo(viewX, viewY);
                            isFirstPoint = false;
                        } else {
                            transformedPath.lineTo(viewX, viewY);
                        }
                    }
                    
                    transformedPath.close();
                }
                
                // 计算深度和透明度
                float vDepth = 0.01f - worldZ;  // 简化深度计算
                float fogAmount = 1.0f - (float)Math.exp(-(vDepth - (EYE_Z - 1.0f)) * FOG_K);
                fogAmount = Math.max(0, Math.min(1, fogAmount));
                
                // 应用光照（使用实际的射线方向）
                float[] normal = new float[]{0, 0, 1};
                // 使用从相机到世界点的射线方向计算光照
                float[] lightRayDir = new float[]{0, 0, -1};  // 简化：假设光线从相机方向来
                float dotProduct = normal[0] * (-lightRayDir[0]) + normal[1] * (-lightRayDir[1]) + normal[2] * (-lightRayDir[2]);
                dotProduct = Math.max(0, Math.min(1, dotProduct));
                float lightIntensity = 1.5f;
                float brightness = dotProduct * lightIntensity + 0.1f;
                brightness = Math.max(0.1f, Math.min(1.0f, brightness));
                
                // 计算最终透明度
                float baseAlpha = borderLayerAlpha[layerIdx];
                if (layerIdx > 0) {
                    baseAlpha *= (1.0f - fogAmount * 0.5f);
                }
                int alpha = (int) (baseAlpha * brightness * 255);
                alpha = Math.max(0, Math.min(255, alpha));
                
                int baseColor = 0xFFFFFFFF; // 白色
                
                // 绘制多层光效（参考霓虹灯边框，但不添加呼吸动画）
                // 使用3D投影变换后的路径，所有层都跟随陀螺仪产生3D效果
                Path pathToDraw = transformedPath;  // 使用3D投影变换后的路径，跟随陀螺仪
                
                // 记录实际使用的路径边界，用于调试
                android.graphics.RectF actualBounds = new android.graphics.RectF();
                pathToDraw.computeBounds(actualBounds, true);
                android.graphics.RectF originalBounds = new android.graphics.RectF();
                layerBorderPath.computeBounds(originalBounds, true);
                LogHelper.d(TAG, "📐 边框第" + (layerIdx + 1) + "层路径边界: 原始=" + originalBounds.toString() + 
                          ", 3D变换后=" + actualBounds.toString() + ", Z轴偏移=" + offsetZ + 
                          ", 尺寸变化=" + (actualBounds.width() / originalBounds.width()) + "x" + 
                          (actualBounds.height() / originalBounds.height()));
                
                // 所有层都绘制完整的多层光效，但透明度不同
                // 第一层（最外层光效，最淡、最宽、最模糊）- 15%透明度
                int outerColor1 = (int)(alpha * 0.15f) << 24 | (baseColor & 0x00FFFFFF);
                rectBorderGlowPaint1.setColor(outerColor1);
                canvas.drawPath(pathToDraw, rectBorderGlowPaint1);
                
                // 第二层（中间层光效）- 30%透明度
                int outerColor2 = (int)(alpha * 0.30f) << 24 | (baseColor & 0x00FFFFFF);
                rectBorderGlowPaint2.setColor(outerColor2);
                canvas.drawPath(pathToDraw, rectBorderGlowPaint2);
                
                // 第三层（内层光效，较亮）- 50%透明度
                int outerColor3 = (int)(alpha * 0.50f) << 24 | (baseColor & 0x00FFFFFF);
                rectBorderGlowPaint3.setColor(outerColor3);
                canvas.drawPath(pathToDraw, rectBorderGlowPaint3);
                
                // 第四层（主边框，最亮、最清晰）- 100%透明度
                int borderColor = alpha << 24 | (baseColor & 0x00FFFFFF);
                rectBorderPaint.setColor(borderColor);
                canvas.drawPath(pathToDraw, rectBorderPaint);
                
            } catch (Exception e) {
                LogHelper.e(TAG, "❌ 绘制边框层失败: " + layerIdx, e);
            } finally {
                canvas.restore();
            }
        }
    }
    
    /**
     * 绘制圆形边框（对齐到摄像头位置）
     */
    private void drawCircleBorders(Canvas canvas, int centerX, int centerY,
                                   float screenWidth, float screenHeight, float aspectRatio,
                                   float offset, float renderPlaneZ, int layerCount) {
        // 获取摄像头位置（通过cutout信息）
        Rect cutout = getCutoutInfo();
        
        // 计算摄像头中心位置
        // 如果cutout.top > 0，说明顶部有摄像头
        // 摄像头中心X坐标：屏幕中心（centerX）
        // 摄像头中心Y坐标：cutout.top / 2（摄像头区域的一半位置）
        float[] circlePositions = null;
        
        if (cutout != null && cutout.top > 0) {
            // 有顶部摄像头，计算摄像头中心位置
            // 将屏幕坐标转换为NDC坐标（-1到1）
            // 摄像头中心X：屏幕中心 = centerX，NDC坐标 = 0
            // 摄像头中心Y：cutout.top / 2（从屏幕顶部到cutout.top的一半位置）
            // 注意：在Canvas坐标系中，Y轴向下为正，屏幕顶部Y=0
            // 在NDC坐标系中，Y轴向上为正，屏幕顶部Y=-1，屏幕中心Y=0，屏幕底部Y=1
            // 转换公式：NDC Y = (屏幕Y - centerY) / (screenHeight / 2)，然后取反（因为Canvas Y轴向下）
            float cameraCenterY = cutout.top / 2.0f;  // 摄像头中心Y（屏幕坐标，从顶部开始）
            // 转换为NDC坐标：先计算相对于中心的偏移，然后除以半高，最后取反（因为NDC Y轴向上）
            float cameraCenterYNDC = -(cameraCenterY - centerY) / (screenHeight / 2.0f);
            
            // 两个圆：一个在摄像头中心，一个在对称位置（下方）
            circlePositions = new float[]{
                0f, cameraCenterYNDC,      // 上方圆（摄像头位置）
                0f, -cameraCenterYNDC      // 下方圆（对称位置）
            };
            
            LogHelper.d(TAG, "✅ 使用摄像头位置绘制圆圈: cutout.top=" + cutout.top + 
                      ", 摄像头中心Y(屏幕)=" + cameraCenterY + 
                      ", centerY=" + centerY +
                      ", screenHeight=" + screenHeight +
                      ", 摄像头中心Y(NDC)=" + cameraCenterYNDC);
        } else {
            // 没有cutout信息，使用参考项目的默认位置
            float circleTfPosX = -1.05f;  // 参考项目非P2机型
            float circleTfPosY = 0.478f;   // 参考项目非P2机型
            circlePositions = new float[]{
                circleTfPosX, circleTfPosY,   // 上方圆
                circleTfPosX, -circleTfPosY  // 下方圆（Y坐标取反）
            };
            LogHelper.d(TAG, "⚠️ 未获取到cutout信息，使用默认位置");
        }
        
        // 圆形半径（参考项目：circle scale = 0.34）
        // 根据摄像头大小调整半径，如果cutout.top > 0，使用摄像头大小的一半
        float circleRadius;
        if (cutout != null && cutout.top > 0) {
            // 使用摄像头大小的一半作为半径（假设摄像头是圆形的）
            circleRadius = cutout.top / 2.0f;
            // 限制最小和最大半径
            circleRadius = Math.max(30f, Math.min(circleRadius, screenWidth * 0.2f));
        } else {
            circleRadius = screenWidth * 0.15f;  // 默认半径
        }
        
        // 为每个圆绘制多层边框
        for (int posIdx = 0; posIdx < circlePositions.length; posIdx += 2) {
            float circleX = circlePositions[posIdx];
            float circleY = circlePositions[posIdx + 1];
            
            // 绘制多层圆形边框（从远到近）
            for (int i = layerCount - 1; i >= 0; i--) {
                canvas.save();
                
                try {
                    float offsetZ = offset * i;
                    float worldZ = renderPlaneZ - offsetZ;
                    
                    // 计算3D投影（与文字相同的算法）
                    float[] eyePos = new float[]{0, 0, EYE_Z};
                    float[] rotatedEye = rotateEuler(eyePos, eulerDiff);
                    float[] worldP = new float[]{circleX, circleY, worldZ};
                    
                    // 计算射线方向
                    float[] rayDir = new float[]{
                        worldP[0] - rotatedEye[0],
                        worldP[1] - rotatedEye[1],
                        worldP[2] - rotatedEye[2]
                    };
                    float rayLength = (float) Math.sqrt(
                        rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2]
                    );
                    if (rayLength > 0.0001f) {
                        rayDir[0] /= rayLength;
                        rayDir[1] /= rayLength;
                        rayDir[2] /= rayLength;
                    }
                    
                    // 计算投影点
                    float planeZ = 0.01f;
                    float[] planeNormal = new float[]{0, 0, 1};
                    float denom = planeNormal[0] * rayDir[0] + planeNormal[1] * rayDir[1] + planeNormal[2] * rayDir[2];
                    
                    float t = 0;
                    if (Math.abs(denom) > 0.0001f) {
                        float[] planePoint = new float[]{0, 0, planeZ};
                        float[] toPlane = new float[]{
                            planePoint[0] - rotatedEye[0],
                            planePoint[1] - rotatedEye[1],
                            planePoint[2] - rotatedEye[2]
                        };
                        float numerator = toPlane[0] * planeNormal[0] + toPlane[1] * planeNormal[1] + toPlane[2] * planeNormal[2];
                        t = numerator / denom;
                    }
                    
                    float[] planeP = new float[]{
                        rotatedEye[0] + t * rayDir[0],
                        rotatedEye[1] + t * rayDir[1],
                        rotatedEye[2] + t * rayDir[2]
                    };
                    planeP[0] *= aspectRatio;
                    
                    // 计算屏幕坐标
                    // 参考项目：plane_p.x *= uResolution.y / uResolution.x 已经在前面应用
                    // NDC坐标(-1到1) -> 屏幕坐标(0到width)
                    float screenX = centerX + planeP[0] * (screenWidth / 2.0f);
                    float screenY = centerY - planeP[1] * (screenHeight / 2.0f); // Y轴翻转
                    
                    // 计算深度和透明度
                    float vDepth = planeP[2] - worldZ;
                    float alpha = Math.max(0.1f, Math.min(1.0f, 1.0f - vDepth * 0.1f));
                    
                    // 应用光照（严格按照shader逻辑）
                    // l = clamp(dot(NORMAL, -VRayDir), 0.0, 1.0) * LightIntensity + 0.1
                    float[] normal = new float[]{0, 0, 1};
                    float dotProduct = normal[0] * (-rayDir[0]) + normal[1] * (-rayDir[1]) + normal[2] * (-rayDir[2]);
                    dotProduct = Math.max(0, Math.min(1, dotProduct));
                    float lightIntensity = 2.5f;
                    float brightness = dotProduct * lightIntensity + 0.1f;
                    brightness = Math.max(0.1f, Math.min(1.0f, brightness));
                    
                    // 应用雾化效果（参考项目fragment shader）
                    float fogAmount = 1.0f - (float)Math.exp(-(vDepth - (EYE_Z - 1.0f)) * FOG_K);
                    fogAmount = Math.max(0, Math.min(1, fogAmount));
                    
                    // 计算基础透明度（参考霓虹边框：使用固定的透明度比例）
                    // 后层（i > 0）应用深度衰减，但保持可见
                    float baseAlphaFactor = 1.0f;
                    if (i > 0) {
                        // 后层根据深度衰减，但保持最小可见度
                        baseAlphaFactor = Math.max(0.3f, 1.0f - fogAmount * 0.5f);
                    }
                    int baseAlpha = (int) (brightness * baseAlphaFactor * 255);
                    baseAlpha = Math.max(0, Math.min(255, baseAlpha));
                    int baseColor = 0xFFFFFFFF; // 白色
                    
                    // 使用从3D投影计算得到的屏幕坐标（circleX和circleY已经在前面从circlePositions获取，但这里使用screenX和screenY）
                    // 注意：由于圆形边框已禁用，这段代码实际上不会执行
                    float finalCircleX = screenX;
                    float finalCircleY = screenY;
                
                    // 参考霓虹边框：多层叠加实现真实光效（严格按照MarqueeLightView的实现）
                    // 注意：由于圆形边框已禁用，这段代码实际上不会执行
                    // 第一层：最外层光效（最淡、最宽、最模糊）- 15%透明度
                    int outerColor1 = (int)(baseAlpha * 0.15f) << 24 | (baseColor & 0x00FFFFFF);
                    circleBorderGlowPaint1.setColor(outerColor1);
                    canvas.drawCircle(finalCircleX, finalCircleY, circleRadius, circleBorderGlowPaint1);
                    
                    // 第二层：中间层光效 - 30%透明度
                    int outerColor2 = (int)(baseAlpha * 0.30f) << 24 | (baseColor & 0x00FFFFFF);
                    circleBorderGlowPaint2.setColor(outerColor2);
                    canvas.drawCircle(finalCircleX, finalCircleY, circleRadius, circleBorderGlowPaint2);
                    
                    // 第三层：内层光效（较亮）- 50%透明度
                    int outerColor3 = (int)(baseAlpha * 0.50f) << 24 | (baseColor & 0x00FFFFFF);
                    circleBorderGlowPaint3.setColor(outerColor3);
                    canvas.drawCircle(finalCircleX, finalCircleY, circleRadius, circleBorderGlowPaint3);
                    
                    // 第四层：主边框（最亮、最清晰）- 100%透明度
                    int borderColor = baseAlpha << 24 | (baseColor & 0x00FFFFFF);
                    circleBorderPaint.setColor(borderColor);
                    canvas.drawCircle(finalCircleX, finalCircleY, circleRadius, circleBorderPaint);
                    
                } catch (Exception e) {
                    LogHelper.e(TAG, "❌ 绘制圆形边框层失败: " + i, e);
                } finally {
                    canvas.restore();
                }
            }
        }
    }
    
    /**
     * 欧拉角旋转向量（严格按照参考项目shader的rotateEuler函数）
     * 参考项目shader：
     *   mat3 rotX = mat3(
     *     vec3(1.0, 0.0, 0.0),
     *     vec3(0.0, cx, -sx),
     *     vec3(0.0, sx, cx)
     *   );
     *   mat3 rotY = mat3(
     *     vec3(cy, 0.0, sy),
     *     vec3(0.0, 1.0, 0.0),
     *     vec3(-sy, 0.0, cy)
     *   );
     *   mat3 rotZ = mat3(
     *     vec3(cz, -sz, 0.0),
     *     vec3(sz, cz, 0.0),
     *     vec3(0.0, 0.0, 1.0)
     *   );
     *   return rotZ * rotY * rotX * v;
     */
    private float[] rotateEuler(float[] v, float[] euler) {
        float cx = (float) Math.cos(euler[0]);
        float sx = (float) Math.sin(euler[0]);
        float cy = (float) Math.cos(euler[1]);
        float sy = (float) Math.sin(euler[1]);
        float cz = (float) Math.cos(euler[2]);
        float sz = (float) Math.sin(euler[2]);
        
        // 严格按照shader的矩阵定义
        float x = v[0];
        float y = v[1];
        float z = v[2];
        
        // rotX * v
        float x1 = x;
        float y1 = y * cx - z * sx;
        float z1 = y * sx + z * cx;
        
        // rotY * (rotX * v)
        float x2 = x1 * cy + z1 * sy;
        float y2 = y1;
        float z2 = -x1 * sy + z1 * cy;
        
        // rotZ * (rotY * rotX * v)
        float x3 = x2 * cz - y2 * sz;
        float y3 = x2 * sz + y2 * cz;
        float z3 = z2;
        
        return new float[]{x3, y3, z3};
    }
    
    
    /**
     * 绘制占位文本
     */
    private void drawPlaceholder(Canvas canvas) {
        String text = "暂无歌词";
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        
        Paint placeholderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        placeholderPaint.setTextSize(textSize);
        placeholderPaint.setColor(0x66FFFFFF); // 半透明白色
        placeholderPaint.setTextAlign(Paint.Align.CENTER);
        placeholderPaint.setTypeface(typeface);
        
        // 调整y坐标为基线位置
        Paint.FontMetrics fm = placeholderPaint.getFontMetrics();
        float textY = centerY - (fm.ascent + fm.descent) / 2; // 垂直居中
        
        canvas.drawText(text, centerX, textY, placeholderPaint);
    }
    
    /**
     * 设置歌词数据
     */
    public void setLyricLines(List<EnhancedLRCParser.EnhancedLyricLine> lines) {
        this.lyricLines = lines != null ? lines : new ArrayList<>();
        // 如果歌词不为空且当前索引无效，设置为第一行
        if (!this.lyricLines.isEmpty() && (currentLineIndex < 0 || currentLineIndex >= this.lyricLines.size())) {
            currentLineIndex = 0;
            LogHelper.d(TAG, "✅ 设置歌词数据: " + this.lyricLines.size() + " 行, 自动设置当前行为第一行");
        } else if (this.lyricLines.isEmpty()) {
            currentLineIndex = -1;
            LogHelper.d(TAG, "⚠️ 歌词数据为空，清除当前行索引");
        } else {
            LogHelper.d(TAG, "✅ 设置歌词数据: " + this.lyricLines.size() + " 行, 保持当前行索引=" + currentLineIndex);
        }
        // 标记需要重绘（由Choreographer统一处理，避免频繁重绘）
        needsRedraw = true;
    }
    
    /**
     * 设置当前行索引
     */
    public void setCurrentLineIndex(int index) {
        // 首先检查歌词数据是否有效
        if (lyricLines == null || lyricLines.isEmpty()) {
            // 如果没有歌词数据，重置索引为-1
            if (currentLineIndex != -1) {
                LogHelper.w(TAG, "⚠️ 歌词数据为空，重置当前行索引: " + currentLineIndex + " -> -1");
                currentLineIndex = -1;
                needsRedraw = true;
            }
            return;
        }
        
        // 验证索引有效性
        if (index < 0 || index >= lyricLines.size()) {
            if (index != currentLineIndex) {
                LogHelper.w(TAG, "⚠️ 无效的行索引: " + index + ", 歌词行数=" + lyricLines.size() + 
                          ", 自动调整为有效索引");
                // 自动调整为有效索引
                if (index < 0) {
                    index = 0;  // 负数时设为第一行
                } else if (index >= lyricLines.size()) {
                    index = lyricLines.size() - 1;  // 超出范围时设为最后一行
                }
            } else {
                // 索引无效且与当前相同，尝试修复当前索引
                if (currentLineIndex < 0 || currentLineIndex >= lyricLines.size()) {
                    LogHelper.w(TAG, "⚠️ 当前索引无效，自动修复: " + currentLineIndex + " -> 0");
                    index = 0;
                } else {
                    return;  // 索引无效但当前索引有效，不更新
                }
            }
        }
        
        if (index != currentLineIndex) {
            int oldIndex = currentLineIndex;
            this.currentLineIndex = index;
            LogHelper.d(TAG, "🔄 当前行索引更新: " + oldIndex + " -> " + index + 
                      (index >= 0 && index < lyricLines.size() ? 
                       ", 歌词: " + lyricLines.get(index).text : ""));
            // 标记需要重绘（由Choreographer统一处理，避免频繁重绘）
            needsRedraw = true;
        }
    }
    
    /**
     * 设置文本大小
     */
    public void setTextSize(float size) {
        this.textSize = size;
        textPaint.setTextSize(size);
        // 所有层使用相同大小（参考项目）
        for (int i = 0; i < LAYER_COUNT; i++) {
            layerPaints[i].setTextSize(size);
        }
        // 标记需要重绘（由Choreographer统一处理，避免频繁重绘）
        needsRedraw = true;
    }
    
    /**
     * 设置文本颜色
     */
    public void setTextColor(int color) {
        this.textColor = color;
        textPaint.setColor(color);
        for (int i = 0; i < LAYER_COUNT; i++) {
            int alpha = (int) (LAYER_ALPHA[i] * 255);
            layerPaints[i].setColor(color);
            layerPaints[i].setAlpha(alpha);
        }
        // 标记需要重绘（由Choreographer统一处理，避免频繁重绘）
        needsRedraw = true;
    }
    
    /**
     * 设置旋转强度
     */
    public void setRotationIntensity(float intensity) {
        this.rotationIntensity = Math.max(0, Math.min(1, intensity));
    }
    
    /**
     * 获取旋转强度
     */
    public float getRotationIntensity() {
        return rotationIntensity;
    }
    
    /**
     * 获取文本颜色
     */
    public int getTextColor() {
        return textColor;
    }
    
    /**
     * 获取摄像头位置信息（cutout）
     */
    private Rect getCutoutInfo() {
        try {
            // 从DisplayInfoCache获取cutout信息
            if (DisplayInfoCache.getInstance().isInitialized()) {
                RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
                if (info != null && info.hasCutout()) {
                    return info.cutout;
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "⚠️ 获取cutout信息失败", e);
        }
        return null;
    }
}
