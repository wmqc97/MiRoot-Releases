# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留行号与源文件名占位，便于 Release 崩溃经 mapping.txt 反混淆后定位到行（上传 Play / Firebase 等时请一并上传 mapping）。
# 若需进一步隐藏真实文件名，可保留下一行 -renamesourcefileattribute（堆栈中源文件名将显示为 SourceFile）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku Binder / provider
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Manifest 中的 Activity/Service/Receiver 等由 AGP 合并清单生成保留规则，无需再整包 keep `com.wmqc.miroot.ui.**`。
# ViewBinding 生成类（仅 databinding 包，不再整包 keep `com.wmqc.miroot.ui.**`）。
-keep class com.wmqc.miroot.databinding.** implements androidx.viewbinding.ViewBinding { *; }

# 其余应用代码依赖 R8 收缩与混淆；若某处反射/ JNI 在 Release 出问题，再对该类或包补细粒度 -keep。
# 第三方库若自带 consumer ProGuard 规则，会随 AAR 自动合并，一般无需在此重复整包 keep。

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# SuperLyricApi（官方 README 强烈建议，勿删）
# Binder / AIDL / 数据模型类名被混淆会导致 registerReceiver、onLyric 回调异常。
-keep class com.hchen.superlyricapi.** { *; }
-dontwarn com.hchen.superlyricapi.**
-dontwarn android.os.ServiceManager

# jieba 分词（词典在 AAR 内，类名勿混淆以免反射/序列化异常）
-keep class com.huaban.analysis.jieba.** { *; }

# Kuwo car edition broadcast lyrics bridge (LYRIC_FULL / LYRIC_PROGRESS)
-keep class com.wmqc.miroot.lyrics.KuwoBroadcastLyricBridge { *; }
-keep class com.wmqc.miroot.lyrics.KuwoBroadcastLyricParser { *; }
-keep class com.wmqc.miroot.lyrics.KuwoBroadcastLyricReceiver { *; }
-keep class com.wmqc.miroot.lyrics.KuwoBroadcastLyricReceiver$Listener { *; }
-keep class com.wmqc.miroot.lyrics.KuwoCarLyricsPolicy { *; }
-keep class com.wmqc.miroot.lyrics.KuwoAudioLyricParser { *; }
-keepclassmembers class com.wmqc.miroot.lyrics.RearScreenLyricsActivity {
    private static com.wmqc.miroot.lyrics.RearScreenLyricsActivity currentInstance;
    private com.wmqc.miroot.lyrics.ModernLyricsView lyricsView;
    private com.wmqc.miroot.lyrics.ITaskService taskService;
    private com.wmqc.miroot.lyrics.KuwoBroadcastLyricBridge kuwoBroadcastLyricBridge;
    private boolean kuwoBroadcastWordTimestampsApplied;
    public void applyKuwoBroadcastLyrics(java.util.List);
    public void applyKuwoBroadcastProgress(long, boolean, int, int, int);
}

# Kuwo broadcast word hint fields+method in ModernLyricsView
-keepclassmembers class com.wmqc.miroot.lyrics.ModernLyricsView {
    public void setKuwoWordHighlightHint(int, int, int);
    boolean mKuwoWordHintValid;
    int mKuwoWordHintLineIndex;
    int mKuwoWordHintCharStart;
    int mKuwoWordHintCharEnd;
    long mKuwoWordHintTimestamp;
}
-keepclassmembers class com.wmqc.miroot.car.RearScreenCarControlActivity {
    private static com.wmqc.miroot.car.RearScreenCarControlActivity currentInstance;
    private android.widget.RelativeLayout mainLayout;
    private com.wmqc.miroot.lyrics.ITaskService taskService;
}
