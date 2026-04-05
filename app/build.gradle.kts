plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** 与 `res/values/strings.xml` 中 `app_name` 同步；也可改为手写常量。 */
fun appLabelFromStrings(): String {
    val xml = file("src/main/res/values/strings.xml").readText()
    return Regex("""<string name="app_name">\s*([^<]+?)\s*</string>""")
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: "MiRoot"
}

/** 定位 packageRelease 产物：AGP 9 未签名时常为 app-release-unsigned.apk，签名后为 app-release.apk。 */
fun findPackagedReleaseApk(releaseDir: File): File {
    val meta = File(releaseDir, "output-metadata.json")
    if (meta.exists()) {
        val name = Regex(""""outputFile"\s*:\s*"([^"]+\.apk)"""")
            .find(meta.readText())
            ?.groupValues
            ?.get(1)
        if (name != null) {
            val fromMeta = File(releaseDir, name)
            if (fromMeta.exists()) return fromMeta
        }
    }
    sequenceOf("app-release.apk", "app-release-unsigned.apk")
        .map { File(releaseDir, it) }
        .firstOrNull { it.exists() }
        ?.let { return it }
    throw org.gradle.api.GradleException(
        "未在 ${releaseDir.absolutePath} 找到 release APK（已检查 output-metadata.json 与常见文件名）"
    )
}

android {
    namespace = "com.wmqc.miroot"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.wmqc.miroot"
        // Android 16 = API 36；minSdk 勿与 target 相同，否则仅 16+ 设备可安装
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            // R8：代码压缩、优化与混淆（须配合 proguard-rules.pro）
            isMinifyEnabled = true
            // 资源压缩：在 minify 之后移除未引用资源
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        compose = true
        aidl = true
        buildConfig = true
    }

    // 带壳底图统一在 assets/shell/；勿再打入 flutter_assets、rear_shell 等重复目录
    packaging {
        resources {
            excludes += "/flutter_assets/**"
            excludes += "/rear_shell/**"
        }
    }
}

// 仓库根目录 assets/car → app/src/main/assets/car，随 preBuild 打入 APK（与 CarControlAssets.ASSET_DIR 一致）
val carControlAssetsSource = rootProject.layout.projectDirectory.dir("assets/car")
val carControlAssetsTarget = layout.projectDirectory.dir("src/main/assets/car")
val syncCarControlAssets by tasks.registering(Copy::class) {
    group = "build"
    description = "同步 assets/car 到 app/src/main/assets/car，供车控投屏打包"
    from(carControlAssetsSource)
    into(carControlAssetsTarget)
    include("**/*")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    onlyIf { carControlAssetsSource.asFile.exists() }
}
tasks.named("preBuild").configure { dependsOn(syncCarControlAssets) }

/**
 * assembleRelease：在 packageRelease 之后生成分发包，文件名为「应用名_版本号_Release.apk」。
 * 依赖 assemble*（而非仅 package*），避免 package 为 UP-TO-DATE 时 doLast 不跑导致分发包缺失。
 */
afterEvaluate {
    val labelRaw = appLabelFromStrings()
    val safeLabel = labelRaw.replace(Regex("""[\\/:*?"<>|]"""), "_")
    val ver = android.defaultConfig.versionName ?: "0"
    val releaseApkName = "${safeLabel}_${ver}_Release.apk"

    tasks.register("namedDebugApk") {
        dependsOn("packageDebug")
        doLast {
            val dir = layout.buildDirectory.get().asFile.resolve("outputs/apk/debug")
            val src = File(dir, "app-debug.apk")
            val dst = File(dir, "${safeLabel}_${ver}_debug.apk")
            if (src.exists()) src.copyTo(dst, overwrite = true)
        }
    }
    tasks.register("namedReleaseApk") {
        group = "build"
        description = "复制 release APK 为 ${releaseApkName}（应用名_版本号_Release）"
        dependsOn("packageRelease")
        doLast {
            val dir = layout.buildDirectory.get().asFile.resolve("outputs/apk/release")
            val src = findPackagedReleaseApk(dir)
            val dst = File(dir, releaseApkName)
            src.copyTo(dst, overwrite = true)
        }
    }
    tasks.named("assembleDebug") { dependsOn("namedDebugApk") }
    tasks.named("assembleRelease") { dependsOn("namedReleaseApk") }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.compose.runtime:runtime-livedata")
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
