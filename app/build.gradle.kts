import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** 从 `local.properties`、项目属性或环境变量读取 release 签名配置（密码勿写入版本库）。 */
private fun Project.loadSigningProperties(): Properties {
    val p = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { p.load(it) }
    return p
}

private fun Project.signingProp(key: String, local: Properties): String? =
    local.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: (findProperty(key) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }

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
val signLocal = loadSigningProperties()
/** 爱发电开放接口（写入 local.properties 的 AFDIAN_USER_ID / AFDIAN_TOKEN，勿提交仓库）。 */
val afdianUserId = signingProp("AFDIAN_USER_ID", signLocal) ?: ""
val afdianToken = signingProp("AFDIAN_TOKEN", signLocal) ?: ""
fun escapeForBuildConfigValue(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

val keystorePath = signingProp("KEYSTORE_FILE", signLocal)
val keystoreFile = keystorePath?.let { file(it) }
val keystoreStorePassword = signingProp("KEYSTORE_PASSWORD", signLocal)
val keystoreKeyAlias = signingProp("KEY_ALIAS", signLocal)
val keystoreKeyPassword = signingProp("KEY_PASSWORD", signLocal) ?: keystoreStorePassword
val hasReleaseSigning =
    keystoreFile != null && keystoreFile.isFile &&
        keystoreStorePassword != null && keystoreKeyAlias != null && keystoreKeyPassword != null

fun sha256Hex(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { b -> "%02X".format(b) }
}

/** 从 release keystore 计算证书 SHA-256（X509 证书编码）。 */
val releaseCertSha256: String = runCatching {
    if (!hasReleaseSigning) return@runCatching ""
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(keystoreFile!!.inputStream(), keystoreStorePassword!!.toCharArray())
    val cert = ks.getCertificate(keystoreKeyAlias!!)
    if (cert == null) return@runCatching ""
    // cert.encoded 在 Java 安全证书中应为 DER 编码字节。
    sha256Hex(cert.encoded)
}.getOrDefault("")

fun findPackagedReleaseApk(releaseDir: File): File {
    if (!releaseDir.isDirectory) {
        throw org.gradle.api.GradleException("Release 输出目录不存在: ${releaseDir.absolutePath}")
    }
    for (dir in sequenceOf(releaseDir, releaseDir.parentFile).filterNotNull()) {
        val meta = File(dir, "output-metadata.json")
        if (!meta.exists()) continue
        val name = Regex(""""outputFile"\s*:\s*"([^"]+\.apk)"""")
            .find(meta.readText())
            ?.groupValues
            ?.get(1)
        if (name != null) {
            val fromMeta = File(dir, name)
            if (fromMeta.exists()) return fromMeta
            val nested = File(releaseDir, name)
            if (nested.exists()) return nested
        }
    }
    sequenceOf("app-release.apk", "app-release-unsigned.apk")
        .map { File(releaseDir, it) }
        .firstOrNull { it.exists() }
        ?.let { return it }
    val candidates = releaseDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        .filter { f ->
            val n = f.name.lowercase()
            !n.contains("androidtest") && !n.contains("debug") && !n.contains("androidTest".lowercase())
        }
        .sortedByDescending { f ->
            when {
                f.name == "app-release.apk" -> 100
                f.name.endsWith("-release.apk") -> 90
                f.name.contains("unsigned") -> 10
                else -> 50
            }
        }
        .toList()
    candidates.firstOrNull()?.let { return it }
    throw org.gradle.api.GradleException(
        "未在 ${releaseDir.absolutePath} 找到 release APK（已检查 metadata、常见文件名与子目录；" +
            "若目录为空请先确认 assembleRelease 是否成功完成）",
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
        versionCode = 26
        versionName = "1.9.2"

        manifestPlaceholders["superlyricApiVersionName"] = "3.4"
        manifestPlaceholders["superlyricApiVersionCode"] = "34"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "AFDIAN_USER_ID", "\"${escapeForBuildConfigValue(afdianUserId)}\"")
        buildConfigField("String", "AFDIAN_TOKEN", "\"${escapeForBuildConfigValue(afdianToken)}\"")
        // 正式包签名校检：运行时从已安装包取证书 SHA-256 与该常量比对；不一致直接退出。
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"${releaseCertSha256}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile!!
                storePassword = keystoreStorePassword!!
                keyAlias = keystoreKeyAlias!!
                keyPassword = keystoreKeyPassword!!
            }
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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

    // Debug/全量 build 不因历史 Lint 误报中断；Release 仍走 lintVital。
    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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

// 仓库根目录 assets/ditu → app/src/main/assets/shell，随 preBuild 打入 APK（与 DeviceModelHelper.SHELL_ASSETS_PREFIX 一致）
// 源文件为 WebP 格式，运行时仅加载 *.webp（见 DeviceModelHelper.getPhoneBackImageFileName）
val shellAssetsSource = rootProject.layout.projectDirectory.dir("assets/ditu")
val shellAssetsTarget = layout.projectDirectory.dir("src/main/assets/shell")
val syncShellAssets by tasks.registering(Copy::class) {
    group = "build"
    description = "同步带壳底图 assets/ditu 到 app/src/main/assets/shell（pro*.webp）"
    from(shellAssetsSource)
    into(shellAssetsTarget)
    include("**/*.webp")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    onlyIf { shellAssetsSource.asFile.exists() }
}

tasks.named("preBuild").configure {
    dependsOn(syncCarControlAssets)
    dependsOn(syncShellAssets)
}

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
            logger.lifecycle("Release 分发包: ${dst.absolutePath}")
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.runtime.livedata)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.miuix.kmp)
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
    implementation(libs.okhttp)
    implementation(libs.jieba.android)
    implementation("com.github.HChenX:SuperLyricApi:3.4")
    testImplementation(libs.junit)
    // JVM 单元测试中 android.jar 的 org.json 为桩，JSONObject.put 等会抛「not mocked」；测试用真实实现
    testImplementation(libs.json.org)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
