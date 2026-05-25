plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
    signing
}

val apiVersion = "1.0.0"

android {
    namespace = "com.wmqc.miroot.api"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.wmqc.miroot"
                artifactId = "miroot-api"
                version = apiVersion
                from(components["release"])

                pom {
                    name.set("miroot-api")
                    description.set("MiRoot rear screen public API")
                    inceptionYear.set("2026")
                    url.set("https://github.com/wmqc/MiRoot")
                    licenses {
                        license {
                            name.set("GNU Lesser General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                            distribution.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                        }
                    }
                    developers {
                        developer {
                            id.set("wmqc")
                            name.set("wmqc")
                            url.set("https://github.com/wmqc")
                        }
                    }
                    scm {
                        url.set("https://github.com/wmqc/MiRoot")
                        connection.set("scm:git:git://github.com/wmqc/MiRoot.git")
                        developerConnection.set("scm:git:ssh://git@github.com/wmqc/MiRoot.git")
                    }
                }
            }
        }
    }

    signing {
        val needSign = gradle.startParameter.taskNames.any {
            it.contains("publish", ignoreCase = true) ||
                it.contains("sign", ignoreCase = true)
        }
        isRequired = needSign
        if (needSign) {
            useGpgCmd()
            sign(publishing.publications)
        }
    }
}
