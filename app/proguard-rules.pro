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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Shizuku Binder / provider
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# App entry、Fragment、ViewBinding（Release 混淆时避免闪退）
-keep class com.wmqc.miroot.MainActivity { *; }
-keep class com.wmqc.miroot.ui.** { *; }
-keep class com.wmqc.miroot.databinding.** { *; }
-keep class com.wmqc.miroot.viewmodel.** { *; }
-keep class com.wmqc.miroot.capability.** { *; }
-keep class com.wmqc.miroot.service.** { *; }
-keep class com.wmqc.miroot.shell.** { *; }
-keep class com.wmqc.miroot.record.** { *; }
-keep class com.wmqc.miroot.charging.** { *; }
-keep class com.wmqc.miroot.car.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod